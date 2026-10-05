package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every status code Nova Poshta publishes, decided one at a time.
 *
 * The owner compared the carrier's own tracker with this app and asked not only
 * that today's status match but that it keep matching as the parcel moves on. A
 * parcel walks through perhaps six of these codes, so the rest arrive on a day
 * nobody is looking — and before this, a code nobody had thought about fell
 * through `stageForStatusCode`'s `else` and silently changed nothing at all.
 *
 * So the table below is the ladder, not a sample of it: each of the carrier's
 * twenty-one codes is written out with the stage it means and whether it is
 * trouble, and [`every published code is accounted for`] fails if the app's own
 * [NOVA_POSHTA_STATUS_CODES] and this table ever stop agreeing. Adding a code to
 * the app without deciding what it means here is not possible.
 *
 * Source: Nova Poshta's published tracking status set, cross-checked against the
 * live `TrackingDocument.getStatusDocuments` response for a real parcel (code 6).
 * Note that `Common.getDocumentStatuses` is a *different* dictionary — twenty-one
 * `StateId` values 0..20 describing an internet document's internal state — and
 * reconciling this ladder against that one would be wrong: their numbers overlap
 * and their meanings do not.
 */
class StatusLadderTest {

    /** code, what the carrier means by it, the stage it maps to, is it trouble. */
    private data class Rung(
        val code: Int,
        val meaning: String,
        val stage: String,
        val problem: Boolean
    )

    private val ladder = listOf(
        Rung(1, "Відправник створив ЕН, але не передав відправлення", ORDERED, false),
        Rung(2, "Видалено", "", true),
        Rung(3, "Номер не знайдено", "", true),
        Rung(4, "Відправлення у місті відправника (міжміська)", IN_TRANSIT, false),
        Rung(5, "Прямує до міста одержувача", IN_TRANSIT, false),
        Rung(6, "У місті одержувача, очікуйте повідомлення про прибуття", IN_TRANSIT, false),
        Rung(7, "Прибуло у відділення", AT_BRANCH, false),
        Rung(8, "Прибуло у відділення (завантажено в поштомат)", AT_BRANCH, false),
        Rung(9, "Відправлення отримано", RECEIVED, false),
        Rung(10, "Отримано, очікується грошовий переказ", RECEIVED, false),
        // The one that used to send the rail back to its first dot, and only ever
        // on a parcel carrying money: this is the end of a cash-on-delivery
        // journey, not the beginning of anything.
        Rung(11, "Отримано, грошовий переказ видано", RECEIVED, false),
        // The one that used to be announced in red. Nova Poshta is packing it.
        Rung(12, "Нова Пошта комплектує відправлення", ORDERED, false),
        // Was missing entirely, so a same-city parcel never advanced on its own.
        Rung(41, "Відправлення у місті (місцева доставка)", IN_TRANSIT, false),
        Rung(101, "На шляху до одержувача (кур'єр)", IN_TRANSIT, false),
        Rung(102, "Відмова: відправник створив замовлення на повернення", "", true),
        Rung(103, "Одержувач відмовився від відправлення", "", true),
        Rung(104, "Змінено адресу", IN_TRANSIT, false),
        Rung(105, "Припинено зберігання", "", true),
        Rung(106, "Отримано, створено зворотну доставку", RECEIVED, false),
        // Was missing from the ladder and from the problem set, so a parcel a
        // courier failed to hand over went on reading as one still on its way.
        Rung(111, "Невдала спроба доставки: нікого не застали", "", true),
        Rung(112, "Дата доставки перенесена одержувачем", IN_TRANSIT, false)
    )

    @Test
    fun `every published code is accounted for`() {
        assertEquals(
            "the ladder under test and the app's own code list must not drift apart",
            NOVA_POSHTA_STATUS_CODES.sorted(),
            ladder.map { it.code }.sorted()
        )
        assertEquals("no code may appear twice", ladder.size, ladder.map { it.code }.toSet().size)
    }

    @Test
    fun `every code maps to the stage the carrier means by it`() {
        for (rung in ladder) {
            assertEquals(
                "code ${rung.code} — ${rung.meaning}",
                rung.stage,
                stageForStatusCode(rung.code)
            )
        }
    }

    @Test
    fun `every code is decided to be trouble or not`() {
        for (rung in ladder) {
            assertEquals(
                "code ${rung.code} — ${rung.meaning}",
                rung.problem,
                isProblemCode(rung.code)
            )
        }
    }

    @Test
    fun `a stage and a problem are never claimed at once`() {
        // A parcel cannot both have advanced and be stuck. Where the carrier says
        // trouble the rail must keep whatever dot the person last had, which is
        // what an empty stage means to applyStatus.
        for (rung in ladder) {
            if (isProblemCode(rung.code)) {
                assertEquals("code ${rung.code}", "", stageForStatusCode(rung.code))
            }
        }
    }

    @Test
    fun `every stage a code produces is one the rail can draw`() {
        for (rung in ladder) {
            val stage = stageForStatusCode(rung.code)
            if (stage.isNotEmpty()) {
                assertTrue("code ${rung.code} produced $stage", stage in PARCEL_STAGES)
            }
        }
    }

    @Test
    fun `each trouble code says which trouble it is`() {
        val general = problemNote(0)
        val said = mutableSetOf<String>()
        for (rung in ladder.filter { it.problem }) {
            val note = problemNote(rung.code)
            assertFalse(
                "code ${rung.code} still falls back to the general sentence",
                note == general
            )
            assertTrue("code ${rung.code} says nothing at all", note.isNotBlank())
            assertTrue("code ${rung.code} repeats another code's sentence", said.add(note))
        }
        assertEquals(6, said.size)
    }

    @Test
    fun `a code with no trouble to report is never given a trouble sentence`() {
        // The note is only ever drawn behind the problem flag, so what matters is
        // that a code which is not trouble never gets a sentence of its own that
        // could leak onto a healthy parcel through some future caller.
        for (rung in ladder.filter { !it.problem }) {
            assertEquals("code ${rung.code}", problemNote(0), problemNote(rung.code))
        }
    }

    @Test
    fun `a parcel restored without a code still explains itself`() {
        // Parcels saved before the code was stored carry the flag and a zero, and
        // the old general wording is vague but true.
        assertEquals(
            "Потрібна увага: перевірте номер або статус у перевізника",
            problemNote(0)
        )
        assertEquals(problemNote(0), problemNote(999))
    }

    @Test
    fun `the page says why the carrier's own app shows more than this one`() {
        // The public response carries the current status and no movement list, so
        // stops the carrier's app shows as timestamped events cannot appear here
        // however right the ladder is. The page names both halves of that, or a
        // correct app reads as a broken one when the two are held side by side.
        assertTrue(NO_STOPS_NOTE.contains("Нової Пошти"))
        assertTrue(NO_STOPS_NOTE.contains("сортувальний центр"))
        assertTrue(NO_STOPS_NOTE.contains("не вигадує"))
    }

    @Test
    fun `the rail is told to stop reading as progress when a parcel is stuck`() {
        // Not a redesign of the rail — the dot stays where the parcel genuinely
        // last was — but the one sentence that stops the dot meaning "fine here".
        assertTrue(STAGE_HELD_NOTE.contains("справді була"))
        assertTrue(STAGE_HELD_NOTE.contains("Далі вона не пішла"))
    }
}

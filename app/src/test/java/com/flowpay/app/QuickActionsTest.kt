package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * «Сплачено» from the widget and from the morning message.
 *
 * Nothing here can be tried on the phone by whoever changes it, and the two ways
 * it fails are both silent: a mark for the wrong month (the widget keeps showing
 * what was just paid, or a past month is quietly marked), and a mark lost because
 * the open app saved its older list over it (HANDOFF §15). Both are pinned here.
 */
class QuickActionsTest {

    // 2026: 1 October is a Thursday, so the 20th is a Tuesday, the 30th a Friday
    // and 1 November a Sunday.
    private val oct20 = LocalDate.of(2026, 10, 20)
    private val oct30 = LocalDate.of(2026, 10, 30)
    private val oct = "2026-10"
    private val nov = "2026-11"

    private val rent = Pay("Оренда", 8_000.0, day = 1)
    private val internet = Pay("Інтернет", 300.0, day = 5)

    // ------------------------------------------------------------ which month

    @Test
    fun `inside the notice period the month is the one the app's own tick marks`() {
        // Every reminder over two months of days, across rhythms the app knows:
        // monthly, a weekend shift, a long notice, a trial, an annual fee, a plan.
        val pays = listOf(
            rent,
            internet.copy(warnDays = 3),
            Pay("Спортзал", 900.0, day = 31, warnDays = 0),
            Pay("Netflix", 249.0, day = 3, warnDays = 3, trialEnd = LocalDate.of(2026, 11, 3).toEpochDay()),
            Pay("Домен", 600.0, day = 10, warnDays = 7, billingMonth = 11),
            Pay("Телефон", 2_000.0, day = 12, warnDays = 1, instalments = 6, instalmentStart = LocalDate.of(2026, 9, 12).toEpochDay())
        )
        var checked = 0
        for (offset in 0L until 62L) {
            val today = LocalDate.of(2026, 10, 1).plusDays(offset)
            remindersDue(pays, today).forEach { reminder ->
                assertEquals(
                    "${reminder.pay.name} on $today",
                    tickMonth(reminder.pay, today),
                    chargeMonth(reminder.pay, today)
                )
                checked++
            }
        }
        assertTrue("the loop has to have looked at something", checked > 20)
    }

    @Test
    fun `outside the notice period the widget marks the charge it shows, not the one behind`() {
        // On the 20th the screen's tick for rent paid on the 1st answers for
        // October; the widget is showing November's rent.
        assertEquals(oct, tickMonth(rent, oct20))
        assertEquals(nov, chargeMonth(rent, oct20))
    }

    @Test
    fun `after the widget's mark the widget shows the next payment`() {
        val pays = listOf(rent, internet)
        val next = nextPayment(stillOwing(pays, emptyList(), oct20), oct20, 41.0)
        val tick = widgetTick(next, oct20) as WidgetTick.Mark
        assertEquals(QuickMark("Оренда", nov), tick.mark)

        val marks = quickMarked(emptyList(), pays, tick.mark).marks
        val after = nextPayment(stillOwing(pays, marks, oct20), oct20, 41.0)

        assertEquals(listOf("Інтернет"), after?.items?.map { it.name })
        // And October's own mark — the screen's tick on the 20th — is untouched.
        assertFalse(isPaid(marks, "Оренда", oct))
    }

    @Test
    fun `a mark is offered only for a month the store would keep`() {
        assertTrue(markKept(oct, oct20))
        assertTrue(markKept(nov, oct20))
        assertFalse(markKept("2026-12", oct20))
        assertFalse(markKept("2027-03", oct20))
    }

    @Test
    fun `an annual fee months away gets no tick, because its mark would be pruned`() {
        val pays = listOf(Pay("Домен", 600.0, day = 10, billingMonth = 3))
        val next = nextPayment(pays, oct20, 41.0)

        assertEquals(WidgetTick.None, widgetTick(next, oct20))
    }

    @Test
    fun `several payments on the day open the app instead of marking them all`() {
        val pays = listOf(rent, rent.copy(name = "Комуналка"), rent.copy(name = "Інтернет"))
        val next = nextPayment(pays, oct20, 41.0)

        assertEquals(WidgetTick.OpenPayments, widgetTick(next, oct20))
    }

    @Test
    fun `two payments on the day are several too`() {
        val next = nextPayment(listOf(rent, rent.copy(name = "Комуналка")), oct20, 41.0)

        assertEquals(WidgetTick.OpenPayments, widgetTick(next, oct20))
    }

    @Test
    fun `nothing scheduled means no tick`() {
        assertEquals(WidgetTick.None, widgetTick(null, oct20))
    }

    // ------------------------------------------------------------ the record

    @Test
    fun `the mark is the record the app's tick makes, amount and currency copied in`() {
        val dollars = Pay("Оренда", 400.0, day = 1, currency = USD)

        val result = quickMarked(emptyList(), listOf(dollars), QuickMark("Оренда", nov))

        assertEquals(QuickOutcome.ADDED, result.outcome)
        assertEquals(togglePaid(emptyList(), dollars, nov), result.marks)
        assertEquals(PaidMark("Оренда", nov, 400.0, USD), result.marks.single())
    }

    @Test
    fun `a tap from outside never takes a mark off`() {
        val marked = listOf(PaidMark("Оренда", nov, 8_000.0))

        val result = quickMarked(marked, listOf(rent), QuickMark("Оренда", nov))

        assertEquals(QuickOutcome.ALREADY, result.outcome)
        assertEquals(marked, result.marks)
        assertEquals(marked, markPaid(marked, rent, nov))
    }

    @Test
    fun `a payment renamed or deleted since the button was drawn is left alone`() {
        val result = quickMarked(emptyList(), listOf(internet), QuickMark("Оренда", nov))

        assertEquals(QuickOutcome.GONE, result.outcome)
        assertTrue(result.marks.isEmpty())
    }

    @Test
    fun `undo takes back exactly one mark`() {
        val marks = listOf(PaidMark("Оренда", nov, 8_000.0), PaidMark("Оренда", oct, 8_000.0), PaidMark("Інтернет", nov, 300.0))

        assertEquals(marks.drop(1), unmarkPaid(marks, QuickMark("Оренда", nov)))
    }

    // ------------------------------------------------------------ the open app

    @Test
    fun `the app's tick on its stale list keeps the mark the widget made meanwhile`() {
        // HANDOFF §15: the screen read the marks when it came to the front …
        val screen = listOf(PaidMark("Мобільний", oct, 231.0))
        // … the widget marked the internet behind its back …
        val store = quickMarked(screen, listOf(internet), QuickMark("Інтернет", oct)).marks
        // … and the screen ticks the rent on the list it still holds.
        val ticked = togglePaid(screen, rent, oct)
        assertFalse("saving the screen's list is the bug", isPaid(ticked, "Інтернет", oct))

        val saved = rebaseMarks(store, screen, ticked)

        assertTrue(isPaid(saved, "Інтернет", oct))
        assertTrue(isPaid(saved, "Оренда", oct))
        assertTrue(isPaid(saved, "Мобільний", oct))
    }

    @Test
    fun `an untick in the app is applied, and does not bring back what the notification undid`() {
        val screen = listOf(PaidMark("Мобільний", oct, 231.0), PaidMark("Інтернет", oct, 300.0))
        // The notification's «Скасувати» took the internet off meanwhile.
        val store = listOf(PaidMark("Мобільний", oct, 231.0))
        val unticked = togglePaid(screen, Pay("Мобільний", 231.0), oct)

        assertEquals(emptyList<PaidMark>(), rebaseMarks(store, screen, unticked))
    }

    @Test
    fun `a corrected amount and a rename travel as changes`() {
        val screen = listOf(PaidMark("Газ", oct, 400.0), PaidMark("Газ", "2026-09", 380.0))
        val store = screen + PaidMark("Інтернет", oct, 300.0)

        val charged = rebaseMarks(store, screen, withMarkAmount(screen, "Газ", oct, 612.0))
        assertEquals(612.0, charged.first { it.name == "Газ" && it.month == oct }.amount, 0.0)
        assertTrue(isPaid(charged, "Інтернет", oct))

        val renamed = rebaseMarks(store, screen, renamePaidMarks(screen, "Газ", "Газопостачання"))
        assertFalse(renamed.any { it.name == "Газ" })
        assertEquals(2, renamed.count { it.name == "Газопостачання" })
        assertTrue(isPaid(renamed, "Інтернет", oct))
    }

    @Test
    fun `a screen that changed nothing leaves the store as it is`() {
        val screen = listOf(PaidMark("Газ", oct, 400.0))
        val store = screen + PaidMark("Інтернет", oct, 300.0)

        assertEquals(store, rebaseMarks(store, screen, screen))
    }

    // ------------------------------------------------------------ the widget's undo

    @Test
    fun `the widget offers to undo its own mark for the rest of the day`() {
        val mark = QuickMark("Інтернет", oct)
        val marks = listOf(PaidMark("Інтернет", oct, 300.0))

        assertEquals(mark, widgetUndo(mark, oct20.toEpochDay(), marks, oct20))
        // Tomorrow the line is gone; so it is once the app took the mark off.
        assertNull(widgetUndo(mark, oct20.toEpochDay(), marks, oct20.plusDays(1)))
        assertNull(widgetUndo(mark, oct20.toEpochDay(), emptyList(), oct20))
        assertNull(widgetUndo(null, oct20.toEpochDay(), marks, oct20))
        assertEquals("✓ Інтернет · Скасувати", widgetUndoLine(mark))
    }

    // ------------------------------------------------------------ the morning message

    private val due = listOf(
        rent,
        internet.copy(day = 2, warnDays = 3),
        Pay("Netflix", 249.0, day = 15),
        Pay("Спортзал", 900.0, day = 31, warnDays = 0),
        Pay("Телефон", 231.0, day = 1, warnDays = 7)
    )

    @Test
    fun `the buttons are the payment lines of the message, soonest first, three at most`() {
        val reminders = remindersDue(due, oct30)
        assertEquals(4, reminders.size)

        val offers = digestOffers(due, oct30, emptySet(), emptyList())

        assertEquals(reminders.take(3).map { QuickMark(it.pay.name, tickMonth(it.pay, oct30)) }, offers)
        assertFalse(offers.any { it.name == "Netflix" })
    }

    @Test
    fun `a payment already ticked in the app gets no button, as it gets no line`() {
        val marks = listOf(PaidMark("Оренда", nov, 8_000.0))

        val offers = digestOffers(due, oct30, emptySet(), marks)

        assertFalse(offers.any { it.name == "Оренда" })
        assertEquals(3, offers.size)
    }

    @Test
    fun `no payment lines, no buttons`() {
        assertTrue(digestOffers(listOf(Pay("Netflix", 249.0, day = 15)), oct30, emptySet(), emptyList()).isEmpty())
    }

    private val morning = DigestCard(
        title = "Зведення за день",
        body = "Оренда 8 000 ₴ — сьогодні\nІнтернет 300 ₴ — через 3 дні\nВільно 12 000 ₴",
        offers = listOf(QuickMark("Оренда", nov), QuickMark("Інтернет", nov))
    )

    @Test
    fun `before anything is pressed the message is the morning's, with a button per payment`() {
        assertEquals("Зведення за день", digestTitle(morning))
        assertEquals(
            listOf("Сплачено · Оренда", "Сплачено · Інтернет"),
            digestButtons(morning).map(::digestButtonLabel)
        )
    }

    @Test
    fun `after a press it says Позначено and offers Скасувати, the other payment still there`() {
        val pressed = digestPressed(morning, QuickMark("Оренда", nov), added = true)

        assertEquals("Позначено: Оренда", digestTitle(pressed))
        assertEquals(listOf("Скасувати", "Сплачено · Інтернет"), digestButtons(pressed).map(::digestButtonLabel))
        assertEquals(DigestButton.Undo(QuickMark("Оренда", nov)), digestButtons(pressed).first())
        // The text is the morning's: rebuilding it later would not say the same.
        assertEquals(morning.body, pressed.body)
    }

    @Test
    fun `undo puts the message back as it was`() {
        val pressed = digestPressed(morning, QuickMark("Оренда", nov), added = true)

        assertEquals(morning, digestUndone(pressed, QuickMark("Оренда", nov)))
    }

    @Test
    fun `undo after two presses takes back the last, then the one before`() {
        val two = digestPressed(
            digestPressed(morning, QuickMark("Оренда", nov), added = true),
            QuickMark("Інтернет", nov),
            added = true
        )
        assertEquals("Позначено: Інтернет", digestTitle(two))
        assertEquals(listOf(DigestButton.Undo(QuickMark("Інтернет", nov))), digestButtons(two))

        val one = digestUndone(two, QuickMark("Інтернет", nov))
        assertEquals("Позначено: Оренда", digestTitle(one))
        assertEquals(listOf("Скасувати", "Сплачено · Інтернет"), digestButtons(one).map(::digestButtonLabel))
    }

    @Test
    fun `a double tap on the same button keeps the way back`() {
        val once = digestPressed(morning, QuickMark("Оренда", nov), added = true)
        // The second tap finds the mark the first one made.
        val twice = digestPressed(once, QuickMark("Оренда", nov), added = false)

        assertEquals(once, twice)
        assertEquals("Скасувати", digestButtonLabel(digestButtons(twice).first()))
    }

    @Test
    fun `a payment marked in the app since the morning is said so, with nothing to undo`() {
        val pressed = digestPressed(morning, QuickMark("Оренда", nov), added = false)

        assertEquals("Уже позначено: Оренда", digestTitle(pressed))
        assertEquals(listOf("Сплачено · Інтернет"), digestButtons(pressed).map(::digestButtonLabel))
    }

    @Test
    fun `a payment deleted since the morning loses its button`() {
        val gone = digestGone(morning, QuickMark("Оренда", nov))

        assertEquals("Зведення за день", digestTitle(gone))
        assertEquals(listOf("Сплачено · Інтернет"), digestButtons(gone).map(::digestButtonLabel))
    }

    @Test
    fun `the stored message comes back whole`() {
        val card = digestPressed(
            digestPressed(morning, QuickMark("Оренда", nov), added = true),
            QuickMark("Інтернет", nov),
            added = false
        )

        assertEquals(card, digestCardOf(org.json.JSONObject(digestCardJson(card).toString())))
    }

    // ------------------------------------------------------------ «Як скасувати» beside them

    private val netflixHelp = DigestAction("Як скасувати Netflix", "https://www.netflix.com/cancelplan")
    private val megogoHelp = DigestAction("Як скасувати Megogo", "https://megogo.net/ua/account?view_type=subscriptions")
    private val spotifyHelp = DigestAction("Як скасувати Spotify", "https://www.spotify.com/account/overview/")

    private fun shown(card: DigestCard) = digestButtons(card).size + digestLinks(card).size

    @Test
    fun `a link takes one of the three slots when there are payments to mark`() {
        val card = morning.copy(
            offers = listOf(QuickMark("Оренда", nov), QuickMark("Інтернет", nov), QuickMark("Netflix", nov)),
            links = listOf(netflixHelp, megogoHelp)
        )

        assertEquals(listOf(netflixHelp), digestLinks(card))
        assertEquals(listOf("Сплачено · Оренда", "Сплачено · Інтернет"), digestButtons(card).map(::digestButtonLabel))
        assertEquals(3, shown(card))
    }

    @Test
    fun `with nothing to mark, two links and no payment buttons`() {
        val card = morning.copy(offers = emptyList(), links = listOf(netflixHelp, megogoHelp, spotifyHelp))

        assertEquals(listOf(netflixHelp, megogoHelp), digestLinks(card))
        assertTrue(digestButtons(card).isEmpty())
    }

    @Test
    fun `the link survives a press and an undo`() {
        val card = morning.copy(links = listOf(netflixHelp))

        val pressed = digestPressed(card, QuickMark("Оренда", nov), added = true)
        assertEquals(listOf(netflixHelp), digestLinks(pressed))
        // «Скасувати» first, then the one payment still unmarked, then the link.
        assertEquals(listOf("Скасувати", "Сплачено · Інтернет"), digestButtons(pressed).map(::digestButtonLabel))
        assertEquals(3, shown(pressed))

        val undone = digestUndone(pressed, QuickMark("Оренда", nov))
        assertEquals(card, undone)
        assertEquals(listOf(netflixHelp), digestLinks(undone))

        assertEquals(listOf(netflixHelp), digestLinks(digestGone(card, QuickMark("Оренда", nov))))
    }

    @Test
    fun `never more than three buttons in all`() {
        val marks = listOf("Оренда", "Інтернет", "Netflix", "Megogo", "Газ").map { QuickMark(it, nov) }
        val helps = listOf(netflixHelp, megogoHelp, spotifyHelp)
        for (offers in 0..marks.size) {
            for (links in 0..helps.size) {
                val card = DigestCard("Зведення за день", "", marks.take(offers), links = helps.take(links))
                val states = listOf(card) + marks.take(offers).flatMap { mark ->
                    listOf(digestPressed(card, mark, added = true), digestPressed(card, mark, added = false))
                }
                states.forEach { state ->
                    assertTrue("$offers offers, $links links: ${shown(state)}", shown(state) <= DIGEST_BUTTONS)
                    // A link given is always shown: the deadline it is about is real.
                    if (links > 0) assertTrue(digestLinks(state).isNotEmpty())
                }
            }
        }
    }

    @Test
    fun `the links come back from storage, and one that is not a web page does not`() {
        val card = digestPressed(morning.copy(links = listOf(netflixHelp, megogoHelp)), QuickMark("Оренда", nov), added = true)
        assertEquals(card, digestCardOf(org.json.JSONObject(digestCardJson(card).toString())))

        val o = digestCardJson(morning)
        o.getJSONArray("tcl")
            .put(org.json.JSONObject().put("tcll", "Як скасувати Щось").put("tclu", "javascript:alert(1)"))
            .put(org.json.JSONObject().put("tcll", "").put("tclu", "https://example.com"))
        assertTrue(digestCardOf(o).links.isEmpty())
    }

    @Test
    fun `a card stored before links existed still reads`() {
        val old = digestCardJson(morning).apply { remove("tcl") }

        assertEquals(morning, digestCardOf(old))
    }

    @Test
    fun `a stored mark without a month is dropped rather than trusted`() {
        val o = digestCardJson(morning)
        o.getJSONArray("tco").put(org.json.JSONObject().put("tcn", "Газ").put("tcm", "колись"))

        assertEquals(morning.offers, digestCardOf(o).offers)
    }
}

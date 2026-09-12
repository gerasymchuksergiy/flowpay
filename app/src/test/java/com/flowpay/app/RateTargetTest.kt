package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A number the user named, said once.
 *
 * Two failures matter here and neither of them is wording. One is a threshold that
 * fires every morning for the rest of the year, which is the behaviour that gets a
 * digest muted and takes the parcel and payment lines down with it. The other is a
 * direction worked out afresh each morning instead of at the moment the number was
 * set: a rate wandering back over the threshold would then answer the opposite of
 * the question that was asked, and answer it immediately.
 */
class RateTargetTest {

    private val today = LocalDate.of(2026, 9, 15).toEpochDay()

    // Figures reach the screen with a non-breaking space inside them, which no
    // test should have to type.
    private fun shown(text: String) = text.replace(' ', ' ')

    @Test
    fun `the direction is read off the rate when the number is set`() {
        // 42 named while the dollar sits below it is a question about a rise.
        val rise = armRateTarget(42.0, 41.30)
        assertNotNull(rise)
        assertTrue(rise!!.above)

        // The same 42 named while the dollar sits above it is the opposite question.
        val fall = armRateTarget(42.0, 42.60)
        assertNotNull(fall)
        assertFalse(fall!!.above)
    }

    @Test
    fun `a threshold cannot be armed without a rate to read the direction from`() {
        assertNull(armRateTarget(42.0, 0.0))
        assertNull(armRateTarget(0.0, 41.30))
        assertNull(armRateTarget(-1.0, 41.30))
    }

    @Test
    fun `a rise is reached from below and is not reached by a fall`() {
        val target = armRateTarget(42.0, 41.30)!!

        assertFalse(rateTargetReached(target, 41.99))
        assertTrue(rateTargetReached(target, 42.0))
        assertTrue(rateTargetReached(target, 42.35))
        // The rate dropping away from a threshold set above it is not the event.
        assertFalse(rateTargetReached(target, 40.10))
    }

    @Test
    fun `a fall is reached from above and is not reached by a rise`() {
        val target = armRateTarget(40.0, 41.30)!!

        assertFalse(rateTargetReached(target, 40.01))
        assertTrue(rateTargetReached(target, 40.0))
        assertTrue(rateTargetReached(target, 39.50))
        assertFalse(rateTargetReached(target, 43.00))
    }

    @Test
    fun `a rate that crosses back does not reverse what was asked`() {
        // The whole reason the direction is stored rather than recomputed. Asked
        // while the dollar was at 41,30, this is a question about reaching 42.
        val target = armRateTarget(42.0, 41.30)!!

        // A week later the rate has run past the threshold and come back under it.
        // Recomputing the direction here would make it a question about falling to
        // 42 — already true — and it would fire on a fall nobody asked about.
        assertFalse(rateTargetReached(target, 41.10))
    }

    @Test
    fun `nothing is reached without a target or without a rate`() {
        assertFalse(rateTargetReached(null, 42.0))
        assertFalse(rateTargetReached(armRateTarget(42.0, 41.30), 0.0))
    }

    @Test
    fun `once told it never speaks again`() {
        val armed = armRateTarget(42.0, 41.30)!!
        assertTrue(rateTargetReached(armed, 42.10))

        val told = disarmRateTarget(armed, today)
        // Still the same threshold, still crossed, and now permanently silent.
        assertEquals(42.0, told.rate, 0.001)
        assertEquals(today, told.hitDay)
        assertFalse(rateTargetReached(told, 42.10))
        assertFalse(rateTargetReached(told, 99.0))
        assertNull(rateTargetLine(told, 42.10))
    }

    @Test
    fun `the line names both the rate now and the number that was asked for`() {
        val target = armRateTarget(42.0, 41.30)!!

        assertNull(rateTargetLine(target, 41.80))
        assertEquals(
            "Долар 42,10 ₴ — курс перетнув 42,00",
            shown(rateTargetLine(target, 42.10)!!)
        )
        assertNull(rateTargetLine(null, 42.10))
    }

    @Test
    fun `figures in the line are Ukrainian whatever the phone is set to`() {
        val target = armRateTarget(42.0, 41.30)!!
        val line = rateTargetLine(target, 42.10)!!

        // A decimal point here would mean the line was formatted in the phone's
        // locale rather than the app's, which CI runs as en-US on purpose.
        assertTrue(line.contains("42,10"))
        assertFalse(line.contains("42.10"))
    }

    @Test
    fun `the screen says which way it is waiting and stops waiting once met`() {
        assertEquals(
            "Скажу один раз, коли курс дійде до вашого числа",
            rateTargetNote(null, 41.30)
        )
        assertEquals(
            "Скажу, коли курс підніметься до 42,00",
            shown(rateTargetNote(armRateTarget(42.0, 41.30), 41.30))
        )
        assertEquals(
            "Скажу, коли курс опуститься до 40,00",
            shown(rateTargetNote(armRateTarget(40.0, 41.30), 41.30))
        )
        // Crossed but not yet nine o'clock: the screen must not look broken for
        // showing a rate past a threshold the phone has said nothing about.
        assertEquals(
            "42,00 — уже досягнуто, скажу вранці",
            shown(rateTargetNote(armRateTarget(42.0, 41.30), 42.10))
        )
        assertEquals(
            "42,00 — досягнуто 15 вересня 2026",
            shown(rateTargetNote(disarmRateTarget(armRateTarget(42.0, 41.30)!!, today), 42.10))
        )
    }

    // ------------------------------------------------------ inside the digest

    private val day = LocalDate.of(2026, 9, 15)

    private fun morning(target: RateTarget?, rate: Double) = digest(
        wishes = emptyList(),
        pays = emptyList(),
        orders = emptyList(),
        today = day,
        usdSellRate = rate,
        income = 0.0,
        rateTarget = target
    )

    @Test
    fun `a crossing is worth waking the digest for on its own`() {
        // Nothing else happened at all, so this proves the line is the news and
        // not a rider on somebody else's.
        val quiet = morning(armRateTarget(42.0, 41.30), 41.80)
        assertTrue(quiet.empty)

        val crossed = morning(armRateTarget(42.0, 41.30), 42.10)
        assertFalse(crossed.empty)
        assertEquals("Долар 42,10 ₴ — курс перетнув 42,00", shown(crossed.title))
    }

    @Test
    fun `a threshold already told is not repeated the next morning`() {
        val told = disarmRateTarget(armRateTarget(42.0, 41.30)!!, today)
        // The rate is still above 42 and will be for weeks. This is the difference
        // between a threshold and a daily bulletin about the dollar.
        assertTrue(morning(told, 42.10).empty)
        assertTrue(morning(told, 43.50).empty)
    }

    @Test
    fun `the crossing leads the message it shares`() {
        val pay = Pay("Оренда", 400.0, day = day.dayOfMonth, currency = USD, warnDays = 1)
        val full = digest(
            wishes = emptyList(),
            pays = listOf(pay),
            orders = emptyList(),
            today = day,
            usdSellRate = 42.10,
            income = 30000.0,
            rateTarget = armRateTarget(42.0, 41.30)
        )

        assertFalse(full.empty)
        // The one line asked for by name sits above the lines the app chose.
        assertEquals("Долар 42,10 ₴ — курс перетнув 42,00", shown(full.lines.first()))
        assertTrue(full.lines.any { it.contains("Оренда") })
    }

    @Test
    fun `no threshold changes nothing about the digest`() {
        assertTrue(morning(null, 42.10).empty)
    }
}

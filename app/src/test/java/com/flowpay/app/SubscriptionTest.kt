package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDate

/**
 * A subscription that raises its price quietly is how a subscription earns.
 *
 * The expense used to remember one number, so 269 becoming 309 left nothing at all
 * behind: no "was", no percentage, no year-on-year figure. These are the tests for
 * the memory that fixes it, and for the two ways it could lie — inventing a change
 * where none happened, and reading a currency switch as a price cut.
 */
class SubscriptionTest {

    // A Tuesday, so nothing here is accidentally testing the weekend rule.
    private val today = LocalDate.of(2026, 9, 15)
    private val epochToday = today.toEpochDay()

    private fun shown(text: String) = text.replace(' ', ' ')

    private fun <T> inLocale(locale: java.util.Locale, block: () -> T): T {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(locale)
        try {
            return block()
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    private val netflix = Pay("Netflix", 269.0, day = 12, warnDays = 7)

    /** Netflix at 269 since the spring, raised to 309 a fortnight ago. */
    private val raised = netflix.copy(
        amount = 309.0,
        amounts = listOf(
            PricePoint(269.0, LocalDate.of(2026, 3, 1).toEpochDay()),
            PricePoint(309.0, LocalDate.of(2026, 9, 1).toEpochDay())
        )
    )

    // ------------------------------------------------------ recording a change

    @Test
    fun `the figure being replaced is kept, not overwritten`() {
        // The whole feature in one assertion: an expense with no history at all is
        // edited, and the old number survives the edit.
        val after = withAmount(netflix, 309.0, epochToday)

        assertEquals(309.0, after.amount, 0.001)
        assertEquals(2, after.amounts.size)
        assertEquals(269.0, after.amounts.first().price, 0.001)
        assertEquals(309.0, after.amounts.last().price, 0.001)
        assertEquals(epochToday, after.amounts.last().day)
    }

    @Test
    fun `the replaced figure carries no invented date`() {
        // Nothing on the phone knows when 269 started, and guessing today would
        // make the sheet claim the old price began the day it ended.
        val after = withAmount(netflix, 309.0, epochToday)

        assertEquals(0L, after.amounts.first().day)
    }

    @Test
    fun `saving the sheet without touching the amount records nothing`() {
        // Opening an expense and closing it is not a price change, and recording
        // one would put a 0% line in tomorrow's digest.
        assertEquals(netflix, withAmount(netflix, 269.0, epochToday))
        assertTrue(withAmount(netflix, 269.0, epochToday).amounts.isEmpty())
    }

    @Test
    fun `an amount of nothing is refused rather than recorded`() {
        assertEquals(netflix, withAmount(netflix, 0.0, epochToday))
        assertEquals(netflix, withAmount(netflix, -5.0, epochToday))
    }

    @Test
    fun `a second raise appends rather than replacing the first`() {
        val again = withAmount(raised, 349.0, epochToday)

        assertEquals(listOf(269.0, 309.0, 349.0), again.amounts.map { it.price })
    }

    @Test
    fun `switching currency starts the history again`() {
        // 309 ₴ becoming 9 $ is not a 97% fall, and recording it as one would be
        // the history's first and worst lie — one the digest would then announce.
        val inDollars = edited(raised, 9.0, USD, epochToday)

        assertEquals(USD, inDollars.currency)
        assertEquals(listOf(9.0), inDollars.amounts.map { it.price })
        assertNull(lastAmountChange(inDollars))
    }

    @Test
    fun `an edit in the same currency keeps the history`() {
        val after = edited(netflix, 309.0, UAH, epochToday)

        assertEquals(listOf(269.0, 309.0), after.amounts.map { it.price })
    }

    // -------------------------------------------------------- reading it back

    @Test
    fun `an expense that has only ever cost one thing reports no change`() {
        assertNull(lastAmountChange(netflix))
        assertNull(lastAmountChange(netflix.copy(amounts = listOf(PricePoint(269.0, epochToday)))))
        assertNull(amountHistoryNote(netflix))
        assertTrue(amountTrailLines(netflix).isEmpty())
    }

    @Test
    fun `the last change is the last two figures and the gap between them`() {
        val change = lastAmountChange(raised)

        assertNotNull(change)
        assertEquals(269.0, change!!.from, 0.001)
        assertEquals(309.0, change.to, 0.001)
        assertEquals(LocalDate.of(2026, 9, 1).toEpochDay(), change.day)
        assertEquals(14.87, change.percent, 0.01)
        assertTrue(change.raised)
    }

    @Test
    fun `a fall is reported as a fall`() {
        val cheaper = netflix.copy(
            amount = 199.0,
            amounts = listOf(PricePoint(269.0, 0L), PricePoint(199.0, epochToday))
        )

        assertFalse(lastAmountChange(cheaper)!!.raised)
        assertTrue(lastAmountChange(cheaper)!!.percent < 0)
    }

    @Test
    fun `the trail closes with what the expense costs now`() {
        // A backup written before any of this restores an amount with no point to
        // match it. The sheet must not quote a "current" figure the amount beside
        // it contradicts.
        val drifted = netflix.copy(amount = 349.0, amounts = listOf(PricePoint(269.0, 100L)))

        assertEquals(listOf(269.0, 349.0), amountTrail(drifted).map { it.price })
        assertEquals(349.0, lastAmountChange(drifted)!!.to, 0.001)
    }

    @Test
    fun `the note under the amount names the old figure and the move`() {
        assertEquals("Було 269 ₴ · +15% з 1 вересня", shown(amountHistoryNote(raised)!!))
    }

    @Test
    fun `an undated old figure gets no date rather than a guessed one`() {
        val seeded = withAmount(netflix, 309.0, epochToday)

        assertEquals("Було 269 ₴ · +15% з 15 вересня", shown(amountHistoryNote(seeded)!!))
        assertEquals(
            listOf("309 ₴ · з 15 вересня", "269 ₴ · раніше"),
            amountTrailLines(seeded).map(::shown)
        )
    }

    @Test
    fun `the trail is newest first and stops before it becomes a wall`() {
        val many = netflix.copy(
            amount = 9.0,
            amounts = (1..9).map { PricePoint(it.toDouble(), epochToday - 9 + it) }
        )

        val lines = amountTrailLines(many, limit = 3)

        assertEquals(3, lines.size)
        assertTrue(lines.first().startsWith("9 ₴"))
        assertTrue(lines.last().startsWith("7 ₴"))
    }

    @Test
    fun `the digest line is the shape a notification has room for`() {
        assertEquals(
            "Netflix 269 → 309 ₴, +15%",
            shown(amountChangeLine(raised, lastAmountChange(raised)!!))
        )
    }

    @Test
    fun `a dollar expense states its own currency once`() {
        val hosting = Pay("Хостинг", 12.0, currency = USD)
            .copy(amounts = listOf(PricePoint(9.0, 100L), PricePoint(12.0, epochToday)))

        assertEquals(
            "Хостинг 9 → 12 $, +33%",
            shown(amountChangeLine(hosting, lastAmountChange(hosting)!!))
        )
    }

    // ------------------------------------------------------- what it cost then

    @Test
    fun `the amount on a day is the last figure recorded by then`() {
        val march = LocalDate.of(2026, 3, 15).toEpochDay()
        val august = LocalDate.of(2026, 8, 15).toEpochDay()

        assertEquals(269.0, amountOn(raised, march), 0.001)
        assertEquals(269.0, amountOn(raised, august), 0.001)
        assertEquals(309.0, amountOn(raised, epochToday), 0.001)
    }

    @Test
    fun `before anything was recorded the oldest figure stands in`() {
        // A point is only ever written when the amount moves, so an expense with
        // no point before the day had not moved by then.
        val january = LocalDate.of(2026, 1, 1).toEpochDay()

        assertEquals(269.0, amountOn(raised, january), 0.001)
    }

    @Test
    fun `an expense with no history at all cost what it costs`() {
        assertEquals(269.0, amountOn(netflix, 0L), 0.001)
        assertEquals(269.0, amountOn(netflix, epochToday), 0.001)
    }

    // -------------------------------------------------------- a year of it

    @Test
    fun `the year is compared against the same list at its old prices`() {
        val shift = yearlyShift(listOf(raised), usdSellRate = 0.0, today = today)

        assertEquals(269.0 * 12, shift.before.total, 0.001)
        assertEquals(309.0 * 12, shift.now.total, 0.001)
        assertEquals(480.0, shift.difference, 0.001)
        assertFalse(shift.unchanged)
    }

    @Test
    fun `a list where nothing moved says nothing`() {
        val shift = yearlyShift(listOf(netflix), usdSellRate = 0.0, today = today)

        assertTrue(shift.unchanged)
        assertNull(yearlyShiftNote(shift))
    }

    @Test
    fun `the yearly line states both figures and the gap`() {
        val note = yearlyShiftNote(yearlyShift(listOf(raised), 0.0, today))

        assertEquals("Торік 3 228 ₴ на рік, зараз 3 708 ₴ (+15%)", shown(note!!))
    }

    @Test
    fun `an empty list has no year to compare`() {
        assertTrue(yearlyShift(emptyList(), 0.0, today).unchanged)
        assertNull(yearlyShiftNote(yearlyShift(emptyList(), 0.0, today)))
    }

    // ------------------------------------------------------------ the digest

    @Test
    fun `a raise this week reaches the morning message`() {
        val recent = withAmount(netflix, 309.0, epochToday - 2)

        val summary = digest(
            wishes = emptyList(),
            pays = listOf(recent),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 20_000.0
        )

        assertTrue(shown(summary.body + summary.title).contains("Netflix 269 → 309 ₴, +15%"))
    }

    @Test
    fun `a raise from months ago is not still being announced`() {
        // The digest is rebuilt from state every morning, so without a window the
        // same raise would be reported for the rest of the subscription's life.
        val old = withAmount(netflix, 309.0, epochToday - 90)

        val summary = digest(
            wishes = emptyList(),
            // Day 12 with seven days' notice, against the 15th: nothing is due, so
            // an empty digest here is proof the raise alone did not carry it.
            pays = listOf(old.copy(day = 12, warnDays = 0)),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 20_000.0
        )

        assertTrue(summary.empty)
    }

    // ------------------------------------------------------------ on disk

    @Test
    fun `the history survives a round trip through storage`() {
        val back = payOf(payJson(raised))

        assertEquals(raised, back)
        assertEquals(listOf(269.0, 309.0), back.amounts.map { it.price })
        assertEquals(LocalDate.of(2026, 3, 1).toEpochDay(), back.amounts.first().day)
    }

    @Test
    fun `an expense stored before any of this existed loads and behaves as it did`() {
        // The mapping is also how the bin restores a deleted expense and how a
        // backup comes back, so a missing key has to be a working expense.
        val old = JSONObject().put("n", "Інтернет").put("a", 300.0).put("d", 1)

        val pay = payOf(old)

        assertEquals("Інтернет", pay.name)
        assertEquals(300.0, pay.amount, 0.001)
        assertEquals(UAH, pay.currency)
        assertEquals(DEFAULT_WARN_DAYS, pay.warnDays)
        assertTrue(pay.amounts.isEmpty())
        assertNull(lastAmountChange(pay))
        assertNull(amountHistoryNote(pay))
    }

    @Test
    fun `a half written history comes back shorter rather than taking the expense with it`() {
        val damaged = payJson(raised).put("am", org.json.JSONArray().put("nonsense").put(7))

        assertTrue(payOf(damaged).amounts.isEmpty())
        assertEquals(309.0, payOf(damaged).amount, 0.001)
    }

    // ------------------------------------------------------------ the locale

    @Test
    fun `every figure here is written in the app's language, not the phone's`() {
        // CI runs en-US and the phone runs uk-UA, so a figure formatted against the
        // default locale passes locally and ships broken.
        val note = inLocale(java.util.Locale.US) { amountHistoryNote(raised) }
        val line = inLocale(java.util.Locale.US) {
            amountChangeLine(raised, lastAmountChange(raised)!!)
        }
        val yearly = inLocale(java.util.Locale.US) {
            yearlyShiftNote(yearlyShift(listOf(raised), 0.0, today))
        }

        assertEquals("Було 269 ₴ · +15% з 1 вересня", shown(note!!))
        assertEquals("Netflix 269 → 309 ₴, +15%", shown(line))
        assertEquals("Торік 3 228 ₴ на рік, зараз 3 708 ₴ (+15%)", shown(yearly!!))
    }
}

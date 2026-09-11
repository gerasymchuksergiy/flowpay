package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict on the item page is the strongest claim this app makes, so the rules
 * behind it are pinned here, including every case where it must refuse to have an
 * opinion.
 */
class HistoryTest {

    private val day = 20_000L

    // -------------------------------------------------------------- recording

    @Test
    fun `a changed price is recorded with the day it changed`() {
        val history = appendPrice(listOf(PricePoint(1_000.0, day - 5)), 900.0, day)

        assertEquals(2, history.size)
        assertEquals(900.0, history.last().price, 0.001)
        assertEquals(day, history.last().day)
    }

    @Test
    fun `an unchanged price is not recorded twice`() {
        val start = listOf(PricePoint(1_000.0, day - 30))

        assertEquals(start, appendPrice(start, 1_000.0, day))
    }

    @Test
    fun `a price that returns to an earlier value is still a change`() {
        var history = listOf(PricePoint(1_000.0, day - 10))
        history = appendPrice(history, 900.0, day - 5)
        history = appendPrice(history, 1_000.0, day)

        assertEquals(listOf(1_000.0, 900.0, 1_000.0), history.map { it.price })
    }

    @Test
    fun `a missing price is not recorded at all`() {
        val start = listOf(PricePoint(1_000.0, day))

        assertEquals(start, appendPrice(start, 0.0, day + 1))
        assertEquals(start, appendPrice(start, -50.0, day + 1))
    }

    @Test
    fun `recording stays within the cap and drops the oldest`() {
        var history = emptyList<PricePoint>()
        for (i in 1..HISTORY_CAP + 20) history = appendPrice(history, i.toDouble(), day + i)

        assertEquals(HISTORY_CAP, history.size)
        assertEquals(21.0, history.first().price, 0.001)
        assertEquals((HISTORY_CAP + 20).toDouble(), history.last().price, 0.001)
    }

    // --------------------------------------------------------------- verdicts

    @Test
    fun `no opinion is offered on a single observation`() {
        val insight = priceInsight(listOf(PricePoint(1_000.0, day - 30)), 1_000.0, day)

        assertEquals(BuyVerdict.UNKNOWN, insight.verdict)
    }

    @Test
    fun `no opinion is offered inside the first week`() {
        val history = listOf(PricePoint(1_000.0, day - 3), PricePoint(800.0, day - 1))

        val insight = priceInsight(history, 800.0, day)

        assertEquals(3, insight.daysTracked)
        assertEquals(BuyVerdict.UNKNOWN, insight.verdict)
    }

    @Test
    fun `the lowest price yet seen is a good moment`() {
        val history = listOf(PricePoint(1_000.0, day - 60), PricePoint(700.0, day - 2))

        val insight = priceInsight(history, 700.0, day)

        assertTrue(insight.atLowest)
        assertEquals(BuyVerdict.GOOD, insight.verdict)
        assertEquals(700.0, insight.lowest, 0.001)
        assertEquals(1_000.0, insight.highest, 0.001)
        assertEquals(0.0, insight.position, 0.001)
        assertEquals(30.0, insight.offHighest, 0.001)
        assertEquals(60, insight.daysTracked)
    }

    @Test
    fun `the top of the range is a bad moment`() {
        val history = listOf(
            PricePoint(700.0, day - 60),
            PricePoint(800.0, day - 40),
            PricePoint(1_000.0, day - 2)
        )

        val insight = priceInsight(history, 1_000.0, day)

        assertEquals(BuyVerdict.POOR, insight.verdict)
        assertEquals(1.0, insight.position, 0.001)
        assertFalse(insight.atLowest)
    }

    @Test
    fun `the middle of the range is neither`() {
        val history = listOf(
            PricePoint(500.0, day - 90),
            PricePoint(1_500.0, day - 45),
            PricePoint(1_000.0, day - 2)
        )

        val insight = priceInsight(history, 1_000.0, day)

        assertEquals(BuyVerdict.FAIR, insight.verdict)
        assertEquals(0.5, insight.position, 0.001)
    }

    @Test
    fun `a price that never moved is fair rather than a bargain`() {
        val history = listOf(PricePoint(1_000.0, day - 60), PricePoint(1_000.0, day - 30))

        val insight = priceInsight(history, 1_000.0, day)

        // Two entries and plenty of time, but no range: nothing recommends buying now
        // over any other day, and calling it a good moment would be dishonest.
        assertEquals(BuyVerdict.FAIR, insight.verdict)
        assertEquals(0.0, insight.position, 0.001)
    }

    @Test
    fun `a history carried over without dates yields no time span and no verdict`() {
        // Entries written before dates existed read as day zero.
        val legacy = listOf(PricePoint(1_000.0, 0L), PricePoint(900.0, 0L))

        val insight = priceInsight(legacy, 900.0, 0L)

        assertEquals(0, insight.daysTracked)
        assertEquals(BuyVerdict.UNKNOWN, insight.verdict)
        // The prices themselves are still usable.
        assertEquals(900.0, insight.lowest, 0.001)
        assertEquals(1_000.0, insight.highest, 0.001)
    }

    @Test
    fun `an empty history says nothing rather than dividing by zero`() {
        val insight = priceInsight(emptyList(), 0.0, day)

        assertEquals(BuyVerdict.UNKNOWN, insight.verdict)
        assertEquals(0.0, insight.position, 0.001)
        assertEquals(0, insight.changes)
    }

    // ----------------------------------------------------------------- labels

    @Test
    fun `day counts use the three ukrainian plural forms`() {
        assertEquals("1 день", daysLabel(1))
        assertEquals("2 дні", daysLabel(2))
        assertEquals("5 днів", daysLabel(5))
        assertEquals("11 днів", daysLabel(11))
        assertEquals("21 день", daysLabel(21))
        assertEquals("44 дні", daysLabel(44))
    }

    @Test
    fun `change counts use the three ukrainian plural forms`() {
        assertEquals("1 зміна", changesLabel(1))
        assertEquals("3 зміни", changesLabel(3))
        assertEquals("12 змін", changesLabel(12))
        assertEquals("21 зміна", changesLabel(21))
    }

    // --------------------------------------------------------- exchange rate

    @Test
    fun `a rate is recorded even when it has not moved`() {
        // The opposite rule to a price. A week in which the rate held still is a flat
        // week on the chart, and skipping those days would draw it as a single bar.
        val history = appendRate(listOf(PricePoint(41.2, day)), 41.2, day + 1)

        assertEquals(2, history.size)
        assertEquals(day + 1, history.last().day)
    }

    @Test
    fun `a second reading on the same day corrects it instead of adding a bar`() {
        val morning = appendRate(emptyList(), 41.2, day)
        val evening = appendRate(morning, 41.6, day)

        assertEquals(1, evening.size)
        assertEquals(41.6, evening.single().price, 0.001)
        assertEquals(day, evening.single().day)
    }

    @Test
    fun `a rate that failed to load is not recorded as zero`() {
        val start = listOf(PricePoint(41.2, day))

        assertEquals(start, appendRate(start, 0.0, day + 1))
        assertEquals(start, appendRate(start, -1.0, day + 1))
        // No date is as useless as no rate: a bar has to sit somewhere on the axis.
        assertEquals(start, appendRate(start, 41.5, 0L))
    }

    @Test
    fun `the rate history stops at a month and drops the oldest day first`() {
        var history = emptyList<PricePoint>()
        repeat(40) { index -> history = appendRate(history, 41.0 + index, day + index) }

        assertEquals(RATE_HISTORY_CAP, history.size)
        assertEquals(day + 10, history.first().day)
        assertEquals(day + 39, history.last().day)
    }

    @Test
    fun `a brand new install says the history starts today rather than showing a month`() {
        assertEquals("Історія курсу почнеться з сьогодні", rateHistoryNote(emptyList(), day))
    }

    @Test
    fun `the first day says so instead of claiming a span`() {
        val history = appendRate(emptyList(), 41.2, day)

        assertEquals("Записую курс щодня, поки що 1 запис", rateHistoryNote(history, day))
    }

    @Test
    fun `the note counts the days actually observed, not the days the chart could hold`() {
        val history = listOf(
            PricePoint(41.2, day),
            PricePoint(41.3, day + 1),
            PricePoint(41.1, day + 2)
        )

        // Three readings covering three days, including both ends.
        assertEquals("3 записи за 3 дні", rateHistoryNote(history, day + 2))
    }

    @Test
    fun `entry counts use the three ukrainian plural forms`() {
        assertEquals("1 запис", entriesLabel(1))
        assertEquals("3 записи", entriesLabel(3))
        assertEquals("11 записів", entriesLabel(11))
        assertEquals("21 запис", entriesLabel(21))
    }

    @Test
    fun `a range is only claimed once the rate has actually moved`() {
        assertNull(rateRangeNote(emptyList()))
        assertNull(rateRangeNote(listOf(PricePoint(41.2, day))))
        assertNull(rateRangeNote(listOf(PricePoint(41.2, day), PricePoint(41.2, day + 1))))

        val moved = listOf(PricePoint(41.2, day), PricePoint(41.8, day + 1))
        assertEquals("41,20 – 41,80", rateRangeNote(moved))
    }
}

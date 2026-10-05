package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * The second half of the October audit: the morning message, the spreadsheet,
 * the carrier's dates, the plan totals and the widget.
 */
class AuditFixesSecondTest {

    private fun wish(price: Double, history: List<PricePoint>) = Wish(
        id = "w", name = "Ніж", url = "https://shop.example/x", image = "",
        price = price, history = history
    )

    @Test
    fun `a fall after yesterday's message is told this morning`() {
        // 2 700 at the 09:00 message yesterday, 2 400 recorded at 14:00 yesterday.
        val today = 20_000L
        val w = wish(2_400.0, listOf(PricePoint(2_700.0, today - 5), PricePoint(2_400.0, today - 1)))
        assertNull(recentChange(w, today))
        assertEquals(-300.0, recentChange(w, today, lastSaid = 2_700.0)!!, 0.001)
    }

    @Test
    fun `a price the last message already told is not told again`() {
        val today = 20_000L
        val w = wish(2_400.0, listOf(PricePoint(2_700.0, today - 5), PricePoint(2_400.0, today - 1)))
        assertNull(recentChange(w, today, lastSaid = 2_400.0))
    }

    @Test
    fun `the digest goes at its hour, or soon when the hour passed unsent`() {
        val morning = LocalDateTime.of(2026, 10, 3, 8, 0)
        assertEquals(3_600_000L, ReminderWorker.nextDigestDelay(morning, 9, ranToday = false))
        val late = LocalDateTime.of(2026, 10, 3, 10, 0)
        assertEquals(60_000L, ReminderWorker.nextDigestDelay(late, 9, ranToday = false))
        assertEquals(23 * 3_600_000L, ReminderWorker.nextDigestDelay(late, 9, ranToday = true))
    }

    @Test
    fun `a slip corrected the same day is not a price move`() {
        val day = 20_000L
        val netflix = Pay("Netflix", 269.0, amounts = listOf(PricePoint(269.0, 0L)))
        val slipped = withAmount(netflix, 3_090.0, day)
        val fixed = withAmount(slipped, 309.0, day)
        assertEquals(listOf(269.0, 309.0), fixed.amounts.map { it.price })
        // Corrected back to what it was: no move at all.
        val undone = withAmount(slipped, 269.0, day)
        assertEquals(listOf(269.0), undone.amounts.map { it.price })
    }

    @Test
    fun `every raise inside a month is seen, not only the latest`() {
        val sept20 = LocalDate.of(2026, 9, 20).toEpochDay()
        val oct2 = LocalDate.of(2026, 10, 2).toEpochDay()
        val pay = Pay(
            "Netflix", 329.0,
            amounts = listOf(PricePoint(269.0, 0L), PricePoint(309.0, sept20), PricePoint(329.0, oct2))
        )
        val september = amountChangeIn(
            pay, LocalDate.of(2026, 9, 1).toEpochDay(), LocalDate.of(2026, 9, 30).toEpochDay()
        )!!
        assertEquals(269.0, september.from, 0.001)
        assertEquals(309.0, september.to, 0.001)
    }

    @Test
    fun `a spreadsheet does not run a name as a formula`() {
        assertEquals("'+380 Київстар", csvField("+380 Київстар"))
        assertEquals("'-30% Навушники", csvField("-30% Навушники"))
        assertEquals("'=SUM(A1)", csvField("=SUM(A1)"))
        assertEquals("-5,00", csvField("-5,00"))
        assertEquals("Оренда", csvField("Оренда"))
    }

    @Test
    fun `january exports the year just ended`() {
        assertEquals(2026, exportYear(LocalDate.of(2027, 1, 5)))
        assertEquals(2027, exportYear(LocalDate.of(2027, 2, 1)))
    }

    @Test
    fun `projected subscriptions alone are not a year with anything in it`() {
        val rows = expenseRows(listOf(Pay("Інтернет", 300.0)), emptyList(), emptyList(), 2027, 0.0)
        assertTrue(rows.isNotEmpty())
        assertFalse(hasRecordedRows(rows))
        val paid = expenseRows(
            listOf(Pay("Інтернет", 300.0)),
            listOf(PaidMark("Інтернет", "2027-01", 300.0)),
            emptyList(), 2027, 0.0
        )
        assertTrue(hasRecordedRows(paid))
    }

    @Test
    fun `a year-first carrier date is read`() {
        assertEquals(LocalDate.of(2026, 10, 9), parseCarrierDate("2026-10-09 00:00:00"))
        assertEquals(LocalDate.of(2026, 10, 9), parseCarrierDate("09.10.2026"))
        assertEquals(LocalDate.of(2026, 10, 9), parseCarrierDate("09-10-2026 12:00:00"))
    }

    @Test
    fun `a wish planned by date asks what the date demands`() {
        val today = LocalDate.of(2026, 10, 3)
        val byDate = wish(30_000.0, emptyList()).copy(deadline = today.plusMonths(3).toEpochDay())
        assertEquals(10_000.0, plannedMonthly(byDate, today), 0.001)
        val done = wish(30_000.0, emptyList()).copy(saved = 30_000.0, monthlyPlan = 2_000.0)
        assertEquals(0.0, plannedMonthly(done, today), 0.001)
        val monthly = wish(30_000.0, emptyList()).copy(monthlyPlan = 2_000.0)
        assertEquals(2_000.0, plannedMonthly(monthly, today), 0.001)
    }

    @Test
    fun `the widget does not announce a bill already ticked off`() {
        val today = LocalDate.of(2026, 10, 3)
        val rent = Pay("Оренда", 11_000.0, day = 5)
        val internet = Pay("Інтернет", 300.0, day = 20)
        val summary = widgetSummary(
            listOf(rent, internet), emptyList(), 0.0, 0.0, today,
            marks = listOf(PaidMark("Оренда", "2026-10", 11_000.0))
        )
        assertEquals("Інтернет", summary.paymentName)
    }

    @Test
    fun `a filed purchase is not waiting at the branch`() {
        val today = LocalDate.of(2026, 10, 3)
        val filed = Order("o", "x", "u", AT_BRANCH, archivedDay = 20_000L)
        val summary = widgetSummary(emptyList(), listOf(filed), 0.0, 0.0, today)
        assertEquals(branchLine(0), summary.parcels)
    }
}

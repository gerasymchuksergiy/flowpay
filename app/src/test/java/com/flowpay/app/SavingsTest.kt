package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The savings planner decides what the item page tells you to put aside each
 * month, so an off-by-one month here would be believed rather than noticed.
 */
class SavingsTest {

    @Test
    fun `a plan divides what is left by the monthly amount`() {
        val plan = savingsPlan(goal = 3000.0, saved = 600.0, monthly = 800.0)

        assertEquals(2400.0, plan.remaining, 0.001)
        assertEquals(3, plan.months)
        assertEquals(0.2f, plan.progress, 0.001f)
        assertFalse(plan.reached)
        assertFalse(plan.needsRate)
    }

    @Test
    fun `a part month still counts as a whole month of saving`() {
        // 2401 at 800 a month is three months and a little, which is four months
        // before the money is actually there.
        assertEquals(4, savingsPlan(3001.0, 600.0, 800.0).months)
        assertEquals(1, savingsPlan(100.0, 0.0, 800.0).months)
    }

    @Test
    fun `reaching the goal leaves nothing to save and no waiting`() {
        val plan = savingsPlan(goal = 2000.0, saved = 2000.0, monthly = 500.0)

        assertEquals(0.0, plan.remaining, 0.001)
        assertEquals(0, plan.months)
        assertEquals(1f, plan.progress, 0.001f)
        assertTrue(plan.reached)
        assertFalse(plan.needsRate)
    }

    @Test
    fun `saving more than the goal does not report negative money or overflow the bar`() {
        val plan = savingsPlan(goal = 2000.0, saved = 3500.0, monthly = 0.0)

        assertEquals(0.0, plan.remaining, 0.001)
        assertEquals(1f, plan.progress, 0.001f)
        assertTrue(plan.reached)
    }

    @Test
    fun `without a monthly amount the plan asks for one instead of dividing by zero`() {
        val plan = savingsPlan(goal = 2000.0, saved = 0.0, monthly = 0.0)

        assertEquals(0, plan.months)
        assertTrue(plan.needsRate)
        assertFalse(plan.reached)
    }

    @Test
    fun `a goal of zero is not treated as already reached`() {
        val plan = savingsPlan(goal = 0.0, saved = 0.0, monthly = 0.0)

        assertFalse(plan.reached)
        assertEquals(0f, plan.progress, 0.001f)
        assertEquals(0.0, plan.remaining, 0.001)
    }

    @Test
    fun `negative input is clamped rather than trusted`() {
        val plan = savingsPlan(goal = -100.0, saved = -50.0, monthly = -10.0)

        assertEquals(0.0, plan.goal, 0.001)
        assertEquals(0.0, plan.saved, 0.001)
        assertEquals(0.0, plan.monthly, 0.001)
        assertEquals(0.0, plan.remaining, 0.001)
    }

    @Test
    fun `weekly and daily figures come from the year, not from four week months`() {
        val plan = savingsPlan(goal = 10_000.0, saved = 0.0, monthly = 1300.0)

        assertEquals(1300.0 * 12 / 52, plan.weekly, 0.001)
        assertEquals(1300.0 * 12 / 365, plan.daily, 0.001)
    }

    @Test
    fun `the monthly rate needed for a deadline is the remainder split evenly`() {
        assertEquals(500.0, monthlyRateFor(remaining = 2000.0, months = 4), 0.001)
        assertEquals(0.0, monthlyRateFor(remaining = 2000.0, months = 0), 0.001)
        assertEquals(0.0, monthlyRateFor(remaining = 0.0, months = 4), 0.001)
    }

    @Test
    fun `the ready date is the month count added to today`() {
        val today = LocalDate.of(2026, 9, 10)

        assertEquals(LocalDate.of(2026, 12, 10), readyDate(3, today))
        assertEquals(today, readyDate(0, today))
        // End of month arithmetic must not roll into the next month.
        assertEquals(LocalDate.of(2026, 2, 28), readyDate(1, LocalDate.of(2026, 1, 31)))
    }

    @Test
    fun `dates read as a ukrainian sentence`() {
        assertEquals("10 грудня 2026", formatDate(LocalDate.of(2026, 12, 10)))
        assertEquals("1 січня 2027", formatDate(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun `month counts use the three ukrainian plural forms`() {
        assertEquals("1 місяць", monthsLabel(1))
        assertEquals("2 місяці", monthsLabel(2))
        assertEquals("4 місяці", monthsLabel(4))
        assertEquals("5 місяців", monthsLabel(5))
        assertEquals("11 місяців", monthsLabel(11))
        assertEquals("21 місяць", monthsLabel(21))
        assertEquals("22 місяці", monthsLabel(22))
    }

    @Test
    fun `months until a deadline counts only whole months`() {
        val today = LocalDate.of(2026, 9, 10)

        assertEquals(3, monthsUntil(today, LocalDate.of(2026, 12, 10)))
        // One day short of three months is two full contributions, not three.
        assertEquals(2, monthsUntil(today, LocalDate.of(2026, 12, 9)))
        assertEquals(0, monthsUntil(today, LocalDate.of(2026, 10, 9)))
        assertEquals(12, monthsUntil(today, LocalDate.of(2027, 9, 10)))
    }

    @Test
    fun `a deadline today or in the past leaves no months`() {
        val today = LocalDate.of(2026, 9, 10)

        assertEquals(0, monthsUntil(today, today))
        assertEquals(0, monthsUntil(today, LocalDate.of(2026, 1, 1)))
    }

    @Test
    fun `a deadline plan splits what is left across the months available`() {
        val plan = deadlinePlan(
            goal = 3000.0,
            saved = 600.0,
            today = LocalDate.of(2026, 9, 10),
            deadline = LocalDate.of(2026, 12, 10)
        )

        assertEquals(2400.0, plan.remaining, 0.001)
        assertEquals(800.0, plan.monthly, 0.001)
        assertEquals(3, plan.months)
        assertFalse(plan.needsRate)
    }

    @Test
    fun `a deadline under a month demands the whole remainder at once`() {
        val plan = deadlinePlan(
            goal = 3000.0,
            saved = 0.0,
            today = LocalDate.of(2026, 9, 10),
            deadline = LocalDate.of(2026, 9, 25)
        )

        assertEquals(3000.0, plan.remaining, 0.001)
        assertEquals(0.0, plan.monthly, 0.001)
        assertTrue(plan.needsRate)
    }

    @Test
    fun `a deadline plan on a goal already met asks for nothing`() {
        val plan = deadlinePlan(
            goal = 2000.0,
            saved = 2000.0,
            today = LocalDate.of(2026, 9, 10),
            deadline = LocalDate.of(2027, 3, 10)
        )

        assertEquals(0.0, plan.monthly, 0.001)
        assertTrue(plan.reached)
        assertFalse(plan.needsRate)
    }

    @Test
    fun `both directions of the plan agree`() {
        val today = LocalDate.of(2026, 9, 10)
        val deadline = LocalDate.of(2027, 3, 10)

        val fromDate = deadlinePlan(goal = 6000.0, saved = 0.0, today = today, deadline = deadline)
        // Feeding the rate it produced back in must reproduce the same timing.
        val fromRate = savingsPlan(goal = 6000.0, saved = 0.0, monthly = fromDate.monthly)

        assertEquals(1000.0, fromDate.monthly, 0.001)
        assertEquals(6, fromRate.months)
        assertEquals(deadline, readyDate(fromRate.months, today))
    }

    @Test
    fun `typed amounts accept a comma and reject nonsense`() {
        assertEquals(1500.5, parseAmount("1500,5"), 0.001)
        assertEquals(1500.5, parseAmount(" 1500.5 "), 0.001)
        assertEquals(0.0, parseAmount(""), 0.001)
        assertEquals(0.0, parseAmount("багато"), 0.001)
    }

    @Test
    fun `an input box is empty for zero and shows whole numbers without a decimal`() {
        assertEquals("", amountText(0.0))
        assertEquals("1500", amountText(1500.0))
        assertEquals("1500.5", amountText(1500.5))
    }
}

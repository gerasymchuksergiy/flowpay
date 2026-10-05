package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** «Частинами»: a payment with a fixed number of monthly instalments. */
class InstalmentsTest {

    private val today = LocalDate.of(2026, 10, 4)

    /** The locale's grouping space is a no-break one; the expectations use a plain one. */
    private fun plain(text: String?) = text?.replace(' ', ' ')?.replace(' ', ' ')

    /** A phone on six payments of 2 500 ₴ on the 15th, two already behind on 4 October. */
    private val phone = Pay(
        "iPhone частинами", 2_500.0, day = 15,
        instalments = 6, instalmentStart = instalmentStartFor(15, done = 2, today = today)
    )

    @Test
    fun `the form's answers become a first payment`() {
        assertEquals(LocalDate.of(2026, 8, 15), instalmentFirst(phone))
        assertEquals(LocalDate.of(2027, 1, 15), instalmentLast(phone))
        // Nothing behind yet: the first payment is the coming one.
        val fresh = Pay("Курс", 1_000.0, day = 15, instalments = 3, instalmentStart = instalmentStartFor(15, 0, today))
        assertEquals(LocalDate.of(2026, 10, 15), instalmentFirst(fresh))
        // A day already gone this month starts the count next month.
        val late = Pay("Курс", 1_000.0, day = 1, instalments = 3, instalmentStart = instalmentStartFor(1, 0, today))
        assertEquals(LocalDate.of(2026, 11, 1), instalmentFirst(late))
    }

    @Test
    fun `a plan charges only between its first and last payment`() {
        assertFalse(chargesIn(phone, LocalDate.of(2026, 7, 1)))
        assertTrue(chargesIn(phone, LocalDate.of(2026, 8, 1)))
        assertTrue(chargesIn(phone, LocalDate.of(2027, 1, 1)))
        assertFalse(chargesIn(phone, LocalDate.of(2027, 2, 1)))
        assertEquals(0.0, monthlyTotal(listOf(phone), 41.6, today, LocalDate.of(2027, 2, 1)).total, 0.0)
        assertEquals(2_500.0, monthlyTotal(listOf(phone), 41.6, today).total, 0.0)
    }

    @Test
    fun `it counts its payments and says where it is`() {
        assertEquals(2, instalmentsBehind(phone, today))
        assertEquals(4, instalmentsLeft(phone, today))
        assertEquals("платіж 3 з 6 · останній 15 січня", instalmentLine(phone, today))
        val fresh = Pay("Курс", 1_000.0, day = 15, instalments = 3, instalmentStart = instalmentStartFor(15, 0, today))
        assertEquals("перший з 3 — 15 жовтня", instalmentLine(fresh, today))
        assertEquals("усі 6 платежів позаду", instalmentLine(phone, LocalDate.of(2027, 1, 16)))
        assertNull(instalmentLine(Pay("Netflix", 299.0), today))
    }

    @Test
    fun `the year holds only the payments still to come`() {
        assertEquals(10_000.0, yearlyCharge(phone, today), 0.0)
        assertEquals(15_000.0, yearlyCost(phone), 0.0)
    }

    @Test
    fun `a plan whose last payment is behind it leaves the schedule`() {
        val after = LocalDate.of(2027, 1, 16)
        assertFalse(isFinished(phone, LocalDate.of(2027, 1, 15)))
        assertTrue(isFinished(phone, after))
        assertTrue(stillOwing(listOf(phone), emptyList(), after).isEmpty())
        assertNull(nextPayment(listOf(phone), after, 41.6))
        assertTrue(paymentGroups(listOf(phone), after).isEmpty())
        assertTrue(remindersDue(listOf(phone.copy(warnDays = 7)), after).isEmpty())
    }

    @Test
    fun `before it starts, the next payment is its first`() {
        val fresh = Pay("Курс", 1_000.0, day = 1, instalments = 3, instalmentStart = instalmentStartFor(1, 0, today))
        assertEquals(LocalDate.of(2026, 11, 1), nextCharge(fresh, today))
        assertEquals(LocalDate.of(2026, 11, 1), nextPayment(listOf(fresh), today, 41.6)?.date)
    }

    @Test
    fun `the month it ends is said, with what it gives back`() {
        assertEquals("Після 15 січня звільниться 2 500 ₴ на місяць", plain(freedLine(listOf(phone), today, 41.6)))
        // More than half a year away: not yet worth a line.
        val long = phone.copy(instalments = 24)
        assertNull(freedLine(listOf(long), today, 41.6))
    }

    @Test
    fun `a past month inside the plan is counted in its record`() {
        val september = monthRecord(listOf(phone), emptyList(), "2026-09", today, 41.6)
        assertEquals(1, september.plannedCount)
        val july = monthRecord(listOf(phone), emptyList(), "2026-07", today, 41.6)
        assertEquals(0, july.plannedCount)
    }

    @Test
    fun `the form reads its fields`() {
        assertEquals(0, planTotal(false, "6"))
        assertEquals(6, planTotal(true, " 6 "))
        assertEquals(0L, planStart(true, "", "0", "15", today))
        assertEquals(instalmentStartFor(15, 2, today), planStart(true, "6", "2", "15", today))
        // More "done" than there are payments is read as all of them.
        assertEquals(instalmentStartFor(15, 6, today), planStart(true, "6", "9", "15", today))
    }

    @Test
    fun `a plan survives the bin and the backup`() {
        assertEquals(phone, payOf(payJson(phone)))
        val old = JSONObject().put("n", "Netflix").put("a", 299.0).put("d", 28)
        val back = payOf(old)
        assertEquals(0, back.instalments)
        assertFalse(isInstalment(back))
    }

    @Test
    fun `a plan is never an annual fee`() {
        assertFalse(isInstalment(phone.copy(billingMonth = 3)))
    }
}

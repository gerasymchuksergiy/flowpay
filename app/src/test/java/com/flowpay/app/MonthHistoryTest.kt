package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * "Скільки я витратив за вересень", answered on Платежі, row by row.
 */
class MonthHistoryTest {

    private val today = LocalDate.of(2026, 10, 3)
    private val october = "2026-10"
    private val september = "2026-09"

    private val rent = Pay("Оренда квартири", 11_000.0, day = 5)
    private val internet = Pay("Інтернет", 300.0, day = 1)
    // Charged in March only.
    private val domain = Pay("Домен", 600.0, day = 10, billingMonth = 3)

    private fun mark(pay: Pay, month: String, amount: Double = pay.amount) =
        PaidMark(pay.name, month, amount, pay.currency)

    @Test
    fun `the month just ended is there even when nothing was marked in it`() {
        val months = monthRecords(listOf(rent), emptyList(), today, 0.0, atLeast = 2)

        assertEquals(listOf(october, september), months.map { it.month })
        assertEquals(MonthState.UNRECORDED, months[1].state)
    }

    @Test
    fun `asking for two months does not cut a longer record short`() {
        val months = monthRecords(
            listOf(rent),
            listOf(mark(rent, "2026-07")),
            today,
            0.0,
            atLeast = 2
        )

        assertEquals(listOf(october, september, "2026-08", "2026-07"), months.map { it.month })
    }

    @Test
    fun `the default still goes back only as far as the record`() {
        assertEquals(1, monthRecords(listOf(rent), emptyList(), today, 0.0).size)
    }

    @Test
    fun `a paid row says what was paid then, not what the expense costs now`() {
        // Rent went up in October; September must still read September's figure.
        val raised = rent.copy(amount = 12_000.0)
        val lines = monthLines(listOf(raised, internet), listOf(mark(rent, september)), september)

        val rentLine = lines.single { it.pay.name == rent.name }
        assertTrue(rentLine.paid)
        assertEquals(11_000.0, rentLine.amount, 0.001)
        val internetLine = lines.single { it.pay.name == internet.name }
        assertFalse(internetLine.paid)
        assertEquals(300.0, internetLine.amount, 0.001)
    }

    @Test
    fun `an expense deleted since still shows its paid row`() {
        // Its mark is in the month's total, so it has to be in the rows under it.
        val lines = monthLines(listOf(internet), listOf(mark(rent, september)), september)

        val gone = lines.single { it.pay.name == rent.name }
        assertTrue(gone.paid)
        assertEquals(11_000.0, gone.amount, 0.001)
    }

    @Test
    fun `an annual fee is not listed as unpaid in a month it is not due`() {
        val lines = monthLines(listOf(rent, domain), emptyList(), september)

        assertNull(lines.firstOrNull { it.pay.name == domain.name })
        assertEquals(1, monthLines(listOf(rent, domain), emptyList(), "2027-03")
            .count { it.pay.name == domain.name })
    }

    @Test
    fun `an annual fee paid off its month is still shown`() {
        val lines = monthLines(listOf(domain), listOf(mark(domain, september)), september)

        assertTrue(lines.single().paid)
    }

    @Test
    fun `tapping a rebuilt row takes its mark back off`() {
        val marks = listOf(mark(rent, september))
        val gone = monthLines(emptyList(), marks, september).single()

        assertTrue(togglePaid(marks, gone.pay, september).isEmpty())
    }

    // ------------------------------------------------------------ the strip

    @Test
    fun `the strip shows only when something is wrong`() {
        val fine = HealthLine("Фонове оновлення працює", "Ціни й посилки — сьогодні о 17:46", alarm = false)
        val broken = HealthLine("Фонове оновлення не працює", "Ціни й посилки — 3 дні тому", alarm = true)

        assertNull(stripLine(fine))
        assertNull(stripLine(null))
        assertEquals(broken, stripLine(broken))
    }
}

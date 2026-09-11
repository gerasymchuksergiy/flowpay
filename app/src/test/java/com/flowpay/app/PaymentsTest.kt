package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The monthly total is the one number on the expenses screen, and it has to add up
 * two currencies without ever quietly dropping one of them.
 */
class PaymentsTest {

    private val rentInDollars = Pay("Оренда квартири", 250.0, day = 5, currency = USD)
    private val utilities = Pay("Комуналка", 2400.0, day = 20)
    private val internet = Pay("Інтернет", 300.0, day = 1, currency = UAH)

    @Test
    fun `hryvnia only expenses add up without needing a rate`() {
        val total = monthlyTotal(listOf(utilities, internet), usdSellRate = 0.0)

        assertEquals(2700.0, total.uah, 0.001)
        assertEquals(2700.0, total.total, 0.001)
        assertEquals(0.0, total.usd, 0.001)
        assertFalse(total.hasUsd)
        assertFalse(total.rateMissing)
    }

    @Test
    fun `dollar rent is converted and added to the hryvnia expenses`() {
        val total = monthlyTotal(listOf(rentInDollars, utilities, internet), usdSellRate = 44.8)

        assertEquals(2700.0, total.uah, 0.001)
        assertEquals(250.0, total.usd, 0.001)
        assertEquals(11_200.0, total.usdInUah, 0.001)
        assertEquals(13_900.0, total.total, 0.001)
        assertTrue(total.hasUsd)
        assertFalse(total.rateMissing)
    }

    @Test
    fun `without a rate the dollars are reported separately rather than counted as zero hryvnia`() {
        val total = monthlyTotal(listOf(rentInDollars, utilities), usdSellRate = 0.0)

        assertEquals(2400.0, total.total, 0.001)
        assertEquals(250.0, total.usd, 0.001)
        assertEquals(0.0, total.usdInUah, 0.001)
        assertTrue(total.rateMissing)
    }

    @Test
    fun `a negative rate is treated as no rate at all`() {
        val total = monthlyTotal(listOf(rentInDollars), usdSellRate = -44.8)

        assertEquals(0.0, total.usdInUah, 0.001)
        assertEquals(0.0, total.total, 0.001)
        assertTrue(total.rateMissing)
    }

    @Test
    fun `an entry saved before currencies existed counts as hryvnia`() {
        val legacy = Pay("Мобільний", 200.0)

        assertEquals(UAH, legacy.currency)
        assertEquals(200.0, monthlyTotal(listOf(legacy), 44.8).total, 0.001)
    }

    @Test
    fun `an empty list costs nothing and needs no rate`() {
        val total = monthlyTotal(emptyList(), usdSellRate = 44.8)

        assertEquals(0.0, total.total, 0.001)
        assertFalse(total.hasUsd)
        assertFalse(total.rateMissing)
    }

    @Test
    fun `free money is income less everything standing`() {
        val month = budget(45_000.0, monthlyTotal(listOf(rentInDollars, utilities, internet), 44.8))

        assertEquals(45_000.0, month.income, 0.001)
        assertEquals(13_900.0, month.expenses, 0.001)
        assertEquals(31_100.0, month.free, 0.001)
        assertFalse(month.overspent)
        assertFalse(month.unknown)
    }

    @Test
    fun `a month that does not fit reports a shortfall rather than clamping to zero`() {
        val month = budget(10_000.0, monthlyTotal(listOf(rentInDollars, utilities), 44.8))

        assertEquals(-3_600.0, month.free, 0.001)
        assertTrue(month.overspent)
    }

    @Test
    fun `no income means nothing can be said about free money`() {
        val month = budget(0.0, monthlyTotal(listOf(utilities), 44.8))

        assertTrue(month.unknown)
        assertFalse(month.overspent)
    }

    @Test
    fun `income with no expenses is entirely free`() {
        val month = budget(20_000.0, monthlyTotal(emptyList(), 44.8))

        assertEquals(20_000.0, month.free, 0.001)
        assertFalse(month.overspent)
    }

    @Test
    fun `several dollar expenses are summed before conversion`() {
        val total = monthlyTotal(
            listOf(rentInDollars, Pay("Хостинг", 30.0, currency = USD)),
            usdSellRate = 44.0
        )

        assertEquals(280.0, total.usd, 0.001)
        assertEquals(12_320.0, total.total, 0.001)
    }

    // ------------------------------------------------------- how it reads

    /**
     * Groups of thousands are separated by a non-breaking space in this locale,
     * which is right on screen and unreadable in a failure message.
     */
    private fun shown(text: String) = text.replace('\u00a0', ' ')

    @Test
    fun `hryvnia never shows a fraction of a kopeck`() {
        // 250 $ at 44,8009 is 11 200,225 — three decimals, which the screen showed.
        assertEquals("11 200,23 ₴", shown(money(11_200.225)))
    }

    @Test
    fun `a whole amount carries no decimals at all`() {
        assertEquals("40 000 ₴", shown(money(40_000.0)))
        assertEquals("250 $", shown(dollars(250.0)))
    }

    @Test
    fun `kopecks and cents survive when they are real`() {
        assertEquals("2 203,24 ₴", shown(money(2_203.24)))
        assertEquals("9,59 $", shown(dollars(9.59)))
    }

    @Test
    fun `a converted figure is rounded to whole hryvnia`() {
        // An estimate at today's rate has no business claiming kopecks.
        assertEquals("11 200 ₴", shown(approxMoney(250.0 * 44.8009)))
        assertEquals("45 ₴", shown(approxMoney(44.6)))
    }

    @Test
    fun `an amount is labelled in the currency it was entered in`() {
        assertEquals("9,59 $", shown(amountLabel(9.59, USD)))
        assertEquals("8 000 ₴", shown(amountLabel(8_000.0, UAH)))
    }

    // ------------------------------------------------ when the money leaves

    private val dueRent = Pay("Оренда квартири", 250.0, 1, USD)
    private val dueFood = Pay("Скинутись на їжу", 8_000.0, 1)
    private val dueInternet = Pay("Інтернет", 300.0, 10)
    private val dueAll = listOf(dueRent, dueFood, dueInternet)

    @Test
    fun `something due today is nought days away, not one`() {
        val next = nextPayment(dueAll, LocalDate.of(2026, 9, 1), 44.0)!!

        assertEquals(0, next.daysAway)
        assertEquals(LocalDate.of(2026, 9, 1), next.date)
        assertEquals(listOf(dueRent, dueFood), next.items)
        assertEquals(19_000.0, next.total.total, 0.001)
    }

    @Test
    fun `once a day has passed the search moves into next month`() {
        // The 10th is behind us, so the 1st of October is what comes next.
        val next = nextPayment(dueAll, LocalDate.of(2026, 9, 11), 44.0)!!

        assertEquals(LocalDate.of(2026, 10, 1), next.date)
        assertEquals(20, next.daysAway)
    }

    @Test
    fun `the nearer of two dates wins`() {
        val next = nextPayment(dueAll, LocalDate.of(2026, 9, 5), 44.0)!!

        assertEquals(LocalDate.of(2026, 9, 10), next.date)
        assertEquals(listOf(dueInternet), next.items)
    }

    @Test
    fun `a missing rate stays visible instead of reading as a smaller bill`() {
        val next = nextPayment(listOf(dueRent), LocalDate.of(2026, 9, 1), usdSellRate = 0.0)!!

        assertTrue(next.total.rateMissing)
        assertEquals(250.0, next.total.usd, 0.001)
    }

    @Test
    fun `nothing to pay means nothing to announce`() {
        assertNull(nextPayment(emptyList(), LocalDate.of(2026, 9, 1), 44.0))
    }

    @Test
    fun `the thirty-first falls on the last day a short month has`() {
        val end = Pay("Хостинг", 200.0, 31)

        assertEquals(
            LocalDate.of(2026, 9, 30),
            nextPayment(listOf(end), LocalDate.of(2026, 9, 5), 44.0)!!.date
        )
        assertEquals(
            LocalDate.of(2027, 2, 28),
            nextPayment(listOf(end), LocalDate.of(2027, 2, 1), 44.0)!!.date
        )
    }

    @Test
    fun `the countdown is said the way Ukrainian says it`() {
        assertEquals("сьогодні", dueLabel(0))
        assertEquals("завтра", dueLabel(1))
        assertEquals("через 2 дні", dueLabel(2))
        assertEquals("через 5 днів", dueLabel(5))
        assertEquals("через 11 днів", dueLabel(11))
        assertEquals("через 21 день", dueLabel(21))
    }

    // -------------------------------------------------- grouped for the list

    @Test
    fun `expenses sharing a date are one group, soonest first`() {
        val groups = paymentGroups(dueAll, LocalDate.of(2026, 9, 5))

        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2026, 9, 10), groups[0].date)
        assertEquals(listOf(2), groups[0].positions)
        assertEquals(LocalDate.of(2026, 10, 1), groups[1].date)
        assertEquals(listOf(0, 1), groups[1].positions)
    }

    @Test
    fun `a group carries positions, so identical entries stay distinguishable`() {
        // Two expenses equal as values: deleting "one of them" must mean a definite one.
        val twins = listOf(dueFood, dueFood)

        val groups = paymentGroups(twins, LocalDate.of(2026, 9, 1))

        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1), groups[0].positions)
    }

    @Test
    fun `nothing to show is an empty timeline, not a group of nothing`() {
        assertTrue(paymentGroups(emptyList(), LocalDate.of(2026, 9, 1)).isEmpty())
    }

    @Test
    fun `a panel says how many when naming them all would be a paragraph`() {
        // Six names wrapped the panel to three lines and repeated the list below it.
        assertEquals("Інтернет", dueSummary(listOf(dueInternet)))
        assertEquals("Оренда квартири, Скинутись на їжу", dueSummary(listOf(dueRent, dueFood)))
        assertEquals("3 платежі", dueSummary(dueAll))
        assertEquals("", dueSummary(emptyList()))
    }

    @Test
    fun `payments are counted the way Ukrainian counts them`() {
        assertEquals("1 платіж", paymentsLabel(1))
        assertEquals("2 платежі", paymentsLabel(2))
        assertEquals("5 платежів", paymentsLabel(5))
        assertEquals("11 платежів", paymentsLabel(11))
        assertEquals("21 платіж", paymentsLabel(21))
    }

    @Test
    fun `the strip marks every day that carries a payment`() {
        assertEquals(setOf(1, 10), paymentDays(dueAll, monthLength = 30))
        // A day past the end of the month is drawn on the last one.
        assertEquals(setOf(28), paymentDays(listOf(Pay("Хостинг", 1.0, 31)), monthLength = 28))
    }
}

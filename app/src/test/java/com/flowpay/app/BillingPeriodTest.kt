package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A subscription billed once a year is the quietest lie a tracker can tell.
 *
 * It charges in one month out of twelve, so every "наступні 30 днів" view hid it
 * for eleven of them, while the annual figure multiplied it by twelve on top. Both
 * halves are wrong in opposite directions, and a person who saw either would plan
 * a month that does not exist.
 */
class BillingPeriodTest {

    private val today = LocalDate.of(2026, 9, 15)

    /** ₴1 200 a year, taken on the 14th of March. */
    private val domain = Pay("Домен", 1_200.0, day = 14, billingMonth = 3)
    private val internet = Pay("Інтернет", 300.0, day = 1)

    @Test
    fun `an expense with no month set is charged every month, as every old one was`() {
        assertFalse(isAnnual(internet))
        assertTrue(chargesIn(internet, today))
        assertTrue(chargesIn(internet, today.plusMonths(5)))
    }

    @Test
    fun `an annual charge counts in its own month and nowhere else`() {
        assertTrue(isAnnual(domain))
        assertTrue(chargesIn(domain, LocalDate.of(2026, 3, 1)))
        assertFalse(chargesIn(domain, today))
    }

    @Test
    fun `a year of an annual expense is the amount itself, not twelve of it`() {
        assertEquals(1_200.0, yearlyCost(domain), 0.001)
        assertEquals(3_600.0, yearlyCost(internet), 0.001)
    }

    @Test
    fun `the month it does not charge in shows none of it`() {
        val total = monthlyTotal(listOf(domain, internet), usdSellRate = 0.0, today = today)

        assertEquals(300.0, total.total, 0.001)
    }

    @Test
    fun `the month it does charge in shows all of it, not a twelfth`() {
        val march = LocalDate.of(2026, 3, 10)
        val total = monthlyTotal(listOf(domain, internet), usdSellRate = 0.0, today = march)

        // 1 200 in March, not 100 every month. A budget shown 100 in March is
        // short by 1 100 on the one day of the year it matters.
        assertEquals(1_500.0, total.total, 0.001)
    }

    @Test
    fun `the yearly total counts an annual charge once`() {
        val yearly = yearlyTotal(listOf(domain, internet), usdSellRate = 0.0, today = today)

        assertEquals(1_200.0 + 3_600.0, yearly.total, 0.001)
    }

    @Test
    fun `a dollar annual fee still converts, and still counts once`() {
        val hosting = Pay("Хостинг", 60.0, day = 2, currency = USD, billingMonth = 11)
        val yearly = yearlyTotal(listOf(hosting), usdSellRate = 42.0, today = today)

        assertEquals(60.0, yearly.usd, 0.001)
        assertEquals(2_520.0, yearly.total, 0.001)
        assertFalse(yearly.rateMissing)
    }

    @Test
    fun `an annual charge is not due on its day in every other month`() {
        assertTrue(paymentsDueOn(listOf(domain), LocalDate.of(2026, 3, 14)).isNotEmpty())
        assertTrue(paymentsDueOn(listOf(domain), LocalDate.of(2026, 9, 14)).isEmpty())
    }

    @Test
    fun `the next charge is next year once this year's has gone`() {
        val next = nextPayment(listOf(domain), today, usdSellRate = 0.0)

        assertEquals(LocalDate.of(2027, 3, 14), next?.date)
        assertEquals(1_200.0, next?.total?.total ?: 0.0, 0.001)
    }

    @Test
    fun `a charge on the 31st of February lands on a date that exists`() {
        val leap = Pay("Страховка", 900.0, day = 31, billingMonth = 2)
        val next = nextPayment(listOf(leap), LocalDate.of(2026, 1, 5), usdSellRate = 0.0)

        assertEquals(LocalDate.of(2026, 2, 28), next?.date)
    }

    @Test
    fun `a trial on an annual fee pushes the first charge a whole year, not a month`() {
        // Free until October; the fee is taken in March, so October is not a
        // charging month and the first real charge is the following March.
        val free = domain.copy(trialEnd = LocalDate.of(2026, 10, 1).toEpochDay())
        val next = nextPayment(listOf(free), today, usdSellRate = 0.0)

        assertEquals(LocalDate.of(2027, 3, 14), next?.date)
    }

    @Test
    fun `the first-charge note names the charging month, not the month the trial ends`() {
        val note = firstChargeNote(
            day = 14,
            trialEnd = LocalDate.of(2026, 10, 1).toEpochDay(),
            today = today,
            billingMonth = 3
        )

        assertEquals("Перше списання 14 березня", note)
    }

    @Test
    fun `the timeline drops an annual charge that is months away`() {
        val groups = paymentGroups(listOf(domain, internet), today)

        assertEquals(1, groups.size)
        assertEquals(listOf(1), groups.first().positions)
    }

    @Test
    fun `the timeline keeps an annual charge once it is close`() {
        val soon = LocalDate.of(2026, 3, 1)
        val groups = paymentGroups(listOf(domain, internet), soon)

        assertEquals(2, groups.size)
    }

    @Test
    fun `what the timeline drops is exactly what the annual section picks up`() {
        val shown = paymentGroups(listOf(domain, internet), today)
            .flatMap { it.positions }
            .map { listOf(domain, internet)[it] }
        val dormant = annualElsewhere(listOf(domain, internet), today)

        assertEquals(listOf(domain, internet).size, shown.size + dormant.size)
        assertEquals(listOf(domain), dormant)
    }

    @Test
    fun `the annual section says nothing at all when nothing is billed annually`() {
        assertTrue(annualElsewhere(listOf(internet), today).isEmpty())
        assertNull(annualElsewhereNote(listOf(internet), today, usdSellRate = 0.0))
    }

    @Test
    fun `the annual row states the real date and the real amount`() {
        assertEquals("14 березня · ${money(1_200.0)}", annualDueLine(domain, today))
    }

    @Test
    fun `the annual section totals the real charges without dividing them by twelve`() {
        val note = annualElsewhereNote(listOf(domain, internet), today, usdSellRate = 0.0)

        assertEquals("Цього місяця не списуються · ${money(1_200.0)} протягом року", note)
    }

    @Test
    fun `an annual row carries both denominators and names the rhythm between them`() {
        assertEquals("раз на рік · ≈100 ₴/міс", billingLine(domain))
    }

    @Test
    fun `a monthly row still leads with the figure that changes minds`() {
        assertEquals("${money(3_600.0)} на рік", billingLine(internet))
    }

    @Test
    fun `per-day normalisation puts the two rhythms on one scale`() {
        val monthlyPerDay = perDay(internet)
        val annualPerDay = perDay(domain)

        assertEquals(3_600.0 / DAYS_IN_YEAR, monthlyPerDay, 0.0001)
        assertEquals(1_200.0 / DAYS_IN_YEAR, annualPerDay, 0.0001)
        assertTrue(monthlyPerDay > annualPerDay)
    }

    @Test
    fun `a month's record plans the annual fee into that month and no other`() {
        val march = monthRecord(listOf(domain, internet), emptyList(), "2026-03", today, 0.0)
        val august = monthRecord(listOf(domain, internet), emptyList(), "2026-08", today, 0.0)

        assertEquals(1_500.0, march.planned.total, 0.001)
        assertEquals(2, march.plannedCount)
        assertEquals(300.0, august.planned.total, 0.001)
        assertEquals(1, august.plannedCount)
    }

    @Test
    fun `a month is settled once what actually charged in it is marked`() {
        val marks = listOf(PaidMark("Інтернет", "2026-08", 300.0))
        val august = monthRecord(listOf(domain, internet), marks, "2026-08", today, 0.0)

        // The domain fee is unmarked and should be: nothing was taken in August.
        assertEquals(MonthState.SETTLED, august.state)
    }

    @Test
    fun `a stored month outside the twelve is read as every month rather than trusted`() {
        val restored = payOf(payJson(domain.copy(billingMonth = 13)))

        assertEquals(0, restored.billingMonth)
    }

    @Test
    fun `the billing month survives being written and read back`() {
        val restored = payOf(payJson(domain))

        assertEquals(3, restored.billingMonth)
        assertEquals(domain, restored)
    }
}

package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
}

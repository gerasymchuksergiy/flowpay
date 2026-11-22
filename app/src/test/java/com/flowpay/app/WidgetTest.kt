package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The home screen widget has no screen to fall back on.
 *
 * Everything it shows is decided here and handed over as finished text, so these
 * are the only tests that can catch a widget saying "1 посилки", a rent quoted in
 * dollars silently missing from a total, or an empty app showing a blank panel.
 */
class WidgetTest {

    private val today = LocalDate.of(2026, 9, 12)

    private fun parcel(status: String) =
        Order(id = status, name = "Посилка", url = "", status = status)

    private fun summary(
        pays: List<Pay> = emptyList(),
        orders: List<Order> = emptyList(),
        income: Double = 30_000.0,
        rate: Double = 41.0
    ) = widgetSummary(pays, orders, income, rate, today)

    // ------------------------------------------------------------- next payment

    @Test
    fun `the nearest payment is named with its date and how far off it is`() {
        val pays = listOf(Pay("Інтернет", 300.0, day = 14), Pay("Комуналка", 2_400.0, day = 25))

        val shown = summary(pays)

        assertTrue(shown.hasPayment)
        assertEquals("Інтернет", shown.paymentName)
        assertEquals("14 вересня", shown.paymentDate)
        assertEquals("через 2 дні", shown.paymentCountdown)
        assertEquals("300 ₴", shown.paymentAmount)
    }

    @Test
    fun `something due today says so rather than counting nought days`() {
        val shown = summary(listOf(Pay("Оренда", 1_000.0, day = 12)))

        assertEquals("сьогодні", shown.paymentCountdown)
    }

    @Test
    fun `several payments on one date are counted instead of listed`() {
        val pays = listOf(
            Pay("Інтернет", 300.0, day = 14),
            Pay("Спортзал", 800.0, day = 14),
            Pay("Музика", 100.0, day = 14)
        )

        assertEquals("3 платежі", summary(pays).paymentName)
    }

    @Test
    fun `a payment set for the 31st is found in a short month`() {
        val shown = widgetSummary(
            pays = listOf(Pay("Оренда", 1_000.0, day = 31)),
            orders = emptyList(),
            income = 30_000.0,
            usdSellRate = 41.0,
            today = LocalDate.of(2026, 2, 20)
        )

        assertEquals("28 лютого", shown.paymentDate)
    }

    @Test
    fun `a dollar payment with no rate is admitted rather than shown as nought`() {
        val pays = listOf(Pay("Оренда", 250.0, day = 14, currency = USD))

        val shown = summary(pays, rate = 0.0)

        assertEquals("250 $", shown.paymentAmount)
    }

    @Test
    fun `a mixed date with no rate keeps both halves visible`() {
        val pays = listOf(
            Pay("Оренда", 250.0, day = 14, currency = USD),
            Pay("Інтернет", 300.0, day = 14)
        )

        assertEquals("300 ₴ + 250 $", summary(pays, rate = 0.0).paymentAmount)
    }

    @Test
    fun `an app with no expenses says so instead of leaving the panel blank`() {
        val shown = summary()

        assertFalse(shown.hasPayment)
        assertEquals("Платежів не заплановано", shown.paymentName)
        assertEquals("", shown.paymentDate)
        assertEquals("", shown.paymentAmount)
    }

    // ----------------------------------------------------------------- the month

    @Test
    fun `free cash is what is left after the standing costs`() {
        val pays = listOf(Pay("Оренда", 10_000.0, day = 5), Pay("Інтернет", 300.0, day = 14))

        assertEquals("Вільно 19 700 ₴", summary(pays).freeCash.replace(' ', ' '))
    }

    @Test
    fun `a month that does not fit is said out loud`() {
        val pays = listOf(Pay("Оренда", 40_000.0, day = 5))

        assertEquals("Бракує 10 000 ₴", summary(pays).freeCash.replace(' ', ' '))
    }

    @Test
    fun `with no income entered the widget does not invent a figure`() {
        val pays = listOf(Pay("Оренда", 10_000.0, day = 5))

        assertEquals("Дохід не вказано", summary(pays, income = 0.0).freeCash)
    }

    // ---------------------------------------------------------------- the parcels

    @Test
    fun `only parcels waiting at a branch are counted`() {
        val orders = listOf(
            parcel(AT_BRANCH),
            parcel(IN_TRANSIT),
            parcel(ORDERED),
            parcel(RECEIVED)
        )

        assertEquals("1 посилка на відділенні", summary(orders = orders).parcels)
    }

    @Test
    fun `nothing waiting is a statement, not an empty line`() {
        assertEquals("На відділенні порожньо", summary().parcels)
    }

    @Test
    fun `the parcel count declines the way Ukrainian requires`() {
        assertEquals("1 посилка", parcelsLabel(1))
        assertEquals("2 посилки", parcelsLabel(2))
        assertEquals("5 посилок", parcelsLabel(5))
        assertEquals("11 посилок", parcelsLabel(11))
        assertEquals("12 посилок", parcelsLabel(12))
        assertEquals("21 посилка", parcelsLabel(21))
        assertEquals("22 посилки", parcelsLabel(22))
        assertEquals("0 посилок", parcelsLabel(0))
    }
}

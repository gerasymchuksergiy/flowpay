package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * One test per defect the October audit found, named after what went wrong.
 */
class AuditFixesTest {

    // ------------------------------------------------------------ reading pages

    @Test
    fun `an apostrophe inside a title does not cut it short`() {
        val page = """<meta property="og:title" content="Карта пам'яті Kingston 64GB"/>"""
        assertEquals("Карта пам'яті Kingston 64GB", metaContent(page, "og:title"))
        val reversed = """<meta content="М'який чохол" property="og:title">"""
        assertEquals("М'який чохол", metaContent(reversed, "og:title"))
        val singleQuoted = """<meta property='og:title' content='Чохол "Люкс"'>"""
        assertEquals("Чохол \"Люкс\"", metaContent(singleQuoted, "og:title"))
    }

    @Test
    fun `a comma grouping thousands is not a decimal point`() {
        assertEquals(1299.0, priceNumber("1,299")!!, 0.001)
        assertEquals(1299.5, priceNumber("1.299,50")!!, 0.001)
        assertEquals(1234.56, priceNumber("1,234.56")!!, 0.001)
        assertEquals(2199.5, priceNumber("2199,50")!!, 0.001)
        // A rating is never grouped.
        assertEquals(4.667, priceNumber("4,667", grouping = false)!!, 0.0001)
    }

    @Test
    fun `a bare number after price is not read as one`() {
        val minorUnits = """<script>{"name":"X","price":145821}</script>"""
        assertTrue(extractOffers(minorUnits).none { it.price == 145821.0 })
    }

    @Test
    fun `a price string after an empty one and a long url is still found`() {
        val longUrl = "https://img.example/" + "a".repeat(80) + ".jpg"
        val script = """<script>{"a":"","img":"$longUrl","priceStr":"1 458.21₴"}</script>"""
        assertEquals(1458.21, extractOffers(script).single().price, 0.001)
    }

    @Test
    fun `the marketplace's name comes off a title`() {
        assertEquals("nova 2 lite бездротовий ігровий для пк з", cleanProductTitle("nova 2 lite бездротовий ігровий для пк з - Temu Ukraine"))
        // A tail that is not a marketplace stays: it may be the colour.
        assertEquals("Чохол - чорний", cleanProductTitle("Чохол - чорний"))
        assertEquals("Навушники", withoutShopSuffix("Навушники | Цифрус", "Цифрус"))
        assertEquals("Навушники | Цифрус", withoutShopSuffix("Навушники | Цифрус", ""))
    }

    @Test
    fun `the product photograph beats a sharing banner`() {
        val page = """
            <meta property="og:image" content="https://share.example/banner.png"/>
            <script type="application/ld+json">{"@type":"Product","name":"X",
              "image":[{"@type":"ImageObject","contentURL":"https://img.kwcdn.com/product/x.jpg"}]}</script>
        """.trimIndent()
        assertEquals("https://img.kwcdn.com/product/x.jpg", pageFacts(page).image)
        // With nothing declared, og:image is still used.
        assertEquals("https://share.example/banner.png", pageFacts(page.substringBefore("<script")).image)
    }

    @Test
    fun `one reviewer's stars are not the product's rating`() {
        val page = """
            <script type="application/ld+json">{"@type":"Product","name":"X","brand":"Temu",
              "review":[{"@type":"Review","reviewRating":{"ratingValue":"5"},"description":"Супер"}],
              "aggregateRating":{"@type":"AggregateRating","ratingValue":"4.7","reviewCount":"925"}}</script>
        """.trimIndent()
        val about = extractAbout(page)
        assertEquals(4.7, about.rating, 0.001)
        assertEquals(925, about.ratingCount)
        assertEquals("", about.brand)
    }

    // ------------------------------------------------------------ prices

    private fun wish(price: Double, freshness: Freshness, target: Double = 0.0) = Wish(
        id = "w", name = "Ніж", url = "https://shop.example/x", image = "",
        price = price, history = emptyList(), targetPrice = target, freshness = freshness
    )

    @Test
    fun `back in stock only after sold out`() {
        assertEquals(AlertKind.BACK_IN_STOCK, priceAlertFor(wish(900.0, Freshness.OUT_OF_STOCK), 900.0).kind)
        assertFalse(priceAlertFor(wish(0.0, Freshness.UNREADABLE), 900.0).kind == AlertKind.BACK_IN_STOCK)
    }

    @Test
    fun `the first real price of a wish with a target can reach it`() {
        assertEquals(
            AlertKind.TARGET_REACHED,
            priceAlertFor(wish(0.0, Freshness.UNREADABLE, target = 1000.0), 900.0).kind
        )
    }

    @Test
    fun `a rising price is not called an inflated discount`() {
        val rising = listOf(PricePoint(1500.0, 100L), PricePoint(900.0, 105L), PricePoint(1200.0, 110L))
        assertNull(priceInsight(rising, 1200.0, 112L).priorLow)
        val inflated = listOf(PricePoint(900.0, 100L), PricePoint(1500.0, 105L), PricePoint(1200.0, 110L))
        assertEquals(900.0, priceInsight(inflated, 1200.0, 112L).priorLow!!.price, 0.001)
    }

    @Test
    fun `how far above the low is measured from the low`() {
        val history = listOf(PricePoint(1000.0, 100L), PricePoint(1500.0, 105L), PricePoint(1400.0, 110L))
        assertEquals(40.0, overLowPercent(priceInsight(history, 1400.0, 112L)), 0.001)
    }

    // ------------------------------------------------------------ typed figures

    @Test
    fun `a prefilled figure is rounded to kopecks`() {
        assertEquals("8863.51", amountText(8863.509999999998))
        assertEquals("2199", amountText(2199.0))
        assertEquals("2.5", amountText(2.5))
        assertEquals("", amountText(0.0))
    }

    @Test
    fun `a figure with spaces in it is read`() {
        assertEquals(1500.0, parseAmount("1 500"), 0.001)
        assertEquals(1500.5, parseAmount("1 500,50"), 0.001)
        assertEquals(0.0, parseAmount("abc"), 0.001)
    }

    // ------------------------------------------------------------ payments

    private val rent = Pay("Оренда", 11_000.0, day = 1, warnDays = 3)

    @Test
    fun `just after the charge the tick is about the month just paid`() {
        assertEquals("2026-10", tickMonth(rent, LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun `inside the reminder window the tick is about the coming charge`() {
        assertEquals("2026-11", tickMonth(rent, LocalDate.of(2026, 10, 29)))
    }

    @Test
    fun `a charge later this month is this month`() {
        val internet = Pay("Інтернет", 300.0, day = 20)
        assertEquals("2026-10", tickMonth(internet, LocalDate.of(2026, 10, 3)))
    }

    @Test
    fun `next month's mark survives pruning, the month after does not`() {
        val today = LocalDate.of(2026, 10, 29)
        val kept = prunePaidMarks(
            listOf(PaidMark("Оренда", "2026-11", 11_000.0), PaidMark("Оренда", "2026-12", 11_000.0)),
            today
        )
        assertEquals(listOf("2026-11"), kept.map { it.month })
    }

    @Test
    fun `a rename carries the marks across`() {
        val marks = listOf(PaidMark("Оренда", "2026-09", 11_000.0), PaidMark("Інтернет", "2026-09", 300.0))
        val renamed = renamePaidMarks(marks, "Оренда", "Оренда квартири")
        assertEquals(listOf("Оренда квартири", "Інтернет"), renamed.map { it.name })
    }

    // ------------------------------------------------------------ carriers

    private fun parcel(number: String) = Order("o", "x", "https://shop.example", ORDERED, tracking = number)

    @Test
    fun `a number names its carrier's own tracking page`() {
        assertEquals("Укрпошти", trackingSite(parcel("0500100031143"))!!.name)
        assertEquals("Укрпошти", trackingSite(parcel("RR123456785UA"))!!.name)
        assertEquals("17TRACK", trackingSite(parcel("RL778364634EE"))!!.name)
        assertNull(trackingSite(parcel("20450000000001")))
    }
}

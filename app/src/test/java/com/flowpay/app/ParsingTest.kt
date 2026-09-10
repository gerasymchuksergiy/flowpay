package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the part of FlowPay most likely to break without anyone noticing.
 *
 * Reading a price off a shop page depends on markup nobody here controls, so these
 * tests pin the behaviour against saved fragments in both attribute orders shops
 * actually emit.
 */
class ParsingTest {

    private val page = """
        <html><head>
        <meta property="og:title" content="Чоловічі кросівки ASICS Gel 1130, ціна 2203.24"/>
        <meta content="https://shop.example/photo.jpg" property="og:image"/>
        <meta property="product:price:amount" content="2203.24"/>
        </head><body></body></html>
    """.trimIndent()

    @Test
    fun `meta content is read in either attribute order`() {
        assertEquals("2203.24", metaContent(page, "product:price:amount"))
        assertEquals("https://shop.example/photo.jpg", metaContent(page, "og:image"))
    }

    @Test
    fun `a missing meta tag yields an empty string rather than throwing`() {
        assertEquals("", metaContent(page, "og:description"))
    }

    @Test
    fun `the price appended to a title is dropped`() {
        assertEquals(
            "Чоловічі кросівки ASICS Gel 1130",
            cleanProductTitle("Чоловічі кросівки ASICS Gel 1130, ціна 2203.24")
        )
        assertEquals(
            "Куртка зимова",
            cleanProductTitle("Куртка зимова — ціна 4 500 грн")
        )
        assertEquals(
            "Навушники Sony",
            cleanProductTitle("Навушники Sony | ціна 7999₴")
        )
    }

    @Test
    fun `a title that is only a price is left alone rather than emptied`() {
        assertEquals("ціна 2203.24", cleanProductTitle("ціна 2203.24"))
    }

    @Test
    fun `a genuine number in a name survives`() {
        assertEquals("iPhone 15 Pro 256 ГБ", cleanProductTitle("iPhone 15 Pro 256 ГБ"))
        assertEquals("ASICS Gel 1130", cleanProductTitle("ASICS  Gel   1130 "))
    }

    @Test
    fun `html entities are decoded and ampersands are not double decoded`() {
        assertEquals("""Куртка "Зима"""", cleanProductTitle("Куртка &quot;Зима&quot;"))
        assertEquals("Dolce & Gabbana", cleanProductTitle("Dolce &amp; Gabbana"))
        assertEquals("""&quot;""", decodeEntities("&amp;quot;"))
    }

    @Test
    fun `price comes from open graph first and json second`() {
        assertEquals(2203.24, extractPrice(page), 0.001)
        val jsonOnly = """<script>{"name":"X","price": "1499,50"}</script>"""
        assertEquals(1499.50, extractPrice(jsonOnly), 0.001)
    }

    @Test
    fun `a page with no price reports zero`() {
        assertEquals(0.0, extractPrice("<html></html>"), 0.001)
    }

    @Test
    fun `parsing a product keeps the requested url and id`() {
        val wish = parseProduct(page, "https://shop.example/item", "fixed-id")

        assertEquals("fixed-id", wish.id)
        assertEquals("https://shop.example/item", wish.url)
        assertEquals("Чоловічі кросівки ASICS Gel 1130", wish.name)
        assertEquals("https://shop.example/photo.jpg", wish.image)
        assertEquals(2203.24, wish.price, 0.001)
        assertEquals(listOf(2203.24), wish.history)
    }

    @Test
    fun `a product page without a price is rejected`() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            parseProduct("<html><head></head></html>", "https://shop.example/item", "id")
        }
        assertTrue(failure.message!!.contains("ціну"))
    }

    @Test
    fun `a product page without a title falls back to a placeholder name`() {
        val noTitle = """<meta property="product:price:amount" content="100"/>"""
        assertEquals("Новий товар", parseProduct(noTitle, "https://shop.example/x", "id").name)
    }

    @Test
    fun `the usd pair is picked out of the currency feed`() {
        val feed = """
            [
              {"currencyCodeA":978,"currencyCodeB":980,"rateBuy":47.0,"rateSell":47.9},
              {"currencyCodeA":840,"currencyCodeB":980,"rateBuy":44.4,"rateSell":44.8}
            ]
        """.trimIndent()

        val rate = parseUsdRate(feed)

        assertEquals(44.4, rate.buy, 0.001)
        assertEquals(44.8, rate.sell, 0.001)
    }

    @Test
    fun `a feed without the usd pair yields an empty rate instead of throwing`() {
        val feed = """[{"currencyCodeA":978,"currencyCodeB":980,"rateBuy":47.0,"rateSell":47.9}]"""

        val rate = parseUsdRate(feed)

        assertEquals(0.0, rate.buy, 0.001)
        assertEquals(0.0, rate.sell, 0.001)
    }

    @Test
    fun `measurement counts use the three ukrainian plural forms`() {
        assertEquals("1 вимірювання", measurementsLabel(1))
        assertEquals("2 вимірювання", measurementsLabel(2))
        assertEquals("4 вимірювання", measurementsLabel(4))
        assertEquals("5 вимірювань", measurementsLabel(5))
        assertEquals("11 вимірювань", measurementsLabel(11))
        assertEquals("14 вимірювань", measurementsLabel(14))
        assertEquals("21 вимірювання", measurementsLabel(21))
        assertEquals("22 вимірювання", measurementsLabel(22))
        assertEquals("25 вимірювань", measurementsLabel(25))
        assertEquals("0 вимірювань", measurementsLabel(0))
    }
}

package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading the currency off a page, and converting what it says into hryvnia.
 *
 * The bug underneath all of this: `$59.99` used to be recorded as `59,99 ₴`, and
 * the app announced a ninety-nine per cent drop on a thing that had not moved.
 */
class CurrencyTest {

    private val rate = FxRate(41.0, 41.34, SOURCE_MONOBANK)

    @Test
    fun `json-ld states the currency beside the price`() {
        val html = """
            <html><script type="application/ld+json">
            {"@type":"Product","offers":{"price":"59.99","priceCurrency":"USD"}}
            </script></html>
        """.trimIndent()

        val offer = extractOffers(html).first()

        assertEquals(59.99, offer.price, 0.001)
        assertEquals(USD, offer.currency)
    }

    @Test
    fun `a currency on the product carries down to the offers nested in it`() {
        val html = """
            <html><script type="application/ld+json">
            {"@type":"Product","priceCurrency":"USD","offers":[
              {"name":"Standard","price":"59.99"},
              {"name":"Deluxe","price":"89.99"}
            ]}
            </script></html>
        """.trimIndent()

        val offers = extractOffers(html)

        assertEquals(listOf(USD, USD), offers.map { it.currency })
        assertEquals(listOf("Standard", "Deluxe"), offers.map { it.label })
    }

    @Test
    fun `microdata states the currency in an attribute`() {
        val html = """
            <div itemprop="price" content="2199"></div>
            <meta itemprop="priceCurrency" content="UAH">
        """.trimIndent()

        assertEquals(UAH, pageCurrency(html))
        assertEquals(UAH, extractOffers(html).first().currency)
    }

    @Test
    fun `open graph is read when there is no structured data at all`() {
        val html = """
            <meta property="og:price:amount" content="129.00">
            <meta property="og:price:currency" content="EUR">
        """.trimIndent()

        assertEquals("EUR", extractOffers(html).first().currency)
    }

    @Test
    fun `a page that states no currency leaves it empty rather than guessing`() {
        val html = """<div itemprop="price" content="2199"></div>"""

        assertEquals("", extractOffers(html).first().currency)
    }

    @Test
    fun `a page-wide currency labels prices found anywhere on it`() {
        // The figure is in an attribute, which carries no currency of its own.
        val html = """
            <meta itemprop="priceCurrency" content="USD">
            <span data-product-price="5999"></span>
        """.trimIndent()

        assertEquals(USD, extractOffers(html).first().currency)
    }

    @Test
    fun `symbols and codes both come back as codes`() {
        assertEquals(UAH, currencyCode("₴"))
        assertEquals(UAH, currencyCode("грн"))
        assertEquals(UAH, currencyCode("UAH"))
        assertEquals(USD, currencyCode("$"))
        assertEquals(USD, currencyCode("usd"))
        assertEquals("EUR", currencyCode("€"))
    }

    @Test
    fun `a currency the app has never heard of survives as itself`() {
        // It must not fold into "nothing stated", which would put it straight back
        // into the hryvnia bucket — the exact failure being fixed.
        assertEquals("SEK", currencyCode("SEK"))
        assertEquals("JPY", currencyCode("jpy"))
    }

    @Test
    fun `nonsense is not a currency`() {
        assertEquals("", currencyCode(""))
        assertEquals("", currencyCode("   "))
        assertEquals("", currencyCode("12345"))
    }

    @Test
    fun `hryvnia converts to itself and says nothing about it`() {
        val converted = toHryvnia(2_199.0, UAH, rate)

        assertEquals(2_199.0, converted.uah, 0.001)
        assertEquals(1.0, converted.rate, 0.001)
        assertFalse(converted.noRate)
        assertNull(convertedPriceLine(converted))
    }

    @Test
    fun `a page that named no currency is read as hryvnia`() {
        val converted = toHryvnia(2_199.0, "", rate)

        assertEquals(UAH, converted.currency)
        assertEquals(2_199.0, converted.uah, 0.001)
        assertFalse(converted.noRate)
    }

    @Test
    fun `dollars convert at the rate the bank sells at`() {
        val converted = toHryvnia(59.99, USD, rate)

        assertEquals(59.99 * 41.34, converted.uah, 0.001)
        assertEquals(59.99, converted.amount, 0.001)
        assertEquals(41.34, converted.rate, 0.001)
        assertFalse(converted.noRate)
    }

    @Test
    fun `the converted line shows its working`() {
        val line = convertedPriceLine(toHryvnia(59.99, USD, rate))

        assertNotNull(line)
        assertTrue(line!!, line.contains(dollars(59.99)))
        assertTrue(line, line.contains(rateFigure(41.34)))
        // Rounded to whole hryvnia: it is an estimate at today's rate. Built through
        // [approxMoney] rather than written out, because the group separator in
        // Ukrainian is a non-breaking space and a typed one is a different string.
        assertTrue(line, line.contains(approxMoney(59.99 * 41.34)))
    }

    @Test
    fun `a currency with no rate gets no number at all`() {
        val converted = toHryvnia(499.0, "SEK", rate)

        assertTrue(converted.noRate)
        assertEquals(0.0, converted.uah, 0.0)
        assertEquals(0.0, converted.rate, 0.0)
        // The figure the shop printed survives, because it is the only thing that
        // explains the state.
        assertEquals(499.0, converted.amount, 0.001)
        assertEquals("SEK", converted.currency)
    }

    @Test
    fun `dollars with no rate loaded are as unconvertible as kronor`() {
        val converted = toHryvnia(59.99, USD, FxRate())

        assertTrue(converted.noRate)
        assertEquals(0.0, converted.uah, 0.0)
    }

    @Test
    fun `the line for an unconvertible price says so instead of showing a figure`() {
        val line = convertedPriceLine(toHryvnia(499.0, "SEK", rate))

        assertNotNull(line)
        assertTrue(line!!, line.contains("SEK"))
        assertFalse(line, line.contains("₴"))
    }

    @Test
    fun `an amount in money the app cannot convert prints its code`() {
        assertEquals("499 SEK", amountLabel(499.0, "SEK"))
        // Four figures go through the same grouping the other two labels use, and
        // in Ukrainian that separator is a non-breaking space — so the expectation
        // is built rather than typed.
        assertEquals("${figureGrouped(2_480.0)} SEK", amountLabel(2_480.0, "SEK"))
        // The two the app does know keep their symbols.
        assertEquals(dollars(59.99), amountLabel(59.99, USD))
        assertEquals(money(2_199.0), amountLabel(2_199.0, UAH))
    }

    /** The same grouping [money] uses, without the currency on the end. */
    private fun figureGrouped(value: Double): String =
        money(value).removeSuffix(" ₴")

    @Test
    fun `a fresh rate is not worth mentioning`() {
        val converted = toHryvnia(59.99, USD, rate)

        assertNull(staleRateNote(converted, rateDay = 20_000L, today = 20_000L))
        assertNull(staleRateNote(converted, rateDay = 19_999L, today = 20_000L))
    }

    @Test
    fun `a rate left over from last week is`() {
        val converted = toHryvnia(59.99, USD, rate)

        val note = staleRateNote(converted, rateDay = 19_993L, today = 20_000L)

        assertNotNull(note)
        assertTrue(note!!, note.contains("7 днів"))
    }

    @Test
    fun `a hryvnia price never mentions the rate however old it is`() {
        val converted = toHryvnia(2_199.0, UAH, rate)

        assertNull(staleRateNote(converted, rateDay = 19_000L, today = 20_000L))
    }

    @Test
    fun `a dollar page becomes a wish priced in hryvnia`() {
        val html = """
            <meta property="og:title" content="Half-Life 3">
            <script type="application/ld+json">
            {"@type":"Product","offers":{"price":"59.99","priceCurrency":"USD"}}
            </script>
        """.trimIndent()

        val wish = parseProduct(html, "https://shop.example/hl3", "id", 20_000L, rate)

        assertEquals(59.99 * 41.34, wish.price, 0.001)
        assertEquals(Freshness.OK, wish.freshness)
        val source = wishSources(wish).single()
        assertEquals(USD, source.currency)
        assertEquals(59.99, source.amount, 0.001)
        assertEquals(41.34, source.rate, 0.001)
        // One series, in hryvnia, like every other wish.
        assertEquals(listOf(wish.price), wish.history.map { it.price })
    }

    @Test
    fun `a page in kronor makes a wish with no price rather than a wrong one`() {
        val html = """
            <meta property="og:title" content="Något">
            <script type="application/ld+json">
            {"@type":"Product","offers":{"price":"499","priceCurrency":"SEK"}}
            </script>
        """.trimIndent()

        val wish = parseProduct(html, "https://shop.example/x", "id", 20_000L, rate)

        assertEquals(0.0, wish.price, 0.0)
        assertEquals(Freshness.UNREADABLE, wish.freshness)
        // Nothing goes into the history, so no verdict is ever computed from it.
        assertTrue(wish.history.isEmpty())
        assertEquals("SEK", wishSources(wish).single().currency)
    }
}

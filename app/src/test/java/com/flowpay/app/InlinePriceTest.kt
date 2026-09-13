package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The price a shop renders in the browser instead of declaring in the page.
 *
 * A real failure: a Temu link came back with no JSON-LD, no microdata, no
 * `og:price`, no `priceCurrency` — and the price sitting in plain sight inside an
 * inline script as "1 458.21₴", under a key name that was different on the next
 * fetch of the same address. Reading the key name would be a patch for one shop
 * and would break the week the shop renames it. Reading a string the shop itself
 * formatted for a person to look at does not care what the key is called.
 *
 * These tests are mostly about what must *not* be found. Inference is the part of
 * a price tracker that can quietly start tracking a delivery charge, so the bounds
 * matter more than the hits.
 */
class InlinePriceTest {

    /**
     * The page as the shop actually serves it.
     *
     * Everything in the script is drawn from what was observed: the price as a
     * formatted string, the crossed-out recommended price with its own label, the
     * range across variants, minor-unit integers under unstable key names, a
     * delivery sentence with a figure in it, and the challenge wording that turns
     * up whether or not the price came with it.
     */
    private val renderedPrice = """
        <html><head>
        <meta property="og:title" content="ігровий контролер gamesir super nova - Temu Ukraine"/>
        <meta property="og:image" content="https://img.example/nova.jpg"/>
        </head><body><div id="root"></div>
        <script>window.rawData={"goods":{"curr":"UAH","salePrice":145821,
        "normalLinePrice":336684,"priceStr":"1 458.21₴","lineStr":"РРЦ 3 366.84₴ ",
        "rangeStr":"1 458.21₴-1 603.45₴","tip":"Доставка безкоштовна від 500₴ у вихідні",
        "verifyTitle":"Перевірка безпеки"}};</script>
        </body></html>
    """.trimIndent()

    @Test
    fun `a formatted price string in a script is found`() {
        val offers = extractOffers(renderedPrice)

        assertEquals(listOf(1458.21, 3366.84, 1603.45), offers.map { it.price })
        assertEquals(UAH, offers.first().currency)
        assertEquals(1458.21, extractPrice(renderedPrice), 0.001)
    }

    @Test
    fun `the shop's own label rides along, so the picker can warn you off it`() {
        // "РРЦ 3 366.84₴" is a crossed-out recommended price, not a price anyone
        // pays. The app cannot know that; the person reading the row can.
        val crossedOut = extractOffers(renderedPrice).first { it.price == 3366.84 }

        assertEquals("РРЦ", crossedOut.label)
        assertEquals("РРЦ", offerLabel(crossedOut, 1))
        assertEquals("Варіант 1", offerLabel(extractOffers(renderedPrice).first(), 0))
    }

    @Test
    fun `a range is two offers, because that is a page saying the variants differ`() {
        val offers = priceStringOffers("1 458.21₴-1 603.45₴")

        assertEquals(listOf(1458.21, 1603.45), offers.map { it.price })
    }

    @Test
    fun `the spaces shops actually use inside a figure are spaces`() {
        // A non-breaking space and a narrow one, which is what the markup carries
        // where the rendered page merely looks like it has an ordinary space.
        assertEquals(1458.21, priceStringOffers("1 458.21₴").single().price, 0.001)
        assertEquals(1458.21, priceStringOffers("1 458,21 грн").single().price, 0.001)
    }

    @Test
    fun `the same figure written twice is offered once`() {
        // 1 458.21 appears as the sale price and again as the foot of the range.
        assertEquals(1, extractOffers(renderedPrice).count { it.price == 1458.21 })
    }

    @Test
    fun `a sentence that merely mentions money is not a price`() {
        assertTrue(priceStringOffers("Доставка безкоштовна від 500₴ у вихідні").isEmpty())
        assertTrue(priceStringOffers("Знижка до 70% на все").isEmpty())
        assertTrue(priceStringOffers("Ціна діє лише сьогодні, встигни до 100 грн").isEmpty())
    }

    @Test
    fun `a bare number is never a price, however its key is named`() {
        // Minor units, ids and timestamps are all bare integers, and a rule that
        // read them would find a price on every page ever served.
        val script = """<script>var d={"salePrice":145821,"id":1758000000};</script>"""

        assertTrue(inlinePriceOffers(script).isEmpty())
    }

    @Test
    fun `only script content is read, never the page's own prose`() {
        val body = """<html><body><p>"1 458.21₴"</p></body></html>"""

        assertTrue(inlinePriceOffers(body).isEmpty())
    }

    @Test
    fun `money written before the figure is read too`() {
        val offer = priceStringOffers("$59.99").single()

        assertEquals(59.99, offer.price, 0.001)
        assertEquals(USD, offer.currency)
    }

    @Test
    fun `a declared price is never joined by inferred ones`() {
        // The whole risk of inference is that it crowds out a fact. A page with
        // one honest JSON-LD offer must stay a one-offer page, or every shop that
        // works today would start asking which of nine numbers you meant — and a
        // later refresh would have a crowd of near-misses to snap its variant on.
        val declared = """
            <html><head><script type="application/ld+json">
            {"@type":"Product","offers":{"price":"2199.00","priceCurrency":"UAH"}}
            </script></head><body>
            <script>var d={"priceStr":"1 458.21₴","lineStr":"РРЦ 3 366.84₴"};</script>
            </body></html>
        """.trimIndent()

        assertEquals(listOf(2199.0), extractOffers(declared).map { it.price })
    }

    @Test
    fun `a wall of figures is cut rather than turned into a wall of choices`() {
        val many = (1..30).joinToString(",") { """"p$it":"${it * 100} грн"""" }

        assertEquals(8, inlinePriceOffers("<script>var d={$many};</script>").size)
    }

    @Test
    fun `a page with nothing priceable in it stays empty`() {
        val script = """<script>var d={"name":"Товар","curr":"UAH","tag":"грн"};</script>"""

        assertTrue(inlinePriceOffers(script).isEmpty())
        assertEquals(0.0, extractPrice(script), 0.001)
    }
}

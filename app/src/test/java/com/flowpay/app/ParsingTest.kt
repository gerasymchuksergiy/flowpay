package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // ------------------------------------------- every price a page states

    /** Steam's shape: microdata for the base edition, an attribute per edition, no JSON-LD. */
    private val twoEditions = """
        <html><head>
        <meta property="og:title" content="EA SPORTS FC 27"/>
        <span itemprop="offers"><meta itemprop="priceCurrency" content="UAH">
        <meta itemprop="price" content="2 199"></span>
        </head><body>
        <div class="game_purchase_price price" data-price-final="219900"></div>
        <div class="game_purchase_price price" data-price-final="299900"></div>
        </body></html>
    """.trimIndent()

    @Test
    fun `a page with editions yields one offer per price`() {
        val offers = extractOffers(twoEditions)

        assertEquals(2, offers.size)
        assertEquals(2199.0, offers[0].price, 0.001)
        assertEquals(2999.0, offers[1].price, 0.001)
    }

    @Test
    fun `microdata alone is enough, which is what this page used to fail on`() {
        // No JSON-LD anywhere on it, so the old parser found nothing and the add
        // dialog said "Не вдалося знайти ціну на сторінці".
        assertEquals(2199.0, extractPrice(twoEditions), 0.001)
    }

    @Test
    fun `a price written in minor units is brought back down`() {
        val html = """<div data-price-final="299900"></div>"""

        assertEquals(2999.0, extractOffers(html).single().price, 0.001)
    }

    @Test
    fun `a round figure that is already whole hryvnia is left alone`() {
        // 450 is under the threshold; 12300 is over it but not a plausible
        // hundredfold of anything this app would see as a price.
        assertEquals(450.0, extractOffers("""<i data-price="450"></i>""").single().price, 0.001)
        assertEquals(123.0, extractOffers("""<i data-price="12300"></i>""").single().price, 0.001)
    }

    @Test
    fun `the same figure from two sources is offered once`() {
        val html = """
            <meta itemprop="price" content="2199">
            <div data-price-final="219900"></div>
            <meta property="product:price:amount" content="2199"/>
        """.trimIndent()

        assertEquals(1, extractOffers(html).size)
    }

    @Test
    fun `an offers array keeps the name the shop gave each price`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","name":"Кросівки","offers":[
              {"@type":"Offer","name":"42","price":"2499.00"},
              {"@type":"Offer","name":"43","price":"2599.00"}]}
            </script>
        """.trimIndent()

        val offers = extractOffers(html)

        assertEquals(2, offers.size)
        assertEquals("42", offers[0].label)
        assertEquals(2599.0, offers[1].price, 0.001)
        assertEquals("43", offers[1].label)
    }

    @Test
    fun `one price is one offer, so nothing has to be asked`() {
        assertEquals(1, extractOffers("""<meta property="og:price:amount" content="640"/>""").size)
    }

    @Test
    fun `a page with no price at all yields no offers`() {
        assertTrue(extractOffers("<html><body>Немає в наявності</body></html>").isEmpty())
    }

    @Test
    fun `structured data outranks a stray number in an attribute`() {
        // Taking the cheapest would pick the 9 here, which is a quantity.
        val html = """
            <script type="application/ld+json">{"@type":"Product","offers":{"price":"1500"}}</script>
            <span data-price-per-item="9"></span>
        """.trimIndent()

        assertEquals(1500.0, extractPrice(html), 0.001)
    }

    // ------------------------------------- what a page says about the thing

    @Test
    fun `the description comes from the tag every shop actually fills`() {
        // og:description decides how a link looks pasted into a messenger, which is
        // why shops fill it even when they fill nothing else.
        val html = """<meta property="og:description" content="Ласкаво просимо до World"/>"""

        assertEquals("Ласкаво просимо до World", extractAbout(html).description)
    }

    @Test
    fun `structured data is preferred over the plain meta description`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","description":"Демісезонні кросівки з сіткою"}
            </script>
            <meta name="description" content="Купуйте зі знижкою в нашому магазині"/>
        """.trimIndent()

        assertEquals("Демісезонні кросівки з сіткою", extractAbout(html).description)
    }

    @Test
    fun `brand, rating and its count are read when the shop declares them`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","brand":{"@type":"Brand","name":"ASICS"},
             "aggregateRating":{"ratingValue":"4.7","reviewCount":"128"}}
            </script>
        """.trimIndent()

        val about = extractAbout(html)

        assertEquals("ASICS", about.brand)
        assertEquals(4.7, about.rating, 0.001)
        assertEquals(128, about.ratingCount)
    }

    @Test
    fun `a rating stated in attributes is read too`() {
        val html = """
            <span itemprop="ratingValue" content="4.2"></span>
            <span itemprop="reviewCount" content="31"></span>
        """.trimIndent()

        val about = extractAbout(html)

        assertEquals(4.2, about.rating, 0.001)
        assertEquals(31, about.ratingCount)
    }

    @Test
    fun `specifications are read only where the shop published them as data`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","additionalProperty":[
              {"@type":"PropertyValue","name":"Матеріал","value":"Текстиль"},
              {"@type":"PropertyValue","name":"Сезон","value":"Демісезон"}]}
            </script>
        """.trimIndent()

        val specs = extractAbout(html).specs

        assertEquals(2, specs.size)
        assertEquals("Матеріал" to "Текстиль", specs[0])
        assertEquals("Сезон" to "Демісезон", specs[1])
    }

    @Test
    fun `a page that declares nothing about itself yields nothing invented`() {
        // Most shops are this page: the specification table is markup, not data,
        // and guessing a description out of it produces a paragraph of tags.
        val about = extractAbout("<html><body><table><tr><td>Колір</td></tr></table></body></html>")

        assertTrue(about.isEmpty)
        assertEquals("", about.description)
        assertEquals(0, about.ratingCount)
    }

    @Test
    fun `a very long description is cut at a sentence rather than mid-word`() {
        val sentence = "Це дуже довгий опис товару з великою кількістю слів. "
        val html = """<meta property="og:description" content="${sentence.repeat(20)}"/>"""

        val description = extractAbout(html).description

        assertTrue(description.length <= 620)
        assertTrue(description.endsWith("…"))
        assertFalse(description.contains("  "))
    }

    @Test
    fun `markup and entities inside a description do not reach the screen`() {
        // Escaped tags and a non-breaking space are how shops actually write this.
        // Entities are decoded once, not repeatedly: text that arrives double
        // escaped stays as the shop wrote it rather than being unwrapped twice.
        val html = """<meta property="og:description" content="&lt;b&gt;Нове&lt;/b&gt;&nbsp; взуття"/>"""

        assertEquals("Нове взуття", extractAbout(html).description)
    }

    @Test
    fun `a wish built from a chosen offer remembers which one it was`() {
        val html = """
            <meta property="og:title" content="EA SPORTS FC 27"/>
            <meta property="og:image" content="https://shop/cover.jpg"/>
        """.trimIndent()

        val wish = wishFromOffer(html, "https://shop/app", "id-1", Offer(2999.0, "Ultimate"), today = 42L)

        assertEquals("Ultimate", wish.variant)
        assertEquals(2999.0, wish.price, 0.001)
        assertEquals("EA SPORTS FC 27", wish.name)
        assertEquals(1, wish.history.size)
        assertEquals(42L, wish.history.first().day)
    }

    @Test
    fun `an offer with no name of its own is identified by its position`() {
        // Editions are usually named in the page's layout, not in its data, so most
        // of the time the price is what the row has to be recognised by.
        assertEquals("Ultimate", offerLabel(Offer(2999.0, "Ultimate"), 1))
        assertEquals("Варіант 1", offerLabel(Offer(2199.0), 0))
        assertEquals("Варіант 2", offerLabel(Offer(2999.0), 1))
    }

    @Test
    fun `spaces shops use inside numbers do not break them`() {
        assertEquals(12_499.5, priceNumber("12\u00a0499,50")!!, 0.001)
        assertEquals(2199.0, priceNumber("2 199")!!, 0.001)
        assertEquals(1234.56, priceNumber("1,234.56")!!, 0.001)
        assertNull(priceNumber("від 138"))
        assertNull(priceNumber(""))
        assertNull(priceNumber("0"))
    }


    @Test
    fun `a price glued on with non-breaking spaces still comes off the title`() {
        // Straight from a card on the phone: the shop separates with U+00A0, which
        // the earlier pattern did not accept, so the whole price stayed in the name.
        val raw = "Чоловічі кросівки ASICS Gel 1130 Dark Grey " +
            "демісезонні, ціна 2203.24"

        assertEquals(
            "Чоловічі кросівки ASICS Gel 1130 Dark Grey демісезонні",
            cleanProductTitle(raw)
        )
    }

    @Test
    fun `an ordinary space before the price works as before`() {
        assertEquals(
            "Кросівки",
            cleanProductTitle("Кросівки, ціна 2203.24")
        )
        assertEquals(
            "Кросівки",
            cleanProductTitle("Кросівки — Ціна 2 203,24 грн")
        )
    }


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
        assertEquals(listOf(2203.24), wish.history.map { it.price })
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
        assertEquals("", rate.source)
    }

    @Test
    fun `a monobank rate carries monobank as its source`() {
        val feed = """[{"currencyCodeA":840,"currencyCodeB":980,"rateBuy":44.4,"rateSell":44.8}]"""

        assertEquals(SOURCE_MONOBANK, parseUsdRate(feed).source)
    }

    // ------------------------------------------------------ national bank

    private val nbuFeed = """
        [{"r030":840,"txt":"Долар США","rate":41.2537,"cc":"USD","exchangedate":"12.09.2026"}]
    """.trimIndent()

    @Test
    fun `the national bank feed yields the official rate and the day it is for`() {
        val rate = parseNbuRate(nbuFeed)

        assertEquals(41.2537, rate.sell, 0.0001)
        assertEquals(SOURCE_NBU, rate.source)
        assertEquals("12.09.2026", rate.date)
    }

    @Test
    fun `the official rate has no spread, so buying and selling read the same`() {
        // Not an oversight: the published rate is one figure, and splitting it into
        // a fake buy and sell would invent a spread nobody trades at.
        val rate = parseNbuRate(nbuFeed)

        assertEquals(rate.buy, rate.sell, 0.0001)
    }

    @Test
    fun `an empty or unrelated national bank answer yields no rate rather than zero pretending to be one`() {
        assertEquals(0.0, parseNbuRate("[]").sell, 0.001)
        assertEquals("", parseNbuRate("[]").source)

        val euro = """[{"rate":47.9,"cc":"EUR","exchangedate":"12.09.2026"}]"""
        assertEquals(0.0, parseNbuRate(euro).sell, 0.001)
    }

    @Test
    fun `a source is stated for every rate that reaches the screen`() {
        assertEquals("Ринковий курс Monobank", rateSourceLabel(FxRate(44.4, 44.8, SOURCE_MONOBANK)))
        assertEquals(
            "Офіційний курс НБУ на 12.09.2026",
            rateSourceLabel(FxRate(41.25, 41.25, SOURCE_NBU, "12.09.2026"))
        )
        assertEquals("Офіційний курс НБУ", rateSourceLabel(FxRate(41.25, 41.25, SOURCE_NBU)))
        // Nothing to label when there is no figure yet.
        assertEquals("", rateSourceLabel(FxRate()))
    }

    @Test
    fun `an official rate is shown as one figure and a bank rate as two`() {
        assertEquals(
            "Купівля 44,40 · продаж 44,80",
            rateHeadline(FxRate(44.4, 44.8, SOURCE_MONOBANK))
        )
        assertEquals("41,25 ₴ за долар", rateHeadline(FxRate(41.2537, 41.2537, SOURCE_NBU)))
        assertEquals("Курс ще не завантажено", rateHeadline(FxRate()))
    }
}

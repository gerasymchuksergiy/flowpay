package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The second pass over prices (4 October 2026): Rozetka's three figures, the
 * glitch a shop serves for a day, the market on Hotline, the gap while a thing is
 * sold out, Black Friday in the November recap.
 *
 * The two shop pages are real ones, saved from the live sites on 4 October 2026
 * with FlowPay's own User-Agent and trimmed to the head and the structured data —
 * see the comment at the top of each file under src/test/resources. Nobody's
 * review is in them.
 */
class PricesMoreTest {

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource(name)) { "no fixture $name" }
            .readText(Charsets.UTF_8)

    private val rozetka by lazy { fixture("rozetka-jbl-tune-520bt.html") }
    private val rozetkaUrl = "https://rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649/"

    // ------------------------------------------------------- Rozetka's three prices

    @Test
    fun `a Rozetka page offers one price to follow, not three`() {
        // Regular 1 599, crossed out 1 799, with the Rozetka card 1 519 — all three
        // under the product's own name, which is why «Яка ціна ваша?» showed three
        // identical rows.
        assertEquals(listOf(1599.0), extractOffers(rozetka).map { it.price })
    }

    @Test
    fun `adding a Rozetka link asks nothing and starts at the regular price`() {
        val read = readForAdd(rozetka, rozetkaUrl, "w1", today = 20_365L)

        assertTrue(read is PageAdd.Priced)
        val wish = (read as PageAdd.Priced).wish
        assertEquals(1599.0, wish.price, 0.001)
        assertEquals(listOf(1599.0), wish.history.map { it.price })
    }

    @Test
    fun `offers sharing one name are told apart by the price they were followed at`() {
        // The picker could still show same-named rows on another shop's page, and
        // the next check used to take the first of them whatever was chosen.
        val offers = listOf(Offer(1599.0, "X"), Offer(1799.0, "X"), Offer(1519.0, "X"))

        assertEquals(OfferMatch.Found(Offer(1519.0, "X")), matchOffer(offers, "X", 1519.0))
        assertEquals(OfferMatch.Found(Offer(1799.0, "X")), matchOffer(offers, "X", 1790.0))
        assertEquals(OfferMatch.Missing, matchOffer(offers, "Y", 1519.0))
    }

    private fun rozetkaWish(): Wish =
        (readForAdd(rozetka, rozetkaUrl, "w1", today = 20_365L) as PageAdd.Priced).wish

    @Test
    fun `the crossed-out figure is kept only as the was price`() {
        assertEquals(1799.0, rozetkaWish().sources.single().listPrice, 0.001)
        assertEquals(1799.0, declaredListPrice(rozetka), 0.001)
    }

    @Test
    fun `the card price is a field of the shop, with its programme`() {
        val source = rozetkaWish().sources.single()

        assertEquals(1519.0, source.memberPrice, 0.001)
        assertEquals("https://rozetka.com.ua/#rozetka-card", source.memberTier)
        assertTrue(isRozetkaCard(source.memberTier))
        // The bonus tier states points, not a price, and is not one.
        assertEquals(listOf(1519.0), memberPrices(rozetka).map { it.price })
    }

    @Test
    fun `the next check stays on the regular price with no invented step`() {
        val wish = rozetkaWish()

        val again = readWish(wish, rozetka, 20_366L)

        assertTrue(again is Reading.Priced)
        val read = (again as Reading.Priced).wish
        assertEquals(1599.0, read.price, 0.001)
        assertEquals(listOf(1599.0), read.history.map { it.price })
        assertEquals(1519.0, read.sources.single().memberPrice, 0.001)
    }

    @Test
    fun `a card price that stops being stated is cleared on the next check`() {
        val wish = rozetkaWish()
        val withoutCard = rozetka.replace("validForMemberTier", "x")

        val read = (readWish(wish, withoutCard, 20_366L) as Reading.Priced).wish

        assertEquals(0.0, read.sources.single().memberPrice, 0.001)
        assertEquals("", read.sources.single().memberTier)
    }

    @Test
    fun `a member price above the ordinary one or absurdly below it is not kept`() {
        fun page(member: String) = """
            <script type="application/ld+json">{"@type":"Product","name":"T","offers":{"@type":"Offer",
            "price":1000,"priceCurrency":"UAH","priceSpecification":[{"@type":"UnitPriceSpecification",
            "price":$member,"priceCurrency":"UAH","validForMemberTier":{"@id":"https://rozetka.com.ua/#rozetka-card"}}]}}</script>
        """.trimIndent()

        assertEquals(null, memberOfferIn(page("1100"), Offer(1000.0, currency = UAH), FxRate()))
        assertEquals(null, memberOfferIn(page("100"), Offer(1000.0, currency = UAH), FxRate()))
        assertEquals(950.0, memberOfferIn(page("950"), Offer(1000.0, currency = UAH), FxRate())!!.price, 0.001)
    }

    @Test
    fun `a specification with no type and no tier is still an ordinary price`() {
        // Some templates put the only figure inside priceSpecification.
        val page = """
            <script type="application/ld+json">{"@type":"Product","name":"T","offers":{"@type":"Offer",
            "priceSpecification":{"@type":"UnitPriceSpecification","price":777,"priceCurrency":"UAH"}}}</script>
        """.trimIndent()

        assertEquals(listOf(777.0), extractOffers(page).map { it.price })
    }

    @Test
    fun `a list or recommended price in a specification is not an edition either`() {
        val page = """
            <script type="application/ld+json">{"@type":"Product","name":"T","offers":{"@type":"Offer",
            "price":900,"priceCurrency":"UAH","priceSpecification":[
            {"@type":"UnitPriceSpecification","priceType":"ListPrice","price":1200},
            {"@type":"UnitPriceSpecification","priceType":"https://schema.org/MSRP","price":1300}]}}</script>
        """.trimIndent()

        assertEquals(listOf(900.0), extractOffers(page).map { it.price })
    }

    // ---------------------------------------------------- «У мене є Картка Rozetka»

    private fun plain(text: String?): String? = text?.replace(' ', ' ')?.replace(' ', ' ')

    @Test
    fun `without the setting the card is neither shown nor counted`() {
        val wish = rozetkaWish().copy(targetPrice = 1550.0)

        assertEquals(0.0, cardPrice(wish, hasCard = false), 0.001)
        assertNull(cardLine(wish, hasCard = false))
        assertEquals(1599.0, targetBasis(wish, hasCard = false), 0.001)
    }

    @Test
    fun `with the card the price shows dimly under the ordinary one`() {
        val wish = rozetkaWish()

        assertEquals("1 519 ₴ з Карткою Rozetka", plain(cardLine(wish, hasCard = true)))
        assertEquals(1519.0, targetBasis(wish, hasCard = true), 0.001)
    }

    @Test
    fun `the line says when the card has reached a target the ordinary price has not`() {
        val wish = rozetkaWish().copy(targetPrice = 1550.0)

        assertEquals("1 519 ₴ з Карткою Rozetka — у межах цілі", plain(cardLine(wish, hasCard = true)))
    }

    @Test
    fun `a stale wish or a dearer card price says nothing`() {
        val wish = rozetkaWish()

        assertNull(cardLine(wish.copy(freshness = Freshness.OUT_OF_STOCK), hasCard = true))
        val cheaperElsewhere = wish.copy(price = 1500.0)
        assertNull(cardLine(cheaperElsewhere, hasCard = true))
    }

    @Test
    fun `the push says the card reached the target and what the ordinary price is`() {
        val before = rozetkaWish().copy(targetPrice = 1550.0)
            .let { it.copy(sources = it.sources.map { s -> s.copy(memberPrice = 1569.0) }) }
        val after = rozetkaWish().copy(targetPrice = 1550.0)

        assertTrue(cardTargetReached(before, after, hasCard = true))
        assertFalse(cardTargetReached(before, after, hasCard = false))
        // Once: the next reading at the same figures is not a crossing.
        assertFalse(cardTargetReached(after, after, hasCard = true))
        assertEquals(
            "Досягнуто ціль 1 550 ₴ — 1 519 ₴ при оплаті Карткою Rozetka (звичайна 1 599 ₴)",
            plain(cardTargetText(1550.0, 1519.0, 1599.0))
        )
    }

    @Test
    fun `when the ordinary price reaches the target the ordinary push speaks instead`() {
        val before = rozetkaWish().copy(targetPrice = 1550.0)
        val after = before.copy(price = 1540.0)

        assertFalse(cardTargetReached(before, after, hasCard = true))
        assertEquals(AlertKind.TARGET_REACHED, priceAlertFor(before, 1540.0).kind)
    }

    @Test
    fun `the card fields survive the round trip through storage`() {
        val source = rozetkaWish().sources.single()

        assertEquals(source, sourceOf(sourceJson(source)))
        val wish = rozetkaWish()
        assertEquals(wish, wishOf(wishJson(wish)))
    }
}

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

    // ------------------------------------------------- sharing a link already watched

    @Test
    fun `sharing a link already on the list opens that wish`() {
        val watched = rozetkaWish()
        val other = Wish("w2", "Інше", "https://prom.ua/p1", "", 100.0, history = emptyList())

        val link = sharedLink("Глянь: $rozetkaUrl", listOf(other, watched))

        assertEquals("w1", wishToOpen(link))
        assertEquals(
            "«Навушники JBL Tune 520BT Black (JBLT520BTBLKEU)» уже у списку — відкриваю сторінку",
            knownShareNote(watched)
        )
    }

    @Test
    fun `a new link or no link at all opens nothing`() {
        val wishes = listOf(rozetkaWish())

        assertNull(wishToOpen(sharedLink("https://prom.ua/p2", wishes)))
        assertNull(wishToOpen(sharedLink("без посилання", wishes)))
    }

    @Test
    fun `the shop app's share tags do not make a known link look new`() {
        val wishes = listOf(rozetkaWish())

        assertEquals("w1", wishToOpen(sharedLink("$rozetkaUrl?utm_source=app&utm_medium=share", wishes)))
        assertEquals("w1", wishToOpen(sharedLink("https://www.rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649", wishes)))
        // A parameter that is part of the product stays part of it.
        val sized = listOf(rozetkaWish().copy(id = "w3", url = "https://shop.example/p?size=42", sources = emptyList()))
        assertNull(wishToOpen(sharedLink("https://shop.example/p?size=43&utm_source=x", sized)))
        assertEquals("w3", wishToOpen(sharedLink("https://shop.example/p?size=42&utm_source=x", sized)))
    }

    // ------------------------------------------------------------ «Схоже на збій»

    private fun watched(vararg points: PricePoint, price: Double = points.last().price) = Wish(
        id = "g1", name = "Навушники", url = "https://shop.example/h", image = "",
        price = price, history = points.toList(), checkedDay = points.last().day
    )

    @Test
    fun `a one-check dip far from both neighbours is asked about`() {
        val history = listOf(PricePoint(1599.0, 20_300L), PricePoint(15.0, 20_340L), PricePoint(1599.0, 20_340L))

        assertEquals(listOf(PricePoint(15.0, 20_340L)), glitchCandidates(history))
    }

    @Test
    fun `a spike lasting a day is asked about too`() {
        val history = listOf(PricePoint(1000.0, 20_300L), PricePoint(1500.0, 20_340L), PricePoint(1000.0, 20_341L))

        assertEquals(listOf(PricePoint(1500.0, 20_340L)), glitchCandidates(history))
    }

    @Test
    fun `a sale that lasted three days, a step, and a small dip are left alone`() {
        val sale = listOf(PricePoint(1599.0, 20_300L), PricePoint(999.0, 20_340L), PricePoint(1599.0, 20_343L))
        val step = listOf(PricePoint(1599.0, 20_300L), PricePoint(999.0, 20_340L), PricePoint(899.0, 20_341L))
        val small = listOf(PricePoint(1000.0, 20_300L), PricePoint(700.0, 20_340L), PricePoint(1000.0, 20_341L))

        assertTrue(glitchCandidates(sale).isEmpty())
        assertTrue(glitchCandidates(step).isEmpty())
        assertTrue(glitchCandidates(small).isEmpty())
    }

    @Test
    fun `the price standing now is never a candidate and cannot be set aside`() {
        val history = listOf(PricePoint(1599.0, 20_300L), PricePoint(15.0, 20_340L))

        assertTrue(glitchCandidates(history).isEmpty())
        assertFalse(canSetAside(history, PricePoint(15.0, 20_340L)))
        assertTrue(canSetAside(history, PricePoint(1599.0, 20_300L)))
    }

    @Test
    fun `a point the owner called real is not asked about again`() {
        val wish = watched(PricePoint(1599.0, 20_300L), PricePoint(15.0, 20_340L), PricePoint(1599.0, 20_340L))

        val kept = withRealPoint(wish, PricePoint(15.0, 20_340L))

        assertTrue(glitchCandidates(kept.history, kept.realPoints).isEmpty())
        assertEquals(wish.history, kept.history)
    }

    @Test
    fun `a set-aside point stops counting everywhere the history is read`() {
        val glitch = PricePoint(15.0, 20_340L)
        val wish = watched(
            PricePoint(1599.0, 20_280L), PricePoint(1599.5, 20_300L), glitch, PricePoint(1599.0, 20_340L),
            PricePoint(1549.0, 20_350L)
        ).copy(targetPrice = 1500.0)

        val clean = withoutGlitch(wish, glitch)

        assertFalse(clean.history.any { samePoint(it, glitch) })
        assertEquals(listOf(SetAside(glitch, PricePoint(1599.0, 20_340L))), clean.excluded)
        // The lowest ever and the purchase verdict.
        assertEquals(15.0, lowestTracked(wish), 0.001)
        assertEquals(1549.0, lowestTracked(clean), 0.001)
        assertEquals(PurchaseVerdict.PATIENT, purchaseReview(1549.0, lowestTracked(clean)).verdict)
        // The thirty-day window and the all-time low the verdict and the bar use.
        val insight = priceInsight(clean.history, clean.price, 20_350L)
        assertEquals(1549.0, insight.lowest, 0.001)
        assertEquals(1549.0, insight.referenceLow, 0.001)
        // The target hints.
        assertFalse(targetSuggestions(clean.history, 1549.0, 20_350L).any { it.price == 15.0 })
        // «новий мінімум»: 1 400 is a new low once the glitch is gone, and was not before.
        assertEquals(AlertKind.NEW_LOW, priceAlertFor(clean.copy(targetPrice = 0.0), 1400.0).kind)
        assertFalse(priceAlertFor(wish.copy(targetPrice = 0.0), 1400.0).kind == AlertKind.NEW_LOW)
        // The shop's discount check: the thirty days before 1 549 no longer hold 15.
        assertEquals(1599.0, lowBeforeCurrent(clean.history, 1549.0, 20_350L)!!, 0.001)
    }

    @Test
    fun `putting a point back restores the history exactly, even on a shared day`() {
        val glitch = PricePoint(15.0, 20_340L, 41.2, SOURCE_MONOBANK)
        val wish = watched(PricePoint(1599.0, 20_300L), glitch, PricePoint(1599.0, 20_340L))

        val back = withGlitchBack(withoutGlitch(wish, glitch), glitch)

        assertEquals(wish.history, back.history)
        assertTrue(back.excluded.isEmpty())
        // Put back by hand means real: the hint does not ask about it again.
        assertTrue(glitchCandidates(back.history, back.realPoints).isEmpty())
    }

    @Test
    fun `the counts read as Ukrainian`() {
        assertEquals("1 точка схожа на збій магазину — не враховувати?", glitchHint(1))
        assertEquals("2 точки схожі на збій магазину — не враховувати?", glitchHint(2))
        assertEquals("5 точок схожі на збій магазину — не враховувати?", glitchHint(5))
        assertEquals("21 точка схожа на збій магазину — не враховувати?", glitchHint(21))
        assertEquals("1 точку не враховано", excludedNote(1))
        assertEquals("3 точки не враховано", excludedNote(3))
        assertEquals("11 точок не враховано", excludedNote(11))
        assertNull(excludedNote(0))
    }

    // ------------------------------------------------------ the gap while sold out

    @Test
    fun `a gap opens when the thing is seen sold out and closes when a price is back`() {
        val opened = stockGapsAfter(emptyList(), Freshness.OUT_OF_STOCK, 20_340L)
        assertEquals(listOf(StockGap(20_340L)), opened)
        // Still sold out, or a page that said nothing: the same gap, untouched.
        assertEquals(opened, stockGapsAfter(opened, Freshness.OUT_OF_STOCK, 20_341L))
        assertEquals(opened, stockGapsAfter(opened, Freshness.UNREADABLE, 20_342L))
        assertEquals(listOf(StockGap(20_340L, 20_350L)), stockGapsAfter(opened, Freshness.OK, 20_350L))
        // A price with no gap open changes nothing.
        assertTrue(stockGapsAfter(emptyList(), Freshness.OK, 20_350L).isEmpty())
    }

    @Test
    fun `a refresh records the gap on the wish itself`() {
        val wish = rozetkaWish().copy(checkedDay = 20_365L)
        val soldOut = rozetka.replace("schema.org/InStock", "schema.org/OutOfStock")

        val gone = (readWish(wish, soldOut, 20_370L) as Reading.Stale).wish
        assertEquals(listOf(StockGap(20_370L)), gone.stockGaps)

        val back = (readWish(gone, rozetka, 20_380L) as Reading.Priced).wish
        assertEquals(listOf(StockGap(20_370L, 20_380L)), back.stockGaps)
    }

    @Test
    fun `the gap is placed on the chart by date, and an open one runs to the end`() {
        val points = listOf(PricePoint(1000.0, 100L), PricePoint(900.0, 150L), PricePoint(900.0, 200L))

        assertEquals(listOf(0.2f..0.4f), gapSpans(points, listOf(StockGap(120L, 140L))))
        assertEquals(listOf(0.9f..1.0f), gapSpans(points, listOf(StockGap(190L))))
        // Undated points are spaced evenly; a day has no place on such a chart.
        assertTrue(gapSpans(points + PricePoint(800.0, 0L), listOf(StockGap(120L, 140L))).isEmpty())
    }

    @Test
    fun `glitches, real points and gaps survive the round trip through storage`() {
        val glitch = PricePoint(15.0, 20_340L)
        val wish = withRealPoint(
            withoutGlitch(watched(PricePoint(1599.0, 20_300L), glitch, PricePoint(1599.0, 20_340L)), glitch),
            PricePoint(1599.0, 20_300L)
        ).copy(stockGaps = listOf(StockGap(20_200L, 20_210L), StockGap(20_350L)))

        val back = wishOf(wishJson(wish))

        assertEquals(wish.excluded.map { it.point }, back.excluded.map { it.point })
        assertEquals(wish.excluded.map { it.next?.price to it.next?.day }, back.excluded.map { it.next?.price to it.next?.day })
        assertEquals(wish.realPoints, back.realPoints)
        assertEquals(wish.stockGaps, back.stockGaps)
        assertEquals(wish, back)
    }

    @Test
    fun `the card fields survive the round trip through storage`() {
        val source = rozetkaWish().sources.single()

        assertEquals(source, sourceOf(sourceJson(source)))
        val wish = rozetkaWish()
        assertEquals(wish, wishOf(wishJson(wish)))
    }
}

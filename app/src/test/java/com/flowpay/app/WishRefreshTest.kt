package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WishRefreshTest {

    private val today = 20_000L

    @Test
    fun `refresh preserves user fields and updates remote fields`() {
        val previous = Wish(
            id = "saved-id",
            name = "Моя назва",
            url = "https://shop.example/item",
            image = "https://shop.example/old.jpg",
            price = 1_000.0,
            targetPrice = 900.0,
            category = "Техніка",
            history = listOf(PricePoint(1_100.0, today - 10), PricePoint(1_000.0, today - 5))
        )
        val current = Wish(
            id = "scraped-id",
            name = "Назва магазину",
            url = previous.url,
            image = "https://shop.example/new.jpg",
            price = 950.0,
            history = listOf(PricePoint(950.0, today))
        )

        val refreshed = refreshedWish(previous, current, today)

        assertEquals("saved-id", refreshed.id)
        assertEquals("Моя назва", refreshed.name)
        assertEquals("Техніка", refreshed.category)
        assertEquals(900.0, refreshed.targetPrice, 0.0)
        assertEquals("https://shop.example/new.jpg", refreshed.image)
        assertEquals(950.0, refreshed.price, 0.0)
        assertEquals(
            listOf(1_100.0, 1_000.0, 950.0),
            refreshed.history.map { it.price }
        )
        assertEquals(today, refreshed.history.last().day)
        assertEquals(today, refreshed.checkedDay)
    }

    @Test
    fun `an unchanged price adds no entry but still moves the checked date`() {
        val previous = Wish(
            "id", "name", "https://example.com", "", 1_000.0,
            history = listOf(PricePoint(1_000.0, today - 30)),
            checkedDay = today - 1
        )
        val current = Wish("new", "new", previous.url, "", 1_000.0, history = emptyList())

        val refreshed = refreshedWish(previous, current, today)

        // The point of recording only changes: a price that held for a month is one
        // entry, not sixty identical ones.
        assertEquals(1, refreshed.history.size)
        assertEquals(today - 30, refreshed.history.first().day)
        assertEquals(today, refreshed.checkedDay)
    }

    @Test
    fun `a refresh that fails to find a price leaves the history alone`() {
        val previous = Wish(
            "id", "name", "https://example.com", "", 1_000.0,
            history = listOf(PricePoint(1_000.0, today - 5))
        )
        val current = Wish("new", "new", previous.url, "", 0.0, history = emptyList())

        val refreshed = refreshedWish(previous, current, today)

        assertEquals(1, refreshed.history.size)
        assertEquals(1_000.0, refreshed.history.first().price, 0.0)
    }

    @Test
    fun `history is capped and the oldest entries fall off first`() {
        val long = (1..HISTORY_CAP).map { PricePoint(it.toDouble(), today - HISTORY_CAP + it) }
        val previous = Wish("id", "name", "https://example.com", "", HISTORY_CAP.toDouble(), history = long)
        val current = Wish("new", "new", previous.url, "", 9_999.0, history = emptyList())

        val refreshed = refreshedWish(previous, current, today)

        assertEquals(HISTORY_CAP, refreshed.history.size)
        assertEquals(2.0, refreshed.history.first().price, 0.0)
        assertEquals(9_999.0, refreshed.history.last().price, 0.0)
    }

    @Test
    fun `only complete web URLs are accepted`() {
        assertTrue(isSupportedWebUrl("https://example.com/item"))
        assertTrue(isSupportedWebUrl(" http://example.com/item "))
        assertFalse(isSupportedWebUrl("https://"))
        assertFalse(isSupportedWebUrl("javascript:alert(1)"))
        assertFalse(isSupportedWebUrl("not-a-url"))
    }

    // ------------------------------------------ following one of several prices

    private val twoEditions = listOf(Offer(2199.0, "Standard"), Offer(2999.0, "Ultimate"))

    private fun followed(variant: String, price: Double) = Wish(
        id = "w",
        name = "EA SPORTS FC 27",
        url = "https://store.example/app/1",
        image = "",
        price = price,
        history = listOf(PricePoint(price, today - 1)),
        variant = variant
    )

    @Test
    fun `a named variant is found again by its name, not its position`() {
        val match = matchOffer(twoEditions.reversed(), "Ultimate", lastPrice = 2999.0)

        assertEquals(OfferMatch.Found(Offer(2999.0, "Ultimate")), match)
    }

    @Test
    fun `a name is followed even after its price moves`() {
        // A sale must not look like a different edition.
        val onSale = listOf(Offer(1499.0, "Standard"), Offer(2999.0, "Ultimate"))

        val match = matchOffer(onSale, "Standard", lastPrice = 2199.0)

        assertEquals(1499.0, (match as OfferMatch.Found).offer.price, 0.001)
    }

    @Test
    fun `a variant that is gone is reported as gone, not replaced`() {
        assertEquals(OfferMatch.Missing, matchOffer(twoEditions, "Deluxe", lastPrice = 2499.0))
    }

    @Test
    fun `a page with no prices is a different failure from a missing variant`() {
        assertEquals(OfferMatch.None, matchOffer(emptyList(), "Standard", lastPrice = 2199.0))
        assertEquals(OfferMatch.None, matchOffer(emptyList(), "", lastPrice = 2199.0))
    }

    @Test
    fun `without a name the nearest price to the last reading is followed`() {
        // Unlabelled offers are the common case: the anchor is the price itself,
        // and the gap between editions is far wider than any discount.
        val match = matchOffer(
            listOf(Offer(2249.0), Offer(3099.0)),
            variant = "",
            lastPrice = 2199.0
        )

        assertEquals(2249.0, (match as OfferMatch.Found).offer.price, 0.001)
    }

    @Test
    fun `one offer is taken whatever was stored before`() {
        val match = matchOffer(listOf(Offer(640.0)), variant = "", lastPrice = 0.0)

        assertEquals(640.0, (match as OfferMatch.Found).offer.price, 0.001)
    }

    // ------------------------------------------------ refreshing from a page

    private val editionsPage = """
        <script type="application/ld+json">
        {"@type":"Product","offers":[
          {"@type":"Offer","name":"Standard","price":"1999"},
          {"@type":"Offer","name":"Ultimate","price":"2999"}]}
        </script>
    """.trimIndent()

    @Test
    fun `a refresh follows the chosen edition and records only its price`() {
        val reading = readWish(followed("Ultimate", 2999.0), editionsPage, today)
        val updated = (reading as Reading.Priced).wish

        assertEquals(2999.0, updated.price, 0.001)
        assertEquals("Ultimate", updated.variant)
        assertEquals(today, updated.checkedDay)
        assertEquals(Freshness.OK, updated.freshness)
    }

    @Test
    fun `a fall in the followed edition reaches the history`() {
        val reading = readWish(followed("Standard", 2199.0), editionsPage, today)
        val updated = (reading as Reading.Priced).wish

        assertEquals(1999.0, updated.price, 0.001)
        assertEquals(1999.0, updated.history.last().price, 0.001)
    }

    @Test
    fun `a refresh stamps the day's rate onto the price it records`() {
        val reading = readWish(
            followed("Standard", 2199.0),
            editionsPage,
            today,
            FxRate(40.0, 41.0, SOURCE_MONOBANK)
        )

        val point = (reading as Reading.Priced).wish.history.last()
        assertEquals(41.0, point.rate, 0.001)
        assertEquals(SOURCE_MONOBANK, point.rateSource)
    }

    @Test
    fun `a vanished edition leaves the price alone but says it is out of stock`() {
        val previous = followed("Deluxe", 2499.0)

        val reading = readWish(previous, editionsPage, today)
        val wish = (reading as Reading.Stale).wish

        assertEquals(Freshness.OUT_OF_STOCK, wish.freshness)
        // The stored price and its history stay exactly as they were; only the
        // claim being made about them changes.
        assertEquals(2499.0, wish.price, 0.001)
        assertEquals(previous.history, wish.history)
        // The page was genuinely looked at, so the date of the look moves.
        assertEquals(today, wish.checkedDay)
    }

    @Test
    fun `a page that lost its prices is a different diagnosis`() {
        val reading = readWish(followed("Standard", 2199.0), "<html>Немає</html>", today)
        val wish = (reading as Reading.Stale).wish

        assertEquals(Freshness.UNREADABLE, wish.freshness)
        assertEquals(2199.0, wish.price, 0.001)
    }

    @Test
    fun `a page that starts answering again clears the warning`() {
        val stale = followed("Standard", 2199.0).copy(freshness = Freshness.OUT_OF_STOCK)

        val reading = readWish(stale, editionsPage, today)

        assertEquals(Freshness.OK, (reading as Reading.Priced).wish.freshness)
    }

    @Test
    fun `a fold counts prices, keeps stale verdicts, and ignores failed fetches`() {
        val first = followed("Standard", 2199.0).copy(id = "a")
        val second = followed("Ultimate", 2999.0).copy(id = "b")
        val third = followed("Deluxe", 1599.0).copy(id = "c")

        val result = applyFollowed(
            listOf(first, second, third),
            listOf(
                Reading.Failed,
                Reading.Priced(second.copy(price = 2599.0)),
                Reading.Stale(third.copy(freshness = Freshness.GONE))
            )
        )

        // Only the priced one counts: saying "2 updated" when one came back without
        // a price is the exact overstatement freshness exists to remove.
        assertEquals(1, result.updated)
        assertEquals(Freshness.OK, result.wishes[0].freshness)
        assertEquals(2199.0, result.wishes[0].price, 0.001)
        assertEquals(2599.0, result.wishes[1].price, 0.001)
        assertEquals(Freshness.GONE, result.wishes[2].freshness)
        assertEquals(1, staleCount(result.wishes))
    }

    @Test
    fun `a dropped connection changes nothing, not even the warning`() {
        // A tunnel must not be able to mark a whole healthy list as gone.
        val healthy = followed("Standard", 2199.0)

        val result = applyFollowed(listOf(healthy), listOf(Reading.Failed))

        assertEquals(healthy, result.wishes.single())
        assertEquals(0, result.updated)
    }

    // ------------------------------------------------------ freshness on screen

    @Test
    fun `a hand-typed price is not treated as a doubtful one`() {
        assertFalse(isStale(Freshness.OK))
        assertFalse(isStale(Freshness.MANUAL))
        assertTrue(isStale(Freshness.UNREADABLE))
        assertTrue(isStale(Freshness.OUT_OF_STOCK))
        assertTrue(isStale(Freshness.GONE))
    }

    @Test
    fun `a wish saved before freshness existed reads back as read fine`() {
        assertEquals(Freshness.OK, freshnessFrom(""))
        assertEquals(Freshness.OK, freshnessFrom("SOMETHING_LATER"))
        assertEquals(Freshness.GONE, freshnessFrom("GONE"))
    }

    @Test
    fun `only a doubtful reading carries a warning`() {
        assertNull(freshnessLabel(Freshness.OK))
        assertNull(freshnessNote(Freshness.OK))
        assertEquals("Немає в наявності", freshnessLabel(Freshness.OUT_OF_STOCK))
        assertEquals("Ціна вручну", freshnessLabel(Freshness.MANUAL))
    }

    @Test
    fun `a link whose page could not be read is kept and marked, not dropped`() {
        val placeholder = placeholderWish("https://shop.example/item", "id", today)

        assertEquals(Freshness.UNREADABLE, placeholder.freshness)
        assertEquals(today, placeholder.addedDay)
        assertEquals(0.0, placeholder.price, 0.0)
    }

    @Test
    fun `coming back into stock is announced ahead of a new low`() {
        val previous = followed("Standard", 2199.0).copy(freshness = Freshness.OUT_OF_STOCK)

        assertEquals(AlertKind.BACK_IN_STOCK, priceAlertFor(previous, 1_999.0).kind)
    }

    @Test
    fun `a target still outranks coming back into stock`() {
        val previous = followed("Standard", 2199.0)
            .copy(freshness = Freshness.GONE, targetPrice = 2_000.0)

        assertEquals(AlertKind.TARGET_REACHED, priceAlertFor(previous, 1_900.0).kind)
    }

    @Test
    fun `a healthy wish is not told it came back from anywhere`() {
        val previous = followed("Standard", 2199.0)

        // A rise, so no other rule fires either and only the new state could speak.
        assertEquals(AlertKind.NONE, priceAlertFor(previous, 2_210.0).kind)
    }

    @Test
    fun `the run reports what came back without a price`() {
        assertNull(staleMessage(0))
        assertEquals("Без ціни: 1 позиція", staleMessage(1))
        assertEquals("Без ціни: 3 позиції", staleMessage(3))
    }

    // ------------------------------------------------- age and deliberate holds

    @Test
    fun `a wish added before the field existed is dated by its first price`() {
        val legacy = followed("Standard", 2199.0).copy(
            addedDay = 0L,
            history = listOf(PricePoint(2199.0, today - 90), PricePoint(2099.0, today - 5))
        )

        assertEquals(today - 90, addedDay(legacy))
        assertEquals(90, wantedDays(legacy, today))
        assertEquals("У списку 3 місяці", wantedLabel(legacy, today))
    }

    @Test
    fun `a stamped day wins over the history it happens to carry`() {
        val wish = followed("Standard", 2199.0).copy(
            addedDay = today - 10,
            history = listOf(PricePoint(2199.0, today - 90))
        )

        assertEquals(today - 10, addedDay(wish))
        assertEquals("У списку 10 днів", wantedLabel(wish, today))
    }

    @Test
    fun `a wish with nothing to date it says nothing about its age`() {
        val undated = followed("Standard", 2199.0)
            .copy(addedDay = 0L, history = listOf(PricePoint(2199.0, 0L)))

        assertNull(wantedDays(undated, today))
        assertNull(wantedLabel(undated, today))
    }

    @Test
    fun `the day it was added is not yet a day of wanting`() {
        val fresh = followed("Standard", 2199.0).copy(addedDay = today)

        assertEquals(0, wantedDays(fresh, today))
        assertEquals("Додано сьогодні", wantedLabel(fresh, today))
    }

    @Test
    fun `a hold keeps the wish quiet until its day`() {
        val held = followed("Standard", 2199.0).copy(holdUntil = today + 30)

        assertTrue(onHold(held, today))
        assertFalse(holdEnded(held, today))
        assertEquals("Відкладено до ${formatDate(java.time.LocalDate.ofEpochDay(today + 30))}", holdLabel(held, today))
    }

    @Test
    fun `a hold ending today is over today, not tomorrow`() {
        // The date the user picked is the date they wanted to be asked. Treating the
        // day itself as one more day of waiting would answer a day late, every time.
        val due = followed("Standard", 2199.0).copy(holdUntil = today)

        assertFalse(onHold(due, today))
        assertTrue(holdEnded(due, today))
        assertEquals("Ще хочеш?", holdLabel(due, today))
    }

    @Test
    fun `a wish never put on hold says nothing about holds`() {
        val plain = followed("Standard", 2199.0)

        assertFalse(onHold(plain, today))
        assertFalse(holdEnded(plain, today))
        assertNull(holdLabel(plain, today))
    }

    @Test
    fun `held wishes keep their place in the list rather than vanishing`() {
        val watched = followed("Standard", 2199.0).copy(id = "a")
        val held = followed("Ultimate", 2999.0).copy(id = "b", holdUntil = today + 14)
        val due = followed("Deluxe", 1599.0).copy(id = "c", holdUntil = today)

        val (active, aside) = partitionByHold(listOf(watched, held, due), today)

        // The one whose hold has run out is back in the list asking its question.
        assertEquals(listOf("a", "c"), active.map { it.id })
        assertEquals(listOf("b"), aside.map { it.id })
    }
}

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
        val updated = refreshedFromPage(followed("Ultimate", 2999.0), editionsPage, today)!!

        assertEquals(2999.0, updated.price, 0.001)
        assertEquals("Ultimate", updated.variant)
        assertEquals(today, updated.checkedDay)
    }

    @Test
    fun `a fall in the followed edition reaches the history`() {
        val updated = refreshedFromPage(followed("Standard", 2199.0), editionsPage, today)!!

        assertEquals(1999.0, updated.price, 0.001)
        assertEquals(1999.0, updated.history.last().price, 0.001)
    }

    @Test
    fun `a vanished edition changes nothing at all`() {
        // Not "the price did not change" — nothing is written, so the stored price
        // and its history stay exactly as they were.
        assertNull(refreshedFromPage(followed("Deluxe", 2499.0), editionsPage, today))
    }

    @Test
    fun `a page that lost its prices changes nothing either`() {
        assertNull(refreshedFromPage(followed("Standard", 2199.0), "<html>Немає</html>", today))
    }

    @Test
    fun `a fold keeps the wishes whose pages said nothing usable`() {
        val first = followed("Standard", 2199.0).copy(id = "a")
        val second = followed("Ultimate", 2999.0).copy(id = "b")

        val result = applyFollowed(listOf(first, second), listOf(null, second.copy(price = 2599.0)))

        assertEquals(1, result.updated)
        assertEquals(2199.0, result.wishes[0].price, 0.001)
        assertEquals(2599.0, result.wishes[1].price, 0.001)
    }

}

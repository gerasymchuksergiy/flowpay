package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}

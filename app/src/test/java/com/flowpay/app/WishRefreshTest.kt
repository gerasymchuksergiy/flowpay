package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishRefreshTest {
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
            history = listOf(1_100.0, 1_000.0)
        )
        val current = Wish(
            id = "scraped-id",
            name = "Назва магазину",
            url = previous.url,
            image = "https://shop.example/new.jpg",
            price = 950.0,
            history = listOf(950.0)
        )

        val refreshed = refreshedWish(previous, current)

        assertEquals("saved-id", refreshed.id)
        assertEquals("Моя назва", refreshed.name)
        assertEquals("Техніка", refreshed.category)
        assertEquals(900.0, refreshed.targetPrice, 0.0)
        assertEquals("https://shop.example/new.jpg", refreshed.image)
        assertEquals(950.0, refreshed.price, 0.0)
        assertEquals(listOf(1_100.0, 1_000.0, 950.0), refreshed.history)
    }

    @Test
    fun `refresh keeps no more than ninety prices`() {
        val previous = Wish("id", "name", "https://example.com", "", 100.0, history = (1..90).map(Int::toDouble))
        val current = Wish("new", "new", previous.url, "", 91.0, history = listOf(91.0))

        val refreshed = refreshedWish(previous, current)

        assertEquals(90, refreshed.history.size)
        assertEquals(2.0, refreshed.history.first(), 0.0)
        assertEquals(91.0, refreshed.history.last(), 0.0)
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


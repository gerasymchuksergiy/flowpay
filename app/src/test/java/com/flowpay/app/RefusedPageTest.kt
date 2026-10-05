package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A shop that turns apps away (§29): ua.store.asus.com answers every request that is not
 * a browser with 403 from behind DataDome. What the app calls that, what the item page
 * says about a wish that never had a price, and the one way a typed price is saved.
 */
class RefusedPageTest {

    private val asusUrl = "https://ua.store.asus.com/90lm0aa0-b01170.html"
    private val day = 20_000L

    @Test
    fun `401 and 403 turn the app away, and nothing else does`() {
        assertTrue(refusesApps(403))
        assertTrue(refusesApps(401))
        listOf(200, 301, 404, 410, 429, 500, 503).forEach { assertFalse("$it", refusesApps(it)) }
    }

    @Test
    fun `the refusal names the shop and what is left to do`() {
        assertEquals(
            "ua.store.asus.com не пускає застосунки на свої сторінки, тож ціну тут доведеться вписати вручну",
            refusedNote(asusUrl)
        )
    }

    @Test
    fun `a wish that never had a price is not shown a last known one`() {
        val placeholder = placeholderWish(asusUrl, "w1", day)

        assertEquals(NO_PRICE_YET_NOTE, wishNote(placeholder))
        // Once there was a price, the old sentence is the true one.
        val read = placeholder.copy(price = 13_819.0)
        assertEquals(freshnessNote(Freshness.UNREADABLE), wishNote(read))
        assertEquals(freshnessNote(Freshness.OK), wishNote(read.copy(freshness = Freshness.OK)))
    }

    @Test
    fun `a typed price is marked as typed and starts the history`() {
        val placeholder = placeholderWish(asusUrl, "w1", day)

        val typed = typedWish(placeholder, 13_819.0, "TUF Gaming VG34VQ3B", day + 1)

        assertEquals(13_819.0, typed.price, 0.0)
        assertEquals(Freshness.MANUAL, typed.freshness)
        assertEquals(day + 1, typed.checkedDay)
        assertEquals(listOf(13_819.0), typed.history.map { it.price })
        assertEquals("TUF Gaming VG34VQ3B", typed.name)
        assertEquals("w1", typed.id)
        assertEquals(asusUrl, typed.url)
    }

    @Test
    fun `no name keeps the name, and nought or the same price keeps the figure`() {
        val wish = typedWish(placeholderWish(asusUrl, "w1", day), 13_819.0, null, day)

        assertEquals("Товар з ua.store.asus.com", wish.name)
        assertEquals(wish, typedWish(wish, 13_819.0, " ", day + 2))
        assertEquals(wish, typedWish(wish, 0.0, null, day + 2))
        // A new figure goes on top of the history it has.
        assertEquals(listOf(13_819.0, 12_999.0), typedWish(wish, 12_999.0, null, day + 2).history.map { it.price })
    }
}

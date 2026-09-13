package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The share sheet and the launcher shortcuts.
 *
 * Everything here runs on text the app did not write: a messenger's wording
 * around a link, a shop's own slogan after it, a shortcut fired at an app that
 * is already open. None of it can be tried by hand twice in a row, so the three
 * awkward cases — text around the link, a link that will not parse, and the same
 * link arriving twice — are pinned here instead.
 */
class ShareTest {

    private fun wish(id: String, url: String, name: String = "Товар") =
        Wish(id, name, url, "", 1_000.0, history = listOf(PricePoint(1_000.0, 20_000L)))

    // ------------------------------------------------------------ finding a link

    @Test
    fun `a link is found among the words a messenger wrapped it in`() {
        assertEquals(
            "https://rozetka.com.ua/p123/",
            extractUrl("Дивись що знайшов https://rozetka.com.ua/p123/ норм ціна?")
        )
    }

    @Test
    fun `a browser share puts the title first and the link last`() {
        // Chrome's own format: name, newline, address.
        assertEquals(
            "https://shop.example/item?id=7",
            extractUrl("Кросівки ASICS Gel 1130\nhttps://shop.example/item?id=7")
        )
    }

    @Test
    fun `the full stop that ends the sentence is not part of the address`() {
        assertEquals("https://shop.example/item", extractUrl("Ось воно: https://shop.example/item."))
        assertEquals("https://shop.example/item", extractUrl("(https://shop.example/item)"))
    }

    @Test
    fun `a query string keeps its commas and equals signs`() {
        assertEquals(
            "https://shop.example/p?size=42,43&ref=share",
            extractUrl("https://shop.example/p?size=42,43&ref=share")
        )
    }

    @Test
    fun `the first link wins when a message carries several`() {
        assertEquals(
            "https://shop.example/a",
            extractUrl("https://shop.example/a і ще https://shop.example/b")
        )
    }

    @Test
    fun `text with nothing to open is not a link`() {
        assertNull(extractUrl("Подивись цю штуку в торговому центрі"))
        assertNull(extractUrl(""))
        assertNull(extractUrl(null))
        // A scheme the app cannot fetch is worse than none: it would be saved.
        assertNull(extractUrl("Встанови ftp://files.example/item"))
    }

    // ------------------------------------------------- deciding what to do with it

    @Test
    fun `a shared message with no link is answered rather than swallowed`() {
        assertEquals(SharedLink.Missing, sharedLink("просто текст", emptyList()))
    }

    @Test
    fun `a link already on the list is recognised through its spelling`() {
        val existing = listOf(wish("1", "https://shop.example/item", "Кросівки"))

        // Same item, shared from the browser rather than from the shop's app:
        // upper case host, no scheme difference the user can see, trailing slash.
        val again = sharedLink("HTTPS://Shop.Example/item/#reviews", existing)

        assertTrue(again is SharedLink.Known)
        assertEquals("Кросівки", (again as SharedLink.Known).wish.name)
    }

    @Test
    fun `a different item on the same shop is not a duplicate`() {
        val existing = listOf(wish("1", "https://shop.example/item"))

        val other = sharedLink("https://shop.example/other", existing)

        assertTrue(other is SharedLink.New)
        assertEquals("https://shop.example/other", (other as SharedLink.New).url)
    }

    // ------------------------------------------------------ when the page will not read

    @Test
    fun `a link that cannot be parsed is still recognisable by its shop`() {
        assertEquals("Товар з rozetka.com.ua", placeholderName("https://www.rozetka.com.ua/p123/"))
        assertEquals("Товар з shop.example", placeholderName("http://shop.example/item"))
        assertEquals("Нове бажання", placeholderName("not a url at all"))
    }

    @Test
    fun `a placeholder wish keeps the link and claims no price`() {
        val kept = placeholderWish("https://www.shop.example/item", "id-1")

        assertEquals("https://www.shop.example/item", kept.url)
        assertEquals("Товар з shop.example", kept.name)
        assertEquals(0.0, kept.price, 0.0)
        // An empty history rather than a zero point: a price of nought was never
        // observed, and recording it would put a false floor under the chart.
        assertTrue(kept.history.isEmpty())
    }

    @Test
    fun `reading the page later fills the placeholder without losing the link`() {
        val kept = placeholderWish("https://shop.example/item", "id-1")
        val read = Wish("scraped", "Кросівки ASICS", kept.url, "https://shop.example/p.jpg", 2_203.0, history = emptyList())

        val filled = refreshedWish(kept, read, today = 20_500L).copy(name = read.name)

        assertEquals("Кросівки ASICS", filled.name)
        assertEquals("id-1", filled.id)
        assertEquals(2_203.0, filled.price, 0.0)
        assertEquals(1, filled.history.size)
        assertEquals(20_500L, filled.checkedDay)
    }

    // --------------------------------------------------------------- the commands

    @Test
    fun `a share carrying text becomes an add`() {
        val command = appCommand("android.intent.action.SEND", "https://shop.example/item")

        assertEquals(AppCommand.AddShared("https://shop.example/item"), command)
    }

    @Test
    fun `a share carrying nothing is not acted on`() {
        // An image shared as text, or a send with the extra missing: answering it
        // with the add dialog would look like the app misread something.
        assertNull(appCommand("android.intent.action.SEND", null))
        assertNull(appCommand("android.intent.action.SEND", "   "))
    }

    @Test
    fun `each shortcut maps to its own command and an ordinary launch to none`() {
        assertEquals(AppCommand.AddWish, appCommand(ACTION_ADD_WISH, null))
        assertEquals(AppCommand.RefreshPrices, appCommand(ACTION_REFRESH_PRICES, null))
        assertNull(appCommand("android.intent.action.MAIN", null))
        assertNull(appCommand(null, null))
    }

    // ----------------------------------------------------------- the price run

    @Test
    fun `a shop that did not answer leaves its wish exactly as it was`() {
        val items = listOf(
            wish("1", "https://shop.example/a"),
            wish("2", "https://shop.example/b"),
            wish("3", "https://shop.example/c")
        )
        val fetched = listOf(
            items[0].copy(price = 900.0),
            null,
            items[2].copy(price = 1_100.0)
        )

        val result = applyRefresh(items, fetched, today = 20_010L)

        assertEquals(2, result.updated)
        assertEquals(900.0, result.wishes[0].price, 0.0)
        assertEquals(items[1], result.wishes[1])
        assertEquals(1_100.0, result.wishes[2].price, 0.0)
        assertEquals(3, result.wishes.size)
    }

    @Test
    fun `a run where nothing answered changes nothing`() {
        val items = listOf(wish("1", "https://shop.example/a"))

        val result = applyRefresh(items, listOf(null), today = 20_010L)

        assertEquals(0, result.updated)
        assertEquals(items, result.wishes)
    }

    @Test
    fun `a short list of results does not drop the wishes it does not cover`() {
        val items = listOf(wish("1", "https://a"), wish("2", "https://b"))

        val result = applyRefresh(items, emptyList(), today = 20_010L)

        assertEquals(0, result.updated)
        assertEquals(2, result.wishes.size)
    }

    @Test
    fun `the result is said in a form Ukrainian allows`() {
        assertEquals("Немає чого оновлювати", refreshMessage(0, 0))
        assertEquals("Жодної ціни не вдалося прочитати", refreshMessage(0, 4))
        assertEquals("Ціни оновлено: 1 позиція", refreshMessage(1, 1))
        assertEquals("Ціни оновлено: 3 позиції", refreshMessage(3, 3))
        assertEquals("Ціни оновлено: 11 позицій", refreshMessage(11, 11))
        assertEquals("Ціни оновлено: 21 позиція", refreshMessage(21, 21))
        assertEquals("Ціни оновлено: 2 з 5", refreshMessage(2, 5))
    }
}

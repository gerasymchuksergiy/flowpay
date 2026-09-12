package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wish watched in several shops.
 *
 * The behaviour that matters is what happens when the shops disagree: one dies,
 * one goes out of stock, one starts quoting dollars. None of those may drag the
 * wish down while another shop is still answering, and all of them together must
 * behave exactly the way one dead shop behaves today.
 */
class WishSourcesTest {

    private val today = 20_000L
    private val rate = FxRate(41.0, 41.34, SOURCE_MONOBANK)

    private fun wish(vararg sources: WishSource) = Wish(
        id = "id",
        name = "Навушники",
        url = sources.firstOrNull()?.url.orEmpty(),
        image = "",
        price = sources.firstOrNull()?.price ?: 0.0,
        history = emptyList(),
        sources = sources.toList()
    )

    private fun source(
        host: String,
        price: Double,
        freshness: Freshness = Freshness.OK
    ) = WishSource(
        url = "https://$host/item",
        price = price,
        freshness = freshness,
        checkedDay = today - 1,
        amount = price,
        currency = UAH,
        rate = 1.0
    )

    private fun page(price: String, currency: String = "UAH") = """
        <meta property="og:title" content="Навушники">
        <script type="application/ld+json">
        {"@type":"Product","offers":{"price":"$price","priceCurrency":"$currency"}}
        </script>
    """.trimIndent()

    // ------------------------------------------------------- the old shape still loads

    @Test
    fun `a wish saved with one address comes back as a one-source wish`() {
        val stored = JSONObject()
            .put("id", "old").put("n", "Товар").put("u", "https://rozetka.ua/item")
            .put("p", 2_199.0).put("v", "Deluxe").put("fr", "OUT_OF_STOCK").put("cd", 19_990L)

        val restored = wishOf(stored)
        val sources = wishSources(restored)

        assertEquals(1, sources.size)
        assertEquals("https://rozetka.ua/item", sources.single().url)
        assertEquals(2_199.0, sources.single().price, 0.001)
        assertEquals("Deluxe", sources.single().variant)
        assertEquals(Freshness.OUT_OF_STOCK, sources.single().freshness)
        assertEquals(19_990L, sources.single().checkedDay)
        // A price saved as a bare number is hryvnia, because that is all the app
        // could read when it was written.
        assertEquals(UAH, sources.single().currency)
    }

    @Test
    fun `sources survive a round trip through storage`() {
        val original = wish(
            source("rozetka.ua", 2_199.0),
            WishSource(
                url = "https://steampowered.com/app",
                price = 2_480.0,
                variant = "Deluxe",
                freshness = Freshness.OK,
                checkedDay = today,
                amount = 59.99,
                currency = USD,
                rate = 41.34
            )
        )

        val restored = wishOf(wishJson(original))

        assertEquals(original.sources, restored.sources)
    }

    @Test
    fun `a wish with no shops of its own comes back exactly as it went in`() {
        // The bin restores by writing the stored shape back, so the round trip has
        // to be identity. Filling the array in on the way past would mean a wish
        // came out of the bin subtly different from the one that went in.
        val old = Wish("id", "Товар", "https://shop.ua/x", "", 999.0, history = emptyList())

        val restored = wishOf(wishJson(old))

        assertEquals(old, restored)
        assertTrue(restored.sources.isEmpty())
        // And it still reads as the one-source wish it has always been.
        assertEquals("https://shop.ua/x", wishSources(restored).single().url)
        assertEquals(999.0, wishSources(restored).single().price, 0.001)
    }

    @Test
    fun `a wish with no address at all has no sources to invent`() {
        val orphan = Wish("id", "Товар", "", "", 0.0, history = emptyList())

        assertTrue(wishSources(orphan).isEmpty())
    }

    // --------------------------------------------------------------- picking a price

    @Test
    fun `the wish shows the cheapest shop that answers`() {
        val sources = listOf(
            source("rozetka.ua", 2_400.0),
            source("comfy.ua", 2_199.0),
            source("allo.ua", 2_350.0)
        )

        assertEquals("https://comfy.ua/item", bestSource(sources)?.url)
        assertEquals(Freshness.OK, sourceFreshness(sources))
    }

    @Test
    fun `a shop with no price is not a candidate however fresh it claims to be`() {
        val sources = listOf(source("rozetka.ua", 0.0), source("comfy.ua", 2_199.0))

        assertEquals("https://comfy.ua/item", bestSource(sources)?.url)
    }

    // ------------------------------------------------------------- one shop dies

    @Test
    fun `one shop going gone leaves the wish healthy on the others`() {
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))

        val reading = mergeSources(
            previous,
            listOf(
                SourceReading.Stale(
                    previous.sources[0].copy(checkedDay = today, freshness = Freshness.GONE)
                ),
                readSource(previous.sources[1], page("2199"), today, rate)
            ),
            today,
            rate
        )

        assertTrue(reading is Reading.Priced)
        val next = (reading as Reading.Priced).wish
        assertEquals(Freshness.OK, next.freshness)
        assertFalse(isStale(next.freshness))
        assertEquals(2_199.0, next.price, 0.001)
        assertEquals("https://comfy.ua/item", next.url)
        // The dead shop keeps its own state, so the screen can still explain it.
        assertEquals(Freshness.GONE, next.sources[0].freshness)
    }

    @Test
    fun `the price follows the surviving shop even when it is dearer`() {
        val previous = wish(source("rozetka.ua", 2_199.0), source("comfy.ua", 2_400.0))

        val reading = mergeSources(
            previous,
            listOf(
                SourceReading.Stale(
                    previous.sources[0].copy(checkedDay = today, freshness = Freshness.GONE)
                ),
                SourceReading.Failed
            ),
            today,
            rate
        )

        val next = (reading as Reading.Priced).wish
        assertEquals(2_400.0, next.price, 0.001)
        assertEquals("https://comfy.ua/item", next.url)
    }

    @Test
    fun `when every shop dies the wish goes stale the way one shop always did`() {
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))

        val reading = mergeSources(
            previous,
            listOf(
                SourceReading.Stale(
                    previous.sources[0].copy(checkedDay = today, freshness = Freshness.GONE)
                ),
                SourceReading.Stale(
                    previous.sources[1].copy(checkedDay = today, freshness = Freshness.OUT_OF_STOCK)
                )
            ),
            today,
            rate
        )

        assertTrue(reading is Reading.Stale)
        val next = (reading as Reading.Stale).wish
        assertTrue(isStale(next.freshness))
        // The most recoverable reason wins: an out-of-stock variant usually comes
        // back, a page that 404s does not.
        assertEquals(Freshness.OUT_OF_STOCK, next.freshness)
        // The last known price is left standing, exactly as before.
        assertEquals(2_400.0, next.price, 0.001)
        assertEquals(today, next.checkedDay)
    }

    @Test
    fun `a wish whose every shop times out is not touched at all`() {
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))
            .copy(checkedDay = today - 5, freshness = Freshness.OK)

        val reading = mergeSources(
            previous,
            listOf(SourceReading.Failed, SourceReading.Failed),
            today,
            rate
        )

        assertSame(Reading.Failed, reading)
    }

    @Test
    fun `a shop that could not be reached keeps the price it had`() {
        // Otherwise a tunnel would make the wish look cheaper by dropping whichever
        // shop happened to be dearest.
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))

        val reading = mergeSources(
            previous,
            listOf(
                readSource(previous.sources[0], page("2500"), today, rate),
                SourceReading.Failed
            ),
            today,
            rate
        )

        val next = (reading as Reading.Priced).wish
        assertEquals(2_500.0, next.sources[0].price, 0.001)
        assertEquals(2_199.0, next.sources[1].price, 0.001)
        assertEquals(2_199.0, next.price, 0.001)
    }

    // --------------------------------------------------------------- the history

    @Test
    fun `history stays one series holding the best price of the day`() {
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_300.0))
            .copy(history = listOf(PricePoint(2_500.0, today - 10)))

        val reading = mergeSources(
            previous,
            listOf(
                readSource(previous.sources[0], page("2400"), today, rate),
                readSource(previous.sources[1], page("2199"), today, rate)
            ),
            today,
            rate
        )

        val next = (reading as Reading.Priced).wish
        assertEquals(listOf(2_500.0, 2_199.0), next.history.map { it.price })
        assertEquals(41.34, next.history.last().rate, 0.001)
    }

    // -------------------------------------------------------------- a dollar shop

    @Test
    fun `a shop quoting dollars is compared in hryvnia like the rest`() {
        val previous = wish(source("rozetka.ua", 2_600.0), source("steam.com", 2_480.0))

        val reading = mergeSources(
            previous,
            listOf(
                readSource(previous.sources[0], page("2600"), today, rate),
                readSource(previous.sources[1], page("59.99", "USD"), today, rate)
            ),
            today,
            rate
        )

        val next = (reading as Reading.Priced).wish
        assertEquals(59.99 * 41.34, next.price, 0.001)
        assertEquals("https://steam.com/item", next.url)
        assertEquals(USD, next.sources[1].currency)
        assertEquals(59.99, next.sources[1].amount, 0.001)
    }

    @Test
    fun `a shop quoting money with no rate is unreadable rather than wrong`() {
        val previous = wish(source("rozetka.ua", 2_600.0), source("shop.se", 2_480.0))

        val reading = mergeSources(
            previous,
            listOf(
                readSource(previous.sources[0], page("2600"), today, rate),
                readSource(previous.sources[1], page("499", "SEK"), today, rate)
            ),
            today,
            rate
        )

        val next = (reading as Reading.Priced).wish
        // The krona shop must not win by looking like 499 hryvnia.
        assertEquals(2_600.0, next.price, 0.001)
        assertEquals(Freshness.UNREADABLE, next.sources[1].freshness)
        assertEquals("SEK", next.sources[1].currency)
        assertEquals(499.0, next.sources[1].amount, 0.001)
        // The wish itself is untroubled: another shop answered.
        assertEquals(Freshness.OK, next.freshness)
    }

    @Test
    fun `a lone shop that starts quoting kronor makes the wish unreadable`() {
        val previous = wish(source("shop.se", 2_480.0))

        val reading = mergeSources(
            previous,
            listOf(readSource(previous.sources[0], page("499", "SEK"), today, rate)),
            today,
            rate
        )

        assertTrue(reading is Reading.Stale)
        assertEquals(Freshness.UNREADABLE, (reading as Reading.Stale).wish.freshness)
        // The last figure anyone could actually pay is left standing.
        assertEquals(2_480.0, reading.wish.price, 0.001)
    }

    @Test
    fun `the row for an unconvertible shop says which money it is in`() {
        val stuck = WishSource(
            url = "https://shop.se/x",
            price = 0.0,
            freshness = Freshness.UNREADABLE,
            amount = 499.0,
            currency = "SEK"
        )

        val note = sourceNote(stuck)

        assertNotNull(note)
        assertTrue(note!!, note.contains("SEK"))
        assertEquals("499 SEK", sourcePriceLabel(stuck))
    }

    @Test
    fun `an ordinary dead shop keeps the wording it always had`() {
        val dead = WishSource(url = "https://shop.ua/x", freshness = Freshness.GONE)

        assertEquals(freshnessNote(Freshness.GONE), sourceNote(dead))
        assertEquals("—", sourcePriceLabel(dead))
    }

    // --------------------------------------------------- adding and removing shops

    @Test
    fun `adding a cheaper shop moves the price and the link to it`() {
        val previous = wish(source("rozetka.ua", 2_400.0))

        val next = withSource(previous, source("comfy.ua", 2_199.0))

        assertEquals(2, next.sources.size)
        assertEquals(2_199.0, next.price, 0.001)
        assertEquals("https://comfy.ua/item", next.url)
    }

    @Test
    fun `adding a dearer shop leaves the price where it was`() {
        val previous = wish(source("rozetka.ua", 2_199.0))

        val next = withSource(previous, source("comfy.ua", 2_400.0))

        assertEquals(2_199.0, next.price, 0.001)
        assertEquals("https://rozetka.ua/item", next.url)
    }

    @Test
    fun `the same shop pasted twice is added once`() {
        val previous = wish(source("rozetka.ua", 2_400.0))

        // Differs only in scheme and a trailing slash, which is what sharing the
        // same page from the app and from the browser produces.
        val next = withSource(
            previous,
            WishSource(url = "http://rozetka.ua/item/", price = 1.0)
        )

        assertEquals(1, next.sources.size)
        assertEquals(2_400.0, next.price, 0.001)
        assertTrue(hasSource(previous, "http://rozetka.ua/item/"))
    }

    @Test
    fun `removing a shop recomputes which one the wish follows`() {
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))
            .copy(price = 2_199.0, url = "https://comfy.ua/item")

        val next = withoutSource(previous, "https://comfy.ua/item")

        assertEquals(1, next.sources.size)
        assertEquals(2_400.0, next.price, 0.001)
        assertEquals("https://rozetka.ua/item", next.url)
    }

    @Test
    fun `the last shop cannot be removed`() {
        // A wish with nowhere to read is a number nothing will ever update. The way
        // to be rid of it is to delete the wish, where the bin can give it back.
        val previous = wish(source("rozetka.ua", 2_400.0))

        assertSame(previous, withoutSource(previous, "https://rozetka.ua/item"))
    }

    @Test
    fun `removing a shop that is not there changes nothing`() {
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))

        assertSame(previous, withoutSource(previous, "https://allo.ua/item"))
    }

    // ------------------------------------------------------------------ the extras

    @Test
    fun `a shared link is matched against every shop, not just the current one`() {
        val watched = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))

        val shared = sharedLink("Дивись https://comfy.ua/item", listOf(watched))

        assertTrue(shared is SharedLink.Known)
        assertEquals(watched.id, (shared as SharedLink.Known).wish.id)
    }

    @Test
    fun `the spread is only reported when two shops actually answered`() {
        assertNull(sourceSpreadNote(listOf(source("rozetka.ua", 2_400.0))))
        assertNull(
            sourceSpreadNote(
                listOf(
                    source("rozetka.ua", 2_400.0),
                    source("comfy.ua", 2_199.0, Freshness.GONE)
                )
            )
        )
        val note = sourceSpreadNote(
            listOf(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_199.0))
        )
        assertNotNull(note)
        assertTrue(note!!, note.contains("201"))
    }

    @Test
    fun `shops asking the same money are not a spread worth a sentence`() {
        val note = sourceSpreadNote(
            listOf(source("rozetka.ua", 2_199.0), source("comfy.ua", 2_199.50))
        )

        assertNull(note)
    }

    @Test
    fun `a shop is named by its host`() {
        assertEquals("rozetka.ua", sourceName("https://www.rozetka.ua/item/123"))
        assertEquals("Магазин", sourceName("не посилання"))
    }

    @Test
    fun `with nothing to go on the wish reports itself unreadable`() {
        assertEquals(Freshness.UNREADABLE, sourceFreshness(emptyList()))
    }

    // ------------------------------------------- where browsing meets the sources

    @Test
    fun `ordering by price uses the cheapest shop, not the one added first`() {
        val cheapElsewhere = wish(source("rozetka.ua", 5_000.0), source("comfy.ua", 900.0))
            .copy(id = "a", price = 900.0, url = "https://comfy.ua/item")
        val plain = Wish("b", "Б", "https://shop.ua/b", "", 2_000.0, history = emptyList())

        assertEquals(
            listOf("a", "b"),
            sortWishes(listOf(plain, cheapElsewhere), WishSort.CHEAPEST).map { it.id }
        )
        assertEquals(
            listOf("b", "a"),
            sortWishes(listOf(plain, cheapElsewhere), WishSort.DEAREST).map { it.id }
        )
    }

    @Test
    fun `a category total counts the cheapest shop's price`() {
        val watched = wish(source("rozetka.ua", 5_000.0), source("comfy.ua", 900.0))
            .copy(price = 900.0, category = "Техніка")

        assertEquals(900.0, allCategoriesTotal(listOf(watched)), 0.001)
        assertEquals(900.0, categoryTotals(listOf(watched)).single().total, 0.001)
    }

    @Test
    fun `a wish with no readable price never reads as the cheapest thing on the list`() {
        // Its one shop quotes kronor, so nothing could be converted and the price
        // stayed at nought. Sorting on the bare figure used to float it to the very
        // top of "Дешевші", where it looked free.
        val unpriced = Wish("none", "Något", "https://shop.se/x", "", 0.0, history = emptyList())
            .copy(freshness = Freshness.UNREADABLE)
        val cheap = Wish("cheap", "Дешеве", "https://shop.ua/c", "", 100.0, history = emptyList())
        val dear = Wish("dear", "Дороге", "https://shop.ua/d", "", 9_000.0, history = emptyList())
        val list = listOf(unpriced, dear, cheap)

        assertEquals(
            listOf("cheap", "dear", "none"),
            sortWishes(list, WishSort.CHEAPEST).map { it.id }
        )
        assertEquals(
            listOf("dear", "cheap", "none"),
            sortWishes(list, WishSort.DEAREST).map { it.id }
        )
        assertFalse(hasReadablePrice(unpriced))
    }

    @Test
    fun `a wish whose shops went quiet keeps its place in a price ordering`() {
        // Its last known price is a real figure someone once could have paid, so it
        // sorts on that rather than being shoved to the end with the unpriced.
        val stale = Wish("stale", "Тихе", "https://shop.ua/s", "", 500.0, history = emptyList())
            .copy(freshness = Freshness.GONE)
        val dear = Wish("dear", "Дороге", "https://shop.ua/d", "", 9_000.0, history = emptyList())

        assertEquals(
            listOf("stale", "dear"),
            sortWishes(listOf(dear, stale), WishSort.CHEAPEST).map { it.id }
        )
        assertTrue(hasReadablePrice(stale))
    }

    @Test
    fun `an unreadable wish still answers to search and to its category`() {
        // Vanishing from the grid would read as a deletion the person did not do.
        val unpriced = Wish("none", "Något", "https://shop.se/x", "", 0.0, history = emptyList())
            .copy(freshness = Freshness.UNREADABLE, category = "Техніка")

        assertEquals(listOf("none"), filterWishes(listOf(unpriced), null, "något").map { it.id })
        assertEquals(
            listOf("none"),
            filterWishes(listOf(unpriced), "Техніка", "").map { it.id }
        )
    }
}

package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Turning what the app knows about a thing into words a comparison site can match.
 *
 * The failure this guards is silent and total: hotline finds nothing whatsoever for
 * «ігровий контролер gamesir super nova multi platform з hall - Temu Ukraine», and
 * finds the thing immediately for the three words in the middle of it. A search
 * button built on the untidied title would look like it worked, open a real page,
 * and show an empty result — and nothing on either side would say why.
 *
 * Every expectation about what does and does not match was checked against the
 * live site before it was written down here.
 */
class CompareTest {

    private fun wish(
        name: String = "Товар",
        url: String = "https://rozetka.com.ua/p1/",
        brand: String = "",
        freshness: Freshness = Freshness.OK,
        price: Double = 0.0,
        sources: List<WishSource> = emptyList(),
        searchQuery: String = ""
    ) = Wish(
        id = "1",
        name = name,
        url = url,
        image = "",
        price = price,
        history = emptyList(),
        about = ProductAbout(brand = brand),
        freshness = freshness,
        sources = sources,
        searchQuery = searchQuery
    )

    // ------------------------------------------------------------------ the query

    /**
     * The example the whole feature was asked for, end to end.
     *
     * A Temu title: a Ukrainian category in front, English filler behind, a
     * preposition in the middle of the filler, and the shop's name on the end. The
     * three words that identify the thing are surrounded on both sides.
     */
    @Test
    fun `strips a Temu title down to the brand and model`() {
        assertEquals(
            "gamesir super nova",
            searchTerms("ігровий контролер gamesir super nova multi platform з hall - Temu Ukraine")
        )
    }

    /** The shop's own name, hung off the title by every separator shops use. */
    @Test
    fun `drops whatever the shop appended to the title`() {
        assertEquals("Sony WH-1000XM5", searchTerms("Sony WH-1000XM5 | Rozetka"))
        assertEquals("Sony WH-1000XM5", searchTerms("Sony WH-1000XM5 — Цифрус"))
        assertEquals("Sony WH-1000XM5", searchTerms("Sony WH-1000XM5 - Temu Ukraine"))
        assertEquals("Sony WH-1000XM5", searchTerms("Sony WH-1000XM5 · Comfy"))
    }

    /**
     * The separator has to be spaced, because a model number is full of hyphens.
     *
     * "WH-1000XM5" split at its hyphen would leave "WH", and a comparison site
     * asked for "Sony WH" is a comparison site asked for nothing.
     */
    @Test
    fun `keeps a hyphen that lives inside a model number`() {
        assertEquals("Sony WH-1000XM5 Black", searchTerms("Sony WH-1000XM5 Black"))
        assertEquals("Kite Education K22-700M", searchTerms("Рюкзак шкільний Kite Education K22-700M"))
    }

    /**
     * The rule that does the real work: in a Ukrainian listing the description is
     * Cyrillic and the identity is Latin.
     */
    @Test
    fun `takes the latin run out of a mixed title`() {
        assertEquals(
            "Sony WH-1000XM5 Black",
            searchTerms("Навушники Sony WH-1000XM5 Black бездротові")
        )
        assertEquals(
            "Dyson V15 Detect",
            searchTerms("Пилосос Dyson V15 Detect Absolute купити в Києві")
        )
    }

    /** A model number is script-neutral and must not break the run around it. */
    @Test
    fun `a number inside the latin run does not end it`() {
        assertEquals(
            "ASICS Gel 1130",
            searchTerms("ASICS Gel 1130 Dark Grey демісезонні")
        )
    }

    /**
     * Two words at least, or a lone import decides the whole query.
     *
     * «Кава Чорна Карта Gold» reduced to "Gold" searches for nothing to do with
     * coffee; the Ukrainian name is what the thing is called and is used as it is.
     */
    @Test
    fun `one latin word is not an identity`() {
        assertEquals("Кава Чорна Карта", searchTerms("Кава Чорна Карта Gold"))
    }

    /** A title with no Latin in it is simply used, shortened. */
    @Test
    fun `an entirely ukrainian name is left alone`() {
        assertEquals("Книга Гаррі Поттер", searchTerms("Книга Гаррі Поттер і келих вогню"))
    }

    /**
     * Three words, and then one more only if it is a model number.
     *
     * "Logitech MX Master" and "Logitech MX Master 3S" are different mice; "Xiaomi
     * Redmi Note" and "Xiaomi Redmi Note 13" are different phones. The word after
     * the number is not taken, whatever it is.
     */
    @Test
    fun `keeps the model number past the limit and nothing else`() {
        assertEquals("Logitech MX Master 3S", searchTerms("Миша Logitech MX Master 3S Graphite"))
        assertEquals(
            "Xiaomi Redmi Note 13",
            searchTerms("Смартфон Xiaomi Redmi Note 13 Pro Midnight Black")
        )
        assertEquals("Dyson Supersonic Nural", searchTerms("Dyson Supersonic Nural Ceramic Patina"))
    }

    /** Grammar and the furniture shops write for search engines carry nothing. */
    @Test
    fun `drops stopwords and shop furniture`() {
        assertEquals("Sony WH-1000XM5", searchTerms("Купити Sony WH-1000XM5 в Києві недорого"))
    }

    /** The price a shop hangs off its own og:title is already known to be junk. */
    @Test
    fun `drops the price suffix the title cleaner knows about`() {
        assertEquals(
            "ASICS Gel 1130",
            searchTerms("ASICS Gel 1130 Dark Grey демісезонні, ціна 2203.24")
        )
    }

    /** The strongest single token goes in front when the page declared one. */
    @Test
    fun `puts a declared brand in front`() {
        assertEquals("ASICS Gel 1130", searchTerms("Gel 1130 Dark Grey", brand = "ASICS"))
    }

    /**
     * And is not repeated when the title already says it.
     *
     * The declared spelling is the one that survives — "GameSir" rather than the
     * title's "gamesir". Nothing turns on it for the search, which matches either
     * way, but the query is shown to the person before he sends it, and the shop's
     * own capitals are the ones he will recognise as the name of the thing.
     */
    @Test
    fun `does not say the brand twice`() {
        assertEquals(
            "GameSir super nova",
            searchTerms(
                "ігровий контролер gamesir super nova multi platform",
                brand = "GameSir"
            )
        )
    }

    /**
     * Temu publishes `"brand":"Temu"` on other people's products.
     *
     * "Temu gamesir super" asks a comparison site about a shop that is not on it,
     * and costs the query the one word it could least afford to lose.
     */
    @Test
    fun `a brand that is really the shop is not a brand`() {
        assertEquals(
            "gamesir super nova",
            searchTerms(
                "ігровий контролер gamesir super nova multi platform",
                brand = "Temu",
                shop = "temu.com"
            )
        )
    }

    /** A name that is nothing but stopwords is still searched for, not refused. */
    @Test
    fun `falls back to the words it has rather than to nothing`() {
        assertEquals("для на та", searchTerms("для на та"))
    }

    @Test
    fun `a nameless wish has nothing to search for`() {
        assertEquals("", searchTerms("   "))
        assertEquals("", searchTerms("!!! ---"))
    }

    // -------------------------------------------------------------------- the URL

    /**
     * The address, which is the one thing here that cannot be reasoned out.
     *
     * `hotline.ua/ua/search/?q=` answers 200 with "Legacy home controller has been
     * disabled" and no products at all, so this is pinned rather than left to be
     * rewritten by someone guessing at the tidier spelling.
     */
    @Test
    fun `builds the search address the live site actually uses`() {
        assertEquals(
            "https://hotline.ua/ua/sr/?q=gamesir%20super%20nova",
            hotlineSearch("gamesir super nova")
        )
    }

    @Test
    fun `encodes ukrainian and punctuation into the query`() {
        assertEquals("https://hotline.ua/ua/sr/?q=%D0%9A%D0%B0%D0%B2%D0%B0", hotlineSearch("Кава"))
        // A literal plus survives as a plus rather than being read back as a space.
        assertEquals("https://hotline.ua/ua/sr/?q=USB-C%2B", hotlineSearch("USB-C+"))
    }

    @Test
    fun `trims what the person typed before sending it`() {
        assertEquals("https://hotline.ua/ua/sr/?q=Sony", hotlineSearch("  Sony  "))
    }

    // --------------------------------------------------------------- when it shows

    @Test
    fun `a wish being read normally is not offered a search`() {
        assertFalse(worthComparing(wish(name = "Sony WH-1000XM5", price = 9000.0)))
    }

    @Test
    fun `a sold out wish is`() {
        assertTrue(
            worthComparing(wish(name = "Sony WH-1000XM5", freshness = Freshness.OUT_OF_STOCK))
        )
    }

    /** A page that has gone is a thing that cannot be bought here either. */
    @Test
    fun `a wish whose page has gone is too`() {
        assertTrue(worthComparing(wish(name = "Sony WH-1000XM5", freshness = Freshness.GONE)))
    }

    /**
     * The line: an unreadable page is the app's reading failing, not the shelf
     * being empty. The shop is almost certainly still selling the thing, and the
     * answer already on offer is a price typed in by hand.
     */
    @Test
    fun `an unreadable page is not an empty shelf`() {
        assertFalse(
            worthComparing(wish(name = "Sony WH-1000XM5", freshness = Freshness.UNREADABLE))
        )
    }

    @Test
    fun `a hand-typed price is not a reason to go looking`() {
        assertFalse(
            worthComparing(
                wish(name = "Sony WH-1000XM5", freshness = Freshness.MANUAL, price = 9000.0)
            )
        )
    }

    /** One shop out of stock while another has it in a box is not a problem. */
    @Test
    fun `another shop still having it settles the question`() {
        val wish = wish(
            name = "Sony WH-1000XM5",
            freshness = Freshness.OK,
            sources = listOf(
                WishSource(
                    url = "https://a.ua/p/",
                    freshness = Freshness.OUT_OF_STOCK,
                    availability = Availability.SOLD_OUT
                ),
                WishSource(url = "https://b.ua/p/", price = 9000.0, freshness = Freshness.OK)
            )
        )
        assertFalse(worthComparing(wish))
    }

    /** And every shop being out of stock is exactly when it is one. */
    @Test
    fun `every shop being out is when it is offered`() {
        val wish = wish(
            name = "Sony WH-1000XM5",
            freshness = Freshness.OUT_OF_STOCK,
            sources = listOf(
                WishSource(
                    url = "https://a.ua/p/",
                    freshness = Freshness.OUT_OF_STOCK,
                    availability = Availability.SOLD_OUT
                ),
                WishSource(url = "https://b.ua/p/", freshness = Freshness.GONE)
            )
        )
        assertTrue(worthComparing(wish))
    }

    @Test
    fun `a wish with no words to search for is not offered a search`() {
        assertFalse(worthComparing(wish(name = "   ", freshness = Freshness.OUT_OF_STOCK)))
    }

    // ------------------------------------------------------------------- the words

    /** What the note says depends on whether the thing is coming back. */
    @Test
    fun `the note follows what actually happened`() {
        assertEquals(
            "Ця сторінка зникла. Пошукайте товар в інших магазинах.",
            compareNote(wish(freshness = Freshness.GONE))
        )
        assertEquals(
            "Цей магазин більше його не продає. Пошукайте в інших.",
            compareNote(
                wish(
                    freshness = Freshness.OUT_OF_STOCK,
                    sources = listOf(
                        WishSource(
                            url = "https://a.ua/p/",
                            freshness = Freshness.OUT_OF_STOCK,
                            availability = Availability.DISCONTINUED
                        )
                    )
                )
            )
        )
        assertEquals(
            "Тут його зараз не купити. Подивіться, у кого він є.",
            compareNote(wish(freshness = Freshness.OUT_OF_STOCK))
        )
    }

    // ----------------------------------------------------------- his words, stored

    @Test
    fun `a corrected query wins over the built one`() {
        val wish = wish(name = "ігровий контролер gamesir super nova", searchQuery = "GameSir Nova 2")
        assertEquals("GameSir Nova 2", wishSearchTerms(wish))
        assertEquals("gamesir super nova", builtSearchTerms(wish))
    }

    @Test
    fun `an uncorrected wish follows the builder`() {
        val wish = wish(name = "Навушники Sony WH-1000XM5 Black")
        assertEquals("Sony WH-1000XM5 Black", wishSearchTerms(wish))
    }

    /** Blank is not a correction; a wish with one still gets a usable query. */
    @Test
    fun `whitespace is not a correction`() {
        assertEquals(
            "Sony WH-1000XM5",
            wishSearchTerms(wish(name = "Sony WH-1000XM5", searchQuery = "   "))
        )
    }

    /** The wish's own shop is what a brand is checked against, not a fixed list. */
    @Test
    fun `the builder checks the brand against the wish's own shop`() {
        val wish = wish(
            name = "ігровий контролер gamesir super nova multi platform",
            url = "https://www.temu.com/ua/p-1.html",
            brand = "Temu"
        )
        assertEquals("gamesir super nova", builtSearchTerms(wish))
    }

    // ------------------------------------------------------------------- and back

    /** Both sides of the mapping, which is where a persisted field goes missing. */
    @Test
    fun `a corrected query survives being stored and read back`() {
        val stored = wishJson(wish(name = "Товар", searchQuery = "GameSir Nova 2"))
        assertEquals("GameSir Nova 2", wishOf(stored).searchQuery)
    }

    @Test
    fun `a wish saved before the search existed reads back uncorrected`() {
        val old = JSONObject().put("id", "1").put("n", "Навушники Sony WH-1000XM5 Black")
            .put("u", "https://rozetka.com.ua/p1/")
        val wish = wishOf(old)
        assertEquals("", wish.searchQuery)
        assertEquals("Sony WH-1000XM5 Black", wishSearchTerms(wish))
    }

    /** An empty correction is written rather than omitted, so the bin restores it. */
    @Test
    fun `an uncorrected wish round-trips as uncorrected`() {
        assertEquals("", wishOf(wishJson(wish(name = "Товар"))).searchQuery)
    }
}

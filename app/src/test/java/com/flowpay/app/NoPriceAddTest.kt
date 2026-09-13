package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adding a thing from a page that says what it is and not what it costs.
 *
 * The failure this closes: a Temu link was shared in, the page came back with a
 * title, a photograph and a description — and the add failed outright on the one
 * missing number, keeping none of the three. A shop that tells you what the thing
 * is but not its price is not a failure to add. It is a wish whose price has to be
 * typed, which is a state the app already had a word for and no way into.
 */
class NoPriceAddTest {

    private val describedPage = """
        <html><head>
        <meta property="og:title" content="ігровий контролер gamesir super nova - Temu Ukraine"/>
        <meta property="og:image" content="https://img.example/nova.jpg"/>
        <meta property="og:description" content="Бездротовий геймпад із холлівськими стіками."/>
        </head><body><div id="root"></div></body></html>
    """.trimIndent()

    private val pricedPage = """
        <html><head>
        <meta property="og:title" content="GameSir Super Nova"/>
        <meta property="og:image" content="https://img.example/nova.jpg"/>
        <meta property="og:description" content="Бездротовий геймпад."/>
        <meta property="product:price:amount" content="1458.21"/>
        <meta property="product:price:currency" content="UAH"/>
        </head><body></body></html>
    """.trimIndent()

    // ------------------------------------------ what the page gave besides a price

    @Test
    fun `a page with no price still gives up what the thing is`() {
        val facts = pageFacts(describedPage)

        assertTrue(facts.describable)
        assertEquals("ігровий контролер gamesir super nova - Temu Ukraine", facts.name)
        assertEquals("https://img.example/nova.jpg", facts.image)
        assertEquals("Бездротовий геймпад із холлівськими стіками.", facts.about.description)
    }

    @Test
    fun `a page that named nothing is not describable`() {
        assertFalse(pageFacts("<html><head></head></html>").describable)
        // A description with no name and no photograph is not a thing anyone would
        // recognise in a list a week later.
        val blurbOnly = """<meta property="og:description" content="Найкращі ціни"/>"""
        assertFalse(pageFacts(blurbOnly).describable)
        assertTrue(pageFacts(blurbOnly).about.description.isNotBlank())
    }

    // --------------------------------------------- which of the three things happened

    @Test
    fun `a priced page is a priced add, and keeps everything else it said`() {
        val read = readForAdd(pricedPage, "https://shop.example/x", "id", 20_000L)

        assertTrue(read is PageAdd.Priced)
        val wish = (read as PageAdd.Priced).wish
        assertEquals(1458.21, wish.price, 0.001)
        assertEquals("GameSir Super Nova", wish.name)
        assertEquals("https://img.example/nova.jpg", wish.image)
        assertEquals("Бездротовий геймпад.", wish.about.description)
        assertEquals(Freshness.OK, wish.freshness)
    }

    @Test
    fun `a page with no price but a name is a described add`() {
        val read = readForAdd(describedPage, "https://temu.example/x", "id", 20_000L)

        assertTrue(read is PageAdd.Described)
        val wish = (read as PageAdd.Described).wish
        assertEquals("ігровий контролер gamesir super nova - Temu Ukraine", wish.name)
        assertEquals("https://img.example/nova.jpg", wish.image)
        assertEquals("Бездротовий геймпад із холлівськими стіками.", wish.about.description)
        assertEquals("https://temu.example/x", wish.url)
        // Nothing has been typed yet, so nothing may claim to have been. This is
        // the same state a link that could not be fetched at all comes back in.
        assertEquals(Freshness.UNREADABLE, wish.freshness)
        assertEquals(0.0, wish.price, 0.001)
        assertTrue(wish.history.isEmpty())
    }

    @Test
    fun `a page that said nothing at all is blank`() {
        assertEquals(PageAdd.Blank, readForAdd("<html><head></head></html>", "https://s.example/x", "id"))
    }

    // ------------------------------------------------ the wish you type a price into

    private val facts = PageFacts(
        name = "GameSir Super Nova",
        image = "https://img.example/nova.jpg",
        about = ProductAbout(description = "Бездротовий геймпад.")
    )

    private fun typed(price: Double = 1458.0) =
        wishFromFacts(facts, "https://temu.example/x", "w1", price, 20_000L)

    @Test
    fun `a typed price is marked as typed, not passed off as a reading`() {
        val wish = typed()

        assertEquals(Freshness.MANUAL, wish.freshness)
        assertEquals("Ціна вручну", freshnessLabel(wish.freshness))
        assertNotNull(freshnessNote(wish.freshness))
        assertEquals(1458.0, wish.price, 0.001)
        assertEquals("GameSir Super Nova", wish.name)
        assertEquals("Бездротовий геймпад.", wish.about.description)
        assertEquals(listOf(1458.0), wish.history.map { it.price })
        assertEquals(listOf(Freshness.MANUAL), wish.sources.map { it.freshness })
        assertEquals(20_000L, wish.checkedDay)
        assertEquals(20_000L, addedDay(wish))
        // Not stale: it is exactly as current as the person who typed it made it.
        assertFalse(isStale(wish.freshness))
    }

    @Test
    fun `a page that named nothing falls back to the shop's own name`() {
        val bare = wishFromFacts(PageFacts(), "https://temu.example/x", "w1", 100.0, 20_000L)

        assertEquals("Товар з temu.example", bare.name)
    }

    @Test
    fun `a typed wish carries a target and announces reaching it`() {
        val wish = typed(1200.0).copy(targetPrice = 1500.0)

        assertTrue(targetHit(wish, 20_000L))
        assertEquals(1500.0, wishGoal(wish), 0.001)
    }

    @Test
    fun `a typed wish sorts, searches and holds like any other`() {
        val cheap = typed(500.0).copy(id = "cheap")
        val dear = typed(9000.0).copy(id = "dear", name = "Навушники Sony")
        val list = listOf(dear, cheap)

        assertTrue(hasReadablePrice(cheap))
        assertEquals(listOf("cheap", "dear"), sortWishes(list, WishSort.CHEAPEST).map { it.id })
        assertEquals(listOf("dear", "cheap"), sortWishes(list, WishSort.DEAREST).map { it.id })
        assertEquals(listOf("cheap"), filterWishes(list, null, "gamesir").map { it.id })

        val held = cheap.copy(holdUntil = 20_010L)
        assertTrue(onHold(held, 20_000L))
        assertEquals(listOf("dear"), partitionByHold(listOf(dear, held), 20_000L).first.map { it.id })
    }

    @Test
    fun `a typed wish sits in a category and counts towards it`() {
        val wish = typed().copy(category = "Техніка")

        assertEquals(listOf("w1"), filterWishes(listOf(wish), "Техніка", "").map { it.id })
        assertEquals(1458.0, categoryTotals(listOf(wish)).single { it.name == "Техніка" }.total, 0.001)
    }

    @Test
    fun `a typed wish goes into the bin and comes back whole`() {
        val wish = typed().copy(targetPrice = 1200.0, category = "Техніка")

        val restored = wishOf(JSONObject(binEntryOf(wish, 20_000L).payload))

        assertEquals(Freshness.MANUAL, restored.freshness)
        assertEquals(1458.0, restored.price, 0.001)
        assertEquals(1200.0, restored.targetPrice, 0.001)
        assertEquals("Техніка", restored.category)
        assertEquals("https://img.example/nova.jpg", restored.image)
        assertEquals("Бездротовий геймпад.", restored.about.description)
        assertEquals(listOf(Freshness.MANUAL), restored.sources.map { it.freshness })
    }

    @Test
    fun `a described wish keeps its words when the link is filled in behind it`() {
        // The shared-link path saves a placeholder first and fills it in after the
        // fetch, and the description used to be dropped on exactly that hop.
        val placeholder = placeholderWish("https://temu.example/x", "w1", 20_000L)
        val read = (readForAdd(describedPage, "https://temu.example/x", "w1", 20_000L)
            as PageAdd.Described).wish

        val filled = refreshedWish(placeholder, read, 20_000L).copy(name = read.name)

        assertEquals("ігровий контролер gamesir super nova - Temu Ukraine", filled.name)
        assertEquals("https://img.example/nova.jpg", filled.image)
        assertEquals("Бездротовий геймпад із холлівськими стіками.", filled.about.description)
    }

    // --------------------------------------------------------------- what it says

    @Test
    fun `the message says which of the two things happened`() {
        assertTrue(noPriceNote(facts).startsWith("Прочитав назву й фото, але ціни на сторінці немає"))
        assertTrue(noPriceNote(facts.copy(image = "")).startsWith("Прочитав назву,"))
        assertTrue(noPriceNote(facts.copy(name = "")).startsWith("Прочитав фото,"))
        assertTrue(noPriceNote(facts).contains("Впиши її сам"))
        // And the failure that really is a failure sends you back to the link.
        assertTrue(NOTHING_READ_NOTE.contains("посилання"))
    }
}

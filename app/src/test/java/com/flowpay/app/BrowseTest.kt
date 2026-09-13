package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers making a long wishlist browsable: what the chips say, what the search
 * box finds, the two orderings added for it, and the folding that keeps one
 * category from becoming two.
 */
class BrowseTest {

    private fun wish(
        id: String,
        name: String = "Товар $id",
        category: String = "Інше",
        price: Double = 1000.0,
        target: Double = 0.0,
        addedDay: Long = 0L
    ) = Wish(
        id, name, "https://shop/$id", "", price, target, category,
        listOf(PricePoint(price, 20_000L)),
        checkedDay = 20_100L,
        addedDay = addedDay
    )

    // --------------------------------------------------------------- normalising

    @Test
    fun `two spellings of one category share a key`() {
        assertEquals(categoryKey("Техніка"), categoryKey("техніка"))
        assertEquals(categoryKey("Техніка"), categoryKey("  ТЕХНІКА  "))
        assertEquals(categoryKey("Дім та сад"), categoryKey("Дім   та   сад"))
    }

    @Test
    fun `letters that only look alike stay apart`() {
        // і, и and ї are separate letters, not decorations of one.
        assertTrue(categoryKey("Мій") != categoryKey("Мий"))
        assertTrue(categoryKey("Їжа") != categoryKey("Іжа"))
    }

    @Test
    fun `every kind of apostrophe reads as the same category`() {
        assertEquals(categoryKey("П'ятниця"), categoryKey("П’ятниця"))
        assertEquals(categoryKey("П'ятниця"), categoryKey("Пʼятниця"))
    }

    @Test
    fun `a blank category is the fallback bucket`() {
        assertEquals(OTHER_CATEGORY, categoryName(""))
        assertEquals(OTHER_CATEGORY, categoryName("   "))
        assertEquals("Техніка", categoryName("  Техніка  "))
        assertEquals("Дім та сад", categoryName("Дім  та\tсад"))
    }

    @Test
    fun `the commoner spelling of a category is the one shown`() {
        val items = listOf(
            wish("1", category = "Техніка"),
            wish("2", category = "техніка"),
            wish("3", category = "Техніка")
        )

        assertEquals(listOf("Техніка"), knownCategories(items))
    }

    @Test
    fun `categories are alphabetical with the catch-all last`() {
        val items = listOf(
            wish("1", category = "Інше"),
            wish("2", category = "Одяг"),
            wish("3", category = "Books"),
            wish("4", category = "Авто")
        )

        assertEquals(listOf("Books", "Авто", "Одяг", "Інше"), knownCategories(items))
    }

    @Test
    fun `a typed category joins the one already in use`() {
        val known = listOf("Техніка", "Одяг")

        assertEquals("Техніка", canonicalCategory("техніка", known))
        assertEquals("Техніка", canonicalCategory("  ТЕХНІКА ", known))
        // Something genuinely new is still allowed through, tidied but not renamed.
        assertEquals("Меблі", canonicalCategory("  Меблі  ", known))
        assertEquals(OTHER_CATEGORY, canonicalCategory("", known))
    }

    // ------------------------------------------------------------------- search

    @Test
    fun `search ignores case in either language`() {
        val item = wish("1", name = "Навушники Sony WH-1000XM5")

        assertTrue(matchesSearch(item, "навушники"))
        assertTrue(matchesSearch(item, "SONY"))
        assertTrue(matchesSearch(item, "xm5"))
    }

    @Test
    fun `every typed word has to appear but the order does not matter`() {
        val item = wish("1", name = "Sony WH-1000XM5 бездротові навушники")

        assertTrue(matchesSearch(item, "навушники sony"))
        assertTrue(matchesSearch(item, "  sony   навушники  "))
        assertFalse(matchesSearch(item, "sony клавіатура"))
    }

    @Test
    fun `an empty search matches everything`() {
        val item = wish("1", name = "Будь-що")

        assertTrue(matchesSearch(item, ""))
        assertTrue(matchesSearch(item, "   "))
    }

    @Test
    fun `an apostrophe typed either way finds the thing`() {
        val item = wish("1", name = "П’ятиденний абонемент")

        assertTrue(matchesSearch(item, "п'ятиденний"))
        assertTrue(matchesSearch(item, "П’ятиденний"))
    }

    @Test
    fun `search does not fold letters that differ`() {
        val item = wish("1", name = "Мій стіл")

        assertFalse(matchesSearch(item, "мий"))
    }

    // ------------------------------------------------------------------ filtering

    @Test
    fun `the chip and the search box narrow together`() {
        val items = listOf(
            wish("1", name = "Ноутбук Asus", category = "Техніка"),
            wish("2", name = "Ноутбук Lenovo", category = "техніка"),
            wish("3", name = "Куртка", category = "Одяг")
        )

        assertEquals(listOf("1", "2", "3"), filterWishes(items, null, "").map { it.id })
        // The chip carries one spelling and the wishes another; both still answer.
        assertEquals(listOf("1", "2"), filterWishes(items, "Техніка", "").map { it.id })
        assertEquals(listOf("2"), filterWishes(items, "Техніка", "lenovo").map { it.id })
        assertTrue(filterWishes(items, "Одяг", "ноутбук").isEmpty())
    }

    @Test
    fun `filtering keeps the order it was given`() {
        val items = listOf(
            wish("3", category = "Техніка"),
            wish("1", category = "Техніка"),
            wish("2", category = "Техніка")
        )

        assertEquals(listOf("3", "1", "2"), filterWishes(items, "Техніка", "").map { it.id })
    }

    // ------------------------------------------------------------------- totals

    @Test
    fun `a category totals what its wishes are aiming at`() {
        val items = listOf(
            // A target price is what this one is asking for, not its 30 000 sticker.
            wish("1", category = "Техніка", price = 30_000.0, target = 25_000.0),
            wish("2", category = "техніка", price = 5_000.0),
            wish("3", category = "Одяг", price = 2_000.0)
        )

        val totals = categoryTotals(items)

        assertEquals(listOf("Одяг", "Техніка"), totals.map { it.name })
        assertEquals(2_000.0, totals.first { it.name == "Одяг" }.total, 0.01)
        assertEquals(1, totals.first { it.name == "Одяг" }.count)
        assertEquals(30_000.0, totals.first { it.name == "Техніка" }.total, 0.01)
        assertEquals(2, totals.first { it.name == "Техніка" }.count)
    }

    @Test
    fun `a held wish still counts towards its category`() {
        val items = listOf(
            wish("1", category = "Техніка", price = 5_000.0).copy(holdUntil = 30_000L),
            wish("2", category = "Техніка", price = 5_000.0)
        )

        assertEquals(10_000.0, categoryTotals(items).single().total, 0.01)
        assertEquals(10_000.0, allCategoriesTotal(items), 0.01)
    }

    @Test
    fun `the totals of every category add up to the whole list`() {
        val items = listOf(
            wish("1", category = "Техніка", price = 5_000.0),
            wish("2", category = "Одяг", price = 2_500.0),
            wish("3", category = "Інше", price = 100.0)
        )

        assertEquals(
            allCategoriesTotal(items),
            categoryTotals(items).sumOf { it.total },
            0.01
        )
    }

    @Test
    fun `one category is not a choice worth a row`() {
        val single = listOf(wish("1", category = "Техніка"), wish("2", category = "техніка"))
        val two = single + wish("3", category = "Одяг")

        assertFalse(showsCategoryRow(emptyList()))
        assertFalse(showsCategoryRow(single))
        assertTrue(showsCategoryRow(two))
    }

    // -------------------------------------------------------------- empty state

    @Test
    fun `an empty grid says which narrowing emptied it`() {
        assertEquals(
            "Нічого не знайшлося за запитом «ноутбук»",
            browseEmptyNote("  ноутбук  ", "Техніка")
        )
        assertEquals("У категорії «Техніка» поки порожньо", browseEmptyNote("", "Техніка"))
        assertEquals("Нічого не знайшлося", browseEmptyNote("", null))
    }

    // ------------------------------------------------------------------ sorting

    @Test
    fun `by name orders the Ukrainian alphabet properly`() {
        val items = listOf(
            wish("1", name = "Ялинка"),
            wish("2", name = "Ірис"),
            wish("3", name = "Абрикос"),
            wish("4", name = "Ива")
        )

        // и before і before ї before я is the alphabet, which the code points are
        // not: Cyrillic і sits at U+0456, far above every letter here.
        assertEquals(
            listOf("Абрикос", "Ива", "Ірис", "Ялинка"),
            sortWishes(items, WishSort.NAME).map { it.name }
        )
    }

    @Test
    fun `by name ignores case`() {
        val items = listOf(wish("1", name = "яблуко"), wish("2", name = "Абрикос"))

        assertEquals(
            listOf("Абрикос", "яблуко"),
            sortWishes(items, WishSort.NAME).map { it.name }
        )
    }

    @Test
    fun `newest first puts undated wishes at the bottom`() {
        val items = listOf(
            wish("old", addedDay = 20_000L),
            // Nothing dates this one: no addedDay, and no dated price behind it.
            wish("unknown", addedDay = 0L).copy(history = emptyList()),
            wish("new", addedDay = 20_500L)
        )

        assertEquals(
            listOf("new", "old", "unknown"),
            sortWishes(items, WishSort.NEWEST).map { it.id }
        )
    }

    @Test
    fun `an unrecognised stored sort falls back rather than throwing`() {
        assertEquals(WishSort.NAME, wishSortFrom("NAME"))
        assertEquals(WishSort.NEWEST, wishSortFrom("NEWEST"))
        assertEquals(WishSort.ADDED, wishSortFrom("BY_COLOUR"))
    }

    @Test
    fun `no ordering ever drops or duplicates a wish`() {
        val items = listOf(
            wish("1", name = "Ялинка", category = "Техніка", price = 500.0, addedDay = 20_001L),
            wish("2", name = "Абрикос", category = "Одяг", price = 12_000.0, target = 9_000.0),
            wish("3", name = "Ірис", price = 700.0, addedDay = 20_900L)
        )

        WishSort.entries.forEach { sort ->
            assertEquals(
                "$sort lost or duplicated a wish",
                items.map { it.id }.sorted(),
                sortWishes(items, sort).map { it.id }.sorted()
            )
        }
    }
}

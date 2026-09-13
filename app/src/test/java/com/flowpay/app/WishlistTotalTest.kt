package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the figure the wishlist never used to show: what it is a total of, what
 * it leaves out and says so, and that it describes the screen rather than the
 * whole list once something is filtered.
 */
class WishlistTotalTest {

    private fun wish(
        id: String,
        name: String = "Товар $id",
        category: String = "Інше",
        price: Double = 1000.0,
        target: Double = 0.0,
        freshness: Freshness = Freshness.OK
    ) = Wish(
        id, name, "https://shop/$id", "", price, target, category,
        listOf(PricePoint(price, 20_000L)),
        checkedDay = 20_100L,
        freshness = freshness
    )

    // ------------------------------------------------------------- what it sums

    @Test
    fun `the headline is what the things cost, not what the targets ask`() {
        // The chips sum wishGoal, which is the target where one is set. A
        // headline may not mix a hoped-for price into a real one: the sum would
        // be neither figure and there would be no true sentence to label it with.
        val items = listOf(
            wish("a", price = 1000.0, target = 700.0),
            wish("b", price = 2000.0)
        )
        assertEquals(3000.0, wishlistTotal(items).total, 0.001)
        // And it is deliberately not the same number as the «Усі» chip.
        assertEquals(2700.0, allCategoriesTotal(items), 0.001)
    }

    @Test
    fun `an empty list totals nothing and says there is nothing to count`() {
        val sum = wishlistTotal(emptyList())
        assertEquals(0.0, sum.total, 0.001)
        assertEquals(0, sum.counted)
        assertEquals(0, sum.unpriced)
        assertEquals("Рахувати ще нема чого", wishlistTotalNote(sum))
    }

    // ------------------------------------------------- what it leaves out, aloud

    @Test
    fun `a wish with no readable price adds nothing and is named`() {
        val items = listOf(
            wish("a", price = 1000.0),
            wish("b", price = 0.0, freshness = Freshness.UNREADABLE)
        )
        val sum = wishlistTotal(items)
        assertEquals(1000.0, sum.total, 0.001)
        assertEquals(1, sum.counted)
        assertEquals(1, sum.unpriced)
        // The sum understates by exactly one thing, and the line under it says so.
        assertTrue(wishlistTotalNote(sum).contains("не враховано"))
        assertTrue(wishlistTotalNote(sum).contains("1 позиція"))
    }

    @Test
    fun `a list where nothing could be priced says that rather than showing nought`() {
        val items = listOf(
            wish("a", price = 0.0, freshness = Freshness.UNREADABLE),
            wish("b", price = 0.0, freshness = Freshness.GONE)
        )
        val sum = wishlistTotal(items)
        assertEquals(0.0, sum.total, 0.001)
        assertEquals("Жодної ціни прочитати не вдалося: 2 позиції", wishlistTotalNote(sum))
    }

    @Test
    fun `a stale price stays in the sum and the doubt is stated beside it`() {
        val items = listOf(
            wish("a", price = 1000.0),
            wish("b", price = 2000.0, freshness = Freshness.GONE)
        )
        val sum = wishlistTotal(items)
        assertEquals(3000.0, sum.total, 0.001)
        assertEquals(2, sum.counted)
        assertEquals(1, sum.doubtful)
        assertTrue(wishlistTotalNote(sum).contains("за останньою відомою ціною: 1"))
    }

    @Test
    fun `going out of stock does not make the total drop`() {
        // Reading stock from the page is about to make OUT_OF_STOCK common. A
        // total that fell every time a variant went away would lurch about for
        // reasons that have nothing to do with what is on the list.
        val before = listOf(wish("a", price = 1000.0), wish("b", price = 2000.0))
        val after = listOf(
            wish("a", price = 1000.0),
            wish("b", price = 2000.0, freshness = Freshness.OUT_OF_STOCK)
        )
        assertEquals(wishlistTotal(before).total, wishlistTotal(after).total, 0.001)
        assertEquals(0, wishlistTotal(before).doubtful)
        assertEquals(1, wishlistTotal(after).doubtful)
    }

    @Test
    fun `a hand-typed price counts and raises no doubt`() {
        val items = listOf(wish("a", price = 1500.0, freshness = Freshness.MANUAL))
        val sum = wishlistTotal(items)
        assertEquals(1500.0, sum.total, 0.001)
        assertEquals(1, sum.counted)
        assertEquals(0, sum.doubtful)
        assertEquals(0, sum.unpriced)
        assertEquals("1 позиція", wishlistTotalNote(sum))
    }

    @Test
    fun `both kinds of missing are reported in one line`() {
        val items = listOf(
            wish("a", price = 1000.0),
            wish("b", price = 2000.0, freshness = Freshness.OUT_OF_STOCK),
            wish("c", price = 0.0, freshness = Freshness.UNREADABLE)
        )
        assertEquals(
            "2 позиції · з них за останньою відомою ціною: 1 · не враховано: 1 позиція",
            wishlistTotalNote(wishlistTotal(items))
        )
    }

    @Test
    fun `an untroubled list says only how many things it covers`() {
        val items = listOf(wish("a"), wish("b"), wish("c"))
        assertEquals("3 позиції", wishlistTotalNote(wishlistTotal(items)))
    }

    // ----------------------------------------------------- it follows the screen

    @Test
    fun `filtering to a category narrows the sum`() {
        val items = listOf(
            wish("a", category = "Техніка", price = 1000.0),
            wish("b", category = "Техніка", price = 2000.0),
            wish("c", category = "Одяг", price = 500.0)
        )
        val all = wishlistTotal(filterWishes(items, null, ""))
        val tech = wishlistTotal(filterWishes(items, "Техніка", ""))
        assertEquals(3500.0, all.total, 0.001)
        assertEquals(3000.0, tech.total, 0.001)
        assertEquals(2, tech.counted)
    }

    @Test
    fun `searching narrows the sum to what was found`() {
        val items = listOf(
            wish("a", name = "Sony WH-1000XM5 навушники", price = 12_000.0),
            wish("b", name = "Крісло офісне", price = 8000.0)
        )
        val found = wishlistTotal(filterWishes(items, null, "навушники"))
        assertEquals(12_000.0, found.total, 0.001)
        assertEquals(1, found.counted)
    }

    // ----------------------------------------------------------------- the label

    @Test
    fun `the label says what the figure is and what it covers`() {
        assertEquals("Список зараз коштує", wishlistTotalLabel(null, ""))
        assertEquals("«Техніка» зараз коштує", wishlistTotalLabel("Техніка", ""))
        assertEquals("Знайдене зараз коштує", wishlistTotalLabel(null, "навушники"))
    }

    @Test
    fun `a search names itself even while a category is chosen`() {
        // Both narrow the grid; the search is the one just typed, so it is the
        // one the line answers to.
        assertEquals("Знайдене зараз коштує", wishlistTotalLabel("Техніка", "sony"))
    }

    @Test
    fun `a category label is tidied the way its chip is`() {
        assertEquals("«Дім та сад» зараз коштує", wishlistTotalLabel("  Дім   та   сад ", ""))
    }

    @Test
    fun `the label never reads as a savings goal`() {
        // The whole reason this string is not a bare «Разом»: the same figure
        // could be read as the targets added up, or as what is left to save.
        listOf(
            wishlistTotalLabel(null, ""),
            wishlistTotalLabel("Техніка", ""),
            wishlistTotalLabel(null, "sony")
        ).forEach { assertTrue(it.contains("зараз коштує")) }
    }
}

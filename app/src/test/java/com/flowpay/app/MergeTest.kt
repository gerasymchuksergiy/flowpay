package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Slow work landing on a list that kept changing. See Merge.kt.
 */
class MergeTest {

    private data class Row(val id: String, val value: Int)

    private val a = Row("a", 1)
    private val b = Row("b", 1)
    private val c = Row("c", 1)

    @Test
    fun `a reading lands on an item nobody touched`() {
        val merged = mergeById(listOf(a, b), listOf(a, b), listOf(a.copy(value = 2), b)) { it.id }
        assertEquals(listOf(Row("a", 2), b), merged)
    }

    @Test
    fun `an item deleted while the work ran stays deleted`() {
        // The bug: matching by position brought the deleted wish back, and dropped
        // the last one off the end.
        val merged = mergeById(
            current = listOf(a, c),
            before = listOf(a, b, c),
            after = listOf(a.copy(value = 2), b.copy(value = 2), c.copy(value = 2))
        ) { it.id }
        assertEquals(listOf(Row("a", 2), Row("c", 2)), merged)
    }

    @Test
    fun `an item added while the work ran is kept`() {
        val added = Row("d", 9)
        val merged = mergeById(listOf(a, added), listOf(a), listOf(a.copy(value = 2))) { it.id }
        assertEquals(listOf(Row("a", 2), added), merged)
    }

    @Test
    fun `an edit made while the work ran wins over the reading`() {
        val edited = a.copy(value = 7)
        val merged = mergeById(listOf(edited), listOf(a), listOf(a.copy(value = 2))) { it.id }
        assertEquals(listOf(edited), merged)
    }

    @Test
    fun `repeated ids are dropped, first kept`() {
        val twice = listOf(a, b, a.copy(value = 5))
        assertEquals(listOf(a, b), withoutRepeatedIds(twice) { it.id })
    }

    @Test
    fun `a bought wish and its parcel never share a bin id`() {
        val wish = Wish(id = "w1", name = "Ніж", url = "https://shop.example/knife", image = "", price = 100.0, history = emptyList())
        val order = Order(id = "w1", name = "Ніж", url = wish.url, status = ORDERED)
        assertNotEquals(binEntryOf(wish, 10L).id, binEntryOf(order, 10L).id)
        // And restoring still finds the item's own id inside the payload.
        assertEquals("w1", orderOf(JSONObject(binEntryOf(order, 10L).payload)).id)
    }

    @Test
    fun `two deletions of the same item get two bin ids`() {
        val wish = Wish(id = "w1", name = "Ніж", url = "https://shop.example/knife", image = "", price = 100.0, history = emptyList())
        assertTrue(binEntryId(BIN_WISH, wish.id, 1L) != binEntryId(BIN_WISH, wish.id, 2L))
    }
}

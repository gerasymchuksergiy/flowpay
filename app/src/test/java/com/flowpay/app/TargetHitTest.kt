package com.flowpay.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The one fact three separate parts of the app act on at once.
 *
 * The status pill announces a reached target, the card changes shape for it, and
 * the phone marks the moment it happens under your hand. They read one predicate so
 * they cannot disagree, and what is pinned here is the two ways that predicate is
 * easy to get wrong: a target nobody set, and a reading too old to stand behind.
 */
class TargetHitTest {

    private val today = LocalDate.of(2026, 3, 10)
    private val day = today.toEpochDay()

    private fun wish(
        price: Double,
        target: Double,
        checked: LocalDate? = today
    ) = Wish(
        id = "w1",
        name = "Sony WH-1000XM5",
        url = "https://shop.example/x",
        image = "",
        price = price,
        targetPrice = target,
        history = emptyList(),
        checkedDay = checked?.toEpochDay() ?: 0L
    )

    @Test
    fun `a price at or under the target counts`() {
        assertTrue(targetHit(wish(price = 9000.0, target = 9500.0), day))
        // The target is a price you would pay, so landing exactly on it is a hit.
        assertTrue(targetHit(wish(price = 9500.0, target = 9500.0), day))
    }

    @Test
    fun `a price still above the target does not`() {
        assertFalse(targetHit(wish(price = 9600.0, target = 9500.0), day))
    }

    @Test
    fun `no target means nothing to reach`() {
        // Most wishes have no target at all. Zero is "unset", not "free".
        assertFalse(targetHit(wish(price = 9000.0, target = 0.0), day))
    }

    @Test
    fun `a wish with no price cannot have reached one`() {
        // A page that has never been read leaves the price at zero, which is below
        // every target ever set and means nothing at all.
        assertFalse(targetHit(wish(price = 0.0, target = 9500.0), day))
    }

    @Test
    fun `yesterday's reading still speaks for today`() {
        assertTrue(targetHit(wish(price = 9000.0, target = 9500.0, checked = today.minusDays(1)), day))
    }

    @Test
    fun `a reading older than that does not`() {
        // This is the clause that stops a dip from months ago holding the state
        // for ever: the price is still under the target, but nothing has confirmed
        // it since, so the app stops claiming it is true now.
        assertFalse(targetHit(wish(price = 9000.0, target = 9500.0, checked = today.minusDays(2)), day))
        assertFalse(targetHit(wish(price = 9000.0, target = 9500.0, checked = null), day))
    }

    @Test
    fun `the pill and the predicate agree`() {
        // The status pill was the original home of this rule. It now reads the
        // shared predicate, and this pins the two together so a later change to
        // one cannot quietly leave the other announcing something else.
        val hit = wish(price = 9000.0, target = 9500.0)
        val note = statusNote(emptyList(), emptyList(), listOf(hit), today, 41.0)
        assertTrue(targetHit(hit, day))
        assertTrue(note?.kind == StatusKind.TARGET)

        val stale = wish(price = 9000.0, target = 9500.0, checked = today.minusDays(2))
        assertFalse(targetHit(stale, day))
        assertTrue(statusNote(emptyList(), emptyList(), listOf(stale), today, 41.0) == null)
    }
}

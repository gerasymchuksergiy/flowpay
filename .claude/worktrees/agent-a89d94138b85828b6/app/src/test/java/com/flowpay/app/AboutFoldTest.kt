package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers how much of «Про товар» shows at once: where a specification table stops
 * being readable at a glance and earns a fold, and what the clamp on the shop's
 * own prose offers instead.
 */
class AboutFoldTest {

    // ------------------------------------------------------------- the spec fold

    @Test
    fun `a short specification table is left standing`() {
        assertFalse(foldsSpecs(0))
        assertFalse(foldsSpecs(1))
        assertFalse(foldsSpecs(2))
        assertFalse(foldsSpecs(5))
    }

    @Test
    fun `a long specification table goes behind a heading`() {
        assertTrue(foldsSpecs(6))
        assertTrue(foldsSpecs(14))
        assertTrue(foldsSpecs(40))
    }

    @Test
    fun `the fold turns on at one row past the limit and nowhere else`() {
        // The threshold is the whole decision, so it is pinned rather than
        // left to be inferred from two examples either side of it.
        assertFalse(foldsSpecs(SPEC_FOLD_LIMIT))
        assertTrue(foldsSpecs(SPEC_FOLD_LIMIT + 1))
    }

    @Test
    fun `the shut heading says how many rows it is holding`() {
        assertEquals("14", specsCountLabel(14))
        assertEquals("6", specsCountLabel(6))
        // Not specsLabel: the title already said what they are, so
        // «Характеристики · 14 характеристик» would say it twice.
        assertTrue(specsCountLabel(14) != specsLabel(14))
    }

    @Test
    fun `the count carries no thousands separator and no locale`() {
        // A count is not money. Nothing here may pick up a grouping mark, and
        // nothing here may go through a formatter that has an opinion about one.
        assertEquals("1200", specsCountLabel(1200))
    }

    // ------------------------------------------------------- the description clamp

    @Test
    fun `the clamp offers the way out and the way back`() {
        assertEquals("більше", moreLabel(false))
        assertEquals("менше", moreLabel(true))
    }

    @Test
    fun `the clamp shows enough to judge by`() {
        // A clamp exists so the shut state answers "what is this thing" on its
        // own. One line does not, and the whole 600-character paragraph
        // Parsing.kt stores is the wall this is fixing.
        assertTrue(ABOUT_CLAMP_LINES >= 3)
        assertTrue(ABOUT_CLAMP_LINES <= 6)
    }
}

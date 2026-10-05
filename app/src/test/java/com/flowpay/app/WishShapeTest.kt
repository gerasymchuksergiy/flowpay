package com.flowpay.app

import androidx.graphics.shapes.Morph
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The card outline, measured rather than looked at.
 *
 * A wrong shape compiles perfectly and ships. The two failures that would actually
 * reach the phone are an outline that does not fill its card — because the polygon
 * was built in the library's unit square and then stretched — and a resting shape
 * that is not the card this app already draws, which would change every wish on the
 * grid rather than the one that reached its price. Both are geometry, and geometry
 * can be checked here without a device.
 */
class WishShapeTest {

    // A wishlist tile on this phone: two columns, taller than it is wide.
    private val width = 480f
    private val height = 840f

    // 18dp and 34dp at 3x, which is what this phone would hand the shape.
    private val watchingRadius = 54f
    private val reachedRadius = 102f

    private fun morph(w: Float = width, h: Float = height) =
        wishMorph(w, h, watchingRadius, reachedRadius)

    /** Every anchor and control point of the outline at this point in the morph. */
    private fun points(morph: Morph, progress: Float): List<Pair<Float, Float>> {
        val cubics = morph.asCubics(progress)
        assertTrue("the morph produced no outline at all", cubics.isNotEmpty())
        return cubics.flatMap {
            listOf(
                it.anchor0X to it.anchor0Y,
                it.control0X to it.control0Y,
                it.control1X to it.control1Y,
                it.anchor1X to it.anchor1Y
            )
        }
    }

    private fun assertFillsBox(morph: Morph, progress: Float, w: Float, h: Float) {
        val points = points(morph, progress)
        // Controls are included, so a curve that bulged past the card's own edge
        // would be caught too, not only a misplaced anchor.
        assertEquals("left edge at $progress", 0f, points.minOf { it.first }, 0.5f)
        assertEquals("top edge at $progress", 0f, points.minOf { it.second }, 0.5f)
        assertEquals("right edge at $progress", w, points.maxOf { it.first }, 0.5f)
        assertEquals("bottom edge at $progress", h, points.maxOf { it.second }, 0.5f)
    }

    @Test
    fun `the watching shape fills the card exactly`() {
        // Top-left at the origin, bottom-right at the card's own size: the outline
        // is in the container's coordinate space, not in a unit square waiting to be
        // scaled. An offset here would clip the card against its own corner.
        assertFillsBox(morph(), 0f, width, height)
    }

    @Test
    fun `the reached shape fills the same card`() {
        // The card opens its corners; it does not shrink, grow or drift. If it did,
        // a wish reaching its price would visibly change size in the grid, which is
        // layout news rather than price news.
        assertFillsBox(morph(), 1f, width, height)
    }

    @Test
    fun `every step between stays inside the card`() {
        val morph = morph()
        listOf(0.25f, 0.5f, 0.75f).forEach { assertFillsBox(morph, it, width, height) }
    }

    @Test
    fun `the reached card is visibly more open than the watching one`() {
        val morph = morph()
        // Where the outline finally settles onto the straight part of the top edge.
        // A bigger radius leaves the corner later, so this number is how open the
        // corner reads — and the whole point of the state channel is that the two
        // are far enough apart to be told apart at a glance across a grid.
        val atRest = topEdgeStart(morph, 0f)
        val reached = topEdgeStart(morph, 1f)
        assertEquals("the resting corner is the app's own 18dp card", 54f, atRest, 6f)
        assertTrue(
            "the reached corner must be far more open: $reached vs $atRest",
            reached > atRest * 2f
        )
    }

    @Test
    fun `a corner too big for the card is scaled down rather than thrown`() {
        // A narrow tile cannot fit a 34dp radius twice across its top edge. The
        // library splits the room it has instead of failing, and this pins that so a
        // small screen does not crash on the wishlist.
        assertFillsBox(morph(w = 60f, h = 90f), 1f, 60f, 90f)
    }

    /**
     * How far along the top edge the corner runs before the outline lies flat on
     * it: the smallest x among the anchors that have reached y = 0.
     *
     * Anchors only. A control point of the corner's own curve also sits on the top
     * edge — that is what makes the curve leave the edge smoothly — so counting
     * controls here would measure the curve's tangent rather than where the corner
     * ends.
     */
    private fun topEdgeStart(morph: Morph, progress: Float): Float =
        morph.asCubics(progress)
            .flatMap { listOf(it.anchor0X to it.anchor0Y, it.anchor1X to it.anchor1Y) }
            .filter { it.second < 0.5f }
            .minOf { it.first }
}

package com.flowpay.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.rectangle
import androidx.graphics.shapes.toPath
import android.graphics.Path as AndroidPath

/**
 * The one place in this app where shape carries a state rather than a style.
 *
 * A wish is either being watched or has reached the price you named for it, and
 * the second is the only thing the wishlist ever has to report. The palette cannot
 * carry it: the whole strength of lime on black is that exactly one thing is lime
 * at a time, and a fourth coloured badge would spend that. So the card itself
 * changes outline. It is legible from across the grid, out of the corner of an eye,
 * without reading a word or spending the accent.
 *
 * The direction is the app's existing vocabulary, not a new one. A verdict chip
 * already says *rounder is better* — a good price wears a capsule, a bad one wears
 * a leaf with two corners cut across. A card that has reached its price opens up
 * the same way, and deliberately not into the leaf, which in this app already means
 * the opposite. What it adds over the chip's four sprung corners is real corner
 * smoothing: the straight edge eases into the curve instead of meeting it, which is
 * a quality of outline `RoundedCornerShape` cannot express at all.
 *
 * ## What this is not
 * It is not applied on tap, not applied to every state, and not applied anywhere
 * else. Shape morphing is a good state channel and a terrible decoration: put it on
 * every card and it becomes the visual equivalent of the buzzy haptics — motion
 * that marks nothing and therefore hides the one thing that would have mattered.
 */

/** Today's card, unchanged: the soft squircle every surface in the app wears. */
internal val WatchingRadius: Dp = Radius.card

/**
 * Reached. Nearly twice the radius, and the corners smoothed so the sides ease
 * into them — the shape a card takes when the waiting is over.
 */
internal val ReachedRadius: Dp = 34.dp
internal const val ReachedSmoothing = 1f

/**
 * The polygons and the morph between them, built once and kept.
 *
 * This is the whole cost model of `graphics-shapes`, and the reason its API takes
 * an optional `Path` to write into and hands back a reusable `MutableCubic`:
 * building a [RoundedPolygon] means solving every corner's cubics, and building a
 * [Morph] means matching the two outlines' features and pairing them up. Do that
 * per frame — which is what you get by constructing either one inside a
 * composable's body without remembering it — and the result is the allocation
 * storm behind every "shape morphing is slow" claim. Do it once and the per-frame
 * work is one float and a path rewind.
 *
 * The polygons are built in pixels at the container's own width and height rather
 * than in the library's default unit square. Scaling a unit square to a card that
 * is twice as tall as it is wide would stretch its corners into ellipses and make
 * the resting shape subtly wrong — and the resting shape has to be exactly the card
 * this app already draws, or every wish on the grid changes the day this ships.
 *
 * Kept per card, so two cards drawn in the same frame never share the scratch path.
 */
class WishOutline internal constructor() {
    private val scratch = AndroidPath()

    /**
     * Wraps [scratch] rather than copying it — `asComposePath` is a wrapper, so
     * refilling the platform path refills this one too, and neither is reallocated.
     */
    private val wrapper: Path = scratch.asComposePath()

    private var morph: Morph? = null
    private var builtFor: Size = Size.Unspecified
    private var builtAtDensity: Float = 0f

    internal fun path(size: Size, density: Density, progress: Float): Path {
        // toPath rewinds the path it is given before refilling it, so the wrapper
        // above stays correct without being cleared here.
        shapeFor(size, density).toPath(progress, scratch)
        return wrapper
    }

    private fun shapeFor(size: Size, density: Density): Morph {
        val held = morph
        if (held != null && size == builtFor && density.density == builtAtDensity) return held
        val built = wishMorph(
            width = size.width,
            height = size.height,
            watchingRadius = with(density) { WatchingRadius.toPx() },
            reachedRadius = with(density) { ReachedRadius.toPx() }
        )
        morph = built
        builtFor = size
        builtAtDensity = density.density
        return built
    }
}

/**
 * The two outlines and the morph between them, in pixels, for a container of this
 * size.
 *
 * Separate from the composable and from Android's `Path` so the geometry can be
 * measured on its own: both ends of the morph have to fill the card exactly, and a
 * shape that is offset or scaled wrong is invisible to a compile and obvious on a
 * phone.
 */
internal fun wishMorph(
    width: Float,
    height: Float,
    watchingRadius: Float,
    reachedRadius: Float
): Morph {
    val watching = rectangleWith(width, height, watchingRadius, smoothing = 0f)
    val reached = rectangleWith(width, height, reachedRadius, ReachedSmoothing)
    // Both rectangles carry four vertices in the same order, so the morph pairs
    // each corner with the corner it becomes rather than guessing a mapping.
    return Morph(watching, reached)
}

private fun rectangleWith(
    width: Float,
    height: Float,
    radius: Float,
    smoothing: Float
): RoundedPolygon =
    RoundedPolygon.rectangle(
        width = width,
        height = height,
        rounding = CornerRounding(radius, smoothing),
        // Centred on the container rather than on the origin, so the path lands in
        // the coordinate space the outline is measured in.
        centerX = width / 2,
        centerY = height / 2
    )

/** A [Shape] that is the morph frozen at one progress. Tiny; the work is all above. */
private class WishCardShape(
    private val outline: WishOutline,
    private val progress: Float
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density
    ): Outline = Outline.Generic(outline.path(size, density, progress))
}

/**
 * The outline a wish card wears, given whether its price has been reached.
 *
 * Every card goes through here, including the ones that will never move: sharing
 * one shape function is what guarantees a watching card is drawn by exactly the
 * same geometry as the one beside it, instead of a rounded rectangle that merely
 * looks like the polygon at rest.
 *
 * The progress is sprung rather than tweened, so a card whose price lands while the
 * grid is on screen is seen to open rather than found already open. Under the
 * phone's reduce-animations setting [Motion] snaps it, and that is the right answer
 * rather than a shortened version of the same movement: the shape is the state, so
 * snapping still says the true thing — instantly, with nothing to watch. And on
 * first composition `animateFloatAsState` starts at its target, so opening the app
 * on a wish that was already reached shows the reached shape rather than animating
 * into it, which would be motion reporting nothing but that a screen had loaded.
 */
@Composable
fun wishCardShape(reached: Boolean): Shape {
    val outline = remember { WishOutline() }
    val progress by animateFloatAsState(
        if (reached) 1f else 0f,
        Motion.spatial(),
        label = "wish target reached"
    )
    return remember(outline, progress) { WishCardShape(outline, progress) }
}

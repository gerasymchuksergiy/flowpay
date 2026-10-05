package com.flowpay.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import coil.request.ImageRequest
import kotlinx.coroutines.launch

/**
 * The motion of the October redesign, in one place.
 *
 * The owner asked for the life he saw in AI-made motion-graphics videos. Those are
 * web tools; the native equivalent is a handful of small, physical movements that
 * answer something the person did or something that changed — never motion for its
 * own sake on an everyday screen. Everything here runs on the [Motion] springs, so
 * a phone told to stop animating gets every end state at once.
 */

// ------------------------------------------------------------ rolling figures

/**
 * A finished figure whose digits roll when it changes, like a mechanical counter.
 *
 * Each character from the right is its own slot: a digit that changed slides out
 * and the new one slides in, up when the number grew and down when it fell;
 * spaces, commas and the currency never move. It does not count up from nought
 * when a screen opens — `AnimatedContent` does not animate its first composition —
 * because on money that reads as a magic trick. The formatting is still done by
 * [money] and friends (§7.1): this only animates the string it is given.
 *
 * TalkBack reads one number, not a row of characters.
 */
@Composable
fun RollingText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontWeight: FontWeight? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    style: TextStyle = Tabular
) {
    val previous = remember { mutableValue(text) }
    val direction = rollDirection(previous.value, text)
    previous.value = text
    // Read here, in composition: the transition spec below is not composable, and
    // these are what make the roll snap under reduced motion.
    val move = Motion.spatial<IntOffset>()
    val fade = Motion.effects<Float>()
    // When the tab is opening, the figure's own digits roll up into place one
    // after another, left to right — the real number arriving, not a count from
    // nought (which on money reads as a magic trick).
    val entrance = LocalEntrance.current
    val introduce = remember { entrance.plays() }
    Row(modifier.clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.Bottom) {
        // Indexed from the right, so "999" → "1 000" shifts the new digit in at
        // the front instead of every digit changing place.
        val padded = text.reversed()
        for (index in padded.indices.reversed()) {
            val char = padded[index]
            AnimatedContent(
                modifier = if (introduce) Modifier.rollIn(padded.lastIndex - index) else Modifier,
                targetState = char,
                transitionSpec = {
                    if (!targetState.isDigit() || !initialState.isDigit()) {
                        fadeIn(fade) togetherWith fadeOut(fade)
                    } else {
                        (slideInVertically(move) { direction * it } + fadeIn(fade))
                            .togetherWith(slideOutVertically(move) { -direction * it } + fadeOut(fade))
                    }.using(SizeTransform(clip = false))
                },
                label = "digit"
            ) { shown ->
                Text(
                    shown.toString(),
                    color = color,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    letterSpacing = letterSpacing,
                    style = style
                )
            }
        }
    }
}

/** One character of a figure rolling up into its slot, [slot] places from the left. */
private fun Modifier.rollIn(slot: Int): Modifier = composed {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(120L + slot * 38L)
        progress.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f))
    }
    graphicsLayer {
        alpha = progress.value.coerceIn(0f, 1f)
        translationY = (1f - progress.value) * size.height
    }
}

/** A plain holder that survives recomposition without causing one. */
private class Holder<T>(var value: T)

private fun <T> mutableValue(value: T) = Holder(value)

/**
 * Which way the digits roll: +1 (in from below, the number grew) or −1.
 *
 * Read from the digits alone, so "1 117 ₴" against "999 ₴" is a rise whatever the
 * separators did. Anything without digits rolls upward.
 */
fun rollDirection(before: String, after: String): Int {
    val old = before.filter { it.isDigit() }.toBigIntegerOrNull()
    val new = after.filter { it.isDigit() }.toBigIntegerOrNull()
    if (old == null || new == null) return 1
    return if (new >= old) 1 else -1
}

// ------------------------------------------------------------ the paid tick

/**
 * The paid mark: a ring that fills, and a tick that draws itself.
 *
 * Replaces two icons swapped in place. The ring fills on the fast spatial spring
 * with its small overshoot — the one bit of bounce this control gets — and the
 * tick is drawn along its own path on the effects spring, about a fifth of a
 * second, so marking a bill paid looks like the act it is. Unticking runs it back.
 */
@Composable
fun PaidCheck(
    done: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    /** On a pastel tile the open ring and the fill are ink, and the tick is the tile. */
    ring: Color = TextDisabled,
    fill: Color = Accent,
    tick: Color = AccentInk
) {
    val filled by animateFloatAsState(if (done) 1f else 0f, Motion.fastSpatial(), label = "paid fill")
    val drawnTick by animateFloatAsState(if (done) 1f else 0f, Motion.effects(), label = "paid tick")
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() * 0.09f
        val radius = this.size.minDimension / 2 - stroke / 2
        // The ring: grey when open, lime as it fills.
        drawCircle(
            color = if (filled > 0.02f) fill else ring,
            radius = radius,
            style = Stroke(width = stroke)
        )
        if (filled > 0f) {
            drawCircle(color = fill, radius = radius * filled.coerceIn(0f, 1.15f))
        }
        if (drawnTick > 0f) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.28f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.67f)
                lineTo(w * 0.73f, h * 0.36f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val drawn = Path()
            measure.getSegment(0f, measure.length * drawnTick.coerceIn(0f, 1f), drawn, true)
            drawPath(drawn, tick, style = Stroke(width = stroke * 1.4f, cap = StrokeCap.Round))
        }
    }
}

// ------------------------------------------------------------ entrances

/*
 * The third wave (4 October 2026). The owner pointed at motion-graphics videos made
 * with code — kinetic type, staggered physics, things that draw themselves on — and
 * asked for tabs and tiles to *appear* like that. A tab is an arrival now: every
 * time one opens, its title slides up out of a mask and tightens, the tiles fall
 * into place on an underdamped spring with a tilt and a blur clearing, the figures
 * roll their digits in, bars grow, and the emoji pop in last, top to bottom.
 *
 * All of it lasts well under a second, none of it repeats while you read (see
 * [Entrance]), and a phone told to reduce motion sees none of it.
 */

/**
 * Whether what is being drawn should arrive rather than appear: true for the
 * first moments after a tab opens.
 *
 * The time window is what keeps a tile scrolled into view later — or scrolled
 * back to after the list dropped it — from flying in again in the middle of
 * reading.
 */
class Entrance(private val active: Boolean, private val openedAt: Long) {
    fun plays(): Boolean = active && android.os.SystemClock.uptimeMillis() - openedAt < ENTRANCE_WINDOW_MS
}

/** How long after a tab opens something may still arrive. */
private const val ENTRANCE_WINDOW_MS = 700L

/** The entrance of the tab being drawn. Provided once, around each tab, in FlowPayApp. */
val LocalEntrance = staticCompositionLocalOf { Entrance(false, 0L) }

/** A fresh entrance: one per opening of a tab. Inactive under reduced motion. */
@Composable
fun rememberEntrance(): Entrance {
    val reduced = LocalReducedMotion.current
    return remember { Entrance(!reduced, android.os.SystemClock.uptimeMillis()) }
}

/** The gap between one tile's arrival and the next. */
private const val STAGGER_MS = 55L

/** Rows after this one arrive together: a cascade longer than the screen is a wait. */
private const val STAGGER_CAP = 7

/**
 * A tile falling into place: up from below, tilted back, slightly small and
 * blurred, on a spring that overshoots once and settles — the "real physics" of
 * the reference videos. [order] staggers it behind the tiles above.
 *
 * The blur needs Android 12; older phones get the rest.
 */
fun Modifier.revealOnEnter(order: Int, entrance: Entrance): Modifier = composed {
    val play = remember { entrance.plays() }
    if (!play) return@composed Modifier
    val progress = remember { Animatable(0f) }
    val density = LocalDensity.current
    val rise = with(density) { 40.dp.toPx() }
    val blur = with(density) { 14.dp.toPx() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(order.coerceIn(0, STAGGER_CAP) * STAGGER_MS)
        progress.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 320f))
    }
    graphicsLayer {
        val p = progress.value
        val left = 1f - p
        alpha = p.coerceIn(0f, 1f)
        translationY = left * rise
        val scale = 0.9f + 0.1f * p
        scaleX = scale
        scaleY = scale
        rotationX = left * 16f
        cameraDistance = 14f * density.density
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f)
        renderEffect = if (android.os.Build.VERSION.SDK_INT >= 31 && left > 0.02f) {
            androidx.compose.ui.graphics.BlurEffect(blur * left, blur * left)
        } else {
            null
        }
    }
}

/** [revealOnEnter] with the entrance of the tab it is in. */
fun Modifier.revealOnEnter(order: Int): Modifier = composed {
    revealOnEnter(order, LocalEntrance.current)
}

/** When the emoji start popping, after the first tiles have begun to land. */
private const val POP_DELAY_MS = 180L

/** How much later the lowest emoji on the screen pops than the highest. */
private const val POP_SPREAD_MS = 420f

/**
 * An emoji popping in: from nothing, a third of a turn back, on a bouncy spring —
 * after the tile it sits on, and later the further down the screen it is, so the
 * emoji arrive top to bottom without anything having to number them.
 */
fun Modifier.popInOnEnter(): Modifier = composed {
    val entrance = LocalEntrance.current
    val play = remember { entrance.plays() }
    if (!play) return@composed Modifier
    val progress = remember { Animatable(0f) }
    var top by remember { androidx.compose.runtime.mutableFloatStateOf(-1f) }
    val screen = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize.height.toFloat()
    LaunchedEffect(top >= 0f) {
        if (top < 0f) return@LaunchedEffect
        val share = if (screen > 0f) (top / screen).coerceIn(0f, 1f) else 0f
        kotlinx.coroutines.delay(POP_DELAY_MS + (share * POP_SPREAD_MS).toLong())
        progress.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 360f))
    }
    onGloballyPositioned { if (top < 0f) top = it.positionInRoot().y }
        .graphicsLayer {
            val p = progress.value
            scaleX = p
            scaleY = p
            alpha = p.coerceIn(0f, 1f)
            rotationZ = (1f - p) * -40f
        }
}

/**
 * A fraction that grows from nought when the tab opens — a bar filling, a strip of
 * days drawing itself on — and follows [target] on a spring after that.
 */
@Composable
fun entranceFraction(target: Float, delayMs: Long = 150L, stiffness: Float = 140f): Float {
    val entrance = LocalEntrance.current
    val play = remember { entrance.plays() }
    val value = remember { Animatable(if (play) 0f else target) }
    val follow = Motion.spatial<Float>()
    val first = remember { mutableValue(true) }
    LaunchedEffect(target) {
        if (first.value && play) {
            first.value = false
            kotlinx.coroutines.delay(delayMs)
            value.animateTo(target, spring(dampingRatio = 0.75f, stiffness = stiffness))
        } else {
            first.value = false
            value.animateTo(target, follow)
        }
    }
    return value.value
}

/**
 * A small jump when [value] goes up: a payment ticked off, one more marked paid.
 *
 * Up to 135% and a twelfth of a turn on the fast spring, back on the slower one.
 * It answers the person's own tap and nothing else, so it never plays when the
 * value merely arrives with the screen, and never when it goes down — unticking
 * is a correction, not an event.
 */
fun Modifier.popOnRise(value: Int): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val up = Motion.fastSpatial<Float>()
    val down = Motion.spatial<Float>()
    val scale = remember { Animatable(1f) }
    val turn = remember { Animatable(0f) }
    val last = remember { mutableValue(value) }
    LaunchedEffect(value) {
        val rose = value > last.value
        last.value = value
        if (rose && !reduced) {
            kotlinx.coroutines.coroutineScope {
                launch {
                    scale.animateTo(1.35f, up)
                    scale.animateTo(1f, down)
                }
                launch {
                    turn.animateTo(-12f, up)
                    turn.animateTo(0f, down)
                }
            }
        }
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
        rotationZ = turn.value
    }
}

// ------------------------------------------------------------ touch

/**
 * Gives a little under the finger.
 *
 * Down to 97% on the fast spatial spring and back, read in the layer so a press
 * redraws rather than recomposes. No haptic: Haptics.kt spends none on ordinary
 * taps, and this is the screen answering contact, not consequence.
 */
fun Modifier.pressScale(interaction: MutableInteractionSource): Modifier = composed {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.fastSpatial(), label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * A photo request that fades in, unless the phone asked for no motion.
 *
 * Coil's crossfade runs on its own clock and ignores the system animation scale,
 * so it is switched off by hand there. A memory-cache hit never crossfades, which
 * keeps the shared photo between a card and its page exact.
 */
@Composable
fun crossfadeImage(url: String): ImageRequest {
    val reduced = LocalReducedMotion.current
    val context = LocalContext.current
    return remember(url, reduced) {
        ImageRequest.Builder(context).data(url).crossfade(if (reduced) 0 else 180).build()
    }
}

// ------------------------------------------------------------ sheets

/**
 * How many form sheets are open, so the app behind them can step back.
 *
 * Provided once in FlowPayApp; every [FormSheet] counts itself in while it is up.
 */
val LocalSheetsOpen = staticCompositionLocalOf<MutableIntState> { mutableIntStateOf(0) }

/** Counts the caller in for as long as it is composed. */
@Composable
fun SheetPresence() {
    val open = LocalSheetsOpen.current
    DisposableEffect(open) {
        open.intValue += 1
        onDispose { open.intValue -= 1 }
    }
}

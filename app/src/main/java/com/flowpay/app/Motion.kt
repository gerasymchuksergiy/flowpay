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
    Row(modifier.clearAndSetSemantics { contentDescription = text }, verticalAlignment = Alignment.Bottom) {
        // Indexed from the right, so "999" → "1 000" shifts the new digit in at
        // the front instead of every digit changing place.
        val padded = text.reversed()
        for (index in padded.indices.reversed()) {
            val char = padded[index]
            AnimatedContent(
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
fun PaidCheck(done: Boolean, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    val fill by animateFloatAsState(if (done) 1f else 0f, Motion.fastSpatial(), label = "paid fill")
    val tick by animateFloatAsState(if (done) 1f else 0f, Motion.effects(), label = "paid tick")
    Canvas(modifier.size(size)) {
        val stroke = size.toPx() * 0.09f
        val radius = this.size.minDimension / 2 - stroke / 2
        // The ring: grey when open, lime as it fills.
        drawCircle(
            color = if (fill > 0.02f) Accent else TextDisabled,
            radius = radius,
            style = Stroke(width = stroke)
        )
        if (fill > 0f) {
            drawCircle(color = Accent, radius = radius * fill.coerceIn(0f, 1.15f))
        }
        if (tick > 0f) {
            val w = this.size.width
            val h = this.size.height
            val path = Path().apply {
                moveTo(w * 0.28f, h * 0.52f)
                lineTo(w * 0.44f, h * 0.67f)
                lineTo(w * 0.73f, h * 0.36f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val drawn = Path()
            measure.getSegment(0f, measure.length * tick.coerceIn(0f, 1f), drawn, true)
            drawPath(drawn, AccentInk, style = Stroke(width = stroke * 1.4f, cap = StrokeCap.Round))
        }
    }
}

// ------------------------------------------------------------ entrances

/**
 * Rises into place the first time it is composed, a little later the further down
 * it is.
 *
 * Staggered by stiffness rather than by delay: a later block is on a softer
 * spring, so it arrives after the ones above without anything waiting on a timer —
 * which also means a phone set to reduce motion gets everything at once, because
 * the springs snap there and there is no delay left to sit through.
 */
fun Modifier.revealOnEnter(order: Int): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val progress = remember { Animatable(if (reduced) 1f else 0f) }
    val rise = with(LocalDensity.current) { 18.dp.toPx() }
    LaunchedEffect(Unit) {
        if (!reduced) {
            progress.animateTo(
                1f,
                spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = (Spring.StiffnessMediumLow / (1f + order * 0.45f)).coerceAtLeast(Spring.StiffnessVeryLow)
                )
            )
        }
    }
    graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * rise
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

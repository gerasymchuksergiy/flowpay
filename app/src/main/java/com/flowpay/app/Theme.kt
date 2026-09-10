package com.flowpay.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Every colour, distance, radius and text size the app is allowed to use.
 *
 * Before this file the screens carried twenty-three different dp values, fourteen
 * text sizes, seven corner radii and eight near identical dark greys, all chosen
 * one at a time. Nothing lined up, which is what made a finished app read as a
 * draft. Pick from the scales below instead of inventing a value.
 */

// Surfaces, darkest to lightest. Each step is a visible jump, not a nudge.
val AppBackground = Color(0xff090a08)
val SurfaceLow = Color(0xff121310)
val SurfaceBase = Color(0xff1a1b18)
val SurfaceRaised = Color(0xff23251f)
val SurfaceHigh = Color(0xff2e312a)

// Text. Three levels is enough; a fourth only ever gets confused with the third.
val TextPrimary = Color(0xfff1f3ec)
val TextSecondary = Color(0xff9b9d96)
val TextMuted = Color(0xff6b6e65)

// One accent, plus a tinted container for anything secondary, plus one alarm colour.
val Accent = Color(0xffd7ff63)
val AccentInk = Color(0xff10120d)
val AccentSoft = Color(0xff2a3318)
val Negative = Color(0xffff6b6b)

val HairLine = Color(0xff33362d)

/** Vertical and horizontal rhythm. Six steps, nothing between them. */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    /** Every screen keeps the same side margin so blocks share one left edge. */
    val screen = 20.dp
}

/** Three radii. A card and a chip should not each have their own. */
object Radius {
    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(18.dp)
    val lg = RoundedCornerShape(26.dp)
    val pill = RoundedCornerShape(50)
}

/** Text sizes, paired with the weight and line height each one is meant to carry. */
object Type {
    val displaySize = 28.sp
    val displayLine = 32.sp
    val displayWeight = FontWeight.ExtraBold

    val titleSize = 20.sp
    val titleLine = 25.sp
    val titleWeight = FontWeight.Bold

    val figureSize = 30.sp
    val figureWeight = FontWeight.Black

    val bodySize = 15.sp
    val bodyLine = 21.sp

    val labelSize = 13.sp
    val captionSize = 11.sp

    val kickerSize = 11.sp
    val kickerWeight = FontWeight.Bold
    val kickerTracking = 1.4.sp
}

/**
 * The scheme names every role the app touches. Leaving roles unset was the reason
 * the tab indicator, the tonal buttons and the operator chips came out lilac: those
 * components read secondaryContainer, and an unset secondaryContainer falls back to
 * the stock Material purple, which fought the lime everywhere it appeared.
 */
private val FlowPayColors = darkColorScheme(
    primary = Accent,
    onPrimary = AccentInk,
    primaryContainer = AccentSoft,
    onPrimaryContainer = Accent,
    inversePrimary = AccentSoft,

    secondary = Accent,
    onSecondary = AccentInk,
    secondaryContainer = AccentSoft,
    onSecondaryContainer = Accent,

    tertiary = Accent,
    onTertiary = AccentInk,
    tertiaryContainer = AccentSoft,
    onTertiaryContainer = Accent,

    background = AppBackground,
    onBackground = TextPrimary,

    surface = SurfaceBase,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceRaised,
    onSurfaceVariant = TextSecondary,
    surfaceTint = Accent,

    surfaceDim = AppBackground,
    surfaceBright = SurfaceHigh,
    surfaceContainerLowest = AppBackground,
    surfaceContainerLow = SurfaceLow,
    surfaceContainer = SurfaceBase,
    surfaceContainerHigh = SurfaceRaised,
    surfaceContainerHighest = SurfaceHigh,

    outline = HairLine,
    outlineVariant = Color(0xff26291f),

    error = Negative,
    onError = AccentInk,
    errorContainer = Color(0xff3a1f1f),
    onErrorContainer = Negative,

    inverseSurface = TextPrimary,
    inverseOnSurface = AppBackground,
    scrim = Color(0xcc000000)
)

@Composable
fun FlowPayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FlowPayColors, content = content)
}

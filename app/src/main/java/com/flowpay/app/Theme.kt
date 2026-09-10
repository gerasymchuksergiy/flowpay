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
 * Before this file the screens were styled a value at a time: twenty-three dp
 * distances, fourteen text sizes, seven corner radii and eight nearly identical
 * dark greys. The gap between two of those greys does not read as a decision, it
 * reads as carelessness, and that is what made a finished app look like a draft.
 * Pick from the scales below instead of inventing a value.
 */

// Surfaces, darkest to lightest, each tinted slightly green so nothing fights the lime.
val AppBackground = Color(0xff0a0b09)
val SurfaceLow = Color(0xff121410)
val SurfaceBase = Color(0xff161814)
val SurfaceRaised = Color(0xff1e211b)
val SurfaceHigh = Color(0xff22241e)

// Text. Three live levels plus one for absent values.
val TextPrimary = Color(0xfff1f3ec)
val TextSecondary = Color(0xff9b9d96)
val TextDisabled = Color(0xff5c5f56)

// One accent, one tinted container for anything secondary, one alarm colour.
val Accent = Color(0xffd7ff63)
val AccentInk = Color(0xff0a0b09)
val AccentSoft = Color(0xff2a3318)
val Negative = Color(0xffff7a6b)

val HairLine = Color(0xff33362c)

/**
 * Vertical and horizontal rhythm on a 4dp base.
 *
 * The grouping rule that matters: the gap between two groups must be at least
 * twice the largest gap inside either of them. Evenly spread space stops being
 * air and becomes emptiness, which is exactly how the old screens read.
 */
object Space {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val huge = 48.dp

    /** One side margin for every screen, so all blocks share a left edge. */
    val screen = 20.dp

    /**
     * Bottom padding for a list that sits under the floating action button.
     * Scaffold insets account for the navigation bar but not for the button, so
     * without this the last row scrolls underneath it and cannot be reached.
     */
    val fabClearance = 96.dp
}

/** Three radii. A card and a chip should not each invent their own. */
object Radius {
    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(18.dp)
    val lg = RoundedCornerShape(26.dp)
    val pill = RoundedCornerShape(50)
}

/**
 * Seven levels, three weights.
 *
 * Black is reserved for the one hero figure on a screen. It used to appear five
 * times per screen, and a weight used five times is no longer heavy. On Cyrillic
 * at this size it also closes up the counters in ж, щ, м and ш, so the title read
 * as greasy rather than strong.
 */
object Type {
    val heroSize = 34.sp
    val heroLine = 36.sp
    val heroTracking = (-1.0).sp

    val screenTitleSize = 30.sp
    val screenTitleLine = 34.sp
    val screenTitleTracking = (-0.6).sp

    val sectionSize = 20.sp
    val sectionLine = 26.sp

    val cardTitleSize = 17.sp
    val cardTitleLine = 22.sp

    val bodySize = 15.sp
    val bodyLine = 22.sp

    val captionSize = 13.sp
    val captionLine = 18.sp

    val overlineSize = 11.sp
    val overlineTracking = 1.2.sp

    val navLabelSize = 10.sp
    val navLabelTracking = 0.3.sp

    val regular = FontWeight.Normal
    val medium = FontWeight.SemiBold
    val strong = FontWeight.Bold
}

/**
 * The scheme names every role the app touches.
 *
 * Leaving roles unset is why the tab indicator, the tonal buttons and the
 * operator chips came out lilac: those components read secondaryContainer and
 * onSecondaryContainer, and an unset container falls back to the stock Material
 * purple. Two unset roles produced almost all of the clash.
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
    surfaceVariant = SurfaceHigh,
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
    outlineVariant = SurfaceHigh,

    error = Negative,
    onError = AccentInk,
    errorContainer = Color(0xff331a16),
    onErrorContainer = Negative,

    inverseSurface = TextPrimary,
    inverseOnSurface = AppBackground,
    scrim = Color(0xcc000000)
)

@Composable
fun FlowPayTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = FlowPayColors, content = content)
}

package com.flowpay.app

import android.graphics.Bitmap
import android.provider.Settings
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
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
 * Grain, because near-black is where a panel runs out of numbers.
 *
 * An OLED cannot hold [AppBackground] accurately. Down here the effective
 * precision falls below the eight bits the colour is written in, so two tones a
 * single level apart come out identical, a slow gradient collapses into steps,
 * and the panel's own dithering invents its own pattern on top. Noise fixes it by
 * construction: it scatters neighbouring pixels across several levels, so there
 * is no longer an edge where a band could form.
 *
 * **Static, and it stays static.** Film grain that moves is motion by another
 * name — it would run straight past the reduced-motion setting the rest of this
 * file is careful to honour, and it would never idle. A fixed tile gets the whole
 * anti-banding benefit for nothing: no animation, no invalidation, one bitmap.
 *
 * The numbers, because the obvious ones are wrong. Composited normally, a tile of
 * full-range white noise at a tenth opacity lifts #0a0b09 from 10 to 22 — past
 * [SurfaceLow] at 18, so the background would end up lighter than the surface
 * meant to sit above it, and the whole ramp would invert. The tile therefore has
 * to be dark itself. Capped at 64 and drawn at a tenth, the ground moves between
 * 9 and 15 around a mean of 12: three levels of swing, which is comfortably more
 * than the one-level step that causes the banding, and two levels of lift, which
 * leaves black still reading as black and well clear of [SurfaceLow].
 */
private const val GrainTileSize = 128
private const val GrainCeiling = 64
private const val GrainAlpha = 0.10f

/**
 * One tile of monochrome noise.
 *
 * Seeded rather than random, so the texture is the same on every launch and a
 * screenshot taken today matches one taken tomorrow.
 */
private fun grainTile(): ImageBitmap {
    val random = java.util.Random(0x5EEDL)
    val pixels = IntArray(GrainTileSize * GrainTileSize)
    for (index in pixels.indices) {
        val level = random.nextInt(GrainCeiling + 1)
        pixels[index] = (0xff shl 24) or (level shl 16) or (level shl 8) or level
    }
    return Bitmap
        .createBitmap(GrainTileSize, GrainTileSize, Bitmap.Config.ARGB_8888)
        .apply { setPixels(pixels, 0, GrainTileSize, 0, 0, GrainTileSize, GrainTileSize) }
        .asImageBitmap()
}

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

    /**
     * The navigation bar's own height, above whatever the system reserves below it.
     *
     * The bar is translucent and content runs underneath it, so nothing measures
     * this for us any more: a list has to add it to its own bottom padding or its
     * last row ends up behind the tabs.
     */
    val navBar = 80.dp

    /**
     * Height of the compact bar the large title shrinks into, and of the
     * segmented control. Both are single-row touch targets, and a target smaller
     * than this is one people miss.
     */
    val touchRow = 48.dp
}

/** Three radii. A card and a chip should not each invent their own. */
object Radius {
    /**
     * The card radius as a bare distance.
     *
     * Glance rounds a widget with a Dp rather than a shape, and a widget that
     * rounds to a different radius than the cards inside the app reads as a
     * different app sitting on the home screen.
     */
    val card = 18.dp

    val sm = RoundedCornerShape(12.dp)
    val md = RoundedCornerShape(card)
    val lg = RoundedCornerShape(26.dp)
    val pill = RoundedCornerShape(50)
}

/**
 * How strongly the top edge of a raised surface catches the light.
 *
 * Material puts its baseline dark surface at #121212 partly so that a shadow
 * still has somewhere to fall. This app's ground is darker than that, which costs
 * it the shadow channel outright: a shadow on #0a0b09 is a shadow on black, and
 * nothing is drawn.
 *
 * Material's own replacement, tonal surface tint, does not survive here either.
 * It tints every elevated surface with the primary, and with a single lime accent
 * that turns the entire interface green — which is also the opposite of the rule
 * [HeroPanel] enforces, that exactly one surface per screen may be lime.
 *
 * What works for a one-accent dark design is neutral: lighten, and light the top
 * edge. A hairline of white along the top reads as a raised edge catching the
 * light from above, which is the same cue a shadow gives, drawn from the other
 * side. It fades out down the sides, because an edge lit all the way round reads
 * as an outline instead — a box drawn on the surface rather than the surface
 * itself standing slightly proud.
 */
private const val LitEdgeStrength = 0.08f

/**
 * The lit top edge, following whatever shape the surface is cut to.
 *
 * Given as a border rather than a line across the top so that it tracks the
 * corner radius: a straight hairline would overshoot a rounded corner and hang
 * off both ends.
 */
fun Modifier.litEdge(shape: Shape, strength: Float = LitEdgeStrength): Modifier = border(
    width = Dp.Hairline,
    brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = strength),
        0.45f to Color.White.copy(alpha = strength * 0.12f),
        1f to Color.Transparent
    ),
    shape = shape
)

/**
 * Seven levels, three weights.
 *
 * Black is reserved for the one hero figure on a screen. It used to appear five
 * times per screen, and a weight used five times is no longer heavy. On Cyrillic
 * at this size it also closes up the counters in ж, щ, м and ш, so the title read
 * as greasy rather than strong.
 */
object Type {
    val heroSize = 28.sp
    val heroLine = 32.sp
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
 * The app's own typeface, rather than whatever the phone happens to ship.
 *
 * Until this, FlowPay rendered in the system font — HyperOS's on this phone,
 * Roboto on a Pixel, something else again on a Samsung. The app looked like a
 * different app on every device and had no say at all in how its numerals lined
 * up, which is the one thing a screen full of prices needs.
 *
 * **Inter**, and the binary was checked rather than the metadata. Plus Jakarta
 * Sans advertises `cyrillic-ext` and ships a font with no Cyrillic outlines in it
 * at all, so the only trustworthy answer comes from the file itself. Parsing the
 * three files below: 248 codepoints in U+0400–04FF, every one of ґ є і ї with a
 * real outline, ₴ present, the apostrophe reachable from both U+02BC and U+2019
 * (both map to the same glyph), and `tnum` in GSUB — which [TabularFigures] needs.
 *
 * **Three static weights, not one variable file.** Variable axes do work at this
 * app's floor: `Paint.setFontVariationSettings` arrived in API 26, and Compose's
 * `setFontVariationSettings` is gated on `SDK_INT >= 26` and returns the typeface
 * untouched below it. So the capability is there. It was declined anyway, because
 * the way it fails is silent — an axis the file does not define is ignored, the
 * boolean saying so is discarded, and the text simply renders at the font's
 * default instance. Every title in the app would quietly stop being bold, and
 * nothing would say why. The app asks for exactly three weights, so a variable
 * axis buys no design freedom to pay for that risk. Static resource fonts are the
 * oldest path in Android text and have no version gate at all.
 *
 * The files are subset to Latin-1, Cyrillic and the punctuation this app actually
 * sets — 118 KB each, 355 KB in total, against 876 KB for Inter's full two-axis
 * variable file. `opsz` is pinned to 14, its text optical size: the display sizes
 * in [Type] already carry hand-tuned negative tracking, which is the part of an
 * optical size that matters here.
 *
 * Licensed under the SIL Open Font License 1.1; the text is in `app/licenses/`.
 */
val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold)
)

/**
 * The OpenType feature that makes every digit the same width.
 *
 * Proportional digits are drawn to look right inside a word: 1 is narrow, 0 is
 * wide. That is correct in a sentence and wrong in a column, where "1 299 ₴" and
 * "999 ₴" then fail to line up and the two-column wishlist grid reads as if it
 * had been nudged. It is also why a figure counting up mid-animation makes the
 * layout around it twitch — the string keeps changing width. Both are fixed here
 * once, statically, with no motion involved.
 *
 * Applies to money and to figures. Not to prose: a date or a count inside a
 * sentence wants the proportional digits the typeface was drawn with.
 */
const val TabularFigures = "tnum"

/**
 * The ambient text style with money digits locked to one width.
 *
 * Written as a copy of whatever style is already in force rather than as a style
 * of its own, so a call site keeps its size, weight and colour and changes only
 * the one thing: `Text(money(x), style = Tabular)`.
 */
val Tabular: TextStyle
    @Composable get() = LocalTextStyle.current.copy(fontFeatureSettings = TabularFigures)

/**
 * Material's scale, in the app's typeface.
 *
 * Every role is respelled rather than a few, because the ones left out are exactly
 * the ones that surface later: a Button's label, a Snackbar, the text inside a
 * dialog. One of those still rendering in the system font is the sort of thing
 * nobody sees until it ships.
 */
private fun typographyIn(family: FontFamily): Typography = Typography().run {
    Typography(
        displayLarge = displayLarge.copy(fontFamily = family),
        displayMedium = displayMedium.copy(fontFamily = family),
        displaySmall = displaySmall.copy(fontFamily = family),
        headlineLarge = headlineLarge.copy(fontFamily = family),
        headlineMedium = headlineMedium.copy(fontFamily = family),
        headlineSmall = headlineSmall.copy(fontFamily = family),
        titleLarge = titleLarge.copy(fontFamily = family),
        titleMedium = titleMedium.copy(fontFamily = family),
        titleSmall = titleSmall.copy(fontFamily = family),
        bodyLarge = bodyLarge.copy(fontFamily = family),
        bodyMedium = bodyMedium.copy(fontFamily = family),
        bodySmall = bodySmall.copy(fontFamily = family),
        labelLarge = labelLarge.copy(fontFamily = family),
        labelMedium = labelMedium.copy(fontFamily = family),
        labelSmall = labelSmall.copy(fontFamily = family)
    )
}

private val FlowPayTypography = typographyIn(Inter)

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

/**
 * Material 3 Expressive's motion, written out as the springs it actually is.
 *
 * A duration and an easing curve describe a film of a movement. A spring describes
 * the movement: interrupt it halfway and it carries its velocity into wherever it
 * is going next, which is the whole reason a state change can be read at a glance
 * on a 120 Hz screen instead of being a cut between two pictures.
 *
 * The numbers are the Expressive motion scheme's own token values. They are
 * spelled out here rather than read from `MaterialTheme.motionScheme`, because in
 * material3 1.4.0 that property and the whole `MotionScheme` type are `internal`
 * — the Expressive motion API is compiled into the artifact but not yet released
 * to callers. Written out, the two knobs that matter are visible anyway: damping
 * below one overshoots and settles back, which is the bounce, and stiffness is how
 * quickly it gets there.
 *
 * Spatial springs move things — size, offset, corner radius — and may overshoot.
 * Effects springs change colour and alpha, and are critically damped at 1.0,
 * because a colour that overshoots is simply the wrong colour for a few frames.
 */
private const val SpatialDamping = 0.8f
private const val SpatialStiffness = 380f
private const val FastSpatialDamping = 0.6f
private const val FastSpatialStiffness = 800f
private const val EffectsDamping = 1.0f
private const val EffectsStiffness = 1600f

/**
 * Whether the phone has been told to stop animating.
 *
 * Read once at the top of the app rather than per animation: it is a system
 * setting, and a screen where half the motion honoured it would be worse than
 * either answer.
 */
val LocalReducedMotion = staticCompositionLocalOf { false }

/**
 * The system's own switch, which is what "Вимкнути анімацію" in accessibility
 * settings and developer options both write to. Anything unreadable counts as
 * motion allowed, because refusing to animate on a phone that never asked would
 * be its own kind of wrong.
 */
@Composable
private fun systemReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

/**
 * The springs the app animates with, already answering the reduced-motion setting.
 *
 * Reduced motion is honoured by snapping rather than by shortening: a fast
 * animation is still an animation, and the point of the setting is that there
 * should not be one. The state still changes, instantly.
 */
object Motion {
    /** Movement: size, offset, corner radius. Allowed to overshoot. */
    @Composable
    fun <T> spatial(): FiniteAnimationSpec<T> =
        honoured(spring(dampingRatio = SpatialDamping, stiffness = SpatialStiffness))

    /** The same, for a small thing that should feel immediate. Bouncier on purpose. */
    @Composable
    fun <T> fastSpatial(): FiniteAnimationSpec<T> =
        honoured(spring(dampingRatio = FastSpatialDamping, stiffness = FastSpatialStiffness))

    /** Colour and alpha. Never overshoots. */
    @Composable
    fun <T> effects(): FiniteAnimationSpec<T> =
        honoured(spring(dampingRatio = EffectsDamping, stiffness = EffectsStiffness))

    @Composable
    private fun <T> honoured(spec: FiniteAnimationSpec<T>): FiniteAnimationSpec<T> =
        if (LocalReducedMotion.current) snap() else spec
}

@Composable
fun FlowPayTheme(content: @Composable () -> Unit) {
    // One bitmap for the life of the app. The brush is what every screen draws
    // its ground with, so building it per screen would be building it per tab.
    val grain = remember {
        ShaderBrush(ImageShader(grainTile(), TileMode.Repeated, TileMode.Repeated))
    }
    CompositionLocalProvider(LocalReducedMotion provides systemReducedMotion()) {
        MaterialTheme(
            colorScheme = FlowPayColors,
            typography = FlowPayTypography
        ) {
            // The ground is painted here rather than by the Scaffold, because the
            // grain has to land between the background and the content: over the
            // flat colour, where the banding is, and under the text and the
            // photographs, which have no use for it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(AppBackground)
                    .drawBehind { drawRect(grain, alpha = GrainAlpha) }
            ) {
                content()
            }
        }
    }
}

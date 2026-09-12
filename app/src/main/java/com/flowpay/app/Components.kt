package com.flowpay.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The pieces that carry the app's look.
 *
 * The rule they encode: **one lime surface per screen**. The accent used to appear
 * as a colour on text scattered across five cards, which spread it thin and left
 * every screen reading as a uniform grey column. Here the single most important
 * figure on a screen sits on a solid lime panel with dark text, and everything else
 * on that screen goes quiet. That is what makes a screen have a subject.
 */

/**
 * The one loud block on a screen: a lime panel carrying the figure that matters.
 *
 * The panel is lime whether or not there is a figure yet. Greying it out when the
 * value is zero was a mistake: an app with nothing in it then had no accent
 * anywhere and read as a stack of grey slabs, which is the opposite of the point.
 * A dark zero on lime still looks decided. `muted` only softens the number.
 *
 * Nothing else on the same screen may be lime, apart from the navigation indicator
 * and the action button. If two of these appear together, both stop working.
 */
@Composable
fun HeroPanel(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    muted: Boolean = false,
    trailing: (@Composable () -> Unit)? = null
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Accent),
        shape = Radius.lg
    ) {
        Row(
            // Generous at the sides, tighter top and bottom: the panel was carrying
            // more empty height than the figure inside it earned.
            Modifier.padding(horizontal = Space.xl, vertical = Space.md),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = AccentInk.copy(alpha = 0.65f),
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    value,
                    color = if (muted) AccentInk.copy(alpha = 0.45f) else AccentInk,
                    fontSize = Type.heroSize,
                    lineHeight = Type.heroLine,
                    letterSpacing = Type.heroTracking,
                    fontWeight = if (muted) Type.medium else FontWeight.Black
                )
                caption?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = AccentInk.copy(alpha = 0.65f),
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                }
            }
            trailing?.let {
                Spacer(Modifier.width(Space.lg))
                it()
            }
        }
    }
}

/**
 * A ring showing how much of a goal is saved.
 *
 * Two concentric strokes: the track, and the part covered so far. A ring reads as a
 * quantity at a glance in a way a thin bar does not, and it gives a screen a shape
 * rather than another horizontal line.
 */
@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    diameter: Dp = 96.dp,
    stroke: Dp = 10.dp,
    trackColor: Color = AccentInk.copy(alpha = 0.18f),
    ringColor: Color = AccentInk,
    center: (@Composable () -> Unit)? = null
) {
    Box(modifier.size(diameter), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(diameter)) {
            val width = stroke.toPx()
            val inset = width / 2
            val arcSize = Size(size.width - width, size.height - width)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width, cap = StrokeCap.Round)
            )
            if (progress > 0f) {
                drawArc(
                    color = ringColor,
                    // From the top, clockwise, which is how people read a dial.
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width, cap = StrokeCap.Round)
                )
            }
        }
        center?.invoke()
    }
}

/**
 * Price history as bars rather than a line.
 *
 * At the size this occupies on a card a single-pixel line is nearly invisible and
 * says nothing; bars read as a shape. Bars are spaced by date, so a price that held
 * for a month occupies a month of the width, and the current one is highlighted.
 */
@Composable
fun PriceBars(history: List<PricePoint>, modifier: Modifier = Modifier) {
    val points = history.filter { it.price > 0 }
    Canvas(modifier) {
        if (points.isEmpty()) return@Canvas
        val min = points.minOf { it.price }
        val max = points.maxOf { it.price }
        val range = (max - min).takeIf { it > 0 } ?: max.takeIf { it > 0 } ?: 1.0

        // A dotted baseline gives the bars something to stand on.
        var dot = 0f
        while (dot < size.width) {
            drawRoundRect(
                color = HairLine,
                topLeft = Offset(dot, size.height - 1f),
                size = Size(3f, 2f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f)
            )
            dot += 8f
        }

        val count = points.size
        val slot = size.width / count
        val barWidth = (slot * 0.55f).coerceAtMost(14f).coerceAtLeast(3f)

        points.forEachIndexed { index, point ->
            // Floor of 12% so the cheapest bar is still visibly a bar.
            val share = ((point.price - min) / range).toFloat().coerceIn(0f, 1f)
            val height = (size.height * (0.12f + 0.88f * share)).coerceAtLeast(barWidth)
            val x = slot * index + (slot - barWidth) / 2
            val isLast = index == count - 1
            drawRoundRect(
                color = if (isLast) Accent else SurfaceHigh,
                topLeft = Offset(x, size.height - height),
                size = Size(barWidth, height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(barWidth / 2)
            )
        }
    }
}

/**
 * A progress bar shaped like a pill, thick enough to read as a quantity.
 *
 * The stock indicator is a hairline: at arm's length it reads as a divider rather
 * than as a measurement, which is why the reference look uses a chunky capsule.
 */
@Composable
fun PillProgress(
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 12.dp,
    barColor: Color = Accent,
    trackColor: Color = SurfaceHigh
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(trackColor, Radius.pill)
    ) {
        val fraction = progress.coerceIn(0f, 1f)
        if (fraction > 0f) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(height)
                    .background(barColor, Radius.pill)
            )
        }
    }
}


/**
 * A value with the figure it is working towards, on one line.
 *
 * "500 ₴ / 6 000 ₴" says both the progress and the scale in the space a single
 * number used to take, and the muted half keeps the emphasis on what is achieved.
 */
@Composable
fun FigureWithTarget(
    value: String,
    modifier: Modifier = Modifier,
    target: String? = null,
    color: Color = TextPrimary,
    valueSize: androidx.compose.ui.unit.TextUnit = Type.sectionSize
) {
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(value, color = color, fontSize = valueSize, fontWeight = Type.strong)
        target?.let {
            Text(
                " / $it",
                color = TextSecondary,
                fontSize = Type.captionSize,
                modifier = Modifier.padding(bottom = 2.dp)
            )
        }
    }
}

/** A round icon chip, the way list rows carry their icon in this look. */
@Composable
fun IconChip(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    background: Color = AccentSoft,
    tint: Color = Accent
) {
    Box(
        modifier.size(size).background(background, Radius.pill),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, null, modifier = Modifier.size(size * 0.45f), tint = tint)
    }
}

/** One column of a [StatStrip]: a small lime icon, the value, and its target. */
data class StatColumn(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val target: String? = null
)

/**
 * Several figures side by side in one card, with rings reading the same numbers.
 *
 * This is the block the reference builds its home screen around, and it is why that
 * screen looks dense while a column of one-figure cards looks sparse: three
 * quantities and their targets occupy the height of one.
 */
@Composable
fun StatStrip(
    columns: List<StatColumn>,
    modifier: Modifier = Modifier,
    rings: List<Float>? = null
) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
        shape = Radius.lg
    ) {
        Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.md)) {
                columns.forEach { column ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            column.icon,
                            null,
                            modifier = Modifier.size(16.dp),
                            tint = Accent
                        )
                        Spacer(Modifier.width(Space.sm))
                        Column {
                            Text(
                                column.label,
                                color = TextSecondary,
                                fontSize = Type.captionSize
                            )
                            FigureWithTarget(
                                value = column.value,
                                target = column.target,
                                valueSize = Type.cardTitleSize
                            )
                        }
                    }
                }
            }
            rings?.takeIf { it.isNotEmpty() }?.let {
                Spacer(Modifier.width(Space.lg))
                ProgressRings(
                    values = it,
                    colors = listOf(Accent, Accent.copy(alpha = 0.65f), Accent.copy(alpha = 0.4f)),
                    diameter = 92.dp
                )
            }
        }
    }
}

/**
 * Concentric rings, outermost first.
 *
 * Several quantities in one circle is the shape that carries this look. Each ring
 * steps inward by its own stroke plus a gap, so they read as separate tracks rather
 * than as one thick band.
 */
@Composable
fun ProgressRings(
    values: List<Float>,
    colors: List<Color>,
    modifier: Modifier = Modifier,
    diameter: Dp = 92.dp,
    stroke: Dp = 9.dp,
    gap: Dp = 5.dp,
    trackColor: Color = SurfaceHigh
) {
    Canvas(modifier.size(diameter)) {
        val width = stroke.toPx()
        val step = width + gap.toPx()
        values.forEachIndexed { index, value ->
            val inset = width / 2 + step * index
            if (size.width - inset * 2 <= 0f) return@forEachIndexed
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(
                color = trackColor,
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width, cap = StrokeCap.Round)
            )
            val fraction = value.coerceIn(0f, 1f)
            if (fraction > 0f) {
                drawArc(
                    color = colors.getOrElse(index) { Accent },
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width, cap = StrokeCap.Round)
                )
            }
        }
    }
}

/**
 * A fact as a label and a value pulled apart by a dotted line.
 *
 * The leader makes a list of unrelated facts scan as a table without drawing one,
 * which is how the reference lays out the numbers under its chart.
 */
@Composable
fun LeaderRow(label: String, value: String, modifier: Modifier = Modifier, alarm: Boolean = false) {
    Row(
        modifier.fillMaxWidth().padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextSecondary, fontSize = Type.captionSize)
        Box(Modifier.weight(1f).padding(horizontal = Space.sm)) {
            DottedLeader(Modifier.fillMaxWidth())
        }
        Text(
            value,
            color = if (alarm) Negative else TextPrimary,
            fontSize = Type.captionSize,
            fontWeight = Type.strong
        )
    }
}


/**
 * How far along a thing is, drawn as the route rather than as a set of buttons.
 *
 * Stops already passed are grey, the one it is at now is lime and larger, the
 * rest are dark. Every stop stays tappable: the carrier's status is sometimes
 * wrong or silent, and correcting it by hand was always possible here.
 */
@Composable
fun StageRail(
    stages: List<String>,
    current: String,
    modifier: Modifier = Modifier,
    onPick: (String) -> Unit
) {
    val reached = stages.indexOf(current).coerceAtLeast(0)
    // Moving a parcel along by hand should feel like moving something.
    val touch = LocalHapticFeedback.current
    Row(modifier.fillMaxWidth()) {
        stages.forEachIndexed { position, stage ->
            val passed = position < reached
            val here = position == reached
            Column(
                Modifier
                    .weight(1f)
                    .clickable {
                        touch.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onPick(stage)
                    }
                    .padding(vertical = Space.sm),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // A parcel moving a stage is news, and news that simply appears is
                // missed. The dot swells into place on a bouncy spring so the eye
                // is drawn to which stop is live now; the colours settle without
                // overshooting, because a half-lime dot would be a third state.
                val dot by animateDpAsState(
                    if (here) 14.dp else 9.dp,
                    Motion.fastSpatial(),
                    label = "stage dot"
                )
                val dotColor by animateColorAsState(
                    when {
                        here -> Accent
                        passed -> TextDisabled
                        else -> HairLine
                    },
                    Motion.effects(),
                    label = "stage dot colour"
                )
                val labelColor by animateColorAsState(
                    when {
                        here -> Accent
                        passed -> TextSecondary
                        else -> TextDisabled
                    },
                    Motion.effects(),
                    label = "stage label colour"
                )
                Row(
                    Modifier.fillMaxWidth().height(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Half a connector on each side, so the stops read as one route
                    // and the ends of the row stay open.
                    RailSegment(Modifier.weight(1f), drawn = position > 0, passed = passed || here)
                    Box(Modifier.size(dot).background(dotColor, CircleShape))
                    RailSegment(
                        Modifier.weight(1f),
                        drawn = position < stages.lastIndex,
                        passed = passed
                    )
                }
                Spacer(Modifier.height(Space.sm))
                Text(
                    stage,
                    color = labelColor,
                    fontSize = Type.overlineSize,
                    lineHeight = Type.captionLine,
                    textAlign = TextAlign.Center,
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun RailSegment(modifier: Modifier, drawn: Boolean, passed: Boolean) {
    // The line fills in behind the dot rather than with it, which is what makes a
    // stage change read as travel along a route instead of two lamps swapping.
    val colour by animateColorAsState(
        when {
            !drawn -> Color.Transparent
            passed -> TextDisabled
            else -> HairLine
        },
        Motion.effects(),
        label = "rail segment"
    )
    Box(modifier.height(1.dp).background(colour))
}

/**
 * The dotted run between a label and its figure.
 *
 * Carries the eye across the gap without drawing a line that competes with the
 * text, the way a table of contents does.
 */
@Composable
fun DottedLeader(modifier: Modifier = Modifier) {
    Canvas(modifier.height(1.dp)) {
        var x = 0f
        while (x < size.width) {
            drawRoundRect(
                color = HairLine,
                topLeft = Offset(x, 0f),
                size = Size(2f, 1.5f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f)
            )
            x += 6f
        }
    }
}

/**
 * The days ahead in a single line, today first.
 *
 * A tick per day: short and grey for an ordinary one, tall and lime where money
 * leaves, full height and light for today. It counts forward rather than drawing
 * the calendar month, because the list underneath already runs past the month's
 * end and the two were describing different spans.
 */
@Composable
fun DaysStrip(
    days: Int,
    marked: Set<Int>,
    modifier: Modifier = Modifier
) {
    Row(
        modifier.fillMaxWidth().height(26.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        (0 until days).forEach { offset ->
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 1.dp)
                    .height(
                        when {
                            offset == 0 -> 26.dp
                            offset in marked -> 16.dp
                            else -> 6.dp
                        }
                    )
                    .background(
                        when {
                            // Today is light rather than lime, so the colour keeps
                            // meaning one thing only: money.
                            offset == 0 -> TextPrimary
                            offset in marked -> Accent
                            else -> HairLine
                        },
                        RoundedCornerShape(2.dp)
                    )
            )
        }
    }
}

/**
 * What a screen with nothing on it shows.
 *
 * A lime panel with an invitation, rather than hairline placeholders holding grey
 * labels. Outlined ghosts read as a form that failed to load; the reference look
 * answers an empty screen with its loudest surface and a sentence.
 */
@Composable
fun EmptyInvite(title: String, text: String, modifier: Modifier = Modifier) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Accent),
        shape = Radius.lg
    ) {
        Column(Modifier.padding(Space.xl)) {
            Text(
                title,
                color = AccentInk,
                fontSize = Type.screenTitleSize,
                lineHeight = Type.screenTitleLine,
                letterSpacing = Type.screenTitleTracking,
                fontWeight = FontWeight.Black
            )
            Spacer(Modifier.height(Space.sm))
            Text(
                text,
                color = AccentInk.copy(alpha = 0.75f),
                fontSize = Type.bodySize,
                lineHeight = Type.bodyLine
            )
        }
    }
}

/**
 * Placeholder rows for a screen that already carries a lime panel of its own.
 *
 * Solid surfaces rather than outlines, because a hairline box on a dark ground
 * reads as a rendering failure.
 */
@Composable
fun PlaceholderRows(fields: List<Pair<String, String>>, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        fields.forEach { (primary, secondary) ->
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                shape = Radius.md
            ) {
                Row(
                    Modifier.padding(Space.lg),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(44.dp).background(SurfaceHigh, Radius.sm))
                    Spacer(Modifier.width(Space.md))
                    Column {
                        Text(primary, color = TextSecondary, fontSize = Type.bodySize)
                        Text(secondary, color = TextDisabled, fontSize = Type.captionSize)
                    }
                }
            }
        }
    }
}

/**
 * A dark pill sitting on top of a photo.
 *
 * The reference labels its photo cards this way rather than putting the label
 * underneath: a translucent dark capsule with a lime icon and a short line. It
 * keeps the image whole and still reads at a glance.
 */
/**
 * The corners a verdict wears, one radius per corner.
 *
 * A verdict changing is one of the three things in this app that actually happens
 * rather than merely being displayed, and a cross-fade between two capsules is
 * indistinguishable from a redraw — you look up and the words are different, with
 * nothing to say when. A shape that springs from a capsule into a leaf is legible
 * out of the corner of an eye, and stays legible at 120 Hz where a 200ms fade is
 * over before it has been noticed.
 *
 * Four radii rather than one because a uniform radius only ever reads as "rounder"
 * or "squarer". Opposite corners pulled in opposite directions changes the outline,
 * which is what the eye catches.
 */
data class VerdictCorners(val topStart: Dp, val topEnd: Dp, val bottomEnd: Dp, val bottomStart: Dp) {
    companion object {
        /** The resting shape: a plain capsule, used wherever nothing is being judged. */
        val pill = VerdictCorners(17.dp, 17.dp, 17.dp, 17.dp)
    }
}

/** Open and round for a good moment, cut across for a bad one, small while unsure. */
fun buyVerdictCorners(verdict: BuyVerdict): VerdictCorners = when (verdict) {
    BuyVerdict.GOOD -> VerdictCorners.pill
    BuyVerdict.FAIR -> VerdictCorners(11.dp, 11.dp, 11.dp, 11.dp)
    BuyVerdict.POOR -> VerdictCorners(17.dp, 4.dp, 17.dp, 4.dp)
    BuyVerdict.UNKNOWN -> VerdictCorners(6.dp, 6.dp, 6.dp, 6.dp)
}

/** The same three shapes for the verdict on a purchase that is already over. */
fun purchaseVerdictCorners(verdict: PurchaseVerdict): VerdictCorners = when (verdict) {
    PurchaseVerdict.PATIENT -> VerdictCorners.pill
    PurchaseVerdict.HASTY -> VerdictCorners(17.dp, 4.dp, 17.dp, 4.dp)
    PurchaseVerdict.UNJUDGED -> VerdictCorners(6.dp, 6.dp, 6.dp, 6.dp)
}

/**
 * A verdict in a capsule that morphs into its next shape rather than being replaced.
 *
 * The outline moves on a spatial spring and the colour on an effects spring, which
 * is why the two do not arrive together: the shape overshoots slightly and settles,
 * the colour does not. Under the system's reduced-motion setting both snap, and the
 * verdict is still correct — [Motion] handles that, not this.
 */
@Composable
fun VerdictChip(
    text: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tint: Color = Accent,
    corners: VerdictCorners = VerdictCorners.pill
) {
    val shapeSpec = Motion.spatial<Dp>()
    val topStart by animateDpAsState(corners.topStart, shapeSpec, label = "verdict top start")
    val topEnd by animateDpAsState(corners.topEnd, shapeSpec, label = "verdict top end")
    val bottomEnd by animateDpAsState(corners.bottomEnd, shapeSpec, label = "verdict bottom end")
    val bottomStart by animateDpAsState(corners.bottomStart, shapeSpec, label = "verdict bottom start")
    val ink by animateColorAsState(tint, Motion.effects(), label = "verdict tint")
    Row(
        modifier
            .background(
                AppBackground.copy(alpha = 0.72f),
                RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)
            )
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        icon?.let {
            Icon(it, null, modifier = Modifier.size(14.dp), tint = ink)
            Spacer(Modifier.width(Space.xs))
        }
        Text(text, color = ink, fontSize = Type.captionSize, fontWeight = Type.medium)
    }
}

/**
 * A product photo with its headline facts laid over it.
 *
 * A percentage that moved is the news, so it goes on the image as a large lime
 * numeral the way the reference overlays its workout numbers, and the verdict sits
 * in a capsule in the corner. Without a photo there is no block at all rather than
 * a dead grey rectangle.
 */
@Composable
fun PhotoHeader(
    imageUrl: String,
    description: String,
    modifier: Modifier = Modifier,
    // A wish card carried 190dp of photograph plus its text, which came to more
    // than half the screen for one item. The picture still identifies the thing
    // at 132 without owning the list.
    height: Dp = 132.dp,
    overlayNumber: String? = null,
    overlayNumberColor: Color = Accent,
    chip: String? = null,
    chipIcon: ImageVector? = null,
    chipColor: Color = Accent,
    /** The shape the chip springs to. Left as a capsule where nothing is judged. */
    chipCorners: VerdictCorners = VerdictCorners.pill,
    content: @Composable (Modifier) -> Unit
) {
    Box(modifier.fillMaxWidth().height(height)) {
        content(Modifier.fillMaxWidth().height(height))
        // Shops photograph on white, which in a dark app is a lit panel rather
        // than a picture. A thin wash of the page colour settles it down without
        // hiding what the thing looks like.
        Box(
            Modifier
                .matchParentSize()
                .background(AppBackground.copy(alpha = 0.14f))
        )
        overlayNumber?.let {
            Text(
                it,
                color = overlayNumberColor,
                fontSize = 46.sp,
                lineHeight = 48.sp,
                letterSpacing = (-1.5).sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.md)
            )
        }
        chip?.let {
            VerdictChip(
                it,
                Modifier.align(Alignment.BottomStart).padding(Space.md),
                chipIcon,
                chipColor,
                chipCorners
            )
        }
    }
}

/**
 * The floating status pill: one narrow capsule near the top with the single thing
 * the app would interrupt you about, or nothing at all.
 *
 * It is dark, not lime, and that is a decision rather than an oversight. The pill
 * appears above every screen, so a lime pill would sit beside the [HeroPanel] on
 * the expenses and overview screens and neither would be the subject any more. The
 * hero keeps the lime; the pill spends the accent only on its icon, which is the
 * same small mark the navigation indicator is allowed.
 *
 * [note] being null draws nothing: a bar that is always there with nothing in it
 * teaches you to stop looking at it.
 */
@Composable
fun StatusPill(note: StatusNote?, modifier: Modifier = Modifier, onOpen: (Int) -> Unit) {
    AnimatedVisibility(
        visible = note != null,
        modifier = modifier,
        // A target being reached is one of the app's three real moments, and this
        // bar is where it surfaces. The height springs open so the screen below is
        // seen to be pushed down by something arriving; the contents fade on the
        // critically damped spec, because text that overshoots is just a wobble.
        enter = expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
        exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())
    ) {
        // Null only while the pill is closing, when there is nothing left to draw.
        note?.let { shown ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.screen, vertical = Space.sm)
                    .clip(Radius.pill)
                    .clickable { onOpen(shown.tab) }
                    // Translucent, so the page tint shows through and it reads as
                    // something resting on the screen rather than part of it.
                    .background(SurfaceHigh.copy(alpha = 0.92f), Radius.pill)
                    .border(Dp.Hairline, HairLine, Radius.pill)
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    // The tab's own icon, so the pill shows where tapping it lands.
                    when (shown.kind) {
                        StatusKind.PARCEL -> Icons.Default.LocalShipping
                        StatusKind.PAYMENT -> Icons.Default.ReceiptLong
                        StatusKind.TARGET -> Icons.Default.FavoriteBorder
                    },
                    null,
                    Modifier.size(Space.lg),
                    tint = if (shown.urgent) Negative else Accent
                )
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        shown.title,
                        color = TextPrimary,
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        shown.detail,
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    Modifier.size(Space.lg),
                    tint = TextDisabled
                )
            }
        }
    }
}

/**
 * The compact bar the large title shrinks into.
 *
 * The large title still scrolls away with the content, the way it should; this
 * takes over the moment it would slide out of sight, so being far down a list
 * never leaves the screen unnamed. Translucent for the same reason as the
 * navigation bar: the list underneath stays visible and the bar reads as glass
 * over it rather than as a lid on it.
 *
 * The header must be item 0 of [listState] — that is what is measured.
 */
@Composable
fun CollapsingTitle(
    title: String,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val barHeight = with(LocalDensity.current) { Space.touchRow.roundToPx() }
    val collapsed by remember(listState, barHeight) {
        derivedStateOf {
            val header = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            titleCollapsed(header?.let { it.offset + it.size }, barHeight)
        }
    }
    CollapsingTitleBar(title, collapsed, modifier, trailing)
}

/**
 * The same bar over a grid.
 *
 * A grid lays out in two dimensions, so its visible items carry an `IntOffset` and
 * an `IntSize` where a list carries two plain ints. Only that measurement differs:
 * both overloads hand the same number to the same [titleCollapsed] threshold, so
 * the wishlist and the lists cannot drift apart about when a title gives way.
 */
@Composable
fun CollapsingTitle(
    title: String,
    gridState: LazyGridState,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    val barHeight = with(LocalDensity.current) { Space.touchRow.roundToPx() }
    val collapsed by remember(gridState, barHeight) {
        derivedStateOf {
            val header = gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }
            titleCollapsed(header?.let { it.offset.y + it.size.height }, barHeight)
        }
    }
    CollapsingTitleBar(title, collapsed, modifier, trailing)
}

@Composable
private fun CollapsingTitleBar(
    title: String,
    collapsed: Boolean,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null
) {
    AnimatedVisibility(
        visible = collapsed,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut()
    ) {
        Column(Modifier.fillMaxWidth().background(SurfaceLow.copy(alpha = 0.92f))) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(Space.touchRow)
                    .padding(horizontal = Space.screen),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    Modifier.weight(1f),
                    color = TextPrimary,
                    fontSize = Type.cardTitleSize,
                    lineHeight = Type.cardTitleLine,
                    fontWeight = Type.medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                trailing?.invoke()
            }
            // Without an edge the bar and the list under it merge into one grey.
            Box(Modifier.fillMaxWidth().height(Dp.Hairline).background(HairLine))
        }
    }
}

/**
 * Bottom padding for a list that scrolls underneath the translucent navigation bar.
 *
 * The bar no longer takes its height out of the scaffold's content area — that is
 * what lets the list show through it — so each list has to leave the room itself
 * or its last row sits unreachable behind the tabs.
 */
@Composable
fun navClearance(): Dp =
    Space.navBar + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

/**
 * One control with two halves and a lime indicator that slides between them.
 *
 * Two separate chips made the choice look like two independent switches, either or
 * both of which might be on. A single track with one marker says what is true: one
 * of these, and changing it moves the marker rather than lighting a second lamp.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selected: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit
) {
    if (options.isEmpty()) return
    val active = selected.coerceIn(0, options.lastIndex)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(Space.touchRow)
            .background(SurfaceHigh, Radius.pill)
    ) {
        val slot = maxWidth / options.size
        val travel by animateDpAsState(slot * active, label = "segment")
        Box(
            Modifier
                // The lambda overload, so the sliding indicator relayouts rather
                // than recomposing the whole control on every animation frame.
                .offset { IntOffset(travel.roundToPx(), 0) }
                .width(slot)
                .fillMaxHeight()
                .padding(Space.xs)
                .background(Accent, Radius.pill)
        )
        Row(Modifier.fillMaxSize()) {
            options.forEachIndexed { index, label ->
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(Radius.pill)
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = if (index == active) AccentInk else TextSecondary,
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

/**
 * A form that grows from the bottom edge instead of landing in the middle.
 *
 * Every one of these forms has a text field in it, and a centred dialog with the
 * keyboard up had nowhere to go: the confirm button ended up under the keys, which
 * is why one of them had scrolling bolted on and the rest simply did not fit. A
 * sheet starts at the bottom, rises above the keyboard, and closes with the same
 * downward swipe used everywhere else on the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FormSheet(
    title: String,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        // Half height would put the confirm button below the fold on every one of
        // these forms, so there is only one useful size.
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceLow,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = HairLine) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = Space.screen)
                .padding(bottom = Space.xl)
        ) {
            Text(
                title,
                fontSize = Type.sectionSize,
                lineHeight = Type.sectionLine,
                fontWeight = Type.medium
            )
            Spacer(Modifier.height(Space.md))
            content()
            Spacer(Modifier.height(Space.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                TextButton(onDismiss, Modifier.weight(1f)) { Text("Скасувати") }
                Button(
                    onConfirm,
                    Modifier.weight(1f),
                    enabled = confirmEnabled,
                    shape = Radius.pill
                ) { Text(confirmLabel) }
            }
        }
    }
}

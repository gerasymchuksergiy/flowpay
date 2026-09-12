package com.flowpay.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import kotlin.math.abs

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
                    fontWeight = if (muted) Type.medium else FontWeight.Black,
                    style = Tabular
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

// ---------------------------------------------------------------------- charts

/**
 * What the charts are allowed to draw with.
 *
 * `AppBackground` is #0a0b09, and a saturated lime laid straight onto it
 * vibrates: the edge between the two is very nearly the whole contrast the screen
 * can produce, so the eye reads the boundary rather than the shape the line is
 * making. Two rules follow from that, and neither invents a colour — both come
 * off the ramp in Theme.kt.
 *
 * A chart gets its own ground, one step up from the card it sits on, so the data
 * is drawn on a surface instead of on the page. And the accent is mixed a quarter
 * of the way towards a grey of its own brightness before it is used as ink: at
 * chart weights — a two-pixel line, a four-pixel marker — full lime on near-black
 * is exactly the case that buzzes, while the calmer tone still reads as the app's
 * colour.
 *
 * One hue and no category palette is a constraint working in the charts' favour.
 * A second series is separated by opacity and by the shape of its line, never by
 * a second colour, so nothing here has to invent a meaning for red or blue.
 */
private const val ChartDesaturation = 0.25f

/**
 * Mixes [color] towards a grey of the same brightness. Zero leaves it alone, one
 * takes the colour out entirely.
 */
fun desaturated(color: Color, amount: Float): Color {
    val mix = amount.coerceIn(0f, 1f)
    val grey = 0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue
    return Color(
        red = color.red * (1f - mix) + grey * mix,
        green = color.green * (1f - mix) + grey * mix,
        blue = color.blue * (1f - mix) + grey * mix,
        alpha = color.alpha
    )
}

/** The accent, calmed down for a thin line on a dark ground. */
val ChartInk = desaturated(Accent, ChartDesaturation)

/** The plate a chart draws on, a step above the card underneath it. */
val ChartGround = SurfaceHigh

/** Dim enough that the data wins. A gridline that competes is decoration. */
val ChartGrid = HairLine.copy(alpha = 0.55f)

/** How long a finger has to rest before it is scrubbing rather than tapping. */
private const val SCRUB_HOLD_MS = 100L

/** The vertical range a chart's axis covers, in the values' own units. */
data class ChartAxis(val low: Double, val high: Double) {
    val span: Double get() = high - low

    /** Where [value] sits: zero at the bottom of the axis, one at the top. */
    fun fraction(value: Double): Float =
        if (span <= 0.0) 0.5f else ((value - low) / span).toFloat().coerceIn(0f, 1f)
}

/** The smallest slice of the price level an axis may cover, as a fraction of it. */
const val MIN_AXIS_SPAN = 0.10

/**
 * Picks the axis for a set of values, and this is the decision the old chart got
 * wrong.
 *
 * The bars scaled every history to its own `min..max` and then floored the
 * shortest bar at 12% of the height. A wish that moved from ₴4 900 to ₴4 850 —
 * one percent — therefore drew the same cliff as one that had halved. Two
 * separate things are wrong there and they need separate answers.
 *
 * The first is the mark. A bar encodes a quantity as a **length**, which is why a
 * bar chart has to start at zero: cut the bottom off a bar and its length is no
 * longer the number. A line encodes as **position**, and a line may legitimately
 * start wherever it likes — which is why every price chart ever drawn is a line
 * and not a bar. So the bars are gone, and with them the truncated-length lie.
 * That is not a preference about looks; it is what the two marks mean.
 *
 * The second is the zoom, and no automatic axis fully solves it: an axis fitted
 * to its own data always makes that data fill the frame. What it can do is refuse
 * to zoom past a point. The axis here never covers less than [MIN_AXIS_SPAN] of
 * the price level, so a one-percent move takes up a tenth of the height and a
 * month that barely moved draws nearly flat, instead of both being inflated into
 * a full-height cliff. Above that floor the chart scales like any other and the
 * two figures printed beneath it carry the magnitude, which is the contract an
 * axis has always had and the reason it is labelled at all.
 *
 * Across a list the same trap scales up, and the answer taken here is the second
 * of the two honest ones: per-item scales, with the size of the move stated in
 * text beside the chart. The first — rebasing every row to a percentage on one
 * shared scale — would make the wishlist comparable at the cost of never showing
 * a price, and a wishlist whose rows do not show prices is not a wishlist.
 */
fun chartAxis(values: List<Double>, minimumSpan: Double = MIN_AXIS_SPAN): ChartAxis {
    val real = values.filter { it > 0.0 }
    if (real.isEmpty()) return ChartAxis(0.0, 1.0)
    val low = real.min()
    val high = real.max()
    val middle = (low + high) / 2
    // A little headroom at both ends, so the line never runs along an edge.
    val span = maxOf((high - low) * 1.3, middle * minimumSpan)
    return ChartAxis(middle - span / 2, middle + span / 2)
}

/** "вісь 4 850 – 5 350 ₴" — where the vertical axis starts and where it stops. */
fun axisNote(axis: ChartAxis, format: (Double) -> String): String =
    "вісь ${bareAmount(axis.low)} – ${format(axis.high)}"

/**
 * Where each reading sits horizontally: zero at the left edge, one at the right.
 *
 * By date, because [appendPrice] writes a point only when the price changes, so
 * the gaps between points are wildly unequal. The old bars claimed in their own
 * comment to be spaced by date and were in fact spaced by index, which put a
 * change made yesterday and one made six months ago the same distance apart. A
 * history with any undated point falls back to even spacing, because by then
 * there is nothing else left to place it with.
 */
fun chartPositions(points: List<PricePoint>): List<Float> {
    if (points.isEmpty()) return emptyList()
    if (points.size == 1) return listOf(0.5f)
    val even = points.indices.map { it / (points.size - 1).toFloat() }
    if (points.any { it.day <= 0L }) return even
    val first = points.first().day
    val span = (points.last().day - first).toFloat()
    if (span <= 0f) return even
    return points.map { ((it.day - first) / span).coerceIn(0f, 1f) }
}

/**
 * The series a price chart actually draws: the recorded changes, with the price
 * in force right now closing the line.
 *
 * The history holds changes only, and today's price lives on the wish rather than
 * in the list. Drawing the list alone therefore ends the line at the last change,
 * which on a price that has held for three months is three months short and reads
 * as the tracking having quietly stopped.
 */
fun chartSeries(history: List<PricePoint>, current: Double, today: Long): List<PricePoint> {
    val points = history.filter { it.price > 0.0 }
    if (current <= 0.0 || today <= 0L) return points
    val last = points.lastOrNull()
    if (last != null && last.price == current && last.day >= today) return points
    return points + PricePoint(current, today, last?.rate ?: 0.0, last?.rateSource ?: "")
}

/** How the space between two readings is drawn. */
enum class ChartLine {
    /**
     * Flat until the change, then straight up or down. The only honest rendering
     * of what [appendPrice] stores: a point exists only where the price moved, so
     * the history is a list of change events rather than a series sampled at
     * intervals. A sloped line from ₴400 in March to ₴350 on 28 April would state
     * that the price glided down across the whole of April. It did not — it sat
     * at ₴400 and then dropped. Every corner in a step line is a real event, which
     * is also why it looks more deliberate than a smooth curve.
     */
    STEP,

    /**
     * Straight from one reading to the next, for a quantity that genuinely does
     * move between samples. [appendRate] records the rate every day whether or not
     * it changed, so its gaps are gaps in sampling rather than periods of nothing
     * happening, and a step there would claim the rate held still for a week and
     * then jumped — the same lie, mirrored.
     */
    LINEAR
}

/** The line through the points, in canvas coordinates. */
private fun chartPath(
    points: List<PricePoint>,
    positions: List<Float>,
    axis: ChartAxis,
    kind: ChartLine,
    width: Float,
    top: Float,
    height: Float
): Path {
    val path = Path()
    var lastY = 0f
    points.forEachIndexed { index, point ->
        val x = positions.getOrElse(index) { 0f } * width
        val y = top + (1f - axis.fraction(point.price)) * height
        when {
            index == 0 -> path.moveTo(x, y)
            // Hold the old level right up to the day it changed, then move.
            kind == ChartLine.STEP -> {
                path.lineTo(x, lastY)
                path.lineTo(x, y)
            }
            else -> path.lineTo(x, y)
        }
        lastY = y
    }
    return path
}

/**
 * A price history as a line, with the scrub a stock app has.
 *
 * Deliberately carries no shaded area under it. With a non-zero baseline the area
 * is not a quantity — it is whatever the axis happened to be cut at — and a fill
 * that size reads as volume whether or not it was meant to.
 *
 * Hold for a moment and drag to read a single day off it: the segment ahead of the
 * finger drops back in opacity, a hairline marks the position, and the figure and
 * date appear under the chart rather than floating over it, where a label near
 * either edge would have to be nudged back inside. The touch target is the whole
 * height of the plot. Nothing is consumed until the hold is over, so a tap or a
 * scroll through the chart still reaches the list underneath it — an animation
 * that eats a gesture is the worst thing a chart can do.
 *
 * The one moving part is the marker, and it moves on a spring through [Motion],
 * which snaps under the system's reduced-motion setting. There is no draw-on or
 * any other decorative motion to suppress: the guidance is to remove decorative
 * movement outright and to replace meaningful movement rather than delete it, and
 * the honest way to meet both is not to add the decorative kind in the first place.
 */
@Composable
fun PriceChart(
    points: List<PricePoint>,
    modifier: Modifier = Modifier,
    kind: ChartLine = ChartLine.STEP,
    format: (Double) -> String = ::money,
    /**
     * Added after the axis figures. Where a chart is drawn on its own scale —
     * which is every chart here — the size of the move has to be stated in text,
     * because that is the only thing making two of them comparable.
     */
    note: String? = null,
    height: Dp = 132.dp
) {
    val drawn = remember(points) { points.filter { it.price > 0.0 } }
    val positions = remember(drawn) { chartPositions(drawn) }
    val axis = remember(drawn) { chartAxis(drawn.map { it.price }) }
    val touch = rememberTouch()
    var selected by remember(drawn) { mutableIntStateOf(-1) }

    val marker by animateFloatAsState(
        if (selected >= 0) positions.getOrElse(selected) { 0f } else 0f,
        Motion.spatial(),
        label = "scrub position"
    )
    val markerAlpha by animateFloatAsState(
        if (selected >= 0) 1f else 0f,
        Motion.effects(),
        label = "scrub fade"
    )

    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(Radius.sm)
                .background(ChartGround)
                .then(
                    if (drawn.size < 2) {
                        Modifier
                    } else {
                        Modifier.pointerInput(drawn, positions) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // A hold, not a press: letting go or being taken
                                // over by the scrolling list first means this was
                                // never a scrub, and nothing has been consumed.
                                val holding = try {
                                    withTimeout(SCRUB_HOLD_MS) { waitForUpOrCancellation() }
                                    false
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    true
                                }
                                if (!holding) return@awaitEachGesture
                                // The hold has just turned a press into a scrub,
                                // and that is the one moment in this app where a
                                // gesture crosses a commit point. Felt here, under
                                // a finger that has not moved yet, it says the
                                // chart is yours to drag; felt on release it would
                                // say only that a finger left the glass.
                                touch.committed()
                                var landing = true
                                val width = size.width.toFloat()
                                fun choose(x: Float) {
                                    if (width <= 0f) return
                                    val target = (x / width).coerceIn(0f, 1f)
                                    var best = 0
                                    positions.forEachIndexed { index, at ->
                                        if (abs(at - target) < abs(positions[best] - target)) {
                                            best = index
                                        }
                                    }
                                    if (best != selected) {
                                        selected = best
                                        // One tick per reading crossed, and never
                                        // per frame — a finger resting still on one
                                        // point stays silent, which is what this
                                        // guard is for. The first point is silent
                                        // too: the commit above already answered
                                        // for it, and two feedbacks a frame apart
                                        // read as one bad buzz rather than two
                                        // facts.
                                        if (landing) landing = false else touch.stepped()
                                    }
                                }
                                down.consume()
                                choose(down.position.x)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                    if (change == null || !change.pressed) break
                                    choose(change.position.x)
                                    change.consume()
                                }
                                selected = -1
                            }
                        }
                    }
                )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = 6.dp.toPx()
                val plot = (size.height - inset * 2).coerceAtLeast(1f)
                // One gridline, across the middle of the axis. The two figures
                // under the chart already say what the axis covers, so a grid of
                // five would be ruling standing in front of the data.
                val middle = inset + plot / 2
                drawLine(ChartGrid, Offset(0f, middle), Offset(size.width, middle), 1f)

                if (drawn.isEmpty()) return@Canvas
                if (drawn.size == 1) {
                    // One reading is a price, not a history. A dot says that; a
                    // line drawn through a single point would say more.
                    drawCircle(ChartInk, 4.dp.toPx(), Offset(size.width / 2, middle))
                    return@Canvas
                }

                val path = chartPath(drawn, positions, axis, kind, size.width, inset, plot)
                val stroke = Stroke(
                    2.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round
                )
                // What the finger has passed stays lit and what is ahead of it
                // drops back — by opacity, there being no second hue to reach for.
                drawPath(path, ChartInk.copy(alpha = 1f - 0.65f * markerAlpha), style = stroke)
                if (markerAlpha > 0f) {
                    val x = marker * size.width
                    clipRect(right = x.coerceIn(0f, size.width)) {
                        drawPath(path, ChartInk, style = stroke)
                    }
                    drawLine(
                        TextPrimary.copy(alpha = 0.5f * markerAlpha),
                        Offset(x, 0f),
                        Offset(x, size.height),
                        1.dp.toPx()
                    )
                    drawn.getOrNull(selected)?.let { point ->
                        val y = inset + (1f - axis.fraction(point.price)) * plot
                        drawCircle(ChartGround, 6.dp.toPx(), Offset(x, y))
                        drawCircle(Accent.copy(alpha = markerAlpha), 4.dp.toPx(), Offset(x, y))
                    }
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(axisNote(axis, format), note).joinToString(" · "),
                color = TextDisabled,
                fontSize = Type.overlineSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
                style = Tabular
            )
            drawn.getOrNull(selected)?.let { point ->
                Spacer(Modifier.width(Space.sm))
                Text(
                    listOfNotNull(
                        format(point.price),
                        point.day.takeIf { it > 0L }
                            ?.let { dayMonth(LocalDate.ofEpochDay(it)) }
                    ).joinToString(" · "),
                    color = TextPrimary,
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium,
                    maxLines = 1,
                    // The read-out is rebuilt on every reading the finger crosses.
                    // On proportional digits its width changes with the value, and
                    // because it shares a row with a weighted label, that resizes
                    // the label and moves its ellipsis — the whole row breathing
                    // in time with the scrub. Equal-width digits hold it still.
                    style = Tabular
                )
            }
        }
    }
}

/** Everything a range bar needs, each a fraction of the bar's own width. */
data class RangeBarGeometry(
    val marker: Float,
    val bandStart: Float,
    val bandEnd: Float,
    /** Nothing has a span yet, so the bar degrades to a dot rather than to a lie. */
    val single: Boolean
) {
    val hasBand: Boolean get() = !single && bandEnd > bandStart
}

/** Places today's price, and the usual thirty days, along the whole tracked range. */
fun rangeBarGeometry(insight: PriceInsight): RangeBarGeometry {
    val low = insight.lowest
    val span = insight.highest - low
    if (low <= 0.0 || span <= 0.0) return RangeBarGeometry(0.5f, 0f, 0f, single = true)
    fun at(value: Double) = ((value - low) / span).toFloat().coerceIn(0f, 1f)
    val band = insight.referenceLow > 0.0 && insight.referenceHigh > insight.referenceLow
    return RangeBarGeometry(
        marker = at(insight.current),
        bandStart = if (band) at(insight.referenceLow) else 0f,
        bandEnd = if (band) at(insight.referenceHigh) else 0f,
        single = false
    )
}

/**
 * Where today's price sits between the cheapest and the dearest ever recorded,
 * with the usual thirty days shaded behind it.
 *
 * The range bar a stock app draws for a year, and the most a single row of pixels
 * here can say: the low at the left end, the high at the right, a marker at
 * today's proportional position. No axis, legible at any size, and it works on two
 * readings — with one it becomes a dot, because one reading has no range.
 *
 * The band behind it is the thirty-day reference window, the same span the verdict
 * is measured over, shaded the way Google Flights shades a route's usual fare.
 * That is what lets "cheaper than usual" be read off the picture instead of out of
 * a sentence: the marker sits to the left of the band, inside it, or past it.
 */
@Composable
fun PriceRangeBar(
    insight: PriceInsight,
    modifier: Modifier = Modifier,
    format: (Double) -> String = ::money,
    labels: Boolean = true,
    height: Dp = 10.dp
) {
    val geometry = remember(insight) { rangeBarGeometry(insight) }
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(ChartGround, size = size, cornerRadius = radius)
            if (geometry.single) {
                drawCircle(ChartInk, size.height / 2, Offset(size.width / 2, size.height / 2))
                return@Canvas
            }
            if (geometry.hasBand) {
                val left = geometry.bandStart * size.width
                val right = geometry.bandEnd * size.width
                drawRoundRect(
                    HairLine,
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                    cornerRadius = radius
                )
            }
            val width = 4.dp.toPx()
            val half = width / 2
            val x = (geometry.marker * size.width)
                .coerceIn(half, (size.width - half).coerceAtLeast(half))
            drawRoundRect(
                // The one place a brighter step of the same hue earns its keep:
                // the cheapest it has ever been is a different fact from cheap.
                if (insight.atLowest) Accent else ChartInk,
                topLeft = Offset(x - half, 0f),
                size = Size(width, size.height),
                cornerRadius = CornerRadius(half)
            )
        }
        if (labels && !geometry.single) {
            Spacer(Modifier.height(Space.xs))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    format(insight.lowest),
                    color = TextDisabled,
                    fontSize = Type.overlineSize,
                    style = Tabular
                )
                Spacer(Modifier.weight(1f))
                Text(
                    format(insight.highest),
                    color = TextDisabled,
                    fontSize = Type.overlineSize,
                    style = Tabular
                )
            }
        }
    }
}

/** Two series on one scale, each divided by its own first value and set to 100. */
data class RebasedSeries(val price: List<PricePoint>, val rate: List<PricePoint>)

/** Null-ish — both lists empty — until two readings carry a rate to rebase against. */
fun rebasedToHundred(history: List<PricePoint>): RebasedSeries {
    val dated = history.filter { it.price > 0.0 && it.rate > 0.0 }
    if (dated.size < 2) return RebasedSeries(emptyList(), emptyList())
    val basePrice = dated.first().price
    val baseRate = dated.first().rate
    return RebasedSeries(
        price = dated.map { it.copy(price = it.price / basePrice * 100) },
        rate = dated.map { it.copy(price = it.rate / baseRate * 100) }
    )
}

/**
 * The price and the hryvnia's dollar rate on one scale, both set to 100 at the
 * first reading that carried a rate.
 *
 * The chart no shopping app can draw, because none of them keeps the rate that was
 * in force on the day a price was read — [PricePoint] does. Rebasing is the
 * standard way to put two series with different units on one axis, and it beats a
 * second axis down the right-hand side, where the reader has to keep remembering
 * which line belongs to which edge.
 *
 * What it answers is the question the whole app exists for. The price line pulling
 * above the rate line means the thing genuinely got dearer; the two travelling
 * together mean nothing happened except the currency. [currencyMoveNote] says that
 * in a sentence — this is the picture of it.
 *
 * The price is a step and the rate is a plain line, which is not decoration: one is
 * a list of change events and the other a quantity sampled on whichever days a
 * price happened to be read. The two are told apart by that shape and by opacity,
 * never by a second colour.
 */
@Composable
fun RebasedPriceAndRate(
    history: List<PricePoint>,
    modifier: Modifier = Modifier,
    height: Dp = 132.dp
) {
    val series = remember(history) { rebasedToHundred(history) }
    if (series.price.size < 2) return
    val positions = remember(series) { chartPositions(series.price) }
    val axis = remember(series) {
        chartAxis(series.price.map { it.price } + series.rate.map { it.price })
    }
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(height)
                .clip(Radius.sm)
                .background(ChartGround)
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = 6.dp.toPx()
                val plot = (size.height - inset * 2).coerceAtLeast(1f)
                // Everything is measured from a hundred, so that is the one line
                // the grid gets.
                val base = inset + (1f - axis.fraction(100.0)) * plot
                drawLine(ChartGrid, Offset(0f, base), Offset(size.width, base), 1f)
                drawPath(
                    chartPath(
                        series.rate, positions, axis, ChartLine.LINEAR,
                        size.width, inset, plot
                    ),
                    ChartInk.copy(alpha = 0.45f),
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
                drawPath(
                    chartPath(
                        series.price, positions, axis, ChartLine.STEP,
                        size.width, inset, plot
                    ),
                    ChartInk,
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }
        Spacer(Modifier.height(Space.sm))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ChartKey("Ціна", ChartInk)
            Spacer(Modifier.width(Space.md))
            ChartKey("Курс", ChartInk.copy(alpha = 0.45f))
            Spacer(Modifier.weight(1f))
            Text(
                "100 = перше вимірювання",
                color = TextDisabled,
                fontSize = Type.overlineSize,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** One entry of the two-line key. Needed only where opacity carries the meaning. */
@Composable
private fun ChartKey(label: String, colour: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 12.dp, height = 2.dp).background(colour))
        Spacer(Modifier.width(Space.xs))
        Text(label, color = TextSecondary, fontSize = Type.overlineSize)
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
        Text(value, color = color, fontSize = valueSize, fontWeight = Type.strong, style = Tabular)
        target?.let {
            Text(
                " / $it",
                color = TextSecondary,
                fontSize = Type.captionSize,
                modifier = Modifier.padding(bottom = 2.dp),
                style = Tabular
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
        modifier.fillMaxWidth().litEdge(Radius.lg),
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
            fontWeight = Type.strong,
            style = Tabular
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
    // No haptic on these. Correcting a stop is a tap on a row of stops, and the dot
    // springing across to the one you touched is already the answer; a buzz here
    // would be the app reporting contact rather than consequence, and it is exactly
    // the kind of feedback that spends the phone's whole vibration budget on the
    // gestures that needed it least.
    Row(modifier.fillMaxWidth()) {
        stages.forEachIndexed { position, stage ->
            val passed = position < reached
            val here = position == reached
            Column(
                Modifier
                    .weight(1f)
                    .clickable { onPick(stage) }
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
                Modifier.fillMaxWidth().litEdge(Radius.md),
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
                modifier = Modifier.align(Alignment.TopEnd).padding(Space.md),
                style = Tabular
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
        // A dissolve, not a slide. This bar hands over on almost every scroll of
        // almost every screen, which makes it the highest-frequency motion in the
        // app — and it used to slide down from the top while the list it belongs to
        // was travelling up underneath it, two movements crossing during a gesture
        // the finger is still making. The title appearing is the whole message; it
        // does not also need to arrive from somewhere. On the effects spring it is
        // over in about a tenth of a second, and it snaps when the phone asks for
        // no motion.
        enter = fadeIn(Motion.effects()),
        exit = fadeOut(Motion.effects())
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
 * "Working on it", including when the phone has been told to stop animating.
 *
 * This is the one genuine reduced-motion leak in the app, and it is the shape the
 * complaint threads describe: an indeterminate [CircularProgressIndicator] is
 * driven by an `infiniteRepeatable`, and Compose answers a zero animator duration
 * scale by skipping every animation to its end and suspending. So the spinner does
 * not slow down and does not stop — it *freezes*, mid-arc, and stays there for as
 * long as the work runs. A frozen spinner is not a quieter spinner; it is the
 * universal sign that an app has hung, shown at exactly the moment the person is
 * waiting on something and most likely to believe it.
 *
 * The rule for these is that meaningful motion gets replaced rather than deleted,
 * and what this motion means is only "still going". So it becomes a lime dot: the
 * same footprint, in the same place, in the colour the app spends on the thing it
 * wants you to look at, sitting in a button that is disabled while the work runs.
 * It says the same thing with a colour instead of a rotation.
 */
@Composable
fun BusyMark(modifier: Modifier = Modifier, tint: Color = Accent) {
    if (LocalReducedMotion.current) {
        Box(modifier.size(18.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(tint, CircleShape))
        }
    } else {
        CircularProgressIndicator(modifier.size(18.dp), strokeWidth = 2.dp, color = tint)
    }
}

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
        // On the app's own spring rather than the platform default. The default is
        // stiffer and does not overshoot, so this one control was arriving on a
        // curve nothing else in the app uses — and under reduced motion it has to
        // snap along with everything else, which is Motion's job rather than this
        // control's.
        val travel by animateDpAsState(slot * active, Motion.spatial(), label = "segment")
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

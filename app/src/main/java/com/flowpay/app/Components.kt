package com.flowpay.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

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
            Modifier.padding(Space.xl),
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
            Canvas(Modifier.fillMaxWidth().height(1.dp)) {
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
        Text(
            value,
            color = if (alarm) Negative else TextPrimary,
            fontSize = Type.captionSize,
            fontWeight = Type.strong
        )
    }
}

/** A section heading with a lime action on the right, the way "See all" reads. */
@Composable
fun SectionRow(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier.fillMaxWidth().padding(top = Space.xxl, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            fontSize = Type.sectionSize,
            lineHeight = Type.sectionLine,
            fontWeight = Type.medium
        )
        Spacer(Modifier.weight(1f))
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                color = Accent,
                fontSize = Type.captionSize,
                fontWeight = Type.medium,
                modifier = Modifier.clickable(onClick = onAction)
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

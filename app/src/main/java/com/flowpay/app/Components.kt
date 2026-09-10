package com.flowpay.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
 * Nothing else on the same screen may be lime, apart from the navigation
 * indicator. If two of these appear together, both stop working.
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
        colors = CardDefaults.cardColors(
            // A figure that is absent does not deserve the loudest surface on screen.
            containerColor = if (muted) SurfaceRaised else Accent
        ),
        shape = Radius.lg
    ) {
        Row(
            Modifier.padding(Space.xl),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    color = if (muted) TextSecondary else AccentInk.copy(alpha = 0.7f),
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium
                )
                Spacer(Modifier.height(Space.sm))
                Text(
                    value,
                    color = if (muted) TextDisabled else AccentInk,
                    fontSize = Type.heroSize,
                    lineHeight = Type.heroLine,
                    letterSpacing = Type.heroTracking,
                    fontWeight = if (muted) Type.regular else FontWeight.Black
                )
                caption?.let {
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        it,
                        color = if (muted) TextSecondary else AccentInk.copy(alpha = 0.7f),
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
 * One small figure in a bento row.
 *
 * The icon sits in its own chip so a row of these has a rhythm instead of being a
 * wall of text, and the value stays quiet because the lime panel above it is the
 * subject of the screen.
 */
@Composable
fun StatTile(
    icon: ImageVector,
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    muted: Boolean = false,
    alarm: Boolean = false
) {
    Card(
        modifier,
        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
        shape = Radius.md
    ) {
        Column(Modifier.padding(Space.lg)) {
            Box(
                Modifier.size(32.dp).background(SurfaceHigh, Radius.sm),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    icon,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = if (alarm) Negative else TextSecondary
                )
            }
            Spacer(Modifier.height(Space.md))
            Text(
                value,
                color = when {
                    alarm -> Negative
                    muted -> TextDisabled
                    else -> TextPrimary
                },
                fontSize = Type.sectionSize,
                lineHeight = Type.sectionLine,
                fontWeight = if (muted) Type.regular else Type.strong
            )
            Text(
                label,
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
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

/** A row of small tiles that share the width evenly. */
@Composable
fun BentoRow(modifier: Modifier = Modifier, content: @Composable RowScopeHolder.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.md)) {
        RowScopeHolder(this).content()
    }
}

/** Lets [BentoRow] hand out equal weights without leaking Compose scope types. */
class RowScopeHolder(private val scope: androidx.compose.foundation.layout.RowScope) {
    val even: Modifier get() = with(scope) { Modifier.weight(1f) }
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

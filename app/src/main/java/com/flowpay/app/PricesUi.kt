package com.flowpay.app

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlin.math.abs

/**
 * The drawing for PricesMore.kt and RateWatch.kt. Nothing here decides anything:
 * every sentence and every figure comes from those two files.
 */

// ------------------------------------------------------------ the Rozetka card

/**
 * «У мене є Картка Rozetka», under Налаштування.
 *
 * Off until switched on, and with it off nothing about the card appears anywhere —
 * not a line, not a hint that the card would be cheaper. With it on, the wish page
 * shows the card price under the ordinary one and the target is checked against it.
 */
@Composable
fun RozetkaCardRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prices = remember(context) { PriceStore(context) }
    var on by remember { mutableStateOf(prices.rozetkaCard()) }
    val touch = rememberTouch()
    // The same shape as the emoji and update rows beside it.
    ListItem(
        modifier = modifier,
        leadingContent = { EmojiGlyph("💳", 28.dp) },
        headlineContent = { Text("У мене є Картка Rozetka", fontWeight = FontWeight.Bold) },
        supportingContent = {
            Text(
                "Тоді під ціною Rozetka видно ціну з карткою, і ціль перевіряється й за нею. " +
                    "Графік та історія — завжди звичайна ціна.",
                color = TextSecondary
            )
        },
        trailingContent = {
            Switch(on, { checked ->
                on = checked
                prices.saveRozetkaCard(checked)
                touch.switched(checked)
            })
        }
    )
}

/**
 * The inside of a button that shares its row with two others: the stock 24 dp a
 * side left three labels wrapping on a 360 dp phone.
 */
val CompactButtonPadding = PaddingValues(horizontal = Space.sm, vertical = 8.dp)

/**
 * The wish page's buttons: all in one row of equal widths when every label fits its
 * third, otherwise the first two in a row and the rest under them at full width.
 *
 * Measured, not guessed: «Оновити», «Магазин ↗» and «Hotline ↗» fit a 360 dp
 * phone at the ordinary font size, and at a larger one «Оновити» was cut to
 * «Оновиті» — so the row decides by each button's own natural width.
 */
@Composable
fun ActionsRow(gap: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val space = gap.roundToPx()
        val count = measurables.size
        if (count == 0) return@Layout layout(width, 0) {}
        val slot = (width - space * (count - 1)) / count
        val fits = count < 3 || measurables.all { it.maxIntrinsicWidth(constraints.maxHeight) <= slot }
        if (fits) {
            val placed = measurables.map { it.measure(Constraints.fixedWidth(slot.coerceAtLeast(0))) }
            val height = placed.maxOf { it.height }
            layout(width, height) {
                placed.forEachIndexed { index, item ->
                    item.place(index * (slot + space), (height - item.height) / 2)
                }
            }
        } else {
            val half = ((width - space) / 2).coerceAtLeast(0)
            val top = measurables.take(2).map { it.measure(Constraints.fixedWidth(half)) }
            val rest = measurables.drop(2).map { it.measure(Constraints.fixedWidth(width)) }
            val topHeight = top.maxOf { it.height }
            val height = topHeight + rest.sumOf { it.height + space }
            layout(width, height) {
                top.forEachIndexed { index, item -> item.place(index * (half + space), 0) }
                var y = topHeight
                rest.forEach { item ->
                    y += space
                    item.place(0, y)
                    y += item.height
                }
            }
        }
    }
}

/** One quiet line under the price on the wish page. */
@Composable
fun PriceAside(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(top = Space.xs),
        color = TextSecondary,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine
    )
}

// ------------------------------------------------------------ the wish's chart

/** How long a finger rests before it is scrubbing rather than tapping — as in [PriceChart]. */
private const val PICK_HOLD_MS = 100L

/**
 * The hryvnia history on the wish page: [PriceChart]'s step line and scrub, with two
 * things that chart does not do.
 *
 * The line breaks where the thing was sold out ([StockGap]) instead of holding its
 * last price flat across weeks nobody could buy it. And the point a scrub ends on is
 * handed back, so the page can offer «Це був збій» for it. The shared chart in
 * Components.kt stays as it is for the rate screen.
 */
@Composable
fun WishPriceChart(
    points: List<PricePoint>,
    gaps: List<StockGap>,
    modifier: Modifier = Modifier,
    note: String? = null,
    onPick: (PricePoint) -> Unit = {},
    height: Dp = 132.dp
) {
    val drawn = remember(points) { points.filter { it.price > 0.0 } }
    val positions = remember(drawn) { chartPositions(drawn) }
    val axis = remember(drawn) { chartAxis(drawn.map { it.price }) }
    val spans = remember(drawn, gaps) { gapSpans(drawn, gaps) }
    val touch = rememberTouch()
    val pick by rememberUpdatedState(onPick)
    var selected by remember(drawn) { mutableIntStateOf(-1) }
    val marker by animateFloatAsState(
        if (selected >= 0) positions.getOrElse(selected) { 0f } else 0f,
        Motion.spatial(),
        label = "pick position"
    )
    val markerAlpha by animateFloatAsState(
        if (selected >= 0) 1f else 0f,
        Motion.effects(),
        label = "pick fade"
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
                                // A hold, not a press: a tap or a scroll through the
                                // chart still reaches the page underneath it.
                                val holding = try {
                                    withTimeout(PICK_HOLD_MS) { waitForUpOrCancellation() }
                                    false
                                } catch (_: PointerEventTimeoutCancellationException) {
                                    true
                                }
                                if (!holding) return@awaitEachGesture
                                touch.committed()
                                var landing = true
                                val width = size.width.toFloat()
                                fun choose(x: Float) {
                                    if (width <= 0f) return
                                    val target = (x / width).coerceIn(0f, 1f)
                                    var best = 0
                                    positions.forEachIndexed { index, at ->
                                        if (abs(at - target) < abs(positions[best] - target)) best = index
                                    }
                                    if (best != selected) {
                                        selected = best
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
                                // The point the finger left on is offered to the page.
                                drawn.getOrNull(selected)?.let { pick(it) }
                                selected = -1
                            }
                        }
                    }
                )
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val inset = 6.dp.toPx()
                val plot = (size.height - inset * 2).coerceAtLeast(1f)
                val middle = inset + plot / 2
                drawLine(ChartGrid, Offset(0f, middle), Offset(size.width, middle), 1f)
                if (drawn.isEmpty()) return@Canvas
                if (drawn.size == 1) {
                    drawCircle(ChartInk, 4.dp.toPx(), Offset(size.width / 2, middle))
                    return@Canvas
                }
                val path = stepPath(drawn, positions, axis, size.width, inset, plot)
                val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                withoutSpans(spans) {
                    drawPath(path, ChartInk.copy(alpha = 1f - 0.65f * markerAlpha), style = stroke)
                    if (markerAlpha > 0f) {
                        clipRect(right = (marker * size.width).coerceIn(0f, size.width)) {
                            drawPath(path, ChartInk, style = stroke)
                        }
                    }
                }
                if (markerAlpha > 0f) {
                    val x = marker * size.width
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
                listOfNotNull(axisNote(axis, ::money), note).joinToString(" · "),
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
                    pointLabel(point),
                    color = TextPrimary,
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium,
                    maxLines = 1,
                    style = Tabular
                )
            }
        }
        if (spans.isNotEmpty()) {
            Text(
                "Розрив у лінії — товару тоді не було в наявності",
                color = TextDisabled,
                fontSize = Type.overlineSize,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
    }
}

/** The step line: flat until the day it changed, then straight up or down. */
private fun stepPath(
    points: List<PricePoint>,
    positions: List<Float>,
    axis: ChartAxis,
    width: Float,
    top: Float,
    height: Float
): Path {
    val path = Path()
    var lastY = 0f
    points.forEachIndexed { index, point ->
        val x = positions.getOrElse(index) { 0f } * width
        val y = top + (1f - axis.fraction(point.price)) * height
        if (index == 0) {
            path.moveTo(x, y)
        } else {
            path.lineTo(x, lastY)
            path.lineTo(x, y)
        }
        lastY = y
    }
    return path
}

/** Draws [block] with every span cut out of it, one nested clip per span. */
private fun DrawScope.withoutSpans(
    spans: List<ClosedFloatingPointRange<Float>>,
    block: DrawScope.() -> Unit
) {
    val first = spans.firstOrNull() ?: return block()
    clipRect(
        left = first.start * size.width,
        top = 0f,
        right = first.endInclusive * size.width,
        bottom = size.height,
        clipOp = ClipOp.Difference
    ) {
        withoutSpans(spans.drop(1), block)
    }
}

/**
 * The hryvnia chart with what can be done about a point under it: the point a scrub
 * ended on, and «Це був збій» for it — anything but the price standing now.
 */
@Composable
fun WishHistoryChart(wish: Wish, note: String?, onChange: (Wish) -> Unit) {
    var picked by remember(wish.id) { mutableStateOf<PricePoint?>(null) }
    // A pick from before a change is a point that may no longer be there.
    LaunchedEffect(wish.history) { picked = null }
    WishPriceChart(
        points = remember(wish.history, wish.price, wish.checkedDay) {
            chartSeries(wish.history, wish.price, wish.checkedDay)
        },
        gaps = wish.stockGaps,
        note = note,
        onPick = { picked = it }
    )
    picked?.let { point ->
        Row(
            Modifier.fillMaxWidth().padding(top = Space.xs),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                pointLabel(point),
                Modifier.weight(1f),
                color = TextPrimary,
                fontSize = Type.captionSize,
                style = Tabular
            )
            if (canSetAside(wish.history, point)) {
                TextButton({
                    onChange(withoutGlitch(wish, point))
                    picked = null
                }) { Text("Це був збій") }
            } else {
                Text("це поточна ціна", color = TextDisabled, fontSize = Type.captionSize)
            }
            IconButton({ picked = null }, Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, "Сховати", tint = TextDisabled, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ------------------------------------------------------------ the market on Hotline

/**
 * «Ринок: від 1 316 ₴ · 97 магазинів · Hotline» under the price, with «на 283 ₴
 * дешевше» beside it when the wish's shop asks noticeably more. A tap opens the
 * Hotline page, where the shops are named. Nothing at all without a bound market.
 */
@Composable
fun MarketRow(wish: Wish, today: Long, onOpen: (String) -> Unit) {
    val market = wish.market ?: return
    val line = marketLine(market, today) ?: return
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.xs)
            .clip(Radius.sm)
            .clickable(onClickLabel = "Відкрити Hotline") { onOpen(market.url) }
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            line,
            Modifier.weight(1f, fill = false),
            color = TextSecondary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine
        )
        marketChip(wish, today)?.let { chip ->
            Spacer(Modifier.width(Space.sm))
            Text(
                chip,
                Modifier
                    .clip(Radius.pill)
                    .background(SurfaceHigh)
                    .padding(horizontal = Space.sm, vertical = 2.dp),
                color = TextPrimary,
                fontSize = Type.overlineSize,
                fontWeight = Type.strong,
                maxLines = 1
            )
        }
    }
}

/**
 * The market's own row under «Де стежимо»: bound — what it is and «Відв'язати»;
 * not bound — «Прив'язати ринок», which opens the Hotline search with the wish's
 * own query. The page found there comes back through «Поділитися».
 */
@Composable
fun MarketShopRow(wish: Wish, onSearch: () -> Unit, onOpen: (String) -> Unit, onUnbind: () -> Unit) {
    val market = wish.market
    if (market == null) {
        TextButton(onSearch) { Text("Прив'язати ринок") }
        Text(
            "Відкриє пошук на Hotline. Знайдіть там цей товар і поділіться його сторінкою з " +
                "FlowPay — під ціною з'явиться мінімальна ціна по Україні.",
            color = TextSecondary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine
        )
        return
    }
    Row(Modifier.fillMaxWidth().padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clip(Radius.sm)
                .clickable { onOpen(market.url) }
        ) {
            Text("Ринок · Hotline", fontSize = Type.bodySize)
            Text(
                listOfNotNull(
                    market.low.takeIf { it > 0.0 }?.let { "від ${money(it)}" },
                    market.offers.takeIf { it > 0 }?.let { shopsLabel(it) }
                ).joinToString(" · ").ifBlank { "ще не прочитано" },
                color = TextSecondary,
                fontSize = Type.captionSize
            )
        }
        TextButton(onUnbind) { Text("Відв'язати", color = TextSecondary) }
    }
}

/**
 * «Прив'язати як ринок до «X»?» — what sharing a Hotline product page asks.
 *
 * The page is read once, here, so the owner can see its photo and name and check it
 * is the same thing; the wish is preselected by name ([bestWishFor]) and any other
 * can be picked. Nothing is bound until «Прив'язати».
 */
@Composable
fun BindMarketSheet(
    url: String,
    wishes: List<Wish>,
    onClose: () -> Unit,
    onBind: (String, Market) -> Unit
) {
    var reading by remember(url) { mutableStateOf<MarketReading?>(null) }
    var problem by remember(url) { mutableStateOf<String?>(null) }
    var loading by remember(url) { mutableStateOf(true) }
    var chosen by remember(url) { mutableStateOf<String?>(null) }
    val today = remember { java.time.LocalDate.now().toEpochDay() }
    val touch = rememberTouch()
    LaunchedEffect(url) {
        val page = runCatching { pageHtml(url) }.getOrNull()
        val read = page?.let { parseMarket(it) }
        reading = read
        problem = when {
            page == null -> "Hotline не відповів. Поділіться сторінкою ще раз трохи згодом."
            read == null -> "Це не сторінка товару на Hotline. Відкрийте сам товар і поділіться ним."
            else -> null
        }
        chosen = read?.let { bestWishFor(it.name, wishes) }
        loading = false
    }
    val target = wishes.firstOrNull { it.id == chosen }
    FormSheet(
        title = target?.let { "Прив'язати як ринок до «${it.name}»?" } ?: "Прив'язати як ринок?",
        confirmLabel = "Прив'язати",
        confirmEnabled = reading != null && target != null,
        onConfirm = {
            val read = reading
            val id = chosen
            if (read != null && id != null) {
                touch.landed()
                onBind(id, withMarketReading(Market(url), read, today))
            }
        },
        onDismiss = onClose
    ) {
        val read = reading
        when {
            loading -> Row(verticalAlignment = Alignment.CenterVertically) {
                BusyMark()
                Spacer(Modifier.width(Space.sm))
                Text("Читаю сторінку Hotline…", color = TextSecondary, fontSize = Type.captionSize)
            }
            read == null -> Text(
                problem.orEmpty(),
                color = Negative,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
            else -> {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (read.image.isNotBlank()) {
                        AsyncImage(
                            read.image,
                            read.name,
                            Modifier.size(64.dp).clip(Radius.sm).background(SurfaceRaised),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(Modifier.width(Space.md))
                    }
                    Column(Modifier.weight(1f)) {
                        Text(read.name.ifBlank { "Товар на Hotline" }, fontSize = Type.bodySize, maxLines = 2)
                        marketLine(withMarketReading(Market(url), read, today), today)?.let {
                            Text(it, color = TextSecondary, fontSize = Type.captionSize)
                        }
                    }
                }
                Text(
                    "Ціна ринку не замінить ціну бажання і не надсилатиме сповіщень: рядок під " +
                        "ціною, а ранкове зведення скаже, коли ринок дійде до вашої цілі.",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    modifier = Modifier.padding(top = Space.md)
                )
                if (wishes.isEmpty()) {
                    Text(
                        "Спершу додайте бажання з магазину — ринок прив'язується до нього.",
                        color = Negative,
                        fontSize = Type.captionSize,
                        modifier = Modifier.padding(top = Space.md)
                    )
                }
                Spacer(Modifier.height(Space.sm))
                wishes.forEach { wish ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(Radius.sm)
                            .clickable { chosen = wish.id }
                            .padding(vertical = Space.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = chosen == wish.id, onClick = { chosen = wish.id })
                        Text(
                            wish.name,
                            Modifier.weight(1f),
                            fontSize = Type.bodySize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (wish.market != null) {
                            Text(
                                "уже є ринок",
                                color = TextDisabled,
                                fontSize = Type.overlineSize,
                                modifier = Modifier.padding(start = Space.sm)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Under the chart: the points that look like a shop's glitch, asked about rather than
 * dropped, and the ones already set aside, each of which can come back.
 */
@Composable
fun GlitchNotes(wish: Wish, onChange: (Wish) -> Unit) {
    val candidates = remember(wish.history, wish.realPoints) {
        glitchCandidates(wish.history, wish.realPoints)
    }
    var showing by remember(wish.id) { mutableStateOf(false) }
    if (candidates.isNotEmpty()) {
        Text(
            glitchHint(candidates.size),
            color = TextPrimary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            modifier = Modifier.padding(top = Space.md)
        )
        // The point on its own line and the two answers under it: side by side
        // they wrapped the date into two lines on a 360 dp phone.
        candidates.forEach { point ->
            Text(
                pointLabel(point),
                color = TextSecondary,
                fontSize = Type.captionSize,
                style = Tabular,
                modifier = Modifier.padding(top = Space.xs)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton({ onChange(withoutGlitch(wish, point)) }) { Text("Не враховувати") }
                TextButton({ onChange(withRealPoint(wish, point)) }) {
                    Text("Справжня ціна", color = TextSecondary)
                }
            }
        }
    }
    excludedNote(wish.excluded.size)?.let { note ->
        Text(
            "$note · ${if (showing) "сховати" else "показати"}",
            color = TextSecondary,
            fontSize = Type.captionSize,
            modifier = Modifier
                .padding(top = Space.sm)
                .clip(Radius.sm)
                .clickable { showing = !showing }
                .padding(vertical = Space.xs)
        )
        if (showing) {
            wish.excluded.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        pointLabel(item.point),
                        Modifier.weight(1f),
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        style = Tabular
                    )
                    TextButton({ onChange(withGlitchBack(wish, item.point)) }) { Text("Повернути") }
                }
            }
        }
    }
}

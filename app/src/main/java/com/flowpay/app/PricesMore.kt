package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject

/**
 * The second pass over the wishlist's prices, from the ten-app research of
 * 4 October 2026 («додай все»). Every part is a plain function over plain data;
 * the drawing is in PricesUi.kt and the background work in PriceWorker.kt.
 */

// ------------------------------------------------------------ the Rozetka card

/**
 * The programme Rozetka names its card's prices with, as it writes it in the page:
 * `"validForMemberTier": {"@id": "https://rozetka.com.ua/#rozetka-card"}`.
 */
const val ROZETKA_CARD_TIER = "rozetka-card"

fun isRozetkaCard(tier: String): Boolean = tier.contains(ROZETKA_CARD_TIER, ignoreCase = true)

/**
 * The price with the Rozetka card, in hryvnia, or nought.
 *
 * Nought unless the owner has said they hold the card (Налаштування → «У мене є
 * Картка Rozetka»): a finance app has no business advertising a shop's card to
 * someone without one, so without the setting the figure is read and kept but never
 * shown and never counted. Among several shops the cheapest card price of the ones
 * that answered, which in practice is the one Rozetka row.
 */
fun cardPrice(wish: Wish, hasCard: Boolean): Double {
    if (!hasCard) return 0.0
    return wishSources(wish)
        .filter {
            it.freshness == Freshness.OK && it.price > 0.0 &&
                it.memberPrice > 0.0 && isRozetkaCard(it.memberTier)
        }
        .minOfOrNull { it.memberPrice } ?: 0.0
}

/**
 * The price the target is measured against: the card's where it is lower.
 *
 * Only the target. The chart, the history, the verdicts and every other figure stay
 * on the price anybody pays — the card is a condition of one buyer, not the price of
 * the thing.
 */
fun targetBasis(wish: Wish, hasCard: Boolean): Double {
    val card = cardPrice(wish, hasCard)
    return if (card > 0.0 && card < wish.price) card else wish.price
}

/**
 * The dim line under the price: «1 519 ₴ з Карткою Rozetka», or null.
 *
 * Said only where it is cheaper than the price above it — on a wish whose other
 * shop is already below the card price the line would be a figure to ignore — and
 * never on a stale wish, whose price is not one anybody can pay today. When the
 * card reaches a target the ordinary price has not, the line says so.
 */
fun cardLine(wish: Wish, hasCard: Boolean): String? {
    if (isStale(wish.freshness)) return null
    val card = cardPrice(wish, hasCard)
    if (card <= 0.0 || card >= wish.price) return null
    val reached = wish.targetPrice > 0.0 && card <= wish.targetPrice && wish.price > wish.targetPrice
    return "${money(card)} з Карткою Rozetka" + if (reached) " — у межах цілі" else ""
}

/**
 * Whether this reading brought the target within reach with the card, while the
 * ordinary price is still above it.
 *
 * The ordinary crossing is [priceAlertFor]'s and keeps its own wording; this is the
 * other one, and it fires once — on the reading that crosses, measured from the
 * figure the target was last measured against ([targetBasis]), card included.
 */
fun cardTargetReached(previous: Wish, current: Wish, hasCard: Boolean): Boolean {
    val target = previous.targetPrice
    if (!hasCard || target <= 0.0) return false
    if (current.price <= target) return false
    val card = cardPrice(current, true)
    if (card <= 0.0 || card > target) return false
    val before = targetBasis(previous, true)
    return before <= 0.0 || before > target
}

/** The push for [cardTargetReached], saying which price reached it and which did not. */
fun cardTargetText(target: Double, card: Double, regular: Double): String =
    "Досягнуто ціль ${money(target)} — ${money(card)} при оплаті Карткою Rozetka " +
        "(звичайна ${money(regular)})"

// ------------------------------------------------- sharing a link already watched

/**
 * The wish whose page a shared link opens, or null when it is not on the list.
 *
 * Sharing a thing already watched used to answer «вже у списку» and stop there,
 * which is the one moment its history is most wanted: the owner is looking at the
 * shop's price and asking whether it is a good one. Keepa opens the chart; so does
 * this. Matched across every shop of every wish by [sharedLink].
 */
fun wishToOpen(link: SharedLink): String? = (link as? SharedLink.Known)?.wish?.id

/** The word that comes with it, so the jump to the page is not a surprise. */
fun knownShareNote(wish: Wish): String = "«${wish.name}» уже у списку — відкриваю сторінку"

// ------------------------------------------------------------ «Схоже на збій»

/**
 * How far a point has to sit from the prices on both sides of it to look like a
 * shop's glitch rather than a sale: thirty-five per cent, the research's figure.
 * It is a guess about this owner's shops, to be checked against their history.
 */
const val GLITCH_SHARE = 0.35

/**
 * And for how long at most. A day is one or two of the twice-daily checks, and
 * because [appendPrice] writes only a change, a glitch seen twice is still one point
 * — so the point's own length, to the next one, is what is measured.
 */
const val GLITCH_DAYS = 1L

/** How many set-aside points, and points said to be real, a wish keeps. */
const val SET_ASIDE_CAP = 30

/**
 * A point taken out of the history as a shop's glitch.
 *
 * [next] is the point that followed it when it was set aside, so that «Повернути»
 * puts it back exactly there. The day alone cannot: a glitch that lasted one check
 * shares its day with the price that ended it.
 */
data class SetAside(val point: PricePoint, val next: PricePoint? = null)

/** The same reading, by what it said and when — the rate stamped on it is not identity. */
fun samePoint(a: PricePoint, b: PricePoint): Boolean = a.day == b.day && a.price == b.price

/**
 * Points that look like a shop's glitch and that nobody has answered about.
 *
 * A point more than [GLITCH_SHARE] away from the price before it *and* the one after
 * it, on the same side of both — a spike or a dip, not a step — and gone within
 * [GLITCH_DAYS]. The last point is never one: it is the price standing now, and
 * nothing has come after it yet. A real flash sale looks exactly like this, which is
 * why the app only asks; nothing is set aside until the owner says so.
 */
fun glitchCandidates(history: List<PricePoint>, real: List<PricePoint> = emptyList()): List<PricePoint> {
    if (history.size < 3) return emptyList()
    return (1 until history.lastIndex).mapNotNull { index ->
        val before = history[index - 1]
        val point = history[index]
        val after = history[index + 1]
        val priced = before.price > 0.0 && point.price > 0.0 && after.price > 0.0
        val dated = point.day > 0L && after.day > 0L
        val brief = after.day - point.day in 0..GLITCH_DAYS
        if (!priced || !dated || !brief) return@mapNotNull null
        val spike = point.price > before.price && point.price > after.price
        val dip = point.price < before.price && point.price < after.price
        val far = kotlin.math.abs(point.price - before.price) / before.price > GLITCH_SHARE &&
            kotlin.math.abs(point.price - after.price) / after.price > GLITCH_SHARE
        point.takeIf { (spike || dip) && far && real.none { samePoint(it, point) } }
    }
}

/**
 * Whether a point picked on the chart can be set aside: anything but the price
 * standing now, which is the last point and the figure on the wish itself.
 */
fun canSetAside(history: List<PricePoint>, point: PricePoint): Boolean {
    val index = history.indexOfFirst { samePoint(it, point) }
    return index in 0 until history.lastIndex
}

/**
 * The wish with [point] moved out of its history and set aside.
 *
 * Moved rather than marked, and that is what makes every reader stop seeing it at
 * once: the lowest ever, the thirty-day window, the shop's discount check, «новий
 * мінімум», the target hints and «Ти поспішив» all read [Wish.history], and none of
 * them had to be told. The point is kept beside it, so nothing is lost.
 */
fun withoutGlitch(wish: Wish, point: PricePoint): Wish {
    if (!canSetAside(wish.history, point)) return wish
    val index = wish.history.indexOfFirst { samePoint(it, point) }
    val taken = SetAside(wish.history[index], wish.history.getOrNull(index + 1))
    return wish.copy(
        history = wish.history.filterIndexed { at, _ -> at != index },
        excluded = (wish.excluded + taken).takeLast(SET_ASIDE_CAP),
        realPoints = wish.realPoints.filterNot { samePoint(it, point) }
    )
}

/**
 * «Повернути»: the point goes back where it was, and counts as a real price from now
 * on, so the hint does not ask about it again.
 */
fun withGlitchBack(wish: Wish, point: PricePoint): Wish {
    val index = wish.excluded.indexOfFirst { samePoint(it.point, point) }
    if (index < 0) return wish
    val item = wish.excluded[index]
    val anchor = item.next?.let { next -> wish.history.indexOfFirst { samePoint(it, next) } } ?: -1
    val byDay = wish.history.indexOfFirst { it.day > item.point.day }
        .let { if (it < 0) wish.history.size else it }
    // Never after the last point: that is the price standing now, and a glitch put
    // back behind it would read as the current price in the history.
    val at = (if (anchor >= 0) anchor else byDay)
        .coerceAtMost((wish.history.size - 1).coerceAtLeast(0))
    val history = wish.history.toMutableList().apply { add(at, item.point) }
    return wish.copy(
        history = history.takeLast(HISTORY_CAP),
        excluded = wish.excluded.filterIndexed { other, _ -> other != index },
        realPoints = (wish.realPoints.filterNot { samePoint(it, point) } + item.point)
            .takeLast(SET_ASIDE_CAP)
    )
}

/** «Ні, це справжня ціна»: the hint stops asking about this point. */
fun withRealPoint(wish: Wish, point: PricePoint): Wish =
    wish.copy(
        realPoints = (wish.realPoints.filterNot { samePoint(it, point) } + point).takeLast(SET_ASIDE_CAP)
    )

/** "1 точка", "2 точки", "5 точок". */
fun pointsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "точок"
        last == 1 -> "точка"
        last in 2..4 -> "точки"
        else -> "точок"
    }
    return "$count $word"
}

/** The same count as an object: «не враховано 1 точку». */
fun pointsAccusative(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "точок"
        last == 1 -> "точку"
        last in 2..4 -> "точки"
        else -> "точок"
    }
    return "$count $word"
}

/** «1 точка схожа на збій магазину — не враховувати?» */
fun glitchHint(count: Int): String {
    val one = count % 10 == 1 && count % 100 != 11
    return "${pointsLabel(count)} ${if (one) "схожа" else "схожі"} на збій магазину — не враховувати?"
}

/** «1 точку не враховано», the line under the chart that opens the list. */
fun excludedNote(count: Int): String? =
    if (count <= 0) null else "${pointsAccusative(count)} не враховано"

/** One point as the owner reads it: «15 ₴ · 3 жовтня». */
fun pointLabel(point: PricePoint): String =
    listOfNotNull(
        money(point.price),
        point.day.takeIf { it > 0L }?.let { dayMonth(java.time.LocalDate.ofEpochDay(it)) }
    ).joinToString(" · ")

fun setAsideJson(items: List<SetAside>): JSONArray = JSONArray().apply {
    items.forEach { item ->
        put(
            JSONObject().put("p", item.point.price).put("d", item.point.day)
                .put("r", item.point.rate).put("rs", item.point.rateSource)
                .apply { item.next?.let { put("np", it.price).put("nd", it.day) } }
        )
    }
}

fun setAsideOf(array: JSONArray?): List<SetAside> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { o ->
            val point = PricePoint(
                o.optDouble("p", 0.0), o.optLong("d", 0L), o.optDouble("r", 0.0), o.optString("rs")
            )
            val next = if (o.has("np")) PricePoint(o.optDouble("np", 0.0), o.optLong("nd", 0L)) else null
            SetAside(point, next).takeIf { point.price > 0.0 && point.price.isFinite() }
        }
    }
}

fun pointsJson(points: List<PricePoint>): JSONArray = JSONArray().apply {
    points.forEach { put(JSONObject().put("p", it.price).put("d", it.day)) }
}

fun pointsOf(array: JSONArray?): List<PricePoint> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { PricePoint(it.optDouble("p", 0.0), it.optLong("d", 0L)) }
    }.filter { it.price > 0.0 && it.price.isFinite() }
}

// ------------------------------------------------------ the gap while sold out

/**
 * A span the thing could not be bought, from the day it was seen sold out to the day
 * a price came back. [to] is nought while it is still sold out.
 *
 * Recorded so the chart can stop its line there: a step held flat across three
 * weeks of «немає в наявності» says the thing cost that the whole time, and
 * nobody could buy it at anything.
 */
data class StockGap(val from: Long, val to: Long = 0L) {
    val open: Boolean get() = to <= 0L
}

/** How many gaps a wish keeps. */
const val STOCK_GAPS_CAP = 24

/**
 * The gaps after one reading: one opens when the wish is seen sold out, and the open
 * one closes when a price is read again. Unreadable and gone pages change nothing —
 * they say nothing about the shelf.
 */
fun stockGapsAfter(gaps: List<StockGap>, now: Freshness, today: Long): List<StockGap> {
    val open = gaps.lastOrNull()?.takeIf { it.open }
    return when {
        now == Freshness.OUT_OF_STOCK && open == null && today > 0L ->
            (gaps + StockGap(today)).takeLast(STOCK_GAPS_CAP)
        now == Freshness.OK && open != null ->
            gaps.dropLast(1) + open.copy(to = maxOf(today, open.from))
        else -> gaps
    }
}

/**
 * Where each gap falls along a chart of [points], as fractions of its width, or
 * nothing when the chart is not laid out by date — undated points are spaced evenly,
 * and a day would then have no place on it. A gap still open runs to the end.
 */
fun gapSpans(points: List<PricePoint>, gaps: List<StockGap>): List<ClosedFloatingPointRange<Float>> {
    if (points.size < 2 || gaps.isEmpty() || points.any { it.day <= 0L }) return emptyList()
    val first = points.first().day
    val last = points.last().day
    val span = (last - first).toFloat()
    if (span <= 0f) return emptyList()
    return gaps.mapNotNull { gap ->
        val end = if (gap.open) last else minOf(gap.to, last)
        val start = maxOf(gap.from, first)
        if (end <= start) return@mapNotNull null
        ((start - first) / span)..((end - first) / span)
    }
}

fun gapsJson(gaps: List<StockGap>): JSONArray = JSONArray().apply {
    gaps.forEach { put(JSONObject().put("f", it.from).put("t", it.to)) }
}

fun gapsOf(array: JSONArray?): List<StockGap> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let { StockGap(it.optLong("f", 0L), it.optLong("t", 0L)) }
    }.filter { it.from > 0L }
}

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

// ------------------------------------------------- the sheet over the shop's app

/** What a share into the sheet (ShopSheet.kt) turns out to be. */
sealed interface SheetRoute {
    /** A link already watched: its chart and verdict, over the shop. */
    data class Known(val id: String) : SheetRoute

    /** A shop's page not on the list yet: today's price and «Стежити». */
    data class New(val url: String) : SheetRoute

    /** Anything else goes to the app's own router, exactly as before. */
    data object Forward : SheetRoute
}

/**
 * More text than this around the link and it is not a shop's share — a shop puts a
 * title or a slogan beside the address — but a letter or a message with a link in
 * it, which the app's own router reads (a subscription e-mail, a carrier's SMS).
 */
const val SHARE_TEXT_LIMIT = 280

/**
 * Where a share goes: the sheet over the shop, or the app's own router.
 *
 * The sheet takes only a shop's link — one already watched, or a new one — shared
 * with little around it. A parcel number anywhere in the text, a Hotline page (bound
 * as a market in the app), no link at all, or a long text with a link in it all go
 * on to [AppCommand.AddShared] untouched, so every rule that router has, and any it
 * gains, still applies to them.
 */
fun shopSheetRoute(text: String?, wishes: List<Wish>): SheetRoute {
    val body = text.orEmpty()
    if (body.isBlank() || trackingNumberIn(body) != null) return SheetRoute.Forward
    return when (val link = sharedLink(body, wishes)) {
        is SharedLink.Known -> SheetRoute.Known(link.wish.id)
        is SharedLink.New -> when {
            hotlineProductUrl(link.url) != null -> SheetRoute.Forward
            body.trim().length - link.url.length > SHARE_TEXT_LIMIT -> SheetRoute.Forward
            else -> SheetRoute.New(link.url)
        }
        SharedLink.Missing -> SheetRoute.Forward
    }
}

/**
 * What the thing usually cost over the reference window: each price weighted by the
 * days it stood, today's included. Nought when there is nothing dated to weigh.
 *
 * Weighted by time rather than averaged over points, because only changes are
 * recorded: a price that held for four weeks and one that held for an afternoon are
 * one point each, and only one of them is what the thing "usually" cost.
 */
fun usualPrice(history: List<PricePoint>, current: Double, today: Long): Double {
    val points = windowPrices(history, current, today)
    if (points.size < 2) return 0.0
    val opens = today - REFERENCE_WINDOW_DAYS + 1
    var weighted = 0.0
    var days = 0L
    points.zipWithNext { held, next ->
        val from = maxOf(held.day, opens)
        if (next.day > from) {
            weighted += held.price * (next.day - from)
            days += next.day - from
        }
    }
    // Today's price stands for today.
    weighted += points.last().price
    days += 1
    return weighted / days
}

/**
 * «Нижче звичайного на 6%» — the sheet's one-line verdict, or null while the history
 * is too short for the verdict itself to speak ([BuyVerdict.UNKNOWN]).
 */
fun usualLine(insight: PriceInsight, usual: Double): String? {
    if (insight.verdict == BuyVerdict.UNKNOWN || usual <= 0.0 || insight.current <= 0.0) return null
    val difference = (insight.current - usual) / usual * 100
    return when {
        kotlin.math.abs(difference) < 1.0 -> "Звичайна ціна за ${daysLabel(insight.referenceDays)}"
        difference < 0 -> "Нижче звичайного на ${figure(-difference, 0)}%"
        else -> "Вище звичайного на ${figure(difference, 0)}%"
    }
}

/** The target, in the sheet's words. */
fun sheetTargetLine(wish: Wish): String = when {
    wish.targetPrice <= 0.0 -> "Ціль не задано — її можна поставити у FlowPay"
    wish.price > 0.0 && wish.price <= wish.targetPrice && !isStale(wish.freshness) ->
        "Ціль ${money(wish.targetPrice)} — досягнуто"
    wish.price > wish.targetPrice -> "Ціль ${money(wish.targetPrice)} — ще ${money(wish.price - wish.targetPrice)}"
    else -> "Ціль ${money(wish.targetPrice)}"
}

/** How fresh the figure in the sheet is: «перевірено сьогодні», «перевірено 3 жовтня». */
fun checkedLine(checkedDay: Long, today: Long): String? = when {
    checkedDay <= 0L -> null
    checkedDay >= today -> "перевірено сьогодні"
    checkedDay == today - 1 -> "перевірено вчора"
    else -> "перевірено ${dayMonth(java.time.LocalDate.ofEpochDay(checkedDay))}"
}

// ------------------------------------------------- Black Friday in the November recap

/**
 * Whether the price standing on Black Friday ([friday]) was a real cut made for the
 * season — set in November ([seasonStart] on) and below the lowest price of the
 * thirty days before it was set — or null when the app was not watching long enough
 * to say: from thirty days before the season, so any November cut can be checked.
 *
 * The same measure as [discountIsReal], without the shop's crossed-out claim, which
 * is not kept day by day: [lowBeforeCurrent] on the history as it stood that Friday.
 * A price that has not moved since October did not get cheaper for Black Friday,
 * and a price raised in early November and «cut» back is caught as what it is.
 */
fun blackFridayVerdict(history: List<PricePoint>, friday: Long, seasonStart: Long): Boolean? {
    val upTo = history.filter { it.price > 0.0 && it.day in 1..friday }.sortedBy { it.day }
    val standing = upTo.lastOrNull() ?: return null
    if (upTo.first().day > seasonStart - DISCOUNT_WINDOW_DAYS) return null
    if (standing.day < seasonStart) return false
    val low = lowBeforeCurrent(upTo, standing.price, friday) ?: return null
    return standing.price < low
}

/**
 * «Чорна п'ятниця для твого списку: справді подешевшали N з M» — one card in the
 * recap of November, or null in every other month and when nothing on the list was
 * watched long enough to check. Only the shops are judged here, never the owner.
 */
fun blackFridayCard(wishes: List<Wish>, month: String): RecapCard? {
    val start = monthKeyDate(month) ?: return null
    if (start.monthValue != 11) return null
    val friday = blackFriday(start.year).toEpochDay()
    val verdicts = wishes.mapNotNull { blackFridayVerdict(it.history, friday, start.toEpochDay()) }
    if (verdicts.isEmpty()) return null
    val real = verdicts.count { it }
    return RecapCard(
        kind = RecapKind.BLACK_FRIDAY,
        overline = "Чорна п'ятниця для твого списку",
        headline = if (real > 0) {
            "Справді подешевшали: $real з ${verdicts.size}"
        } else {
            "Справжніх знижок: 0 з ${verdicts.size}"
        },
        detail = if (real > 0) {
            "Нижче за найнижчу ціну 30 днів до знижки — за перевірками FlowPay"
        } else {
            "У п'ятницю ніщо не було дешевшим за найнижчу ціну 30 днів до того — за перевірками FlowPay"
        }
    )
}

// ------------------------------------------------------------ the market on Hotline

/**
 * What one Hotline product page says: the cheapest offer in Ukraine, and how many
 * shops are offering. The name and the photograph are for the owner to check the
 * page is the right thing before it is bound; nothing else uses them.
 */
data class MarketReading(
    val low: Double,
    val offers: Int,
    val name: String = "",
    val image: String = ""
)

/** One change of the market, for the line the chart does not draw yet. */
data class MarketPoint(val low: Double, val offers: Int, val day: Long)

/**
 * Hotline's page for the same thing, bound to a wish by the owner.
 *
 * A yardstick, never the price: it does not replace [Wish.price], does not enter the
 * history or the verdicts, and never rings the phone. The cheapest offer on a price
 * comparison site is often an unknown shop or a «під замовлення» listing, so it is
 * shown as what the market asks, not as advice to buy there.
 */
data class Market(
    /** The product page — never the search, which hotline's robots.txt forbids. */
    val url: String,
    val low: Double = 0.0,
    val offers: Int = 0,
    /** Epoch day the page was last read with a price on it. Nought before the first. */
    val day: Long = 0L,
    /** Every change of [low] or [offers], kept from the first day. */
    val history: List<MarketPoint> = emptyList()
)

/** How many market changes a wish keeps, as many as price changes. */
const val MARKET_HISTORY_CAP = HISTORY_CAP

/** Above the market's low by more than this share, and the chip says by how much. */
const val MARKET_GAP_SHARE = 0.05

/** A market reading older than this says its date; older still, and no chip is drawn. */
const val MARKET_STALE_DAYS = 2L

/**
 * Paths on hotline.ua that are never a product page: the search (forbidden to robots
 * by the site), its shop redirects, comparisons, brand and account pages.
 */
private val HOTLINE_NOT_PRODUCT = setOf(
    "sr", "go", "cmp", "brands", "yp", "user", "profile", "cart", "login", "register", "search"
)

/**
 * The product page this address is, normalised, or null when it is not one.
 *
 * Hotline writes a product as a hyphenated section and a slug, with the language
 * before them or not: `/ua/av-naushniki-garnitury/jbl-tune-520bt-black-…/`. A
 * category is a bare section and a subsection (`/ua/av/naushniki-garnitury/`), and
 * the search is `/ua/sr/?q=…`, which the site's robots.txt disallows and which this
 * app therefore never reads. Whatever passes here is still checked on the page
 * itself ([parseMarket]) before anything is bound.
 */
fun hotlineProductUrl(url: String): String? {
    val parsed = runCatching { java.net.URI(url.trim()) }.getOrNull() ?: return null
    val host = parsed.host?.lowercase()?.removePrefix("www.") ?: return null
    if (host != "hotline.ua") return null
    val parts = parsed.rawPath.orEmpty().split('/').filter { it.isNotBlank() }
    val language = parts.firstOrNull()?.takeIf { it == "ua" || it == "ru" }
    val body = if (language != null) parts.drop(1) else parts
    if (body.size < 2) return null
    val section = body[0].lowercase()
    if (section in HOTLINE_NOT_PRODUCT || !section.contains('-')) return null
    return "https://hotline.ua/" + listOfNotNull(language, body[0], body[1]).joinToString("/") + "/"
}

/**
 * The market on a Hotline product page: `AggregateOffer.lowPrice` — the low, never
 * the high — and `offerCount`, in hryvnia. Null on anything that is not a product
 * page with an aggregate offer in it, including a category or a search page.
 */
fun parseMarket(html: String): MarketReading? {
    Regex(
        """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).findAll(html).map { it.groupValues[1].trim() }.forEach { block ->
        val root: Any = runCatching {
            when {
                block.startsWith("[") -> JSONArray(block)
                block.startsWith("{") -> JSONObject(block)
                else -> null
            }
        }.getOrNull() ?: return@forEach
        marketIn(root, depth = 0)?.let { found ->
            return found.copy(image = found.image.ifBlank { jsonLdImage(html) })
        }
    }
    return null
}

private fun marketIn(node: Any?, depth: Int): MarketReading? {
    if (depth > 6) return null
    return when (node) {
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { marketIn(node.opt(it), depth + 1) }
        is JSONObject -> {
            val type = node.opt("@type")?.toString().orEmpty()
            val own = if (type.contains("Product")) aggregateIn(node.opt("offers"))?.let { (low, count) ->
                MarketReading(low, count, cleanProductTitle(node.optString("name")))
            } else {
                null
            }
            own ?: node.keys().asSequence()
                .filter { it != "review" && it != "reviews" && it != "offers" }
                .firstNotNullOfOrNull { marketIn(node.opt(it), depth + 1) }
        }
        else -> null
    }
}

/** The low and the count of an AggregateOffer in hryvnia, or null. */
private fun aggregateIn(offers: Any?): Pair<Double, Int>? = when (offers) {
    is JSONArray -> (0 until offers.length()).firstNotNullOfOrNull { aggregateIn(offers.opt(it)) }
    is JSONObject -> {
        val aggregate = offers.optString("@type").contains("AggregateOffer", ignoreCase = true)
        val money = currencyCode(offers.optString("priceCurrency")).ifBlank { UAH }
        val low = priceNumber(offers.opt("lowPrice")?.toString().orEmpty())
        val count = offers.opt("offerCount")?.toString()?.trim()?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (aggregate && money == UAH && low != null) low to count else null
    }
    else -> null
}

/** The market after one reading: the figures updated, a point kept on any change. */
fun withMarketReading(market: Market, reading: MarketReading, today: Long): Market {
    val last = market.history.lastOrNull()
    val changed = last == null || last.low != reading.low || last.offers != reading.offers
    return market.copy(
        low = reading.low,
        offers = reading.offers,
        day = today,
        history = if (changed) {
            (market.history + MarketPoint(reading.low, reading.offers, today)).takeLast(MARKET_HISTORY_CAP)
        } else {
            market.history
        }
    )
}

/** «Ринок: від 1 316 ₴ · 97 магазинів · Hotline», with its date once it is not fresh. */
fun marketLine(market: Market?, today: Long): String? {
    if (market == null || market.low <= 0.0) return null
    val shops = market.offers.takeIf { it > 0 }?.let { " · ${shopsLabel(it)}" }.orEmpty()
    val age = if (market.day > 0L && today - market.day >= MARKET_STALE_DAYS) {
        ", ${dayMonth(java.time.LocalDate.ofEpochDay(market.day))}"
    } else {
        ""
    }
    return "Ринок: від ${money(market.low)}$shops · Hotline$age"
}

/**
 * «на 283 ₴ дешевше» — beside the market line when the wish's cheapest shop asks
 * more than [MARKET_GAP_SHARE] above the market's low. Silent on a stale wish, whose
 * price nobody can pay, and on a market reading too old to compare with today's.
 */
fun marketChip(wish: Wish, today: Long): String? {
    val market = wish.market ?: return null
    if (market.low <= 0.0 || isStale(wish.freshness) || wish.price <= 0.0) return null
    if (market.day <= 0L || today - market.day > MARKET_STALE_DAYS) return null
    if (wish.price <= market.low * (1 + MARKET_GAP_SHARE)) return null
    return "на ${approxMoney(wish.price - market.low)} дешевше"
}

/**
 * The morning digest's word about markets: «… — на Hotline від 1 316 ₴, у межах
 * цілі 1 350 ₴», once per crossing.
 *
 * [said] is each wish's market low as the previous message saw it — the same
 * memory [recentChange] keeps for prices, for the same reason: a crossing dated
 * yesterday afternoon must still be said this morning, and said only this morning.
 * Silent for a held wish, for a market not read in the last day, and for a wish
 * whose own price is already at its target — that one has been told already.
 */
fun marketTargetLines(wishes: List<Wish>, today: Long, said: Map<String, Double>): List<String> =
    wishes.mapNotNull { wish ->
        val market = wish.market ?: return@mapNotNull null
        val target = wish.targetPrice
        val reached = target > 0.0 && market.low > 0.0 && market.low <= target
        val fresh = market.day > 0L && today - market.day <= 1L
        val ownAlready = !isStale(wish.freshness) && wish.price > 0.0 && wish.price <= target
        val saidBefore = said[wish.id]?.let { it <= target } == true
        if (!reached || !fresh || ownAlready || saidBefore || onHold(wish, today)) return@mapNotNull null
        "${wish.name} — на Hotline від ${money(market.low)}, у межах цілі ${money(target)}"
    }

/** What a digest saw of the markets, for the next one's [marketTargetLines]. */
fun marketSeen(wishes: List<Wish>): Map<String, Double> =
    wishes.mapNotNull { wish -> wish.market?.low?.takeIf { it > 0.0 }?.let { wish.id to it } }.toMap()

/**
 * The wish a shared Hotline page most likely belongs to, or null when nothing in
 * the names matches.
 *
 * Words shared between the page's name and each wish's name and search terms, a
 * model number counting double — "520bt" says more than "black". Only a starting
 * choice; the sheet shows every wish and the owner picks.
 */
fun bestWishFor(name: String, wishes: List<Wish>): String? {
    fun words(text: String): Set<String> = Regex("""[\p{L}\p{N}]{2,}""").findAll(text.lowercase())
        .map { it.value }.toSet()
    val wanted = words(name)
    if (wanted.isEmpty()) return null
    return wishes.map { wish ->
        val shared = wanted.intersect(words(wish.name + " " + wishSearchTerms(wish)))
        wish to shared.sumOf { word -> if (word.any { it.isDigit() }) 2 else 1 }
    }.filter { it.second > 0 }.maxByOrNull { it.second }?.first?.id
}

/** Where the Hotline button on a wish goes: its bound product page, or a search. */
fun hotlineLink(wish: Wish, terms: String = wishSearchTerms(wish)): String =
    wish.market?.url?.takeIf { it.isNotBlank() } ?: hotlineSearch(terms)

fun marketJson(market: Market): JSONObject = JSONObject()
    .put("u", market.url).put("l", market.low).put("n", market.offers).put("d", market.day)
    .put(
        "h",
        JSONArray().apply {
            market.history.forEach { put(JSONObject().put("l", it.low).put("n", it.offers).put("d", it.day)) }
        }
    )

fun marketOf(o: JSONObject?): Market? {
    val url = o?.optString("u")?.takeIf { it.isNotBlank() } ?: return null
    val points = o.optJSONArray("h") ?: JSONArray()
    return Market(
        url = url,
        low = o.optDouble("l", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0,
        offers = o.optInt("n", 0).coerceAtLeast(0),
        day = o.optLong("d", 0L),
        history = (0 until points.length()).mapNotNull { index ->
            points.optJSONObject(index)?.let { MarketPoint(it.optDouble("l", 0.0), it.optInt("n", 0), it.optLong("d", 0L)) }
        }.filter { it.low > 0.0 && it.low.isFinite() }
    )
}

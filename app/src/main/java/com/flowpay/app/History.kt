package com.flowpay.app

import java.time.LocalDate

/**
 * Price history, and the one judgement a price tracker exists to make.
 *
 * The old history was a bare list of numbers with no dates, appended to every
 * twelve hours whether or not the price had moved. Ninety entries therefore held
 * about forty-five days of mostly identical values, the chart was a flat line
 * carrying no information, and nothing on screen could honestly say "over the last
 * month" because nothing knew when any measurement was taken.
 *
 * A point is now recorded only when the price actually changes, with the day it
 * changed. The same storage then covers years rather than weeks, and every claim
 * about time has a date behind it.
 */

/**
 * One observed price and the epoch day it was seen on. Day zero means unknown.
 *
 * The rate travels with the price rather than being looked up later, because the
 * question the dollar chart answers — did this actually get dearer, or did the
 * hryvnia move — cannot be answered by dividing an old price by today's rate.
 * That would show the item tracking the currency perfectly and hide the trend.
 */
data class PricePoint(
    val price: Double,
    val day: Long,
    /** Hryvnia per dollar on the day the price was read. Zero before this was recorded. */
    val rate: Double = 0.0,
    /** [SOURCE_MONOBANK] or [SOURCE_NBU]: the two are not the same number. */
    val rateSource: String = ""
)

/** How many recorded changes to keep. Only changes are stored, so this is a long memory. */
const val HISTORY_CAP = 180

/**
 * Records a price, but only if it differs from the last one recorded.
 *
 * The current price lives on the wish itself, and the date of the last check lives
 * beside it, so an unchanged price needs no entry. That is what keeps the history
 * meaningful instead of a wall of duplicates.
 */
fun appendPrice(
    history: List<PricePoint>,
    price: Double,
    today: Long,
    rate: Double = 0.0,
    rateSource: String = ""
): List<PricePoint> {
    if (price <= 0.0) return history
    val last = history.lastOrNull()
    if (last != null && last.price == price) return history
    return (history + PricePoint(price, today, rate.coerceAtLeast(0.0), rateSource))
        .takeLast(HISTORY_CAP)
}

enum class BuyVerdict {
    /** At or near the cheapest of the reference window. */
    GOOD,

    /** In the lower half of the window's range. */
    FAIR,

    /** Near the top of the window's range: waiting has usually paid off before. */
    POOR,

    /** Too little history to say anything honest. */
    UNKNOWN
}

/**
 * The span a verdict is measured over.
 *
 * Thirty days is the window EU law puts on an announced discount, and it is the
 * right one here for the same reason: it is short enough that a shop cannot
 * establish a high "usual" price by simply waiting, and long enough to contain a
 * normal sale cycle.
 */
const val REFERENCE_WINDOW_DAYS = 30

/**
 * The prices this item actually cost on each day of the trailing window.
 *
 * Only changes are recorded, so the points dated inside the window are not the
 * whole story: a price set a year ago and never touched has no point in the last
 * thirty days at all, yet it is what the item cost on every one of them. The last
 * reading before the window opened is therefore carried in, and the current price
 * closes the window. Undated points — histories written before dates existed —
 * cannot be placed on the axis and are left out.
 */
fun windowPrices(
    history: List<PricePoint>,
    current: Double,
    today: Long,
    window: Int = REFERENCE_WINDOW_DAYS
): List<PricePoint> {
    if (today <= 0L) return emptyList()
    val dated = history.filter { it.price > 0.0 && it.day in 1..today }.sortedBy { it.day }
    val opens = today - window + 1
    val inside = dated.filter { it.day >= opens }
    val carried = dated.lastOrNull { it.day < opens }
    val now = current.takeIf { it > 0.0 }?.let { PricePoint(it, today) }
    return listOfNotNull(carried) + inside + listOfNotNull(now)
}

/** A price the app's own history says a later "discount" should be measured from. */
data class PriorLow(val price: Double, val day: Long)

data class PriceInsight(
    val current: Double,
    /** The cheapest ever recorded, across the whole history. */
    val lowest: Double,
    /** The dearest ever recorded, across the whole history. */
    val highest: Double,
    /** The current price is the lowest yet recorded. */
    val atLowest: Boolean,
    /**
     * Where the current price sits in the reference window's range: zero at the
     * cheapest of the last thirty days, one at the dearest. Lower is better.
     */
    val position: Double,
    /** How far below the highest price of the window the current one is, as a percentage. */
    val offHighest: Double,
    /** Days between the first recorded change and the last check. */
    val daysTracked: Int,
    val changes: Int,
    val verdict: BuyVerdict,
    /** The cheapest the item actually cost during the window. Zero when nothing dates it. */
    val referenceLow: Double = 0.0,
    /** The dearest it cost during the window. Zero when nothing dates it. */
    val referenceHigh: Double = 0.0,
    /** How many days of the window the history actually covers, at most the window itself. */
    val referenceDays: Int = 0,
    /** The current price is at or below the window's low. */
    val atReferenceLow: Boolean = false,
    /**
     * Set only when the history shows the price raised inside the window and then
     * cut: the shop's "was" figure is the raise, and this is the real prior low a
     * discount ought to be measured from. Null whenever that pattern is absent.
     */
    val priorLow: PriorLow? = null
)

/**
 * Judges whether now looks like a good moment to buy.
 *
 * Measured against the cheapest of the trailing thirty days rather than the whole
 * tracked range. Judging against everything ever seen makes the verdict drift: an
 * item watched for a year accumulates one freak low and one freak high, after which
 * every ordinary price sits near the top of that range and reads as "дорого" for
 * ever, while a real fall this week barely moves the needle. Thirty days is also the
 * window EU law puts on an announced discount, for the related reason that a shop
 * can otherwise manufacture a high reference price simply by waiting.
 *
 * Deliberately conservative about saying anything at all: with fewer than two
 * recorded changes or less than a week of watching, there is no basis for an
 * opinion, and inventing one is worse than admitting it.
 */
fun priceInsight(
    history: List<PricePoint>,
    current: Double,
    lastCheckedDay: Long,
    window: Int = REFERENCE_WINDOW_DAYS
): PriceInsight {
    val prices = history.map { it.price }.filter { it > 0.0 } + listOf(current).filter { it > 0.0 }
    if (prices.isEmpty()) {
        return PriceInsight(current, 0.0, 0.0, false, 0.0, 0.0, 0, 0, BuyVerdict.UNKNOWN)
    }
    val lowest = prices.min()
    val highest = prices.max()

    val knownDays = history.map { it.day }.filter { it > 0L }
    val firstDay = knownDays.minOrNull()
    val daysTracked = if (firstDay != null && lastCheckedDay > firstDay) {
        (lastCheckedDay - firstDay).toInt()
    } else {
        0
    }

    val recent = windowPrices(history, current, lastCheckedDay, window)
    // With nothing dated, the window can say nothing, and the whole range is the
    // only thing left to measure against. The verdict below refuses to speak in
    // that case anyway, because daysTracked is then zero.
    val referenceLow = recent.minOfOrNull { it.price } ?: lowest
    val referenceHigh = recent.maxOfOrNull { it.price } ?: highest
    val referenceDays = if (firstDay == null || lastCheckedDay <= 0L) {
        0
    } else {
        minOf(window.toLong(), lastCheckedDay - firstDay + 1).coerceAtLeast(0L).toInt()
    }

    val range = referenceHigh - referenceLow
    val position = if (range > 0.0) ((current - referenceLow) / range).coerceIn(0.0, 1.0) else 0.0
    val offHighest = if (referenceHigh > 0.0) (referenceHigh - current) / referenceHigh * 100 else 0.0

    // A fall is in progress and the window was cheaper than the fall has reached:
    // that, and only that, is the inflate-then-discount pattern worth naming.
    val priorLow = if (current > 0.0 && current < referenceHigh && referenceLow < current) {
        recent.filter { it.price == referenceLow }.maxByOrNull { it.day }
            ?.let { PriorLow(it.price, it.day) }
    } else {
        null
    }

    val verdict = when {
        history.size < 2 || daysTracked < 7 -> BuyVerdict.UNKNOWN
        range == 0.0 -> BuyVerdict.FAIR
        current <= referenceLow || position <= 0.2 -> BuyVerdict.GOOD
        position <= 0.6 -> BuyVerdict.FAIR
        else -> BuyVerdict.POOR
    }

    return PriceInsight(
        current = current,
        lowest = lowest,
        highest = highest,
        atLowest = current <= lowest,
        position = position,
        offHighest = offHighest,
        daysTracked = daysTracked,
        changes = history.size,
        verdict = verdict,
        referenceLow = referenceLow,
        referenceHigh = referenceHigh,
        referenceDays = referenceDays,
        atReferenceLow = current > 0.0 && current <= referenceLow,
        priorLow = priorLow
    )
}

/**
 * What the window is allowed to call itself.
 *
 * A caption reading "за 30 днів" above eleven days of history would be a claim the
 * app cannot support, and it is exactly the kind of quiet overstatement this whole
 * change exists to remove.
 */
fun referenceWindowNote(insight: PriceInsight): String? = when {
    insight.referenceDays <= 0 -> null
    insight.referenceDays >= REFERENCE_WINDOW_DAYS -> "Найнижча за 30 днів ${money(insight.referenceLow)}"
    else -> "Найнижча за ${daysLabel(insight.referenceDays)} ${money(insight.referenceLow)}"
}

/**
 * The sentence to put beside a shop's own discount claim.
 *
 * EU law makes a shop quote the lowest price of the thirty days before a reduction,
 * precisely so that raising a price in order to cut it cannot be sold as a saving.
 * Where the app has watched the whole window itself, it can check the claim, and
 * this is the figure the shop ought to have printed.
 */
fun priorLowNote(insight: PriceInsight): String? {
    val prior = insight.priorLow ?: return null
    return "Магазин рахує знижку від ${money(insight.referenceHigh)}, але за останні " +
        "${daysLabel(insight.referenceDays)} ціна вже була ${money(prior.price)}"
}

/** What to put on the card for each verdict. */
fun verdictLabel(verdict: BuyVerdict): String = when (verdict) {
    BuyVerdict.GOOD -> "Гарний момент"
    BuyVerdict.FAIR -> "Середня ціна"
    BuyVerdict.POOR -> "Дорого зараз"
    BuyVerdict.UNKNOWN -> "Ще збираю дані"
}

/** Ukrainian needs three forms, so "1 днів" has to be impossible. */
fun daysLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "днів"
        last == 1 -> "день"
        last in 2..4 -> "дні"
        else -> "днів"
    }
    return "$count $word"
}

/** Ukrainian plural for recorded price changes. */
fun changesLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "змін"
        last == 1 -> "зміна"
        last in 2..4 -> "зміни"
        else -> "змін"
    }
    return "$count $word"
}

/** When the lowest recorded price was seen, or null if no dated point holds it. */
fun lowestPointDay(history: List<PricePoint>): Long? {
    val dated = history.filter { it.price > 0 && it.day > 0 }
    if (dated.isEmpty()) return null
    val lowest = dated.minOf { it.price }
    return dated.last { it.price == lowest }.day
}

enum class AlertKind {
    NONE,

    /** Crossed the target the user set. Always worth saying. */
    TARGET_REACHED,

    /** A page that had stopped giving a price is giving one again. */
    BACK_IN_STOCK,

    /** Cheaper than it has ever been seen. */
    NEW_LOW,

    /** A meaningful fall that is not a new low. */
    DROP
}

data class PriceAlertDecision(val kind: AlertKind, val notifyPrice: Double)

/** A fall smaller than this since the last alert is noise, not news. */
private const val ALERT_STEP = 0.03

/**
 * Decides whether a new price is worth a notification.
 *
 * Notifying on any decrease at all is what makes price trackers annoying: a price
 * that oscillates by a few hryvnia rings the phone twice a day until the channel
 * gets muted, and then the alert that mattered is missed too. So an alert has to
 * beat the last price already announced by a clear step, and the last announced
 * price is remembered rather than compared against yesterday.
 */
fun priceAlertFor(previous: Wish, newPrice: Double): PriceAlertDecision {
    if (newPrice <= 0.0) return PriceAlertDecision(AlertKind.NONE, previous.notifiedPrice)

    val target = previous.targetPrice
    if (target > 0 && previous.price > target && newPrice <= target) {
        return PriceAlertDecision(AlertKind.TARGET_REACHED, newPrice)
    }

    // Ranked above a new low because it is the rarer fact and the one that expires:
    // a thing back in stock can go out again, whereas a low price is still a low
    // price tomorrow. The price alone would announce nothing, since a page that
    // stopped answering usually resumes at the figure it left off at.
    if (isStale(previous.freshness)) {
        return PriceAlertDecision(AlertKind.BACK_IN_STOCK, newPrice)
    }

    val seen = previous.history.map { it.price }.filter { it > 0 } + previous.price
    val lowestBefore = seen.filter { it > 0 }.minOrNull() ?: previous.price
    if (newPrice < lowestBefore) return PriceAlertDecision(AlertKind.NEW_LOW, newPrice)

    val benchmark = if (previous.notifiedPrice > 0) previous.notifiedPrice else previous.price
    if (newPrice <= benchmark * (1 - ALERT_STEP)) {
        return PriceAlertDecision(AlertKind.DROP, newPrice)
    }

    return PriceAlertDecision(AlertKind.NONE, previous.notifiedPrice)
}

/** How stale the price on screen is, in days, or null when it was checked today. */
fun stalenessDays(checkedDay: Long, today: Long): Int? {
    if (checkedDay <= 0L) return null
    val days = (today - checkedDay).toInt()
    return days.takeIf { it > 0 }
}

// ------------------------------------------------------------ exchange rate

/**
 * How many daily exchange-rate readings to keep.
 *
 * A month, because that is the span the chart claims to show. Kept short on
 * purpose: unlike a price history, which only grows when something changes, this
 * gains a point every single day, so an unbounded list would grow for ever.
 */
const val RATE_HISTORY_CAP = 30

/**
 * Records the day's exchange rate.
 *
 * Deliberately unlike [appendPrice], which skips an unchanged value. A rate chart
 * is a shape over time, and a week where the rate held still is information — drop
 * those days and a flat week would draw as a single bar, making the axis a lie.
 *
 * A second reading on a day already recorded replaces it rather than adding to it,
 * so opening the screen five times in an afternoon still leaves one point per day.
 */
fun appendRate(history: List<PricePoint>, rate: Double, today: Long): List<PricePoint> {
    if (rate <= 0.0 || today <= 0L) return history
    val base = if (history.lastOrNull()?.day == today) history.dropLast(1) else history
    return (base + PricePoint(rate, today)).takeLast(RATE_HISTORY_CAP)
}

/** Ukrainian plural for how many readings the rate history holds. */
fun entriesLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "записів"
        last == 1 -> "запис"
        last in 2..4 -> "записи"
        else -> "записів"
    }
    return "$count $word"
}

/**
 * What the rate chart is allowed to claim about itself.
 *
 * Nothing recorded the rate before this version, so on every existing phone the
 * chart starts empty and fills a bar a day. A chart captioned "за місяць" while
 * holding three days would read as a month in which the rate barely moved, which
 * is the opposite of the truth. So the caption says how much has actually been
 * seen, and on day one says plainly that the history begins now.
 */
fun rateHistoryNote(history: List<PricePoint>, today: Long): String {
    if (history.isEmpty()) return "Історія курсу почнеться з сьогодні"
    val first = history.mapNotNull { point -> point.day.takeIf { it > 0L } }.minOrNull()
        ?: return "${entriesLabel(history.size)} без дат"
    val span = (today - first).toInt()
    if (span <= 0) return "Записую курс щодня, поки що ${entriesLabel(history.size)}"
    return "${entriesLabel(history.size)} за ${daysLabel(span + 1)}"
}

// -------------------------------------------------------- the price in dollars

/**
 * The same history restated in dollars.
 *
 * Points recorded before the rate was travel with the price are dropped rather
 * than converted at today's rate. A year-old hryvnia price divided by this
 * morning's dollar is not what the item cost in dollars then, and a chart drawn
 * that way would show the price tracking the currency exactly — hiding the one
 * trend the second currency exists to reveal.
 */
fun inDollars(history: List<PricePoint>): List<PricePoint> =
    history.filter { it.price > 0.0 && it.rate > 0.0 }
        .map { PricePoint(it.price / it.rate, it.day, it.rate, it.rateSource) }

/**
 * Whether the dollar view has enough behind it to be offered at all.
 *
 * One converted point is a number, not a history: the switch would draw a single
 * bar and say nothing about direction, which is the only thing it is there for.
 */
fun hasDollarHistory(history: List<PricePoint>): Boolean = inDollars(history).size >= 2

/**
 * How far the price moved in each currency over the same two readings.
 *
 * The point of recording the rate. Under a floating hryvnia a price that has not
 * moved has still got cheaper in dollars, and one that "rose 5%" may have done
 * nothing but track the rate. Both figures are measured between the first and last
 * points that carry a rate, so the gap between them is the currency and nothing else.
 */
data class CurrencyMove(
    val fromDay: Long,
    val toDay: Long,
    val hryvniaPercent: Double,
    val dollarPercent: Double
)

/** Null until two readings carry a rate, because one cannot be compared with itself. */
fun currencyMove(history: List<PricePoint>): CurrencyMove? {
    val dated = history.filter { it.price > 0.0 && it.rate > 0.0 }
    val first = dated.firstOrNull() ?: return null
    val last = dated.lastOrNull() ?: return null
    if (first === last) return null
    val firstDollars = first.price / first.rate
    val lastDollars = last.price / last.rate
    return CurrencyMove(
        fromDay = first.day,
        toDay = last.day,
        hryvniaPercent = (last.price - first.price) / first.price * 100,
        dollarPercent = (lastDollars - firstDollars) / firstDollars * 100
    )
}

/**
 * The line that only the two-currency history can write.
 *
 * Said out loud only when the currencies disagree about the direction, or when one
 * moved and the other did not. When both say the same thing the second figure adds
 * nothing, and a caption that always appears stops being read.
 */
fun currencyMoveNote(history: List<PricePoint>): String? {
    val move = currencyMove(history) ?: return null
    val flat = 1.0
    val hryvnia = move.hryvniaPercent
    val dollar = move.dollarPercent
    val sameStory = kotlin.math.abs(hryvnia - dollar) < flat
    if (sameStory) return null
    return "У гривні ${signedPercent(hryvnia)}, у доларі ${signedPercent(dollar)} — " +
        "різницю зробив курс"
}

/** "+5%" / "−3%", with the minus sign Ukrainian typography actually uses. */
fun signedPercent(value: Double, decimals: Int = 1): String {
    val rounded = figure(kotlin.math.abs(value), decimals)
    return if (value < 0) "−$rounded%" else "+$rounded%"
}

/** "41,10 – 41,80" — the span the recorded rate covered, or null while it has not moved. */
fun rateRangeNote(history: List<PricePoint>): String? {
    val prices = history.map { it.price }.filter { it > 0.0 }
    if (prices.size < 2) return null
    val low = prices.min()
    val high = prices.max()
    if (high <= low) return null
    return "${rateFigure(low)} – ${rateFigure(high)}"
}

// ------------------------------------------------------- a rate worth watching

/**
 * A rate the user asked to be told about, and which side it has to come from.
 *
 * The direction is settled once, when the number is set, instead of being worked
 * out afresh every morning. Someone who writes 42 while the dollar sits at 41,30
 * is asking about a rise; the same 42 written while it sits at 42,60 is asking
 * about a fall. Deciding it later, from wherever the rate happens to be that
 * morning, would let a rate that wanders back over the number quietly reverse the
 * question — and then answer the reversed one.
 */
data class RateTarget(
    val rate: Double,
    /** The rate has to rise to it. False when it has to fall to it. */
    val above: Boolean,
    /** Epoch day it was reached and said out loud. Zero while it is still waiting. */
    val hitDay: Long = 0L
)

/**
 * Arms a target at [value], reading the direction off [current].
 *
 * Null without a rate on the phone, because there is then no side to watch from
 * and a guessed direction is a coin toss the user never sees being tossed.
 */
fun armRateTarget(value: Double, current: Double): RateTarget? {
    if (value <= 0.0 || current <= 0.0) return null
    // A number set to the rate it already is counts as a rise, and so reads as
    // reached rather than waiting for ever on a rise that has already happened.
    return RateTarget(value, above = value >= current)
}

/** Whether a target that is still waiting has been reached by [rate]. */
fun rateTargetReached(target: RateTarget?, rate: Double): Boolean {
    if (target == null || target.hitDay > 0L || target.rate <= 0.0 || rate <= 0.0) return false
    return if (target.above) rate >= target.rate else rate <= target.rate
}

/**
 * The same target, stamped with the day it was announced.
 *
 * The number is kept rather than cleared. A target that vanishes the morning it
 * fires leaves no way to tell one that was met from one that was never finished
 * being set, and the user would have to remember which.
 */
fun disarmRateTarget(target: RateTarget, today: Long): RateTarget = target.copy(hitDay = today)

/**
 * Whether the stored rate is current enough to make a claim about right now.
 *
 * The same three days [staleRateNote] uses to start admitting a converted price
 * is standing on an old rate, and deliberately the same constant rather than a
 * second opinion about what "current" means: the two would drift, and then the
 * item page would be calling a rate old while the digest quoted it as today's.
 *
 * A rate stamped in the future is a clock that moved, not a fresh reading, but it
 * is treated as fresh here because [staleRateNote] treats it that way too.
 */
fun rateIsFresh(rateDay: Long, today: Long): Boolean =
    rateDay > 0L && today - rateDay < STALE_RATE_DAYS

/**
 * The digest line for a rate that has crossed the number it was watched for.
 *
 * Null while it is still waiting and null once it has been said, which is the
 * whole of "once": by the morning after, the same rate is no longer news about
 * anything, and a line that repeats daily is how a digest stops being read.
 *
 * Also null on a rate too old to speak for the present. The digest needs no
 * network and runs every morning regardless, so a phone that has been offline for
 * a week would otherwise announce that the dollar "перетнув 42" on the strength of
 * last week's figure — and spend the one telling this threshold gets on it. Going
 * quiet costs nothing instead: the target stays armed and says it properly on the
 * first morning there is a real rate to say it about.
 */
fun rateTargetLine(target: RateTarget?, rate: Double, rateDay: Long, today: Long): String? {
    if (target == null || !rateTargetReached(target, rate)) return null
    if (!rateIsFresh(rateDay, today)) return null
    return "Долар ${rateFigure(rate)} ₴ — курс перетнув ${rateFigure(target.rate)}"
}

/**
 * What the rate screen says about the target, under the chart.
 *
 * A target that has been reached but not yet announced says so, because the
 * alternative is a screen showing the rate past the number while the phone has
 * said nothing, which reads as the feature being broken rather than as a message
 * that is due at nine.
 */
fun rateTargetNote(target: RateTarget?, rate: Double): String = when {
    target == null -> "Скажу один раз, коли курс дійде до вашого числа"
    target.hitDay > 0L ->
        "${rateFigure(target.rate)} — досягнуто ${formatDate(LocalDate.ofEpochDay(target.hitDay))}"
    rateTargetReached(target, rate) -> "${rateFigure(target.rate)} — уже досягнуто, скажу вранці"
    target.above -> "Скажу, коли курс підніметься до ${rateFigure(target.rate)}"
    else -> "Скажу, коли курс опуститься до ${rateFigure(target.rate)}"
}

package com.flowpay.app

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

/** One observed price and the epoch day it was seen on. Day zero means unknown. */
data class PricePoint(val price: Double, val day: Long)

/** How many recorded changes to keep. Only changes are stored, so this is a long memory. */
const val HISTORY_CAP = 180

/**
 * Records a price, but only if it differs from the last one recorded.
 *
 * The current price lives on the wish itself, and the date of the last check lives
 * beside it, so an unchanged price needs no entry. That is what keeps the history
 * meaningful instead of a wall of duplicates.
 */
fun appendPrice(history: List<PricePoint>, price: Double, today: Long): List<PricePoint> {
    if (price <= 0.0) return history
    val last = history.lastOrNull()
    if (last != null && last.price == price) return history
    return (history + PricePoint(price, today)).takeLast(HISTORY_CAP)
}

enum class BuyVerdict {
    /** At or near the cheapest this has been seen. */
    GOOD,

    /** In the lower half of the observed range. */
    FAIR,

    /** Near the top of the range: waiting has usually paid off before. */
    POOR,

    /** Too little history to say anything honest. */
    UNKNOWN
}

data class PriceInsight(
    val current: Double,
    val lowest: Double,
    val highest: Double,
    /** The current price is the lowest yet recorded. */
    val atLowest: Boolean,
    /**
     * Where the current price sits in the observed range: zero at the cheapest ever
     * seen, one at the dearest. Lower is better.
     */
    val position: Double,
    /** How far below the highest observed price the current one is, as a percentage. */
    val offHighest: Double,
    /** Days between the first recorded change and the last check. */
    val daysTracked: Int,
    val changes: Int,
    val verdict: BuyVerdict
)

/**
 * Judges whether now looks like a good moment to buy.
 *
 * Deliberately conservative about saying anything at all: with fewer than two
 * recorded changes or less than a week of watching, there is no basis for an
 * opinion, and inventing one is worse than admitting it.
 */
fun priceInsight(
    history: List<PricePoint>,
    current: Double,
    lastCheckedDay: Long
): PriceInsight {
    val prices = history.map { it.price }.filter { it > 0.0 } + listOf(current).filter { it > 0.0 }
    if (prices.isEmpty()) {
        return PriceInsight(current, 0.0, 0.0, false, 0.0, 0.0, 0, 0, BuyVerdict.UNKNOWN)
    }
    val lowest = prices.min()
    val highest = prices.max()
    val range = highest - lowest
    val position = if (range > 0.0) ((current - lowest) / range).coerceIn(0.0, 1.0) else 0.0
    val offHighest = if (highest > 0.0) (highest - current) / highest * 100 else 0.0

    val knownDays = history.map { it.day }.filter { it > 0L }
    val firstDay = knownDays.minOrNull()
    val daysTracked = if (firstDay != null && lastCheckedDay > firstDay) {
        (lastCheckedDay - firstDay).toInt()
    } else {
        0
    }

    val verdict = when {
        history.size < 2 || daysTracked < 7 -> BuyVerdict.UNKNOWN
        range == 0.0 -> BuyVerdict.FAIR
        current <= lowest || position <= 0.2 -> BuyVerdict.GOOD
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
        verdict = verdict
    )
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

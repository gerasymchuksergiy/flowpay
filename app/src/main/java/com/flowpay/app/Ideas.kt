package com.flowpay.app

import java.time.LocalDate
import kotlin.random.Random

/**
 * Three ideas of the owner's "absolutely new, not like everyone else" round,
 * 4 October 2026. Pure logic here; the tiles are in MainActivity.kt.
 *
 * - **Фінансова погода** — the next seven days as a forecast: the money leaving
 *   on each day read as weather, so a rainy Wednesday is seen on Monday.
 * - **Подарунок собі** — instead of telling you what not to buy, the app names
 *   the one wish you could buy now without hurting the month.
 * - **Дуель бажань** — two wishes side by side, tap the one you want more; the
 *   list learns what you actually want, and what keeps losing gets a gentle hint.
 */

// ------------------------------------------------------------ money weather

/** One day of the forecast. */
data class MoneyDay(
    val date: LocalDate,
    val emoji: String,
    /** «ясно», «дрібниці», «дощ», «гроза». */
    val word: String,
    /** What leaves that day, in hryvnias at the sell rate. Nought on a clear day. */
    val leaving: Double,
    /** What leaves, by name, for the sentence under the strip. */
    val names: List<String>
)

/** The four kinds of weather, mildest first. */
enum class MoneySky(val emoji: String, val word: String) {
    CLEAR("☀️", "ясно"),
    BREEZE("🌤️", "дрібниці"),
    RAIN("🌧️", "дощ"),
    STORM("⛈️", "гроза")
}

/**
 * How heavy a day's charge is.
 *
 * Measured against the month's income, because 500 ₴ is a drizzle on one salary
 * and a storm on another; without an income there is nothing to measure against,
 * so fixed amounts stand in. A charge the free money cannot cover is a storm
 * whatever its size.
 */
fun moneySky(leaving: Double, income: Double, free: Double): MoneySky {
    if (leaving <= 0.0) return MoneySky.CLEAR
    // The month does not fit: every charge in it is a storm.
    if (income > 0.0 && free < 0.0) return MoneySky.STORM
    return if (income > 0.0) {
        when {
            leaving <= income * 0.03 -> MoneySky.BREEZE
            leaving <= income * 0.2 -> MoneySky.RAIN
            else -> MoneySky.STORM
        }
    } else {
        when {
            leaving <= 500.0 -> MoneySky.BREEZE
            leaving <= 5_000.0 -> MoneySky.RAIN
            else -> MoneySky.STORM
        }
    }
}

/**
 * The next [days] days from [today], each with what is still to be paid on it.
 *
 * A charge already marked paid for its month is gone from the sky — the same
 * rule as the reminders, so the forecast and the pill never disagree. A free
 * trial's renewal costs nothing and does not rain.
 */
fun moneyWeather(
    items: List<Pay>,
    marks: List<PaidMark>,
    today: LocalDate,
    usdSell: Double,
    income: Double,
    free: Double,
    days: Int = 7,
    /**
     * What a fund already holds of an annual charge in these days, by payment
     * name — see [weatherCover]. Money put aside months ago is not money leaving
     * this week, so the covered part does not rain a second time.
     */
    covered: Map<String, Double> = emptyMap()
): List<MoneyDay> = (0 until days).map { offset ->
    val date = today.plusDays(offset.toLong())
    val due = chargedOn(items, date).filterNot { isPaid(marks, it.name, monthKey(date)) }
    val leaving = due.sumOf {
        val uah = if (it.currency == USD) it.amount * usdSell.coerceAtLeast(0.0) else it.amount
        (uah - (covered[it.name] ?: 0.0)).coerceAtLeast(0.0)
    }
    val sky = moneySky(leaving, income, free)
    MoneyDay(date, sky.emoji, sky.word, leaving, due.map { it.name })
}

private val WEEKDAYS_SHORT = listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Нд")

/** A day's amount in a seventh of the screen: «399 ₴», «1,8 тис», «11 тис». */
fun shortMoney(value: Double): String = when {
    value < 1_000.0 -> money(kotlin.math.round(value))
    value < 10_000.0 -> figure(value / 1_000.0, 1) + " тис"
    else -> figure(value / 1_000.0, 0) + " тис"
}

/** «Пн», «Вт» … for the strip. */
fun weekdayShort(date: LocalDate): String = WEEKDAYS_SHORT[date.dayOfWeek.value - 1]

/** The sentence under the strip: the worst day, or that the week is clear. */
fun weatherLine(days: List<MoneyDay>, today: LocalDate): String {
    val worst = days.filter { it.leaving > 0.0 }.maxByOrNull { it.leaving }
        ?: return "Тиждень ясний — нічого не списується"
    val whenText = when (worst.date) {
        today -> "Сьогодні"
        today.plusDays(1) -> "Завтра"
        else -> "${weekdayShort(worst.date)}, ${dayMonth(worst.date)}"
    }
    val what = worst.names.take(2).joinToString(", ") + if (worst.names.size > 2) " та ін." else ""
    return "$whenText ${worst.word}: $what, ${approxMoney(worst.leaving)}"
}

// ------------------------------------------------------------ a treat

/** The month's suggestion: what you could buy, and what would still be left. */
data class Treat(
    val wish: Wish,
    /** Free money after the plans and after this, never below nought. */
    val leftAfter: Double,
    /** Why this one: a fallen price or a reached target. Empty when it simply fits. */
    val reason: String
)

/**
 * The one wish that could be bought now without hurting the month, or null.
 *
 * It has to fit into what is free after the savings plans, with a fifth of that
 * still left over — a suggestion that empties the month is not a treat. Among
 * those, the one furthest below its own highest recorded price wins, because
 * that is the honest sense of "now is a good time"; a reached target counts as
 * the best deal there is. Held and stale wishes are never suggested.
 */
fun monthTreat(wishes: List<Wish>, available: Double, today: Long): Treat? {
    if (available <= 0.0) return null
    val ceiling = available * 0.8
    val pick = wishes
        .filter { hasReadablePrice(it) && !onHold(it, today) && !isStale(it.freshness) && it.price <= ceiling }
        .minWithOrNull(
            compareBy<Wish>({ if (targetHit(it, today)) 0 else 1 }, { it.price / highestSeen(it) }, { addedDay(it) })
        ) ?: return null
    val high = highestSeen(pick)
    val reason = when {
        targetHit(pick, today) -> "ціна дійшла до вашої цілі"
        pick.price < high * 0.99 -> "на ${percentLabel((1 - pick.price / high) * 100)} дешевше за найвищу ціну"
        else -> ""
    }
    return Treat(pick, (available - pick.price).coerceAtLeast(0.0), reason)
}

/** The highest price this wish has been seen at, its current price included. */
private fun highestSeen(wish: Wish): Double =
    (wish.history.map { it.price } + wish.price).filter { it > 0.0 }.maxOrNull() ?: wish.price

private fun percentLabel(value: Double): String = "${kotlin.math.round(value).toInt()}%"

// ------------------------------------------------------------ the duel

/**
 * How much a wish is wanted, from its duels: wins over duels, pulled towards a
 * half until it has been in a few (so one win is not "loved", one loss not "dead").
 */
fun duelRating(wish: Wish): Double = (wish.duelWins + 1.0) / (wish.duelsPlayed + 2.0)

/** The wishes a duel can use: priced, not put aside. */
fun duelPool(wishes: List<Wish>, today: Long): List<Wish> =
    wishes.filter { hasReadablePrice(it) && !onHold(it, today) }

/**
 * The next two to put side by side.
 *
 * The least-played wish first, so everything gets its turn; its opponent the one
 * closest to it in rating among the less-played, because a duel between a
 * favourite and a forgotten thing teaches nothing. Never the pair just shown.
 */
fun nextDuel(pool: List<Wish>, last: Pair<String, String>?, random: Random = Random.Default): Pair<Wish, Wish>? {
    if (pool.size < 2) return null
    val shuffled = pool.shuffled(random)
    val first = shuffled.minBy { it.duelsPlayed }
    val rating = duelRating(first)
    val second = shuffled
        .filter { it.id != first.id }
        .filterNot { last != null && setOf(it.id, first.id) == setOf(last.first, last.second) }
        .minWithOrNull(compareBy({ it.duelsPlayed / 3 }, { kotlin.math.abs(duelRating(it) - rating) }))
        ?: shuffled.first { it.id != first.id }
    return if (random.nextBoolean()) first to second else second to first
}

/** The list after a duel: one more win for [winner], one more duel for both. */
fun afterDuel(wishes: List<Wish>, winner: String, loser: String): List<Wish> = wishes.map {
    when (it.id) {
        winner -> it.copy(duelWins = it.duelWins + 1, duelsPlayed = it.duelsPlayed + 1)
        loser -> it.copy(duelsPlayed = it.duelsPlayed + 1)
        else -> it
    }
}

/** What a round of duels came to. */
data class DuelResult(
    /** Most wanted first, at most three. */
    val top: List<Wish>,
    /** The one that keeps losing, worth a second thought. Null when nothing does. */
    val fading: Wish?
)

/** At least this many duels before a wish can be called fading. */
const val FADING_AFTER = 4

fun duelResult(wishes: List<Wish>): DuelResult {
    val played = wishes.filter { it.duelsPlayed > 0 }
    val top = played.sortedWith(compareByDescending<Wish> { duelRating(it) }.thenByDescending { it.duelsPlayed }).take(3)
    val fading = played
        .filter { it.duelsPlayed >= FADING_AFTER && it.duelWins * 4 <= it.duelsPlayed }
        .minByOrNull { duelRating(it) }
    return DuelResult(top, fading)
}

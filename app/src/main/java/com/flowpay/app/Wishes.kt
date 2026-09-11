package com.flowpay.app

/**
 * Ordering a wishlist, and the two derived numbers the ordering depends on.
 *
 * Both were being recomputed inline on the cards, which meant the list could
 * plausibly sort by one definition of "price drop" and label the card with
 * another. They live here once.
 */

enum class WishSort(val label: String) {
    ADDED("За додаванням"),
    BIGGEST_DROP("Найбільше падіння"),
    CHEAPEST("Дешевші"),
    DEAREST("Дорожчі"),
    CLOSEST("Ближче до цілі")
}

/**
 * How far the price has moved since the first measurement, as a percentage.
 * Negative means it got cheaper, which is the direction worth noticing.
 */
fun priceChangePercent(wish: Wish): Double {
    val first = wish.history.firstOrNull()?.price ?: wish.price
    return if (first > 0) (wish.price - first) / first * 100 else 0.0
}

/**
 * How the watched list as a whole has moved since tracking began.
 *
 * A single card says what one thing did. This answers the question underneath
 * all of them: is waiting working out, and by how much money.
 */
data class PriceMovement(
    /** Wishes with at least two measurements. Nothing else can have moved. */
    val tracked: Int,
    val cheaper: Int,
    val dearer: Int,
    val steady: Int,
    /** What the tracked wishes cost when each was first seen. */
    val firstTotal: Double,
    val nowTotal: Double,
    /** Negative means the list got cheaper. */
    val change: Double,
    val changePercent: Double,
    val biggestDropName: String?,
    val biggestDropPercent: Double,
    val biggestRiseName: String?,
    val biggestRisePercent: Double
)

// Half a kopeck: below this a price has not really moved, it was rounded.
private const val MOVEMENT_EPSILON = 0.005

fun priceMovement(wishes: List<Wish>): PriceMovement {
    val tracked = wishes.filter { it.history.size > 1 && it.price > 0 }
    val firstTotal = tracked.sumOf { it.history.first().price }
    val nowTotal = tracked.sumOf { it.price }
    val change = nowTotal - firstTotal

    val moves = tracked.map { it to priceChangePercent(it) }
    val fallen = moves.filter { it.second < 0 }
    val risen = moves.filter { it.second > 0 }
    val drop = fallen.minByOrNull { it.second }
    val rise = risen.maxByOrNull { it.second }

    return PriceMovement(
        tracked = tracked.size,
        cheaper = tracked.count { it.price < it.history.first().price - MOVEMENT_EPSILON },
        dearer = tracked.count { it.price > it.history.first().price + MOVEMENT_EPSILON },
        steady = tracked.count {
            kotlin.math.abs(it.price - it.history.first().price) <= MOVEMENT_EPSILON
        },
        firstTotal = firstTotal,
        nowTotal = nowTotal,
        change = change,
        changePercent = if (firstTotal > 0) change / firstTotal * 100 else 0.0,
        biggestDropName = drop?.first?.name,
        biggestDropPercent = drop?.second ?: 0.0,
        biggestRiseName = rise?.first?.name,
        biggestRisePercent = rise?.second ?: 0.0
    )
}

/** Ukrainian plural for how many wishes are being watched. */
fun positionsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "позицій"
        last == 1 -> "позиція"
        last in 2..4 -> "позиції"
        else -> "позицій"
    }
    return "$count $word"
}

/** The sum this wish is actually aiming at: the target when set, else the price. */
fun wishGoal(wish: Wish): Double = if (wish.targetPrice > 0) wish.targetPrice else wish.price

/** Share of the goal already saved, 0..1. */
fun wishProgress(wish: Wish): Double {
    val goal = wishGoal(wish)
    return if (goal > 0) (wish.saved / goal).coerceIn(0.0, 1.0) else 0.0
}

/**
 * Sorts without ever dropping or duplicating an item. Every branch is a stable
 * sort of the same list, so equal items keep the order they were added in.
 */
fun sortWishes(items: List<Wish>, sort: WishSort): List<Wish> = when (sort) {
    WishSort.ADDED -> items
    WishSort.BIGGEST_DROP -> items.sortedBy { priceChangePercent(it) }
    WishSort.CHEAPEST -> items.sortedBy { it.price }
    WishSort.DEAREST -> items.sortedByDescending { it.price }
    WishSort.CLOSEST -> items.sortedByDescending { wishProgress(it) }
}

/** Reads a stored sort name back, falling back to the default if it is unknown. */
fun wishSortFrom(name: String): WishSort =
    WishSort.entries.firstOrNull { it.name == name } ?: WishSort.ADDED

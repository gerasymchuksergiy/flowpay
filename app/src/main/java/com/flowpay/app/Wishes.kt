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
    val first = wish.history.firstOrNull() ?: wish.price
    return if (first > 0) (wish.price - first) / first * 100 else 0.0
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

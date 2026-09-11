package com.flowpay.app

import java.time.LocalDate

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

/** What a message shared into the app turned out to hold. */
sealed interface SharedLink {
    data class New(val url: String) : SharedLink

    /** The same address is already being watched, and a second copy would split its history. */
    data class Known(val wish: Wish) : SharedLink

    data object Missing : SharedLink
}

fun sharedLink(text: String?, existing: List<Wish>): SharedLink {
    val url = extractUrl(text) ?: return SharedLink.Missing
    val already = existing.firstOrNull { linkKey(it.url) == linkKey(url) }
    return if (already != null) SharedLink.Known(already) else SharedLink.New(url)
}

/**
 * The part of an address that identifies the product.
 *
 * Sharing the same item from the app and from the browser produces two strings
 * that differ only in scheme, case or a trailing slash, and a fragment is never
 * anything but a scroll position.
 */
private fun linkKey(url: String): String = url.trim().lowercase()
    .substringBefore('#')
    .removePrefix("https://")
    .removePrefix("http://")
    .trimEnd('/')

/**
 * The wish to keep when a shared page cannot be read.
 *
 * A shop that blocks the fetch, or answers with a consent wall, must not cost
 * the link: a row with no price can be refreshed later, a lost address cannot.
 */
fun placeholderWish(url: String, id: String): Wish =
    Wish(id = id, name = placeholderName(url), url = url, image = "", price = 0.0, history = emptyList())

data class RefreshResult(val wishes: List<Wish>, val updated: Int)

/**
 * Folds freshly read pages back into the list.
 *
 * [fetched] lines up with [items] by position, with null where the page could
 * not be read, so a shop being down leaves that wish exactly as it was rather
 * than dropping it or zeroing its price.
 */
/**
 * Folds already-followed readings back into the list.
 *
 * The sibling of [applyRefresh] for the path that resolves each wish's own
 * variant before merging: the work is done by then, so a non-null entry replaces
 * its wish outright, and a null — an unreadable page or a vanished variant —
 * leaves it exactly as it was.
 */
fun applyFollowed(items: List<Wish>, refreshed: List<Wish?>): RefreshResult {
    var updated = 0
    val next = items.mapIndexed { index, previous ->
        val current = refreshed.getOrNull(index) ?: return@mapIndexed previous
        updated++
        current
    }
    return RefreshResult(next, updated)
}

fun applyRefresh(
    items: List<Wish>,
    fetched: List<Wish?>,
    today: Long = LocalDate.now().toEpochDay()
): RefreshResult {
    var updated = 0
    val next = items.mapIndexed { index, previous ->
        val current = fetched.getOrNull(index) ?: return@mapIndexed previous
        updated++
        refreshedWish(previous, current, today)
    }
    return RefreshResult(next, updated)
}

/**
 * What to tell the user after a price run.
 *
 * The count is introduced by a colon rather than run into the sentence, because
 * "оновлено" governs the accusative while [positionsLabel] is nominative, and
 * "оновлено 1 позиція" is the sort of wrong that a phone reads out loud.
 */
fun refreshMessage(updated: Int, total: Int): String = when {
    total == 0 -> "Немає чого оновлювати"
    updated == 0 -> "Жодної ціни не вдалося прочитати"
    updated == total -> "Ціни оновлено: ${positionsLabel(total)}"
    else -> "Ціни оновлено: $updated з $total"
}

package com.flowpay.app

import java.time.LocalDate

/**
 * Ordering a wishlist, and the two derived numbers the ordering depends on.
 *
 * Both were being recomputed inline on the cards, which meant the list could
 * plausibly sort by one definition of "price drop" and label the card with
 * another. They live here once.
 */

/**
 * How much the price on a card is currently worth believing.
 *
 * Without this a 404, a redesigned shop or an out-of-stock page leaves the last
 * known figure sitting on the card looking exactly like a price read this morning,
 * and the app goes on offering a buy verdict about a number nobody can buy at. The
 * states are the outcomes the fetch can actually distinguish, no more: anything
 * finer would be guesswork dressed as a diagnosis.
 */
enum class Freshness {
    /** The page was read and the followed price was on it. */
    OK,

    /** The page answered, but states no price at all: redesigned, or a wall. */
    UNREADABLE,

    /** The page still lists prices, but not the variant being followed. */
    OUT_OF_STOCK,

    /** The address itself no longer answers: the shop dropped the page. */
    GONE,

    /** Typed in by hand. Nothing is being read, and nothing is claimed to be. */
    MANUAL
}

/**
 * Whether the price behind a card is too doubtful to judge or to sort on.
 *
 * A hand-typed price is not stale: it is exactly as current as the person who
 * typed it made it, and it is the deliberate fallback for a page that cannot be
 * read at all.
 */
fun isStale(freshness: Freshness): Boolean = when (freshness) {
    Freshness.OK, Freshness.MANUAL -> false
    Freshness.UNREADABLE, Freshness.OUT_OF_STOCK, Freshness.GONE -> true
}

/** The short form for a card, or null when there is nothing to warn about. */
fun freshnessLabel(freshness: Freshness): String? = when (freshness) {
    Freshness.OK -> null
    Freshness.UNREADABLE -> "Ціну не розпізнано"
    Freshness.OUT_OF_STOCK -> "Немає в наявності"
    Freshness.GONE -> "Сторінки більше немає"
    Freshness.MANUAL -> "Ціна вручну"
}

/** The whole sentence for the item page, which has the room to explain itself. */
fun freshnessNote(freshness: Freshness): String? = when (freshness) {
    Freshness.OK -> null
    Freshness.UNREADABLE ->
        "На сторінці більше немає ціни, яку вдається прочитати. Показана остання відома."
    Freshness.OUT_OF_STOCK ->
        "Магазин більше не вказує цей варіант. Показана остання відома ціна."
    Freshness.GONE ->
        "Сторінка не відповідає. Показана остання відома ціна."
    Freshness.MANUAL ->
        "Ціну вказано вручну, тож автоматично вона не оновлюється."
}

/** Reads a stored freshness back, defaulting to the state old data was saved in. */
fun freshnessFrom(name: String): Freshness =
    Freshness.entries.firstOrNull { it.name == name } ?: Freshness.OK

/**
 * What one re-read of a page meant for the wish behind it.
 *
 * Three outcomes rather than two, for the same reason [OfferMatch] has three: a
 * page that answered with nothing usable is a fact about the item and belongs on
 * the card, while a dropped connection is a fact about the phone and must change
 * nothing at all. Collapsing them would let a tunnel turn a whole wishlist grey.
 */
sealed interface Reading {
    /** A price was read, and it is the one this wish follows. */
    data class Priced(val wish: Wish) : Reading

    /** The page answered without a usable price. The wish carries which kind. */
    data class Stale(val wish: Wish) : Reading

    /** Nothing came back: no network, a timeout, a shop that hung up. */
    data object Failed : Reading
}

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

// ------------------------------------------------- how long it has been wanted

/**
 * The day this wish joined the list.
 *
 * Wishes saved before the field existed have nothing in it, but they are not
 * undatable: the first price ever recorded for one was recorded when it was added,
 * which is the same day give or take the seconds it took to read the page. Falling
 * back to it means the age works on the user's existing list rather than starting
 * every item at zero on the day this shipped.
 */
fun addedDay(wish: Wish): Long = when {
    wish.addedDay > 0L -> wish.addedDay
    else -> wish.history.firstOrNull { it.day > 0L }?.day ?: 0L
}

/** How many days this has been wanted, or null when nothing dates it. */
fun wantedDays(wish: Wish, today: Long): Int? {
    val since = addedDay(wish).takeIf { it in 1..today } ?: return null
    return (today - since).toInt()
}

/**
 * How long this has been wanted, said the way a person would.
 *
 * The number is the point of the whole feature, so it is stated plainly rather
 * than softened: a wishlist works by putting distance between the urge and the
 * decision, and four months of wanting the same thing is an argument either way.
 * Days up to a month, then months, because "97 днів" stops being a duration and
 * becomes arithmetic.
 */
fun wantedLabel(wish: Wish, today: Long): String? {
    val days = wantedDays(wish, today) ?: return null
    return when {
        days == 0 -> "Додано сьогодні"
        days < 31 -> "У списку ${daysLabel(days)}"
        else -> "У списку ${monthsLabel(days / 30)}"
    }
}

// ------------------------------------------------------------ deliberate holds

/** Put aside on purpose, and not due back yet: collapsed on the list and silent. */
fun onHold(wish: Wish, today: Long): Boolean = wish.holdUntil > today

/**
 * A hold whose day has come.
 *
 * The day itself counts as ended, not as one more day of waiting: a hold "until
 * the twelfth" that stays silent through the twelfth would come back a day late
 * every time, and the date the user picked is the date they wanted to be asked.
 */
fun holdEnded(wish: Wish, today: Long): Boolean = wish.holdUntil in 1..today

/** What a held wish says for itself while it waits. */
fun holdLabel(wish: Wish, today: Long): String? = when {
    onHold(wish, today) -> "Відкладено до ${formatDate(LocalDate.ofEpochDay(wish.holdUntil))}"
    holdEnded(wish, today) -> "Ще хочеш?"
    else -> null
}

/**
 * Splits the list into what is being watched and what was deliberately set aside.
 *
 * Held wishes keep their place in the list rather than being hidden: the point of
 * a hold is to come back to the thing, and something you cannot find again was
 * deleted, not postponed.
 */
fun partitionByHold(items: List<Wish>, today: Long): Pair<List<Wish>, List<Wish>> =
    items.partition { !onHold(it, today) }

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
fun placeholderWish(url: String, id: String, today: Long = LocalDate.now().toEpochDay()): Wish =
    Wish(
        id = id,
        name = placeholderName(url),
        url = url,
        image = "",
        price = 0.0,
        history = emptyList(),
        addedDay = today,
        // It has no price and nothing has claimed one, which is what the state says.
        // Typing one in by hand is the way out, and the card offers it.
        freshness = Freshness.UNREADABLE
    )

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
 * The sibling of [applyRefresh] for the path that resolves each wish's own variant
 * before merging. [readings] lines up with [items] by position. A [Reading.Priced]
 * replaces its wish and counts as an update; a [Reading.Stale] replaces it too —
 * that is how the new freshness reaches the card — but is not counted, because no
 * price was updated and saying otherwise would be the exact dishonesty this is
 * meant to remove. A [Reading.Failed] leaves the wish untouched down to its
 * freshness, so a tunnel cannot mark a shelf of healthy wishes as gone.
 */
fun applyFollowed(items: List<Wish>, readings: List<Reading?>): RefreshResult {
    var updated = 0
    val next = items.mapIndexed { index, previous ->
        when (val reading = readings.getOrNull(index)) {
            is Reading.Priced -> {
                updated++
                reading.wish
            }
            is Reading.Stale -> reading.wish
            else -> previous
        }
    }
    return RefreshResult(next, updated)
}

/** How many of the list came back with something other than a price. */
fun staleCount(items: List<Wish>): Int = items.count { isStale(it.freshness) }

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

/**
 * The second line after a run, naming what came back without a price.
 *
 * Kept apart from [refreshMessage] because the two answer different questions.
 * "3 з 5" says how much arrived; it does not say that two of the cards on screen
 * are now showing figures nobody can buy at, and that is the part worth a sentence.
 */
fun staleMessage(stale: Int): String? = when {
    stale <= 0 -> null
    else -> "Без ціни: ${positionsLabel(stale)}"
}

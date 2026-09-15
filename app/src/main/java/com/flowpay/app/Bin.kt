package com.flowpay.app

/**
 * A marked exit from every destructive action in the app.
 *
 * Deleting a wish used to be final and silent, and it took months of price
 * history with it — a record that cannot be fetched back, because a shop only
 * ever states today's price. An expense or a parcel is smaller but disappears
 * just as quietly. Nielsen's third heuristic is the argument: people act by
 * mistake, and a system that offers no way out turns a slip into a loss.
 *
 * Two exits, not one. The snackbar covers the mistake you notice at once; the bin
 * covers the one you notice a week later, which is exactly when a price history
 * is worth most.
 */

/** What a bin entry holds. Short, because every entry carries one. */
const val BIN_WISH = "w"
const val BIN_PAY = "p"
const val BIN_ORDER = "o"

/**
 * One deleted thing, kept whole.
 *
 * [payload] is the item in the same JSON shape the app stores it in, so restoring
 * is a move rather than a reconstruction and nothing — history, savings, tracking
 * number — is lost on the way through. [title] and [detail] are copied out so the
 * bin can list what it holds without decoding every payload to draw a row.
 */
data class BinEntry(
    val id: String,
    val kind: String,
    val title: String,
    val detail: String,
    val payload: String,
    /** Epoch day it was deleted. */
    val day: Long
)

/**
 * How long a deleted item is kept.
 *
 * Thirty days because that is how long the mistake takes to surface: you go
 * looking for the wish when the price moves, not when you delete it.
 */
const val BIN_DAYS = 30

/** The first day the entry is gone. Deleted on day D, it survives up to D+29. */
fun binExpiryDay(entry: BinEntry): Long = entry.day + BIN_DAYS

/**
 * Whether the entry has run out.
 *
 * The boundary is inclusive on the expiry day itself: an entry deleted on the 1st
 * of a 30-day month is gone on the 31st, having been restorable on each of the
 * thirty days in between. Off by one here means either a 29-day bin or a 31-day
 * one, and neither is what the screen promises.
 */
fun binExpired(entry: BinEntry, today: Long): Boolean = today >= binExpiryDay(entry)

/** Days left before the entry goes, nought on and after the day it does. */
fun binDaysLeft(entry: BinEntry, today: Long): Int =
    (binExpiryDay(entry) - today).coerceAtLeast(0L).toInt()

/**
 * Drops what has run out.
 *
 * Entries with no date — which nothing writes, but a hand-edited backup could —
 * are treated as deleted today rather than as ancient, so a bad date cannot wipe
 * the bin on the next run.
 */
fun pruneBin(entries: List<BinEntry>, today: Long): List<BinEntry> =
    entries.filterNot { it.day > 0L && binExpired(it, today) }

/** Newest first: the thing you are looking for in a bin is what you just deleted. */
fun sortedBin(entries: List<BinEntry>): List<BinEntry> = entries.sortedByDescending { it.day }

/** What a bin entry is, in one word, for the row's overline. */
fun binKindLabel(kind: String): String = when (kind) {
    BIN_WISH -> "Бажання"
    BIN_PAY -> "Витрата"
    BIN_ORDER -> "Покупка"
    else -> "Запис"
}

/** How long this entry has left, as the row says it. */
fun binLeftLabel(entry: BinEntry, today: Long): String {
    val left = binDaysLeft(entry, today)
    return if (left <= 0) "видаляється сьогодні" else "лишилось ${daysLabel(left)}"
}

/**
 * The line the undo snackbar leads with.
 *
 * Names the thing rather than its kind: "Видалено" alone leaves you working out
 * what you just lost from the gap in the list behind the bar.
 */
fun binUndoMessage(entry: BinEntry): String = "Видалено «${entry.title}»"

/**
 * How the bin describes itself on its own shut heading.
 *
 * The bin is folded away on «Огляд», and a fold is only allowed to be shut if the
 * heading answers the question you would have opened it to ask. Here that question
 * has two halves — is there anything in there, and am I about to lose it — so the
 * line carries a count and a deadline and nothing else.
 *
 * The deadline is the **smallest** number of days left, not the largest. The
 * entries do not expire together; the one in danger is the oldest, and a heading
 * quoting the newest entry's twenty-nine days would be reassuring about precisely
 * the item that is not safe.
 *
 * Entries with no date are left out of that minimum rather than counted as gone
 * today. Nothing in the app writes one, but [pruneBin] deliberately never deletes
 * one, and a heading announcing that a thing which will in fact be kept forever
 * disappears this afternoon is the one kind of wrong a safety net cannot afford.
 */
fun binSummary(entries: List<BinEntry>, today: Long): String {
    if (entries.isEmpty()) return "Порожній. Видалене зберігається ${daysLabel(BIN_DAYS)}"
    val soonest = entries.filter { it.day > 0L }.minOfOrNull { binDaysLeft(it, today) }
    val fate = when {
        soonest == null -> "зберігаються ${daysLabel(BIN_DAYS)}"
        soonest <= 0 -> "найстаріше видаляється сьогодні"
        else -> "найстаріше зникне через ${daysLabel(soonest)}"
    }
    return "${entriesLabel(entries.size)} · $fate"
}

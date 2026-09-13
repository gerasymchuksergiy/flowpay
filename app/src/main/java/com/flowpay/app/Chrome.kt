package com.flowpay.app

import java.time.LocalDate

/**
 * The decisions the chrome makes, kept away from the drawing.
 *
 * Both of the judgements here used to be impossible to check: whether the app has
 * anything worth interrupting you with, and whether the large title has scrolled
 * far enough to hand over to the compact one. As plain functions over plain values
 * they are pinned by tests instead of by squinting at a phone.
 */

// Which tab each piece of news belongs to. Named, because `tab = 3` in the middle
// of a notification rule says nothing about where tapping it lands.
const val TAB_WISHES = 0
const val TAB_RATE = 1
const val TAB_PAYMENTS = 2
const val TAB_ORDERS = 3
const val TAB_OVERVIEW = 4

enum class StatusKind {
    /** Sitting at a branch with free storage about to end. Costs money to ignore. */
    PARCEL,

    /** Money leaves today or tomorrow. */
    PAYMENT,

    /** A watched price is at or under the target that was set for it. */
    TARGET
}

/**
 * One line of news, with where tapping it should go.
 *
 * Deliberately a single note rather than a list: the pill is one narrow bar, and
 * three things queued in it would be a notification tray, which the phone already
 * has and does better.
 */
data class StatusNote(
    val kind: StatusKind,
    /** What the matter is, in the same shape for all three kinds. */
    val title: String,
    /** The specifics: a figure, a name, a deadline. */
    val detail: String,
    val tab: Int,
    /** It is already costing something rather than about to. */
    val urgent: Boolean
)

/** Free storage ending within this many days is worth interrupting for. */
const val STORAGE_WARNING_DAYS = 2

/**
 * A target counts as *just* reached while the check that found it is this fresh.
 *
 * Without it a price that settled under its target a month ago would hold the pill
 * for ever, and a bar that never changes stops being read at all.
 */
const val TARGET_FRESH_DAYS = 1L

/**
 * The one thing worth saying before you have asked anything, or nothing at all.
 *
 * The order is by what it costs to miss: storage that starts being billed, then
 * money that leaves this week, then a price that is merely good news. Returns null
 * whenever none of those hold, because an empty pill is worse than no pill.
 */
fun statusNote(
    orders: List<Order>,
    pays: List<Pay>,
    wishes: List<Wish>,
    today: LocalDate,
    usdSellRate: Double
): StatusNote? =
    parcelNote(orders, today)
        ?: paymentNote(pays, today, usdSellRate)
        ?: targetNote(wishes, today)

private fun parcelNote(orders: List<Order>, today: LocalDate): StatusNote? {
    val pressing = orders
        .filter { it.status == AT_BRANCH && it.paidStorageFrom > 0L }
        .mapNotNull { order ->
            freeStorageDaysLeft(LocalDate.ofEpochDay(order.paidStorageFrom), today)
                ?.takeIf { it <= STORAGE_WARNING_DAYS }
                ?.let { order to it }
        }
    // Only the tightest deadline fits, and it is the only one that changes what
    // you would do today anyway.
    val daysLeft = pressing.minOfOrNull { it.second } ?: return null
    return StatusNote(
        kind = StatusKind.PARCEL,
        title = "Посилка на відділенні",
        detail = if (daysLeft == 0) {
            "зберігання вже платне"
        } else {
            "безкоштовно ще ${daysLabel(daysLeft)}"
        },
        tab = TAB_ORDERS,
        urgent = daysLeft == 0
    )
}

private fun paymentNote(pays: List<Pay>, today: LocalDate, usdSellRate: Double): StatusNote? {
    val next = nextPayment(pays, today, usdSellRate) ?: return null
    // How much notice a charge deserves is the user's to set, and it was being
    // decided twice: the daily reminder honoured warnDays while this kept its own
    // "today or tomorrow". An expense set to a week's warning reached one seven
    // days out and the other the night before.
    if (next.items.none { next.daysAway <= it.warnDays.coerceAtLeast(0) }) return null
    val due = next.total
    return StatusNote(
        kind = StatusKind.PAYMENT,
        title = "Платіж ${dueLabel(next.daysAway)}",
        // A rent billed in dollars with no rate loaded would otherwise be announced
        // as the hryvnia part alone, which reads as a smaller bill than it is.
        detail = if (due.rateMissing) {
            listOfNotNull(
                dollars(due.usd),
                money(due.uah).takeIf { due.uah > 0.0 }
            ).joinToString(" і ")
        } else {
            "${money(due.total)} · ${dueSummary(next.items)}"
        },
        tab = TAB_PAYMENTS,
        urgent = next.daysAway == 0
    )
}

private fun targetNote(wishes: List<Wish>, today: LocalDate): StatusNote? {
    val hit = wishes.firstOrNull { targetHit(it, today.toEpochDay()) } ?: return null
    return StatusNote(
        kind = StatusKind.TARGET,
        title = "Ціна досягла цілі",
        detail = "${hit.name} · ${money(hit.price)}",
        tab = TAB_WISHES,
        urgent = false
    )
}

/**
 * Whether the compact title bar has taken over from the large one.
 *
 * [headerBottom] is where the large title's block ends, measured from the top of
 * the list, and is null once that block has scrolled off entirely. [barHeight] is
 * how much of the top the compact bar covers: the handover happens when the large
 * title would otherwise slide underneath it, so exactly one of the two is legible
 * at any moment.
 */
fun titleCollapsed(headerBottom: Int?, barHeight: Int): Boolean =
    headerBottom == null || headerBottom <= barHeight

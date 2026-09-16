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
    val urgent: Boolean,
    /**
     * Which piece of news this is, as a string that changes when the news does.
     *
     * The whole of what [NoteDismissal] compares against, so what it identifies
     * is the *state of the matter* rather than the kind of matter: a parcel with
     * two free days left and the same parcel with one are different keys, because
     * the second is worse news and waving away the first was never consent to be
     * kept in the dark about the second.
     *
     * Built from structure — an epoch date, an id, a count — and never from the
     * rendered [title] or [detail]. Those are formatted through [money] and the
     * Ukrainian plurals, and a stored key that carried formatted text would be a
     * locale-dependent value living in the phone's preferences, which is the same
     * mistake as a locale-dependent storage key anywhere else.
     */
    val key: String
)

/**
 * A pill the person swiped away, and the day they did it.
 *
 * **The rule, in one sentence: a dismissal silences exactly the note that was
 * dismissed, and only for the rest of that day.** Both halves are load-bearing
 * and each one closes the hole the other leaves.
 *
 * *Exactly the note* — [StatusNote.key] identifies the matter and its present
 * state, so waving away «безкоштовно ще 2 дні» does not also wave away
 * «зберігання вже платне» tomorrow, and it does not wave away a payment or a
 * price that turns up an hour later. Without that clause one swipe would buy
 * silence about everything, which is exactly how this bar stops being read.
 *
 * *For the rest of that day* — because the keys of the notes that cannot be
 * marked against are not guaranteed to move. A parcel already on paid storage
 * reports the same thing every day until it is collected, and a price target
 * stays hit; if the key alone decided, one swipe would silence those for ever,
 * and a note nothing can ever bring back is a note that was deleted. A day is
 * the right length because a day is the unit all three notes are stated in —
 * each of them recomputes its own wording when the date turns over — so the
 * expiry lands exactly where the news would have changed anyway.
 *
 * Written to the phone's preferences and deliberately not to [Store.exportJson],
 * for the same reason as the folded sections and the rate target: it records
 * what this phone has already shown its owner today, and restoring a year-old
 * file must not reach across and silence this morning's pill.
 */
data class NoteDismissal(
    val key: String,
    /** Epoch day the swipe happened. */
    val day: Long
)

/**
 * Whether this exact note was already waved away today.
 *
 * The day is compared for equality rather than "not before": a phone whose clock
 * ran ahead and was then corrected would otherwise carry a dismissal that
 * silences the pill until the calendar catches up with the wrong reading.
 */
fun noteSilenced(note: StatusNote, dismissed: NoteDismissal?, today: LocalDate): Boolean =
    dismissed != null && dismissed.key == note.key && dismissed.day == today.toEpochDay()

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
 * Everything worth interrupting for, in the order it costs to miss.
 *
 * Storage that starts being billed, then money that leaves this week, then a
 * price that is merely good news. A list rather than the first survivor, because
 * a dismissal applies to one note and the one below it has to be able to come
 * through: picking the winner before asking what was silenced would let a swiped
 * payment take that day's reached price down with it.
 */
fun statusNotes(
    orders: List<Order>,
    pays: List<Pay>,
    wishes: List<Wish>,
    today: LocalDate,
    usdSellRate: Double,
    paid: List<PaidMark> = emptyList()
): List<StatusNote> = listOfNotNull(
    parcelNote(orders, today),
    paymentNote(pays, paid, today, usdSellRate),
    targetNote(wishes, today)
)

/**
 * The one thing worth saying before you have asked anything, or nothing at all.
 *
 * The highest-ranking note that has not been waved away today. Returns null
 * whenever there is none, because an empty pill is worse than no pill.
 *
 * [paid] and [dismissed] carry defaults so that a caller with neither — a test
 * about ordering, say — reads as the plain question it is asking. The app passes
 * both: the marks are what stop a settled charge announcing itself, and the
 * dismissal is what lets a note that cannot be marked against be silenced by hand.
 */
fun statusNote(
    orders: List<Order>,
    pays: List<Pay>,
    wishes: List<Wish>,
    today: LocalDate,
    usdSellRate: Double,
    paid: List<PaidMark> = emptyList(),
    dismissed: NoteDismissal? = null
): StatusNote? = statusNotes(orders, pays, wishes, today, usdSellRate, paid)
    .firstOrNull { !noteSilenced(it, dismissed, today) }

private fun parcelNote(orders: List<Order>, today: LocalDate): StatusNote? {
    val pressing = orders
        // Archived is this app's word for "dealt with": the purchase is closed and
        // filed, and a parcel that has been filed cannot still be costing storage.
        // Without this a finished purchase left standing at a branch stage would go
        // on announcing a deadline about a thing already in the person's hands.
        .filter { it.status == AT_BRANCH && it.paidStorageFrom > 0L && it.archivedDay == 0L }
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
        urgent = daysLeft == 0,
        // The days left, so every step of the countdown is its own piece of news.
        key = "parcel:$daysLeft"
    )
}

/**
 * Money that is about to leave and has not already gone.
 *
 * The second clause is the one that was missing, and it is why the pill would not
 * go away. [nextPayment] answers "what does the calendar say", which for a
 * subscription charged on the tenth of every month says "the tenth" whether or not
 * the tenth has been settled — so an expense the person had already ticked off on
 * the payments screen went on being announced above every screen in the app until
 * the date passed, and swiping it away would only have brought it back tomorrow.
 *
 * Settled means exactly what the rest of the app means by it, and it is asked
 * through the one function that decides it — [stillOwing], which the morning
 * notification asks as well. Not a second notion invented here: the tick on the
 * payments screen, the month rows on the overview, this bar and the message that
 * arrives at nine have to be incapable of disagreeing about whether the rent is
 * paid, and a second implementation is precisely how they would come to.
 */
private fun paymentNote(
    pays: List<Pay>,
    paid: List<PaidMark>,
    today: LocalDate,
    usdSellRate: Double
): StatusNote? {
    val next = nextPayment(stillOwing(pays, paid, today), today, usdSellRate) ?: return null
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
        urgent = next.daysAway == 0,
        // The date money leaves, in ISO, which is a structure rather than a
        // sentence and so says the same thing on any phone. The countdown in the
        // title moves without it, and the day the dismissal expires on is what
        // brings the pill back as the date gets closer.
        key = "payment:${next.date}"
    )
}

/**
 * A watched price that has reached the figure it was given.
 *
 * A wish on hold is skipped, and that is the same class of fault as the one in
 * [paymentNote]: a hold is the person saying, in the app's own words, that they
 * have dealt with this and do not want to be asked again until a date they chose.
 * The wishlist already collapses a held card and goes quiet about it, and the
 * digest already refuses to mention one. This bar was the last place still
 * shouting about a decision that had already been made.
 */
private fun targetNote(wishes: List<Wish>, today: LocalDate): StatusNote? {
    val day = today.toEpochDay()
    val hit = wishes.firstOrNull { !onHold(it, day) && targetHit(it, day) } ?: return null
    return StatusNote(
        kind = StatusKind.TARGET,
        title = "Ціна досягла цілі",
        detail = "${hit.name} · ${money(hit.price)}",
        tab = TAB_WISHES,
        urgent = false,
        // The thing, not its price: a wish that dips further under its target is
        // the same good news arriving again, and the day's expiry is what decides
        // whether it is worth repeating.
        key = "target:${hit.id}"
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

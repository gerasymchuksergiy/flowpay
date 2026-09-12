package com.flowpay.app

import java.time.LocalDate

/**
 * One message in the morning instead of a day of taps on the shoulder.
 *
 * Price drops, target hits, parcel movements and the payment reminder each used to
 * ring the phone on their own schedule. Four channels, none of them urgent, all of
 * them arriving whenever a background pass happened to finish — which is how a
 * useful notification becomes one that gets swiped away without being read, and
 * then muted.
 *
 * So the ordinary news is collected and said once, at an hour the user chose. What
 * stays immediate is only what a morning would be too late for: a price that has
 * reached the target set for it, and free parcel storage that runs out tomorrow.
 * Both of those cost money to learn about late; nothing else here does.
 */

/** The span the digest reports price movement over: since this time yesterday. */
const val DIGEST_WINDOW_DAYS = 1

/** The hour the digest arrives when the user has not said otherwise. */
const val DEFAULT_DIGEST_HOUR = 9

/** The hours worth offering. Morning by default; the late ones are for shift work. */
val DIGEST_HOURS = listOf(7, 8, 9, 10, 12, 18, 21)

/**
 * Free storage running out this soon cannot wait for the next digest.
 *
 * One day, not two: at two days away the morning message still leaves a full day
 * to collect the parcel, and interrupting for it would be interrupting for
 * something that could have been said in a line.
 */
const val URGENT_STORAGE_DAYS = 1

fun storageIsUrgent(daysLeft: Int?): Boolean =
    daysLeft != null && daysLeft <= URGENT_STORAGE_DAYS

/**
 * The one parcel alert that still interrupts.
 *
 * Past tense when the billing has already started, because "ще 0 днів" is not
 * something a person parses at a glance on a lock screen.
 */
fun urgentStorageText(daysLeft: Int): String =
    if (daysLeft <= 0) "Безкоштовне зберігання закінчилось"
    else "Безкоштовне зберігання ще ${daysLabel(daysLeft)}"

/**
 * What a watched price has done over the last [days] days, or null when there is
 * nothing old enough to compare against.
 *
 * The history only records changes, so the last point dated on or before the
 * window's start is the price as it stood then. A wish added yesterday has no such
 * point and is correctly reported as no news rather than as a change of its whole
 * price.
 */
fun recentChange(wish: Wish, today: Long, days: Int = DIGEST_WINDOW_DAYS): Double? {
    if (wish.price <= 0.0) return null
    val before = wish.history.lastOrNull { it.day in 1..(today - days) }?.price ?: return null
    val change = wish.price - before
    return change.takeIf { it != 0.0 }
}

data class Digest(val title: String, val lines: List<String>) {
    /** Nothing happened. The caller sends no notification at all. */
    val empty: Boolean get() = title.isBlank()

    /** One line per item: the notification is expanded, so newlines are read. */
    val body: String get() = lines.joinToString("\n")
}

/**
 * The whole morning message, built from state rather than from events.
 *
 * Nothing is accumulated between background passes on purpose. An event log would
 * have to survive the app being killed mid-day — on this phone, the very thing
 * that makes the digest necessary — and a digest that silently loses half its
 * contents is worse than one recomputed from what is on disk every morning.
 *
 * Returns an empty digest when there is genuinely nothing to say, and the caller
 * sends nothing at all. A daily "нічого нового" is a daily interruption that
 * carries no information, which is exactly what this feature exists to remove.
 */
fun digest(
    wishes: List<Wish>,
    pays: List<Pay>,
    orders: List<Order>,
    today: LocalDate,
    usdSellRate: Double,
    income: Double,
    holidays: Set<Long> = emptySet()
): Digest {
    val news = buildList {
        // Above the waiting parcel: one that is going back to the sender has a
        // deadline you cannot see and an outcome you have to act to change.
        problemLine(orders)?.let { add(it) }
        parcelLine(orders, today)?.let { add(it) }
        addAll(paymentLines(pays, today, holidays))
        addAll(priceLines(wishes, today.toEpochDay()))
    }
    if (news.isEmpty()) return Digest("", emptyList())

    // The month's free cash rides along rather than standing on its own. It is the
    // figure every one of the lines above is spent against, and it is never news.
    val trailer = freeCashLine(budget(income, monthlyTotal(pays, usdSellRate)))

    // A single piece of news is its own headline. Hiding one sentence behind a
    // generic title would make the digest worse than the notification it replaced;
    // it is only when there are several that a name for the collection earns the
    // line it takes.
    return if (news.size == 1) {
        Digest(news.first(), listOf(trailer))
    } else {
        Digest("Зведення за день", news + trailer)
    }
}

/**
 * Parcels the carrier has reported a refusal, a return or an unknown number for.
 *
 * This used to reach you as a stage-change notification the moment the carrier
 * said so. Folding every stage change into the digest would have dropped it
 * altogether, and a parcel quietly travelling back to the sender is the one piece
 * of parcel news that changes what you would do today.
 */
private fun problemLine(orders: List<Order>): String? {
    val trouble = orders.filter { it.problem && it.archivedDay == 0L }
    return when {
        trouble.isEmpty() -> null
        trouble.size == 1 -> "${trouble.first().name} — проблема з доставкою"
        else -> "${parcelsLabel(trouble.size)} з проблемою доставки"
    }
}

/** Parcels that are sitting at a branch, and how long they stay free. */
private fun parcelLine(orders: List<Order>, today: LocalDate): String? {
    // A purchase that has been closed and filed is a record, not an errand. The
    // status it was archived with can still read "На відділенні", and reporting
    // that every morning would be the app nagging about something already done.
    val waiting = orders.filter { it.status == AT_BRANCH && it.archivedDay == 0L }
    if (waiting.isEmpty()) return null
    val tightest = waiting
        .mapNotNull { order ->
            freeStorageDaysLeft(
                order.paidStorageFrom.takeIf { it > 0L }?.let { LocalDate.ofEpochDay(it) },
                today
            )?.let { order to it }
        }
        .minByOrNull { it.second }
    val head = if (waiting.size == 1) waiting.first().name else parcelsLabel(waiting.size)
    val tail = when {
        tightest == null -> "чекає на відділенні"
        tightest.second == 0 -> "зберігання вже платне"
        else -> "безкоштовно ще ${daysLabel(tightest.second)}"
    }
    // With several parcels the deadline belongs to one of them, so it is named.
    val whose = if (waiting.size > 1 && tightest != null) " (${tightest.first.name})" else ""
    return "$head — $tail$whose"
}

/**
 * Everything whose notice period has opened, with the weekend shift said out loud.
 *
 * The shift is already inside the number of days, so the note only has to explain
 * why the date the user wrote down is not the date being talked about.
 */
private fun paymentLines(
    pays: List<Pay>,
    today: LocalDate,
    holidays: Set<Long>
): List<String> = remindersDue(pays, today, holidays).map { reminder ->
    val amount = amountLabel(reminder.pay.amount, reminder.pay.currency)
    val moved = reminder.movedFrom?.let { " (перенесено з ${dayMonth(it)})" }.orEmpty()
    "${reminder.pay.name} $amount — ${dueLabel(reminder.daysAway)}$moved"
}

/**
 * What the watched prices did.
 *
 * Falls are named one by one because each is something to act on. Rises are
 * counted rather than listed: knowing three things got dearer is worth a line,
 * knowing which three is worth a screen, and this is a notification.
 *
 * The wording carries no verb agreeing with the product name. A shop's title has
 * no grammatical gender the app can know, and "Навушники подешевшав" is the kind
 * of sentence that makes an app read as machine-translated.
 *
 * Two kinds of wish are silent here. One deliberately put aside until March is a
 * decision the user already made, and the digest must not overrule it any more
 * than an immediate alert may. One whose page stopped stating a readable price is
 * showing its last known figure, so a "fall" against it would be an invention.
 */
private fun priceLines(wishes: List<Wish>, today: Long): List<String> {
    val moved = wishes
        .filterNot { onHold(it, today) || isStale(it.freshness) }
        .mapNotNull { wish -> recentChange(wish, today)?.let { wish to it } }
    val fell = moved.filter { it.second < 0 }.sortedBy { it.second }
    val rose = moved.count { it.second > 0 }
    return buildList {
        fell.forEach { (wish, change) ->
            add("${wish.name} — ${money(wish.price)}, дешевше на ${money(-change)}")
        }
        if (rose > 0) add("Подорожчало: ${positionsLabel(rose)}")
    }
}

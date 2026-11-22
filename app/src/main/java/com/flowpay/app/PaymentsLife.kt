package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth

/*
 * A payment's life after it starts — asked for on 4 October 2026 («додай все»
 * over the research page into ten other apps): a cancelled subscription that still
 * works «до 30.10», a plan «частинами» paid off early or returned, a pause, and a
 * promo price that ends on a date («150 ₴ до 1 лютого, далі 300 ₴»).
 *
 * A payment used to be for ever: it started, and it charged every month until it
 * was deleted. The research's warning about changing that was the one «Частинами»
 * already met (§18): dozens of places count future charges, and a stop that one of
 * them misses is a cancelled subscription that goes on «charging» there.
 *
 * So the rule is the one [chargesIn] set for plans. There is ONE check a charge
 * goes through, [runsOn], and [chargesIn] asks it — every total, record, reminder,
 * strip, forecast, digest line, CSV row and the widget then see a stop or a pause
 * without being told. And there is ONE price for a day, [priceOn]: the promo price
 * before the promo's date and the regular one after, a free trial being a promo at
 * nought.
 */

// ------------------------------------------------------------ how it stops

/** «Скасував ✓»: the subscription works until the end of what was paid for, then stops. */
const val STOP_CANCELLED = "cancelled"

/** «Погасив достроково»: the plan's last payment is this month's. */
const val STOP_PAID_OFF = "paidoff"

/** «Повернув»: the thing went back, and the plan closed with no payment after it. */
const val STOP_RETURNED = "returned"

/**
 * A pause that is over. Charges due from [from] up to, not including, [until] did
 * not happen, so the months it covered stay out of «По місяцях» once the payment
 * runs again — a resumed subscription must not leave three months reading
 * «не позначено» for charges that were never taken.
 */
data class PauseSpan(
    /** Epoch day the pause began. */
    val from: Long,
    /** Epoch day it ended; charges on this day run again. */
    val until: Long,
    /** What monobank charged when it ended the pause by itself. Nought when ended by hand. */
    val bankCharge: Double = 0.0
)

/** Finished pauses a payment remembers. Older ones are behind every record the app keeps. */
const val PAUSES_KEPT = 12

// ------------------------------------------------------------ the one check

/**
 * Whether a charge falling due on [date] by this payment's rhythm actually takes
 * money: not after the payment stopped ([Pay.stopsAfter]), not while it is
 * paused, and not inside a pause that is already over.
 *
 * **The one check.** [chargesIn] asks it, and everything that counts a charge asks
 * [chargesIn] — so a stop reaches the month, the year, the next payment, the
 * reminders, the forecast, the records, the digest, the CSV and the widget at once.
 */
fun runsOn(pay: Pay, date: LocalDate): Boolean {
    val day = date.toEpochDay()
    if (pay.stopsAfter > 0L && day > pay.stopsAfter) return false
    if (pay.pausedFrom > 0L && day >= pay.pausedFrom) return false
    return pay.pauses.none { day >= it.from && day < it.until }
}

/** The date this payment's charge falls on in the month [month] is in, clamped to a real day. */
fun chargeDateIn(pay: Pay, month: LocalDate): LocalDate =
    month.withDayOfMonth(effectivePaymentDay(pay.day, month.lengthOfMonth()))

/**
 * What one charge of this payment takes on [day]: the promo price while the promo
 * runs, the regular price after it. A free trial is a promo at nought.
 *
 * **The one price.** Every place that used to read a running trial as nought reads
 * this instead, so a discount that ends on a date is honest everywhere at once.
 * Which day is asked stays each caller's own decision — the month's total asks
 * about today, the record of a month about that month's own charge date.
 */
fun priceOn(pay: Pay, day: Long): Double =
    if (onTrial(pay, day)) pay.promoPrice.coerceAtLeast(0.0) else pay.amount

/** A discount rather than a free trial: something is charged before [Pay.trialEnd]. */
fun isPromo(pay: Pay): Boolean = pay.trialEnd > 0L && pay.promoPrice > 0.0

/**
 * The next date this payment really takes money, or null when it never will again:
 * a plan behind its last payment, a cancelled subscription past what was paid
 * for, a paused one.
 *
 * The first date answers for all the later ones. A stop and a pause each silence
 * every charge from some day on, and a plan's months only run out — so when the
 * next charge does not happen, none after it does either.
 */
fun nextLiveCharge(pay: Pay, today: LocalDate): LocalDate? =
    nextCharge(pay, today).takeIf { chargesIn(pay, it) }

/** Whether this payment will take money again: what [stillOwing] and the schedule ask. */
fun isLive(pay: Pay, today: LocalDate): Boolean = nextLiveCharge(pay, today) != null

/**
 * How many charges really happen from [from] up to, not including, [until] —
 * each month's charge date, kept only where [chargesIn] says it runs. What a year
 * of a payment is made of once a payment can stop.
 */
fun chargesAhead(pay: Pay, from: LocalDate, until: LocalDate): Int {
    var month = from.withDayOfMonth(1)
    var count = 0
    while (month.isBefore(until)) {
        val date = chargeDateIn(pay, month)
        if (!date.isBefore(from) && date.isBefore(until) && chargesIn(pay, month)) count++
        month = month.plusMonths(1)
    }
    return count
}

// ------------------------------------------------------------ where it is in its life

/** Where a payment is in its life, for what the screen shows of it. */
enum class PayLife {
    /** Charging as usual. */
    RUNNING,

    /** Paused by hand: nothing counted, nothing reminded, until resumed. */
    PAUSED,

    /** Cancelled, and the paid period still running: «скасовано · діє до 30 жовтня». */
    CANCELLED,

    /** Cancelled, and the paid period over: one question, then the bin. */
    ENDED,

    /** A plan «частинами» with every payment behind it — paid off, returned or simply done. */
    FINISHED
}

fun lifeOf(pay: Pay, today: LocalDate): PayLife = when {
    isInstalment(pay) -> if (isFinished(pay, today)) PayLife.FINISHED else PayLife.RUNNING
    pay.stopsAfter > 0L && pay.stopsAfter < today.toEpochDay() -> PayLife.ENDED
    pay.stopsAfter > 0L -> PayLife.CANCELLED
    pay.pausedFrom > 0L -> PayLife.PAUSED
    else -> PayLife.RUNNING
}

// ------------------------------------------------------------ cancelled

/**
 * The last day a cancelled subscription still works, as the cancel sheet offers
 * it: the day before the charge it stops. When that charge is already ticked paid,
 * the money for that period has gone, so it is the day before the one after.
 */
fun paidUntil(pay: Pay, today: LocalDate, marks: List<PaidMark>): LocalDate {
    val next = nextCharge(pay, today)
    if (!isPaid(marks, pay.name, monthKey(next))) return next.minusDays(1)
    return nextCharge(pay, next.plusDays(1)).minusDays(1)
}

/** «Скасував ✓», working until [until]. A pause, if there was one, ends with it. */
fun cancelled(pay: Pay, until: LocalDate, today: LocalDate): Pay =
    resumed(pay, today).copy(stopsAfter = until.toEpochDay().coerceAtLeast(1L), stopReason = STOP_CANCELLED)

/** A stop taken back — «Відновити»: the payment runs again as it did. */
fun unstopped(pay: Pay): Pay = pay.copy(stopsAfter = 0L, stopReason = "")

/** «скасовано · діє до 30 жовтня», while a cancelled payment still works; null otherwise. */
fun cancelledLine(pay: Pay, today: LocalDate): String? =
    if (!isInstalment(pay) && pay.stopsAfter > 0L && pay.stopsAfter >= today.toEpochDay()) {
        "скасовано · діє до ${dayMonth(LocalDate.ofEpochDay(pay.stopsAfter))}"
    } else {
        null
    }

/**
 * The question an ended cancellation asks on Платежі, the day after what was paid
 * for ran out — the day the next charge would have come.
 *
 * No verb agreeing with the name: «Netflix не списав?» is what the research
 * wrote, and «Підписка не списав» is what a typed name would have made of it.
 */
fun endedQuestion(pay: Pay): String =
    "Оплачений період скінчився ${dayMonth(LocalDate.ofEpochDay(pay.stopsAfter))}. Нового списання не було?"

/**
 * How long an ended cancellation keeps its question on Платежі before it goes to
 * the bin by itself. A week: the would-be charge is a day old when it is asked,
 * and a bank statement takes a day or two to show it.
 */
const val CANCEL_QUESTION_DAYS = 7

// ------------------------------------------------------------ paused

/** «Поставити на паузу»: nothing from today on is counted or reminded. */
fun paused(pay: Pay, today: LocalDate): Pay =
    if (pay.pausedFrom > 0L) pay else pay.copy(pausedFrom = today.toEpochDay())

/**
 * The pause over. It is kept as a span, so the months it covered stay unasked;
 * [until] is the first day charges run again — today when resumed by hand, the
 * charge's own due date when monobank saw it charge ([bankCharge]).
 */
fun resumed(pay: Pay, today: LocalDate, until: Long = today.toEpochDay(), bankCharge: Double = 0.0): Pay {
    if (pay.pausedFrom <= 0L) return pay
    val end = maxOf(pay.pausedFrom, until)
    // An empty span changes no count, but one the bank ended carries the news.
    val spans = if (end > pay.pausedFrom || bankCharge > 0.0) {
        pay.pauses + PauseSpan(pay.pausedFrom, end, bankCharge)
    } else {
        pay.pauses
    }
    return pay.copy(pausedFrom = 0L, pauses = spans.takeLast(PAUSES_KEPT))
}

/** «на паузі з 4 жовтня», or null when the payment runs. */
fun pausedLine(pay: Pay): String? =
    pay.pausedFrom.takeIf { it > 0L }?.let { "на паузі з ${dayMonth(LocalDate.ofEpochDay(it))}" }

// ------------------------------------------------------------ a plan's own end

/**
 * The last payment of a plan that happens — where a payoff or a return moved its
 * end to. Null when a return came before the first payment.
 */
fun planLast(pay: Pay): LocalDate? =
    (pay.instalments downTo 1).asSequence().map { instalmentDate(pay, it) }.firstOrNull { runsOn(pay, it) }

/** The date of the plan's payment in [month]'s month, or null when that month is outside the plan. */
fun planDateIn(pay: Pay, month: LocalDate): LocalDate? {
    if (!isInstalment(pay)) return null
    val inside = YearMonth.from(month) in YearMonth.from(instalmentFirst(pay))..YearMonth.from(instalmentLast(pay))
    return if (inside) chargeDateIn(pay, month) else null
}

/** What paying the plan off now takes: every payment still to come. */
fun payOffSum(pay: Pay, today: LocalDate): Double = pay.amount * instalmentsLeft(pay, today)

/**
 * «Погасив достроково»: the plan ends with this month — at this month's payment
 * while it is still ahead, so the month that holds the money is the month that
 * planned it, and today when this month's is behind.
 */
fun paidOff(pay: Pay, today: LocalDate): Pay {
    if (!isInstalment(pay)) return pay
    val thisMonth = planDateIn(pay, today)
    val end = if (thisMonth != null && !thisMonth.isBefore(today)) thisMonth else today
    return pay.copy(stopsAfter = end.toEpochDay(), stopReason = STOP_PAID_OFF)
}

/**
 * The month's mark once a plan is paid off: this month's payment — as marked, or
 * as planned when it is not — plus every payment that would have come after it,
 * so «По місяцях» shows the money in the month it actually went.
 */
fun payOffMarks(marks: List<PaidMark>, pay: Pay, today: LocalDate): List<PaidMark> {
    if (!isInstalment(pay)) return marks
    val month = monthKey(today)
    val own = marks.firstOrNull { it.name == pay.name && it.month == month }?.amount
        ?: if (planDateIn(pay, today) != null) pay.amount else 0.0
    val after = (1..pay.instalments).map { instalmentDate(pay, it) }
        .count { YearMonth.from(it) > YearMonth.from(today) && runsOn(pay, it) }
    val total = own + pay.amount * after
    if (total <= 0.0) return marks
    return marks.filterNot { it.name == pay.name && it.month == month } +
        PaidMark(pay.name, month, kotlin.math.round(total * 100) / 100.0, pay.currency)
}

/** «Повернув»: no payment from today on. What was paid stays where it was paid. */
fun returned(pay: Pay, today: LocalDate): Pay =
    pay.copy(stopsAfter = (today.toEpochDay() - 1).coerceAtLeast(1L), stopReason = STOP_RETURNED)

/** What a plan that is over says under its name in «Розстрочки, які закінчились». */
fun finishedPlanLine(pay: Pay): String = when (pay.stopReason) {
    STOP_RETURNED -> "товар повернуто · закрито ${formatDate(LocalDate.ofEpochDay(pay.stopsAfter + 1))}"
    STOP_PAID_OFF -> planLast(pay)?.let { "погашено достроково · ${formatDate(it)}" } ?: "погашено достроково"
    else -> "усі ${paymentsLabel(pay.instalments)} позаду · останній ${formatDate(instalmentLast(pay))}"
}

/**
 * What a purchase says once the plan that paid for it was closed because it went
 * back. The purchase and the plan are tied by [Pay.order].
 */
fun returnedPurchaseLine(order: Order): String? =
    order.planReturned.takeIf { it > 0L }
        ?.let { "Повернуто ${formatDate(LocalDate.ofEpochDay(it))} — розстрочку закрито" }

// ------------------------------------------------------------ the promo price

/**
 * The line under a promo in the editor: «До 1 лютого — 150 ₴, далі 300 ₴».
 * Null with no date set.
 */
fun promoNote(amount: Double, promo: Double, trialEnd: Long, currency: String): String? {
    if (trialEnd <= 0L) return null
    val until = dayMonth(LocalDate.ofEpochDay(trialEnd))
    val then = if (amount > 0.0) ", далі ${amountLabel(amount, currency)}" else ""
    return if (promo > 0.0) {
        "До $until — ${amountLabel(promo, currency)}$then"
    } else {
        "Безкоштовно до $until$then"
    }
}

/**
 * A promo that ran out, written into the history as the move it was — «150 →
 * 300» — with no one having to type it.
 *
 * The promo's own point is undated: the app never saw the day the discount
 * started, and a date it invented would put a fall into some month's recap that
 * never happened. The regular price is dated on the promo's last day, which is
 * the day it took effect. A history that starts here gets the regular price in
 * front as well, undated, so the year before reads at the regular price rather
 * than at the discount. Written once; a second pass finds it and leaves it.
 */
fun withPromoEnded(pay: Pay, today: LocalDate): Pay {
    if (!isPromo(pay) || pay.trialEnd > today.toEpochDay()) return pay
    val end = pay.trialEnd
    val recorded = pay.amounts.withIndex().any { (index, point) ->
        point.day == end && index > 0 && pay.amounts[index - 1].day == 0L && pay.amounts[index - 1].price == pay.promoPrice
    }
    if (recorded) return pay
    val regular = amountOn(pay, end)
    val before = pay.amounts.filter { it.day == 0L || it.day < end }
    val after = pay.amounts.filter { it.day > end }
    val lead = if (before.isEmpty()) listOf(PricePoint(regular, 0L)) else before
    return pay.copy(amounts = lead + PricePoint(pay.promoPrice, 0L) + PricePoint(regular, end) + after)
}

/** Whether the move just before [change] was a promo ending rather than a raise. */
fun isPromoEnd(pay: Pay, change: AmountChange): Boolean =
    pay.promoPrice > 0.0 && change.day == pay.trialEnd && change.from == pay.promoPrice

// ------------------------------------------------------------ time moving on

/** What a list looks like once time has moved on. */
data class LifeSweep(
    /** Ended promos written into their histories, long-ended cancellations taken off. */
    val pays: List<Pay>,
    /** Cancelled payments whose question has waited [CANCEL_QUESTION_DAYS]: they go to the bin. */
    val toBin: List<Pay>
)

/**
 * Applies what a day passing does to the list. Pure and repeatable: running it a
 * second time on its own answer changes nothing, which is what lets the app and
 * the morning worker both run it without agreeing who goes first.
 */
fun sweepLife(pays: List<Pay>, today: LocalDate): LifeSweep {
    val day = today.toEpochDay()
    val recorded = pays.map { withPromoEnded(it, today) }
    val gone = recorded.filter { lifeOf(it, today) == PayLife.ENDED && day - it.stopsAfter > CANCEL_QUESTION_DAYS }
    return LifeSweep(recorded.filterNot { it in gone }, gone)
}

/**
 * A cancelled payment on its way to the bin. It comes back from there running,
 * not cancelled — restoring it means it is wanted again. Its id is the same every
 * time the sweep meets it, so a list saved over the sweep by a screen that was
 * open cannot put it in the bin twice.
 */
fun endedBinEntry(pay: Pay, today: Long): BinEntry = BinEntry(
    id = "$BIN_PAY-ended-${pay.stopsAfter}-${Integer.toHexString(pay.name.hashCode())}",
    kind = BIN_PAY,
    title = pay.name,
    detail = "${amountLabel(pay.amount, pay.currency)} · скасовано, оплачено до " +
        dayMonth(LocalDate.ofEpochDay(pay.stopsAfter.coerceAtLeast(1L))),
    payload = payJson(unstopped(pay)).toString(),
    day = today
)

// ------------------------------------------------------------ the morning message

/** A line the morning message says once, and the key it is remembered by once said. */
data class OnceLine(val key: String, val text: String)

/** How long after the bank lifted a pause it is still news. */
const val RESUMED_NEWS_DAYS = 10

/**
 * The digest's lines about payments' lives, each said once (the worker remembers
 * the keys):
 *
 * - an ended cancellation, the day what was paid for ran out — the day the next
 *   charge would have come: was there really no charge?
 * - a promo about to end, as far ahead as the payment's own notice reaches;
 * - a pause monobank ended by charging again.
 */
fun lifeLines(pays: List<Pay>, today: LocalDate): List<OnceLine> = buildList {
    val day = today.toEpochDay()
    pays.forEach { pay ->
        if (lifeOf(pay, today) == PayLife.ENDED && day - pay.stopsAfter <= CANCEL_QUESTION_DAYS) {
            add(
                OnceLine(
                    "ended|${pay.name}|${pay.stopsAfter}",
                    "${pay.name}: оплачений період скінчився ${dayMonth(LocalDate.ofEpochDay(pay.stopsAfter))} — " +
                        "чи не списали гроші знову?"
                )
            )
        }
        if (isPromo(pay) && isLive(pay, today) && day in (pay.trialEnd - pay.warnDays.coerceAtLeast(0))..pay.trialEnd) {
            val from = if (pay.trialEnd == day) "Від сьогодні" else "З ${dayMonth(LocalDate.ofEpochDay(pay.trialEnd))}"
            add(
                OnceLine(
                    "promo|${pay.name}|${pay.trialEnd}",
                    "$from ${pay.name} коштуватиме ${amountLabel(pay.amount, pay.currency)} замість " +
                        "${amountLabel(pay.promoPrice, pay.currency)} — можна попросити продовжити знижку або змінити тариф"
                )
            )
        }
        pay.pauses.lastOrNull()
            ?.takeIf { it.bankCharge > 0.0 && pay.pausedFrom == 0L && day - it.until in 0..RESUMED_NEWS_DAYS }
            ?.let { span ->
                add(
                    OnceLine(
                        "resumed|${pay.name}|${span.from}",
                        "${pay.name} знову списує ${amountLabel(span.bankCharge, pay.currency)} — паузу знято"
                    )
                )
            }
    }
}

/**
 * The once-lines still to say: what was said before is left out, and so is a
 * second line under a key already taken this morning.
 */
fun unsaid(lines: List<OnceLine>, said: Set<String>): List<OnceLine> =
    lines.filter { it.key !in said }.distinctBy { it.key }

/** The memory of said keys, newest kept: a few hundred is months of mornings. */
fun rememberSaid(said: Collection<String>, now: Collection<String>, keep: Int = SAID_KEPT): List<String> =
    (said.filterNot { it in now } + now).takeLast(keep)

const val SAID_KEPT = 300

// ------------------------------------------------------------ «Як скасувати»

/** A service whose cancel page is known, and the words its payment is recognised by. */
data class CancelPlace(val name: String, val url: String, val words: List<String>)

/**
 * The built-in directory. Each address was requested once on 4 October 2026 and
 * answered (most send a visitor who is not signed in to their sign-in page, which
 * then continues to it). Most specific first: «Google *YouTube» is YouTube before
 * it is Google Play.
 */
val CANCEL_PLACES = listOf(
    CancelPlace("YouTube Premium", "https://www.youtube.com/paid_memberships", listOf("youtube", "ютуб")),
    CancelPlace("Netflix", "https://www.netflix.com/cancelplan", listOf("netflix", "нетфлікс")),
    CancelPlace("Spotify", "https://www.spotify.com/account/overview/", listOf("spotify", "спотіфай")),
    CancelPlace("ChatGPT", "https://chatgpt.com/#settings", listOf("chatgpt", "chat gpt", "openai", "чатгпт")),
    CancelPlace("Megogo", "https://megogo.net/ua/account?view_type=subscriptions", listOf("megogo", "мегого")),
    CancelPlace("Sweet.tv", "https://sweet.tv/ua-uk/cabinet/personal", listOf("sweet.tv", "sweet tv", "sweettv", "світ тв")),
    CancelPlace(
        "Google Play",
        "https://play.google.com/store/account/subscriptions",
        listOf("google play", "play market", "плей маркет", "google")
    )
)

/** Where «Як скасувати» goes, and what the button says about it. */
data class CancelLink(
    val url: String,
    /** «Netflix», «ваше посилання», or the search. */
    val label: String,
    /** Nothing known about this service: the link is a web search. */
    val searched: Boolean
)

/** True when [word] starts a word in [text], the way the emoji rules match. */
private fun opensWord(text: String, word: String): Boolean {
    var from = 0
    while (true) {
        val at = text.indexOf(word, from)
        if (at < 0) return false
        if (at == 0 || !text[at - 1].isLetterOrDigit()) return true
        from = at + 1
    }
}

/** The known service a payment is, by its name or by the merchant the owner confirmed. */
fun cancelPlaceFor(pay: Pay): CancelPlace? {
    val texts = listOf(pay.name.lowercase(), pay.monoMerchant.lowercase()).filter { it.isNotBlank() }
    return CANCEL_PLACES.firstOrNull { place -> place.words.any { word -> texts.any { opensWord(it, word) } } }
}

/** A web search for «як скасувати …». */
fun cancelSearchUrl(name: String): String =
    "https://www.google.com/search?q=" + URLEncoder.encode("як скасувати ${name.trim()}", "UTF-8")

/** The owner's own link first, then the directory, then a search. */
fun cancelLink(pay: Pay): CancelLink {
    val own = pay.cancelUrl.trim()
    if (own.isNotBlank() && isSupportedWebUrl(own)) return CancelLink(own, "ваше посилання", searched = false)
    cancelPlaceFor(pay)?.let { return CancelLink(it.url, it.name, searched = false) }
    return CancelLink(cancelSearchUrl(pay.name), "пошук «як скасувати ${pay.name.trim()}»", searched = true)
}

/** Bills that are not cancelled from a web page — rent, utilities, loans, insurance. */
private val BILL_EMOJI = setOf("🏠", "💡", "🔥", "💧", "🏦", "🛡️", "🚗", "💊", "🐾")

/**
 * Whether «Як скасувати» belongs beside this payment: subscriptions and trials,
 * not the rent. A plan is closed by its own buttons.
 */
fun cancelHelpFits(pay: Pay, today: LocalDate): Boolean = when {
    isInstalment(pay) -> false
    onTrial(pay, today.toEpochDay()) -> true
    pay.cancelUrl.isNotBlank() || cancelPlaceFor(pay) != null -> true
    else -> shownEmoji(pay) !in BILL_EMOJI
}

// ------------------------------------------------------------ on disk

/** The finished pauses, written whole: a lost span reopens months it had closed. */
fun pausesJson(spans: List<PauseSpan>): JSONArray = JSONArray().apply {
    spans.forEach { put(JSONObject().put("f", it.from).put("u", it.until).put("c", it.bankCharge)) }
}

fun pausesOf(array: JSONArray?): List<PauseSpan> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optJSONObject(index)?.let {
            PauseSpan(
                it.optLong("f", 0L),
                it.optLong("u", 0L),
                it.optDouble("c", 0.0).takeIf { charge -> charge.isFinite() && charge > 0.0 } ?: 0.0
            )
        }
    }.filter { it.from > 0L && it.until >= it.from }
}

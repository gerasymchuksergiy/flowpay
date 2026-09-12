package com.flowpay.app

import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Recurring expenses are not all in one currency. Rent is often quoted and paid in
 * dollars while everything else is in hryvnia, so the monthly total has to convert
 * before it can be a single useful number.
 */

const val UAH = "UAH"
const val USD = "USD"

data class MonthlyTotal(
    /** Sum of the entries already in hryvnia. */
    val uah: Double,
    /** Sum of the dollar entries, still in dollars. */
    val usd: Double,
    /** Those dollars in hryvnia. Zero when there is no rate to convert them with. */
    val usdInUah: Double,
    /** What the month actually costs in hryvnia, as far as it can be known. */
    val total: Double,
    val hasUsd: Boolean,
    /** There are dollar entries but no rate, so [total] is short by [usd] dollars. */
    val rateMissing: Boolean
)

/**
 * Adds up a month.
 *
 * Takes the rate at which the bank sells dollars, because that is the side of the
 * spread you pay when you have to find dollars for the rent. Using the buy rate
 * would quietly understate every month.
 */
fun monthlyTotal(items: List<Pay>, usdSellRate: Double): MonthlyTotal {
    val uah = items.filter { it.currency != USD }.sumOf { it.amount }
    val usd = items.filter { it.currency == USD }.sumOf { it.amount }
    val rate = usdSellRate.coerceAtLeast(0.0)
    val hasUsd = usd > 0.0
    val convertible = hasUsd && rate > 0.0
    val usdInUah = if (convertible) usd * rate else 0.0
    return MonthlyTotal(
        uah = uah,
        usd = usd,
        usdInUah = usdInUah,
        total = uah + usdInUah,
        hasUsd = hasUsd,
        rateMissing = hasUsd && rate <= 0.0
    )
}

/** A recurring cost is charged this many times a year. */
const val MONTHS_IN_YEAR = 12

/** What one recurring expense costs in a year, in the currency it was entered in. */
fun yearlyCost(pay: Pay): Double = pay.amount * MONTHS_IN_YEAR

/**
 * A year of the same standing costs.
 *
 * The figure that changes minds: 400 ₴ a month is a rounding error and 4 800 ₴ a
 * year is a decision. Built from [monthlyTotal] so a missing rate stays missing
 * rather than being quietly multiplied into a smaller number twelve times over.
 */
fun yearlyTotal(items: List<Pay>, usdSellRate: Double): MonthlyTotal {
    val monthly = monthlyTotal(items, usdSellRate)
    return monthly.copy(
        uah = monthly.uah * MONTHS_IN_YEAR,
        usd = monthly.usd * MONTHS_IN_YEAR,
        usdInUah = monthly.usdInUah * MONTHS_IN_YEAR,
        total = monthly.total * MONTHS_IN_YEAR
    )
}

/** "36 000 ₴ на рік" — one expense's annual cost, in its own currency. */
fun annualLabel(pay: Pay): String = "${amountLabel(yearlyCost(pay), pay.currency)} на рік"

data class Budget(
    val income: Double,
    val expenses: Double,
    /** Income minus expenses. Negative when the month does not fit. */
    val free: Double,
    val overspent: Boolean,
    /** No income has been entered, so nothing here means anything yet. */
    val unknown: Boolean
)

/**
 * What is left each month once the standing costs are paid.
 *
 * This is the number that turns a wishlist into a plan: it is the most that can
 * realistically go towards a wish without touching anything else. Left unclamped
 * on purpose, because a month that does not fit is worth saying out loud.
 */
fun budget(income: Double, expenses: MonthlyTotal): Budget {
    val safeIncome = income.coerceAtLeast(0.0)
    val free = safeIncome - expenses.total
    return Budget(
        income = safeIncome,
        expenses = expenses.total,
        free = free,
        overspent = safeIncome > 0.0 && free < 0.0,
        unknown = safeIncome <= 0.0
    )
}

/**
 * The day of the month a payment actually lands on.
 *
 * A payment set for the 31st has to happen on the 28th in February. Clamping to
 * the month's length is what a bank does, and without it a reminder for the 31st
 * would silently never fire in four months of the year.
 */
fun effectivePaymentDay(day: Int, monthLength: Int): Int = day.coerceIn(1, monthLength)

/** Payments falling due on exactly this date. */
fun paymentsDueOn(items: List<Pay>, date: LocalDate): List<Pay> {
    val monthLength = date.lengthOfMonth()
    return items.filter { effectivePaymentDay(it.day, monthLength) == date.dayOfMonth }
}

/**
 * The next date money actually leaves, and what leaves with it.
 *
 * A monthly total is a figure you read; this is a figure you act on, which is
 * why it belongs at the top of the screen instead.
 */
data class NextPayment(
    val date: LocalDate,
    /** Nought when something is due today. */
    val daysAway: Int,
    val items: List<Pay>,
    /** Everything due that day, so a missing rate stays visible instead of reading as zero. */
    val total: MonthlyTotal
)

fun nextPayment(items: List<Pay>, today: LocalDate, usdSellRate: Double): NextPayment? {
    if (items.isEmpty()) return null
    // A month and a day is enough to find the next occurrence of any day number,
    // including the 31st landing on the 30th of a short month.
    for (offset in 0..31) {
        val date = today.plusDays(offset.toLong())
        val due = paymentsDueOn(items, date)
        if (due.isNotEmpty()) {
            return NextPayment(date, offset, due, monthlyTotal(due, usdSellRate))
        }
    }
    return null
}

/** Ukrainian plural for how many payments fall together. */
fun paymentsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "платежів"
        last == 1 -> "платіж"
        last in 2..4 -> "платежі"
        else -> "платежів"
    }
    return "$count $word"
}

/**
 * What falls on a date, said in a line rather than a paragraph.
 *
 * Listing six names wrapped the panel to three lines and repeated what the
 * timeline shows directly underneath, so past a pair the count says it better.
 */
fun dueSummary(items: List<Pay>): String = when {
    items.isEmpty() -> ""
    items.size <= 2 -> items.joinToString(", ") { it.name }
    else -> paymentsLabel(items.size)
}

/** "сьогодні", "завтра", "через 3 дні". */
fun dueLabel(daysAway: Int): String = when (daysAway) {
    0 -> "сьогодні"
    1 -> "завтра"
    else -> "через ${daysLabel(daysAway)}"
}

/**
 * Expenses that fall on the same date, soonest first.
 *
 * Positions rather than the expenses themselves, because two identical entries
 * are equal as values and deleting "one of them" has to mean a definite one.
 */
data class PaymentGroup(val date: LocalDate, val positions: List<Int>)

fun paymentGroups(items: List<Pay>, today: LocalDate): List<PaymentGroup> =
    items.indices
        .groupBy { nextDateFor(items[it], today) }
        .toSortedMap()
        .map { (date, positions) -> PaymentGroup(date, positions) }

/** The next time this expense comes round, this month or next. */
private fun nextDateFor(pay: Pay, today: LocalDate): LocalDate {
    val thisMonth = effectivePaymentDay(pay.day, today.lengthOfMonth())
    if (thisMonth >= today.dayOfMonth) return today.withDayOfMonth(thisMonth)
    val next = today.plusMonths(1)
    return next.withDayOfMonth(effectivePaymentDay(pay.day, next.lengthOfMonth()))
}

/**
 * Days from today, 0 being today, that carry a payment.
 *
 * The strip used to draw the calendar month while the list below it was already
 * showing next month's dates, so the two disagreed about what they described.
 * Counting forward from today makes them the same span.
 */
fun paymentOffsets(items: List<Pay>, today: LocalDate, days: Int = 30): Set<Int> =
    (0 until days)
        .filter { paymentsDueOn(items, today.plusDays(it.toLong())).isNotEmpty() }
        .toSet()

// ------------------------------------------------------------ advance warning

/**
 * Days of notice an expense gets when it does not say otherwise.
 *
 * One, not nought, because that is exactly what every expense already had: the
 * reminder used to look at tomorrow and only at tomorrow. Reading a missing field
 * as nought would have silently moved every existing expense on the phone to a
 * reminder that arrives on the charge date, which is the thing this feature exists
 * to stop.
 */
const val DEFAULT_WARN_DAYS = 1

/**
 * The notice periods the editor offers.
 *
 * A day is enough to move money; a week is what a subscription needs, because
 * cancelling before the charge is the only way not to pay it. Nought is kept for
 * the bills you cannot avoid and only need to remember on the day.
 */
val WARN_CHOICES = listOf(0, 1, 3, 7)

/** How a notice period reads on a chip. */
fun warnLabel(days: Int): String = when {
    days <= 0 -> "У день оплати"
    days == 1 -> "За день"
    else -> "За ${daysLabel(days)}"
}

/** An expense whose warning window has opened, and how long is left before it is charged. */
data class DueReminder(val pay: Pay, val daysAway: Int)

/**
 * Everything worth saying on [today], soonest first.
 *
 * The window is per expense: a subscription set to seven days starts appearing a
 * week out, while the electricity bill set to nought appears only on the morning
 * it is taken. Everything found here goes into one notification, so a long notice
 * period costs a line in a daily message rather than a week of separate alarms.
 */
fun remindersDue(items: List<Pay>, today: LocalDate): List<DueReminder> =
    items.mapNotNull { pay ->
        val daysAway = java.time.temporal.ChronoUnit
            .DAYS.between(today, nextDateFor(pay, today)).toInt()
        DueReminder(pay, daysAway).takeIf { daysAway <= pay.warnDays.coerceAtLeast(0) }
    }.sortedBy { it.daysAway }

/** The line a daily reminder leads with. */
fun reminderTitle(reminders: List<DueReminder>): String {
    val days = reminders.map { it.daysAway }.distinct()
    return when {
        reminders.isEmpty() -> ""
        reminders.size == 1 -> "Оплата ${dueLabel(reminders.first().daysAway)}"
        days.size == 1 -> "${paymentsLabel(reminders.size)} ${dueLabel(days.first())}"
        else -> "Найближчі платежі"
    }
}

/**
 * The body of the reminder.
 *
 * When everything falls on the same day the title has already said when, so
 * repeating it under each name would be noise. When the days differ, each name has
 * to carry its own, or a week's notice and today's charge read as the same thing.
 */
fun reminderText(reminders: List<DueReminder>): String {
    val mixed = reminders.map { it.daysAway }.distinct().size > 1
    return reminders.joinToString(if (mixed) " · " else ", ") { reminder ->
        val amount = amountLabel(reminder.pay.amount, reminder.pay.currency)
        if (mixed) {
            "${reminder.pay.name} $amount — ${dueLabel(reminder.daysAway)}"
        } else {
            "${reminder.pay.name} $amount"
        }
    }
}

private val UK = Locale("uk", "UA")

/**
 * Two decimal places at most, and none when the amount is whole.
 *
 * The default number format allows three, which turned a converted rent into
 * "11 200,225 ₴" — a figure carrying a tenth of a kopeck.
 */
private fun amountFormat(): NumberFormat = NumberFormat.getNumberInstance(UK).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 0
}

/** "2 203,24 ₴" */
fun money(value: Double): String = amountFormat().format(value) + " ₴"

/** "250 $" */
fun dollars(value: Double): String = amountFormat().format(value) + " $"

/**
 * A converted figure, rounded to whole hryvnia.
 *
 * It is an estimate at today's rate, so kopecks would claim a precision the
 * number does not have.
 */
fun approxMoney(value: Double): String = money(kotlin.math.round(value))

/** An amount shown in whichever currency it was entered in. */
fun amountLabel(value: Double, currency: String): String =
    if (currency == USD) dollars(value) else money(value)

/**
 * A rate, always with both decimals.
 *
 * "41,2" reads as a truncated number rather than a rate, and the second place is
 * worth about forty kopecks on a hundred dollars.
 */
fun rateFigure(value: Double): String =
    NumberFormat.getNumberInstance(UK).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }.format(value)

/**
 * Where the figure came from, said plainly.
 *
 * The official rate and a bank's rate are different numbers — usually by the best
 * part of a hryvnia — so a rate that reaches the screen without its source invites
 * reading one as the other. This is why the label is never omitted, not even when
 * the fallback worked perfectly.
 */
fun rateSourceLabel(rate: FxRate): String = when {
    rate.sell <= 0.0 -> ""
    rate.source == SOURCE_NBU && rate.date.isNotBlank() -> "Офіційний курс НБУ на ${rate.date}"
    rate.source == SOURCE_NBU -> "Офіційний курс НБУ"
    else -> "Ринковий курс Monobank"
}

/** The rate itself: a spread where there is one, a single official figure where there is not. */
fun rateHeadline(rate: FxRate): String = when {
    rate.sell <= 0.0 -> "Курс ще не завантажено"
    rate.source == SOURCE_NBU -> "${rateFigure(rate.sell)} ₴ за долар"
    else -> "Купівля ${rateFigure(rate.buy)} · продаж ${rateFigure(rate.sell)}"
}

/**
 * A set of payments as one figure, without ever pretending the dollars converted.
 *
 * Somewhere with no room for a caption — a widget, a notification — the missing
 * rate has to be visible inside the figure itself, or a rent quoted in dollars
 * simply vanishes from the total.
 */
fun totalLabel(total: MonthlyTotal): String = when {
    total.rateMissing && total.uah > 0.0 -> "${money(total.uah)} + ${dollars(total.usd)}"
    total.rateMissing -> dollars(total.usd)
    total.hasUsd -> approxMoney(total.total)
    else -> money(total.total)
}

/** What the month has left, in one line, including when the answer is "nothing". */
fun freeCashLine(month: Budget): String = when {
    month.unknown -> "Дохід не вказано"
    month.overspent -> "Бракує ${money(-month.free)}"
    else -> "Вільно ${money(month.free)}"
}

// --------------------------------------------------------- what was actually paid

/**
 * Everything above is a plan. Nothing here was ever a record of what happened, so
 * "скільки пішло в серпні" had no answer at all — the app could only say what
 * August was supposed to cost, which is a different number the moment one bill is
 * skipped, paid late, or paid twice.
 */

/**
 * A month as a sortable key, "2026-08".
 *
 * A month rather than a date, because paying the rent on the 3rd and the internet
 * on the 28th are both August. Zero-padded so sorting the keys as text sorts them
 * as time, which is the whole of the ordering the overview needs.
 */
fun monthKey(date: LocalDate): String = "%04d-%02d".format(date.year, date.monthValue)

/** The first day of the month a key names, or null when the string is not a key. */
fun monthKeyDate(key: String): LocalDate? = runCatching {
    val year = key.substringBefore('-').toInt()
    val month = key.substringAfter('-').toInt()
    LocalDate.of(year, month, 1)
}.getOrNull()

/**
 * Month names in the nominative.
 *
 * [formatDate] carries a genitive set for use inside a sentence — "10 серпня". A
 * heading is not inside a sentence, and "Серпня 2026" as a title is wrong in the
 * way that makes an app read as translated.
 */
private val MONTHS_NOMINATIVE = listOf(
    "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
    "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
)

/** "Серпень 2026", the form a month takes as a heading. */
fun monthTitle(key: String): String {
    val date = monthKeyDate(key) ?: return key
    return "${MONTHS_NOMINATIVE[date.monthValue - 1]} ${date.year}"
}

/**
 * One standing expense, marked as paid for one month.
 *
 * The amount is copied in rather than read back off the expense, because the
 * record has to stay true after the rent goes up: what August cost is not what
 * September costs, and an expense edited in October must not rewrite August.
 *
 * Identified by name, since that is all a [Pay] carries. Two expenses sharing a
 * name are therefore one row here — rare enough to accept, and the alternative is
 * an id on every existing expense that nothing on the phone would have.
 */
data class PaidMark(
    val name: String,
    /** "2026-08". */
    val month: String,
    val amount: Double,
    val currency: String = UAH
)

/** Months of marks kept: this one and the twelve before it. */
const val PAID_HISTORY_MONTHS = 13

/** Whether this expense already has a mark against it for [month]. */
fun isPaid(marks: List<PaidMark>, name: String, month: String): Boolean =
    marks.any { it.name == name && it.month == month }

/**
 * Marks an expense paid, or takes the mark back off it.
 *
 * One action rather than two, because the mistake being recovered from is always
 * the same one: the wrong row was tapped, and the fix is to tap it again.
 */
fun togglePaid(marks: List<PaidMark>, pay: Pay, month: String): List<PaidMark> =
    if (isPaid(marks, pay.name, month)) {
        marks.filterNot { it.name == pay.name && it.month == month }
    } else {
        marks + PaidMark(pay.name, month, pay.amount, pay.currency)
    }

/**
 * Drops marks older than the window, and any month that has not happened yet.
 *
 * A future month can only come from a clock that was wrong when the mark was made,
 * and leaving one in would put a month above the current one on the overview.
 */
fun prunePaidMarks(marks: List<PaidMark>, today: LocalDate): List<PaidMark> {
    val first = today.withDayOfMonth(1)
    val oldest = monthKey(first.minusMonths((PAID_HISTORY_MONTHS - 1).toLong()))
    val newest = monthKey(first)
    return marks.filter { it.month in oldest..newest }
}

/** How a month turned out, once it is known what was planned and what was marked. */
enum class MonthState {
    /** There were no standing expenses and nothing was marked: nothing was owed. */
    NOTHING_DUE,

    /** Every standing expense has a mark against it. */
    SETTLED,

    /** The month is still running, so what is unmarked is not yet late. */
    RUNNING,

    /** The month is over and not one payment was marked. */
    UNRECORDED,

    /** The month is over and some of it was marked. */
    PARTIAL
}

data class MonthRecord(
    /** "2026-08". */
    val month: String,
    /** "Серпень 2026". */
    val title: String,
    /** What the marks for this month add up to. */
    val paid: MonthlyTotal,
    /** What the standing expenses ask for. */
    val planned: MonthlyTotal,
    /** Planned minus paid, never below nought. */
    val gap: Double,
    val paidCount: Int,
    val plannedCount: Int,
    val state: MonthState
)

/**
 * One month, read back.
 *
 * [planned] can only be the expenses standing *now*: nothing on the phone records
 * what the list looked like in August. That is honest for the months this feature
 * has been running and is the reason [monthRecords] refuses to go back further
 * than the first mark.
 */
fun monthRecord(
    pays: List<Pay>,
    marks: List<PaidMark>,
    month: String,
    today: LocalDate,
    usdSellRate: Double
): MonthRecord {
    val forMonth = marks.filter { it.month == month }
    // Marks are totalled through the same function as expenses, so a dollar rent
    // paid with no rate loaded stays visible as dollars instead of reading as nought.
    val paid = monthlyTotal(
        forMonth.map { Pay(it.name, it.amount, currency = it.currency) },
        usdSellRate
    )
    val planned = monthlyTotal(pays, usdSellRate)
    val settled = pays.all { isPaid(marks, it.name, month) }
    return MonthRecord(
        month = month,
        title = monthTitle(month),
        paid = paid,
        planned = planned,
        gap = (planned.total - paid.total).coerceAtLeast(0.0),
        paidCount = forMonth.size,
        plannedCount = pays.size,
        state = when {
            pays.isEmpty() && forMonth.isEmpty() -> MonthState.NOTHING_DUE
            settled -> MonthState.SETTLED
            month >= monthKey(today) -> MonthState.RUNNING
            forMonth.isEmpty() -> MonthState.UNRECORDED
            else -> MonthState.PARTIAL
        }
    )
}

/**
 * The months worth showing, newest first.
 *
 * Runs back from this month only as far as the earliest mark. Before that the app
 * was not keeping a record, and a row per month saying "не позначено жодного
 * платежу" for a year that was in fact paid would be an accusation rather than a
 * report.
 */
fun monthRecords(
    pays: List<Pay>,
    marks: List<PaidMark>,
    today: LocalDate,
    usdSellRate: Double,
    maxMonths: Int = PAID_HISTORY_MONTHS
): List<MonthRecord> {
    val current = monthKey(today)
    val first = today.withDayOfMonth(1)
    val earliest = marks.map { it.month }.filter { it <= current }.minOrNull() ?: current
    return (0 until maxMonths.coerceAtLeast(1))
        .map { monthKey(first.minusMonths(it.toLong())) }
        .takeWhile { it >= earliest }
        .map { monthRecord(pays, marks, it, today, usdSellRate) }
}

/** A month in one line, which is all a row on the overview has room for. */
fun monthRecordLine(record: MonthRecord): String = when (record.state) {
    MonthState.NOTHING_DUE -> "Нічого не було до сплати"
    MonthState.UNRECORDED -> "Не позначено жодного платежу"
    MonthState.SETTLED -> "Сплачено все · ${totalLabel(record.paid)}"
    MonthState.RUNNING -> "Сплачено ${totalLabel(record.paid)} з ${totalLabel(record.planned)}"
    MonthState.PARTIAL -> "Сплачено ${totalLabel(record.paid)}, лишилось ${money(record.gap)}"
}

/**
 * The second line: how many of the month's expenses are accounted for.
 *
 * Empty where the figure above already said it, because a row that repeats itself
 * in smaller grey type is how a list stops being read.
 */
fun monthRecordDetail(record: MonthRecord): String = when (record.state) {
    MonthState.NOTHING_DUE -> ""
    MonthState.UNRECORDED -> "Місяць минув · ${paymentsLabel(record.plannedCount)} у списку"
    MonthState.SETTLED -> paymentsLabel(record.paidCount)
    // Bare figures rather than "1 з 2 платежі": "з" governs the genitive, and
    // building that phrase from a nominative plural helper is how an app comes out
    // sounding translated.
    else -> "Позначено ${record.paidCount} з ${record.plannedCount}"
}

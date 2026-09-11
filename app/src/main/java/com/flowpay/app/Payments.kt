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

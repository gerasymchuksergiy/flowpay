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

/** Which days of the current month carry a payment, for the month strip. */
fun paymentDays(items: List<Pay>, monthLength: Int): Set<Int> =
    items.map { effectivePaymentDay(it.day, monthLength) }.toSet()

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

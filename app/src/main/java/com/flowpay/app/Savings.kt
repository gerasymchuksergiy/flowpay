package com.flowpay.app

import java.time.LocalDate
import kotlin.math.ceil

/**
 * The arithmetic behind "how much do I put aside for this".
 *
 * Kept as plain functions so the numbers can be tested directly. Money planning is
 * the kind of thing where an off-by-one month is both easy to write and hard to
 * notice on screen.
 */

data class SavingsPlan(
    val goal: Double,
    val saved: Double,
    val monthly: Double,
    /** Still to find. Never negative, even when more was saved than needed. */
    val remaining: Double,
    /** Share of the goal already saved, clamped to 0..1 for a progress bar. */
    val progress: Float,
    /** Whole months at the stated monthly rate. Zero when the goal is already met. */
    val months: Int,
    val weekly: Double,
    val daily: Double,
    val reached: Boolean,
    /** True when there is something left to save but no monthly figure to divide by. */
    val needsRate: Boolean
)

/** A month averages slightly more than four weeks, so a year is divided rather than a month multiplied. */
private const val WEEKS_PER_MONTH = 52.0 / 12.0
private const val DAYS_PER_MONTH = 365.0 / 12.0

fun savingsPlan(goal: Double, saved: Double, monthly: Double): SavingsPlan {
    val safeGoal = goal.coerceAtLeast(0.0)
    val safeSaved = saved.coerceAtLeast(0.0)
    val safeMonthly = monthly.coerceAtLeast(0.0)
    val remaining = (safeGoal - safeSaved).coerceAtLeast(0.0)
    val reached = safeGoal > 0.0 && remaining == 0.0
    val progress = if (safeGoal > 0.0) (safeSaved / safeGoal).coerceIn(0.0, 1.0).toFloat() else 0f
    val months = if (safeMonthly > 0.0 && remaining > 0.0) ceil(remaining / safeMonthly).toInt() else 0
    return SavingsPlan(
        goal = safeGoal,
        saved = safeSaved,
        monthly = safeMonthly,
        remaining = remaining,
        progress = progress,
        months = months,
        weekly = safeMonthly / WEEKS_PER_MONTH,
        daily = safeMonthly / DAYS_PER_MONTH,
        reached = reached,
        needsRate = remaining > 0.0 && safeMonthly <= 0.0
    )
}

/**
 * The other direction: the user names a date, the app says what that costs per month.
 * Returns zero when the date is today or in the past, since no rate can fix that.
 */
fun monthlyRateFor(remaining: Double, months: Int): Double =
    if (months > 0 && remaining > 0) remaining / months else 0.0

private val MONTHS_GENITIVE = listOf(
    "січня", "лютого", "березня", "квітня", "травня", "червня",
    "липня", "серпня", "вересня", "жовтня", "листопада", "грудня"
)

/** "10 грудня 2026", the form a date takes inside a Ukrainian sentence. */
fun formatDate(date: LocalDate): String =
    "${date.dayOfMonth} ${MONTHS_GENITIVE[date.monthValue - 1]} ${date.year}"

fun readyDate(months: Int, today: LocalDate): LocalDate = today.plusMonths(months.toLong())

/** Ukrainian needs three forms, so "3 місяць" and "5 місяці" both have to be impossible. */
fun monthsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "місяців"
        last == 1 -> "місяць"
        last in 2..4 -> "місяці"
        else -> "місяців"
    }
    return "$count $word"
}

/** Reads a typed amount, accepting a comma as the decimal separator. */
fun parseAmount(text: String): Double = text.trim().replace(',', '.').toDoubleOrNull() ?: 0.0

/** Prefills an input box, leaving it empty for zero rather than showing "0.0". */
fun amountText(value: Double): String = when {
    value <= 0.0 -> ""
    value % 1.0 == 0.0 -> value.toLong().toString()
    else -> value.toString()
}

/** "19:45", for showing how fresh a cached figure is. */
fun timeLabel(epochMillis: Long): String {
    val time = java.time.Instant.ofEpochMilli(epochMillis)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalTime()
    return "%02d:%02d".format(time.hour, time.minute)
}

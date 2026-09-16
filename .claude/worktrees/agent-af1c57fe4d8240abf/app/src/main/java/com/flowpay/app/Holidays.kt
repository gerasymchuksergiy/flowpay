package com.flowpay.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Which days money can actually move.
 *
 * A recurring expense is stored as a bare day of the month, and a bare day of the
 * month is a lie four or five times a year: the 25th falls on a Sunday, the bank
 * is shut, and a reminder that fires on the day is a reminder that arrives after
 * the useful moment has passed.
 *
 * Everything here degrades to "weekends only" when the holiday feed is missing,
 * and that is the point. A martial-law year moves official holidays by decree and
 * any public feed lags it, so the calendar is an improvement on top of the weekend
 * rule and never something the reminder depends on. An empty holiday set is a
 * fully working state, not an error.
 */

/** Saturday and Sunday, the part of the answer that needs no feed. */
fun isWeekend(date: LocalDate): Boolean =
    date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

/** [holidays] are epoch days, which is how they are cached and compared. */
fun isWorkingDay(date: LocalDate, holidays: Set<Long>): Boolean =
    !isWeekend(date) && date.toEpochDay() !in holidays

/**
 * Why a day is not one for paying on, or null when it is.
 *
 * A public holiday that lands on a Sunday is named as a holiday rather than as a
 * Sunday: that the weekend is a weekend is already obvious, while the holiday is
 * the half the user may not have on their own calendar.
 */
fun nonWorkingReason(date: LocalDate, holidays: Set<Long>): String? = when {
    date.toEpochDay() in holidays -> "святковий день"
    date.dayOfWeek == DayOfWeek.SATURDAY -> "субота"
    date.dayOfWeek == DayOfWeek.SUNDAY -> "неділя"
    else -> null
}

/**
 * How far back a search for a working day is allowed to walk.
 *
 * The new year run of 1–8 January is the longest stretch Ukraine has ever had off
 * in one piece, so ten days is comfortably past the worst case. The cap exists so
 * that a corrupt cache claiming a whole month is a holiday cannot spin forever.
 */
private const val MAX_SHIFT_DAYS = 10

/** The nearest working day on or before [date], or [date] itself if none is near. */
fun workingDayOnOrBefore(date: LocalDate, holidays: Set<Long>): LocalDate {
    var candidate = date
    repeat(MAX_SHIFT_DAYS) {
        if (isWorkingDay(candidate, holidays)) return candidate
        candidate = candidate.minusDays(1)
    }
    return date
}

/**
 * A charge date and the working day it has to be dealt with by.
 *
 * The shift is backwards, never forwards. These are bills a person pays rather
 * than direct debits a bank collects, so a due date on a Sunday means the money
 * has to be there by Friday; moving the reminder to Monday would be advice to be
 * late.
 */
data class PaymentDay(
    val due: LocalDate,
    /** The working day to pay on. The same as [due] whenever that is one. */
    val payOn: LocalDate,
    /** Why [due] is not a working day, or null when it is. */
    val reason: String?
) {
    val moved: Boolean get() = payOn != due
}

fun paymentDay(due: LocalDate, holidays: Set<Long>): PaymentDay =
    PaymentDay(due, workingDayOnOrBefore(due, holidays), nonWorkingReason(due, holidays))

/**
 * How a moved payment day reads under the date it was moved from.
 *
 * Null when nothing moved, because a note saying a Tuesday is a Tuesday is noise.
 */
fun paymentDayNote(day: PaymentDay): String? {
    if (!day.moved) return null
    val reason = day.reason ?: return null
    return "${reason.replaceFirstChar { it.uppercase() }} — платіж до ${dayMonth(day.payOn)}"
}

// ------------------------------------------------------------ the feed

/**
 * Ukraine's public holidays, no key and no registration.
 *
 * Asked for once a year and cached, because the answer changes at most that
 * often and a background pass has better things to spend a request on.
 */
const val HOLIDAY_FEED = "https://nagerholidays.com/api/v4/Holidays/UA"

/**
 * Reads the feed into epoch days.
 *
 * Anything unreadable comes back as an empty set rather than an exception: the
 * caller's fallback for "the fetch failed" and for "the fetch returned nonsense"
 * has to be the same fallback, or a feed that changes shape becomes a crash.
 *
 * Only national public holidays count. The feed also carries regional and
 * observance entries, and a bank does not shut for those.
 */
fun parseHolidays(json: String): Set<Long> = runCatching {
    val entries = JSONArray(json)
    (0 until entries.length()).mapNotNull { index ->
        val entry = entries.optJSONObject(index) ?: return@mapNotNull null
        if (!entry.optBoolean("nationalHoliday", true)) return@mapNotNull null
        val types = entry.optJSONArray("holidayTypes")
        val public = types == null ||
            (0 until types.length()).any { types.optString(it).equals("Public", true) }
        if (!public) return@mapNotNull null
        runCatching { LocalDate.parse(entry.optString("date")).toEpochDay() }.getOrNull()
    }.toSet()
}.getOrDefault(emptySet())

/** Fetches a year of holidays. An empty set means "carry on without them". */
suspend fun fetchHolidays(year: Int): Set<Long> = withContext(Dispatchers.IO) {
    runCatching {
        val connection = URL("$HOLIDAY_FEED/$year").openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        if (connection.responseCode !in 200..299) return@runCatching emptySet()
        parseHolidays(connection.inputStream.bufferedReader().use { it.readText() })
    }.getOrDefault(emptySet())
}

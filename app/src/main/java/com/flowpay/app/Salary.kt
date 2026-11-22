package com.flowpay.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.UUID

/**
 * «Зарплата»: a log of the money that actually came in, month by month — the
 * advance, the salary, anything else — so the owner can see what November came to
 * and keep the list.
 *
 * Deliberately separate from the planned income (`Store.income`, the figure
 * «Вільно» rests on): that one is what a month is expected to bring, this is what
 * each month did bring. Nothing here changes «Вільно».
 *
 * **Agreed in hryvnias, paid in dollars.** The owner's pay is agreed in hryvnias
 * but arrives in dollars, in crypto, not to a card (22 November 2026). So a payment
 * is typed in dollars ([SalaryEntry.amount]) with the rate of its day
 * ([SalaryEntry.rate], prefilled from the app's rate and editable), and the screens
 * lead with the hryvnia figure — the agreed one — with the dollars beside it.
 *
 * Pure logic here, with tests (SalaryTest); the sheet is in SalaryUi.kt. Prefs and
 * JSON keys start with «sl».
 */

/** What a payment was. The code is what is stored; the label and emoji are what is shown. */
enum class SalaryKind(val code: String, val label: String, val emoji: String) {
    ADVANCE("a", "Аванс", "💵"),
    SALARY("s", "Зарплата", "💼"),
    OTHER("o", "Інше", "🎁");

    companion object {
        fun of(code: String?): SalaryKind = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/**
 * One payment received.
 *
 * [month] is the month the money is *for* («2026-11»), not the day it arrived: a
 * November salary paid on 2 December still belongs to November. [day] is the epoch
 * day it was written down, which orders the payments inside a month.
 */
data class SalaryEntry(
    val id: String = UUID.randomUUID().toString(),
    val month: String,
    val kind: SalaryKind,
    val amount: Double,
    val day: Long,
    val note: String = "",
    /** Hryvnias for one dollar on the day — what turns [amount] into the agreed hryvnia pay. Nought: not known. */
    val rate: Double = 0.0
) {
    /** The payment in hryvnias, or nought when the rate is not known. */
    val uah: Double get() = if (rate > 0.0) amount * rate else 0.0
}

fun salaryEntryJson(entry: SalaryEntry): JSONObject = JSONObject()
    .put("sli", entry.id)
    .put("slm", entry.month)
    .put("slk", entry.kind.code)
    .put("sla", entry.amount)
    .put("sld", entry.day)
    .put("sln", entry.note)
    .put("slr", entry.rate)

fun salaryEntryOf(json: JSONObject): SalaryEntry? {
    val month = json.optString("slm")
    if (monthKeyDate(month) == null) return null
    val amount = json.optDouble("sla", 0.0)
    if (!amount.isFinite() || amount <= 0.0) return null
    return SalaryEntry(
        id = json.optString("sli").ifBlank { UUID.randomUUID().toString() },
        month = month,
        kind = SalaryKind.of(json.optString("slk")),
        amount = amount,
        day = json.optLong("sld", 0L),
        note = json.optString("sln"),
        rate = json.optDouble("slr", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    )
}

fun salaryJson(entries: List<SalaryEntry>): String =
    JSONArray(entries.map { salaryEntryJson(it) }).toString()

/** A damaged or missing list reads as empty; one bad entry does not take the rest with it. */
fun salaryOf(text: String?): List<SalaryEntry> = runCatching {
    val array = JSONArray(text ?: "[]")
    (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let { salaryEntryOf(it) } }
}.getOrDefault(emptyList())

/**
 * One month of the log: what came in, in dollars and in hryvnias, and payment by payment.
 * [uah] counts only the payments whose rate is known; [uahKnown] says whether that is all of them.
 */
data class SalaryMonth(val month: String, val total: Double, val entries: List<SalaryEntry>) {
    val uah: Double get() = entries.sumOf { it.uah }
    val uahKnown: Boolean get() = entries.all { it.rate > 0.0 }
    fun sumOf(kind: SalaryKind): Double = entries.filter { it.kind == kind }.sumOf { it.amount }
    fun uahOf(kind: SalaryKind): Double = entries.filter { it.kind == kind }.sumOf { it.uah }
}

/** The log as months, newest first; inside a month, in the order the money came. */
fun salaryMonths(entries: List<SalaryEntry>): List<SalaryMonth> =
    entries.groupBy { it.month }
        .map { (month, list) ->
            val sorted = list.sortedWith(compareBy({ it.day }, { it.kind.ordinal }))
            SalaryMonth(month, sorted.sumOf { it.amount }, sorted)
        }
        .sortedByDescending { it.month }

/**
 * «аванс 14 000 ₴ · зарплата 24 000 ₴» — a month taken apart, kinds in a fixed order;
 * in hryvnias, which is what the pay is agreed in, and in dollars where a rate is missing.
 */
fun salaryBreakdown(month: SalaryMonth): String =
    SalaryKind.entries.mapNotNull { kind ->
        month.sumOf(kind).takeIf { it > 0.0 }?.let { usd ->
            val inKind = month.entries.filter { it.kind == kind }
            val shown = if (inKind.all { it.rate > 0.0 }) money(month.uahOf(kind)) else dollars(usd)
            "${kind.label.lowercase()} $shown"
        }
    }.joinToString(" · ")

/** «38 000 ₴», or the dollars when a payment in the month has no rate. */
fun salaryMainFigure(month: SalaryMonth): String = if (month.uahKnown) money(month.uah) else dollars(month.total)

/** «прийшло 915 $» — the dollars under the hryvnia figure; null when the figure is already dollars. */
fun salaryDollarLine(month: SalaryMonth): String? = if (month.uahKnown) "прийшло ${dollars(month.total)}" else null


/** A year's total and the average month — counted over the months that have anything in them. */
data class SalaryYear(val year: Int, val total: Double, val months: Int, val uah: Double = 0.0, val uahKnown: Boolean = true) {
    val average: Double get() = if (months > 0) total / months else 0.0
}

fun salaryYear(entries: List<SalaryEntry>, year: Int): SalaryYear {
    val months = salaryMonths(entries).filter { monthKeyDate(it.month)?.year == year }
    return SalaryYear(year, months.sumOf { it.total }, months.size, months.sumOf { it.uah }, months.all { it.uahKnown })
}

/** «Листопад 2026» moved by [by] months: the arrows beside the month in the form. */
fun shiftMonthKey(key: String, by: Long): String =
    monthKey((monthKeyDate(key) ?: LocalDate.now().withDayOfMonth(1)).plusMonths(by))

/**
 * The kind the form opens on. With an advance in the payday and none written down
 * for this month yet, it is the advance; once the advance is in, the salary.
 */
fun suggestedKind(entries: List<SalaryEntry>, month: String, payday: Payday): SalaryKind {
    val inMonth = entries.filter { it.month == month }
    val hasAdvance = inMonth.any { it.kind == SalaryKind.ADVANCE }
    val hasSalary = inMonth.any { it.kind == SalaryKind.SALARY }
    return when {
        payday.hasAdvance && !hasAdvance && !hasSalary -> SalaryKind.ADVANCE
        !hasSalary -> SalaryKind.SALARY
        else -> SalaryKind.OTHER
    }
}

/** The dollar amount last written for [kind] — offered as «як минулого разу» — or null with none. */
fun lastAmount(entries: List<SalaryEntry>, kind: SalaryKind): Double? =
    entries.filter { it.kind == kind }
        .maxWithOrNull(compareBy({ it.month }, { it.day }))
        ?.amount

/** The tile on Огляд: this month's figure, and the line under it. */
data class SalaryTileText(val value: String, val caption: String)

fun salaryTile(entries: List<SalaryEntry>, today: LocalDate): SalaryTileText {
    val month = salaryMonths(entries).firstOrNull { it.month == monthKey(today) }
    return if (month == null) {
        // Every month's name is masculine, so «за листопад» is the nominative as it stands.
        SalaryTileText("—", "за ${monthName(today.monthValue).lowercase()} ще не записано")
    } else {
        SalaryTileText(salaryMainFigure(month), listOfNotNull(salaryDollarLine(month), salaryBreakdown(month)).joinToString(" · "))
    }
}

/** Where the log lives: one JSON list in the app's preferences file. It goes into the backup. */
class SalaryStore(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    fun entries(): List<SalaryEntry> = salaryOf(prefs.getString(KEY, "[]"))

    fun save(entries: List<SalaryEntry>) {
        prefs.edit { putString(KEY, salaryJson(entries)) }
        version++
    }

    companion object {
        /** The log's key, which the backup also reads ([Store.exportJson]). */
        const val KEY = "sl_log"

        /** Bumped on every write — and on a restore — so an open screen re-reads. */
        var version by mutableIntStateOf(0)
    }
}

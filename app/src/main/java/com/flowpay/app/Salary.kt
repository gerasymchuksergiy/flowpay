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

// ------------------------------------------------------------ «Вставити списком»

/**
 * What a pasted list read as: the payments found, and the lines that were not
 * understood, shown back so nothing is dropped without a word.
 */
data class SalaryPaste(val entries: List<SalaryEntry>, val unreadable: List<String>)

private val PASTE_DATE = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4})""")
private val PASTE_MONTH = Regex("""за\s+(\d{1,2})\.(\d{4})""", RegexOption.IGNORE_CASE)
private val PASTE_RATE = Regex("""курс\s*(\d+(?:[.,]\d+)?)""", RegexOption.IGNORE_CASE)
private val PASTE_AMOUNT = Regex("""(\d[\d\s ]*(?:[.,]\d+)?)\s*\$""")

/**
 * Reads one payment per line, the way the owner's history was written down:
 *
 * `16.09.2026 аванс 500$ курс 41,2`
 * `03.10.2026 зарплата 520$ курс 41,3 за 09.2026`
 *
 * The date is the day the money came; «за ММ.РРРР» is the month it is for, and
 * without it, the date's own month; «курс» is optional (without it the payment
 * is in dollars only). The kind is «аванс», «зарплата», anything else is «Інше»
 * with the line's leftover words as its note. Blank lines are skipped.
 */
fun parseSalaryLines(text: String): SalaryPaste {
    val found = mutableListOf<SalaryEntry>()
    val bad = mutableListOf<String>()
    text.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { line ->
        val entry = runCatching { salaryLine(line) }.getOrNull()
        if (entry != null) found += entry else bad += line
    }
    return SalaryPaste(found, bad)
}

private fun salaryLine(line: String): SalaryEntry? {
    var rest = line
    val monthMatch = PASTE_MONTH.find(rest)
    monthMatch?.let { rest = rest.replace(it.value, " ") }
    val dateMatch = PASTE_DATE.find(rest) ?: return null
    rest = rest.replace(dateMatch.value, " ")
    val (d, m, y) = dateMatch.destructured
    val day = LocalDate.of(y.toInt(), m.toInt(), d.toInt())
    val rateMatch = PASTE_RATE.find(rest)
    rateMatch?.let { rest = rest.replace(it.value, " ") }
    val amountMatch = PASTE_AMOUNT.find(rest) ?: return null
    rest = rest.replace(amountMatch.value, " ")
    val amount = parseAmount(amountMatch.groupValues[1]).takeIf { it > 0.0 } ?: return null
    val month = monthMatch?.let { monthKey(LocalDate.of(it.groupValues[2].toInt(), it.groupValues[1].toInt(), 1)) }
        ?: monthKey(day)
    val words = rest.replace(Regex("""[·,;:—–-]"""), " ").trim().replace(Regex("""\s+"""), " ")
    val lower = words.lowercase()
    val kind = when {
        "аванс" in lower -> SalaryKind.ADVANCE
        "зарплат" in lower || "зп" == lower -> SalaryKind.SALARY
        else -> SalaryKind.OTHER
    }
    val note = if (kind == SalaryKind.OTHER) words.removePrefix("інше").removePrefix("Інше").trim() else ""
    return SalaryEntry(
        month = month,
        kind = kind,
        amount = amount,
        day = day.toEpochDay(),
        note = note.take(60),
        rate = rateMatch?.let { parseAmount(it.groupValues[1]) }?.takeIf { it > 0.0 } ?: 0.0
    )
}

/**
 * The pasted payments not already in the log — so pasting the same list twice,
 * or a list that overlaps what was typed by hand, adds nothing twice. The same
 * payment means the same month, kind, dollar amount and day.
 */
fun newPayments(existing: List<SalaryEntry>, pasted: List<SalaryEntry>): List<SalaryEntry> {
    fun same(a: SalaryEntry, b: SalaryEntry) =
        a.month == b.month && a.kind == b.kind && a.day == b.day && kotlin.math.abs(a.amount - b.amount) < 0.005
    val kept = mutableListOf<SalaryEntry>()
    pasted.forEach { one -> if (existing.none { same(it, one) } && kept.none { same(it, one) }) kept += one }
    return kept
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

package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * monobank, read with the owner's own personal token — the pure half: what the
 * API answers, and what FlowPay makes of it. The network, the token and the
 * background pass are in MonoSync.kt; the screens in MonoUi.kt.
 *
 * Asked for on 4 October 2026 («роби monobank»), after the research into ten
 * other apps found seven of them leaning on the same thing: the bank already
 * knows what was paid and how much is there. Two rules from that research shape
 * everything here:
 *
 * - **Ask before deciding.** A wrong automatic tick is worse than none. A charge
 *   that looks like a payment is offered — «Це Netflix? Так / Ні» — and only once
 *   the owner has said yes does that merchant tick that payment by itself.
 * - **Nothing leaves the phone.** The API is read-only and called directly; the
 *   statement is kept in the app's own storage, out of every backup and export.
 *
 * API facts (api.monobank.ua/docs, checked 4 October 2026): `X-Token` header;
 * `/personal/client-info` and `/personal/statement/{account}/{from}/{to}`; one
 * request per 60 s; a statement covers at most 31 days and one hour and returns
 * at most 500 operations; amounts are integers in minor units (kopecks), negative
 * for money going out; `amount` is in the account's currency, `operationAmount`
 * and `currencyCode` in the operation's.
 */

const val UAH_CODE = 980
const val USD_CODE = 840
const val EUR_CODE = 978

/** One of the owner's accounts (a card) as client-info describes it. */
data class MonoAccount(
    val id: String,
    val currencyCode: Int,
    /** Minor units, the bank's credit line included. */
    val balance: Long,
    val creditLimit: Long,
    /** «black», «white», «platinum», «iron», «fop», «yellow», «eAid»… */
    val type: String,
    val maskedPan: List<String>,
    val iban: String
) {
    /** The owner's own money, without the bank's credit line, in minor units. */
    val own: Long get() = balance - creditLimit

    /** «•••• 1234», or the account type when the bank shows no card. */
    val label: String get() = maskedPan.firstOrNull()?.takeLast(4)?.let { "•••• $it" } ?: type
}

/** A monobank jar (банка): money put aside, with an optional goal. */
data class MonoJar(
    val id: String,
    val title: String,
    val currencyCode: Int,
    /** Minor units. */
    val balance: Long,
    /** Minor units. Nought when the jar has no goal. */
    val goal: Long
)

data class MonoClient(
    val name: String,
    val accounts: List<MonoAccount>,
    val jars: List<MonoJar>
)

/** One operation from a statement. */
data class MonoTx(
    val id: String,
    /** Unix seconds. */
    val time: Long,
    val description: String,
    val mcc: Int,
    /** Minor units of the account's currency; negative when money left. */
    val amount: Long,
    /** Minor units of the operation's own currency. */
    val operationAmount: Long,
    /** The operation's currency, ISO 4217 numeric. */
    val currencyCode: Int,
    /** Still pending at the bank. */
    val hold: Boolean,
    /** Which of the owner's accounts it came from. */
    val account: String
) {
    val isDebit: Boolean get() = amount < 0
}

// ------------------------------------------------------------ parsing

fun parseMonoClient(json: String): MonoClient {
    val o = JSONObject(json)
    val accounts = o.optJSONArray("accounts") ?: JSONArray()
    val jars = o.optJSONArray("jars") ?: JSONArray()
    return MonoClient(
        name = o.optString("name"),
        accounts = (0 until accounts.length()).mapNotNull { accounts.optJSONObject(it) }.map { a ->
            val pans = a.optJSONArray("maskedPan") ?: JSONArray()
            MonoAccount(
                id = a.optString("id"),
                currencyCode = a.optInt("currencyCode", UAH_CODE),
                balance = a.optLong("balance", 0L),
                creditLimit = a.optLong("creditLimit", 0L),
                type = a.optString("type"),
                maskedPan = (0 until pans.length()).map { pans.optString(it) },
                iban = a.optString("iban")
            )
        }.filter { it.id.isNotBlank() },
        jars = (0 until jars.length()).mapNotNull { jars.optJSONObject(it) }.map { j ->
            MonoJar(
                id = j.optString("id"),
                title = j.optString("title"),
                currencyCode = j.optInt("currencyCode", UAH_CODE),
                balance = j.optLong("balance", 0L),
                goal = j.optLong("goal", 0L)
            )
        }.filter { it.id.isNotBlank() }
    )
}

fun monoClientJson(client: MonoClient): JSONObject = JSONObject()
    .put("name", client.name)
    .put("accounts", JSONArray(client.accounts.map { a ->
        JSONObject().put("id", a.id).put("currencyCode", a.currencyCode).put("balance", a.balance)
            .put("creditLimit", a.creditLimit).put("type", a.type)
            .put("maskedPan", JSONArray(a.maskedPan)).put("iban", a.iban)
    }))
    .put("jars", JSONArray(client.jars.map { j ->
        JSONObject().put("id", j.id).put("title", j.title).put("currencyCode", j.currencyCode)
            .put("balance", j.balance).put("goal", j.goal)
    }))

fun parseMonoStatement(json: String, account: String): List<MonoTx> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { monoTxOf(it, account) }
        .filter { it.id.isNotBlank() }
}

fun monoTxOf(o: JSONObject, account: String = o.optString("acc")): MonoTx = MonoTx(
    id = o.optString("id"),
    time = o.optLong("time", 0L),
    description = o.optString("description"),
    mcc = o.optInt("mcc", 0),
    amount = o.optLong("amount", 0L),
    operationAmount = o.optLong("operationAmount", o.optLong("amount", 0L)),
    currencyCode = o.optInt("currencyCode", UAH_CODE),
    hold = o.optBoolean("hold", false),
    account = account
)

/** The cached form: only what FlowPay uses, nothing about the counterparty. */
fun monoTxJson(tx: MonoTx): JSONObject = JSONObject()
    .put("id", tx.id).put("time", tx.time).put("description", tx.description).put("mcc", tx.mcc)
    .put("amount", tx.amount).put("operationAmount", tx.operationAmount)
    .put("currencyCode", tx.currencyCode).put("hold", tx.hold).put("acc", tx.account)

/** New operations laid over the cached ones by id (a hold settles into the same id), oldest dropped. */
fun mergeMonoTx(cached: List<MonoTx>, fresh: List<MonoTx>, keepFrom: Long): List<MonoTx> =
    (cached.associateBy { it.id } + fresh.associateBy { it.id }).values
        .filter { it.time >= keepFrom }
        .sortedByDescending { it.time }

/** The statement windows to ask for, newest last: at most 31 days each, the API's limit. */
fun statementWindows(from: Long, to: Long): List<Pair<Long, Long>> {
    if (to <= from) return emptyList()
    val out = ArrayList<Pair<Long, Long>>()
    var start = from
    while (start < to) {
        val end = minOf(to, start + STATEMENT_WINDOW_S)
        out += start to end
        start = end
    }
    return out
}

/** 31 days: the longest statement the API answers in one request. */
const val STATEMENT_WINDOW_S = 31L * 24 * 60 * 60

// ------------------------------------------------------------ merchants

/**
 * The part of an operation's description that names who took the money,
 * comparable across months: lower-case, letters only, the first three words.
 * «Netflix.com» → «netflix com»; «Google *YouTube Premium» → «google youtube premium».
 */
fun merchantKey(description: String): String =
    description.lowercase()
        .map { if (it.isLetter()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.length >= 2 }
        .take(3)
        .joinToString(" ")

private val NAME_NOISE = setOf(
    "підписка", "оплата", "платіж", "плата", "мій", "моя", "моє", "мої", "за", "на", "для", "та", "і",
    "місяць", "тариф", "рахунок", "послуги", "premium", "plus", "pro", "the", "app"
)

/** Spellings of a payment's name the bank might use instead. */
private val NAME_ALIASES = mapOf(
    "youtube" to listOf("google"),
    "icloud" to listOf("apple"),
    "київстар" to listOf("kyivstar"),
    "водафон" to listOf("vodafone"),
    "лайфсел" to listOf("lifecell"),
    "нетфлікс" to listOf("netflix"),
    "спотіфай" to listOf("spotify"),
    "мегого" to listOf("megogo"),
    "інтернет" to emptyList(),
    "chatgpt" to listOf("openai"),
    "claude" to listOf("anthropic")
)

/** Ukrainian letters in Latin, the official way (КМУ 2010), close enough to what banks print. */
fun transliterate(word: String): String {
    val map = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e", 'є' to "ie",
        'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "i", 'й' to "i", 'к' to "k", 'л' to "l",
        'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch", 'ь' to "", 'ю' to "iu",
        'я' to "ia", '\'' to "", '’' to ""
    )
    return word.lowercase().map { map[it] ?: it.toString() }.joinToString("")
}

/** The words of a payment's name worth looking for in a bank description. */
fun nameTokens(name: String): List<String> {
    val words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 3 && it !in NAME_NOISE }
    return words.flatMap { word ->
        listOf(word, transliterate(word)) + (NAME_ALIASES[word] ?: emptyList())
    }.filter { it.length >= 3 }.distinct()
}

/** Whether an operation's description names this payment. */
fun namesPayment(description: String, pay: Pay): Boolean {
    val text = merchantKey(description).replace(" ", "") + " " + description.lowercase()
    return nameTokens(pay.name).any { it in text }
}

// ------------------------------------------------------------ amounts

/**
 * What an operation took, in the payment's own currency, or null when the two
 * cannot be compared (a dollar charge from a dollar account against a hryvnia
 * bill, with no rate).
 */
fun chargedIn(tx: MonoTx, pay: Pay, accountCurrency: Int, usdSell: Double): Double? {
    if (!tx.isDebit) return null
    return when (pay.currency) {
        USD -> when {
            tx.currencyCode == USD_CODE -> -tx.operationAmount / 100.0
            accountCurrency == USD_CODE -> -tx.amount / 100.0
            usdSell > 0.0 && accountCurrency == UAH_CODE -> -tx.amount / 100.0 / usdSell
            else -> null
        }
        else -> when (accountCurrency) {
            UAH_CODE -> -tx.amount / 100.0
            else -> if (tx.currencyCode == UAH_CODE) -tx.operationAmount / 100.0 else null
        }
    }
}

/**
 * Whether [charged] is this payment's amount, give or take what banks and
 * currencies do to it: 6 % or 2 ₴ for hryvnias, 3 % for dollars charged in
 * dollars, 8 % for dollars charged in hryvnias at a rate that is not quite ours.
 */
fun amountFits(charged: Double, pay: Pay, convertedFromUah: Boolean): Boolean {
    if (pay.amount <= 0.0) return false
    val gap = kotlin.math.abs(charged - pay.amount)
    return when {
        pay.currency == USD && convertedFromUah -> gap <= pay.amount * 0.08
        pay.currency == USD -> gap <= maxOf(0.1, pay.amount * 0.03)
        else -> gap <= maxOf(2.0, pay.amount * 0.06)
    }
}

/** Whether [value] is within [share] of [target], either way. */
fun withinShare(value: Double, target: Double, share: Double): Boolean =
    target > 0.0 && kotlin.math.abs(value - target) <= target * share

// ------------------------------------------------------------ matching

/** How sure a match is. */
enum class MonoMatchKind {
    /** The owner said yes to this merchant for this payment before: ticked by itself. */
    LEARNED,
    /** The description names the payment, the amount and the date fit: asked about. */
    NAMED,
    /** Only the amount and the date fit: asked about, more carefully worded. */
    AMOUNT_ONLY
}

/** One operation that looks like a payment for one month. */
data class MonoMatch(
    val pay: Pay,
    /** The month the payment is for, "2026-10". */
    val month: String,
    val tx: MonoTx,
    /** What it took, in the payment's currency. */
    val charged: Double,
    val kind: MonoMatchKind
) {
    /** The payment now costs noticeably more or less than it says. */
    val drifted: Boolean get() = kotlin.math.abs(charged - pay.amount) > maxOf(1.0, pay.amount * 0.02)
}

/** How far around a due date a charge is looked for: banks charge early on weekends, services late. */
private const val EARLY_DAYS = 4L
private const val LATE_DAYS = 6L

/**
 * The payments the statement has an answer for.
 *
 * For every payment still unmarked in a month whose due date is behind it (this
 * month's or last month's), the operation that fits best: on the right days, for
 * the right amount, preferably from the merchant the owner confirmed before, then
 * one whose description names it, then — only when the amount is near exact — any.
 * Each operation answers one payment at most. [rejected] holds «operation|payment»
 * pairs the owner said no to, which are never offered again.
 */
fun monoMatches(
    pays: List<Pay>,
    txs: List<MonoTx>,
    marks: List<PaidMark>,
    rejected: Set<String>,
    accountCurrency: Map<String, Int>,
    today: LocalDate,
    usdSell: Double,
    zone: ZoneId = ZoneId.systemDefault()
): List<MonoMatch> {
    val used = HashSet<String>()
    val out = ArrayList<MonoMatch>()
    val debits = txs.filter { it.isDebit && it.mcc !in NOT_A_PAYMENT_MCC }
    for (pay in pays) {
        if (isFinished(pay, today)) continue
        for (due in recentDueDates(pay, today)) {
            val month = monthKey(due)
            if (isPaid(marks, pay.name, month)) continue
            if (onTrial(pay, due.toEpochDay())) continue
            val candidates = debits.mapNotNull { tx ->
                if (tx.id in used || "${tx.id}|${pay.name}" in rejected) return@mapNotNull null
                val day = Instant.ofEpochSecond(tx.time).atZone(zone).toLocalDate()
                if (day.isBefore(due.minusDays(EARLY_DAYS)) || day.isAfter(due.plusDays(LATE_DAYS))) return@mapNotNull null
                val currency = accountCurrency[tx.account] ?: UAH_CODE
                val charged = chargedIn(tx, pay, currency, usdSell) ?: return@mapNotNull null
                val converted = pay.currency == USD && tx.currencyCode != USD_CODE && currency == UAH_CODE
                val kind = when {
                    pay.monoMerchant.isNotBlank() && merchantKey(tx.description) == pay.monoMerchant -> MonoMatchKind.LEARNED
                    namesPayment(tx.description, pay) -> MonoMatchKind.NAMED
                    // Amount alone is a weak sign: only a near-exact one, close to the date.
                    kotlin.math.abs(charged - pay.amount) <= maxOf(0.5, pay.amount * 0.005) &&
                        kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(due, day)) <= 2 -> MonoMatchKind.AMOUNT_ONLY
                    else -> return@mapNotNull null
                }
                // The merchant is the evidence for the first two, so a raised price
                // still matches — and is shown as one ([MonoMatch.drifted]) — while a
                // charge of a quite different size from the same shop does not.
                val fits = when (kind) {
                    MonoMatchKind.LEARNED -> withinShare(charged, pay.amount, 0.4)
                    MonoMatchKind.NAMED -> withinShare(charged, pay.amount, 0.25) || amountFits(charged, pay, converted)
                    MonoMatchKind.AMOUNT_ONLY -> true
                }
                if (!fits) return@mapNotNull null
                MonoMatch(pay, month, tx, charged, kind)
            }
            val best = candidates.minWithOrNull(
                compareBy<MonoMatch>({ it.kind.ordinal }, { kotlin.math.abs(it.charged - pay.amount) })
            ) ?: continue
            used += best.tx.id
            out += best
        }
    }
    return out.sortedByDescending { it.tx.time }
}

/**
 * The due dates a statement can already have an answer for: this month's, if it
 * is not more than a few days ahead, and last month's.
 */
fun recentDueDates(pay: Pay, today: LocalDate): List<LocalDate> {
    val thisMonth = today.withDayOfMonth(1)
    return listOf(thisMonth.minusMonths(1), thisMonth).mapNotNull { month ->
        if (!chargesIn(pay, month)) return@mapNotNull null
        val due = month.withDayOfMonth(effectivePaymentDay(pay.day, month.lengthOfMonth()))
        due.takeIf { !it.isAfter(today.plusDays(EARLY_DAYS)) }
    }
}

/**
 * Codes that are never a payment to anyone: cash, transfers between people and
 * own accounts, top-ups. Without this, «переказ 300 ₴ мамі» matched the 300 ₴
 * internet bill on the same day.
 */
val NOT_A_PAYMENT_MCC = setOf(4829, 6010, 6011, 6012, 6051, 6536, 6537, 6538, 6540)

// ------------------------------------------------------------ forgotten subscriptions

/** A charge that comes back every month and is not on the list. */
data class FoundSubscription(
    /** [merchantKey] of the operations, what «не підписка» remembers. */
    val key: String,
    /** The latest description as the bank wrote it. */
    val title: String,
    val amount: Double,
    /** [UAH] or [USD]. */
    val currency: String,
    /** Day of the month of the latest charge. */
    val day: Int,
    /** How many monthly charges in a row were seen. */
    val times: Int,
    /** Unix seconds of the latest. */
    val last: Long
)

/**
 * Recurring charges nobody told FlowPay about.
 *
 * The same merchant, twice or more, 25–36 days apart, for amounts within a tenth
 * of each other, the latest within the last 40 days — that is a subscription
 * whether or not anyone remembers signing up. Merchants already tied to a
 * payment (by a confirmed match or by name) and ones the owner dismissed are
 * left out.
 */
fun findSubscriptions(
    txs: List<MonoTx>,
    pays: List<Pay>,
    ignored: Set<String>,
    accountCurrency: Map<String, Int>,
    now: Long
): List<FoundSubscription> {
    val known = pays.map { it.monoMerchant }.filter { it.isNotBlank() }.toSet()
    return txs
        .filter { it.isDebit && it.mcc !in NOT_A_PAYMENT_MCC }
        .groupBy { merchantKey(it.description) }
        .filterKeys { it.isNotBlank() && it !in ignored && it !in known }
        .mapNotNull { (key, group) ->
            val sorted = group.sortedBy { it.time }
            val latest = sorted.last()
            if (now - latest.time > 40L * 86_400) return@mapNotNull null
            if (pays.any { namesPayment(latest.description, it) }) return@mapNotNull null
            val inDollars = sorted.all { it.currencyCode == USD_CODE }
            fun value(tx: MonoTx): Double =
                if (inDollars) -tx.operationAmount / 100.0
                else if ((accountCurrency[tx.account] ?: UAH_CODE) == UAH_CODE) -tx.amount / 100.0
                else -tx.operationAmount / 100.0
            // Walk back from the latest while the rhythm and the amount hold.
            var chain = 1
            var current = latest
            for (earlier in sorted.dropLast(1).reversed()) {
                val gapDays = (current.time - earlier.time) / 86_400.0
                if (gapDays < 20) continue
                if (gapDays in 25.0..36.0 && kotlin.math.abs(value(earlier) - value(latest)) <= value(latest) * 0.1) {
                    chain++
                    current = earlier
                } else {
                    break
                }
            }
            if (chain < 2) return@mapNotNull null
            FoundSubscription(
                key = key,
                title = latest.description,
                amount = value(latest),
                currency = if (inDollars) USD else UAH,
                day = Instant.ofEpochSecond(latest.time).atZone(ZoneId.systemDefault()).dayOfMonth,
                times = chain,
                last = latest.time
            )
        }
        .sortedByDescending { it.amount }
}

// ------------------------------------------------------------ balance

/** The owner's own money on the hryvnia accounts that were chosen, in hryvnias. */
fun ownUah(client: MonoClient, chosen: Set<String>): Double =
    client.accounts.filter { it.currencyCode == UAH_CODE && (chosen.isEmpty() || it.id in chosen) }
        .sumOf { it.own } / 100.0

/**
 * The sentence the weather tile adds when the card's balance is known: whether it
 * covers the week's charges, and if not, by how much it falls short and by when.
 */
fun balanceLine(balance: Double, week: List<MoneyDay>): String {
    var left = balance
    for (day in week) {
        left -= day.leaving
        if (left < 0) {
            return "На картці ${approxMoney(balance)} — до ${dayMonth(day.date)} не вистачить ${approxMoney(-left)}"
        }
    }
    val leaving = week.sumOf { it.leaving }
    return if (leaving > 0) {
        "На картці ${approxMoney(balance)} — вистачить на всі списання тижня"
    } else {
        "На картці ${approxMoney(balance)}"
    }
}

/** A jar's money in hryvnias, or null for a jar in another currency. */
fun jarUah(jar: MonoJar): Double? = if (jar.currencyCode == UAH_CODE) jar.balance / 100.0 else null

// ------------------------------------------------------------ answers

/**
 * The marks with this match's month ticked, at what the bank actually took — so
 * «По місяцях» shows the real figure, a raise included, from the first month.
 * Nothing changes when the month is already marked.
 */
fun withMonoMark(marks: List<PaidMark>, match: MonoMatch): List<PaidMark> =
    if (isPaid(marks, match.pay.name, match.month)) {
        marks
    } else {
        marks + PaidMark(match.pay.name, match.month, kotlin.math.round(match.charged * 100) / 100.0, match.pay.currency)
    }

/**
 * A payment's name from a bank description, for «Додати»: «NETFLIX.COM» reads as
 * «Netflix.com»; anything already in mixed case is left as the bank wrote it.
 */
fun prettyMerchant(description: String): String {
    val trimmed = description.trim()
    if (trimmed != trimmed.uppercase()) return trimmed
    return trimmed.lowercase().split(' ').joinToString(" ") { word -> word.replaceFirstChar { it.uppercase() } }
}

/**
 * Confirmed payments whose last charge differs from the amount FlowPay holds:
 * the quiet raise, caught by the bank rather than by memory. Each with what was
 * actually charged, for «Оновити суму».
 */
fun monoDrifts(pays: List<Pay>, marks: List<PaidMark>, today: LocalDate): List<Pair<Pay, Double>> {
    val months = listOf(monthKey(today), monthKey(today.minusMonths(1)))
    return pays.filter { it.monoMerchant.isNotBlank() && !isFinished(it, today) }.mapNotNull { pay ->
        val mark = months.firstNotNullOfOrNull { month ->
            marks.firstOrNull { it.name == pay.name && it.month == month && it.currency == pay.currency }
        } ?: return@mapNotNull null
        if (kotlin.math.abs(mark.amount - pay.amount) > maxOf(1.0, pay.amount * 0.02)) pay to mark.amount else null
    }
}

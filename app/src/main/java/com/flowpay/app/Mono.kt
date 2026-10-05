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

/** How far back a card is read the first time: three months, for the subscription finder. */
const val STATEMENT_HISTORY_S = 93L * 24 * 60 * 60

/** Read again at the recent edge: a hold settles under the same id a day or two later. */
const val STATEMENT_OVERLAP_S = 2L * 24 * 60 * 60

/** One statement request: which account, and the window in unix seconds. */
data class StatementCall(val account: String, val from: Long, val to: Long)

/**
 * Every statement request still needed to bring [accounts] up to [now], oldest
 * first for each: from where the account was last read ([readUntil]), or three
 * months back for one never read.
 *
 * The two-day overlap is only for the recent edge. Further back — a first load
 * picked up again after the phone stopped it — the account is complete up to the
 * point recorded, and an overlap there would only cost one more request, which is
 * a minute.
 */
fun statementPlan(accounts: Collection<String>, readUntil: Map<String, Long>, now: Long): List<StatementCall> {
    val oldest = now - STATEMENT_HISTORY_S
    return accounts.flatMap { account ->
        val until = readUntil[account]
        val from = when {
            until == null -> oldest
            now - until <= STATEMENT_WINDOW_S - STATEMENT_OVERLAP_S -> until - STATEMENT_OVERLAP_S
            else -> until
        }
        statementWindows(maxOf(from, oldest), now).map { (start, end) -> StatementCall(account, start, end) }
    }
}

/**
 * «Завантажую виписку — ще ≈7 хв» while [left] requests remain: a minute each,
 * the API's limit, and one more for the account check that opens a pass.
 */
fun loadingLine(left: Int): String = "Завантажую виписку — ще ≈${left + 1} хв"

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
    for (original in pays) {
        if (isFinished(original, today)) continue
        for (due in recentDueDates(original, today)) {
            val month = monthKey(due)
            if (isPaid(marks, original.name, month)) continue
            // Free that month: nothing was owed. A promo month is matched against
            // the promo price, which is what the bank will have taken.
            val price = priceOn(original, due.toEpochDay())
            if (price <= 0.0) continue
            val pay = if (price == original.amount) original else original.copy(amount = price)
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
    return pays.filter { it.monoMerchant.isNotBlank() && isLive(it, today) }.mapNotNull { pay ->
        val mark = months.firstNotNullOfOrNull { month ->
            marks.firstOrNull { it.name == pay.name && it.month == month && it.currency == pay.currency }
        } ?: return@mapNotNull null
        // Against what that month's charge was meant to take: a promo month marked
        // at the promo price is not a price change.
        val planned = monthKeyDate(mark.month)?.let { priceOn(pay, chargeDateIn(pay, it).toEpochDay()) } ?: pay.amount
        if (planned <= 0.0) return@mapNotNull null
        if (kotlin.math.abs(mark.amount - planned) > maxOf(1.0, planned * 0.02)) pay to mark.amount else null
    }
}

// ------------------------------------------------------------ what the statement still tells

/*
 * The second round of monobank (4 October 2026, «додай все»): what the stored
 * statement says about a payment's life, read-only like everything else here.
 * Each answer is a question or a line, never a decision: a quiet merchant is
 * asked about («Скасовано?»), a double charge is pointed at, a charge after a
 * cancellation is asked about, a missed charge is mentioned once. The two things
 * done without asking are the two the owner set up to be done: a paused payment
 * whose confirmed merchant charges again runs again, and a jar that covers a
 * wish's price is announced once.
 */

/** The day an operation happened, on the phone's calendar. */
fun txDay(tx: MonoTx, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
    Instant.ofEpochSecond(tx.time).atZone(zone).toLocalDate()

/** Charges from the merchant the owner confirmed for this payment, newest first. */
fun merchantDebits(pay: Pay, txs: List<MonoTx>): List<MonoTx> =
    if (pay.monoMerchant.isBlank()) {
        emptyList()
    } else {
        txs.filter { it.isDebit && it.mcc !in NOT_A_PAYMENT_MCC && merchantKey(it.description) == pay.monoMerchant }
            .sortedByDescending { it.time }
    }

/**
 * The due date a charge on [day] pays for — the one within the matching window
 * (four days early, six late), the closest when two are — or null when none is.
 * Asked of the rhythm alone, not of [runsOn]: a paused payment still has dates.
 */
fun dueDateFor(pay: Pay, day: LocalDate): LocalDate? =
    listOf(day.minusMonths(1), day, day.plusMonths(1))
        .filter { !isAnnual(pay) || it.monthValue == pay.billingMonth }
        .map { chargeDateIn(pay, it) }
        .filter { !day.isBefore(it.minusDays(EARLY_DAYS)) && !day.isAfter(it.plusDays(LATE_DAYS)) }
        .minByOrNull { kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(it, day)) }

/** The latest date on or before [today] this payment was due on. */
fun lastDueDate(pay: Pay, today: LocalDate): LocalDate {
    if (isAnnual(pay)) {
        val thisYear = chargeDateIn(pay, LocalDate.of(today.year, pay.billingMonth, 1))
        return if (!thisYear.isAfter(today)) thisYear else chargeDateIn(pay, LocalDate.of(today.year - 1, pay.billingMonth, 1))
    }
    val thisMonth = chargeDateIn(pay, today)
    return if (!thisMonth.isAfter(today)) thisMonth else chargeDateIn(pay, today.minusMonths(1))
}

// ------------------------------------------------------------ a paused payment charges again

/**
 * The list with every paused payment whose confirmed merchant charged again
 * running again — the research's «Пауза»: when the bank sees a charge, the pause
 * is lifted. The pause ends at the due date the charge pays for, so that charge
 * is counted and ticked like any other, and the amount is kept for the morning's
 * «Megogo знову списує 199 ₴».
 *
 * Only a charge for a date inside the pause counts: a late charge for the month
 * before it is not the payment coming back. And only one near the payment's own
 * price — another purchase from the same shop is not the subscription.
 */
fun bankResumed(
    pays: List<Pay>,
    txs: List<MonoTx>,
    accountCurrency: Map<String, Int>,
    usdSell: Double,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): List<Pay> = pays.map { pay ->
    if (pay.pausedFrom <= 0L || pay.monoMerchant.isBlank() || isInstalment(pay)) return@map pay
    val found = merchantDebits(pay, txs).sortedBy { it.time }.firstNotNullOfOrNull { tx ->
        val day = txDay(tx, zone)
        val due = dueDateFor(pay, day)
        val inside = if (due != null) due.toEpochDay() >= pay.pausedFrom else day.toEpochDay() >= pay.pausedFrom + LATE_DAYS
        if (!inside || day.toEpochDay() < pay.pausedFrom) return@firstNotNullOfOrNull null
        val charged = chargedIn(tx, pay, accountCurrency[tx.account] ?: UAH_CODE, usdSell) ?: return@firstNotNullOfOrNull null
        if (!withinShare(charged, pay.amount, 0.4)) return@firstNotNullOfOrNull null
        (if (due != null && due.isBefore(day)) due else day) to charged
    } ?: return@map pay
    val (from, charged) = found
    resumed(pay, today, until = from.toEpochDay(), bankCharge = kotlin.math.round(charged * 100) / 100.0)
}

// ------------------------------------------------------------ «Мовчать»

/** A confirmed payment the bank has gone quiet about. */
data class SilentPay(
    val pay: Pay,
    /** Days since the last charge — or, for an annual fee, since the date it was due. */
    val days: Int,
    val annual: Boolean,
    /** The last charge seen from its merchant. */
    val last: MonoTx
)

/** A month and ten days without a charge is a monthly subscription that stopped. */
const val SILENT_MONTHLY_DAYS = 40

/** Ten days past its date without a charge is an annual fee that did not renew. */
const val SILENT_ANNUAL_DAYS = 10

/** How long «Ще чекаю» quiets the question. */
const val SILENT_WAIT_DAYS = 30L

/**
 * «Spotify: списань не було 47 днів. Скасовано?» — the Rocket Money block, read
 * from the statement: confirmed payments whose merchant has charged nothing for
 * 40 days (monthly) or for 10 days past the date (annual).
 *
 * Left out: a payment paused, cancelled or free this time (nothing was owed), one
 * whose last due month was ticked by hand (it is being paid some other way), one
 * with no charge in the stored statement at all (the app cannot say how long),
 * and one the owner said «Ще чекаю» about ([waiting], name to the epoch day the
 * question may come back).
 */
fun silentPayments(
    pays: List<Pay>,
    txs: List<MonoTx>,
    marks: List<PaidMark>,
    waiting: Map<String, Long>,
    today: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): List<SilentPay> = pays
    .filter { it.monoMerchant.isNotBlank() && !isInstalment(it) && lifeOf(it, today) == PayLife.RUNNING }
    .filterNot { (waiting[it.name] ?: 0L) > today.toEpochDay() }
    .mapNotNull { pay ->
        val last = merchantDebits(pay, txs).firstOrNull() ?: return@mapNotNull null
        val due = lastDueDate(pay, today)
        if (!chargesIn(pay, due) || priceOn(pay, due.toEpochDay()) <= 0.0) return@mapNotNull null
        if (isPaid(marks, pay.name, monthKey(due))) return@mapNotNull null
        val lastDay = txDay(last, zone)
        if (isAnnual(pay)) {
            val since = java.time.temporal.ChronoUnit.DAYS.between(due, today).toInt()
            val chargedAround = !lastDay.isBefore(due.minusDays(EARLY_DAYS))
            if (since < SILENT_ANNUAL_DAYS || chargedAround) null else SilentPay(pay, since, annual = true, last = last)
        } else {
            val days = java.time.temporal.ChronoUnit.DAYS.between(lastDay, today).toInt()
            if (days < SILENT_MONTHLY_DAYS) null else SilentPay(pay, days, annual = false, last = last)
        }
    }

/** «Spotify: списань не було 47 днів» — no verb that would have to agree with the name. */
fun silentLine(silent: SilentPay): String =
    if (silent.annual) {
        "${silent.pay.name}: дата минула ${daysLabel(silent.days)} тому, а річного списання не було"
    } else {
        "${silent.pay.name}: списань не було ${daysLabel(silent.days)}"
    }

/**
 * The «діє до» a quiet payment is cancelled with from «Прибрати»: the end of the
 * period its last charge paid for — already behind, which is why the bank went
 * quiet.
 */
fun silentPaidUntil(silent: SilentPay, zone: ZoneId = ZoneId.systemDefault()): LocalDate {
    val last = txDay(silent.last, zone)
    return (if (silent.annual) last.plusYears(1) else last.plusMonths(1)).minusDays(1)
}

// ------------------------------------------------------------ «Схоже на подвійне списання»

/** The same confirmed merchant taking the same amount twice within a few days. */
data class DoubleCharge(val pay: Pay, val first: MonoTx, val second: MonoTx) {
    /** The two operations in a fixed order: what «Усе гаразд» remembers. */
    val key: String get() = listOf(first.id, second.id).sorted().joinToString("|")
}

/** Charges this close are one charge too many; further apart they can be two months. */
const val DOUBLE_CHARGE_DAYS = 3L

/** How far back a double charge is still worth pointing at. */
const val DOUBLE_LOOKBACK_DAYS = 30L

/**
 * «Схоже на подвійне списання: Megogo 199 ₴ × 2» — the same confirmed merchant,
 * the same amount to the kopeck, twice within three days. A hold that settles is
 * one operation under one id, so it is never mistaken for two.
 */
fun doubleCharges(pays: List<Pay>, txs: List<MonoTx>, dismissed: Set<String>, now: Long): List<DoubleCharge> =
    pays.filter { it.monoMerchant.isNotBlank() }.flatMap { pay ->
        val debits = merchantDebits(pay, txs)
            .filter { now - it.time <= DOUBLE_LOOKBACK_DAYS * 86_400 }
            .sortedBy { it.time }
        buildList {
            for (i in debits.indices) {
                for (j in i + 1 until debits.size) {
                    val first = debits[i]
                    val second = debits[j]
                    if (second.time - first.time > DOUBLE_CHARGE_DAYS * 86_400) break
                    if (first.amount == second.amount && first.id != second.id) add(DoubleCharge(pay, first, second))
                }
            }
        }
    }.distinctBy { it.key }.filterNot { it.key in dismissed }

/** «Megogo 199 ₴ × 2 · 2 і 3 жовтня» */
fun doubleChargeLine(double: DoubleCharge, accountCurrency: Map<String, Int>, zone: ZoneId = ZoneId.systemDefault()): String {
    val one = txDay(double.first, zone)
    val two = txDay(double.second, zone)
    val days = if (one == two) "двічі ${dayMonth(one)}" else "${one.dayOfMonth} і ${dayMonth(two)}"
    val amount = amountLabelMinor(-double.first.amount, accountCurrency[double.first.account] ?: UAH_CODE)
    return "${double.pay.name} $amount × 2 · $days"
}

// ------------------------------------------------------------ «Списали після скасування?»

/**
 * A cancelled payment's merchant, kept for three months after the payment itself
 * has gone from the list, so a charge after the cancellation is still caught.
 */
data class GoneMerchant(
    val name: String,
    /** The [merchantKey] the owner confirmed. */
    val merchant: String,
    /** The last day of what was paid for; a charge after it is the question. */
    val stopsAfter: Long,
    val currency: String = UAH,
    /** The payment as it was, running, for «Повернути платіж»: its [payJson]. */
    val pay: String = ""
)

/** How long a cancelled payment's merchant is watched after what was paid for ran out. */
const val GONE_KEEP_DAYS = 92L

/** The watch list with [pay] on it once, and anything past three months dropped. */
fun withGone(gone: List<GoneMerchant>, pay: Pay, today: LocalDate): List<GoneMerchant> =
    (gone.filterNot { it.name == pay.name && it.merchant == pay.monoMerchant } +
        GoneMerchant(pay.name, pay.monoMerchant, pay.stopsAfter, pay.currency, payJson(unstopped(pay)).toString()))
        .filter { today.toEpochDay() - it.stopsAfter <= GONE_KEEP_DAYS }

fun goneJson(gone: List<GoneMerchant>): JSONArray = JSONArray().apply {
    gone.forEach {
        put(
            JSONObject().put("n", it.name).put("m", it.merchant).put("s", it.stopsAfter)
                .put("cur", it.currency).put("p", it.pay)
        )
    }
}

fun goneOf(text: String?): List<GoneMerchant> = runCatching {
    val array = JSONArray(text ?: "[]")
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map {
        GoneMerchant(
            it.optString("n"),
            it.optString("m"),
            it.optLong("s", 0L),
            it.optString("cur", UAH).ifBlank { UAH },
            it.optString("p")
        )
    }.filter { it.merchant.isNotBlank() && it.stopsAfter > 0L }
}.getOrDefault(emptyList())

/** A charge from a cancelled payment's merchant after what was paid for ran out. */
data class AfterCancel(
    val name: String,
    val tx: MonoTx,
    /** What it took, in the payment's currency. */
    val charged: Double,
    val currency: String,
    /** The payment, while it is still on the list (cancelled, its question waiting). */
    val pay: Pay?,
    /** Its watch entry, once it has gone to the bin. */
    val gone: GoneMerchant?
)

/**
 * «Списали після скасування?» — every charge from a cancelled payment's confirmed
 * merchant dated after the last day it was paid for, within three months of it.
 * [dismissed] holds the operations the owner said «Це не воно» about.
 */
fun chargedAfterCancel(
    pays: List<Pay>,
    gone: List<GoneMerchant>,
    txs: List<MonoTx>,
    dismissed: Set<String>,
    accountCurrency: Map<String, Int>,
    usdSell: Double,
    zone: ZoneId = ZoneId.systemDefault()
): List<AfterCancel> {
    val listed = pays.filter { it.stopReason == STOP_CANCELLED && it.monoMerchant.isNotBlank() && it.stopsAfter > 0L }
    val watched: List<Pair<GoneMerchant, Pay?>> =
        listed.map { GoneMerchant(it.name, it.monoMerchant, it.stopsAfter, it.currency) to it } +
            gone.filter { entry -> listed.none { it.name == entry.name && it.monoMerchant == entry.merchant } }
                .map { it to null }
    return watched.flatMap { (entry, pay) ->
        val stub = pay ?: Pay(entry.name, 0.0, currency = entry.currency, monoMerchant = entry.merchant)
        merchantDebits(stub, txs)
            .filter { it.id !in dismissed }
            .filter { tx -> txDay(tx, zone).toEpochDay().let { it > entry.stopsAfter && it <= entry.stopsAfter + GONE_KEEP_DAYS } }
            .map { tx ->
                val charged = chargedIn(tx, stub, accountCurrency[tx.account] ?: UAH_CODE, usdSell) ?: (-tx.amount / 100.0)
                AfterCancel(entry.name, tx, kotlin.math.round(charged * 100) / 100.0, entry.currency, pay, entry.takeIf { pay == null })
            }
    }.sortedByDescending { it.tx.time }
}

/** «Списали після скасування: Netflix 249 ₴, 2 листопада» */
fun afterCancelLine(after: AfterCancel, zone: ZoneId = ZoneId.systemDefault()): String =
    "Списали після скасування: ${after.name} ${amountLabel(after.charged, after.currency)}, ${dayMonth(txDay(after.tx, zone))}"

// ------------------------------------------------------------ «не списалось»

/** A confirmed payment whose date this month passed with no charge from its merchant. */
data class MissedCharge(val pay: Pay, val due: LocalDate)

/** Days after its date before a charge that has not come is mentioned: services bill a little late. */
const val MISSED_GRACE_DAYS = 3L

/**
 * The research's «Spotify не списався 3 жовтня — перевір картку»: the merchant
 * the owner confirmed took nothing around this month's date, the date is a few
 * days behind, and the statement has been read since then — so the silence is
 * the bank's and not a statement nobody has fetched yet. A month ticked by hand
 * is paid, and a paused, cancelled or free one owed nothing.
 */
fun missedCharges(
    pays: List<Pay>,
    txs: List<MonoTx>,
    marks: List<PaidMark>,
    today: LocalDate,
    /** The last day the statement was read on; nothing later than it can be judged. */
    readUpTo: LocalDate,
    zone: ZoneId = ZoneId.systemDefault()
): List<MissedCharge> = pays
    .filter { it.monoMerchant.isNotBlank() && !isInstalment(it) && lifeOf(it, today) == PayLife.RUNNING }
    .mapNotNull { pay ->
        val due = chargeDateIn(pay, today)
        if (!chargesIn(pay, due) || priceOn(pay, due.toEpochDay()) <= 0.0) return@mapNotNull null
        val judged = due.plusDays(MISSED_GRACE_DAYS)
        if (!judged.isBefore(today) || readUpTo.isBefore(judged)) return@mapNotNull null
        if (isPaid(marks, pay.name, monthKey(due))) return@mapNotNull null
        val charged = merchantDebits(pay, txs).any { !txDay(it, zone).isBefore(due.minusDays(EARLY_DAYS)) }
        if (charged) null else MissedCharge(pay, due)
    }

/** «Spotify: 3 жовтня списання не було — перевірте картку», with no verb to agree with the name. */
fun missedLine(missed: MissedCharge): String =
    "${missed.pay.name}: ${dayMonth(missed.due)} списання не було — перевірте картку"

/** What the statement adds to the morning message, each line said once. */
fun monoLines(
    missed: List<MissedCharge>,
    doubles: List<DoubleCharge>,
    after: List<AfterCancel>,
    accountCurrency: Map<String, Int>,
    zone: ZoneId = ZoneId.systemDefault()
): List<OnceLine> =
    after.map { OnceLine("after|${it.tx.id}", afterCancelLine(it, zone)) } +
        doubles.map { OnceLine("double|${it.key}", "Схоже на подвійне списання: ${doubleChargeLine(it, accountCurrency, zone)}") } +
        missed.map { OnceLine("missed|${it.pay.name}|${monthKey(it.due)}", missedLine(it)) }

// ------------------------------------------------------------ «не вистачить на завтра»

/**
 * The morning's first line when tomorrow's charges from monobank are more than
 * the card's own money: «Завтра Netflix 249 ₴ + iCloud 99 ₴, а власних на картці
 * 210 ₴ — докиньте 138 ₴».
 *
 * Only payments the owner confirmed are paid from monobank (a confirmed merchant)
 * and only what is still unpaid. [own] is balance minus the credit line on the
 * cards the owner ticked ([ownUah]). Dollar charges count at the sell rate, and
 * are left out when there is none rather than read as nought.
 */
fun shortTomorrowLine(pays: List<Pay>, marks: List<PaidMark>, today: LocalDate, own: Double, usdSell: Double): String? {
    val tomorrow = today.plusDays(1)
    val due = stillOwing(pays, marks, today)
        .filter { it.monoMerchant.isNotBlank() && nextCharge(it, today) == tomorrow }
        .mapNotNull { pay ->
            val price = priceOn(pay, tomorrow.toEpochDay())
            val uah = when {
                pay.currency != USD -> price
                usdSell > 0.0 -> price * usdSell
                else -> return@mapNotNull null
            }
            Triple(pay, price, uah).takeIf { price > 0.0 }
        }
    if (due.isEmpty()) return null
    val total = due.sumOf { it.third }
    if (total <= own) return null
    val what = due.joinToString(" + ") { (pay, price, _) -> "${pay.name} ${amountLabel(price, pay.currency)}" }
    val card = if (own > 0.0) "а власних на картці ${approxMoney(own)}" else "а власних грошей на картці немає"
    return "Завтра $what, $card — докиньте ${money(kotlin.math.ceil(total - own))}"
}

// ------------------------------------------------------------ a jar that covers the price

/** A wish whose jar now holds its whole live price. */
data class JarAlert(val wish: Wish, val jar: Double, val price: Double) {
    /** Once per price level, like the target-price alert: a lower price is news again. */
    val key: String get() = "${wish.id}@${kotlin.math.round(price).toLong()}"
}

/** A price this many days old is not a live price any more. */
const val JAR_PRICE_FRESH_DAYS = 2L

/**
 * Wishes whose linked jar first covers the best live price — read from a page in
 * the last two days and in stock ([Freshness.OK]). A wish on hold is the owner's
 * decision to wait and is not interrupted. [said] holds the keys already
 * announced.
 */
fun jarAlerts(wishes: List<Wish>, jars: List<MonoJar>, said: Set<String>, today: Long): List<JarAlert> =
    wishes.mapNotNull { wish ->
        if (wish.jar.isBlank() || wish.price <= 0.0 || wish.freshness != Freshness.OK) return@mapNotNull null
        if (onHold(wish, today) || today - wish.checkedDay > JAR_PRICE_FRESH_DAYS) return@mapNotNull null
        val jar = jars.firstOrNull { it.id == wish.jar }?.let(::jarUah) ?: return@mapNotNull null
        JarAlert(wish, jar, wish.price).takeIf { jar >= wish.price && it.key !in said }
    }

/** «На банці 4 200 ₴, а ціна вже 3 999 ₴ — можна купувати» */
fun jarAlertText(alert: JarAlert): String =
    "На банці ${approxMoney(alert.jar)}, а ціна вже ${money(alert.price)} — можна купувати"

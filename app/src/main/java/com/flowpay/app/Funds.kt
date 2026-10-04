package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * Фонди — money put aside for a known cost, the owner's «додай все» of 4 October
 * 2026 (research: YNAB's true expenses, Copilot's reserve for annual bills,
 * Monarch's non-monthly funds).
 *
 * Two kinds, one shape:
 *
 * - **A fund for an annual payment.** «Автоцивілка 6 400 ₴ · 1 червня» stops
 *   landing on one month: the fund asks a monthly sum worked out like a wish's
 *   «Знаю дату» ([deadlinePlan]), so it sits in «Плани не сходяться» and comes off
 *   the treat like any plan. In the charge month the payment still shows at its
 *   full price in its own month (HANDOFF §12 — amortised costs never masquerade
 *   as cash), and the part the fund holds is counted back once as [fundCoverage]
 *   — so «Вільно», the weather and the plans check are not hit a second time for
 *   money that was put aside months ago. That is the trap every research note
 *   on this idea warned about.
 * - **A fund of its own** — «Подушка», «ТО авто», «Подарунки»: a goal that is not
 *   a thing in a shop, which a wish could never be.
 *
 * Nothing moves money. «✅ Відклав» is the owner's word that he did, exactly as
 * «Вже відкладено» is on a wish.
 */
data class Fund(
    val id: String,
    val name: String,
    /** Picked by hand. Empty means the guess: the payment's emoji, or a jar. */
    val emoji: String = "",
    /**
     * Hryvnias. For a payment's fund this is only the fallback for when the
     * payment is gone: while it stands, its own amount is the goal.
     */
    val goal: Double = 0.0,
    /** Hryvnias put aside so far, by the owner's own word. */
    val saved: Double = 0.0,
    /** «Знаю суму»: what goes in each month. Nought when the fund runs by date. */
    val monthly: Double = 0.0,
    /** «Знаю дату»: the epoch day the money is needed by. Nought when by sum. */
    val deadline: Long = 0L,
    /** The annual payment this fund is for, by name — payments have no id. */
    val payName: String = "",
    /** The charge month the fund is saving towards, "2027-06". Payment funds only. */
    val dueMonth: String = "",
    /** The last charge the fund paid its part of, and how much — see [settledFund]. */
    val coveredMonth: String = "",
    val covered: Double = 0.0,
    /** «Подушка»: the reserve «Місяць наперед» counts. One at most. */
    val cushion: Boolean = false,
    /** A month whose contribution is skipped, "2026-10" — «Пропустити». */
    val skipMonth: String = "",
    /** The month of the last «✅ Відклав» and how much went in that month. */
    val putMonth: String = "",
    val putAmount: Double = 0.0,
    /** Epoch day the fund was started. */
    val createdDay: Long = 0L
)

/** A deleted fund in the 30-day bin. */
const val BIN_FUND = "mpf"

/** The emoji a fund wears when nobody picked one and no payment lends it one. */
const val FUND_EMOJI = "🫙"

/** The funds one can start from nothing, each with its emoji. */
data class FundPreset(val name: String, val emoji: String, val cushion: Boolean = false)

val FUND_PRESETS = listOf(
    // An umbrella — «на чорний день». The lifebuoy would say it better, but it is
    // missing from the owner's Apple pack and would be drawn in the phone's font.
    FundPreset("Подушка", "☂️", cushion = true),
    FundPreset("ТО авто", "🚗"),
    FundPreset("Подарунки", "🎁"),
    FundPreset("Ліки", "💊"),
    FundPreset("Відпустка", "🏖️")
)

// ------------------------------------------------------------ stored

/**
 * One fund, stored. Every key starts with «mp» — the prefix this feature owns —
 * so nothing written by another part of the app can collide with it.
 */
fun fundJson(fund: Fund): JSONObject = JSONObject()
    .put("mpi", fund.id).put("mpn", fund.name).put("mpe", fund.emoji)
    .put("mpg", fund.goal).put("mps", fund.saved).put("mpm", fund.monthly)
    .put("mpd", fund.deadline).put("mpp", fund.payName).put("mpdm", fund.dueMonth)
    .put("mpcm", fund.coveredMonth).put("mpc", fund.covered).put("mpq", fund.cushion)
    .put("mpsk", fund.skipMonth).put("mppm", fund.putMonth).put("mppa", fund.putAmount)
    .put("mpad", fund.createdDay)

/** A number that is a number: a hand-edited file must not put NaN into every total. */
private fun JSONObject.amount(key: String): Double =
    optDouble(key, 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0

fun fundOf(o: JSONObject): Fund = Fund(
    id = o.optString("mpi").ifBlank { "f-${o.optString("mpn").hashCode()}" },
    name = o.optString("mpn").ifBlank { "Фонд" },
    emoji = o.optString("mpe"),
    goal = o.amount("mpg"),
    saved = o.amount("mps"),
    monthly = o.amount("mpm"),
    deadline = o.optLong("mpd", 0L).coerceAtLeast(0L),
    payName = o.optString("mpp"),
    dueMonth = o.optString("mpdm"),
    coveredMonth = o.optString("mpcm"),
    covered = o.amount("mpc"),
    cushion = o.optBoolean("mpq", false),
    skipMonth = o.optString("mpsk"),
    putMonth = o.optString("mppm"),
    putAmount = o.amount("mppa"),
    createdDay = o.optLong("mpad", 0L)
)

/** A fund on its way to the bin: what it was for and how much was in it. */
fun binEntryOf(fund: Fund, today: Long): BinEntry = BinEntry(
    id = binEntryId(BIN_FUND, fund.id),
    kind = BIN_FUND,
    title = fund.name,
    detail = "відкладено ${money(kotlin.math.round(fund.saved))}",
    payload = fundJson(fund).toString(),
    day = today
)

/** The whole list, as the store keeps it. */
fun fundsJson(funds: List<Fund>): String = JSONArray(funds.map(::fundJson)).toString()

/**
 * The whole list back. Anything unreadable is an empty list rather than a crash:
 * the funds are one feature, and a bad entry must not take the screen down.
 */
fun fundsOf(text: String?): List<Fund> = runCatching {
    val array = JSONArray(text ?: "[]")
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map(::fundOf)
}.getOrDefault(emptyList()).let { list -> withoutRepeatedIds(list) { it.id } }

// ------------------------------------------------------------ what a fund is for

/** The annual payment a fund is for, or null for a fund of its own — or one whose payment is gone. */
fun fundPay(fund: Fund, pays: List<Pay>): Pay? =
    if (fund.payName.isBlank()) null else pays.firstOrNull { it.name == fund.payName && isAnnual(it) }

/** What one charge of [pay] is in hryvnias at the sell rate. Nought for dollars with no rate. */
fun chargeUah(pay: Pay, usdSell: Double): Double =
    if (pay.currency == USD) pay.amount * usdSell.coerceAtLeast(0.0) else pay.amount

/** The day [pay] is charged in the month [month] falls in, clamped to a day that exists. */
fun chargeDayIn(pay: Pay, month: LocalDate): LocalDate {
    val first = month.withDayOfMonth(1)
    return first.withDayOfMonth(effectivePaymentDay(pay.day, first.lengthOfMonth()))
}

/** What a fund is aiming at, in hryvnias: its payment's amount while it stands, else its own goal. */
fun fundGoal(fund: Fund, pay: Pay?, usdSell: Double): Double =
    pay?.let { chargeUah(it, usdSell) }?.takeIf { it > 0.0 } ?: fund.goal

/**
 * The date the money is needed by: the charge in the month being saved for, a
 * fund's own date, or null for a fund that runs by a monthly sum.
 */
fun fundDeadline(fund: Fund, pay: Pay?, today: LocalDate): LocalDate? = when {
    pay != null -> monthKeyDate(fund.dueMonth)?.let { chargeDayIn(pay, it) } ?: nextCharge(pay, today)
    fund.deadline > 0L -> LocalDate.ofEpochDay(fund.deadline)
    // A payment's fund whose payment was deleted keeps the date it last saved for.
    fund.payName.isNotBlank() -> monthKeyDate(fund.dueMonth)
    else -> null
}

/** True for a fund that runs to a date, which is how «Пропустити» prices it. */
fun fundByDate(fund: Fund, pay: Pay?, today: LocalDate): Boolean = fundDeadline(fund, pay, today) != null

/**
 * What the fund asks of this month, skip aside: the date's demand, or the monthly
 * sum — never more than the goal still needs.
 */
fun fundWouldAsk(fund: Fund, pay: Pay?, today: LocalDate, usdSell: Double): Double {
    val goal = fundGoal(fund, pay, usdSell)
    if (goal > 0.0 && fund.saved >= goal) return 0.0
    val deadline = fundDeadline(fund, pay, today)
    return when {
        deadline != null -> deadlinePlan(goal, fund.saved, today, deadline).monthly
        fund.monthly > 0.0 && goal > 0.0 -> minOf(fund.monthly, goal - fund.saved)
        else -> fund.monthly
    }.coerceAtLeast(0.0)
}

/** What the fund asks of this month: nought in a month it was skipped for. */
fun fundAsk(fund: Fund, pay: Pay?, today: LocalDate, usdSell: Double): Double =
    if (fund.skipMonth == monthKey(today)) 0.0 else fundWouldAsk(fund, pay, today, usdSell)

// ------------------------------------------------------------ the charge month

/**
 * The fund once its payment has been charged.
 *
 * Worked out from the data, not from a tap, so the fund pays its part whichever
 * way the month got its mark — a tick on Платежі, a monobank match that ticked
 * itself in the background, or no mark at all once the charge month is over
 * (an annual charge whose month has passed was paid, and the money saved for it
 * went with it). The part paid is recorded as [Fund.covered] against
 * [Fund.coveredMonth], so that month's figures can still count it back after the
 * balance has moved on to next year's charge.
 *
 * A tick taken back while its month is still running puts the money back.
 */
fun settledFund(fund: Fund, pays: List<Pay>, marks: List<PaidMark>, today: LocalDate, usdSell: Double): Fund {
    val pay = fundPay(fund, pays) ?: return fund
    var f = fund
    // Never set, or the payment moved to another month: aim at its next charge.
    val due = monthKeyDate(f.dueMonth)
    if (due == null || due.monthValue != pay.billingMonth) {
        f = f.copy(dueMonth = monthKey(nextCharge(pay, today)))
    }
    val thisMonth = monthKey(today)
    if (f.coveredMonth.isNotBlank() && f.coveredMonth >= thisMonth && !isPaid(marks, pay.name, f.coveredMonth)) {
        f = f.copy(saved = f.saved + f.covered, dueMonth = f.coveredMonth, coveredMonth = "", covered = 0.0)
    }
    val mark = marks.firstOrNull { it.name == pay.name && it.month == f.dueMonth }
    if (mark != null || f.dueMonth < thisMonth) {
        val dueDate = monthKeyDate(f.dueMonth) ?: return f
        val charged = mark?.let { if (it.currency == USD) it.amount * usdSell.coerceAtLeast(0.0) else it.amount }
            ?: chargeUah(pay, usdSell)
        val part = minOf(f.saved, charged).coerceAtLeast(0.0)
        f = f.copy(
            saved = f.saved - part,
            coveredMonth = f.dueMonth,
            covered = part,
            dueMonth = monthKey(dueDate.plusYears(1))
        )
    }
    return f
}

fun settledFunds(funds: List<Fund>, pays: List<Pay>, marks: List<PaidMark>, today: LocalDate, usdSell: Double): List<Fund> =
    funds.map { settledFund(it, pays, marks, today, usdSell) }

/**
 * What [pay] takes out of the month [month] falls in, in hryvnias.
 *
 * This month through [monthCharge], so it agrees with [monthlyTotal] to the
 * kopeck; a later month by the charge's own date, so a trial that ends before
 * then counts — the rule [monthRecord] uses, and the trap the research found in
 * asking [monthlyTotal] about next month.
 */
fun chargeInMonth(pay: Pay, month: LocalDate, today: LocalDate, usdSell: Double): Double {
    val inThisMonth = monthKey(month) == monthKey(today)
    val amount = when {
        inThisMonth -> monthCharge(pay, month, today)
        !chargesIn(pay, month.withDayOfMonth(1)) -> 0.0
        onTrial(pay, chargeDayIn(pay, month).toEpochDay()) -> 0.0
        else -> monthCharge(pay.copy(trialEnd = 0L), month.withDayOfMonth(1), today)
    }
    return if (pay.currency == USD) amount * usdSell.coerceAtLeast(0.0) else amount
}

/**
 * The part of [pay]'s charge in [month] the fund already holds — counted back
 * into that month once, so money saved earlier is not spent from this month's
 * income a second time. Never more than the charge the month counts.
 */
fun fundCoverage(fund: Fund, pay: Pay?, month: LocalDate, today: LocalDate, usdSell: Double): Double {
    if (pay == null) return 0.0
    val key = monthKey(month)
    val charge = chargeInMonth(pay, month, today, usdSell)
    if (charge <= 0.0) return 0.0
    return when (key) {
        fund.coveredMonth -> minOf(fund.covered, charge)
        fund.dueMonth -> minOf(fund.saved, charge)
        else -> 0.0
    }.coerceAtLeast(0.0)
}

/** Everything the funds cover in [month]. */
fun coveredIn(funds: List<Fund>, pays: List<Pay>, month: LocalDate, today: LocalDate, usdSell: Double): Double =
    funds.sumOf { fundCoverage(it, fundPay(it, pays), month, today, usdSell) }

/**
 * For the weather: each annual charge in the next [days] days and what its fund
 * covers of it, by payment name. A covered charge is not money leaving this week.
 */
fun weatherCover(funds: List<Fund>, pays: List<Pay>, today: LocalDate, usdSell: Double, days: Int = 7): Map<String, Double> =
    funds.mapNotNull { fund ->
        val pay = fundPay(fund, pays) ?: return@mapNotNull null
        val day = (0 until days).map { today.plusDays(it.toLong()) }.firstOrNull { date -> chargedOn(listOf(pay), date).isNotEmpty() }
            ?: return@mapNotNull null
        fundCoverage(fund, pay, day, today, usdSell).takeIf { it > 0.0 }?.let { pay.name to it }
    }.toMap()

// ------------------------------------------------------------ putting money in

/** «✅ Відклав»: [amount] more in the fund, remembered against this month. */
fun putInto(fund: Fund, amount: Double, today: LocalDate): Fund {
    if (amount <= 0.0 || !amount.isFinite()) return fund
    val month = monthKey(today)
    return fund.copy(
        saved = fund.saved + amount,
        putMonth = month,
        putAmount = (if (fund.putMonth == month) fund.putAmount else 0.0) + amount
    )
}

/** What went in this month, nought when nothing did. */
fun putThisMonth(fund: Fund, today: LocalDate): Double = if (fund.putMonth == monthKey(today)) fund.putAmount else 0.0

/** «Пропустити цього місяця», or its undoing. */
fun skippedFund(fund: Fund, today: LocalDate, skip: Boolean): Fund =
    fund.copy(skipMonth = if (skip) monthKey(today) else "")

/** A sum the owner is asked to put aside: whole hryvnias, rounded up so it is enough. */
fun askRounded(value: Double): Double = if (value <= 0.0) 0.0 else kotlin.math.ceil(value - 0.005)

// ------------------------------------------------------------ the offer

/** «Автоцивілка 6 400 ₴ · 1 червня — відкладати 915 ₴ на місяць?» */
data class FundOffer(val pay: Pay, val goal: Double, val due: LocalDate, val monthly: Double)

/**
 * One offer per annual payment that has no fund and was not declined.
 *
 * Only while there is at least a month to save in: a charge three weeks away
 * cannot be spread over anything, and an offer to put it all aside at once is
 * not a plan.
 */
fun fundOffers(
    pays: List<Pay>,
    funds: List<Fund>,
    declined: Set<String>,
    today: LocalDate,
    usdSell: Double
): List<FundOffer> = pays
    .filter { isAnnual(it) && it.name !in declined && funds.none { f -> f.payName == it.name } }
    .distinctBy { it.name }
    .mapNotNull { pay ->
        val due = nextCharge(pay, today)
        val goal = chargeUah(pay, usdSell)
        if (goal <= 0.0 || monthsUntil(today, due) < 1) return@mapNotNull null
        FundOffer(pay, goal, due, deadlinePlan(goal, 0.0, today, due).monthly)
    }
    .sortedBy { it.due }

fun fundOfferLine(offer: FundOffer): String =
    "${offer.pay.name} ${money(offer.goal)} · ${dayMonth(offer.due)} — відкладати ${money(askRounded(offer.monthly))} на місяць?"

/** The fund «Створити» starts. */
fun fundFromOffer(offer: FundOffer, today: LocalDate, id: String): Fund = Fund(
    id = id,
    name = offer.pay.name,
    goal = offer.goal,
    payName = offer.pay.name,
    dueMonth = monthKey(offer.due),
    createdDay = today.toEpochDay()
)

/** A fund's emoji: the hand-picked one, its payment's, or a jar. */
fun fundEmoji(fund: Fund, pay: Pay?): String =
    fund.emoji.ifBlank { pay?.let { shownEmoji(it) } ?: FUND_PRESETS.firstOrNull { it.name == fund.name }?.emoji ?: FUND_EMOJI }

/** The fund under a payment that was renamed follows it, as the paid marks do. */
fun renamedFunds(funds: List<Fund>, from: String, to: String): List<Fund> =
    if (from == to) funds else funds.map { if (it.payName == from) it.copy(payName = to, name = if (it.name == from) to else it.name) else it }

/** At most one cushion: marking a new one unmarks the old. */
fun withCushion(funds: List<Fund>, id: String): List<Fund> = funds.map { it.copy(cushion = it.id == id) }

// ------------------------------------------------------------ what a fund says

/** «2 140 з 6 400 ₴» */
fun fundProgressLine(fund: Fund, pay: Pay?, usdSell: Double): String {
    val goal = fundGoal(fund, pay, usdSell)
    return if (goal > 0.0) "${bareAmount(kotlin.math.round(fund.saved))} з ${money(kotlin.math.round(goal))}"
    else money(kotlin.math.round(fund.saved))
}

/** Share of the goal saved, 0..1, for the ring. Nought without a goal. */
fun fundProgress(fund: Fund, pay: Pay?, usdSell: Double): Float {
    val goal = fundGoal(fund, pay, usdSell)
    return if (goal > 0.0) (fund.saved / goal).coerceIn(0.0, 1.0).toFloat() else 0f
}

/**
 * The plan in a line: «до 1 червня · по 915 ₴/міс», «по 2 000 ₴/міс»,
 * «пропущено в жовтні», «зібрано повністю».
 */
fun fundPlanLine(fund: Fund, pay: Pay?, today: LocalDate, usdSell: Double): String {
    val goal = fundGoal(fund, pay, usdSell)
    val deadline = fundDeadline(fund, pay, today)
    val would = fundWouldAsk(fund, pay, today, usdSell)
    val skipped = fund.skipMonth == monthKey(today) && would > 0.0
    val date = deadline?.let { "до ${dayMonth(it)}" }
    return when {
        goal > 0.0 && fund.saved >= goal -> listOfNotNull(date, "зібрано повністю").joinToString(" · ")
        skipped -> listOfNotNull(date, "пропущено в ${monthLocative(today.monthValue)}").joinToString(" · ")
        deadline != null && monthsUntil(today, deadline) == 0 ->
            "${dayMonth(deadline)} · бракує ${money(kotlin.math.round(goal - fund.saved))}"
        would > 0.0 -> listOfNotNull(date, "по ${money(askRounded(would))}/міс").joinToString(" · ")
        else -> date ?: "без щомісячної суми"
    }
}

/**
 * What the fund paid at its last charge, while that is still news — the charge
 * month and the month after: «У червні фонд покрив 6 400 з 6 400 ₴».
 */
fun fundCoveredLine(fund: Fund, pay: Pay?, today: LocalDate, usdSell: Double): String? {
    if (fund.coveredMonth.isBlank()) return null
    val month = monthKeyDate(fund.coveredMonth) ?: return null
    val thisMonth = today.withDayOfMonth(1)
    if (month.isBefore(thisMonth.minusMonths(1)) || month.isAfter(thisMonth)) return null
    val charge = pay?.let { chargeUah(it, usdSell) } ?: return null
    return "У ${monthLocative(month.monthValue)} фонд покрив ${bareAmount(kotlin.math.round(fund.covered))} з ${money(kotlin.math.round(charge))}"
}

/**
 * The sentence for the tick that made a fund pay: «Фонд покрив 800 з 1 199 ₴ —
 * 399 ₴ лягли на березень». Null when nothing new was covered.
 */
fun coverageNote(before: List<Fund>, after: List<Fund>, pays: List<Pay>, marks: List<PaidMark>, usdSell: Double): String? {
    val changed = after.firstOrNull { now ->
        val was = before.firstOrNull { it.id == now.id } ?: return@firstOrNull false
        now.coveredMonth.isNotBlank() && now.coveredMonth != was.coveredMonth
    } ?: return null
    val pay = fundPay(changed, pays) ?: return null
    val mark = marks.firstOrNull { it.name == pay.name && it.month == changed.coveredMonth }
    val charged = mark?.let { if (it.currency == USD) it.amount * usdSell.coerceAtLeast(0.0) else it.amount }
        ?: chargeUah(pay, usdSell)
    val month = monthKeyDate(changed.coveredMonth) ?: return null
    val rest = charged - changed.covered
    return when {
        changed.covered <= 0.0 -> null
        rest < 0.5 -> "Фонд покрив увесь платіж — ${money(kotlin.math.round(charged))}"
        else -> "Фонд покрив ${bareAmount(kotlin.math.round(changed.covered))} з ${money(kotlin.math.round(charged))} — " +
            "${money(kotlin.math.round(rest))} лягли на ${monthName(month.monthValue).lowercase()}"
    }
}

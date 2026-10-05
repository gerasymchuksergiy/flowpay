package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * What «Вільно» really is, and planning money ahead — the owner's «додай все» of
 * 4 October 2026, from the ten-app research (Monarch's and YNAB's «На життя» and
 * true expenses, Cleo's «Чи потягну?» and payday ritual, Revolut's payday view,
 * Rocket Money's «скільки можна сьогодні», YNAB's snooze and month ahead).
 *
 * The research offered several overlapping versions of the same few ideas. They
 * are merged here into one model with one rule: **there is one «Вільно»**, and
 * every surface — Огляд, Платежі, the treat, «Плани не сходяться», the weather,
 * the widget, the tile, the morning message — reads the same figure:
 *
 * ```
 * income − this month's payments (annual ones at full price in their month, §12)
 *        + what the funds already hold of this month's annual charges
 *        − «На життя» (when switched on)
 * = Вільно
 * ```
 *
 * Plans — a wish's monthly sum and a fund's — are not taken out of that figure;
 * they are what it is spent on, so they are checked against it («Плани не
 * сходяться») and come off what the treat may use. A held wish and a skipped
 * month ask nothing. Months stay calendar months; a payday only says when money
 * arrives and when to put some aside.
 *
 * Everything is plain arithmetic over the stored data. No generated text, no
 * figure the data does not hold (HANDOFF §7.3).
 */

/** Days in an average month, for spreading a month's figure over its days. */
const val AVERAGE_MONTH_DAYS = 365.0 / 12.0

// ------------------------------------------------------------ «На життя»

/**
 * One monthly number for food, transport, cafés — off by default, and then the
 * app works exactly as it did. The owner's own guess, so it is always shown with
 * a way to change it.
 */
data class LifeCost(val on: Boolean = false, val monthly: Double = 0.0) {
    /** What it takes from the month: the number when switched on, nought otherwise. */
    val amount: Double get() = if (on && monthly > 0.0 && monthly.isFinite()) monthly else 0.0
}

/** What life costs a day, for the days left before money arrives. */
fun lifePerDay(life: LifeCost): Double = life.amount / AVERAGE_MONTH_DAYS

// ------------------------------------------------------------ payday

/** «Останній робочий день» in place of a day of the month. */
const val LAST_WORKING_DAY = -1

/**
 * When money arrives: the salary's day of the month (or [LAST_WORKING_DAY]), and
 * an advance's day when there is one. Nought means not set.
 */
data class Payday(val salary: Int = 0, val advance: Int = 0) {
    val known: Boolean get() = salary == LAST_WORKING_DAY || salary in 1..31
    val hasAdvance: Boolean get() = known && advance in 1..31
}

/** One day money arrives: the salary, or the advance. */
data class PaydayDate(val date: LocalDate, val salary: Boolean)

/**
 * The paydays of the month [month] falls in. A payday on a weekend or a holiday
 * comes on the working day before it — the Labour Code's rule, and the same
 * backward shift [paymentDay] uses — so it can land in the month before.
 */
fun paydaysIn(payday: Payday, month: LocalDate, holidays: Set<Long>): List<PaydayDate> {
    if (!payday.known) return emptyList()
    val first = month.withDayOfMonth(1)
    fun on(day: Int): LocalDate =
        workingDayOnOrBefore(first.withDayOfMonth(effectivePaymentDay(day, first.lengthOfMonth())), holidays)
    val salary = if (payday.salary == LAST_WORKING_DAY) {
        workingDayOnOrBefore(first.withDayOfMonth(first.lengthOfMonth()), holidays)
    } else {
        on(payday.salary)
    }
    val advance = if (payday.hasAdvance) on(payday.advance).takeIf { it != salary } else null
    return listOfNotNull(PaydayDate(salary, true), advance?.let { PaydayDate(it, false) }).sortedBy { it.date }
}

/** Every payday from last month to the one after next, in order. */
fun paydaysAround(payday: Payday, today: LocalDate, holidays: Set<Long>): List<PaydayDate> =
    (-1L..2L).flatMap { paydaysIn(payday, today.plusMonths(it), holidays) }
        .distinctBy { it.date }
        .sortedBy { it.date }

/** The next day money arrives, after today. */
fun nextPayday(payday: Payday, today: LocalDate, holidays: Set<Long>): PaydayDate? =
    paydaysAround(payday, today, holidays).firstOrNull { it.date.isAfter(today) }

/** Today, when money arrives today. */
fun paydayToday(payday: Payday, today: LocalDate, holidays: Set<Long>): PaydayDate? =
    paydaysAround(payday, today, holidays).firstOrNull { it.date == today }

/** The last salary day on or before today. */
fun lastSalaryDay(payday: Payday, today: LocalDate, holidays: Set<Long>): LocalDate? =
    paydaysAround(payday, today, holidays).lastOrNull { it.salary && !it.date.isAfter(today) }?.date

/** «зарплата сьогодні», «зарплата завтра», «до зарплати ще 9 днів», «до авансу ще 3 дні». */
fun paydayCountdown(payday: Payday, today: LocalDate, holidays: Set<Long>): String? {
    if (!payday.known) return null
    paydayToday(payday, today, holidays)?.let { return if (it.salary) "зарплата сьогодні" else "аванс сьогодні" }
    val next = nextPayday(payday, today, holidays) ?: return null
    val days = ChronoUnit.DAYS.between(today, next.date).toInt()
    return when {
        days == 1 && next.salary -> "зарплата завтра"
        days == 1 -> "аванс завтра"
        next.salary -> "до зарплати ще ${daysLabel(days)}"
        else -> "до авансу ще ${daysLabel(days)}"
    }
}

/** How the payday reads in a settings row: «25 числа», «останній робочий день», «аванс 10, зарплата 25 числа». */
fun paydayLabel(payday: Payday): String {
    val last = payday.salary == LAST_WORKING_DAY
    return when {
        !payday.known -> "не вказано"
        !payday.hasAdvance && last -> "останній робочий день"
        !payday.hasAdvance -> "${payday.salary} числа"
        last -> "аванс ${payday.advance} числа, зарплата в останній робочий день"
        else -> "аванс ${payday.advance}, зарплата ${payday.salary} числа"
    }
}

// ------------------------------------------------------------ the month, honestly

/** Everything the plan is worked out from, read once. */
data class MoneyInputs(
    val today: LocalDate,
    val income: Double,
    val pays: List<Pay> = emptyList(),
    val marks: List<PaidMark> = emptyList(),
    val wishes: List<Wish> = emptyList(),
    /** As stored; settled against the marks before use — see [settledFund]. */
    val funds: List<Fund> = emptyList(),
    val usdSell: Double = 0.0,
    val life: LifeCost = LifeCost(),
    val payday: Payday = Payday(),
    val holidays: Set<Long> = emptySet(),
    /** The last «Розкласти зарплату», for what has already been put aside. */
    val ritual: RitualRecord? = null
)

/** This month as money in and money out — the one «Вільно». */
data class HonestMonth(
    val income: Double,
    /** This month's payments as cash: an annual charge whole, in its own month. */
    val payments: MonthlyTotal,
    /** The part of this month's annual charges already held in their funds. */
    val covered: Double,
    /** «На життя»; nought when switched off. */
    val life: Double,
    /** income − payments + covered − life. Unclamped: a month that does not fit says so. */
    val free: Double
) {
    val unknown: Boolean get() = income <= 0.0
    val overspent: Boolean get() = !unknown && free < 0.0

    /** What this month's own money goes to: the payments less the funds' part, and life. */
    val spoken: Double get() = payments.total - covered + life

    /** The same month in the shape the rest of the app reads — the widget, the tile, the bar. */
    fun asBudget(): Budget = Budget(income, spoken, free, overspent, unknown)
}

fun settledFunds(inputs: MoneyInputs): List<Fund> =
    settledFunds(inputs.funds, inputs.pays, inputs.marks, inputs.today, inputs.usdSell)

fun honestMonth(inputs: MoneyInputs, funds: List<Fund> = settledFunds(inputs)): HonestMonth {
    val income = inputs.income.coerceAtLeast(0.0)
    // Through [monthlyTotal], so a payment's whole life — a trial, a plan that
    // ends, anything else [monthCharge] learns — reaches this figure unchanged.
    val payments = monthlyTotal(inputs.pays, inputs.usdSell, inputs.today)
    val covered = coveredIn(funds, inputs.pays, inputs.today, inputs.today, inputs.usdSell)
    val life = inputs.life.amount
    return HonestMonth(income, payments, covered, life, income - payments.total + covered - life)
}

/**
 * A later month projected the same way: its payments by their own charge dates
 * (a trial ending before then counts), what the funds hold for it, and life.
 */
fun projectedFree(inputs: MoneyInputs, funds: List<Fund>, month: LocalDate, pays: List<Pay> = inputs.pays): Double {
    if (monthKey(month) == monthKey(inputs.today)) return honestMonth(inputs.copy(pays = pays), funds).free
    val cost = monthCostAt(pays, month, inputs.usdSell, inputs.today).total
    return inputs.income.coerceAtLeast(0.0) - cost + coveredIn(funds, pays, month, inputs.today, inputs.usdSell) - inputs.life.amount
}

/**
 * What the month [month] falls in costs, each payment judged on its own charge
 * date — the rule [monthRecord] uses. [monthlyTotal] asked about a later month
 * judges a trial against today, so a trial ending before November read as free in
 * November; this does not.
 */
fun monthCostAt(pays: List<Pay>, month: LocalDate, usdSell: Double, today: LocalDate): MonthlyTotal {
    val first = month.withDayOfMonth(1)
    // Each charge at its own date's price (PaymentsLife.kt, [priceOn]): a free trial
    // still running then costs nothing, a promo still running costs its promo price,
    // and one ended by then costs the full price.
    val due = pays.filter { chargesIn(it, first) }
        .map { it.copy(amount = priceOn(it, chargeDayIn(it, first).toEpochDay()), trialEnd = 0L, promoPrice = 0.0) }
        .filter { it.amount > 0.0 }
    return monthlyTotal(due, usdSell, today, first)
}

// ------------------------------------------------------------ the plans

enum class PlanKind { WISH, FUND }

/** One plan's call on this month: a wish's or a fund's. */
data class PlanAsk(
    val kind: PlanKind,
    val id: String,
    val name: String,
    val emoji: String,
    /** What it asks this month; nought when skipped. */
    val monthly: Double,
    /** What it would ask were it not skipped. */
    val wouldAsk: Double,
    val goal: Double,
    val saved: Double,
    /** The date the money is needed by — a plan by date. */
    val deadline: LocalDate?,
    /** When the goal is reached at this rate — a plan by sum. */
    val ready: LocalDate?,
    /** How wanted, from the duel; null when never dueled, and for every fund. */
    val rating: Double?,
    /** The monthly sum a plan by sum was set to. */
    val monthlyPlan: Double = 0.0,
    /** Kept in a monobank jar: the jar holds it, so «Я відклав» leaves its figure alone. */
    val jar: Boolean = false,
    val skipped: Boolean = false,
    /** What was already put aside for it this month — «✅ Відклав», «Я відклав». */
    val put: Double = 0.0
) {
    val byDate: Boolean get() = deadline != null

    /** What this month still asks after what was put aside. */
    val left: Double get() = (monthly - put).coerceAtLeast(0.0)

    /** The date «найдальша дата» is about. */
    val farDate: LocalDate? get() = deadline ?: ready
}

/**
 * A wish's plan, or null when it asks nothing — a held wish never does.
 *
 * [put] is what «Я відклав» added this month. The month's call is worked out
 * from before it, so money just put aside still counts as this month's — a plan
 * that reached its goal with it must not vanish from «Плани не сходяться» and
 * hand the same money to the treat.
 */
fun wishPlanAsk(wish: Wish, today: LocalDate, put: Double = 0.0): PlanAsk? {
    if (onHold(wish, today.toEpochDay())) return null
    val before = if (put > 0.0) wish.copy(saved = (wish.saved - put).coerceAtLeast(0.0)) else wish
    val would = wishAsk(before, today)
    if (would <= 0.0 && put <= 0.0) return null
    val skipped = wish.skipMonth == monthKey(today)
    val goal = wishGoal(wish)
    val deadline = wish.deadline.takeIf { it > 0L }?.let { LocalDate.ofEpochDay(it) }
    val ready = if (deadline == null) readyDate(savingsPlan(goal, wish.saved, wish.monthlyPlan).months, today) else null
    return PlanAsk(
        kind = PlanKind.WISH,
        id = wish.id,
        name = wish.name,
        emoji = wishEmoji(wish.name),
        monthly = if (skipped) put else maxOf(would, put),
        wouldAsk = would,
        goal = goal,
        saved = wish.saved.coerceAtLeast(0.0),
        deadline = deadline,
        ready = ready,
        rating = if (wish.duelsPlayed > 0) duelRating(wish) else null,
        monthlyPlan = wish.monthlyPlan,
        jar = wish.jar.isNotBlank(),
        skipped = skipped,
        put = put
    )
}

/** A fund's plan, or null when it asks nothing. What went in this month counts as this month's, as for a wish. */
fun fundPlanAsk(fund: Fund, pays: List<Pay>, today: LocalDate, usdSell: Double): PlanAsk? {
    val pay = fundPay(fund, pays)
    val put = putThisMonth(fund, today)
    val before = if (put > 0.0) fund.copy(saved = (fund.saved - put).coerceAtLeast(0.0)) else fund
    val would = fundWouldAsk(before, pay, today, usdSell)
    if (would <= 0.0 && put <= 0.0) return null
    val skipped = fund.skipMonth == monthKey(today)
    val goal = fundGoal(fund, pay, usdSell)
    val deadline = fundDeadline(fund, pay, today)
    val ready = if (deadline == null && goal > 0.0 && fund.monthly > 0.0) {
        readyDate(savingsPlan(goal, fund.saved, fund.monthly).months, today)
    } else {
        null
    }
    return PlanAsk(
        kind = PlanKind.FUND,
        id = fund.id,
        name = fund.name,
        emoji = fundEmoji(fund, pay),
        monthly = if (skipped) put else maxOf(would, put),
        wouldAsk = would,
        goal = goal,
        saved = fund.saved,
        deadline = deadline,
        ready = ready,
        rating = null,
        monthlyPlan = fund.monthly,
        skipped = skipped,
        put = put
    )
}

/** The month and every plan's call on it. */
data class MoneyPlan(
    val month: HonestMonth,
    /** The funds as they stand once their payments' charges are settled. */
    val funds: List<Fund>,
    /** Every plan with something to ask, the skipped ones included. */
    val asks: List<PlanAsk>
) {
    /** The plans that ask something this month, what was already put aside included. */
    val active: List<PlanAsk> get() = asks.filter { it.monthly > 0.0 }

    /** The plans with something this month still to put aside. */
    val pending: List<PlanAsk> get() = asks.filter { it.left > 0.0 }
    val planned: Double get() = asks.sumOf { it.monthly }
    val wishPlanned: Double get() = asks.filter { it.kind == PlanKind.WISH }.sumOf { it.monthly }
    val fundPlanned: Double get() = asks.filter { it.kind == PlanKind.FUND }.sumOf { it.monthly }

    /** How much the plans exceed what is free. Nought when they fit. */
    val over: Double get() = (planned - month.free).coerceAtLeast(0.0)

    /** Each plan is set on its own, so their sum can quietly outgrow the month. */
    val conflict: Boolean get() = !month.unknown && planned > 0.0 && planned > month.free

    /** What the treat may spend: free money after the plans. */
    val treatBudget: Double get() = month.free - planned
}

fun moneyPlan(inputs: MoneyInputs): MoneyPlan {
    val funds = settledFunds(inputs)
    // What «Я відклав» put into each wish this month: still this month's money.
    val ritual = inputs.ritual?.takeIf { it.done && monthKey(LocalDate.ofEpochDay(it.day)) == monthKey(inputs.today) }
    val puts = ritual?.entries?.filter { it.kind == PlanKind.WISH }?.associate { it.id to it.amount }.orEmpty()
    val asks = inputs.wishes.mapNotNull { wishPlanAsk(it, inputs.today, puts[it.id] ?: 0.0) } +
        funds.mapNotNull { fundPlanAsk(it, inputs.pays, inputs.today, inputs.usdSell) }
    return MoneyPlan(honestMonth(inputs, funds), funds, asks)
}

/** «Плани по бажаннях і фондах просять 5 000 ₴ на місяць, а вільно 4 200 ₴. Не вистачає 800 ₴.» */
fun plansConflictLine(plan: MoneyPlan): String {
    val whose = when {
        plan.wishPlanned > 0.0 && plan.fundPlanned > 0.0 -> "Плани по бажаннях і фондах"
        plan.fundPlanned > 0.0 -> "Фонди"
        else -> "Плани по бажаннях"
    }
    val free = if (plan.month.life > 0.0) "вільно після платежів і життя" else "вільно"
    return "$whose просять ${money(askRounded(plan.planned))} на місяць, а $free ${money(plan.month.free)}. " +
        "Не вистачає ${money(askRounded(plan.over))}."
}

// ------------------------------------------------------------ «Пропустити»

/**
 * The plan «Пропустити цього місяця» names: the least wanted by the duel, or —
 * with no duel played — the one whose date is furthest off. Wishes before funds:
 * a fund is saving for a bill that will come regardless.
 */
fun skipCandidate(plan: MoneyPlan): PlanAsk? {
    val active = plan.pending
    val wishes = active.filter { it.kind == PlanKind.WISH }
    val rated = wishes.filter { it.rating != null }
    if (rated.isNotEmpty()) {
        return rated.minWithOrNull(
            compareBy<PlanAsk> { it.rating }.thenByDescending { it.farDate?.toEpochDay() ?: Long.MAX_VALUE }
        )
    }
    return wishes.ifEmpty { active }.maxByOrNull { it.farDate?.toEpochDay() ?: Long.MAX_VALUE }
}

/** «з квітня на травень», with the years only when they differ. */
fun monthShift(from: LocalDate, to: LocalDate): String =
    if (from.year == to.year) {
        "з ${monthGenitive(from.monthValue)} на ${monthName(to.monthValue).lowercase()}"
    } else {
        "з ${monthGenitive(from.monthValue)} ${from.year} на ${monthName(to.monthValue).lowercase()} ${to.year}"
    }

/**
 * What skipping this month costs, said before it is done: «з листопада по
 * 1 250 ₴ замість 1 000 ₴» for a plan by date, «ціль зсунеться з квітня на
 * травень» for one by sum.
 */
fun skipPrice(ask: PlanAsk, today: LocalDate): String {
    val next = today.plusMonths(1)
    val deadline = ask.deadline
    if (deadline != null) {
        val after = deadlinePlan(ask.goal, ask.saved, next, deadline).monthly
        val remaining = (ask.goal - ask.saved).coerceAtLeast(0.0)
        return if (after <= 0.0) {
            "до ${dayMonth(deadline)} лишиться менше місяця — ${money(askRounded(remaining))} доведеться знайти одразу"
        } else {
            "з ${monthGenitive(next.monthValue)} по ${money(askRounded(after))} замість ${money(askRounded(ask.wouldAsk))}"
        }
    }
    val ready = ask.ready ?: return "наступний внесок — з 1 ${monthGenitive(next.monthValue)}"
    return "ціль зсунеться ${monthShift(ready, ready.plusMonths(1))}"
}

/** The wish with this month skipped, or the skip taken back. */
fun skippedWish(wish: Wish, today: LocalDate, skip: Boolean): Wish =
    wish.copy(skipMonth = if (skip) monthKey(today) else "")

/** «Пропущено в жовтні · з 1 листопада знову» */
fun skippedLine(today: LocalDate): String {
    val next = today.withDayOfMonth(1).plusMonths(1)
    return "Пропущено в ${monthLocative(today.monthValue)} · з 1 ${monthGenitive(next.monthValue)} знову"
}

// ------------------------------------------------------------ payments in a window

/** One charge still to come, and what its fund covers of it. */
data class Charge(val pay: Pay, val date: LocalDate, val uah: Double, val covered: Double = 0.0) {
    /** What the card has to find. */
    val due: Double get() = (uah - covered).coerceAtLeast(0.0)
}

/**
 * The charges from [from] up to but not including [until] that are not marked
 * paid — each on its own date, so an annual one counts only if it falls inside
 * and a trial only once it has ended.
 */
fun chargesBetween(
    pays: List<Pay>,
    marks: List<PaidMark>,
    from: LocalDate,
    until: LocalDate,
    usdSell: Double,
    funds: List<Fund> = emptyList(),
    today: LocalDate = from
): List<Charge> {
    if (!until.isAfter(from)) return emptyList()
    return generateSequence(from) { it.plusDays(1) }.takeWhile { it.isBefore(until) }.flatMap { day ->
        chargedOn(pays, day).filterNot { isPaid(marks, it.name, monthKey(day)) }.map { pay ->
            val uah = chargeUah(pay, usdSell)
            val fund = funds.firstOrNull { fundPay(it, pays)?.name == pay.name }
            val covered = fund?.let { fundCoverage(it, pay, day, today, usdSell) } ?: 0.0
            Charge(pay, day, uah, minOf(covered, uah))
        }
    }.toList()
}

/** Until when the money now has to last: the next payday, or the end of the month. */
data class Period(val until: LocalDate, val salary: Boolean, val paydayKnown: Boolean)

fun periodOf(inputs: MoneyInputs): Period {
    val next = nextPayday(inputs.payday, inputs.today, inputs.holidays)
    return if (next != null) {
        Period(next.date, next.salary, true)
    } else {
        Period(inputs.today.withDayOfMonth(1).plusMonths(1), salary = false, paydayKnown = false)
    }
}

/** «до зарплати», «до авансу», «до кінця місяця» */
fun untilWords(period: Period): String = when {
    !period.paydayKnown -> "до кінця місяця"
    period.salary -> "до зарплати"
    else -> "до авансу"
}

// ------------------------------------------------------------ «Розкласти зарплату»

/** One sum «Я відклав» wrote, so it can be taken back. */
data class RitualEntry(val kind: PlanKind, val id: String, val amount: Double, val jar: Boolean = false)

/**
 * What happened to one payday's ritual: «Я відклав» (with what was written) or
 * «Не зараз». A view of this phone's last month, not app data — the sums it wrote
 * are in the wishes and funds, which is what the backup carries.
 */
data class RitualRecord(val anchor: Long, val done: Boolean, val day: Long, val entries: List<RitualEntry> = emptyList()) {
    val total: Double get() = entries.sumOf { it.amount }
}

fun ritualJson(record: RitualRecord): String = JSONObject()
    .put("mpa", record.anchor).put("mpo", record.done).put("mpd", record.day)
    .put(
        "mpe",
        JSONArray(
            record.entries.map {
                JSONObject().put("mpk", it.kind.name).put("mpi", it.id).put("mps", it.amount).put("mpj", it.jar)
            }
        )
    )
    .toString()

fun ritualOf(text: String?): RitualRecord? {
    if (text.isNullOrBlank()) return null
    return runCatching {
        val o = JSONObject(text)
        val entries = o.optJSONArray("mpe") ?: JSONArray()
        RitualRecord(
            anchor = o.getLong("mpa"),
            done = o.optBoolean("mpo", false),
            day = o.optLong("mpd", 0L),
            entries = (0 until entries.length()).mapNotNull { entries.optJSONObject(it) }.mapNotNull { e ->
                val kind = runCatching { PlanKind.valueOf(e.optString("mpk")) }.getOrNull() ?: return@mapNotNull null
                val amount = e.optDouble("mps", 0.0).takeIf { it.isFinite() && it > 0.0 } ?: return@mapNotNull null
                RitualEntry(kind, e.optString("mpi"), amount, e.optBoolean("mpj", false))
            }
        )
    }.getOrNull()
}

/** How long after a payday the ritual tile waits to be done. */
const val RITUAL_DAYS = 5

/**
 * The day the ritual on screen belongs to: the last salary day, while it is less
 * than [RITUAL_DAYS] old — or, with no payday set, the 1st of the month.
 */
fun ritualAnchor(payday: Payday, today: LocalDate, holidays: Set<Long>): LocalDate? {
    if (!payday.known) return today.withDayOfMonth(1).takeIf { today.dayOfMonth <= RITUAL_DAYS }
    val last = lastSalaryDay(payday, today, holidays) ?: return null
    return last.takeIf { ChronoUnit.DAYS.between(last, today) < RITUAL_DAYS }
}

/** The salary period now running: from the last salary day, or from the 1st with no payday set. */
fun currentAnchor(payday: Payday, today: LocalDate, holidays: Set<Long>): LocalDate =
    if (payday.known) lastSalaryDay(payday, today, holidays) ?: today.withDayOfMonth(1) else today.withDayOfMonth(1)

/** One line of the ritual: the plan, its full sum, and the sum proposed for it. */
data class RitualLine(val ask: PlanAsk, val full: Double, val proposed: Double)

data class Ritual(
    val anchor: LocalDate,
    /** From a payday rather than from the 1st of the month. */
    val fromPayday: Boolean,
    val period: Period,
    /** What leaves before the next money arrives, not yet marked paid. */
    val charges: List<Charge>,
    val lines: List<RitualLine>,
    val free: Double,
    val incomeKnown: Boolean,
    /** The plans did not fit, so the proposed sums are smaller and fit. */
    val scaled: Boolean,
    /** «Я відклав» already pressed for this payday. */
    val record: RitualRecord?
) {
    val done: Boolean get() = record?.done == true
    val paymentsDue: Double get() = charges.sumOf { it.due }
}

/**
 * The plans' sums made to fit [room]: each cut by the same share, rounded down
 * to whole hryvnias so the total never exceeds it. Unchanged when they fit.
 */
fun proportional(sums: List<Double>, room: Double): List<Double> {
    val total = sums.sum()
    if (total <= 0.0 || room >= total) return sums
    if (room <= 0.0) return sums.map { 0.0 }
    return sums.map { kotlin.math.floor(it * room / total) }
}

/** The ritual to show today, or null: outside its days, «Не зараз» said, or nothing to put aside. */
fun ritualFor(inputs: MoneyInputs, plan: MoneyPlan): Ritual? {
    val anchor = ritualAnchor(inputs.payday, inputs.today, inputs.holidays) ?: return null
    val record = inputs.ritual?.takeIf { it.anchor == anchor.toEpochDay() }
    if (record != null && !record.done) return null
    val asks = plan.pending
    if (asks.isEmpty() && record == null) return null
    val full = asks.map { askRounded(it.left) }
    val known = !plan.month.unknown
    val scaled = known && full.sum() > plan.month.free
    val proposed = if (scaled) proportional(full, plan.month.free) else full
    val period = periodOf(inputs)
    return Ritual(
        anchor = anchor,
        fromPayday = inputs.payday.known,
        period = period,
        charges = chargesBetween(inputs.pays, inputs.marks, inputs.today, period.until, inputs.usdSell, plan.funds, inputs.today),
        lines = asks.indices.map { RitualLine(asks[it], full[it], proposed[it]) },
        free = plan.month.free,
        incomeKnown = known,
        scaled = scaled,
        record = record
    )
}

/** «Відкладаєте 3 500 ₴ — з вільних лишиться 4 200 ₴» */
fun ritualSummary(total: Double, free: Double, incomeKnown: Boolean): String = when {
    !incomeKnown -> "Відкладаєте ${money(total)}"
    free - total >= 0.0 -> "Відкладаєте ${money(total)} — з вільних лишиться ${money(free - total)}"
    else -> "Відкладаєте ${money(total)} — це на ${money(total - free)} більше, ніж вільно"
}

/** The tile's heading line: «Зарплата сьогодні · до наступної 25 листопада». */
fun ritualHeading(ritual: Ritual, today: LocalDate): String {
    val got = when {
        !ritual.fromPayday -> "Початок місяця"
        ritual.anchor == today -> "Зарплата сьогодні"
        else -> "Зарплата ${dayMonth(ritual.anchor)}"
    }
    val next = if (ritual.period.paydayKnown) " · наступна ${dayMonth(ritual.period.until)}" else ""
    return got + next
}

/** «До наступної зарплати спишеться 12 820 ₴ · 12 платежів» */
fun ritualPaymentsLine(ritual: Ritual): String {
    val until = if (ritual.period.paydayKnown) "До ${dayMonth(ritual.period.until)}" else "До кінця місяця"
    return if (ritual.charges.isEmpty()) "$until платежів більше немає"
    else "$until спишеться ${money(ritual.paymentsDue)} · ${paymentsLabel(ritual.charges.size)}"
}

/**
 * «Я відклав»: each chosen sum added to its wish's «Вже відкладено» or its fund.
 * A wish kept in a jar is left alone — the jar's balance is its figure.
 */
fun applyRitual(
    wishes: List<Wish>,
    funds: List<Fund>,
    chosen: List<Pair<PlanAsk, Double>>,
    anchor: LocalDate,
    today: LocalDate
): Triple<List<Wish>, List<Fund>, RitualRecord> {
    val entries = chosen.filter { it.second > 0.0 }.map { (ask, amount) -> RitualEntry(ask.kind, ask.id, amount, ask.jar) }
    val nextWishes = wishes.map { wish ->
        val entry = entries.firstOrNull { it.kind == PlanKind.WISH && it.id == wish.id && !it.jar }
        if (entry != null) wish.copy(saved = wish.saved + entry.amount) else wish
    }
    val nextFunds = funds.map { fund ->
        val entry = entries.firstOrNull { it.kind == PlanKind.FUND && it.id == fund.id }
        if (entry != null) putInto(fund, entry.amount, today) else fund
    }
    return Triple(nextWishes, nextFunds, RitualRecord(anchor.toEpochDay(), true, today.toEpochDay(), entries))
}

/** «Скасувати»: the sums «Я відклав» wrote, taken back out. */
fun undoRitual(wishes: List<Wish>, funds: List<Fund>, record: RitualRecord): Pair<List<Wish>, List<Fund>> {
    val nextWishes = wishes.map { wish ->
        val entry = record.entries.firstOrNull { it.kind == PlanKind.WISH && it.id == wish.id && !it.jar }
        if (entry != null) wish.copy(saved = (wish.saved - entry.amount).coerceAtLeast(0.0)) else wish
    }
    val nextFunds = funds.map { fund ->
        val entry = record.entries.firstOrNull { it.kind == PlanKind.FUND && it.id == fund.id }
        if (entry != null) {
            fund.copy(
                saved = (fund.saved - entry.amount).coerceAtLeast(0.0),
                putAmount = (fund.putAmount - entry.amount).coerceAtLeast(0.0),
                putMonth = if (fund.putAmount - entry.amount > 0.0) fund.putMonth else ""
            )
        } else {
            fund
        }
    }
    return nextWishes to nextFunds
}

/** «Відкладено 3 500 ₴ 23 жовтня» — the tile once it is done. */
fun ritualDoneLine(record: RitualRecord): String =
    "Відкладено ${money(record.total)} ${dayMonth(LocalDate.ofEpochDay(record.day))}"

// ------------------------------------------------------------ the morning message

/**
 * The plan's lines for the morning message: the eve of a salary day — «Завтра
 * зарплата — за планом 3 500 ₴ на бажання і фонди» — and, with no payday set, the
 * 1st of the month for the funds: «Час відкласти у фонди: 1 240 ₴». One prompt a
 * month, at the moment money can be put aside.
 */
fun planDigestLines(inputs: MoneyInputs, plan: MoneyPlan): List<String> {
    val today = inputs.today
    if (inputs.payday.known) {
        val tomorrow = today.plusDays(1)
        val salaryTomorrow = paydaysAround(inputs.payday, today, inputs.holidays).any { it.salary && it.date == tomorrow }
        if (!salaryTomorrow) return emptyList()
        val wishes = plan.pending.filter { it.kind == PlanKind.WISH }.sumOf { askRounded(it.left) }
        val funds = plan.pending.filter { it.kind == PlanKind.FUND }.sumOf { askRounded(it.left) }
        val line = when {
            wishes > 0.0 && funds > 0.0 -> "Завтра зарплата — за планом ${money(wishes + funds)} на бажання і фонди"
            wishes > 0.0 -> "Завтра зарплата — за планом ${money(wishes)} на бажання"
            funds > 0.0 -> "Завтра зарплата — за планом ${money(funds)} у фонди"
            else -> return emptyList()
        }
        return listOf(line)
    }
    if (today.dayOfMonth != 1) return emptyList()
    val due = plan.pending.filter { it.kind == PlanKind.FUND }.sumOf { askRounded(it.left) }
    return if (due > 0.0) listOf("Час відкласти у фонди: ${money(due)}") else emptyList()
}

// ------------------------------------------------------------ «Місяць наперед»

/** How much of next month is already paid for. */
data class MonthAhead(
    /** The 1st of next month. */
    val month: LocalDate,
    /** Next month's payments, each on its own charge date. */
    val payments: Double,
    /** «На життя» for it; nought when switched off. */
    val life: Double,
    /** What the funds already hold of next month's annual charges. */
    val covered: Double,
    /** The «Подушка» fund. */
    val cushion: Double,
    /** This month's payments by the same rule, for «дорожчий на». */
    val thisMonth: Double,
    /** Why next month costs more: an annual charge, a trial ending, a plan starting. */
    val reasons: List<String>
) {
    val needed: Double get() = payments + life
    val have: Double get() = cushion + covered

    /** 0..1+; a month with nothing to pay is fully paid. */
    val share: Double get() = if (needed > 0.0) have / needed else 1.0
    val percent: Int get() = kotlin.math.floor(share.coerceIn(0.0, 1.0) * 100).toInt()
    val dearerBy: Double get() = payments - thisMonth
}

/** Next month's cost and cover, or null while there is no «Подушка» fund to measure. */
fun monthAhead(inputs: MoneyInputs, plan: MoneyPlan): MonthAhead? {
    val cushion = plan.funds.firstOrNull { it.cushion } ?: return null
    val today = inputs.today
    val thisMonth = today.withDayOfMonth(1)
    val next = thisMonth.plusMonths(1)
    val pays = inputs.pays
    fun costsIn(pay: Pay, month: LocalDate) =
        chargesIn(pay, month) && !onTrial(pay, chargeDayIn(pay, month).toEpochDay())
    val reasons = pays.filter { costsIn(it, next) && !costsIn(it, thisMonth) }.map { pay ->
        when {
            isAnnual(pay) -> "річний платіж «${pay.name}»"
            pay.trialEnd > 0L && onTrial(pay, chargeDayIn(pay, thisMonth).toEpochDay()) -> "закінчується пробний період «${pay.name}»"
            isInstalment(pay) -> "починається розстрочка «${pay.name}»"
            else -> "«${pay.name}»"
        }
    }
    return MonthAhead(
        month = next,
        payments = monthCostAt(pays, next, inputs.usdSell, today).total,
        life = inputs.life.amount,
        covered = coveredIn(plan.funds, pays, next, today, inputs.usdSell),
        cushion = cushion.saved,
        thisMonth = monthCostAt(pays, thisMonth, inputs.usdSell, today).total,
        reasons = reasons
    )
}

/** «Платежі листопада покриті на 64%», «Листопад покрито на 64%», «Наступний місяць уже оплачено». */
fun monthAheadHeadline(ahead: MonthAhead): String = when {
    ahead.share >= 1.0 -> "Наступний місяць уже оплачено"
    ahead.life > 0.0 -> "${monthName(ahead.month.monthValue)} покрито на ${ahead.percent}%"
    else -> "Платежі ${monthGenitive(ahead.month.monthValue)} покриті на ${ahead.percent}%"
}

/** «Подушка = 19 днів» — only with «На життя» on: without it a day of life has no price. */
fun cushionDaysLine(ahead: MonthAhead): String? {
    if (ahead.life <= 0.0 || ahead.needed <= 0.0) return null
    val days = kotlin.math.floor(ahead.have / (ahead.needed / ahead.month.lengthOfMonth())).toInt()
    return "Подушка = ${daysLabel(days.coerceAtLeast(0))}"
}

/**
 * What the ring is made of: «подушка 8 000 ₴ з 17 300 ₴ платежів», or with life
 * «подушка 8 000 ₴ з 29 300 ₴: платежі 17 300 ₴ + життя 12 000 ₴».
 */
fun monthAheadDetail(ahead: MonthAhead): String {
    val have = listOfNotNull(
        "подушка ${money(kotlin.math.round(ahead.cushion))}",
        ahead.covered.takeIf { it > 0.0 }?.let { "фонди ${money(kotlin.math.round(it))}" }
    ).joinToString(" + ")
    val need = if (ahead.life > 0.0) {
        "з ${money(kotlin.math.round(ahead.needed))}: платежі ${money(kotlin.math.round(ahead.payments))} + " +
            "життя ${money(kotlin.math.round(ahead.life))}"
    } else {
        "з ${money(kotlin.math.round(ahead.payments))} платежів"
    }
    return "$have $need"
}

/** «Листопад дорожчий на 1 800 ₴: річний платіж «Автоцивілка», закінчується пробний період «Megogo»» */
fun dearerLine(ahead: MonthAhead): String? {
    val by = ahead.dearerBy
    if (by < 1.0 || ahead.reasons.isEmpty()) return null
    return "${monthName(ahead.month.monthValue)} дорожчий на ${money(kotlin.math.round(by))}: ${ahead.reasons.joinToString(", ")}"
}

// ------------------------------------------------------------ what the screens say

/** The label over «Вільно» on Огляд. */
fun heroLabel(month: HonestMonth): String = when {
    month.unknown -> "Вкажіть дохід на Платежах"
    month.overspent -> "Не сходиться цього місяця"
    month.life > 0.0 -> "Вільно після платежів і життя"
    else -> "Вільно до кінця місяця"
}

/**
 * The line under it: what the figure is made of — «змінити» beside life, since
 * life is the owner's own guess — what the funds paid, and the payday.
 */
fun heroCaption(month: HonestMonth, countdown: String?): String {
    val own = month.payments.total - month.covered
    val made = when {
        month.unknown -> committedDetail(committedOf(month.asBudget()))
        month.life > 0.0 -> "Платежі ${money(own)} · на життя ${money(month.life)} · змінити"
        month.covered > 0.0 -> "Постійні витрати ${money(own)} з ${money(month.income)}"
        else -> committedDetail(committedOf(month.asBudget()))
    }
    val second = listOfNotNull(
        month.covered.takeIf { !month.unknown && it > 0.0 }?.let { "з фондів ${money(it)}" },
        countdown
    ).joinToString(" · ").takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() }
    return listOfNotNull(made, second).joinToString("\n")
}

/** The caption under «Лишається» on Платежі, with life and the funds when they are in it. */
fun monthBarDetail(month: HonestMonth): String? = when {
    month.unknown -> null
    month.life > 0.0 -> "Платежі ${money(month.payments.total - month.covered)} · 🛒 життя ${money(month.life)} з ${money(month.income)}"
    month.covered > 0.0 -> "Постійні витрати ${money(month.payments.total - month.covered)} з ${money(month.income)} · ще ${money(month.covered)} з фондів"
    else -> null
}

/** Life's share of the income, for its own segment of the bar. */
fun lifeShare(month: HonestMonth): Float =
    if (month.unknown || month.life <= 0.0) 0f else (month.life / month.income).coerceIn(0.0, 1.0).toFloat()

/** The settings row for «На життя». */
fun lifeRowDetail(life: LifeCost): String =
    if (life.amount > 0.0) "${money(life.amount)} на місяць · «Вільно» — після платежів і життя"
    else "Вимкнено · «Вільно» рахується без їжі й дороги"

/** The settings row for the income and the payday. */
fun incomeRowDetail(income: Double, payday: Payday): String {
    val got = if (income > 0.0) "${money(income)} на місяць" else "Дохід не вказано"
    return if (payday.known) "$got · зарплата: ${paydayLabel(payday)}" else "$got · день зарплати не вказано"
}

/** «Найближча зарплата — 23 жовтня», said in the income dialog while the payday is set. */
fun nextPaydayNote(payday: Payday, today: LocalDate, holidays: Set<Long>): String? {
    if (!payday.known) return null
    paydayToday(payday, today, holidays)?.let { return if (it.salary) "Зарплата сьогодні" else "Аванс сьогодні" }
    val next = nextPayday(payday, today, holidays) ?: return null
    return "Найближча ${if (next.salary) "зарплата" else "виплата авансу"} — ${dayMonth(next.date)}"
}

/**
 * «У фондах 10 940 ₴ · цього місяця відкласти 1 240 ₴» — the funds tile's heading
 * line. One sum, not «з»: a cushion has no goal, so the goals do not add up to
 * anything the saved sum could be a share of.
 */
fun fundsSummary(plan: MoneyPlan): String {
    val saved = plan.funds.sumOf { it.saved }
    val due = plan.pending.filter { it.kind == PlanKind.FUND }.sumOf { askRounded(it.left) }
    val head = "У фондах ${money(kotlin.math.round(saved))}"
    return if (due > 0.0) "$head · цього місяця відкласти ${money(due)}" else head
}

// ------------------------------------------------------------ dates said plainly

/** «Сьогодні», «Завтра», «Субота, 10 жовтня». */
fun dayWords(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Сьогодні"
    today.plusDays(1) -> "Завтра"
    else -> "${WEEKDAYS_LONG[date.dayOfWeek.value - 1]}, ${dayMonth(date)}"
}

private val WEEKDAYS_LONG = listOf("Понеділок", "Вівторок", "Середа", "Четвер", "Пʼятниця", "Субота", "Неділя")

/** The coming Saturday, or today when it is already the weekend. */
fun thisWeekend(today: LocalDate): LocalDate = when (today.dayOfWeek) {
    DayOfWeek.SATURDAY, DayOfWeek.SUNDAY -> today
    else -> today.plusDays((DayOfWeek.SATURDAY.value - today.dayOfWeek.value).toLong())
}

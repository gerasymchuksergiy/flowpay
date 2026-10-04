package com.flowpay.app

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * «Чи потягну?» and «Скільки можна сьогодні» — what a purchase does to the money
 * before it is made, and what the card can spend per day until money arrives
 * (research: Cleo's «Can I afford it?», monobank's balance, Rocket Money's
 * «left to spend until payday»). Pure arithmetic over the stored data and one
 * balance; no model, no invented figure.
 *
 * Two ways of knowing, said apart on screen:
 *
 * - **By balance** — what is on the card now (monobank's own money when it is
 *   connected, else the one number the owner typed, remembered with its date),
 *   less what is still to be paid before the next payday, less this period's
 *   plan contributions not yet put aside.
 * - **By plan** — no balance: the month's «Вільно» less the plans. Labelled «за
 *   планом, не за балансом», because it cannot see what has been spent.
 */

/** What is spent before the next money arrives: the card's balance less what it already owes. */
data class Allowance(
    val balance: Double,
    val period: Period,
    val days: Int,
    val charges: List<Charge>,
    /** This period's plan contributions not yet put aside; nought once «Я відклав» was pressed. */
    val plans: Double,
    val plansDone: Boolean
) {
    val payments: Double get() = charges.sumOf { it.due }
    val left: Double get() = balance - payments - plans
    val perDay: Double get() = if (days > 0) left / days else left
}

/**
 * The plan contributions of the period now running that are still on the card:
 * none once «Я відклав» was pressed for it; a fund ticked «✅ Відклав» this month
 * is out already.
 */
fun plansToPutAside(inputs: MoneyInputs, plan: MoneyPlan): Double =
    pendingAsks(inputs, plan).sumOf { askRounded(it.monthly) }

/** The plans whose contribution for the period now running has not been put aside yet. */
fun pendingAsks(inputs: MoneyInputs, plan: MoneyPlan): List<PlanAsk> {
    val anchor = currentAnchor(inputs.payday, inputs.today, inputs.holidays).toEpochDay()
    if (inputs.ritual?.let { it.done && it.anchor == anchor } == true) return emptyList()
    return plan.active.filter { ask ->
        val fund = if (ask.kind == PlanKind.FUND) plan.funds.firstOrNull { it.id == ask.id } else null
        fund == null || putThisMonth(fund, inputs.today) <= 0.0
    }
}

fun allowance(inputs: MoneyInputs, plan: MoneyPlan, balance: Double): Allowance {
    val period = periodOf(inputs)
    val anchor = currentAnchor(inputs.payday, inputs.today, inputs.holidays).toEpochDay()
    return Allowance(
        balance = balance,
        period = period,
        days = ChronoUnit.DAYS.between(inputs.today, period.until).toInt().coerceAtLeast(1),
        charges = chargesBetween(inputs.pays, inputs.marks, inputs.today, period.until, inputs.usdSell, plan.funds, inputs.today),
        plans = plansToPutAside(inputs, plan),
        plansDone = inputs.ritual?.let { it.done && it.anchor == anchor } == true
    )
}

/** A per-day figure as a person budgets it: rounded down to tens, never below nought. */
fun perDayFigure(value: Double): Double = (kotlin.math.floor(value / 10.0) * 10.0).coerceAtLeast(0.0)

/** «Можна ~620 ₴ на день», or «До зарплати не вистачає 1 200 ₴ на платежі». */
fun allowanceHeadline(a: Allowance): String = when {
    a.left >= 0.0 -> "Можна ~${money(perDayFigure(a.perDay))} на день"
    a.balance - a.payments < 0.0 ->
        "${untilWords(a.period).replaceFirstChar { it.uppercase() }} не вистачає ${money(kotlin.math.round(-a.left))} на платежі"
    else -> "${untilWords(a.period).replaceFirstChar { it.uppercase() }} не вистачає ${money(kotlin.math.round(-a.left))} на платежі й внески"
}

/** «до зарплати 9 днів» */
fun allowanceWhen(a: Allowance): String = "${untilWords(a.period)} ${daysLabel(a.days)}"

/** «на картках 18 400 ₴ − 12 платежів 12 820 ₴ − внески 3 500 ₴ · оновлено 09:12» */
fun allowanceDetail(a: Allowance, updatedAt: Long): String {
    val parts = buildList {
        add("на картках ${money(kotlin.math.round(a.balance))}")
        if (a.charges.isNotEmpty()) add("${paymentsLabel(a.charges.size)} ${money(kotlin.math.round(a.payments))}")
        if (a.plans > 0.0) add("внески ${money(a.plans)}")
    }.joinToString(" − ")
    val done = if (a.plansDone) " · внески вже відкладено" else ""
    val fresh = if (updatedAt > 0L) " · оновлено ${timeLabel(updatedAt)}" else ""
    return parts + done + fresh
}

// ------------------------------------------------------------ «Чи потягну?»

enum class AffordVerdict { FITS, PLANS_MOVE, SHORT_PAYMENTS, SHORT_LIFE }

/** A plan that gives way to the purchase, and what that costs it. */
data class PlanMove(val ask: PlanAsk, val taken: Double, val effect: String)

/** Something that would make it fit, with what it is worth. */
data class Lever(val text: String, val gain: Double)

data class Affordability(
    val price: Double,
    val date: LocalDate,
    val verdict: AffordVerdict,
    val headline: String,
    /** Worked out from a balance; false means «за планом, не за балансом». */
    val byBalance: Boolean,
    /** The basis said in words when it is not a balance. */
    val basis: String?,
    /** «9 днів: ≈1 380 → 1 160 ₴ на день», by balance only. */
    val perDay: String?,
    val shortfall: Double,
    val moves: List<PlanMove>,
    val weather: String?,
    val treat: String?,
    val levers: List<Lever>
)

/** The plans in the order they give way: the least wanted by the duel, then the furthest off; funds last. */
fun givingWayOrder(asks: List<PlanAsk>): List<PlanAsk> {
    val wishes = asks.filter { it.kind == PlanKind.WISH && it.monthly > 0.0 }
    val rated = wishes.filter { it.rating != null }.sortedBy { it.rating }
    val unrated = wishes.filter { it.rating == null }.sortedByDescending { it.farDate?.toEpochDay() ?: Long.MAX_VALUE }
    val funds = asks.filter { it.kind == PlanKind.FUND && it.monthly > 0.0 }
        .sortedByDescending { it.farDate?.toEpochDay() ?: Long.MAX_VALUE }
    return rated + unrated + funds
}

/** «на 2 тижні», «на тиждень», «на місяць», «на 5 днів» */
fun shiftWords(days: Double): String {
    val d = kotlin.math.round(days).toInt().coerceAtLeast(1)
    return when {
        d < 6 -> "на ${daysLabel(d)}"
        d < 11 -> "на тиждень"
        d < 18 -> "на 2 тижні"
        d < 25 -> "на 3 тижні"
        d < 46 -> "на місяць"
        else -> "на ${monthsLabel(kotlin.math.round(d / AVERAGE_MONTH_DAYS).toInt())}"
    }
}

/**
 * What taking [taken] from this month's contribution does to a plan: a date's
 * plan asks more from next month on; a plan by sum reaches its goal later.
 */
fun moveEffect(ask: PlanAsk, taken: Double, today: LocalDate): String {
    val deadline = ask.deadline
    if (deadline != null) {
        val next = today.plusMonths(1)
        val after = deadlinePlan(ask.goal, ask.saved + (ask.monthly - taken).coerceAtLeast(0.0), next, deadline).monthly
        return if (after <= 0.0) {
            "до ${dayMonth(deadline)} доведеться знайти ще ${money(askRounded(taken))} одразу"
        } else {
            "з ${monthGenitive(next.monthValue)} проситиме ${money(askRounded(after))} замість ${money(askRounded(ask.monthly))}"
        }
    }
    val rate = ask.monthlyPlan.takeIf { it > 0.0 } ?: ask.monthly
    return if (ask.ready != null && rate > 0.0) {
        "зсунеться ${shiftWords(taken / rate * AVERAGE_MONTH_DAYS)}"
    } else {
        "отримає на ${money(askRounded(taken))} менше"
    }
}

/** Which plans give way, and by how much, for [shortfall] of this month's contributions. */
fun planMoves(asks: List<PlanAsk>, shortfall: Double, today: LocalDate): List<PlanMove> {
    var left = shortfall
    val moves = mutableListOf<PlanMove>()
    for (ask in givingWayOrder(asks)) {
        if (left <= 0.0) break
        val taken = minOf(askRounded(ask.monthly), left)
        moves += PlanMove(ask, taken, moveEffect(ask, taken, today))
        left -= taken
    }
    return moves
}

/** «Субота стане грозою ⛈️», «Сьогодні: дрібниці 🌤️ → дощ 🌧️» — the forecast for the day of the purchase. */
fun weatherWith(inputs: MoneyInputs, plan: MoneyPlan, price: Double, date: LocalDate): String {
    val due = chargesBetween(inputs.pays, inputs.marks, date, date.plusDays(1), inputs.usdSell, plan.funds, inputs.today)
    val leaving = due.sumOf { it.due }
    val free = projectedFree(inputs, plan.funds, date)
    val before = moneySky(leaving, plan.month.income, free)
    val after = moneySky(leaving + price, plan.month.income, free - price)
    val day = dayWords(date, inputs.today)
    return when {
        before == MoneySky.STORM -> "$day і так гроза ${MoneySky.STORM.emoji}"
        after == MoneySky.STORM -> "$day стане грозою ${MoneySky.STORM.emoji}"
        after != before -> "$day: ${before.word} ${before.emoji} → ${after.word} ${after.emoji}"
        else -> "$day лишиться «${after.word}» ${after.emoji}"
    }
}

/** Whether the month's treat survives the purchase — said only when there is one, and it is not the thing being bought. */
fun treatWith(treat: Treat?, plan: MoneyPlan, price: Double, buyingWish: String?): String? {
    if (treat == null || treat.wish.id == buyingWish) return null
    val available = plan.treatBudget - price
    return if (available > 0.0 && treat.wish.price <= available * 0.8) {
        "Подарунок собі «${treat.wish.name}» лишається"
    } else {
        "«${treat.wish.name}» цього місяця вже не влізе в подарунок собі"
    }
}

/** «Скасувати пробний «Megogo» до 11 жовтня: +199 ₴» — trials whose first charge falls before [until]. */
fun trialLevers(inputs: MoneyInputs, until: LocalDate): List<Lever> =
    inputs.pays.filter { onTrial(it, inputs.today.toEpochDay()) && !isFinished(it, inputs.today) }.mapNotNull { pay ->
        val charge = nextCharge(pay, inputs.today)
        if (!charge.isBefore(until) || isPaid(inputs.marks, pay.name, monthKey(charge))) return@mapNotNull null
        val gain = chargeUah(pay, inputs.usdSell)
        if (gain <= 0.0) return@mapNotNull null
        Lever("Скасувати пробний «${pay.name}» до ${dayMonth(charge.minusDays(1))}", gain)
    }

/**
 * What buying [price] on [date] does.
 *
 * By balance (when one is known and the date falls before the next payday): the
 * balance less the payments due before then must cover the purchase; with «На
 * життя» on, life until then too; and what is left after that is what this
 * period's plan contributions can still have — the plans that do not fit give
 * way, least wanted first. Otherwise by plan: the month's «Вільно» against the
 * purchase and the plans.
 */
fun affordability(
    inputs: MoneyInputs,
    plan: MoneyPlan,
    price: Double,
    date: LocalDate,
    /** The card's money now; null when nothing is known. */
    balance: Double?,
    treat: Treat? = null,
    /** The wish being considered, so the treat line does not talk about the purchase itself. */
    buyingWish: String? = null
): Affordability {
    val today = inputs.today
    val period = periodOf(inputs)
    val inPeriod = date.isBefore(period.until)
    val words = untilWords(period).replaceFirstChar { it.uppercase() }
    val life = inputs.life.amount > 0.0

    val verdict: AffordVerdict
    val shortfall: Double
    var moves: List<PlanMove> = emptyList()
    var perDay: String? = null
    val byBalance = balance != null && inPeriod
    val basis: String?
    if (byBalance) {
        val days = ChronoUnit.DAYS.between(today, period.until).toInt().coerceAtLeast(1)
        val charges = chargesBetween(inputs.pays, inputs.marks, today, period.until, inputs.usdSell, plan.funds, today)
        val payments = charges.sumOf { it.due }
        val lifeNeed = lifePerDay(inputs.life) * days
        val plans = plansToPutAside(inputs, plan)
        val afterPayments = balance!! - payments
        when {
            afterPayments - price < 0.0 -> {
                verdict = AffordVerdict.SHORT_PAYMENTS
                shortfall = price - afterPayments
            }
            life && afterPayments - lifeNeed - price < 0.0 -> {
                verdict = AffordVerdict.SHORT_LIFE
                shortfall = price + lifeNeed - afterPayments
            }
            afterPayments - lifeNeed - plans - price < 0.0 -> {
                verdict = AffordVerdict.PLANS_MOVE
                shortfall = price + lifeNeed + plans - afterPayments
                // Only what has not been put aside yet can give way.
                moves = planMoves(pendingAsks(inputs, plan), shortfall, today)
            }
            else -> {
                verdict = AffordVerdict.FITS
                shortfall = 0.0
            }
        }
        val before = (afterPayments - plans) / days
        val taken = moves.sumOf { it.taken }
        val after = (afterPayments - plans + taken - price) / days
        perDay = "${daysLabel(days)}: ≈${bareAmount(perDayFigure(before))} → ${money(perDayFigure(after))} на день"
        basis = null
    } else {
        val free = projectedFree(inputs, plan.funds, date)
        val planned = plan.planned
        when {
            free + inputs.life.amount - price < 0.0 -> {
                verdict = AffordVerdict.SHORT_PAYMENTS
                shortfall = price - free - inputs.life.amount
            }
            life && free - price < 0.0 -> {
                verdict = AffordVerdict.SHORT_LIFE
                shortfall = price - free
            }
            free - planned - price < 0.0 -> {
                verdict = AffordVerdict.PLANS_MOVE
                shortfall = price + planned - free
                moves = planMoves(plan.active, shortfall, today)
            }
            else -> {
                verdict = AffordVerdict.FITS
                shortfall = 0.0
            }
        }
        basis = if (balance != null) "після ${dayMonth(period.until)} — за планом, не за балансом" else "за планом, не за балансом"
    }

    val when_ = if (byBalance) words else if (monthKey(date) == monthKey(today)) "Цього місяця" else "У ${monthLocative(date.monthValue)}"
    val headline = when (verdict) {
        AffordVerdict.FITS -> "Влазить"
        AffordVerdict.SHORT_PAYMENTS -> "$when_ не влазить: бракує ${money(askRounded(shortfall))} на платежі"
        AffordVerdict.SHORT_LIFE -> "$when_ не влазить: бракує ${money(askRounded(shortfall))} на життя"
        AffordVerdict.PLANS_MOVE -> when {
            moves.size == 1 -> "Влазить, але «${moves.single().ask.name}» ${moves.single().effect}"
            moves.size > 1 -> "Влазить, але зсунуться плани: ${moves.joinToString(", ") { "«${it.ask.name}»" }}"
            else -> "Влазить, але на плани цього місяця не лишиться"
        }
    }

    val levers = if (verdict == AffordVerdict.FITS) emptyList() else buildList {
        if (byBalance) addAll(trialLevers(inputs, period.until))
        if (inPeriod && period.paydayKnown) {
            add(Lever("Купити ${dayMonth(period.until.plusDays(1))}, після ${if (period.salary) "зарплати" else "авансу"}", price))
        }
    }

    return Affordability(
        price = price,
        date = date,
        verdict = verdict,
        headline = headline,
        byBalance = byBalance,
        basis = basis,
        perDay = perDay,
        shortfall = shortfall,
        moves = moves,
        weather = weatherWith(inputs, plan, price, date),
        treat = treatWith(treat, plan, price, buyingWish),
        levers = levers
    )
}

/** «+1 000 ₴» — what a lever is worth. */
fun leverGain(lever: Lever): String = "+${money(askRounded(lever.gain))}"

// ------------------------------------------------------------ «Купив частинами»

/** The instalment payment «Купив частинами» adds: the price in [count] monthly payments, the first on [date]. */
fun instalmentPayFor(name: String, price: Double, count: Int, date: LocalDate, emoji: String = ""): Pay {
    val n = count.coerceIn(2, MAX_INSTALMENTS)
    val each = kotlin.math.round(price / n * 100.0) / 100.0
    return Pay(
        name = name.ifBlank { "Покупка частинами" },
        amount = each,
        day = date.dayOfMonth,
        emoji = emoji,
        instalments = n,
        instalmentStart = date.toEpochDay()
    )
}

/** One coming month's free money, without and with the instalments. */
data class MonthFree(val month: LocalDate, val before: Double, val after: Double)

/** The free money in each month the plan runs through, at most [limit] of them. */
fun instalmentPreview(inputs: MoneyInputs, plan: MoneyPlan, pay: Pay, limit: Int = 6): List<MonthFree> {
    if (!isInstalment(pay)) return emptyList()
    return (1..minOf(pay.instalments, limit)).map { n ->
        val month = instalmentDate(pay, n).withDayOfMonth(1)
        MonthFree(
            month,
            projectedFree(inputs, plan.funds, month),
            projectedFree(inputs, plan.funds, month, inputs.pays + pay)
        )
    }
}

/** «жовтень: 4 200 → 2 284 ₴» */
fun monthFreeLine(line: MonthFree): String =
    "${monthName(line.month.monthValue).lowercase()}: ${bareAmount(kotlin.math.round(line.before))} → ${money(kotlin.math.round(line.after))}"

/** «вписано сьогодні», «вписано вчора», «вписано 3 жовтня» — how old a typed balance is. */
fun typedBalanceAge(day: Long, today: LocalDate): String = when {
    day <= 0L -> ""
    day == today.toEpochDay() -> "вписано сьогодні"
    day == today.toEpochDay() - 1 -> "вписано вчора"
    else -> "вписано ${dayMonth(LocalDate.ofEpochDay(day))}"
}

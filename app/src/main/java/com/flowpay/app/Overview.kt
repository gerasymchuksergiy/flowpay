package com.flowpay.app

import java.time.LocalDate

/**
 * The one place that answers "how am I doing" across all four screens at once.
 *
 * Each screen knows its own corner: wishes know prices, expenses know the month,
 * parcels know where they are. None of them could say whether the whole thing adds
 * up, which is the question you actually open the app with.
 */

data class Overview(
    val wishCount: Int,
    /** Sum of what every wish is aiming at. */
    val wishTotal: Double,
    val savedTotal: Double,
    val savedProgress: Float,
    /** Wishes whose money is already there. */
    val readyCount: Int,
    val income: Double,
    val monthlyExpenses: Double,
    val freeCash: Double,
    val budgetUnknown: Boolean,
    val overspent: Boolean,
    /** Ordered or in transit: nothing to do but wait. */
    val parcelsMoving: Int,
    /** Waiting at a branch, which is the one that asks something of you. */
    val parcelsAtBranch: Int,
    val parcelsDone: Int,
    /**
     * Months to fund everything still unfunded at the current free cash. Zero when
     * there is nothing left to save, and null when it cannot be known.
     */
    val monthsToFundAll: Int?,
    /** Everything the savings plans together ask for each month. */
    val plannedMonthly: Double,
    /** How much those plans exceed what is actually free. Zero when they fit. */
    val plansOverBudget: Double,
    val plansConflict: Boolean,
    /** What the watched prices have done since tracking began. */
    val movement: PriceMovement,
    /** «На життя» taken from [freeCash]; nought when switched off. */
    val lifeCost: Double = 0.0,
    /** What the funds hold of this month's annual charges, counted back into [freeCash]. */
    val fundsCovered: Double = 0.0
)

/**
 * What one wish asks of this month.
 *
 * The plan as it is actually set: a wish planned by date asks what the date
 * demands, and a wish already fully saved for asks nothing. Summing the stored
 * monthly figure instead counted «Знаю дату» wishes as nought — so «Плани не
 * сходяться» could never fire for them — and kept paying into wishes already paid.
 *
 * A wish put aside until a date asks nothing while it waits: the rule is that the
 * app does not nag about what was decided (HANDOFF §12), and a held wish's plan
 * kept «Плани не сходяться» lit and the treat's budget short (found by the
 * research, 4 October 2026). A month «Пропустити» was pressed for asks nothing
 * either, and the 1st of the next month brings the plan back by itself.
 */
fun plannedMonthly(wish: Wish, today: LocalDate): Double {
    if (onHold(wish, today.toEpochDay())) return 0.0
    if (wish.skipMonth == monthKey(today)) return 0.0
    return wishAsk(wish, today)
}

/** What the wish's plan would ask this month, a hold or a skip aside. */
fun wishAsk(wish: Wish, today: LocalDate): Double {
    val goal = wishGoal(wish)
    if (goal > 0.0 && wish.saved >= goal) return 0.0
    if (wish.deadline > 0L) {
        return deadlinePlan(goal, wish.saved, today, LocalDate.ofEpochDay(wish.deadline))
            .monthly.coerceAtLeast(0.0)
    }
    return wish.monthlyPlan.coerceAtLeast(0.0)
}

fun overview(
    wishes: List<Wish>,
    pays: List<Pay>,
    orders: List<Order>,
    income: Double,
    usdSellRate: Double,
    /** Needed only to know which subscriptions are still inside a free trial. */
    today: LocalDate,
    /**
     * The month and its plans as MoneyPlan.kt works them out — life, funds and
     * skips included. Absent, they are worked out from the lists above alone,
     * which is what the overview always was.
     */
    plan: MoneyPlan? = null
): Overview {
    val goals = wishes.sumOf { wishGoal(it) }
    val saved = wishes.sumOf { it.saved.coerceAtLeast(0.0) }
    val money = plan ?: moneyPlan(MoneyInputs(today, income, pays, wishes = wishes, usdSell = usdSellRate))
    val month = money.month.asBudget()
    val remaining = (goals - saved).coerceAtLeast(0.0)
    val planned = money.planned
    // What the wishes can count on each month: the free money less what the funds
    // take first — a fund saves for a bill that will come whatever is wanted.
    val forWishes = month.free - money.fundPlanned
    return Overview(
        movement = priceMovement(wishes),
        wishCount = wishes.size,
        wishTotal = goals,
        savedTotal = saved,
        savedProgress = if (goals > 0) (saved / goals).coerceIn(0.0, 1.0).toFloat() else 0f,
        readyCount = wishes.count { wishGoal(it) > 0 && it.saved >= wishGoal(it) },
        income = month.income,
        monthlyExpenses = money.month.payments.total,
        freeCash = month.free,
        budgetUnknown = month.unknown,
        overspent = month.overspent,
        // A download sits at «Замовлено» until it is filed, and it was being counted
        // as a parcel on the road. Nothing of it is on any road.
        parcelsMoving = orders.count {
            !it.digital && (it.status == ORDERED || it.status == IN_TRANSIT)
        },
        parcelsAtBranch = orders.count { it.status == AT_BRANCH },
        parcelsDone = orders.count { it.status == RECEIVED },
        monthsToFundAll = when {
            remaining <= 0.0 -> 0
            !month.unknown && forWishes > 0.0 -> savingsPlan(goals, saved, forWishes).months
            // No income entered, or the month does not fit: there is no rate to divide by.
            else -> null
        },
        plannedMonthly = planned,
        plansOverBudget = money.over,
        // Each wish plans in isolation, so their sum can quietly exceed the month.
        // Nothing else in the app is in a position to notice that.
        plansConflict = money.conflict,
        lifeCost = money.month.life,
        fundsCovered = money.month.covered
    )
}

/**
 * The three figures the home screen widget shows, already worded.
 *
 * The widget runs in the launcher's process with no theme, no resources of its
 * own and no room to make decisions, so every judgement — what counts as the
 * next payment, whether the month fits, how to decline "посилка" — is made here
 * and tested here. What crosses over is finished text.
 */
data class WidgetSummary(
    /** "Оренда" or "3 платежі", and a plain sentence when nothing is scheduled. */
    val paymentName: String,
    /** "12 вересня". Empty when there is no payment to date. */
    val paymentDate: String,
    /** "завтра" */
    val paymentCountdown: String,
    /** "11 200 ₴" */
    val paymentAmount: String,
    val hasPayment: Boolean,
    val freeCash: String,
    val parcels: String
)

fun widgetSummary(
    pays: List<Pay>,
    orders: List<Order>,
    income: Double,
    usdSellRate: Double,
    today: LocalDate,
    /**
     * What has been ticked off. The pill and the digest leave a paid bill out
     * through [stillOwing], and the widget kept announcing it — three surfaces
     * that must agree, and one of them did not.
     */
    marks: List<PaidMark> = emptyList(),
    /**
     * The month as MoneyPlan.kt works it out — «На життя» and the funds included —
     * so the widget says the same «Вільно» as Огляд. Absent, income less payments.
     */
    month: Budget? = null
): WidgetSummary {
    val next = nextPayment(stillOwing(pays, marks, today), today, usdSellRate)
    @Suppress("NAME_SHADOWING")
    val month = month ?: budget(income, monthlyTotal(pays, usdSellRate, today))
    return WidgetSummary(
        paymentName = next?.let { dueSummary(it.items) } ?: "Платежів не заплановано",
        paymentDate = next?.let { dayMonth(it.date) }.orEmpty(),
        paymentCountdown = next?.let { dueLabel(it.daysAway) }.orEmpty(),
        paymentAmount = next?.let { totalLabel(it.total) }.orEmpty(),
        hasPayment = next != null,
        freeCash = freeCashLine(month),
        // A filed purchase is not waiting anywhere.
        parcels = branchLine(orders.count { it.status == AT_BRANCH && it.archivedDay == 0L })
    )
}

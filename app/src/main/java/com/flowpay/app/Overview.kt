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
    val movement: PriceMovement
)

fun overview(
    wishes: List<Wish>,
    pays: List<Pay>,
    orders: List<Order>,
    income: Double,
    usdSellRate: Double,
    /** Needed only to know which subscriptions are still inside a free trial. */
    today: LocalDate
): Overview {
    val goals = wishes.sumOf { wishGoal(it) }
    val saved = wishes.sumOf { it.saved.coerceAtLeast(0.0) }
    val expenses = monthlyTotal(pays, usdSellRate, today)
    val month = budget(income, expenses)
    val remaining = (goals - saved).coerceAtLeast(0.0)
    val planned = wishes.sumOf { it.monthlyPlan.coerceAtLeast(0.0) }
    return Overview(
        movement = priceMovement(wishes),
        wishCount = wishes.size,
        wishTotal = goals,
        savedTotal = saved,
        savedProgress = if (goals > 0) (saved / goals).coerceIn(0.0, 1.0).toFloat() else 0f,
        readyCount = wishes.count { wishGoal(it) > 0 && it.saved >= wishGoal(it) },
        income = month.income,
        monthlyExpenses = expenses.total,
        freeCash = month.free,
        budgetUnknown = month.unknown,
        overspent = month.overspent,
        parcelsMoving = orders.count { it.status == ORDERED || it.status == IN_TRANSIT },
        parcelsAtBranch = orders.count { it.status == AT_BRANCH },
        parcelsDone = orders.count { it.status == RECEIVED },
        monthsToFundAll = when {
            remaining <= 0.0 -> 0
            month.free > 0.0 -> savingsPlan(goals, saved, month.free).months
            // No income entered, or the month does not fit: there is no rate to divide by.
            else -> null
        },
        plannedMonthly = planned,
        plansOverBudget = (planned - month.free).coerceAtLeast(0.0),
        // Each wish plans in isolation, so their sum can quietly exceed the month.
        // Nothing else in the app is in a position to notice that.
        plansConflict = !month.unknown && planned > 0.0 && planned > month.free
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
    today: LocalDate
): WidgetSummary {
    val next = nextPayment(pays, today, usdSellRate)
    val month = budget(income, monthlyTotal(pays, usdSellRate, today))
    return WidgetSummary(
        paymentName = next?.let { dueSummary(it.items) } ?: "Платежів не заплановано",
        paymentDate = next?.let { dayMonth(it.date) }.orEmpty(),
        paymentCountdown = next?.let { dueLabel(it.daysAway) }.orEmpty(),
        paymentAmount = next?.let { totalLabel(it.total) }.orEmpty(),
        hasPayment = next != null,
        freeCash = freeCashLine(month),
        parcels = branchLine(orders.count { it.status == AT_BRANCH })
    )
}

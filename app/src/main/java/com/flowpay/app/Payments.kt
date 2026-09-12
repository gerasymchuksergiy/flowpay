package com.flowpay.app

import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale

/**
 * Recurring expenses are not all in one currency. Rent is often quoted and paid in
 * dollars while everything else is in hryvnia, so the monthly total has to convert
 * before it can be a single useful number.
 */

const val UAH = "UAH"
const val USD = "USD"

data class MonthlyTotal(
    /** Sum of the entries already in hryvnia. */
    val uah: Double,
    /** Sum of the dollar entries, still in dollars. */
    val usd: Double,
    /** Those dollars in hryvnia. Zero when there is no rate to convert them with. */
    val usdInUah: Double,
    /** What the month actually costs in hryvnia, as far as it can be known. */
    val total: Double,
    val hasUsd: Boolean,
    /** There are dollar entries but no rate, so [total] is short by [usd] dollars. */
    val rateMissing: Boolean
)

// ------------------------------------------------------------- the free month

/**
 * A subscription can start free, and the first real charge is the one nobody
 * remembers.
 *
 * The free month is not a discount, it is a commitment with its price hidden a
 * month away — which is why an app that shows the charge from day one is wrong in
 * one direction and an app that hides the subscription entirely is wrong in the
 * other. So a trial expense is on the list, on the timeline and in the annual
 * figure from the day it is added, and takes nothing at all until this day comes.
 */

/**
 * Whether the trial is still running on [day].
 *
 * The end day itself is a charging day, not one more free one: a trial "до 3
 * жовтня" that stayed free through the third would put the first real charge a
 * day after the date the user was shown.
 */
fun onTrial(pay: Pay, day: Long): Boolean = pay.trialEnd > day

/** What this expense actually takes on [day]. Nothing, while a trial is running. */
fun chargedAmount(pay: Pay, day: Long): Double = if (onTrial(pay, day)) 0.0 else pay.amount

/** The expenses that are still free today. */
fun trialsRunning(items: List<Pay>, today: LocalDate): List<Pay> =
    items.filter { onTrial(it, today.toEpochDay()) }

/**
 * "Перше списання 12 жовтня" — the date a free period actually commits to.
 *
 * Said in the editor, while the trial is being set, because the whole failure this
 * feature addresses is signing up in September and being charged in October with
 * nothing in between having mentioned October.
 */
fun firstChargeNote(day: Int, trialEnd: Long, today: LocalDate): String? {
    if (trialEnd <= 0L) return null
    val charge = nextCharge(Pay("", 0.0, day, trialEnd = trialEnd), today)
    return "Перше списання ${dayMonth(charge)}"
}

/** "безкоштовно до 3 жовтня", or null once the trial has run out. */
fun trialLabel(pay: Pay, today: LocalDate): String? =
    if (onTrial(pay, today.toEpochDay())) {
        "безкоштовно до ${dayMonth(LocalDate.ofEpochDay(pay.trialEnd))}"
    } else {
        null
    }

/**
 * Adds up a month.
 *
 * Takes the rate at which the bank sells dollars, because that is the side of the
 * spread you pay when you have to find dollars for the rent. Using the buy rate
 * would quietly understate every month.
 *
 * [today] is required rather than defaulted, and that is the point: a trial counts
 * as nought here, so every figure built on this one — the free cash, the budget,
 * the widget, the tile, the overview — needs to know what day it is. A default
 * would have let each of those keep compiling while quietly overstating the month
 * by the price of a subscription that is not being charged yet.
 */
fun monthlyTotal(items: List<Pay>, usdSellRate: Double, today: LocalDate): MonthlyTotal {
    val day = today.toEpochDay()
    val billed = items.map { it.copy(amount = chargedAmount(it, day)) }
    val uah = billed.filter { it.currency != USD }.sumOf { it.amount }
    val usd = billed.filter { it.currency == USD }.sumOf { it.amount }
    val rate = usdSellRate.coerceAtLeast(0.0)
    val hasUsd = usd > 0.0
    val convertible = hasUsd && rate > 0.0
    val usdInUah = if (convertible) usd * rate else 0.0
    return MonthlyTotal(
        uah = uah,
        usd = usd,
        usdInUah = usdInUah,
        total = uah + usdInUah,
        hasUsd = hasUsd,
        rateMissing = hasUsd && rate <= 0.0
    )
}

/** A recurring cost is charged this many times a year. */
const val MONTHS_IN_YEAR = 12

/** What one recurring expense costs in a year, in the currency it was entered in. */
fun yearlyCost(pay: Pay): Double = pay.amount * MONTHS_IN_YEAR

/**
 * A year of the same standing costs.
 *
 * The figure that changes minds: 400 ₴ a month is a rounding error and 4 800 ₴ a
 * year is a decision. Built from [monthlyTotal] so a missing rate stays missing
 * rather than being quietly multiplied into a smaller number twelve times over.
 */
fun yearlyTotal(items: List<Pay>, usdSellRate: Double, today: LocalDate): MonthlyTotal {
    val monthly = monthlyTotal(items, usdSellRate, today)
    return monthly.copy(
        uah = monthly.uah * MONTHS_IN_YEAR,
        usd = monthly.usd * MONTHS_IN_YEAR,
        usdInUah = monthly.usdInUah * MONTHS_IN_YEAR,
        total = monthly.total * MONTHS_IN_YEAR
    )
}

/** "36 000 ₴ на рік" — one expense's annual cost, in its own currency. */
fun annualLabel(pay: Pay): String = "${amountLabel(yearlyCost(pay), pay.currency)} на рік"

/**
 * A year of these expenses once every running trial has ended.
 *
 * [yearlyTotal] counts a trial as the nought it currently is, which is the truth
 * about this month and a lie about the year: a free month is a commitment to the
 * eleven paid ones behind it. This is the figure the screen puts underneath, and
 * the reason [yearlyCost] and [annualLabel] never look at the trial either — one
 * expense's annual cost is what signing up commits you to, not what it charged
 * this morning.
 */
fun yearlyCommitment(items: List<Pay>, usdSellRate: Double, today: LocalDate): MonthlyTotal =
    yearlyTotal(items.map { it.copy(trialEnd = 0L) }, usdSellRate, today)

// ------------------------------------------------------- what it used to cost

/**
 * A subscription's price is not a fact, it is a series.
 *
 * Netflix goes from 269 to 309 and an expense that remembers one number simply
 * loses the 269. Nothing here fetches anything — a recurring charge has no page to
 * read — so the whole history is built from the one moment the app can observe: a
 * person opening the expense and typing a different figure.
 */

/**
 * The recorded amounts, ending at what the expense costs now.
 *
 * The closing point is added only when the two disagree, which they can after a
 * restore from a backup written before any of this. Without it the sheet would
 * quote a "current" figure from the history that the amount beside it contradicts.
 */
fun amountTrail(pay: Pay): List<PricePoint> {
    val recorded = pay.amounts.filter { it.price > 0.0 }
    if (pay.amount <= 0.0 || recorded.lastOrNull()?.price == pay.amount) return recorded
    return recorded + PricePoint(pay.amount, 0L)
}

/**
 * Records a new amount, and the one it replaces.
 *
 * An expense that has never been edited carries no history, so the figure being
 * replaced is written in first. Without that seed the very first raise would store
 * the new price on its own and the "було" this exists to show would be gone — the
 * exact loss the feature is here to stop, reintroduced at the one moment it
 * matters. The seed is dated day zero, which [PricePoint] already reads as "seen,
 * but not dated": the app genuinely does not know when the old price started.
 *
 * An unchanged amount writes nothing. Opening the sheet and saving it again is not
 * a price change, and recording one would put a 0% line in tomorrow's digest.
 */
fun withAmount(pay: Pay, amount: Double, today: Long): Pay {
    if (amount <= 0.0 || amount == pay.amount) return pay
    val seeded = pay.amounts.ifEmpty {
        listOfNotNull(PricePoint(pay.amount, 0L).takeIf { pay.amount > 0.0 })
    }
    return pay.copy(amount = amount, amounts = appendPrice(seeded, amount, today))
}

/**
 * Applies an edited amount, keeping the history only where it still describes the
 * same thing.
 *
 * Switching an expense from hryvnia to dollars replaces 309 with 9, and recording
 * that as a 97% fall would be the history's first and worst lie — one the digest
 * would then announce. A currency change starts the history again from the new
 * figure, because in the new currency that is genuinely all the app has ever seen.
 */
fun edited(pay: Pay, amount: Double, currency: String, today: Long): Pay {
    if (amount <= 0.0) return pay
    // Blank reads as hryvnia, the same way [toHryvnia] reads an unlabelled price.
    // The two have to agree on what "the currency changed" means, or an expense
    // restored from old storage with an empty code would look like a switch and
    // lose its history to a change that never happened.
    val code = currency.ifBlank { UAH }
    val was = pay.currency.ifBlank { UAH }
    if (code == was) return withAmount(pay.copy(currency = code), amount, today)
    return pay.copy(amount = amount, currency = code, amounts = listOf(PricePoint(amount, today)))
}

/** A move in what an expense costs, from the figure before it to the one after. */
data class AmountChange(
    val from: Double,
    val to: Double,
    /** Epoch day the new figure took effect. Zero when it was never dated. */
    val day: Long,
    val percent: Double
) {
    val raised: Boolean get() = to > from
}

/** The latest move, or null while the expense has only ever cost one thing. */
fun lastAmountChange(pay: Pay): AmountChange? {
    val trail = amountTrail(pay)
    if (trail.size < 2) return null
    val from = trail[trail.size - 2].price
    val to = trail.last()
    if (from <= 0.0 || from == to.price) return null
    return AmountChange(from, to.price, to.day, (to.price - from) / from * 100)
}

/**
 * What the expense cost on [day], as far as anything recorded knows.
 *
 * The last figure written on or before that day. With nothing written that early
 * the oldest figure there is stands in, because a point is only ever written when
 * the amount moves: an expense with no point before the day had not moved by then.
 */
fun amountOn(pay: Pay, day: Long): Double {
    val recorded = pay.amounts.filter { it.price > 0.0 }
    return recorded.lastOrNull { it.day in 1..day }?.price
        ?: recorded.firstOrNull()?.price
        ?: pay.amount
}

/** "Було 269 ₴ · +15% з 12 вересня" — the line under the amount on the expense's own sheet. */
fun amountHistoryNote(pay: Pay): String? {
    val change = lastAmountChange(pay) ?: return null
    // No verb. A name typed by the user has no gender the app can know, and
    // "Інтернет подорожчала" is how an app comes out reading as machine-translated.
    val since = change.day.takeIf { it > 0L }
        ?.let { " з ${dayMonth(LocalDate.ofEpochDay(it))}" }
        .orEmpty()
    return "Було ${amountLabel(change.from, pay.currency)} · ${signedPercent(change.percent, 0)}$since"
}

/** How many recorded amounts the sheet lists before it stops being a history and starts being a wall. */
const val TRAIL_LINES = 6

/** The history as rows, newest first: "309 ₴ · з 12 вересня". */
fun amountTrailLines(pay: Pay, limit: Int = TRAIL_LINES): List<String> {
    val trail = amountTrail(pay)
    if (trail.size < 2) return emptyList()
    return trail.reversed().take(limit.coerceAtLeast(1)).map { point ->
        val since = point.day.takeIf { it > 0L }
            ?.let { "з ${dayMonth(LocalDate.ofEpochDay(it))}" }
            // The oldest figure predates the app watching, so it gets no date
            // rather than a guessed one.
            ?: "раніше"
        "${amountLabel(point.price, pay.currency)} · $since"
    }
}

/** "Netflix 269 → 309 ₴, +15%" — one raise, said in the length a notification allows. */
fun amountChangeLine(pay: Pay, change: AmountChange): String =
    "${pay.name} ${bareAmount(change.from)} → ${amountLabel(change.to, pay.currency)}, " +
        signedPercent(change.percent, 0)

/**
 * A year of the same standing costs, then and now.
 *
 * The figure a subscription tracker exists to produce. Each raise on its own is a
 * few tens of hryvnia and reads as nothing; the same raises added up over twelve
 * months are the number that gets a subscription cancelled.
 */
data class YearlyShift(
    /** A year of these expenses at the amounts they carried a year ago. */
    val before: MonthlyTotal,
    /** A year of them at what they cost now. */
    val now: MonthlyTotal,
    /** Now minus then, in hryvnia. Negative on the rare year something got cheaper. */
    val difference: Double,
    val percent: Double,
    /** Nothing recorded moved, so the two figures are one figure. */
    val unchanged: Boolean
)

/**
 * Compares the list against itself a year ago.
 *
 * The expenses are the ones standing now, restated at their old amounts — not the
 * list as it stood last year, which nothing on the phone records. That keeps the
 * comparison about prices rather than about what was added or deleted, which is
 * the only version of the question the app can answer honestly.
 */
fun yearlyShift(items: List<Pay>, usdSellRate: Double, today: LocalDate): YearlyShift {
    val yearAgo = today.minusYears(1).toEpochDay()
    // A trial is left in place on both sides, so it counts as nought in each and
    // the comparison stays about prices. Zeroing it on one side alone would make
    // a subscription that is free this month read as having vanished since last year.
    val before = yearlyTotal(
        items.map { it.copy(amount = amountOn(it, yearAgo)) },
        usdSellRate,
        today
    )
    val now = yearlyTotal(items, usdSellRate, today)
    val difference = now.total - before.total
    return YearlyShift(
        before = before,
        now = now,
        difference = difference,
        percent = if (before.total > 0.0) difference / before.total * 100 else 0.0,
        // A hundredth of a hryvnia over a year is rounding, not a raise.
        unchanged = kotlin.math.abs(difference) < 0.01
    )
}

/** The honest yearly line, or null on a year in which nothing moved. */
fun yearlyShiftNote(shift: YearlyShift): String? {
    if (shift.unchanged) return null
    return "Торік ${money(shift.before.total)} на рік, зараз ${money(shift.now.total)} " +
        "(${signedPercent(shift.percent, 0)})"
}

data class Budget(
    val income: Double,
    val expenses: Double,
    /** Income minus expenses. Negative when the month does not fit. */
    val free: Double,
    val overspent: Boolean,
    /** No income has been entered, so nothing here means anything yet. */
    val unknown: Boolean
)

/**
 * What is left each month once the standing costs are paid.
 *
 * This is the number that turns a wishlist into a plan: it is the most that can
 * realistically go towards a wish without touching anything else. Left unclamped
 * on purpose, because a month that does not fit is worth saying out loud.
 */
fun budget(income: Double, expenses: MonthlyTotal): Budget {
    val safeIncome = income.coerceAtLeast(0.0)
    val free = safeIncome - expenses.total
    return Budget(
        income = safeIncome,
        expenses = expenses.total,
        free = free,
        overspent = safeIncome > 0.0 && free < 0.0,
        unknown = safeIncome <= 0.0
    )
}

/**
 * The day of the month a payment actually lands on.
 *
 * A payment set for the 31st has to happen on the 28th in February. Clamping to
 * the month's length is what a bank does, and without it a reminder for the 31st
 * would silently never fire in four months of the year.
 */
fun effectivePaymentDay(day: Int, monthLength: Int): Int = day.coerceIn(1, monthLength)

/** Payments falling due on exactly this date, whether or not money moves. */
fun paymentsDueOn(items: List<Pay>, date: LocalDate): List<Pay> {
    val monthLength = date.lengthOfMonth()
    return items.filter { effectivePaymentDay(it.day, monthLength) == date.dayOfMonth }
}

/**
 * Payments that actually take money on [date].
 *
 * The same list minus whatever is still free that day. The distinction is the
 * whole of the trial feature: a renewal during a trial is a date on the calendar
 * and is not a debit, and the two things are wanted in different places.
 */
fun chargedOn(items: List<Pay>, date: LocalDate): List<Pay> =
    paymentsDueOn(items, date).filterNot { onTrial(it, date.toEpochDay()) }

/**
 * The next date money actually leaves, and what leaves with it.
 *
 * A monthly total is a figure you read; this is a figure you act on, which is
 * why it belongs at the top of the screen instead.
 */
data class NextPayment(
    val date: LocalDate,
    /** Nought when something is due today. */
    val daysAway: Int,
    val items: List<Pay>,
    /** Everything due that day, so a missing rate stays visible instead of reading as zero. */
    val total: MonthlyTotal
)

/**
 * How far ahead the search for the next real charge looks.
 *
 * A month and a day used to be enough to find the next occurrence of any day
 * number, including the 31st landing on the 30th of a short month. A trial breaks
 * that: a subscription free until March has no charge in the next thirty-one days,
 * and stopping there would report it as no charge at all — the free-month
 * blindness this exists to fix, reintroduced one function along. A year and a
 * month covers the longest trial anyone offers and still terminates.
 */
private const val CHARGE_SEARCH_DAYS = 396

fun nextPayment(items: List<Pay>, today: LocalDate, usdSellRate: Double): NextPayment? {
    if (items.isEmpty()) return null
    for (offset in 0..CHARGE_SEARCH_DAYS) {
        val date = today.plusDays(offset.toLong())
        // Charged rather than merely due: this panel says when money next leaves,
        // and during a trial the next renewal is not a day money leaves.
        val due = chargedOn(items, date)
        if (due.isNotEmpty()) {
            return NextPayment(date, offset, due, monthlyTotal(due, usdSellRate, date))
        }
    }
    return null
}

/** Ukrainian plural for how many payments fall together. */
fun paymentsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "платежів"
        last == 1 -> "платіж"
        last in 2..4 -> "платежі"
        else -> "платежів"
    }
    return "$count $word"
}

/**
 * What falls on a date, said in a line rather than a paragraph.
 *
 * Listing six names wrapped the panel to three lines and repeated what the
 * timeline shows directly underneath, so past a pair the count says it better.
 */
fun dueSummary(items: List<Pay>): String = when {
    items.isEmpty() -> ""
    items.size <= 2 -> items.joinToString(", ") { it.name }
    else -> paymentsLabel(items.size)
}

/** "сьогодні", "завтра", "через 3 дні". */
fun dueLabel(daysAway: Int): String = when (daysAway) {
    0 -> "сьогодні"
    1 -> "завтра"
    else -> "через ${daysLabel(daysAway)}"
}

/**
 * Expenses that fall on the same date, soonest first.
 *
 * Positions rather than the expenses themselves, because two identical entries
 * are equal as values and deleting "one of them" has to mean a definite one.
 */
data class PaymentGroup(val date: LocalDate, val positions: List<Int>)

fun paymentGroups(items: List<Pay>, today: LocalDate): List<PaymentGroup> =
    items.indices
        .groupBy { nextDateFor(items[it], today) }
        .toSortedMap()
        .map { (date, positions) -> PaymentGroup(date, positions) }

/** The next time this expense comes round, this month or next. */
private fun nextDateFor(pay: Pay, today: LocalDate): LocalDate {
    val thisMonth = effectivePaymentDay(pay.day, today.lengthOfMonth())
    if (thisMonth >= today.dayOfMonth) return today.withDayOfMonth(thisMonth)
    val next = today.plusMonths(1)
    return next.withDayOfMonth(effectivePaymentDay(pay.day, next.lengthOfMonth()))
}

/**
 * The next date this expense actually takes money.
 *
 * During a trial that is not the next time the day comes round — it is the first
 * time it comes round once the free period has run out, which can be months away.
 * Warning someone about the free charge is warning them about nothing, and it
 * spends the one notification they were going to read on the wrong date.
 *
 * No loop: the day recurs monthly, so the first occurrence on or after the later
 * of today and the trial's end is the answer outright.
 */
fun nextCharge(pay: Pay, today: LocalDate): LocalDate {
    val from = if (pay.trialEnd > 0L) {
        maxOf(today, LocalDate.ofEpochDay(pay.trialEnd))
    } else {
        today
    }
    return nextDateFor(pay, from)
}

/**
 * Days from today, 0 being today, that carry a payment.
 *
 * The strip used to draw the calendar month while the list below it was already
 * showing next month's dates, so the two disagreed about what they described.
 * Counting forward from today makes them the same span.
 */
fun paymentOffsets(items: List<Pay>, today: LocalDate, days: Int = 30): Set<Int> =
    (0 until days)
        // The strip is captioned as debits, so a renewal that is still free does
        // not get a mark. The timeline below it still lists the expense, because
        // that is a list of what is standing, not of what is taken.
        .filter { chargedOn(items, today.plusDays(it.toLong())).isNotEmpty() }
        .toSet()

// ------------------------------------------------------------ advance warning

/**
 * Days of notice an expense gets when it does not say otherwise.
 *
 * One, not nought, because that is exactly what every expense already had: the
 * reminder used to look at tomorrow and only at tomorrow. Reading a missing field
 * as nought would have silently moved every existing expense on the phone to a
 * reminder that arrives on the charge date, which is the thing this feature exists
 * to stop.
 */
const val DEFAULT_WARN_DAYS = 1

/**
 * The notice periods the editor offers.
 *
 * A day is enough to move money; a week is what a subscription needs, because
 * cancelling before the charge is the only way not to pay it. Nought is kept for
 * the bills you cannot avoid and only need to remember on the day.
 */
val WARN_CHOICES = listOf(0, 1, 3, 7)

/** How a notice period reads on a chip. */
fun warnLabel(days: Int): String = when {
    days <= 0 -> "У день оплати"
    days == 1 -> "За день"
    else -> "За ${daysLabel(days)}"
}

/** An expense whose warning window has opened, and how long is left before it is charged. */
data class DueReminder(
    val pay: Pay,
    /** Days until the money has to move, which is not always the day it is charged. */
    val daysAway: Int,
    /**
     * The charge date, when the reminder was pulled earlier off a day off.
     *
     * Null whenever nothing moved, so the note explaining the move only appears
     * where the date the user wrote down differs from the one being talked about.
     */
    val movedFrom: LocalDate? = null
)

/**
 * Everything worth saying on [today], soonest first.
 *
 * The window is per expense: a subscription set to seven days starts appearing a
 * week out, while the electricity bill set to nought appears only on the morning
 * it is taken. Everything found here goes into one notification, so a long notice
 * period costs a line in a daily message rather than a week of separate alarms.
 *
 * [holidays] is allowed to be empty and usually is on the first run of a year.
 * With it, a charge falling on a weekend or a public holiday counts from the last
 * working day before it, because that is the day the money actually has to move.
 */
fun remindersDue(
    items: List<Pay>,
    today: LocalDate,
    holidays: Set<Long> = emptySet()
): List<DueReminder> =
    items.mapNotNull { pay ->
        // The first REAL charge. A subscription free until October renews in
        // September taking nothing, and a reminder three days before that is a
        // reminder about a charge of nought.
        val charged = nextCharge(pay, today)
        val day = paymentDay(charged, holidays)
        // Clamped at nought: a working day already behind us means "today", and
        // "через -1 день" is not a thing to put in front of a person.
        val daysAway = java.time.temporal.ChronoUnit
            .DAYS.between(today, day.payOn).toInt().coerceAtLeast(0)
        DueReminder(pay, daysAway, charged.takeIf { day.moved })
            .takeIf { daysAway <= pay.warnDays.coerceAtLeast(0) }
    }.sortedBy { it.daysAway }

/** The line a daily reminder leads with. */
fun reminderTitle(reminders: List<DueReminder>): String {
    val days = reminders.map { it.daysAway }.distinct()
    return when {
        reminders.isEmpty() -> ""
        reminders.size == 1 -> "Оплата ${dueLabel(reminders.first().daysAway)}"
        days.size == 1 -> "${paymentsLabel(reminders.size)} ${dueLabel(days.first())}"
        else -> "Найближчі платежі"
    }
}

/**
 * The body of the reminder.
 *
 * When everything falls on the same day the title has already said when, so
 * repeating it under each name would be noise. When the days differ, each name has
 * to carry its own, or a week's notice and today's charge read as the same thing.
 */
fun reminderText(reminders: List<DueReminder>): String {
    val mixed = reminders.map { it.daysAway }.distinct().size > 1
    return reminders.joinToString(if (mixed) " · " else ", ") { reminder ->
        val amount = amountLabel(reminder.pay.amount, reminder.pay.currency)
        if (mixed) {
            "${reminder.pay.name} $amount — ${dueLabel(reminder.daysAway)}"
        } else {
            "${reminder.pay.name} $amount"
        }
    }
}

internal val UK = Locale("uk", "UA")

/**
 * A number in the app's own language, whatever language the phone is set to.
 *
 * `"%.1f".format(x)` reads the phone's default locale, so on an English phone
 * the app wrote "3.2%" next to "2 199,50 ₴" — two conventions inside one
 * sentence. Every word in this app is Ukrainian, so every figure is too.
 */
fun figure(value: Double, decimals: Int = 1): String =
    String.format(UK, "%.${decimals}f", value)

/**
 * Two decimal places at most, and none when the amount is whole.
 *
 * The default number format allows three, which turned a converted rent into
 * "11 200,225 ₴" — a figure carrying a tenth of a kopeck.
 */
private fun amountFormat(): NumberFormat = NumberFormat.getNumberInstance(UK).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 0
}

/**
 * The figure alone, with no currency after it.
 *
 * For the left-hand side of "269 → 309 ₴", where naming the currency twice in
 * four words is the sort of repetition that makes a short line hard to read.
 */
fun bareAmount(value: Double): String = amountFormat().format(value)

/** "2 203,24 ₴" */
fun money(value: Double): String = bareAmount(value) + " ₴"

/** "250 $" */
fun dollars(value: Double): String = bareAmount(value) + " $"

/**
 * A converted figure, rounded to whole hryvnia.
 *
 * It is an estimate at today's rate, so kopecks would claim a precision the
 * number does not have.
 */
fun approxMoney(value: Double): String = money(kotlin.math.round(value))

/**
 * An amount shown in whichever currency it was entered in.
 *
 * The third branch is for money the app can read but cannot convert. It prints
 * the code rather than a symbol on purpose: a figure the app has no rate for must
 * not be dressed up to look like one it does.
 */
fun amountLabel(value: Double, currency: String): String = when {
    currency == USD -> dollars(value)
    currency.isBlank() || currency == UAH -> money(value)
    else -> "${bareAmount(value)} $currency"
}

// ------------------------------------------------- a price that is not in hryvnia

/**
 * A price as a shop stated it, and what that is worth here.
 *
 * The whole of this exists because [Offer] used to be a bare number: a page
 * priced `$59.99` was recorded as `59,99 ₴` and the app cheerfully announced a
 * ninety-nine per cent drop. Both halves are kept rather than just the converted
 * figure, so the screen can show its working — and so that a later reading can
 * tell a real price cut apart from the hryvnia moving, which is the same
 * reasoning [inDollars] applies to the history.
 */
data class PriceInUah(
    /** Hryvnia. Zero when there is no rate to get there with. */
    val uah: Double,
    /** The figure the shop actually printed. */
    val amount: Double,
    /** The ISO code of [amount]. A page that named none is read as hryvnia. */
    val currency: String,
    /** Hryvnia per unit of [currency]. One for hryvnia itself, zero when unconvertible. */
    val rate: Double,
    /** The shop priced it in money the app has no rate for, so [uah] means nothing. */
    val noRate: Boolean
)

/**
 * Converts a stated price into hryvnia.
 *
 * A page that states no currency is read as hryvnia, because a Ukrainian shop
 * rarely bothers to say so and guessing otherwise would break every shop that
 * works today. A currency the app has no rate for gets no number at all: the app
 * fetches one pair and one only, and inventing a figure for the rest would be the
 * same lie as reading dollars as hryvnia, only harder to spot.
 *
 * Dollars convert at the rate the bank sells at, for the reason [monthlyTotal]
 * uses it too — that is the side of the spread you pay when you have to find
 * dollars for the thing.
 */
fun toHryvnia(price: Double, currency: String, rate: FxRate): PriceInUah {
    val code = currency.ifBlank { UAH }
    if (code == UAH) return PriceInUah(price, price, UAH, 1.0, noRate = false)
    val sell = rate.sell
    if (code == USD && sell > 0.0) {
        return PriceInUah(price * sell, price, USD, sell, noRate = false)
    }
    return PriceInUah(0.0, price, code, 0.0, noRate = true)
}

/**
 * The line under a converted price, showing its working.
 *
 * Null for hryvnia, which is nearly every shop: a caption saying a hryvnia price
 * is worth that many hryvnia is noise, and a note that always appears stops being
 * read at all.
 */
fun convertedPriceLine(converted: PriceInUah): String? = when {
    converted.currency == UAH -> null
    converted.noRate -> "Ціна в ${converted.currency} — курсу до гривні немає"
    else -> "${amountLabel(converted.amount, converted.currency)} · " +
        "${approxMoney(converted.uah)} за курсом ${rateFigure(converted.rate)}"
}

/**
 * How old the rate behind a converted price is, said out loud once it matters.
 *
 * A converted figure is only ever as current as the rate under it, and the rate
 * is fetched by the same background pass that reads the prices — so a phone that
 * has been offline for a week converts this morning's dollar price at last week's
 * hryvnia. Silent for the first couple of days, because a rate that old changes
 * the figure by less than the rounding already does.
 */
fun staleRateNote(converted: PriceInUah, rateDay: Long, today: Long): String? {
    if (converted.currency == UAH || converted.noRate) return null
    if (rateDay <= 0L || today <= rateDay) return null
    val age = (today - rateDay).toInt()
    return if (age >= STALE_RATE_DAYS) "Курс оновлювався ${daysLabel(age)} тому" else null
}

/** Below this a rate is old enough to mention but not old enough to matter. */
const val STALE_RATE_DAYS = 3

/**
 * A rate, always with both decimals.
 *
 * "41,2" reads as a truncated number rather than a rate, and the second place is
 * worth about forty kopecks on a hundred dollars.
 */
fun rateFigure(value: Double): String =
    NumberFormat.getNumberInstance(UK).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 2
    }.format(value)

/**
 * Where the figure came from, said plainly.
 *
 * The official rate and a bank's rate are different numbers — usually by the best
 * part of a hryvnia — so a rate that reaches the screen without its source invites
 * reading one as the other. This is why the label is never omitted, not even when
 * the fallback worked perfectly.
 */
fun rateSourceLabel(rate: FxRate): String = when {
    rate.sell <= 0.0 -> ""
    rate.source == SOURCE_NBU && rate.date.isNotBlank() -> "Офіційний курс НБУ на ${rate.date}"
    rate.source == SOURCE_NBU -> "Офіційний курс НБУ"
    else -> "Ринковий курс Monobank"
}

/** The rate itself: a spread where there is one, a single official figure where there is not. */
fun rateHeadline(rate: FxRate): String = when {
    rate.sell <= 0.0 -> "Курс ще не завантажено"
    rate.source == SOURCE_NBU -> "${rateFigure(rate.sell)} ₴ за долар"
    else -> "Купівля ${rateFigure(rate.buy)} · продаж ${rateFigure(rate.sell)}"
}

/**
 * A set of payments as one figure, without ever pretending the dollars converted.
 *
 * Somewhere with no room for a caption — a widget, a notification — the missing
 * rate has to be visible inside the figure itself, or a rent quoted in dollars
 * simply vanishes from the total.
 */
fun totalLabel(total: MonthlyTotal): String = when {
    total.rateMissing && total.uah > 0.0 -> "${money(total.uah)} + ${dollars(total.usd)}"
    total.rateMissing -> dollars(total.usd)
    total.hasUsd -> approxMoney(total.total)
    else -> money(total.total)
}

/** What the month has left, in one line, including when the answer is "nothing". */
fun freeCashLine(month: Budget): String = when {
    month.unknown -> "Дохід не вказано"
    month.overspent -> "Бракує ${money(-month.free)}"
    else -> "Вільно ${money(month.free)}"
}

// --------------------------------------------------------- what was actually paid

/**
 * Everything above is a plan. Nothing here was ever a record of what happened, so
 * "скільки пішло в серпні" had no answer at all — the app could only say what
 * August was supposed to cost, which is a different number the moment one bill is
 * skipped, paid late, or paid twice.
 */

/**
 * A month as a sortable key, "2026-08".
 *
 * A month rather than a date, because paying the rent on the 3rd and the internet
 * on the 28th are both August. Zero-padded so sorting the keys as text sorts them
 * as time, which is the whole of the ordering the overview needs.
 */
// A storage key, not a label: Locale.ROOT, because a phone set to a locale with
// its own numerals would otherwise write a key nothing can read back.
fun monthKey(date: LocalDate): String =
    String.format(Locale.ROOT, "%04d-%02d", date.year, date.monthValue)

/** The first day of the month a key names, or null when the string is not a key. */
fun monthKeyDate(key: String): LocalDate? = runCatching {
    val year = key.substringBefore('-').toInt()
    val month = key.substringAfter('-').toInt()
    LocalDate.of(year, month, 1)
}.getOrNull()

/**
 * Month names in the nominative.
 *
 * [formatDate] carries a genitive set for use inside a sentence — "10 серпня". A
 * heading is not inside a sentence, and "Серпня 2026" as a title is wrong in the
 * way that makes an app read as translated.
 */
private val MONTHS_NOMINATIVE = listOf(
    "Січень", "Лютий", "Березень", "Квітень", "Травень", "Червень",
    "Липень", "Серпень", "Вересень", "Жовтень", "Листопад", "Грудень"
)

/** "Серпень 2026", the form a month takes as a heading. */
fun monthTitle(key: String): String {
    val date = monthKeyDate(key) ?: return key
    return "${MONTHS_NOMINATIVE[date.monthValue - 1]} ${date.year}"
}

/**
 * One standing expense, marked as paid for one month.
 *
 * The amount is copied in rather than read back off the expense, because the
 * record has to stay true after the rent goes up: what August cost is not what
 * September costs, and an expense edited in October must not rewrite August.
 *
 * Identified by name, since that is all a [Pay] carries. Two expenses sharing a
 * name are therefore one row here — rare enough to accept, and the alternative is
 * an id on every existing expense that nothing on the phone would have.
 */
data class PaidMark(
    val name: String,
    /** "2026-08". */
    val month: String,
    val amount: Double,
    val currency: String = UAH
)

/** Months of marks kept: this one and the twelve before it. */
const val PAID_HISTORY_MONTHS = 13

/** Whether this expense already has a mark against it for [month]. */
fun isPaid(marks: List<PaidMark>, name: String, month: String): Boolean =
    marks.any { it.name == name && it.month == month }

/**
 * Marks an expense paid, or takes the mark back off it.
 *
 * One action rather than two, because the mistake being recovered from is always
 * the same one: the wrong row was tapped, and the fix is to tap it again.
 */
fun togglePaid(marks: List<PaidMark>, pay: Pay, month: String): List<PaidMark> =
    if (isPaid(marks, pay.name, month)) {
        marks.filterNot { it.name == pay.name && it.month == month }
    } else {
        marks + PaidMark(pay.name, month, pay.amount, pay.currency)
    }

/**
 * Drops marks older than the window, and any month that has not happened yet.
 *
 * A future month can only come from a clock that was wrong when the mark was made,
 * and leaving one in would put a month above the current one on the overview.
 */
fun prunePaidMarks(marks: List<PaidMark>, today: LocalDate): List<PaidMark> {
    val first = today.withDayOfMonth(1)
    val oldest = monthKey(first.minusMonths((PAID_HISTORY_MONTHS - 1).toLong()))
    val newest = monthKey(first)
    return marks.filter { it.month in oldest..newest }
}

/** How a month turned out, once it is known what was planned and what was marked. */
enum class MonthState {
    /** There were no standing expenses and nothing was marked: nothing was owed. */
    NOTHING_DUE,

    /** Every standing expense has a mark against it. */
    SETTLED,

    /** The month is still running, so what is unmarked is not yet late. */
    RUNNING,

    /** The month is over and not one payment was marked. */
    UNRECORDED,

    /** The month is over and some of it was marked. */
    PARTIAL
}

data class MonthRecord(
    /** "2026-08". */
    val month: String,
    /** "Серпень 2026". */
    val title: String,
    /** What the marks for this month add up to. */
    val paid: MonthlyTotal,
    /** What the standing expenses ask for. */
    val planned: MonthlyTotal,
    /** Planned minus paid, never below nought. */
    val gap: Double,
    val paidCount: Int,
    val plannedCount: Int,
    val state: MonthState
)

/**
 * One month, read back.
 *
 * [planned] can only be the expenses standing *now*: nothing on the phone records
 * what the list looked like in August. That is honest for the months this feature
 * has been running and is the reason [monthRecords] refuses to go back further
 * than the first mark.
 */
fun monthRecord(
    pays: List<Pay>,
    marks: List<PaidMark>,
    month: String,
    today: LocalDate,
    usdSellRate: Double
): MonthRecord {
    val forMonth = marks.filter { it.month == month }
    // Marks are totalled through the same function as expenses, so a dollar rent
    // paid with no rate loaded stays visible as dollars instead of reading as nought.
    // A mark is money that was actually handed over, so it carries no trial and
    // none of these ever read as free.
    val paid = monthlyTotal(
        forMonth.map { Pay(it.name, it.amount, currency = it.currency) },
        usdSellRate,
        today
    )
    val planned = monthlyTotal(pays, usdSellRate, today)
    val settled = pays.all { isPaid(marks, it.name, month) }
    return MonthRecord(
        month = month,
        title = monthTitle(month),
        paid = paid,
        planned = planned,
        gap = (planned.total - paid.total).coerceAtLeast(0.0),
        paidCount = forMonth.size,
        plannedCount = pays.size,
        state = when {
            pays.isEmpty() && forMonth.isEmpty() -> MonthState.NOTHING_DUE
            settled -> MonthState.SETTLED
            month >= monthKey(today) -> MonthState.RUNNING
            forMonth.isEmpty() -> MonthState.UNRECORDED
            else -> MonthState.PARTIAL
        }
    )
}

/**
 * The months worth showing, newest first.
 *
 * Runs back from this month only as far as the earliest mark. Before that the app
 * was not keeping a record, and a row per month saying "не позначено жодного
 * платежу" for a year that was in fact paid would be an accusation rather than a
 * report.
 */
fun monthRecords(
    pays: List<Pay>,
    marks: List<PaidMark>,
    today: LocalDate,
    usdSellRate: Double,
    maxMonths: Int = PAID_HISTORY_MONTHS
): List<MonthRecord> {
    val current = monthKey(today)
    val first = today.withDayOfMonth(1)
    val earliest = marks.map { it.month }.filter { it <= current }.minOrNull() ?: current
    return (0 until maxMonths.coerceAtLeast(1))
        .map { monthKey(first.minusMonths(it.toLong())) }
        .takeWhile { it >= earliest }
        .map { monthRecord(pays, marks, it, today, usdSellRate) }
}

/** A month in one line, which is all a row on the overview has room for. */
fun monthRecordLine(record: MonthRecord): String = when (record.state) {
    MonthState.NOTHING_DUE -> "Нічого не було до сплати"
    MonthState.UNRECORDED -> "Не позначено жодного платежу"
    MonthState.SETTLED -> "Сплачено все · ${totalLabel(record.paid)}"
    MonthState.RUNNING -> "Сплачено ${totalLabel(record.paid)} з ${totalLabel(record.planned)}"
    MonthState.PARTIAL -> "Сплачено ${totalLabel(record.paid)}, лишилось ${money(record.gap)}"
}

/**
 * The second line: how many of the month's expenses are accounted for.
 *
 * Empty where the figure above already said it, because a row that repeats itself
 * in smaller grey type is how a list stops being read.
 */
fun monthRecordDetail(record: MonthRecord): String = when (record.state) {
    MonthState.NOTHING_DUE -> ""
    MonthState.UNRECORDED -> "Місяць минув · ${paymentsLabel(record.plannedCount)} у списку"
    MonthState.SETTLED -> paymentsLabel(record.paidCount)
    // Bare figures rather than "1 з 2 платежі": "з" governs the genitive, and
    // building that phrase from a nominative plural helper is how an app comes out
    // sounding translated.
    else -> "Позначено ${record.paidCount} з ${record.plannedCount}"
}

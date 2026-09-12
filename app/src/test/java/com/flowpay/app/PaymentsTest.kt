package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The monthly total is the one number on the expenses screen, and it has to add up
 * two currencies without ever quietly dropping one of them.
 */
class PaymentsTest {

    private val rentInDollars = Pay("Оренда квартири", 250.0, day = 5, currency = USD)
    private val utilities = Pay("Комуналка", 2400.0, day = 20)
    private val internet = Pay("Інтернет", 300.0, day = 1, currency = UAH)

    @Test
    fun `hryvnia only expenses add up without needing a rate`() {
        val total = monthlyTotal(listOf(utilities, internet), usdSellRate = 0.0)

        assertEquals(2700.0, total.uah, 0.001)
        assertEquals(2700.0, total.total, 0.001)
        assertEquals(0.0, total.usd, 0.001)
        assertFalse(total.hasUsd)
        assertFalse(total.rateMissing)
    }

    @Test
    fun `dollar rent is converted and added to the hryvnia expenses`() {
        val total = monthlyTotal(listOf(rentInDollars, utilities, internet), usdSellRate = 44.8)

        assertEquals(2700.0, total.uah, 0.001)
        assertEquals(250.0, total.usd, 0.001)
        assertEquals(11_200.0, total.usdInUah, 0.001)
        assertEquals(13_900.0, total.total, 0.001)
        assertTrue(total.hasUsd)
        assertFalse(total.rateMissing)
    }

    @Test
    fun `without a rate the dollars are reported separately rather than counted as zero hryvnia`() {
        val total = monthlyTotal(listOf(rentInDollars, utilities), usdSellRate = 0.0)

        assertEquals(2400.0, total.total, 0.001)
        assertEquals(250.0, total.usd, 0.001)
        assertEquals(0.0, total.usdInUah, 0.001)
        assertTrue(total.rateMissing)
    }

    @Test
    fun `a negative rate is treated as no rate at all`() {
        val total = monthlyTotal(listOf(rentInDollars), usdSellRate = -44.8)

        assertEquals(0.0, total.usdInUah, 0.001)
        assertEquals(0.0, total.total, 0.001)
        assertTrue(total.rateMissing)
    }

    @Test
    fun `an entry saved before currencies existed counts as hryvnia`() {
        val legacy = Pay("Мобільний", 200.0)

        assertEquals(UAH, legacy.currency)
        assertEquals(200.0, monthlyTotal(listOf(legacy), 44.8).total, 0.001)
    }

    @Test
    fun `an empty list costs nothing and needs no rate`() {
        val total = monthlyTotal(emptyList(), usdSellRate = 44.8)

        assertEquals(0.0, total.total, 0.001)
        assertFalse(total.hasUsd)
        assertFalse(total.rateMissing)
    }

    @Test
    fun `free money is income less everything standing`() {
        val month = budget(45_000.0, monthlyTotal(listOf(rentInDollars, utilities, internet), 44.8))

        assertEquals(45_000.0, month.income, 0.001)
        assertEquals(13_900.0, month.expenses, 0.001)
        assertEquals(31_100.0, month.free, 0.001)
        assertFalse(month.overspent)
        assertFalse(month.unknown)
    }

    @Test
    fun `a month that does not fit reports a shortfall rather than clamping to zero`() {
        val month = budget(10_000.0, monthlyTotal(listOf(rentInDollars, utilities), 44.8))

        assertEquals(-3_600.0, month.free, 0.001)
        assertTrue(month.overspent)
    }

    @Test
    fun `no income means nothing can be said about free money`() {
        val month = budget(0.0, monthlyTotal(listOf(utilities), 44.8))

        assertTrue(month.unknown)
        assertFalse(month.overspent)
    }

    @Test
    fun `income with no expenses is entirely free`() {
        val month = budget(20_000.0, monthlyTotal(emptyList(), 44.8))

        assertEquals(20_000.0, month.free, 0.001)
        assertFalse(month.overspent)
    }

    @Test
    fun `several dollar expenses are summed before conversion`() {
        val total = monthlyTotal(
            listOf(rentInDollars, Pay("Хостинг", 30.0, currency = USD)),
            usdSellRate = 44.0
        )

        assertEquals(280.0, total.usd, 0.001)
        assertEquals(12_320.0, total.total, 0.001)
    }

    // ------------------------------------------------------- how it reads

    /**
     * Groups of thousands are separated by a non-breaking space in this locale,
     * which is right on screen and unreadable in a failure message.
     */
    private fun shown(text: String) = text.replace('\u00a0', ' ')

    @Test
    fun `hryvnia never shows a fraction of a kopeck`() {
        // 250 $ at 44,8009 is 11 200,225 — three decimals, which the screen showed.
        assertEquals("11 200,23 ₴", shown(money(11_200.225)))
    }

    @Test
    fun `a whole amount carries no decimals at all`() {
        assertEquals("40 000 ₴", shown(money(40_000.0)))
        assertEquals("250 $", shown(dollars(250.0)))
    }

    @Test
    fun `kopecks and cents survive when they are real`() {
        assertEquals("2 203,24 ₴", shown(money(2_203.24)))
        assertEquals("9,59 $", shown(dollars(9.59)))
    }

    @Test
    fun `a converted figure is rounded to whole hryvnia`() {
        // An estimate at today's rate has no business claiming kopecks.
        assertEquals("11 200 ₴", shown(approxMoney(250.0 * 44.8009)))
        assertEquals("45 ₴", shown(approxMoney(44.6)))
    }

    @Test
    fun `an amount is labelled in the currency it was entered in`() {
        assertEquals("9,59 $", shown(amountLabel(9.59, USD)))
        assertEquals("8 000 ₴", shown(amountLabel(8_000.0, UAH)))
    }

    // ------------------------------------------------ when the money leaves

    private val dueRent = Pay("Оренда квартири", 250.0, 1, USD)
    private val dueFood = Pay("Скинутись на їжу", 8_000.0, 1)
    private val dueInternet = Pay("Інтернет", 300.0, 10)
    private val dueAll = listOf(dueRent, dueFood, dueInternet)

    @Test
    fun `something due today is nought days away, not one`() {
        val next = nextPayment(dueAll, LocalDate.of(2026, 9, 1), 44.0)!!

        assertEquals(0, next.daysAway)
        assertEquals(LocalDate.of(2026, 9, 1), next.date)
        assertEquals(listOf(dueRent, dueFood), next.items)
        assertEquals(19_000.0, next.total.total, 0.001)
    }

    @Test
    fun `once a day has passed the search moves into next month`() {
        // The 10th is behind us, so the 1st of October is what comes next.
        val next = nextPayment(dueAll, LocalDate.of(2026, 9, 11), 44.0)!!

        assertEquals(LocalDate.of(2026, 10, 1), next.date)
        assertEquals(20, next.daysAway)
    }

    @Test
    fun `the nearer of two dates wins`() {
        val next = nextPayment(dueAll, LocalDate.of(2026, 9, 5), 44.0)!!

        assertEquals(LocalDate.of(2026, 9, 10), next.date)
        assertEquals(listOf(dueInternet), next.items)
    }

    @Test
    fun `a missing rate stays visible instead of reading as a smaller bill`() {
        val next = nextPayment(listOf(dueRent), LocalDate.of(2026, 9, 1), usdSellRate = 0.0)!!

        assertTrue(next.total.rateMissing)
        assertEquals(250.0, next.total.usd, 0.001)
    }

    @Test
    fun `nothing to pay means nothing to announce`() {
        assertNull(nextPayment(emptyList(), LocalDate.of(2026, 9, 1), 44.0))
    }

    @Test
    fun `the thirty-first falls on the last day a short month has`() {
        val end = Pay("Хостинг", 200.0, 31)

        assertEquals(
            LocalDate.of(2026, 9, 30),
            nextPayment(listOf(end), LocalDate.of(2026, 9, 5), 44.0)!!.date
        )
        assertEquals(
            LocalDate.of(2027, 2, 28),
            nextPayment(listOf(end), LocalDate.of(2027, 2, 1), 44.0)!!.date
        )
    }

    @Test
    fun `the countdown is said the way Ukrainian says it`() {
        assertEquals("сьогодні", dueLabel(0))
        assertEquals("завтра", dueLabel(1))
        assertEquals("через 2 дні", dueLabel(2))
        assertEquals("через 5 днів", dueLabel(5))
        assertEquals("через 11 днів", dueLabel(11))
        assertEquals("через 21 день", dueLabel(21))
    }

    // -------------------------------------------------- grouped for the list

    @Test
    fun `expenses sharing a date are one group, soonest first`() {
        val groups = paymentGroups(dueAll, LocalDate.of(2026, 9, 5))

        assertEquals(2, groups.size)
        assertEquals(LocalDate.of(2026, 9, 10), groups[0].date)
        assertEquals(listOf(2), groups[0].positions)
        assertEquals(LocalDate.of(2026, 10, 1), groups[1].date)
        assertEquals(listOf(0, 1), groups[1].positions)
    }

    @Test
    fun `a group carries positions, so identical entries stay distinguishable`() {
        // Two expenses equal as values: deleting "one of them" must mean a definite one.
        val twins = listOf(dueFood, dueFood)

        val groups = paymentGroups(twins, LocalDate.of(2026, 9, 1))

        assertEquals(1, groups.size)
        assertEquals(listOf(0, 1), groups[0].positions)
    }

    @Test
    fun `nothing to show is an empty timeline, not a group of nothing`() {
        assertTrue(paymentGroups(emptyList(), LocalDate.of(2026, 9, 1)).isEmpty())
    }

    @Test
    fun `a panel says how many when naming them all would be a paragraph`() {
        // Six names wrapped the panel to three lines and repeated the list below it.
        assertEquals("Інтернет", dueSummary(listOf(dueInternet)))
        assertEquals("Оренда квартири, Скинутись на їжу", dueSummary(listOf(dueRent, dueFood)))
        assertEquals("3 платежі", dueSummary(dueAll))
        assertEquals("", dueSummary(emptyList()))
    }

    @Test
    fun `payments are counted the way Ukrainian counts them`() {
        assertEquals("1 платіж", paymentsLabel(1))
        assertEquals("2 платежі", paymentsLabel(2))
        assertEquals("5 платежів", paymentsLabel(5))
        assertEquals("11 платежів", paymentsLabel(11))
        assertEquals("21 платіж", paymentsLabel(21))
    }

    @Test
    fun `the strip counts forward from today, not from the first of the month`() {
        // Offsets, so the strip covers the same span as the timeline under it.
        assertEquals(setOf(0, 9), paymentOffsets(dueAll, LocalDate.of(2026, 9, 1)))
        // From the 11th the window reaches 10 October: the 1st is 20 days out,
        // the internet bill 29.
        assertEquals(setOf(20, 29), paymentOffsets(dueAll, LocalDate.of(2026, 9, 11)))
    }

    @Test
    fun `a day past the end of a short month is marked on its last day`() {
        val end = listOf(Pay("Хостинг", 200.0, 31))

        // 1 February 2027 plus 27 days is the 28th, the last day that month has.
        assertEquals(setOf(27), paymentOffsets(end, LocalDate.of(2027, 2, 1)))
    }

    @Test
    fun `nothing due in the window leaves the strip empty`() {
        assertTrue(paymentOffsets(emptyList(), LocalDate.of(2026, 9, 11)).isEmpty())
        assertTrue(paymentOffsets(dueAll, LocalDate.of(2026, 9, 11), days = 5).isEmpty())
    }

    // ------------------------------------------------------------ a year of it

    @Test
    fun `a year is twelve of the same month`() {
        val yearly = yearlyTotal(listOf(utilities, internet), usdSellRate = 0.0)

        assertEquals(32_400.0, yearly.uah, 0.001)
        assertEquals(32_400.0, yearly.total, 0.001)
    }

    @Test
    fun `dollar rent is converted before it is multiplied out`() {
        val yearly = yearlyTotal(listOf(rentInDollars, utilities, internet), usdSellRate = 44.8)

        assertEquals(3_000.0, yearly.usd, 0.001)
        assertEquals(134_400.0, yearly.usdInUah, 0.001)
        assertEquals(166_800.0, yearly.total, 0.001)
        assertFalse(yearly.rateMissing)
    }

    @Test
    fun `a year with no rate says so rather than showing the hryvnia part as the answer`() {
        // The trap this guards: multiplying a short month by twelve makes the gap
        // twelve times bigger while looking like a bigger, more confident number.
        val yearly = yearlyTotal(listOf(rentInDollars, utilities, internet), usdSellRate = 0.0)

        assertTrue(yearly.rateMissing)
        assertEquals(3_000.0, yearly.usd, 0.001)
        assertEquals(0.0, yearly.usdInUah, 0.001)
        assertEquals(32_400.0, yearly.total, 0.001)
    }

    @Test
    fun `an empty screen has no yearly cost and no missing rate`() {
        val yearly = yearlyTotal(emptyList(), usdSellRate = 0.0)

        assertEquals(0.0, yearly.total, 0.001)
        assertFalse(yearly.rateMissing)
        assertFalse(yearly.hasUsd)
    }

    @Test
    fun `each expense states its own year in its own currency`() {
        assertEquals("3 600 ₴ на рік", shown(annualLabel(internet)))
        assertEquals("3 000 $ на рік", shown(annualLabel(rentInDollars)))
    }

    // -------------------------------------------------------- advance warning

    private val today = LocalDate.of(2026, 9, 12)

    @Test
    fun `an expense set to the charge date is announced only that morning`() {
        val onTheDay = Pay("Комуналка", 2400.0, day = 20, warnDays = 0)

        assertTrue(remindersDue(listOf(onTheDay), today).isEmpty())
        assertEquals(
            listOf(0),
            remindersDue(listOf(onTheDay), LocalDate.of(2026, 9, 20)).map { it.daysAway }
        )
    }

    @Test
    fun `a week of warning is what lets a subscription be cancelled in time`() {
        val subscription = Pay("Підписка", 400.0, day = 18, warnDays = 7)

        // Both the 11th and the 18th of September 2026 are Fridays, so nothing is
        // shifted here and the week of notice is a plain week.
        val due = remindersDue(listOf(subscription), LocalDate.of(2026, 9, 11))

        assertEquals(1, due.size)
        assertEquals(7, due.single().daysAway)
    }

    @Test
    fun `the window opens and then stays open until the charge`() {
        val subscription = Pay("Підписка", 400.0, day = 17, warnDays = 3)

        assertTrue(remindersDue(listOf(subscription), LocalDate.of(2026, 9, 13)).isEmpty())
        assertEquals(3, remindersDue(listOf(subscription), LocalDate.of(2026, 9, 14)).single().daysAway)
        assertEquals(0, remindersDue(listOf(subscription), LocalDate.of(2026, 9, 17)).single().daysAway)
    }

    @Test
    fun `an expense read from an older save keeps the day of notice it always had`() {
        // Nothing wrote this field before, and reading the absence as nought would
        // have moved every existing expense to a reminder that arrives too late.
        assertEquals(1, Pay("Інтернет", 300.0, day = 13).warnDays)
        assertEquals(
            1,
            remindersDue(listOf(Pay("Інтернет", 300.0, day = 16)), LocalDate.of(2026, 9, 15))
                .single().daysAway
        )
    }

    @Test
    fun `a day past the end of a short month warns from its real charge date`() {
        val end = listOf(Pay("Хостинг", 200.0, day = 31, warnDays = 3))

        // February 2027 ends on the 28th, and that Sunday is not a day money moves,
        // so the charge has to be dealt with by Friday the 26th and the window opens
        // three days before that.
        assertTrue(remindersDue(end, LocalDate.of(2027, 2, 22)).isEmpty())
        assertEquals(3, remindersDue(end, LocalDate.of(2027, 2, 23)).single().daysAway)
        assertEquals(0, remindersDue(end, LocalDate.of(2027, 2, 26)).single().daysAway)
    }

    @Test
    fun `a warning that reaches into next month still counts the days correctly`() {
        val rent = listOf(Pay("Оренда", 250.0, day = 1, currency = USD, warnDays = 7))

        // 25 September to 1 October is six days, inside a week's notice.
        assertEquals(6, remindersDue(rent, LocalDate.of(2026, 9, 25)).single().daysAway)
    }

    @Test
    fun `the soonest charge is named first`() {
        val items = listOf(
            Pay("Підписка", 400.0, day = 19, warnDays = 7),
            Pay("Інтернет", 300.0, day = 13, warnDays = 1)
        )

        assertEquals(listOf("Інтернет", "Підписка"), remindersDue(items, today).map { it.pay.name })
    }

    @Test
    fun `one expense says when it is due in the title and only its amount below`() {
        // A Tuesday charged on the Wednesday, so the wording is about the notice
        // period rather than about the weekend.
        val tuesday = LocalDate.of(2026, 9, 15)
        val due = remindersDue(listOf(Pay("Інтернет", 300.0, day = 16, warnDays = 1)), tuesday)

        assertEquals("Оплата завтра", reminderTitle(due))
        assertEquals("Інтернет 300 ₴", shown(reminderText(due)))
    }

    @Test
    fun `several on the same day share one heading`() {
        val tuesday = LocalDate.of(2026, 9, 15)
        val items = listOf(
            Pay("Інтернет", 300.0, day = 16, warnDays = 1),
            Pay("Мобільний", 200.0, day = 16, warnDays = 1)
        )

        assertEquals("2 платежі завтра", reminderTitle(remindersDue(items, tuesday)))
        assertEquals(
            "Інтернет 300 ₴, Мобільний 200 ₴",
            shown(reminderText(remindersDue(items, tuesday)))
        )
    }

    @Test
    fun `different days are said against each name, or a week of notice reads as today`() {
        val tuesday = LocalDate.of(2026, 9, 15)
        val items = listOf(
            Pay("Інтернет", 300.0, day = 16, warnDays = 1),
            Pay("Підписка", 400.0, day = 22, warnDays = 7)
        )
        val due = remindersDue(items, tuesday)

        assertEquals("Найближчі платежі", reminderTitle(due))
        assertEquals(
            "Інтернет 300 ₴ — завтра · Підписка 400 ₴ — через 7 днів",
            shown(reminderText(due))
        )
    }

    @Test
    fun `today is said as today rather than as nought days`() {
        val due = remindersDue(listOf(Pay("Комуналка", 2400.0, day = 12, warnDays = 3)), today)

        assertEquals("Оплата сьогодні", reminderTitle(due))
    }

    @Test
    fun `nothing due means nothing to say`() {
        assertEquals("", reminderTitle(emptyList()))
        assertEquals("", reminderText(emptyList()))
        assertTrue(remindersDue(emptyList(), today).isEmpty())
    }

    @Test
    fun `the choices offered read as correct ukrainian`() {
        assertEquals(listOf(0, 1, 3, 7), WARN_CHOICES)
        assertEquals("У день оплати", warnLabel(0))
        assertEquals("За день", warnLabel(1))
        assertEquals("За 3 дні", warnLabel(3))
        assertEquals("За 7 днів", warnLabel(7))
    }
}

/**
 * The record of what was actually paid.
 *
 * The failure that matters most is the quiet one: a month that was in fact paid
 * being reported as a month nobody paid, or the other way round. The app can only
 * know what was marked, so every state it reports has to be distinguishable from
 * the states it cannot tell apart — which is why "nothing was due" and "nothing
 * was marked" are two answers and not one.
 */
class PaidRecordTest {

    private val today = LocalDate.of(2026, 9, 12)
    private val august = "2026-08"
    private val september = "2026-09"

    private val rent = Pay("Оренда квартири", 11_000.0, day = 5)
    private val internet = Pay("Інтернет", 300.0, day = 1)
    private val standing = listOf(rent, internet)

    private fun mark(pay: Pay, month: String) = PaidMark(pay.name, month, pay.amount, pay.currency)

    /** Thousands are grouped with a non-breaking space in this locale. */
    private fun shown(text: String) = text.replace(' ', ' ')

    @Test
    fun `a month key sorts as time and reads as a heading`() {
        assertEquals("2026-09", monthKey(today))
        // Zero padding is the whole point: without it "2026-9" sorts after "2026-10".
        assertTrue(monthKey(LocalDate.of(2026, 9, 1)) > monthKey(LocalDate.of(2026, 8, 31)))
        assertEquals("Серпень 2026", monthTitle(august))
        assertEquals(LocalDate.of(2026, 8, 1), monthKeyDate(august))
        assertNull(monthKeyDate("серпень"))
    }

    @Test
    fun `a month with nothing due is not a month nobody paid`() {
        val record = monthRecord(emptyList(), emptyList(), august, today, usdSellRate = 0.0)

        assertEquals(MonthState.NOTHING_DUE, record.state)
        assertEquals(0.0, record.planned.total, 0.001)
        assertEquals(0.0, record.gap, 0.001)
        assertEquals("Нічого не було до сплати", monthRecordLine(record))

        // The same month with expenses standing and nothing marked is the other
        // answer, and the two must never share a wording.
        val unrecorded = monthRecord(standing, emptyList(), august, today, usdSellRate = 0.0)
        assertEquals(MonthState.UNRECORDED, unrecorded.state)
        assertEquals("Не позначено жодного платежу", monthRecordLine(unrecorded))
        assertEquals(11_300.0, unrecorded.gap, 0.001)
    }

    @Test
    fun `a past month with some of it marked is partly paid`() {
        val record = monthRecord(
            standing,
            listOf(mark(internet, august)),
            august,
            today,
            usdSellRate = 0.0
        )

        assertEquals(MonthState.PARTIAL, record.state)
        assertEquals(300.0, record.paid.total, 0.001)
        assertEquals(11_300.0, record.planned.total, 0.001)
        assertEquals(11_000.0, record.gap, 0.001)
        assertEquals(1, record.paidCount)
        assertEquals(2, record.plannedCount)
        assertEquals("Сплачено 300 ₴, лишилось 11 000 ₴", shown(monthRecordLine(record)))
        assertEquals("Позначено 1 з 2", monthRecordDetail(record))
    }

    @Test
    fun `the month still running is not late merely because it is unfinished`() {
        val record = monthRecord(
            standing,
            listOf(mark(internet, september)),
            september,
            today,
            usdSellRate = 0.0
        )

        assertEquals(MonthState.RUNNING, record.state)
        assertEquals(11_000.0, record.gap, 0.001)
    }

    @Test
    fun `everything marked settles the month, whether it is over or not`() {
        val marks = standing.map { mark(it, august) }
        val record = monthRecord(standing, marks, august, today, usdSellRate = 0.0)

        assertEquals(MonthState.SETTLED, record.state)
        assertEquals(11_300.0, record.paid.total, 0.001)
        assertEquals(0.0, record.gap, 0.001)
        assertEquals("Сплачено все · 11 300 ₴", shown(monthRecordLine(record)))
    }

    @Test
    fun `a mark keeps the amount that was paid, not the amount charged later`() {
        val marks = listOf(mark(rent, august))
        // The rent goes up in September. August must not be rewritten by it.
        val dearer = listOf(rent.copy(amount = 12_500.0), internet)
        val record = monthRecord(dearer, marks, august, today, usdSellRate = 0.0)

        assertEquals(11_000.0, record.paid.total, 0.001)
        assertEquals(12_800.0, record.planned.total, 0.001)
    }

    @Test
    fun `dollars paid with no rate stay visible instead of reading as nothing`() {
        val dollarRent = Pay("Оренда квартири", 250.0, day = 5, currency = USD)
        val record = monthRecord(
            listOf(dollarRent),
            listOf(mark(dollarRent, august)),
            august,
            today,
            usdSellRate = 0.0
        )

        assertEquals(250.0, record.paid.usd, 0.001)
        assertTrue(record.paid.rateMissing)
        assertTrue(monthRecordLine(record).contains("250 $"))
    }

    @Test
    fun `marking is a toggle, so the wrong row is undone by tapping it again`() {
        val once = togglePaid(emptyList(), rent, august)
        assertTrue(isPaid(once, rent.name, august))
        assertEquals(11_000.0, once.single().amount, 0.001)

        val twice = togglePaid(once, rent, august)
        assertFalse(isPaid(twice, rent.name, august))
        assertTrue(twice.isEmpty())

        // Marking one month says nothing about another.
        val septemberOnly = togglePaid(emptyList(), rent, september)
        assertFalse(isPaid(septemberOnly, rent.name, august))
    }

    @Test
    fun `at least a year is kept and anything older is dropped`() {
        val marks = (0L until 18L).map {
            mark(rent, monthKey(today.withDayOfMonth(1).minusMonths(it)))
        }
        val kept = prunePaidMarks(marks, today)

        assertEquals(PAID_HISTORY_MONTHS, kept.size)
        assertTrue(kept.size >= 12)
        // Twelve months back survives; thirteen does not.
        assertTrue(kept.any { it.month == monthKey(today.minusMonths(12)) })
        assertFalse(kept.any { it.month == monthKey(today.minusMonths(13)) })
    }

    @Test
    fun `a month that has not happened yet is never kept`() {
        val kept = prunePaidMarks(listOf(mark(rent, "2027-01")), today)

        assertTrue(kept.isEmpty())
    }

    @Test
    fun `the overview goes back only as far as the record does`() {
        // Nothing marked at all: one row, this month, and no year of accusations.
        val fresh = monthRecords(standing, emptyList(), today, usdSellRate = 0.0)
        assertEquals(1, fresh.size)
        assertEquals(september, fresh.single().month)

        val records = monthRecords(
            standing,
            listOf(mark(rent, "2026-07")),
            today,
            usdSellRate = 0.0
        )
        assertEquals(listOf("2026-09", "2026-08", "2026-07"), records.map { it.month })
        // Newest first: the question is nearly always about the month that just ended.
        assertEquals("Вересень 2026", records.first().title)
        assertEquals(MonthState.UNRECORDED, records[1].state)
        assertEquals(MonthState.PARTIAL, records[2].state)
    }

    // ------------------------------- the app's language, not the phone's

    /**
     * Runs a block with the JVM's default locale temporarily set elsewhere.
     *
     * The build server runs in en-US and this machine in uk-UA, which is how a
     * figure formatted against the default locale got all the way to a release
     * tag before anything noticed.
     */
    private fun <T> inLocale(locale: java.util.Locale, block: () -> T): T {
        val previous = java.util.Locale.getDefault()
        java.util.Locale.setDefault(locale)
        try {
            return block()
        } finally {
            java.util.Locale.setDefault(previous)
        }
    }

    @Test
    fun `figures keep the comma when the phone is set to English`() {
        inLocale(java.util.Locale.US) {
            assertEquals("3,2", figure(3.24))
            assertEquals("44,80", figure(44.8, 2))
            assertEquals("12", figure(12.4, 0))
        }
    }

    @Test
    fun `money and percentages do not change with the phone's language`() {
        val hryvnia = inLocale(java.util.Locale.US) { shown(money(2_199.5)) }
        val percent = inLocale(java.util.Locale.US) { signedPercent(-3.24) }
        val rate = inLocale(java.util.Locale.US) { rateFigure(44.8) }

        assertEquals("2 199,5 ₴", hryvnia)
        assertEquals("−3,2%", percent)
        assertEquals("44,80", rate)
    }

    @Test
    fun `a month key is the same string in every locale`() {
        // It is a storage key: a locale with its own numerals would write one that
        // nothing can read back, and a year of paid marks would vanish.
        val here = monthKey(LocalDate.of(2026, 3, 9))
        val elsewhere = inLocale(java.util.Locale.forLanguageTag("ar-EG")) {
            monthKey(LocalDate.of(2026, 3, 9))
        }

        assertEquals("2026-03", here)
        assertEquals(here, elsewhere)
    }

}

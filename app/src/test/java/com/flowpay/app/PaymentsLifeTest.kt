package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder
import java.time.LocalDate

/**
 * A payment's life after it starts: cancelled «діє до», paused, a promo price
 * that ends, a plan paid off early or returned — and the one check every count
 * goes through, so none of them can keep «charging» anywhere.
 */
class PaymentsLifeTest {

    /** A Sunday. 28 October 2026 is a Wednesday, 1 February 2027 a Monday. */
    private val today = LocalDate.of(2026, 10, 4)

    /** The locale's grouping space is a no-break one; the expectations use a plain one. */
    private fun shown(text: String) = text.replace(' ', ' ').replace(' ', ' ')

    private val netflix = Pay("Netflix", 299.0, day = 28, warnDays = 3)

    // ------------------------------------------------------------ on disk

    @Test
    fun `every new field survives the bin and the backup`() {
        val full = Pay(
            "Netflix", 299.0, day = 28, currency = UAH, warnDays = 3,
            amounts = listOf(PricePoint(269.0, 0L), PricePoint(299.0, 20_000L)),
            trialEnd = 20_500L, emoji = "🍿", monoMerchant = "netflix com",
            stopsAfter = 20_400L, stopReason = STOP_CANCELLED, pausedFrom = 20_300L,
            pauses = listOf(PauseSpan(20_000L, 20_050L), PauseSpan(20_100L, 20_120L, 199.0)),
            promoPrice = 149.0, cancelUrl = "https://example.com/cancel", order = "order-1"
        )
        assertEquals(full, payOf(payJson(full)))
        val order = Order("o1", "iPhone", "", RECEIVED, planReturned = 20_401L)
        assertEquals(order, orderOf(orderJson(order)))
    }

    @Test
    fun `a payment stored before any of this runs as it did`() {
        val back = payOf(JSONObject().put("n", "Інтернет").put("a", 300.0).put("d", 1))
        assertEquals(0L, back.stopsAfter)
        assertEquals("", back.stopReason)
        assertEquals(0L, back.pausedFrom)
        assertTrue(back.pauses.isEmpty())
        assertEquals(0.0, back.promoPrice, 0.0)
        assertEquals("", back.cancelUrl)
        assertEquals("", back.order)
        assertEquals(PayLife.RUNNING, lifeOf(back, today))
        assertEquals(0L, orderOf(JSONObject().put("id", "o").put("n", "X")).planReturned)
    }

    // ------------------------------------------------------------ cancelled

    @Test
    fun `a cancellation works until the day before the charge it stops`() {
        assertEquals(LocalDate.of(2026, 10, 27), paidUntil(netflix, today, emptyList()))
        // October already ticked: that money is spent, so November's period runs.
        val ticked = listOf(PaidMark("Netflix", "2026-10", 299.0))
        assertEquals(LocalDate.of(2026, 11, 27), paidUntil(netflix, today, ticked))
        // An annual fee works until the day before next year's charge.
        val domain = Pay("Домен", 600.0, day = 14, billingMonth = 3)
        assertEquals(LocalDate.of(2027, 3, 13), paidUntil(domain, today, emptyList()))
        // A free trial: until the day before the first real charge.
        val trial = Pay("YouTube Premium", 179.0, day = 20, trialEnd = LocalDate.of(2026, 10, 20).toEpochDay())
        assertEquals(LocalDate.of(2026, 10, 19), paidUntil(trial, today, emptyList()))
    }

    @Test
    fun `a cancelled subscription stops counting everywhere after its paid period`() {
        val gone = cancelled(netflix, paidUntil(netflix, today, emptyList()), today)
        assertEquals(PayLife.CANCELLED, lifeOf(gone, today))
        assertEquals("скасовано · діє до 27 жовтня", cancelledLine(gone, today))
        assertFalse(isLive(gone, today))
        // The month, the next payment, what is owed, the reminders, the timeline.
        assertEquals(0.0, monthlyTotal(listOf(gone), 0.0, today).total, 0.0)
        assertNull(nextPayment(listOf(gone), today, 0.0))
        assertTrue(stillOwing(listOf(gone), emptyList(), today).isEmpty())
        assertTrue(remindersDue(listOf(gone), LocalDate.of(2026, 10, 26)).isEmpty())
        assertTrue(paymentGroups(listOf(gone), today).isEmpty())
        // The year, the strip, the forecast.
        assertEquals(0.0, yearlyCharge(gone, today), 0.0)
        assertEquals(0.0, yearlyTotal(listOf(gone), 0.0, today).total, 0.0)
        assertTrue(chargedOn(listOf(gone), LocalDate.of(2026, 10, 28)).isEmpty())
        assertTrue(paymentOffsets(listOf(gone), today).isEmpty())
        val week = moneyWeather(listOf(gone), emptyList(), LocalDate.of(2026, 10, 25), 41.0, 30_000.0, 20_000.0)
        assertTrue(week.all { it.leaving == 0.0 })
        // The widget, the overview, the pill, the digest.
        assertEquals("Платежів не заплановано", widgetSummary(listOf(gone), emptyList(), 30_000.0, 0.0, today).paymentName)
        assertEquals(0.0, overview(emptyList(), listOf(gone), emptyList(), 30_000.0, 0.0, today).monthlyExpenses, 0.0)
        assertNull(statusNote(emptyList(), listOf(gone), emptyList(), LocalDate.of(2026, 10, 27), 0.0))
        assertTrue(digest(emptyList(), listOf(gone), emptyList(), LocalDate.of(2026, 10, 26), 0.0, 0.0).empty)
        // The spreadsheet projects only what will charge again.
        val rows = expenseRows(listOf(gone, Pay("Spotify", 199.0, day = 5)), emptyList(), emptyList(), 2026, 0.0, today)
        assertEquals(listOf("Spotify"), rows.map { it[1] })
        // And the month it was paid in still holds it.
        assertEquals(1, monthRecord(listOf(gone), emptyList(), "2026-09", today, 0.0).plannedCount)
        assertEquals(0, monthRecord(listOf(gone), emptyList(), "2026-10", today, 0.0).plannedCount)
    }

    @Test
    fun `the day the next charge would have come, the morning asks once`() {
        val until = LocalDate.of(2026, 10, 27)
        val gone = cancelled(netflix, until, today)
        val day = LocalDate.of(2026, 10, 28)
        assertEquals(PayLife.ENDED, lifeOf(gone, day))
        assertTrue(lifeLines(listOf(gone), LocalDate.of(2026, 10, 27)).isEmpty())
        val lines = lifeLines(listOf(gone), day)
        assertEquals(listOf("ended|Netflix|${until.toEpochDay()}"), lines.map { it.key })
        assertEquals("Netflix: оплачений період скінчився 27 жовтня — чи не списали гроші знову?", lines.single().text)
        assertTrue(unsaid(lines, setOf(lines.single().key)).isEmpty())
        // In the message itself, with its key for the worker to remember.
        val message = digest(emptyList(), listOf(gone), emptyList(), day, 0.0, 0.0, once = lines)
        assertTrue(message.title.contains("Netflix"))
        assertEquals(lines.map { it.key }, message.said)
        assertEquals("Оплачений період скінчився 27 жовтня. Нового списання не було?", endedQuestion(gone))
    }

    @Test
    fun `an unanswered question waits a week, then the payment goes to the bin and comes back running`() {
        val gone = cancelled(netflix, LocalDate.of(2026, 10, 27), today)
        assertTrue(sweepLife(listOf(gone), LocalDate.of(2026, 11, 3)).toBin.isEmpty())
        val swept = sweepLife(listOf(gone, Pay("Spotify", 199.0, day = 5)), LocalDate.of(2026, 11, 4))
        assertEquals(listOf(gone), swept.toBin)
        assertEquals(listOf("Spotify"), swept.pays.map { it.name })
        val entry = endedBinEntry(gone, LocalDate.of(2026, 11, 4).toEpochDay())
        val back = payOf(JSONObject(entry.payload))
        assertEquals(netflix, back)
        // The same entry however often the sweep meets it.
        assertEquals(entry.id, endedBinEntry(gone, LocalDate.of(2026, 11, 9).toEpochDay()).id)
        assertEquals(BIN_PAY, entry.kind)
    }

    @Test
    fun `a cancellation that still has charges to come stays on the timeline and says so`() {
        // Moved to the end of November by hand: October's charge still happens.
        val late = cancelled(netflix, LocalDate.of(2026, 11, 30), today)
        assertTrue(isLive(late, today))
        assertEquals(PayLife.CANCELLED, lifeOf(late, today))
        assertEquals(1, paymentGroups(listOf(late), today).size)
        assertEquals(299.0, monthlyTotal(listOf(late), 0.0, today).total, 0.0)
        assertEquals(598.0, yearlyCharge(late, today), 0.0)
        assertEquals("скасовано · діє до 30 листопада", cancelledLine(late, today))
        assertNull(cancelledLine(netflix, today))
    }

    @Test
    fun `a cancellation taken back runs as before`() {
        val gone = cancelled(netflix, LocalDate.of(2026, 10, 27), today)
        assertEquals(netflix, unstopped(gone))
        // A trial cancelled before it charged has no year to promise.
        val trial = Pay("YouTube Premium", 179.0, day = 20, trialEnd = LocalDate.of(2026, 10, 20).toEpochDay())
        val dropped = cancelled(trial, LocalDate.of(2026, 10, 19), today)
        assertTrue(trialsRunning(listOf(dropped), today).isEmpty())
        assertEquals(listOf(trial), trialsRunning(listOf(trial), today))
    }

    // ------------------------------------------------------------ paused

    @Test
    fun `a pause counts nothing and reminds nothing until resumed`() {
        val megogo = Pay("Megogo", 199.0, day = 5, warnDays = 1)
        val stopped = paused(megogo, today)
        assertEquals(PayLife.PAUSED, lifeOf(stopped, today))
        assertEquals("на паузі з 4 жовтня", pausedLine(stopped))
        assertFalse(isLive(stopped, today))
        assertEquals(0.0, monthlyTotal(listOf(stopped), 0.0, today).total, 0.0)
        assertNull(nextPayment(listOf(stopped), today, 0.0))
        assertTrue(remindersDue(listOf(stopped), today).isEmpty())
        assertTrue(paymentGroups(listOf(stopped), today).isEmpty())
        assertEquals(1, monthRecord(listOf(stopped), emptyList(), "2026-09", today, 0.0).plannedCount)
        // Pausing twice keeps the first day.
        assertEquals(stopped, paused(stopped, today.plusDays(3)))
    }

    @Test
    fun `the months a pause covered stay unasked once it is over`() {
        val megogo = Pay("Megogo", 199.0, day = 5)
        val jan10 = LocalDate.of(2027, 1, 10)
        val back = resumed(paused(megogo, today), jan10)
        assertEquals(listOf(PauseSpan(today.toEpochDay(), jan10.toEpochDay())), back.pauses)
        assertEquals(PayLife.RUNNING, lifeOf(back, jan10))
        assertEquals(0, monthRecord(listOf(back), emptyList(), "2026-11", jan10, 0.0).plannedCount)
        assertEquals(0, monthRecord(listOf(back), emptyList(), "2027-01", jan10, 0.0).plannedCount)
        assertEquals(1, monthRecord(listOf(back), emptyList(), "2027-02", jan10, 0.0).plannedCount)
        assertEquals(LocalDate.of(2027, 2, 5), nextPayment(listOf(back), jan10, 0.0)?.date)
        // Resumed the day it was paused: nothing to remember.
        assertTrue(resumed(paused(megogo, today), today).pauses.isEmpty())
    }

    // ------------------------------------------------------------ a promo price

    /** 300 ₴ a month, 150 ₴ until 1 February 2027. */
    private val internet = Pay(
        "Інтернет", 300.0, day = 1, warnDays = 1,
        trialEnd = LocalDate.of(2027, 2, 1).toEpochDay(), promoPrice = 150.0
    )

    @Test
    fun `a promo charges its promo price until the date and the regular one after`() {
        assertTrue(isPromo(internet))
        assertEquals(150.0, priceOn(internet, today.toEpochDay()), 0.0)
        assertEquals(300.0, priceOn(internet, LocalDate.of(2027, 2, 1).toEpochDay()), 0.0)
        assertEquals(150.0, monthlyTotal(listOf(internet), 0.0, today).total, 0.0)
        // Unlike a free trial, its dates are charges.
        assertEquals(LocalDate.of(2026, 11, 1), nextCharge(internet, today))
        assertEquals(150.0, nextPayment(listOf(internet), today, 0.0)!!.total.total, 0.0)
        // The reminder names what this charge takes, and has no «скасувати до».
        val due = remindersDue(listOf(internet), LocalDate.of(2026, 10, 30)).single()
        assertEquals(150.0, due.amount, 0.0)
        assertNull(due.cancelBy)
        assertTrue(shown(reminderText(listOf(due))).contains("150 ₴"))
        // The forecast weighs it at the promo price.
        assertEquals(150.0, chargedOn(listOf(internet), LocalDate.of(2026, 11, 1)).single().amount, 0.0)
        val week = moneyWeather(listOf(internet), emptyList(), LocalDate.of(2026, 10, 30), 41.0, 30_000.0, 20_000.0)
        assertEquals(150.0, week.sumOf { it.leaving }, 0.0)
        // The year at today's price; what it becomes after, at the regular one.
        assertEquals(1_800.0, yearlyCharge(internet, today), 0.0)
        assertEquals(3_600.0, yearlyCommitment(listOf(internet), 0.0, today).total, 0.0)
        assertEquals(listOf(internet), trialsRunning(listOf(internet), today))
        assertEquals("150 ₴ до 1 лютого", shown(trialLabel(internet, today)!!))
        assertEquals("До 1 лютого — 150 ₴, далі 300 ₴", shown(promoNote(300.0, 150.0, internet.trialEnd, UAH)!!))
        assertEquals("Безкоштовно до 1 лютого, далі 300 ₴", shown(promoNote(300.0, 0.0, internet.trialEnd, UAH)!!))
    }

    @Test
    fun `a month in the promo is recorded at the promo price`() {
        assertEquals(150.0, monthRecord(listOf(internet), emptyList(), "2026-11", today, 0.0).planned.total, 0.0)
        assertEquals(300.0, monthRecord(listOf(internet), emptyList(), "2027-02", today, 0.0).planned.total, 0.0)
        assertEquals(150.0, monthLines(listOf(internet), emptyList(), "2026-12").single().amount, 0.0)
    }

    @Test
    fun `the end of a promo is said ahead, by the payment's own notice`() {
        val eve = LocalDate.of(2027, 1, 31)
        val line = lifeLines(listOf(internet), eve).single()
        assertEquals("promo|Інтернет|${internet.trialEnd}", line.key)
        assertEquals(
            "З 1 лютого Інтернет коштуватиме 300 ₴ замість 150 ₴ — можна попросити продовжити знижку або змінити тариф",
            shown(line.text)
        )
        assertTrue(lifeLines(listOf(internet), LocalDate.of(2027, 1, 29)).isEmpty())
        assertEquals(1, lifeLines(listOf(internet.copy(warnDays = 3)), LocalDate.of(2027, 1, 29)).size)
        assertTrue(shown(lifeLines(listOf(internet.copy(warnDays = 0)), LocalDate.of(2027, 2, 1)).single().text).startsWith("Від сьогодні"))
    }

    @Test
    fun `when a promo ends its history records the move by itself, once`() {
        val opened = internet.copy(amounts = listOf(PricePoint(300.0, today.toEpochDay())))
        assertEquals(opened, withPromoEnded(opened, LocalDate.of(2027, 1, 31)))
        val ended = withPromoEnded(opened, LocalDate.of(2027, 2, 2))
        val change = lastAmountChange(ended)!!
        assertEquals(150.0, change.from, 0.0)
        assertEquals(300.0, change.to, 0.0)
        assertEquals(internet.trialEnd, change.day)
        assertTrue(isPromoEnd(ended, change))
        assertEquals(ended, withPromoEnded(ended, LocalDate.of(2027, 2, 10)))
        // The digest's raise line sees it the week after, like any other move.
        val morning = digest(emptyList(), listOf(ended), emptyList(), LocalDate.of(2027, 2, 2), 0.0, 0.0)
        assertTrue(shown(morning.title + morning.body).contains("Інтернет 150 → 300 ₴, +100%"))
        // A year read back before the promo is the regular price, not the discount.
        assertEquals(300.0, amountOn(ended, LocalDate.of(2026, 12, 1).toEpochDay()), 0.0)
        // With no history at all, the regular price leads.
        val bare = withPromoEnded(internet, LocalDate.of(2027, 2, 2))
        assertEquals(listOf(300.0, 150.0, 300.0), bare.amounts.map { it.price })
        // The recap calls it the end of a promo, not a raise.
        val recap = monthlyRecap(emptyList(), listOf(ended), emptyList(), emptyList(), "2027-02", LocalDate.of(2027, 3, 2), 0.0, 0.0)
        assertEquals("Акція скінчилась", recap.cards.first { it.kind == RecapKind.SUB_PRICE_MOVED }.overline)
        // And the sweep is what writes it.
        assertEquals(ended, sweepLife(listOf(opened), LocalDate.of(2027, 2, 2)).pays.single())
    }

    @Test
    fun `a free trial is still a promo at nought`() {
        val trial = Pay("Spotify", 199.0, day = 12, trialEnd = LocalDate.of(2026, 10, 30).toEpochDay())
        assertFalse(isPromo(trial))
        assertEquals(0.0, priceOn(trial, today.toEpochDay()), 0.0)
        assertEquals(LocalDate.of(2026, 11, 12), nextCharge(trial, today))
        assertEquals(trial, withPromoEnded(trial, LocalDate.of(2026, 11, 2)))
    }

    // ------------------------------------------------------------ a plan's own end

    /** Six payments of 2 500 ₴ on the 15th, from August: two behind on 4 October. */
    private val phone = Pay(
        "iPhone частинами", 2_500.0, day = 15,
        instalments = 6, instalmentStart = instalmentStartFor(15, done = 2, today = today)
    )

    @Test
    fun `paid off early, the plan ends with this month's payment`() {
        val off = paidOff(phone, today)
        assertEquals(LocalDate.of(2026, 10, 15), planLast(off))
        assertEquals(STOP_PAID_OFF, off.stopReason)
        assertEquals(10_000.0, payOffSum(phone, today), 0.0)
        val marks = payOffMarks(emptyList(), phone, today)
        assertEquals(PaidMark("iPhone частинами", "2026-10", 10_000.0, UAH), marks.single())
        val ticked = payOffMarks(listOf(PaidMark("iPhone частинами", "2026-10", 2_500.0)), phone, today)
        assertEquals(10_000.0, ticked.single().amount, 0.0)
        // October still counts until it is behind; nothing after it does.
        assertEquals(2_500.0, monthlyTotal(listOf(off), 0.0, today).total, 0.0)
        assertEquals(0.0, monthlyTotal(listOf(off), 0.0, today, LocalDate.of(2026, 11, 1)).total, 0.0)
        assertEquals(2_500.0, yearlyCharge(off, today), 0.0)
        assertTrue(stillOwing(listOf(off), marks, today).isEmpty())
        assertFalse(isFinished(off, today))
        assertTrue(isFinished(off, LocalDate.of(2026, 10, 16)))
        assertEquals("дострокове погашення 15 жовтня", instalmentLine(off, today))
        assertEquals("погашено достроково", instalmentLine(off, LocalDate.of(2026, 10, 16)))
        assertEquals("погашено достроково · 15 жовтня 2026", finishedPlanLine(off))
        assertEquals("Після 15 жовтня звільниться 2 500 ₴ на місяць", shown(freedLine(listOf(off), today, 41.6)!!))
    }

    @Test
    fun `paid off after this month's payment, the plan ends today`() {
        val late = LocalDate.of(2026, 10, 20)
        val off = paidOff(phone, late)
        assertEquals(late.toEpochDay(), off.stopsAfter)
        assertTrue(isFinished(off, late))
        assertEquals(PaidMark("iPhone частинами", "2026-10", 10_000.0, UAH), payOffMarks(emptyList(), phone, late).single())
    }

    @Test
    fun `a returned plan closes at once and keeps what was paid`() {
        val back = returned(phone, today)
        assertTrue(isFinished(back, today))
        assertEquals(PayLife.FINISHED, lifeOf(back, today))
        assertEquals(0.0, yearlyCharge(back, today), 0.0)
        assertNull(nextPayment(listOf(back), today, 0.0))
        assertTrue(paymentGroups(listOf(back), today).isEmpty())
        assertEquals(1, monthRecord(listOf(back), emptyList(), "2026-09", today, 0.0).plannedCount)
        assertEquals(0, monthRecord(listOf(back), emptyList(), "2026-10", today, 0.0).plannedCount)
        assertEquals("товар повернуто · закрито 4 жовтня 2026", finishedPlanLine(back))
        assertEquals("товар повернуто · платежів більше немає", instalmentLine(back, today))
        assertEquals(phone, unstopped(back))
        // The purchase it paid for says so.
        val order = Order("o1", "iPhone", "", RECEIVED, planReturned = today.toEpochDay())
        assertEquals("Повернуто 4 жовтня 2026 — розстрочку закрито", returnedPurchaseLine(order))
        assertNull(returnedPurchaseLine(order.copy(planReturned = 0L)))
        // A plan's natural end reads as it always did.
        assertEquals("усі 6 платежів позаду · останній 15 січня 2027", finishedPlanLine(phone))
    }

    @Test
    fun `a year counts the charges that really happen`() {
        assertEquals(12, chargesAhead(netflix, today, today.plusYears(1)))
        assertEquals(1, chargesAhead(Pay("Домен", 600.0, day = 14, billingMonth = 3), today, today.plusYears(1)))
        assertEquals(4, chargesAhead(phone, today, today.plusYears(1)))
        assertEquals(10_000.0, yearlyCharge(phone, today), 0.0)
    }

    // ------------------------------------------------------------ «Як скасувати»

    @Test
    fun `the cancel button knows the big services and searches for the rest`() {
        assertEquals("https://www.netflix.com/cancelplan", cancelLink(Pay("Netflix", 299.0)).url)
        assertEquals("YouTube Premium", cancelLink(Pay("Ютуб преміум", 179.0)).label)
        assertEquals("YouTube Premium", cancelLink(Pay("Музика", 99.0, monoMerchant = "google youtube premium")).label)
        assertEquals("Megogo", cancelLink(Pay("Мегого", 199.0)).label)
        assertEquals("Sweet.tv", cancelLink(Pay("Sweet.tv", 99.0)).label)
        assertEquals("ChatGPT", cancelLink(Pay("ChatGPT Plus", 20.0, currency = USD)).label)
        assertEquals("Spotify", cancelLink(Pay("Spotify Family", 269.0)).label)
        assertEquals(
            "https://play.google.com/store/account/subscriptions",
            cancelLink(Pay("Duolingo", 99.0, monoMerchant = "google duolingo")).url
        )
        // The owner's own link wins; a malformed one does not.
        assertEquals("https://my.example/off", cancelLink(Pay("Netflix", 299.0, cancelUrl = "https://my.example/off")).url)
        assertEquals("Netflix", cancelLink(Pay("Netflix", 299.0, cancelUrl = "не посилання")).label)
        val search = cancelLink(Pay("Київстар ТБ", 99.0))
        assertTrue(search.searched)
        assertEquals("https://www.google.com/search?q=" + URLEncoder.encode("як скасувати Київстар ТБ", "UTF-8"), search.url)
    }

    @Test
    fun `the cancel button sits by subscriptions and trials, not by the rent`() {
        assertFalse(cancelHelpFits(Pay("Оренда квартири", 11_000.0), today))
        assertFalse(cancelHelpFits(Pay("Комуналка", 2_000.0), today))
        assertTrue(cancelHelpFits(Pay("Netflix", 299.0), today))
        assertTrue(cancelHelpFits(Pay("Щось", 99.0), today))
        assertTrue(cancelHelpFits(Pay("Оренда", 1.0, trialEnd = today.plusDays(5).toEpochDay()), today))
        assertFalse(cancelHelpFits(phone, today))
    }

    @Test
    fun `a trial's last reminder carries the cancel page as a button`() {
        val trial = Pay(
            "YouTube Premium", 179.0, day = 20, warnDays = 3,
            trialEnd = LocalDate.of(2026, 10, 20).toEpochDay()
        )
        val message = digest(emptyList(), listOf(trial), emptyList(), LocalDate.of(2026, 10, 17), 0.0, 0.0)
        val action = message.actions.single()
        assertEquals("Як скасувати YouTube Premium", action.label)
        assertEquals("https://www.youtube.com/paid_memberships", action.url)
        // No trial, no button.
        assertTrue(digest(emptyList(), listOf(netflix), emptyList(), LocalDate.of(2026, 10, 26), 0.0, 0.0).actions.isEmpty())
        // The owner's own link is the one the button opens.
        val own = trial.copy(cancelUrl = "https://my.example/yt")
        assertEquals(
            "https://my.example/yt",
            digest(emptyList(), listOf(own), emptyList(), LocalDate.of(2026, 10, 17), 0.0, 0.0).actions.single().url
        )
    }

    @Test
    fun `a plan paid off but not yet at its last payment is still running`() {
        val off = paidOff(phone, today)
        assertEquals(PayLife.RUNNING, lifeOf(off, today))
        assertEquals(PayLife.FINISHED, lifeOf(off, LocalDate.of(2026, 10, 16)))
        assertEquals(1, paymentGroups(listOf(off), today).size)
    }

    // ------------------------------------------------------------ the morning

    @Test
    fun `a lead line opens the message and once lines carry their keys`() {
        val message = digest(
            emptyList(), emptyList(), emptyList(), today, 0.0, 0.0,
            lead = listOf("Перший"),
            once = listOf(OnceLine("k", "Другий"))
        )
        assertEquals("Зведення за день", message.title)
        assertEquals("Перший", message.lines.first())
        assertEquals("Другий", message.lines[1])
        assertEquals(listOf("k"), message.said)
        assertNotNull(message.lines.last())
    }

    @Test
    fun `what was said is remembered, newest kept`() {
        assertEquals(listOf("b", "c", "a"), rememberSaid(listOf("a", "b"), listOf("c", "a")))
        assertEquals(listOf("c", "d"), rememberSaid(listOf("a", "b", "c"), listOf("d"), keep = 2))
        assertEquals(listOf(OnceLine("x", "1")), unsaid(listOf(OnceLine("x", "1"), OnceLine("x", "2"), OnceLine("y", "3")), setOf("y")))
    }
}

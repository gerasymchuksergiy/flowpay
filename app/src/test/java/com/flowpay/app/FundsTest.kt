package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Фонди: funds for annual payments and funds of their own (Funds.kt). The trap
 * every research note warned about is pinned here first: in the charge month the
 * payment still counts at full price, and the part the fund holds is counted back
 * exactly once — before the tick and after it.
 */
class FundsTest {

    /** Sunday, 4 October 2026. */
    private val today = LocalDate.of(2026, 10, 4)

    /** The locale's grouping space is a no-break one; the expectations use a plain one. */
    private fun plain(text: String?) = text?.replace(' ', ' ')?.replace(' ', ' ')

    private val rent = Pay("Оренда", 11_000.0, day = 1)

    /** Charged on 20 October, this month. */
    private val insurance = Pay("Автоцивілка", 6_400.0, day = 20, billingMonth = 10)

    private fun inputs(
        pays: List<Pay> = listOf(rent, insurance),
        funds: List<Fund> = emptyList(),
        marks: List<PaidMark> = emptyList(),
        on: LocalDate = today
    ) = MoneyInputs(on, 40_000.0, pays, marks, funds = funds, usdSell = 41.6)

    private fun insuranceFund(saved: Double, due: String = "2026-10") =
        Fund("f1", "Автоцивілка", payName = "Автоцивілка", goal = 6_400.0, saved = saved, dueMonth = due)

    // ------------------------------------------------------------ stored

    private val everything = Fund(
        id = "f9",
        name = "Автоцивілка",
        emoji = "🛡️",
        goal = 6_400.0,
        saved = 2_140.5,
        monthly = 535.0,
        deadline = 20_605L,
        payName = "Автоцивілка",
        dueMonth = "2027-06",
        coveredMonth = "2026-06",
        covered = 6_100.0,
        cushion = true,
        skipMonth = "2026-10",
        putMonth = "2026-10",
        putAmount = 535.0,
        createdDay = 20_365L
    )

    @Test
    fun `a fund comes back from storage exactly as it went in`() {
        assertEquals(everything, fundOf(fundJson(everything)))
        assertEquals(listOf(everything, everything.copy(id = "f10")), fundsOf(fundsJson(listOf(everything, everything.copy(id = "f10")))))
    }

    @Test
    fun `a fund survives the bin`() {
        val entry = binEntryOf(everything, today.toEpochDay())
        assertEquals(BIN_FUND, entry.kind)
        assertEquals("Фонд", binKindLabel(entry.kind))
        assertEquals(everything, fundOf(JSONObject(entry.payload)))
    }

    @Test
    fun `every key a fund writes is one this feature owns`() {
        val keys = fundJson(everything).keys().asSequence().toList()
        assertTrue(keys.isNotEmpty())
        assertTrue(keys.joinToString(), keys.all { it.startsWith("mp") })
    }

    @Test
    fun `a damaged list reads as nothing and a repeated id as one`() {
        assertEquals(emptyList<Fund>(), fundsOf("not json"))
        assertEquals(emptyList<Fund>(), fundsOf(null))
        assertEquals(1, fundsOf(fundsJson(listOf(everything, everything))).size)
        // A bare object with only a name reads with honest defaults.
        val bare = fundOf(JSONObject("""{"mpn":"Подарунки","mps":"NaN"}"""))
        assertEquals("Подарунки", bare.name)
        assertEquals(0.0, bare.saved, 0.0)
        assertFalse(bare.cushion)
        assertEquals("", bare.payName)
    }

    // ------------------------------------------------------------ the offer

    @Test
    fun `an annual payment is offered a fund once, with the monthly sum its date asks`() {
        val june = Pay("Автоцивілка", 6_400.0, day = 1, billingMonth = 6)
        val offers = fundOffers(listOf(rent, june), emptyList(), emptySet(), today, 41.6)
        assertEquals(1, offers.size)
        val offer = offers.single()
        assertEquals(LocalDate.of(2027, 6, 1), offer.due)
        // Seven whole months from 4 October to 1 June: 6 400 / 7.
        assertEquals(6_400.0 / 7, offer.monthly, 1e-6)
        assertEquals("Автоцивілка 6 400 ₴ · 1 червня — відкладати 915 ₴ на місяць?", plain(fundOfferLine(offer)))
        // Declined, or already with a fund: no offer.
        assertTrue(fundOffers(listOf(june), emptyList(), setOf("Автоцивілка"), today, 41.6).isEmpty())
        val fund = fundFromOffer(offer, today, "f1")
        assertTrue(fundOffers(listOf(june), listOf(fund), emptySet(), today, 41.6).isEmpty())
        assertEquals("2027-06", fund.dueMonth)
        assertEquals("Автоцивілка", fund.payName)
    }

    @Test
    fun `no offer for a charge less than a month away`() {
        // 20 October is sixteen days off: nothing can be spread over that.
        assertTrue(fundOffers(listOf(insurance), emptyList(), emptySet(), today, 41.6).isEmpty())
    }

    @Test
    fun `a dollar payment's fund aims at its hryvnias at the sell rate`() {
        val domain = Pay("Домен", 20.0, day = 14, currency = USD, billingMonth = 3)
        val offer = fundOffers(listOf(domain), emptyList(), emptySet(), today, 41.6).single()
        assertEquals(832.0, offer.goal, 1e-9)
    }

    // ------------------------------------------------------------ the ask

    @Test
    fun `a payment's fund asks what its date demands, nothing in a skipped month, nothing once full`() {
        val june = Pay("Автоцивілка", 6_400.0, day = 1, billingMonth = 6)
        val fund = Fund("f1", "Автоцивілка", payName = "Автоцивілка", dueMonth = "2027-06")
        assertEquals(6_400.0 / 7, fundAsk(fund, june, today, 41.6), 1e-6)
        assertEquals(0.0, fundAsk(skippedFund(fund, today, true), june, today, 41.6), 0.0)
        // The skip is this month's only: on 4 November six months are left.
        assertEquals(6_400.0 / 6, fundAsk(skippedFund(fund, today, true), june, LocalDate.of(2026, 11, 4), 41.6), 1e-6)
        assertEquals(0.0, fundAsk(fund.copy(saved = 6_400.0), june, today, 41.6), 0.0)
    }

    @Test
    fun `a fund of its own asks its monthly sum but never more than the goal still needs`() {
        val car = Fund("f2", "ТО авто", goal = 3_000.0, saved = 2_000.0, monthly = 2_000.0)
        assertEquals(1_000.0, fundAsk(car, null, today, 41.6), 0.0)
        val cushion = Fund("f3", "Подушка", monthly = 2_000.0, cushion = true)
        assertEquals(2_000.0, fundAsk(cushion, null, today, 41.6), 0.0)
        val gifts = Fund("f4", "Подарунки", goal = 5_000.0, deadline = LocalDate.of(2026, 12, 20).toEpochDay())
        // Two whole months to 20 December.
        assertEquals(2_500.0, fundAsk(gifts, null, today, 41.6), 1e-9)
    }

    // ------------------------------------------------------------ the trap

    @Test
    fun `in the charge month the payment shows whole and the fund's part is counted back once`() {
        val without = honestMonth(inputs(pays = listOf(rent)))
        val month = honestMonth(inputs(funds = listOf(insuranceFund(6_400.0))))
        // HANDOFF §12: the annual charge is its whole self in its own month.
        assertEquals(17_400.0, month.payments.total, 0.0)
        assertEquals(6_400.0, month.covered, 0.0)
        // And «Вільно» is not hit a second time for money saved months ago.
        assertEquals(without.free, month.free, 1e-9)
        assertEquals(29_000.0, month.free, 1e-9)
    }

    @Test
    fun `a fund short of the charge lets only the shortfall reach the month`() {
        val month = honestMonth(inputs(funds = listOf(insuranceFund(4_000.0))))
        assertEquals(26_600.0, month.free, 1e-9)
    }

    @Test
    fun `the fund asks nothing in its charge month, so the plans are not hit twice either`() {
        val plan = moneyPlan(inputs(funds = listOf(insuranceFund(4_000.0))))
        assertEquals(0.0, plan.fundPlanned, 0.0)
        assertFalse(plan.conflict)
    }

    @Test
    fun `marking the payment paid does not move the month`() {
        val before = honestMonth(inputs(funds = listOf(insuranceFund(6_400.0))))
        val marks = listOf(PaidMark("Автоцивілка", "2026-10", 6_400.0))
        val after = honestMonth(inputs(funds = listOf(insuranceFund(6_400.0)), marks = marks))
        assertEquals(before.free, after.free, 1e-9)
        val settled = settledFund(insuranceFund(6_400.0), listOf(rent, insurance), marks, today, 41.6)
        assertEquals(0.0, settled.saved, 0.0)
        assertEquals("2026-10", settled.coveredMonth)
        assertEquals(6_400.0, settled.covered, 0.0)
        assertEquals("2027-10", settled.dueMonth)
    }

    @Test
    fun `after the charge the fund starts over for next year`() {
        val marks = listOf(PaidMark("Автоцивілка", "2026-10", 6_400.0))
        val later = LocalDate.of(2026, 10, 25)
        val settled = settledFund(insuranceFund(6_400.0), listOf(rent, insurance), marks, later, 41.6)
        // Eleven whole months from 25 October to 20 October next year.
        val months = monthsUntil(later, LocalDate.of(2027, 10, 20))
        assertEquals(11, months)
        assertEquals(6_400.0 / months, fundAsk(settled, insurance, later, 41.6), 1e-6)
    }

    @Test
    fun `a tick taken back puts the money back in the fund`() {
        val marks = listOf(PaidMark("Автоцивілка", "2026-10", 6_400.0))
        val settled = settledFund(insuranceFund(6_400.0), listOf(rent, insurance), marks, today, 41.6)
        val undone = settledFund(settled, listOf(rent, insurance), emptyList(), today, 41.6)
        assertEquals(6_400.0, undone.saved, 0.0)
        assertEquals("2026-10", undone.dueMonth)
        assertEquals("", undone.coveredMonth)
    }

    @Test
    fun `a charge month that ended unmarked is settled by itself`() {
        val november = LocalDate.of(2026, 11, 2)
        val settled = settledFund(insuranceFund(5_000.0), listOf(rent, insurance), emptyList(), november, 41.6)
        assertEquals(0.0, settled.saved, 0.0)
        assertEquals(5_000.0, settled.covered, 0.0)
        assertEquals("2027-10", settled.dueMonth)
        // And taking nothing back: the month is over.
        assertEquals(settled, settledFund(settled, listOf(rent, insurance), emptyList(), november, 41.6))
    }

    @Test
    fun `the note says what the fund paid and what fell on the month`() {
        val marks = listOf(PaidMark("Автоцивілка", "2026-10", 6_400.0))
        val before = listOf(insuranceFund(4_000.0))
        val after = settledFunds(before, listOf(rent, insurance), marks, today, 41.6)
        assertEquals(
            "Фонд покрив 4 000 з 6 400 ₴ — 2 400 ₴ лягли на жовтень",
            plain(coverageNote(before, after, listOf(rent, insurance), marks, 41.6))
        )
        val full = settledFunds(listOf(insuranceFund(6_400.0)), listOf(rent, insurance), marks, today, 41.6)
        assertEquals(
            "Фонд покрив увесь платіж — 6 400 ₴",
            plain(coverageNote(listOf(insuranceFund(6_400.0)), full, listOf(rent, insurance), marks, 41.6))
        )
        assertNull(coverageNote(before, before, listOf(rent, insurance), marks, 41.6))
    }

    @Test
    fun `the charge the month counts caps what the fund counts back`() {
        // Paid 7 000 against a payment of 6 400: the month counts 6 400, and so does the fund.
        val marks = listOf(PaidMark("Автоцивілка", "2026-10", 7_000.0))
        val settled = settledFund(insuranceFund(9_000.0), listOf(rent, insurance), marks, today, 41.6)
        assertEquals(7_000.0, settled.covered, 0.0)
        assertEquals(6_400.0, fundCoverage(settled, insurance, today, today, 41.6), 0.0)
    }

    @Test
    fun `a payment moved to another month moves its fund's target`() {
        val moved = insurance.copy(billingMonth = 3)
        val settled = settledFund(insuranceFund(1_000.0), listOf(rent, moved), emptyList(), today, 41.6)
        assertEquals("2027-03", settled.dueMonth)
        assertEquals(1_000.0, settled.saved, 0.0)
    }

    @Test
    fun `the weather is told what the funds cover this week`() {
        val soon = insurance.copy(day = 8)
        val cover = weatherCover(listOf(insuranceFund(6_000.0)), listOf(rent, soon), today, 41.6)
        assertEquals(mapOf("Автоцивілка" to 6_000.0), cover)
        val week = moneyWeather(listOf(rent, soon), emptyList(), today, 41.6, 40_000.0, 29_000.0, covered = cover)
        assertEquals(400.0, week.first { it.date.dayOfMonth == 8 }.leaving, 1e-9)
        // Without the cover the same day is a storm of 6 400.
        assertEquals(MoneySky.RAIN.emoji, moneyWeather(listOf(rent, soon), emptyList(), today, 41.6, 40_000.0, 29_000.0).first { it.date.dayOfMonth == 8 }.emoji)
    }

    @Test
    fun `next month's cover is the saved money for a charge due then`() {
        val november = Pay("Страховка", 3_000.0, day = 15, billingMonth = 11)
        val fund = Fund("f5", "Страховка", payName = "Страховка", saved = 2_000.0, dueMonth = "2026-11")
        assertEquals(2_000.0, coveredIn(listOf(fund), listOf(november), LocalDate.of(2026, 11, 1), today, 41.6), 0.0)
        assertEquals(0.0, coveredIn(listOf(fund), listOf(november), today, today, 41.6), 0.0)
    }

    // ------------------------------------------------------------ putting money in

    @Test
    fun `putting money in adds to the fund and remembers this month`() {
        val fund = Fund("f2", "ТО авто", saved = 1_000.0)
        val once = putInto(fund, 500.0, today)
        val twice = putInto(once, 300.0, today)
        assertEquals(1_800.0, twice.saved, 0.0)
        assertEquals(800.0, putThisMonth(twice, today), 0.0)
        assertEquals(0.0, putThisMonth(twice, today.plusMonths(1)), 0.0)
        assertEquals(300.0, putThisMonth(putInto(twice, 300.0, today.plusMonths(1)), today.plusMonths(1)), 0.0)
        assertEquals(fund, putInto(fund, -5.0, today))
        assertEquals(fund, putInto(fund, Double.NaN, today))
    }

    @Test
    fun `a renamed payment takes its fund along, and there is one cushion`() {
        val funds = listOf(insuranceFund(100.0), Fund("f2", "Подушка", cushion = true), Fund("f3", "Подарунки"))
        val renamed = renamedFunds(funds, "Автоцивілка", "ОСЦПВ")
        assertEquals("ОСЦПВ", renamed.first().payName)
        assertEquals("ОСЦПВ", renamed.first().name)
        val moved = withCushion(funds, "f3")
        assertEquals(listOf(false, false, true), moved.map { it.cushion })
    }

    // ------------------------------------------------------------ what it says

    @Test
    fun `a fund says how far it got and what it asks`() {
        val june = Pay("Автоцивілка", 6_400.0, day = 1, billingMonth = 6)
        val fund = Fund("f1", "Автоцивілка", payName = "Автоцивілка", saved = 2_140.0, dueMonth = "2027-06")
        assertEquals("2 140 з 6 400 ₴", plain(fundProgressLine(fund, june, 41.6)))
        assertEquals(2_140f / 6_400f, fundProgress(fund, june, 41.6), 1e-6f)
        assertEquals("до 1 червня · по 609 ₴/міс", plain(fundPlanLine(fund, june, today, 41.6)))
        assertEquals("до 1 червня · пропущено в жовтні", plain(fundPlanLine(skippedFund(fund, today, true), june, today, 41.6)))
        assertEquals("до 1 червня · зібрано повністю", plain(fundPlanLine(fund.copy(saved = 6_400.0), june, today, 41.6)))
        val cushion = Fund("f3", "Подушка", monthly = 2_000.0, cushion = true, saved = 8_000.0)
        assertEquals("по 2 000 ₴/міс", plain(fundPlanLine(cushion, null, today, 41.6)))
        assertEquals("8 000 ₴", plain(fundProgressLine(cushion, null, 41.6)))
        assertEquals("🛟", fundEmoji(cushion, null))
        assertEquals("🫙", fundEmoji(Fund("f6", "Ремонт"), null))
    }

    @Test
    fun `the month of the charge and the one after say what the fund paid`() {
        val settled = insuranceFund(0.0).copy(coveredMonth = "2026-10", covered = 6_400.0, dueMonth = "2027-10")
        assertEquals("У жовтні фонд покрив 6 400 з 6 400 ₴", plain(fundCoveredLine(settled, insurance, today, 41.6)))
        assertEquals("У жовтні фонд покрив 6 400 з 6 400 ₴", plain(fundCoveredLine(settled, insurance, LocalDate.of(2026, 11, 20), 41.6)))
        assertNull(fundCoveredLine(settled, insurance, LocalDate.of(2026, 12, 1), 41.6))
    }

    @Test
    fun `a sum to put aside is whole hryvnias and enough`() {
        assertEquals(915.0, askRounded(6_400.0 / 7), 0.0)
        assertEquals(2_000.0, askRounded(2_000.0), 0.0)
        assertEquals(2_000.0, askRounded(2_000.000001), 0.0)
        assertEquals(0.0, askRounded(-3.0), 0.0)
    }
}

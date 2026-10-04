package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The one «Вільно» and the plans read against it (MoneyPlan.kt): «На життя», a
 * held wish, «Пропустити», the payday and its ritual, the morning lines and
 * «Місяць наперед».
 */
class MoneyPlanTest {

    /** Sunday, 4 October 2026. */
    private val today = LocalDate.of(2026, 10, 4)

    private fun plain(text: String?) = text?.replace(' ', ' ')?.replace(' ', ' ')

    private val rent = Pay("Оренда", 11_000.0, day = 1)
    private val internet = Pay("Інтернет", 300.0, day = 15)

    private fun wish(
        id: String,
        price: Double,
        monthly: Double = 0.0,
        saved: Double = 0.0,
        deadline: LocalDate? = null,
        hold: Long = 0L,
        skip: String = "",
        wins: Int = 0,
        played: Int = 0,
        jar: String = ""
    ) = Wish(
        id = id, name = "Річ $id", url = "https://shop/$id", image = "", price = price,
        history = listOf(PricePoint(price, today.toEpochDay() - 10)), checkedDay = today.toEpochDay(),
        saved = saved, monthlyPlan = monthly, deadline = deadline?.toEpochDay() ?: 0L,
        holdUntil = hold, skipMonth = skip, duelWins = wins, duelsPlayed = played, jar = jar
    )

    private fun inputs(
        income: Double = 40_000.0,
        pays: List<Pay> = listOf(rent, internet),
        wishes: List<Wish> = emptyList(),
        funds: List<Fund> = emptyList(),
        life: LifeCost = LifeCost(),
        payday: Payday = Payday(),
        on: LocalDate = today,
        marks: List<PaidMark> = emptyList(),
        ritual: RitualRecord? = null,
        holidays: Set<Long> = emptySet()
    ) = MoneyInputs(on, income, pays, marks, wishes, funds, 41.6, life, payday, holidays, ritual)

    // ------------------------------------------------------------ «На життя»

    @Test
    fun `with life switched off the month is what it always was`() {
        val month = honestMonth(inputs())
        val old = budget(40_000.0, monthlyTotal(listOf(rent, internet), 41.6, today))
        assertEquals(old, month.asBudget())
        assertEquals(28_700.0, month.free, 0.0)
    }

    @Test
    fun `life switched on comes off the free money and only there`() {
        val month = honestMonth(inputs(life = LifeCost(true, 12_000.0)))
        assertEquals(16_700.0, month.free, 0.0)
        assertEquals(11_300.0, month.payments.total, 0.0)
        assertEquals(23_300.0, month.asBudget().expenses, 0.0)
        // A number without the switch, or the switch without a number, changes nothing.
        assertEquals(28_700.0, honestMonth(inputs(life = LifeCost(false, 12_000.0))).free, 0.0)
        assertEquals(28_700.0, honestMonth(inputs(life = LifeCost(true, 0.0))).free, 0.0)
    }

    @Test
    fun `the widget, the tile and the overview read the one figure`() {
        val plan = moneyPlan(inputs(life = LifeCost(true, 12_000.0)))
        val summary = overview(emptyList(), listOf(rent, internet), emptyList(), 40_000.0, 41.6, today, plan)
        assertEquals(16_700.0, summary.freeCash, 0.0)
        assertEquals(12_000.0, summary.lifeCost, 0.0)
        val widget = widgetSummary(listOf(rent, internet), emptyList(), 40_000.0, 41.6, today, month = plan.month.asBudget())
        assertEquals("Вільно 16 700 ₴", plain(widget.freeCash))
        assertEquals("16 700 ₴", plain(tileFace(TILE_FREE, FxRate(41.2, 41.6), plan.month.asBudget()).label))
        // The digest's closing line, too.
        assertEquals("Вільно 16 700 ₴", plain(freeCashLine(plan.month.asBudget())))
    }

    @Test
    fun `life makes the plans check honest`() {
        val wishes = listOf(wish("a", 30_000.0, monthly = 20_000.0))
        assertFalse(moneyPlan(inputs(wishes = wishes)).conflict)
        val honest = moneyPlan(inputs(wishes = wishes, life = LifeCost(true, 12_000.0)))
        assertTrue(honest.conflict)
        assertEquals(3_300.0, honest.over, 0.0)
        assertEquals(
            "Плани по бажаннях просять 20 000 ₴ на місяць, а вільно після платежів і життя 16 700 ₴. Не вистачає 3 300 ₴.",
            plain(plansConflictLine(honest))
        )
    }

    @Test
    fun `the treat is chosen from the free money after life and the plans`() {
        val cheap = wish("t", 9_000.0)
        val plan = moneyPlan(inputs(wishes = listOf(cheap, wish("a", 50_000.0, monthly = 5_000.0)), life = LifeCost(true, 12_000.0)))
        // 16 700 free − 5 000 planned = 11 700; with a fifth to spare, 9 360 — the 9 000 fits.
        assertEquals(11_700.0, plan.treatBudget, 0.0)
        assertEquals("t", monthTreat(listOf(cheap), plan.treatBudget, today.toEpochDay())?.wish?.id)
        val tighter = moneyPlan(inputs(wishes = listOf(cheap, wish("a", 50_000.0, monthly = 6_000.0)), life = LifeCost(true, 12_000.0)))
        assertNull(monthTreat(listOf(cheap), tighter.treatBudget, today.toEpochDay()))
    }

    // ------------------------------------------------------------ a held wish, a skipped month

    @Test
    fun `a wish put aside asks nothing of the month`() {
        val held = wish("h", 20_000.0, monthly = 30_000.0, hold = today.toEpochDay() + 10)
        assertEquals(0.0, plannedMonthly(held, today), 0.0)
        assertEquals(30_000.0, wishAsk(held, today), 0.0)
        val summary = overview(listOf(held), listOf(rent, internet), emptyList(), 40_000.0, 41.6, today)
        assertFalse(summary.plansConflict)
        assertEquals(0.0, summary.plannedMonthly, 0.0)
        // The hold's day comes: the plan is back.
        assertEquals(30_000.0, plannedMonthly(held, today.plusDays(10)), 0.0)
    }

    @Test
    fun `a skipped month asks nothing and the 1st brings the plan back`() {
        val skipped = skippedWish(wish("s", 20_000.0, monthly = 2_000.0), today, true)
        assertEquals("2026-10", skipped.skipMonth)
        assertEquals(0.0, plannedMonthly(skipped, today), 0.0)
        assertEquals(2_000.0, plannedMonthly(skipped, LocalDate.of(2026, 11, 1)), 0.0)
        val ask = moneyPlan(inputs(wishes = listOf(skipped))).asks.single()
        assertTrue(ask.skipped)
        assertEquals(0.0, ask.monthly, 0.0)
        assertEquals(2_000.0, ask.wouldAsk, 0.0)
        assertEquals("", skippedWish(skipped, today, false).skipMonth)
        assertEquals("Пропущено в жовтні · з 1 листопада знову", skippedLine(today))
    }

    @Test
    fun `the skip and the rest of a wish survive the backup`() {
        val w = wish("k", 3_000.0, monthly = 500.0, skip = "2026-10", wins = 2, played = 5, jar = "jar1")
        assertEquals(w, wishOf(wishJson(w)))
        val old = JSONObject(wishJson(w).toString()).apply { remove("mpsk") }
        assertEquals("", wishOf(old).skipMonth)
    }

    @Test
    fun `the skip names the least wanted plan, or the furthest one without duels`() {
        val loved = wish("l", 10_000.0, monthly = 1_000.0, wins = 4, played = 5)
        val meh = wish("m", 10_000.0, monthly = 1_000.0, wins = 0, played = 4)
        val near = wish("n", 3_000.0, deadline = LocalDate.of(2026, 12, 20))
        assertEquals("m", skipCandidate(moneyPlan(inputs(wishes = listOf(loved, meh, near))))?.id)
        val far = wish("f", 50_000.0, monthly = 2_000.0)
        assertEquals("f", skipCandidate(moneyPlan(inputs(wishes = listOf(near, far))))?.id)
        assertNull(skipCandidate(moneyPlan(inputs())))
    }

    @Test
    fun `skipping a plan by date says what the months after will ask`() {
        val dated = wish("d", 6_000.0, saved = 1_000.0, deadline = LocalDate.of(2027, 3, 1))
        val ask = wishPlanAsk(dated, today)!!
        // Four whole months to 1 March: 5 000 / 4.
        assertEquals(1_250.0, ask.monthly, 1e-9)
        // From 4 November, three: 5 000 / 3.
        assertEquals("з листопада по 1 667 ₴ замість 1 250 ₴", plain(skipPrice(ask, today)))
        val close = wishPlanAsk(wish("c", 6_000.0, deadline = LocalDate.of(2026, 11, 20)), today)!!
        assertEquals("до 20 листопада лишиться менше місяця — 6 000 ₴ доведеться знайти одразу", plain(skipPrice(close, today)))
    }

    @Test
    fun `skipping a plan by sum moves its goal by a month`() {
        val bySum = wishPlanAsk(wish("b", 10_000.0, monthly = 2_000.0), today)!!
        assertEquals(LocalDate.of(2027, 3, 4), bySum.ready)
        assertEquals("ціль зсунеться з березня на квітень", skipPrice(bySum, today))
        val year = wishPlanAsk(wish("y", 6_000.0, monthly = 2_000.0), today)!!
        assertEquals("ціль зсунеться з січня на лютий", skipPrice(year, today))
        assertEquals("з грудня 2026 на січень 2027", monthShift(LocalDate.of(2026, 12, 4), LocalDate.of(2027, 1, 4)))
    }

    // ------------------------------------------------------------ the payday

    @Test
    fun `a payday on a weekend comes on the working day before`() {
        // 25 October 2026 is a Sunday.
        assertEquals(listOf(PaydayDate(LocalDate.of(2026, 10, 23), true)), paydaysIn(Payday(25), today, emptySet()))
        // And a holiday pushes it back further.
        val holiday = setOf(LocalDate.of(2026, 10, 23).toEpochDay())
        assertEquals(LocalDate.of(2026, 10, 22), paydaysIn(Payday(25), today, holiday).single().date)
        // The 31st in a 30-day month is the 30th.
        assertEquals(LocalDate.of(2026, 11, 30), paydaysIn(Payday(31), LocalDate.of(2026, 11, 2), emptySet()).single().date)
    }

    @Test
    fun `the last working day skips the weekend`() {
        // 31 October 2026 is a Saturday.
        assertEquals(LocalDate.of(2026, 10, 30), paydaysIn(Payday(LAST_WORKING_DAY), today, emptySet()).single().date)
    }

    @Test
    fun `an advance and a salary are both paydays and only the salary carries the plans`() {
        val both = paydaysIn(Payday(25, 10), today, emptySet())
        // 10 October is a Saturday.
        assertEquals(listOf(LocalDate.of(2026, 10, 9), LocalDate.of(2026, 10, 23)), both.map { it.date })
        assertEquals(listOf(false, true), both.map { it.salary })
        assertEquals(LocalDate.of(2026, 9, 25), lastSalaryDay(Payday(25, 10), today, emptySet()))
        assertFalse(Payday(0, 10).hasAdvance)
        assertTrue(paydaysIn(Payday(), today, emptySet()).isEmpty())
    }

    @Test
    fun `the countdown says what comes next`() {
        assertEquals("до зарплати ще 19 днів", paydayCountdown(Payday(25), today, emptySet()))
        assertEquals("до авансу ще 5 днів", paydayCountdown(Payday(25, 10), today, emptySet()))
        assertEquals("зарплата сьогодні", paydayCountdown(Payday(25), LocalDate.of(2026, 10, 23), emptySet()))
        assertEquals("зарплата завтра", paydayCountdown(Payday(25), LocalDate.of(2026, 10, 22), emptySet()))
        assertEquals("аванс сьогодні", paydayCountdown(Payday(25, 10), LocalDate.of(2026, 10, 9), emptySet()))
        assertNull(paydayCountdown(Payday(), today, emptySet()))
    }

    @Test
    fun `a payday reads plainly in the settings`() {
        assertEquals("не вказано", paydayLabel(Payday()))
        assertEquals("25 числа", paydayLabel(Payday(25)))
        assertEquals("останній робочий день", paydayLabel(Payday(LAST_WORKING_DAY)))
        assertEquals("аванс 10, зарплата 25 числа", paydayLabel(Payday(25, 10)))
        assertEquals("аванс 15 числа, зарплата в останній робочий день", paydayLabel(Payday(LAST_WORKING_DAY, 15)))
    }

    // ------------------------------------------------------------ the ritual

    @Test
    fun `the ritual waits five days after a salary, or the first five of the month without one`() {
        val payday = Payday(25)
        assertEquals(LocalDate.of(2026, 10, 23), ritualAnchor(payday, LocalDate.of(2026, 10, 23), emptySet()))
        assertEquals(LocalDate.of(2026, 10, 23), ritualAnchor(payday, LocalDate.of(2026, 10, 27), emptySet()))
        assertNull(ritualAnchor(payday, LocalDate.of(2026, 10, 28), emptySet()))
        assertNull(ritualAnchor(payday, LocalDate.of(2026, 10, 22), emptySet()))
        // No payday: the 1st of the month.
        assertEquals(LocalDate.of(2026, 10, 1), ritualAnchor(Payday(), today, emptySet()))
        assertNull(ritualAnchor(Payday(), LocalDate.of(2026, 10, 6), emptySet()))
        // The advance does not open one.
        assertNull(ritualAnchor(Payday(25, 10), LocalDate.of(2026, 10, 9), emptySet()))
    }

    @Test
    fun `plans that do not fit are cut by the same share and fit`() {
        assertEquals(listOf(1_000.0, 500.0), proportional(listOf(2_000.0, 1_000.0), 1_500.0))
        val cut = proportional(listOf(1_333.0, 667.0, 915.0), 1_000.0)
        assertTrue(cut.sum() <= 1_000.0)
        assertEquals(listOf(2_000.0, 1_000.0), proportional(listOf(2_000.0, 1_000.0), 9_000.0))
        assertEquals(listOf(0.0, 0.0), proportional(listOf(2_000.0, 1_000.0), -50.0))
    }

    @Test
    fun `on payday the ritual lists what is due before the next one and each plan`() {
        val payday = Payday(25)
        val on = LocalDate.of(2026, 10, 23)
        val pays = listOf(rent, internet, Pay("Netflix", 299.0, day = 28))
        val wishes = listOf(wish("a", 30_000.0, monthly = 2_000.0))
        val funds = listOf(Fund("f", "Подушка", monthly = 1_000.0, cushion = true))
        val input = inputs(pays = pays, wishes = wishes, funds = funds, payday = payday, on = on)
        val plan = moneyPlan(input)
        val ritual = ritualFor(input, plan)!!
        assertEquals(on, ritual.anchor)
        assertEquals("Зарплата сьогодні · наступна 25 листопада", ritualHeading(ritual, on))
        // Netflix on the 28th and the rent on the 1st come before 25 November;
        // the internet on the 15th does too.
        assertEquals(listOf("Netflix", "Оренда", "Інтернет"), ritual.charges.map { it.pay.name })
        assertEquals("До 25 листопада спишеться 11 599 ₴ · 3 платежі", plain(ritualPaymentsLine(ritual)))
        assertEquals(listOf(2_000.0, 1_000.0), ritual.lines.map { it.proposed })
        assertFalse(ritual.scaled)
        assertEquals("Відкладаєте 3 000 ₴ — з вільних лишиться 25 401 ₴", plain(ritualSummary(3_000.0, ritual.free, true)))
    }

    @Test
    fun `when the plans do not fit the ritual proposes smaller sums that do`() {
        val wishes = listOf(wish("a", 90_000.0, monthly = 20_000.0), wish("b", 90_000.0, monthly = 20_000.0))
        val input = inputs(wishes = wishes, payday = Payday(25), on = LocalDate.of(2026, 10, 23))
        val ritual = ritualFor(input, moneyPlan(input))!!
        assertTrue(ritual.scaled)
        assertTrue(ritual.lines.sumOf { it.proposed } <= ritual.free)
        assertEquals(listOf(20_000.0, 20_000.0), ritual.lines.map { it.full })
        assertEquals("Відкладаєте 40 000 ₴ — це на 11 300 ₴ більше, ніж вільно", plain(ritualSummary(40_000.0, 28_700.0, true)))
    }

    @Test
    fun `«Не зараз» hides the ritual and «Я відклав» leaves it saying what was put aside`() {
        val on = LocalDate.of(2026, 10, 23)
        val input = inputs(wishes = listOf(wish("a", 30_000.0, monthly = 2_000.0)), payday = Payday(25), on = on)
        val plan = moneyPlan(input)
        assertNull(ritualFor(input.copy(ritual = RitualRecord(on.toEpochDay(), false, on.toEpochDay())), plan))
        val done = RitualRecord(on.toEpochDay(), true, on.toEpochDay(), listOf(RitualEntry(PlanKind.WISH, "a", 2_000.0)))
        assertTrue(ritualFor(input.copy(ritual = done), plan)!!.done)
        assertEquals("Відкладено 2 000 ₴ 23 жовтня", plain(ritualDoneLine(done)))
        // Last month's record says nothing about this month's ritual.
        val old = RitualRecord(LocalDate.of(2026, 9, 25).toEpochDay(), false, 0L)
        assertFalse(ritualFor(input.copy(ritual = old), plan)!!.done)
        // Nothing to put aside, nothing to show.
        assertNull(ritualFor(inputs(payday = Payday(25), on = on), moneyPlan(inputs(payday = Payday(25), on = on))))
    }

    @Test
    fun `«Я відклав» adds each sum to its wish or fund, leaves a jar alone, and undoes`() {
        val on = LocalDate.of(2026, 10, 23)
        val wishes = listOf(wish("a", 30_000.0, monthly = 2_000.0, saved = 500.0), wish("j", 9_000.0, monthly = 1_000.0, jar = "jar1"))
        val funds = listOf(Fund("f", "Подушка", monthly = 1_000.0, saved = 3_000.0))
        val plan = moneyPlan(inputs(wishes = wishes, funds = funds, on = on))
        val chosen = plan.active.map { it to askRounded(it.monthly) }
        val (w, f, record) = applyRitual(wishes, funds, chosen, on, on)
        assertEquals(2_500.0, w.first { it.id == "a" }.saved, 0.0)
        assertEquals(0.0, w.first { it.id == "j" }.saved, 0.0)
        assertEquals(4_000.0, f.single().saved, 0.0)
        assertEquals(1_000.0, putThisMonth(f.single(), on), 0.0)
        assertEquals(4_000.0, record.total, 0.0)
        val (backW, backF) = undoRitual(w, f, record)
        assertEquals(wishes, backW)
        assertEquals(funds.single().saved, backF.single().saved, 0.0)
        assertEquals(0.0, putThisMonth(backF.single(), on), 0.0)
        assertEquals(record, ritualOf(ritualJson(record)))
        assertNull(ritualOf("garbage"))
        assertNull(ritualOf(null))
    }

    @Test
    fun `money put aside this month still counts as this month's, so the treat does not get it twice`() {
        val on = LocalDate.of(2026, 10, 23)
        // 2 000 more reaches the goal: after «Я відклав» the plan asks nothing more.
        val wishes = listOf(wish("a", 3_000.0, monthly = 2_000.0, saved = 1_000.0))
        val funds = listOf(Fund("f", "ТО авто", goal = 3_000.0, saved = 2_000.0, monthly = 2_000.0))
        val before = moneyPlan(inputs(wishes = wishes, funds = funds, payday = Payday(25), on = on))
        assertEquals(3_000.0, before.planned, 0.0)
        val chosen = before.pending.map { it to askRounded(it.left) }
        val (w, f, record) = applyRitual(wishes, funds, chosen, on, on)
        val after = moneyPlan(inputs(wishes = w, funds = f, payday = Payday(25), on = on, ritual = record))
        // Still this month's 3 000 in the check and off the treat...
        assertEquals(3_000.0, after.planned, 0.0)
        assertEquals(before.treatBudget, after.treatBudget, 0.0)
        // ...and nothing left to ask for.
        assertTrue(after.pending.isEmpty())
        assertNull(skipCandidate(after))
        // Next month the reached goals ask nothing.
        val november = moneyPlan(inputs(wishes = w, funds = f, payday = Payday(25), on = LocalDate.of(2026, 11, 2), ritual = record))
        assertEquals(0.0, november.planned, 0.0)
    }

    // ------------------------------------------------------------ the morning message

    @Test
    fun `the eve of a salary says what the plan puts aside tomorrow`() {
        val wishes = listOf(wish("a", 30_000.0, monthly = 2_000.0))
        val funds = listOf(Fund("f", "Подушка", monthly = 1_000.0))
        val eve = inputs(wishes = wishes, funds = funds, payday = Payday(25), on = LocalDate.of(2026, 10, 22))
        assertEquals(listOf("Завтра зарплата — за планом 3 000 ₴ на бажання і фонди"), planDigestLines(eve, moneyPlan(eve)).map { plain(it) })
        val wishesOnly = eve.copy(funds = emptyList())
        assertEquals(listOf("Завтра зарплата — за планом 2 000 ₴ на бажання"), planDigestLines(wishesOnly, moneyPlan(wishesOnly)).map { plain(it) })
        val notEve = eve.copy(today = LocalDate.of(2026, 10, 21))
        assertTrue(planDigestLines(notEve, moneyPlan(notEve)).isEmpty())
        // The advance's eve says nothing.
        val advance = eve.copy(payday = Payday(25, 10), today = LocalDate.of(2026, 10, 8))
        assertTrue(planDigestLines(advance, moneyPlan(advance)).isEmpty())
    }

    @Test
    fun `without a payday the 1st asks for the funds once`() {
        val funds = listOf(Fund("f", "Подушка", monthly = 1_000.0), Fund("g", "ТО авто", monthly = 240.0))
        val first = inputs(funds = funds, on = LocalDate.of(2026, 11, 1))
        assertEquals(listOf("Час відкласти у фонди: 1 240 ₴"), planDigestLines(first, moneyPlan(first)).map { plain(it) })
        val put = first.copy(funds = listOf(putInto(funds[0], 1_000.0, LocalDate.of(2026, 11, 1)), funds[1]))
        assertEquals(listOf("Час відкласти у фонди: 240 ₴"), planDigestLines(put, moneyPlan(put)).map { plain(it) })
        val second = first.copy(today = LocalDate.of(2026, 11, 2))
        assertTrue(planDigestLines(second, moneyPlan(second)).isEmpty())
        val withPayday = first.copy(payday = Payday(25))
        assertTrue(planDigestLines(withPayday, moneyPlan(withPayday)).isEmpty())
    }

    // ------------------------------------------------------------ «Місяць наперед»

    /** A trial that runs out on 20 October, charged on the 10th of each month: free in October, paid in November. */
    private val netflix = Pay("Netflix", 300.0, day = 10, trialEnd = LocalDate.of(2026, 10, 20).toEpochDay())
    private val insurance = Pay("Страховка", 6_000.0, day = 15, billingMonth = 11)

    @Test
    fun `next month is judged by its own charge dates, not by today`() {
        val pays = listOf(rent, netflix, insurance)
        val november = LocalDate.of(2026, 11, 1)
        // The trap: asked about November, monthlyTotal judges the trial against today.
        assertEquals(17_000.0, monthlyTotal(pays, 41.6, today, november).total, 0.0)
        assertEquals(17_300.0, monthCostAt(pays, november, 41.6, today).total, 0.0)
    }

    @Test
    fun `the ring is the cushion over next month's payments`() {
        val pays = listOf(rent, netflix, insurance)
        val cushion = Fund("c", "Подушка", saved = 8_000.0, cushion = true)
        val input = inputs(pays = pays, funds = listOf(cushion))
        val ahead = monthAhead(input, moneyPlan(input))!!
        assertEquals(17_300.0, ahead.payments, 0.0)
        assertEquals(46, ahead.percent)
        assertEquals("Платежі листопада покриті на 46%", monthAheadHeadline(ahead))
        assertNull(cushionDaysLine(ahead))
        assertEquals(
            "Листопад дорожчий на 6 300 ₴: закінчується пробний період «Netflix», річний платіж «Страховка»",
            plain(dearerLine(ahead))
        )
        assertEquals("подушка 8 000 ₴ з 17 300 ₴ платежів", plain(monthAheadDetail(ahead)))
    }

    @Test
    fun `with life on the whole month is covered, and the cushion counts days`() {
        val pays = listOf(rent, netflix, insurance)
        val cushion = Fund("c", "Подушка", saved = 8_000.0, cushion = true)
        val input = inputs(pays = pays, funds = listOf(cushion), life = LifeCost(true, 12_000.0))
        val ahead = monthAhead(input, moneyPlan(input))!!
        // 8 000 of 29 300.
        assertEquals(27, ahead.percent)
        assertEquals("Листопад покрито на 27%", monthAheadHeadline(ahead))
        // 29 300 over 30 days is 976,67 a day: 8 000 lasts 8 days.
        assertEquals("Подушка = 8 днів", cushionDaysLine(ahead))
        assertEquals("подушка 8 000 ₴ з 29 300 ₴: платежі 17 300 ₴ + життя 12 000 ₴", plain(monthAheadDetail(ahead)))
    }

    @Test
    fun `a fund for next month's annual charge counts as already paid`() {
        val pays = listOf(rent, insurance)
        val funds = listOf(
            Fund("c", "Подушка", saved = 5_000.0, cushion = true),
            Fund("s", "Страховка", payName = "Страховка", saved = 6_000.0, dueMonth = "2026-11")
        )
        val input = inputs(pays = pays, funds = funds)
        val ahead = monthAhead(input, moneyPlan(input))!!
        assertEquals(6_000.0, ahead.covered, 0.0)
        assertEquals(64, ahead.percent)
        assertEquals("подушка 5 000 ₴ + фонди 6 000 ₴ з 17 000 ₴ платежів", plain(monthAheadDetail(ahead)))
    }

    @Test
    fun `a month fully paid says so calmly, and there is no ring without a cushion`() {
        val cushion = Fund("c", "Подушка", saved = 50_000.0, cushion = true)
        val input = inputs(funds = listOf(cushion))
        assertEquals("Наступний місяць уже оплачено", monthAheadHeadline(monthAhead(input, moneyPlan(input))!!))
        assertNull(monthAhead(inputs(), moneyPlan(inputs())))
    }

    // ------------------------------------------------------------ what the screens say

    @Test
    fun `the hero says what «Вільно» is made of`() {
        val bare = honestMonth(inputs())
        assertEquals("Вільно до кінця місяця", heroLabel(bare))
        assertEquals("Постійні витрати 11 300 ₴ з 40 000 ₴", plain(heroCaption(bare, null)))
        val life = honestMonth(inputs(life = LifeCost(true, 12_000.0)))
        assertEquals("Вільно після платежів і життя", heroLabel(life))
        assertEquals(
            "Платежі 11 300 ₴ · на життя 12 000 ₴ · змінити\nДо зарплати ще 19 днів",
            plain(heroCaption(life, paydayCountdown(Payday(25), today, emptySet())))
        )
        assertEquals("Не сходиться цього місяця", heroLabel(honestMonth(inputs(life = LifeCost(true, 40_000.0)))))
        assertEquals("Вкажіть дохід на Платежах", heroLabel(honestMonth(inputs(income = 0.0))))
        val insured = Pay("Автоцивілка", 6_400.0, day = 20, billingMonth = 10)
        val fund = Fund("f", "Автоцивілка", payName = "Автоцивілка", saved = 6_400.0, dueMonth = "2026-10")
        val covered = honestMonth(inputs(pays = listOf(rent, internet, insured), funds = listOf(fund)))
        assertEquals("Постійні витрати 11 300 ₴ з 40 000 ₴\nЗ фондів 6 400 ₴", plain(heroCaption(covered, null)))
        assertEquals(
            "Постійні витрати 11 300 ₴ з 40 000 ₴\nЗ фондів 6 400 ₴ · до зарплати ще 19 днів",
            plain(heroCaption(covered, paydayCountdown(Payday(25), today, emptySet())))
        )
    }

    @Test
    fun `the bar on Платежі gets life as its own part`() {
        val life = honestMonth(inputs(life = LifeCost(true, 12_000.0)))
        assertEquals("Платежі 11 300 ₴ · 🛒 життя 12 000 ₴ з 40 000 ₴", plain(monthBarDetail(life)))
        assertEquals(0.3f, lifeShare(life), 1e-6f)
        assertNull(monthBarDetail(honestMonth(inputs())))
        assertEquals(0f, lifeShare(honestMonth(inputs())), 0f)
        assertNull(monthBarDetail(honestMonth(inputs(income = 0.0, life = LifeCost(true, 12_000.0)))))
    }

    @Test
    fun `the settings rows say what is set`() {
        assertEquals("Вимкнено · «Вільно» рахується без їжі й дороги", lifeRowDetail(LifeCost()))
        assertEquals("12 000 ₴ на місяць · «Вільно» — після платежів і життя", plain(lifeRowDetail(LifeCost(true, 12_000.0))))
        assertEquals("40 000 ₴ на місяць · зарплата: 25 числа", plain(incomeRowDetail(40_000.0, Payday(25))))
        assertEquals("Дохід не вказано · день зарплати не вказано", incomeRowDetail(0.0, Payday()))
        assertEquals("Найближча зарплата — 23 жовтня", nextPaydayNote(Payday(25), today, emptySet()))
        assertEquals("Найближча виплата авансу — 9 жовтня", nextPaydayNote(Payday(25, 10), today, emptySet()))
        assertNull(nextPaydayNote(Payday(), today, emptySet()))
    }

    @Test
    fun `the funds tile sums what is saved and what this month asks`() {
        val june = Pay("Автоцивілка", 6_400.0, day = 1, billingMonth = 6)
        val funds = listOf(
            Fund("f", "Автоцивілка", payName = "Автоцивілка", saved = 2_140.0, dueMonth = "2027-06"),
            Fund("c", "Подушка", monthly = 1_000.0, saved = 3_000.0, goal = 10_000.0)
        )
        val input = inputs(pays = listOf(rent, june), funds = funds)
        val plan = moneyPlan(input)
        // 609 for the insurance (4 260 over 7 months), 1 000 for the cushion.
        assertEquals("У фондах 5 140 ₴ · цього місяця відкласти 1 609 ₴", plain(fundsSummary(plan)))
    }

    @Test
    fun `the morning message carries the plan's lines and the one «Вільно»`() {
        val eve = inputs(
            wishes = listOf(wish("a", 30_000.0, monthly = 2_000.0)),
            payday = Payday(25),
            on = LocalDate.of(2026, 10, 22),
            life = LifeCost(true, 12_000.0)
        )
        val plan = moneyPlan(eve)
        val message = digest(
            emptyList(), eve.pays, emptyList(), eve.today, 41.6, eve.income,
            planLines = planDigestLines(eve, plan),
            month = plan.month.asBudget()
        )
        assertEquals("Завтра зарплата — за планом 2 000 ₴ на бажання", plain(message.title))
        assertEquals(listOf("Вільно 16 700 ₴"), message.lines.map { plain(it) })
    }

    // ------------------------------------------------------------ dates said plainly

    @Test
    fun `days and weekends are said the way a person would`() {
        assertEquals("Сьогодні", dayWords(today, today))
        assertEquals("Завтра", dayWords(today.plusDays(1), today))
        assertEquals("Субота, 10 жовтня", dayWords(LocalDate.of(2026, 10, 10), today))
        // Sunday is the weekend already; a Wednesday looks ahead to Saturday.
        assertEquals(today, thisWeekend(today))
        assertEquals(LocalDate.of(2026, 10, 10), thisWeekend(LocalDate.of(2026, 10, 7)))
    }
}

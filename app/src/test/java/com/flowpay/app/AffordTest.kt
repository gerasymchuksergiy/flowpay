package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * «Чи потягну?», «Скільки можна сьогодні» and «Купив частинами» (Afford.kt):
 * every figure from the data and one balance.
 */
class AffordTest {

    /** Sunday, 4 October 2026. The next salary, on the 25th, comes on Friday the 23rd. */
    private val today = LocalDate.of(2026, 10, 4)

    private fun plain(text: String?) = text?.replace(' ', ' ')?.replace(' ', ' ')

    private val internet = Pay("Інтернет", 300.0, day = 15)
    private val netflix = Pay("Netflix", 299.0, day = 28)
    private val rent = Pay("Оренда", 11_000.0, day = 1)
    private val domain = Pay("Домен", 500.0, day = 20, billingMonth = 10)
    private val insurance = Pay("Автоцивілка", 6_400.0, day = 15, billingMonth = 11)
    private val pays = listOf(internet, netflix, rent, domain, insurance)

    private fun wish(id: String, price: Double, monthly: Double = 0.0, saved: Double = 0.0, deadline: LocalDate? = null, wins: Int = 0, played: Int = 0) =
        Wish(
            id = id, name = "Річ $id", url = "https://shop/$id", image = "", price = price,
            history = listOf(PricePoint(price, today.toEpochDay() - 10)), checkedDay = today.toEpochDay(),
            saved = saved, monthlyPlan = monthly, deadline = deadline?.toEpochDay() ?: 0L,
            duelWins = wins, duelsPlayed = played
        )

    private fun inputs(
        wishes: List<Wish> = listOf(wish("a", 30_000.0, monthly = 2_000.0)),
        payList: List<Pay> = pays,
        funds: List<Fund> = emptyList(),
        life: LifeCost = LifeCost(),
        payday: Payday = Payday(25),
        marks: List<PaidMark> = emptyList(),
        ritual: RitualRecord? = null
    ) = MoneyInputs(today, 40_000.0, payList, marks, wishes, funds, 41.6, life, payday, emptySet(), ritual)

    private fun check(input: MoneyInputs, price: Double, balance: Double?, date: LocalDate = today, treat: Treat? = null, buying: String? = null) =
        affordability(input, moneyPlan(input), price, date, balance, treat, buying)

    // ------------------------------------------------------------ «Скільки можна сьогодні»

    @Test
    fun `the card's money less what is due before payday and this period's plans, per day`() {
        val input = inputs()
        val a = allowance(input, moneyPlan(input), 18_400.0)
        assertEquals(LocalDate.of(2026, 10, 23), a.period.until)
        assertEquals(19, a.days)
        // The internet on the 15th and the annual domain on the 20th fall inside;
        // Netflix on the 28th, the rent on the 1st and November's insurance do not.
        assertEquals(listOf("Інтернет", "Домен"), a.charges.map { it.pay.name })
        assertEquals(800.0, a.payments, 0.0)
        assertEquals(2_000.0, a.plans, 0.0)
        assertEquals(15_600.0, a.left, 0.0)
        assertEquals("Можна ~820 ₴ на день", plain(allowanceHeadline(a)))
        assertEquals("до зарплати 19 днів", allowanceWhen(a))
        assertEquals("на картках 18 400 ₴ − 2 платежі 800 ₴ − внески 2 000 ₴", plain(allowanceDetail(a, 0L)))
        assertTrue(allowanceDetail(a, 1_000L).contains(" · оновлено "))
    }

    @Test
    fun `once «Я відклав» was pressed the plans are no longer on the card`() {
        val done = RitualRecord(LocalDate.of(2026, 9, 25).toEpochDay(), true, 0L)
        val input = inputs(ritual = done)
        val a = allowance(input, moneyPlan(input), 18_400.0)
        assertEquals(0.0, a.plans, 0.0)
        assertEquals("на картках 18 400 ₴ − 2 платежі 800 ₴ · внески вже відкладено", plain(allowanceDetail(a, 0L)))
    }

    @Test
    fun `a payment marked paid is not counted again, nor a fund put aside this month`() {
        val funds = listOf(putInto(Fund("f", "Подушка", monthly = 1_000.0), 1_000.0, today))
        val input = inputs(marks = listOf(PaidMark("Інтернет", "2026-10", 300.0)), funds = funds)
        val plan = moneyPlan(input)
        val a = allowance(input, plan, 18_400.0)
        assertEquals(500.0, a.payments, 0.0)
        assertEquals(2_000.0, a.plans, 0.0)
        assertEquals(listOf("a"), pendingAsks(input, plan).map { it.id })
    }

    @Test
    fun `an annual charge inside the period counts only the part its fund does not hold`() {
        val funds = listOf(Fund("d", "Домен", payName = "Домен", saved = 400.0, dueMonth = "2026-10"))
        val input = inputs(funds = funds)
        val a = allowance(input, moneyPlan(input), 18_400.0)
        assertEquals(400.0, a.payments, 0.0)
    }

    @Test
    fun `a card that cannot cover the period says what it lacks`() {
        val input = inputs()
        val plan = moneyPlan(input)
        assertEquals("До зарплати не вистачає 2 300 ₴ на платежі", plain(allowanceHeadline(allowance(input, plan, 500.0))))
        assertEquals("До зарплати не вистачає 800 ₴ на платежі й внески", plain(allowanceHeadline(allowance(input, plan, 2_000.0))))
    }

    // ------------------------------------------------------------ «Чи потягну?» by balance

    @Test
    fun `a purchase that fits says so, with the day's money before and after`() {
        val result = check(inputs(), 1_000.0, 18_400.0)
        assertEquals(AffordVerdict.FITS, result.verdict)
        assertEquals("Влазить", result.headline)
        assertTrue(result.byBalance)
        assertNull(result.basis)
        assertEquals("19 днів: ≈820 → 760 ₴ на день", plain(result.perDay))
        assertTrue(result.levers.isEmpty())
    }

    @Test
    fun `when it eats into the plans the least wanted one gives way and says by how much`() {
        val result = check(inputs(), 16_500.0, 18_400.0)
        assertEquals(AffordVerdict.PLANS_MOVE, result.verdict)
        assertEquals(900.0, result.shortfall, 1e-9)
        assertEquals("Влазить, але «Річ a» зсунеться на 2 тижні", result.headline)
        assertEquals("19 днів: ≈820 → 0 ₴ на день", plain(result.perDay))
        assertEquals(listOf("Купити 24 жовтня, після зарплати"), result.levers.map { it.text })
    }

    @Test
    fun `plans give way least wanted first, then the furthest off, funds last`() {
        val lo = wish("lo", 20_000.0, monthly = 1_000.0, wins = 0, played = 4)
        val hi = wish("hi", 20_000.0, monthly = 1_000.0, wins = 4, played = 5)
        val result = check(inputs(wishes = listOf(hi, lo)), 17_100.0, 18_400.0)
        assertEquals(listOf("lo" to 1_000.0, "hi" to 500.0), result.moves.map { it.ask.id to it.taken })
        assertEquals("Влазить, але зсунуться плани: «Річ lo», «Річ hi»", result.headline)
        val near = wish("n", 3_000.0, deadline = LocalDate.of(2026, 12, 20))
        val far = wish("f", 50_000.0, monthly = 2_000.0)
        val fund = Fund("c", "Подушка", monthly = 1_000.0)
        val order = givingWayOrder(moneyPlan(inputs(wishes = listOf(near, far, hi), funds = listOf(fund))).active)
        assertEquals(listOf("hi", "f", "n", "c"), order.map { it.id })
    }

    @Test
    fun `a plan by date that gives way asks more from next month`() {
        val dated = wish("d", 6_000.0, saved = 1_000.0, deadline = LocalDate.of(2027, 3, 1))
        val result = check(inputs(wishes = listOf(dated)), 16_850.0, 18_400.0)
        assertEquals(AffordVerdict.PLANS_MOVE, result.verdict)
        assertEquals("Влазить, але «Річ d» з листопада проситиме 1 417 ₴ замість 1 250 ₴", plain(result.headline))
    }

    @Test
    fun `when the payments are not covered it says so, with what would help`() {
        val megogo = Pay("Megogo", 199.0, day = 12, trialEnd = LocalDate.of(2026, 10, 12).toEpochDay())
        val result = check(inputs(payList = pays + megogo), 18_000.0, 18_400.0)
        assertEquals(AffordVerdict.SHORT_PAYMENTS, result.verdict)
        assertEquals("До зарплати не влазить: бракує 599 ₴ на платежі", plain(result.headline))
        assertEquals(
            listOf("Скасувати пробний «Megogo» до 11 жовтня" to "+199 ₴", "Купити 24 жовтня, після зарплати" to "+18 000 ₴"),
            result.levers.map { it.text to plain(leverGain(it)) }
        )
    }

    @Test
    fun `with life switched on, life until payday comes before the purchase`() {
        val result = check(inputs(life = LifeCost(true, 12_000.0)), 12_000.0, 18_400.0)
        assertEquals(AffordVerdict.SHORT_LIFE, result.verdict)
        assertEquals("До зарплати не влазить: бракує 1 896 ₴ на життя", plain(result.headline))
        // Without life the same purchase eats only into the plans.
        assertEquals(AffordVerdict.FITS, check(inputs(), 12_000.0, 18_400.0).verdict)
    }

    // ------------------------------------------------------------ by plan

    @Test
    fun `without a balance it answers from the plan and says so`() {
        val fits = check(inputs(), 1_000.0, null)
        assertEquals(AffordVerdict.FITS, fits.verdict)
        assertFalse(fits.byBalance)
        assertEquals("за планом, не за балансом", fits.basis)
        assertNull(fits.perDay)
        // 40 000 − 12 099 of October's payments = 27 901 free; 2 000 planned.
        val moves = check(inputs(), 26_500.0, null)
        assertEquals(AffordVerdict.PLANS_MOVE, moves.verdict)
        assertEquals("Влазить, але «Річ a» зсунеться на тиждень", moves.headline)
        val short = check(inputs(), 30_000.0, null)
        assertEquals("Цього місяця не влазить: бракує 2 099 ₴ на платежі", plain(short.headline))
    }

    @Test
    fun `money already put aside this month cannot give way again`() {
        val fund = putInto(Fund("f", "Відпустка", monthly = 5_000.0), 5_000.0, today)
        val input = inputs(funds = listOf(fund))
        // 27 901 free, 5 000 of it already in the fund: 22 901 to spend or plan with.
        val moves = check(input, 21_500.0, null)
        assertEquals(AffordVerdict.PLANS_MOVE, moves.verdict)
        assertEquals(listOf("a" to 599.0), moves.moves.map { it.ask.id to it.taken })
        val short = check(input, 23_500.0, null)
        assertEquals("Цього місяця не влазить: бракує 599 ₴ на платежі", plain(short.headline))
    }

    @Test
    fun `a purchase after payday is weighed against the plan for its month`() {
        val after = check(inputs(), 1_000.0, 18_400.0, date = LocalDate.of(2026, 10, 26))
        assertFalse(after.byBalance)
        assertEquals("після 23 жовтня — за планом, не за балансом", after.basis)
        // November: 40 000 − 17 999 (the insurance lands then) = 22 001 free.
        val november = check(inputs(), 22_500.0, null, date = LocalDate.of(2026, 11, 10))
        assertEquals("У листопаді не влазить: бракує 499 ₴ на платежі", plain(november.headline))
    }

    // ------------------------------------------------------------ the weather and the treat

    @Test
    fun `the day of the purchase says what weather it becomes`() {
        assertEquals("Сьогодні стане грозою ⛈️", check(inputs(), 9_000.0, 18_400.0).weather)
        assertEquals("Сьогодні: ясно ☀️ → дрібниці 🌤️", check(inputs(), 500.0, 18_400.0).weather)
        val fifteenth = LocalDate.of(2026, 10, 15)
        assertEquals("Четвер, 15 жовтня: дрібниці 🌤️ → дощ 🌧️", check(inputs(), 2_000.0, 18_400.0, date = fifteenth).weather)
        assertEquals("Четвер, 15 жовтня лишиться «дрібниці» 🌤️", check(inputs(), 100.0, 18_400.0, date = fifteenth).weather)
    }

    @Test
    fun `the treat is said to stay or to go`() {
        val treat = Treat(wish("t", 9_000.0), 0.0, "")
        assertEquals("Подарунок собі «Річ t» лишається", check(inputs(), 1_000.0, null, treat = treat).treat)
        assertEquals("«Річ t» цього місяця вже не влізе в подарунок собі", check(inputs(), 15_000.0, null, treat = treat).treat)
        assertNull(check(inputs(), 9_000.0, null, treat = treat, buying = "t").treat)
        assertNull(check(inputs(), 1_000.0, null).treat)
    }

    // ------------------------------------------------------------ «Купив частинами»

    @Test
    fun `bought in parts becomes a plan of payments from the day of purchase`() {
        val pay = instalmentPayFor("Ноутбук", 30_000.0, 6, today)
        assertEquals(5_000.0, pay.amount, 0.0)
        assertEquals(4, pay.day)
        assertEquals(6, pay.instalments)
        assertTrue(isInstalment(pay))
        assertEquals(LocalDate.of(2027, 3, 4), instalmentLast(pay))
        assertEquals("Покупка частинами", instalmentPayFor("", 1_000.0, 1, today).name)
        assertEquals(2, instalmentPayFor("", 1_000.0, 1, today).instalments)
        assertEquals(333.33, instalmentPayFor("x", 1_000.0, 3, today).amount, 0.0)
    }

    @Test
    fun `and shows the free money in each month it runs through`() {
        val input = inputs()
        val plan = moneyPlan(input)
        val preview = instalmentPreview(input, plan, instalmentPayFor("Ноутбук", 30_000.0, 6, today))
        assertEquals(6, preview.size)
        // October as Огляд counts it; November with the insurance it brings.
        assertEquals("жовтень: 27 901 → 22 901 ₴", plain(monthFreeLine(preview[0])))
        assertEquals("листопад: 22 001 → 17 001 ₴", plain(monthFreeLine(preview[1])))
        assertTrue(preview.all { kotlin.math.abs(it.before - it.after - 5_000.0) < 0.01 })
        // A long plan shows its first six months.
        assertEquals(6, instalmentPreview(input, plan, instalmentPayFor("Ноутбук", 30_000.0, 12, today)).size)
    }

    // ------------------------------------------------------------ words

    @Test
    fun `a typed balance says how old it is`() {
        assertEquals("вписано сьогодні", typedBalanceAge(today.toEpochDay(), today))
        assertEquals("вписано вчора", typedBalanceAge(today.toEpochDay() - 1, today))
        assertEquals("вписано 1 жовтня", typedBalanceAge(LocalDate.of(2026, 10, 1).toEpochDay(), today))
        assertEquals("", typedBalanceAge(0L, today))
    }

    @Test
    fun `a shift is said in weeks and months`() {
        assertEquals("на 3 дні", shiftWords(3.0))
        assertEquals("на тиждень", shiftWords(7.0))
        assertEquals("на 2 тижні", shiftWords(14.0))
        assertEquals("на 3 тижні", shiftWords(21.0))
        assertEquals("на місяць", shiftWords(30.0))
        assertEquals("на 2 місяці", shiftWords(61.0))
        assertEquals(820.0, perDayFigure(821.05), 0.0)
        assertEquals(0.0, perDayFigure(-50.0), 0.0)
    }
}

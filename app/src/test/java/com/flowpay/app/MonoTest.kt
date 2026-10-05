package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** monobank: reading the API, matching charges to payments, finding subscriptions. */
class MonoTest {

    private val zone: ZoneId = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 10, 4)

    /** Unix seconds at noon of a day this month or last. */
    private fun at(month: Int, day: Int): Long = LocalDate.of(2026, month, day).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    private fun plain(text: String) = text.replace(' ', ' ').replace(' ', ' ')

    private fun tx(id: String, time: Long, description: String, amount: Long, mcc: Int = 4899, currency: Int = UAH_CODE, operation: Long = amount) =
        MonoTx(id, time, description, mcc, amount, operation, currency, hold = false, account = "acc")

    private val accounts = mapOf("acc" to UAH_CODE)

    // ------------------------------------------------------------ the requests a pass makes

    private val daySeconds = 86_400L
    private val planNow = 1_800_000_000L

    @Test
    fun `a card never read is read three months back, a month a request`() {
        val plan = statementPlan(listOf("a", "b"), emptyMap(), planNow)
        assertEquals(listOf("a", "a", "a", "b", "b", "b"), plan.map { it.account })
        assertEquals(planNow - 93 * daySeconds, plan[0].from)
        assertEquals(planNow, plan[2].to)
        assertTrue(plan.all { it.to - it.from <= STATEMENT_WINDOW_S })
        // Back to back, nothing skipped.
        assertEquals(plan[0].to, plan[1].from)
    }

    @Test
    fun `a card read this morning is read again from two days before`() {
        val morning = planNow - 6 * 3_600
        assertEquals(
            listOf(StatementCall("a", morning - 2 * daySeconds, planNow)),
            statementPlan(listOf("a"), mapOf("a" to morning), planNow)
        )
    }

    @Test
    fun `a first load the phone stopped goes on where it stopped, with no extra request`() {
        // Two of the three months read when the pass was stopped.
        val stoppedAt = planNow - 93 * daySeconds + 2 * STATEMENT_WINDOW_S
        assertEquals(
            listOf(StatementCall("a", stoppedAt, planNow)),
            statementPlan(listOf("a"), mapOf("a" to stoppedAt), planNow)
        )
        // A month behind is still one request, not two.
        assertEquals(1, statementPlan(listOf("a"), mapOf("a" to planNow - 30 * daySeconds), planNow).size)
        // And a card left for half a year is read three months back, not six.
        assertEquals(
            planNow - 93 * daySeconds,
            statementPlan(listOf("a"), mapOf("a" to planNow - 180 * daySeconds), planNow).first().from
        )
    }

    @Test
    fun `a load says how long is left`() {
        assertEquals("Завантажую виписку — ще ≈13 хв", loadingLine(12))
    }

    // ------------------------------------------------------------ reading

    @Test
    fun `client info is read with balances in kopecks and the credit line apart`() {
        val json = """
            {"clientId":"x","name":"Сергій","permissions":"psfj","accounts":[
              {"id":"acc1","sendId":"s","balance":1534500,"creditLimit":1000000,"type":"black","currencyCode":980,
               "cashbackType":"UAH","maskedPan":["537541******1234"],"iban":"UA1"},
              {"id":"acc2","balance":12000,"creditLimit":0,"type":"black","currencyCode":840,"maskedPan":["444111******9999"]}],
             "jars":[{"id":"jar1","sendId":"j","title":"Навушники","currencyCode":980,"balance":400000,"goal":1050000}]}
        """
        val client = parseMonoClient(json)
        assertEquals("Сергій", client.name)
        assertEquals(534_500L, client.accounts[0].own)
        assertEquals("•••• 1234", client.accounts[0].label)
        assertEquals(5_345.0, ownUah(client, emptySet()), 1e-9)
        assertEquals(4_000.0, jarUah(client.jars[0])!!, 1e-9)
        // And the stored copy reads back the same.
        assertEquals(client, parseMonoClient(monoClientJson(client).toString()))
    }

    @Test
    fun `a statement is read and cached without the counterparty`() {
        val json = """[{"id":"t1","time":1759312800,"description":"NETFLIX.COM","mcc":4899,"originalMcc":4899,"hold":false,
            "amount":-29900,"operationAmount":-29900,"currencyCode":980,"commissionRate":0,"cashbackAmount":0,"balance":100,
            "counterIban":"UA000","counterName":"Хтось"}]"""
        val items = parseMonoStatement(json, "acc1")
        assertEquals(1, items.size)
        assertEquals(-29900L, items[0].amount)
        assertEquals("acc1", items[0].account)
        val cached = monoTxJson(items[0]).toString()
        assertFalse(cached.contains("counter"))
        assertEquals(items[0], monoTxOf(JSONObject(cached)))
    }

    @Test
    fun `statements are asked for in windows of at most 31 days`() {
        val day = 86_400L
        val windows = statementWindows(0, 70 * day)
        assertEquals(3, windows.size)
        assertTrue(windows.all { it.second - it.first <= STATEMENT_WINDOW_S })
        assertEquals(70 * day, windows.last().second)
        assertTrue(statementWindows(10, 10).isEmpty())
    }

    @Test
    fun `fresh operations replace cached ones by id and old ones are dropped`() {
        val old = tx("a", 100, "X", -100)
        val settled = tx("a", 100, "X", -120)
        val ancient = tx("b", 1, "Y", -5)
        val merged = mergeMonoTx(listOf(old, ancient), listOf(settled), keepFrom = 50)
        assertEquals(listOf(settled), merged)
    }

    // ------------------------------------------------------------ names

    @Test
    fun `a merchant is the description's first words in letters`() {
        assertEquals("netflix com", merchantKey("Netflix.com"))
        assertEquals("google youtube premium", merchantKey("Google *YouTube Premium 12345"))
        assertEquals("київстар", merchantKey("Київстар"))
    }

    @Test
    fun `a payment's name is looked for in Latin too`() {
        assertEquals("volia", transliterate("Воля"))
        assertTrue("volia" in nameTokens("Інтернет · Воля"))
        assertTrue(namesPayment("VOLIA KYIV", Pay("Інтернет · Воля", 231.0)))
        assertTrue(namesPayment("Google *YouTube", Pay("YouTube Premium", 179.0)))
        assertTrue(namesPayment("KYIVSTAR", Pay("Київстар", 250.0)))
        assertFalse(namesPayment("SILPO", Pay("Київстар", 250.0)))
        // Generic words alone match nothing.
        assertTrue(nameTokens("Підписка").isEmpty())
    }

    @Test
    fun `a bank description becomes a readable name`() {
        assertEquals("Netflix.com", prettyMerchant("NETFLIX.COM"))
        assertEquals("Google *YouTube", prettyMerchant("Google *YouTube"))
    }

    // ------------------------------------------------------------ matching

    private val pays = listOf(
        Pay("Netflix", 299.0, day = 28),
        Pay("YouTube Premium", 179.0, day = 20),
        Pay("Інтернет · Воля", 231.0, day = 1),
        Pay("Оренда квартири", 11_000.0, day = 1),
        Pay("Підписка Adobe", 9.59, day = 15, currency = USD)
    )

    private val statement = listOf(
        tx("n9", at(9, 28), "NETFLIX.COM", -29900),
        tx("y9", at(9, 20), "Google *YouTube", -17900),
        tx("v10", at(10, 1), "VOLIA", -23100),
        // A transfer of the rent's amount to a person: never a payment.
        tx("t10", at(10, 1), "Переказ на картку", -1_100_000, mcc = 4829),
        tx("a9", at(9, 15), "ADOBE *PHOTOGPHY", -39900, mcc = 5818, currency = USD_CODE, operation = -959),
        tx("s10", at(10, 2), "SILPO", -84530, mcc = 5411)
    )

    @Test
    fun `charges are matched to the months they pay for`() {
        val found = monoMatches(pays, statement, emptyList(), emptySet(), accounts, today, 41.6, zone)
        val byName = found.associateBy { it.pay.name }
        assertEquals("2026-09", byName.getValue("Netflix").month)
        assertEquals(MonoMatchKind.NAMED, byName.getValue("Netflix").kind)
        assertEquals("2026-09", byName.getValue("YouTube Premium").month)
        assertEquals("2026-10", byName.getValue("Інтернет · Воля").month)
        assertEquals(9.59, byName.getValue("Підписка Adobe").charged, 1e-9)
        // The transfer is not the rent, and the groceries are nobody's bill.
        assertNull(byName["Оренда квартири"])
        assertEquals(4, found.size)
    }

    @Test
    fun `a marked month and a refused pair are not asked about again`() {
        val marks = listOf(PaidMark("Netflix", "2026-09", 299.0))
        val rejected = setOf("y9|YouTube Premium")
        val found = monoMatches(pays, statement, marks, rejected, accounts, today, 41.6, zone)
        assertTrue(found.none { it.pay.name == "Netflix" || it.pay.name == "YouTube Premium" })
    }

    @Test
    fun `a confirmed merchant is recognised, a raise included`() {
        val learned = pays.map { if (it.name == "Netflix") it.copy(monoMerchant = "netflix com") else it }
        val raised = listOf(tx("n9", at(9, 28), "NETFLIX.COM", -32900))
        val match = monoMatches(learned, raised, emptyList(), emptySet(), accounts, today, 41.6, zone).single()
        assertEquals(MonoMatchKind.LEARNED, match.kind)
        assertTrue(match.drifted)
        val marks = withMonoMark(emptyList(), match)
        assertEquals(PaidMark("Netflix", "2026-09", 329.0, UAH), marks.single())
        // Marked once, not twice.
        assertEquals(marks, withMonoMark(marks, match))
    }

    @Test
    fun `an amount alone counts only when it is exact and on the day`() {
        val rent = listOf(Pay("Оренда квартири", 11_000.0, day = 1))
        val exact = listOf(tx("r", at(10, 1), "LLC KVARTYRA", -1_100_000, mcc = 6513))
        assertEquals(MonoMatchKind.AMOUNT_ONLY, monoMatches(rent, exact, emptyList(), emptySet(), accounts, today, 41.6, zone).single().kind)
        val off = listOf(tx("r", at(10, 1), "LLC KVARTYRA", -1_080_000, mcc = 6513))
        assertTrue(monoMatches(rent, off, emptyList(), emptySet(), accounts, today, 41.6, zone).isEmpty())
    }

    @Test
    fun `one charge answers one payment`() {
        val twins = listOf(Pay("Netflix", 299.0, day = 28), Pay("Netflix сім'я", 299.0, day = 28))
        val found = monoMatches(twins, listOf(tx("n9", at(9, 28), "NETFLIX.COM", -29900)), emptyList(), emptySet(), accounts, today, 41.6, zone)
        assertEquals(1, found.size)
    }

    @Test
    fun `a price change from the bank is offered once it is marked`() {
        val learned = Pay("Netflix", 299.0, day = 28, monoMerchant = "netflix com")
        val marks = listOf(PaidMark("Netflix", "2026-09", 329.0))
        assertEquals(listOf(learned to 329.0), monoDrifts(listOf(learned), marks, today))
        assertTrue(monoDrifts(listOf(learned.copy(amount = 329.0)), marks, today).isEmpty())
    }

    // ------------------------------------------------------------ subscriptions

    @Test
    fun `a charge that comes back monthly and is not on the list is found`() {
        val now = at(10, 6)
        val txs = listOf(
            tx("s8", at(8, 5), "SPOTIFY", -16900),
            tx("s9", at(9, 5), "SPOTIFY", -16900),
            tx("s10", at(10, 5), "SPOTIFY", -17900),
            tx("r1", at(9, 12), "ROZETKA", -349900, mcc = 5732),
            // Already a payment on the list.
            tx("n8", at(8, 28), "NETFLIX.COM", -29900),
            tx("n9", at(9, 28), "NETFLIX.COM", -29900)
        )
        val found = findSubscriptions(txs, pays, emptySet(), accounts, now)
        assertEquals(1, found.size)
        val spotify = found.single()
        assertEquals("spotify", spotify.key)
        assertEquals(3, spotify.times)
        assertEquals(179.0, spotify.amount, 1e-9)
        assertEquals(5, spotify.day)
        // «Не підписка» is remembered.
        assertTrue(findSubscriptions(txs, pays, setOf("spotify"), accounts, now).isEmpty())
    }

    @Test
    fun `transfers are never subscriptions`() {
        val now = at(10, 6)
        val txs = listOf(
            tx("m8", at(8, 1), "Мамі", -300000, mcc = 4829),
            tx("m9", at(9, 1), "Мамі", -300000, mcc = 4829),
            tx("m10", at(10, 1), "Мамі", -300000, mcc = 4829)
        )
        assertTrue(findSubscriptions(txs, emptyList(), emptySet(), accounts, now).isEmpty())
    }

    // ------------------------------------------------------------ balance

    @Test
    fun `the balance says whether the week's charges are covered`() {
        val week = moneyWeather(listOf(Pay("Оренда", 1_200.0, day = 6)), emptyList(), today, 41.6, 40_000.0, 27_000.0)
        assertEquals("На картці 1 000 ₴ — до 6 жовтня не вистачить 200 ₴", plain(balanceLine(1_000.0, week)))
        assertEquals("На картці 5 000 ₴ — вистачить на всі списання тижня", plain(balanceLine(5_000.0, week)))
        val clear = moneyWeather(emptyList(), emptyList(), today, 41.6, 40_000.0, 27_000.0)
        assertEquals("На картці 5 000 ₴", plain(balanceLine(5_000.0, clear)))
    }

    // ------------------------------------------------------------ storage

    @Test
    fun `the confirmed merchant and the jar survive the backup`() {
        val pay = Pay("Netflix", 299.0, day = 28, monoMerchant = "netflix com")
        assertEquals(pay, payOf(payJson(pay)))
        val wish = Wish(
            id = "w", name = "Навушники", url = "https://rozetka.com.ua/x", image = "", price = 11_499.0,
            category = "Техніка", history = emptyList(), jar = "jar1"
        )
        assertEquals("jar1", wishOf(wishJson(wish)).jar)
        assertEquals("", payOf(JSONObject().put("n", "X").put("a", 1.0)).monoMerchant)
    }
}

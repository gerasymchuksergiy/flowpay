package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * What the stored monobank statement still tells about a payment's life: a pause
 * the bank ended, a merchant gone quiet, a double charge, a charge after a
 * cancellation, a missed charge, tomorrow's shortfall, a jar that covers a wish.
 * Sample data only — nothing here has met the owner's real statement.
 */
class MonoLifeTest {

    private val zone: ZoneId = ZoneOffset.UTC

    private fun shown(text: String) = text.replace(' ', ' ').replace(' ', ' ')

    /** Unix seconds at noon of a day. */
    private fun at(year: Int, month: Int, day: Int): Long = LocalDate.of(year, month, day).atTime(12, 0).toEpochSecond(ZoneOffset.UTC)

    private fun tx(id: String, time: Long, description: String, amount: Long, mcc: Int = 4899) =
        MonoTx(id, time, description, mcc, amount, amount, UAH_CODE, hold = false, account = "acc")

    private val accounts = mapOf("acc" to UAH_CODE)

    // ------------------------------------------------------------ a pause the bank ended

    private val megogo = Pay("Megogo", 199.0, day = 5, monoMerchant = "megogo")

    @Test
    fun `a new charge from a paused payment's merchant runs it again from that charge's date`() {
        val stopped = megogo.copy(pausedFrom = LocalDate.of(2026, 9, 1).toEpochDay())
        val today = LocalDate.of(2026, 10, 8)
        val txs = listOf(tx("m10", at(2026, 10, 6), "MEGOGO", -19_900))
        val back = bankResumed(listOf(stopped), txs, accounts, 41.0, today, zone).single()
        assertEquals(0L, back.pausedFrom)
        assertEquals(
            listOf(PauseSpan(LocalDate.of(2026, 9, 1).toEpochDay(), LocalDate.of(2026, 10, 5).toEpochDay(), 199.0)),
            back.pauses
        )
        // September stays unasked, October counts — and is matched as learned at once.
        assertEquals(0, monthRecord(listOf(back), emptyList(), "2026-09", today, 0.0).plannedCount)
        assertEquals(1, monthRecord(listOf(back), emptyList(), "2026-10", today, 0.0).plannedCount)
        val match = monoMatches(listOf(back), txs, emptyList(), emptySet(), accounts, today, 41.0, zone).single()
        assertEquals(MonoMatchKind.LEARNED, match.kind)
        // The morning says it once.
        val line = lifeLines(listOf(back), today).single()
        assertEquals("Megogo знову списує 199 ₴ — паузу знято", shown(line.text))
        assertTrue(line.key.startsWith("resumed|Megogo|"))
    }

    @Test
    fun `a late charge for the month before the pause, or another purchase, is not the payment back`() {
        val today = LocalDate.of(2026, 10, 8)
        val stopped = megogo.copy(day = 1, pausedFrom = LocalDate.of(2026, 10, 3).toEpochDay())
        val late = listOf(tx("m", at(2026, 10, 4), "MEGOGO", -19_900))
        assertEquals(stopped, bankResumed(listOf(stopped), late, accounts, 41.0, today, zone).single())
        val film = listOf(tx("f", at(2026, 10, 6), "MEGOGO", -129_900))
        val paused5 = megogo.copy(pausedFrom = LocalDate.of(2026, 9, 1).toEpochDay())
        assertEquals(paused5, bankResumed(listOf(paused5), film, accounts, 41.0, today, zone).single())
    }

    // ------------------------------------------------------------ «Мовчать»

    private val spotify = Pay("Spotify", 169.0, day = 5, monoMerchant = "spotify")

    @Test
    fun `a merchant quiet for forty days is asked about`() {
        val today = LocalDate.of(2026, 10, 4)
        val txs = listOf(tx("s8", at(2026, 8, 5), "SPOTIFY", -16_900))
        val silent = silentPayments(listOf(spotify), txs, emptyList(), emptyMap(), today, zone).single()
        assertEquals(60, silent.days)
        assertFalse(silent.annual)
        assertEquals("Spotify: списань не було 60 днів", silentLine(silent))
        assertEquals(LocalDate.of(2026, 9, 4), silentPaidUntil(silent, zone))
        // A charge in September: not quiet.
        val charged = txs + tx("s9", at(2026, 9, 5), "SPOTIFY", -16_900)
        assertTrue(silentPayments(listOf(spotify), charged, emptyList(), emptyMap(), today, zone).isEmpty())
        // September ticked by hand: paid some other way.
        val ticked = listOf(PaidMark("Spotify", "2026-09", 169.0))
        assertTrue(silentPayments(listOf(spotify), txs, ticked, emptyMap(), today, zone).isEmpty())
        // «Ще чекаю» quiets it for a while.
        val waiting = mapOf("Spotify" to today.plusDays(10).toEpochDay())
        assertTrue(silentPayments(listOf(spotify), txs, emptyList(), waiting, today, zone).isEmpty())
        // Paused or cancelled: nothing was owed.
        assertTrue(silentPayments(listOf(paused(spotify, today)), txs, emptyList(), emptyMap(), today, zone).isEmpty())
        // Never seen in the statement: the app cannot say how long.
        assertTrue(silentPayments(listOf(spotify), emptyList(), emptyList(), emptyMap(), today, zone).isEmpty())
    }

    @Test
    fun `an annual fee is quiet ten days past its date`() {
        val domain = Pay("Домен", 600.0, day = 14, billingMonth = 9, monoMerchant = "imena")
        val txs = listOf(tx("d", at(2025, 9, 14), "IMENA", -60_000))
        val silent = silentPayments(listOf(domain), txs, emptyList(), emptyMap(), LocalDate.of(2026, 10, 4), zone).single()
        assertTrue(silent.annual)
        assertEquals(20, silent.days)
        assertEquals("Домен: дата минула 20 днів тому, а річного списання не було", silentLine(silent))
        assertTrue(silentPayments(listOf(domain), txs, emptyList(), emptyMap(), LocalDate.of(2026, 9, 20), zone).isEmpty())
    }

    // ------------------------------------------------------------ «Схоже на подвійне списання»

    @Test
    fun `the same merchant taking the same amount twice in three days is pointed at`() {
        val now = at(2026, 10, 4)
        val txs = listOf(
            tx("a", at(2026, 10, 2), "MEGOGO", -19_900),
            tx("b", at(2026, 10, 3), "MEGOGO", -19_900),
            tx("c", at(2026, 9, 2), "MEGOGO", -19_900)
        )
        val double = doubleCharges(listOf(megogo), txs, emptySet(), now).single()
        assertEquals("a|b", double.key)
        assertEquals("Megogo 199 ₴ × 2 · 2 і 3 жовтня", shown(doubleChargeLine(double, accounts, zone)))
        assertTrue(doubleCharges(listOf(megogo), txs, setOf("a|b"), now).isEmpty())
        // A different amount, or four days apart, is two charges.
        val other = listOf(tx("a", at(2026, 10, 2), "MEGOGO", -19_900), tx("b", at(2026, 10, 3), "MEGOGO", -9_900))
        assertTrue(doubleCharges(listOf(megogo), other, emptySet(), now).isEmpty())
        val apart = listOf(tx("a", at(2026, 9, 28), "MEGOGO", -19_900), tx("b", at(2026, 10, 2), "MEGOGO", -19_900))
        assertTrue(doubleCharges(listOf(megogo), apart, emptySet(), now).isEmpty())
    }

    // ------------------------------------------------------------ «Списали після скасування?»

    private val netflix = Pay("Netflix", 299.0, day = 28, monoMerchant = "netflix com")

    @Test
    fun `a charge after a cancellation is asked about, for three months`() {
        val today = LocalDate.of(2026, 11, 29)
        val gone = cancelled(netflix, LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 4))
        val txs = listOf(
            tx("n9", at(2026, 9, 28), "NETFLIX.COM", -29_900),
            tx("n11", at(2026, 11, 28), "NETFLIX.COM", -29_900)
        )
        val after = chargedAfterCancel(listOf(gone), emptyList(), txs, emptySet(), accounts, 41.0, zone).single()
        assertEquals("n11", after.tx.id)
        assertEquals(299.0, after.charged, 0.0)
        assertEquals(gone, after.pay)
        assertEquals("Списали після скасування: Netflix 299 ₴, 28 листопада", shown(afterCancelLine(after, zone)))
        assertTrue(chargedAfterCancel(listOf(gone), emptyList(), txs, setOf("n11"), accounts, 41.0, zone).isEmpty())
        // Once it has gone to the bin, its merchant is still watched.
        val watch = withGone(emptyList(), gone, today)
        val fromBin = chargedAfterCancel(emptyList(), watch, txs, emptySet(), accounts, 41.0, zone).single()
        assertNull(fromBin.pay)
        assertEquals("Netflix", fromBin.gone?.name)
        assertEquals(netflix, payOf(org.json.JSONObject(fromBin.gone!!.pay)))
        // And forgotten three months after what was paid for ran out.
        assertTrue(withGone(watch, gone.copy(name = "Інше"), LocalDate.of(2027, 2, 1)).none { it.name == "Netflix" })
        assertEquals(watch, goneOf(goneJson(watch).toString()))
        // The morning line, said once by its operation.
        val lines = monoLines(emptyList(), emptyList(), listOf(after), accounts, zone)
        assertEquals("after|n11", lines.single().key)
    }

    // ------------------------------------------------------------ «не списалось»

    @Test
    fun `a date that passed without a charge is mentioned once the statement has been read past it`() {
        val due1 = spotify.copy(day = 1)
        val txs = listOf(tx("s9", at(2026, 9, 1), "SPOTIFY", -16_900))
        val today = LocalDate.of(2026, 10, 5)
        val missed = missedCharges(listOf(due1), txs, emptyList(), today, today, zone).single()
        assertEquals(LocalDate.of(2026, 10, 1), missed.due)
        assertEquals("Spotify: 1 жовтня списання не було — перевірте картку", missedLine(missed))
        assertEquals("missed|Spotify|2026-10", monoLines(listOf(missed), emptyList(), emptyList(), accounts, zone).single().key)
        // A charge a day early is the charge.
        val early = txs + tx("s10", at(2026, 9, 30), "SPOTIFY", -16_900)
        assertTrue(missedCharges(listOf(due1), early, emptyList(), today, today, zone).isEmpty())
        // Too soon to say, or a statement not read that far.
        assertTrue(missedCharges(listOf(due1), txs, emptyList(), LocalDate.of(2026, 10, 4), LocalDate.of(2026, 10, 4), zone).isEmpty())
        assertTrue(missedCharges(listOf(due1), txs, emptyList(), today, LocalDate.of(2026, 10, 3), zone).isEmpty())
        // Ticked by hand, paused: nothing to say.
        assertTrue(missedCharges(listOf(due1), txs, listOf(PaidMark("Spotify", "2026-10", 169.0)), today, today, zone).isEmpty())
        assertTrue(missedCharges(listOf(paused(due1, LocalDate.of(2026, 9, 20))), txs, emptyList(), today, today, zone).isEmpty())
    }

    // ------------------------------------------------------------ «не вистачить на завтра»

    @Test
    fun `tomorrow's charges the card cannot cover lead the morning`() {
        val today = LocalDate.of(2026, 10, 4)
        val pays = listOf(
            Pay("Netflix", 249.0, day = 5, monoMerchant = "netflix com"),
            Pay("iCloud", 99.0, day = 5, monoMerchant = "apple com bill"),
            // Paid from another bank: not the card's business.
            Pay("Оренда", 11_000.0, day = 5)
        )
        assertEquals(
            "Завтра Netflix 249 ₴ + iCloud 99 ₴, а власних на картці 210 ₴ — докиньте 138 ₴",
            shown(shortTomorrowLine(pays, emptyList(), today, 210.0, 41.0)!!)
        )
        assertNull(shortTomorrowLine(pays, emptyList(), today, 400.0, 41.0))
        assertEquals(
            "Завтра Netflix 249 ₴ + iCloud 99 ₴, а власних грошей на картці немає — докиньте 848 ₴",
            shown(shortTomorrowLine(pays, emptyList(), today, -500.0, 41.0)!!)
        )
        // Already ticked: not owed.
        val marks = listOf(PaidMark("Netflix", "2026-10", 249.0), PaidMark("iCloud", "2026-10", 99.0))
        assertNull(shortTomorrowLine(pays, marks, today, 0.0, 41.0))
        // And it leads the message.
        val line = shortTomorrowLine(pays, emptyList(), today, 210.0, 41.0)!!
        val message = digest(emptyList(), pays, emptyList(), today, 41.0, 30_000.0, lead = listOf(line))
        assertEquals(line, message.lines.first())
    }

    // ------------------------------------------------------------ a jar that covers the price

    private fun wish(price: Double, jar: String = "jar1", checked: Long = 20_000L, freshness: Freshness = Freshness.OK, hold: Long = 0L) = Wish(
        id = "w1", name = "Навушники", url = "https://shop.example/x", image = "", price = price,
        history = emptyList(), checkedDay = checked, freshness = freshness, holdUntil = hold, jar = jar
    )

    private val jars = listOf(MonoJar("jar1", "Навушники", UAH_CODE, 420_000L, 0L))

    @Test
    fun `a jar that first covers a fresh, in-stock price is announced once per price`() {
        val alert = jarAlerts(listOf(wish(3_999.0)), jars, emptySet(), 20_000L).single()
        assertEquals("w1@3999", alert.key)
        assertEquals("На банці 4 200 ₴, а ціна вже 3 999 ₴ — можна купувати", shown(jarAlertText(alert)))
        assertTrue(jarAlerts(listOf(wish(3_999.0)), jars, setOf("w1@3999"), 20_000L).isEmpty())
        // A lower price is news again.
        assertEquals(1, jarAlerts(listOf(wish(3_800.0)), jars, setOf("w1@3999"), 20_000L).size)
        // Not covered, held, stale, sold out, no jar: nothing.
        assertTrue(jarAlerts(listOf(wish(4_300.0)), jars, emptySet(), 20_000L).isEmpty())
        assertTrue(jarAlerts(listOf(wish(3_999.0, hold = 20_010L)), jars, emptySet(), 20_000L).isEmpty())
        assertTrue(jarAlerts(listOf(wish(3_999.0, checked = 19_990L)), jars, emptySet(), 20_000L).isEmpty())
        assertTrue(jarAlerts(listOf(wish(3_999.0, freshness = Freshness.OUT_OF_STOCK)), jars, emptySet(), 20_000L).isEmpty())
        assertTrue(jarAlerts(listOf(wish(3_999.0, jar = "")), jars, emptySet(), 20_000L).isEmpty())
    }

    // ------------------------------------------------------------ a promo, as the bank sees it

    @Test
    fun `a promo month is matched against the promo price, and is no price change`() {
        val today = LocalDate.of(2026, 10, 4)
        val volia = Pay(
            "Інтернет · Воля", 300.0, day = 1,
            trialEnd = LocalDate.of(2027, 2, 1).toEpochDay(), promoPrice = 150.0
        )
        val txs = listOf(tx("v10", at(2026, 10, 1), "VOLIA", -15_000))
        val match = monoMatches(listOf(volia), txs, emptyList(), emptySet(), accounts, today, 41.0, zone).single()
        assertEquals(150.0, match.charged, 0.0)
        assertFalse(match.drifted)
        val learned = volia.copy(monoMerchant = "volia")
        assertTrue(monoDrifts(listOf(learned), listOf(PaidMark(volia.name, "2026-10", 150.0)), today).isEmpty())
        // Without the promo, 150 against 300 is a change worth asking about.
        val plain = learned.copy(trialEnd = 0L, promoPrice = 0.0)
        assertEquals(1, monoDrifts(listOf(plain), listOf(PaidMark(volia.name, "2026-10", 150.0)), today).size)
    }
}

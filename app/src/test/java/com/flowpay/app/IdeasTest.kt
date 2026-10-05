package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import kotlin.random.Random

/** The money weather, the month's treat and the wish duel. See Ideas.kt. */
class IdeasTest {

    private val today = LocalDate.of(2026, 10, 4)

    /** The locale's grouping space is a no-break one; the expectations use a plain one. */
    private fun plain(text: String) = text.replace(' ', ' ').replace(' ', ' ')
    private val day = today.toEpochDay()

    // ------------------------------------------------------------ weather

    @Test
    fun `a day is weathered by what leaves against the income`() {
        assertEquals(MoneySky.CLEAR, moneySky(0.0, 40_000.0, 27_000.0))
        assertEquals(MoneySky.BREEZE, moneySky(179.0, 40_000.0, 27_000.0))
        assertEquals(MoneySky.RAIN, moneySky(2_000.0, 40_000.0, 27_000.0))
        assertEquals(MoneySky.STORM, moneySky(11_000.0, 40_000.0, 27_000.0))
        // A month that does not fit makes every charge a storm.
        assertEquals(MoneySky.STORM, moneySky(179.0, 40_000.0, -500.0))
        // No income: fixed amounts stand in, and no storm is invented from nothing.
        assertEquals(MoneySky.BREEZE, moneySky(179.0, 0.0, -12_000.0))
    }

    @Test
    fun `the forecast shows what is still to pay and skips what is marked`() {
        val pays = listOf(
            Pay("YouTube Premium", 179.0, day = 6),
            Pay("Оренда квартири", 11_000.0, day = 8),
            Pay("Інтернет", 300.0, day = 6)
        )
        val marks = listOf(PaidMark("Інтернет", monthKey(today), 300.0))
        val week = moneyWeather(pays, marks, today, 41.6, 40_000.0, 27_000.0)
        assertEquals(7, week.size)
        assertEquals(today, week.first().date)
        val sixth = week.first { it.date.dayOfMonth == 6 }
        assertEquals(listOf("YouTube Premium"), sixth.names)
        assertEquals(179.0, sixth.leaving, 1e-9)
        assertEquals("🌤️", sixth.emoji)
        assertEquals("⛈️", week.first { it.date.dayOfMonth == 8 }.emoji)
        assertEquals("☀️", week.first { it.date.dayOfMonth == 5 }.emoji)
    }

    @Test
    fun `dollars rain at the sell rate`() {
        val week = moneyWeather(listOf(Pay("Adobe", 10.0, day = 5, currency = USD)), emptyList(), today, 41.6, 40_000.0, 27_000.0)
        assertEquals(416.0, week[1].leaving, 1e-9)
    }

    @Test
    fun `the sentence names the worst day or a clear week`() {
        val week = moneyWeather(listOf(Pay("Оренда квартири", 11_000.0, day = 8)), emptyList(), today, 41.6, 40_000.0, 27_000.0)
        assertEquals("Чт, 8 жовтня гроза: Оренда квартири, 11 000 ₴", plain(weatherLine(week, today)))
        val clear = moneyWeather(emptyList(), emptyList(), today, 41.6, 40_000.0, 27_000.0)
        assertEquals("Тиждень ясний — нічого не списується", weatherLine(clear, today))
    }

    @Test
    fun `a day's amount fits a seventh of the screen`() {
        assertEquals("399 ₴", shortMoney(398.94))
        assertEquals("1,8 тис", shortMoney(1_800.0))
        assertEquals("11 тис", shortMoney(11_000.0))
    }

    // ------------------------------------------------------------ treat

    private fun wish(id: String, price: Double, vararg past: Double, target: Double = 0.0, hold: Long = 0L) = Wish(
        id = id, name = "Річ $id", url = "https://rozetka.com.ua/$id", image = "", price = price,
        targetPrice = target, category = "Інше",
        history = past.mapIndexed { i, p -> PricePoint(p, day - 30 + i) } + PricePoint(price, day),
        checkedDay = day, holdUntil = hold
    )

    @Test
    fun `the treat is the best deal that fits with a fifth to spare`() {
        val wishes = listOf(
            wish("a", 3_999.0, 4_299.0),        // 7% below its high
            wish("b", 2_000.0, 3_000.0),        // 33% below its high
            wish("c", 30_000.0, 40_000.0)       // does not fit
        )
        val treat = monthTreat(wishes, available = 20_000.0, today = day)!!
        assertEquals("b", treat.wish.id)
        assertEquals(18_000.0, treat.leftAfter, 1e-9)
        assertEquals("на 33% дешевше за найвищу ціну", treat.reason)
    }

    @Test
    fun `a reached target is the best deal there is`() {
        val wishes = listOf(wish("a", 2_000.0, 3_000.0), wish("t", 5_000.0, 5_100.0, target = 5_000.0))
        assertEquals("t", monthTreat(wishes, available = 20_000.0, today = day)!!.wish.id)
    }

    @Test
    fun `no treat when nothing fits or nothing is free`() {
        assertNull(monthTreat(listOf(wish("a", 9_000.0)), available = 10_000.0, today = day))
        assertNull(monthTreat(listOf(wish("a", 100.0)), available = 0.0, today = day))
        assertNull(monthTreat(listOf(wish("a", 100.0, hold = day + 5)), available = 10_000.0, today = day))
    }

    // ------------------------------------------------------------ duel

    @Test
    fun `a duel counts a win for one and a duel for both`() {
        val after = afterDuel(listOf(wish("a", 1.0), wish("b", 2.0), wish("c", 3.0)), winner = "a", loser = "b")
        assertEquals(1 to 1, after[0].duelWins to after[0].duelsPlayed)
        assertEquals(0 to 1, after[1].duelWins to after[1].duelsPlayed)
        assertEquals(0 to 0, after[2].duelWins to after[2].duelsPlayed)
    }

    @Test
    fun `the next duel is two different wishes and never the pair just shown`() {
        val pool = listOf(wish("a", 1.0), wish("b", 2.0), wish("c", 3.0))
        repeat(50) { seed ->
            val (x, y) = nextDuel(pool, last = "a" to "b", random = Random(seed))!!
            assertNotEquals(x.id, y.id)
            assertTrue(setOf(x.id, y.id) != setOf("a", "b"))
        }
        assertNull(nextDuel(listOf(wish("a", 1.0)), null))
    }

    @Test
    fun `what keeps losing is called fading, and only after enough duels`() {
        val wishes = listOf(
            wish("a", 1.0).copy(duelWins = 4, duelsPlayed = 4),
            wish("b", 2.0).copy(duelWins = 0, duelsPlayed = 4),
            wish("c", 3.0).copy(duelWins = 0, duelsPlayed = 2)
        )
        val result = duelResult(wishes)
        assertEquals("a", result.top.first().id)
        assertEquals("b", result.fading?.id)
        assertEquals(listOf("a", "c", "b"), sortWishes(wishes, WishSort.WANTED).map { it.id })
    }

    @Test
    fun `the duel record survives the backup`() {
        val w = wish("a", 1.0).copy(duelWins = 3, duelsPlayed = 7)
        val back = wishOf(wishJson(w))
        assertEquals(3, back.duelWins)
        assertEquals(7, back.duelsPlayed)
        val old = org.json.JSONObject(wishJson(wish("b", 2.0)).toString()).apply { remove("dw"); remove("dp") }
        assertEquals(0, wishOf(old).duelsPlayed)
    }
}

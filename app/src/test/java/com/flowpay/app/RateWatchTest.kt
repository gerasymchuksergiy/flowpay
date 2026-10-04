package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dollar's corridor and «Сплеск» (RateWatch.kt): two optional edges on
 * Monobank's sell rate, a word when the rate jumps more than one per cent in a day,
 * and the NBU's figure kept out of all of it.
 */
class RateWatchTest {

    private val now = 1_800_000_000_000L
    private val today = 20_365L

    private fun bank(sell: Double) = FxRate(sell - 0.4, sell, SOURCE_MONOBANK)
    private fun nbu(rate: Double) = FxRate(rate, rate, SOURCE_NBU)

    private fun check(corridor: RateCorridor, rate: FxRate, history: List<PricePoint> = emptyList(), at: Long = now) =
        checkRate(corridor, rate, at, now, history, today)

    private val corridor = RateCorridor(below = RateBound(41.10), above = RateBound(42.00))

    @Test
    fun `inside the corridor nothing is said`() {
        val result = check(corridor, bank(41.50))

        assertTrue(result.news.isEmpty())
        assertEquals(corridor, result.corridor)
    }

    @Test
    fun `falling below the lower edge is said once`() {
        val first = check(corridor, bank(41.05))

        assertEquals(listOf(RateNews.Below(41.05, 41.10)), first.news)
        assertFalse(first.corridor.below.armed)
        // The next hour, still below: quiet.
        assertTrue(check(first.corridor, bank(41.02)).news.isEmpty())
    }

    @Test
    fun `rising above the upper edge is said once`() {
        val first = check(corridor, bank(42.10))

        assertEquals(listOf(RateNews.Above(42.10, 42.00)), first.news)
        assertTrue(check(first.corridor, bank(42.30)).news.isEmpty())
    }

    @Test
    fun `an edge speaks again only after the rate has come back inside`() {
        val spoke = check(corridor, bank(41.05)).corridor

        // A hair above the line is not "back": it would ring every hour on a flutter.
        val hovering = check(spoke, bank(41.11)).corridor
        assertFalse(hovering.below.armed)
        // Back inside by a quarter of a per cent: armed again, silently.
        val back = check(hovering, bank(41.25))
        assertTrue(back.news.isEmpty())
        assertTrue(back.corridor.below.armed)
        // And the next fall is said.
        assertEquals(1, check(back.corridor, bank(41.00)).news.size)
    }

    @Test
    fun `the NBU's figure never arms, crosses or fills anything`() {
        val result = check(corridor, nbu(40.90))

        assertTrue(result.news.isEmpty())
        assertEquals(corridor, result.corridor)
        // Nor does it re-arm an edge that has spoken.
        val spoke = check(corridor, bank(41.05)).corridor
        assertEquals(spoke, check(spoke, nbu(41.90)).corridor)
    }

    @Test
    fun `a bank reading too old to be about now says nothing`() {
        val result = check(corridor, bank(40.90), at = now - RATE_WATCH_FRESH_MS - 1)

        assertTrue(result.news.isEmpty())
    }

    @Test
    fun `a jump of more than one per cent since yesterday is a spike, said once a day`() {
        val spiking = RateCorridor(spike = true)
        val history = listOf(PricePoint(41.30, today - 1), PricePoint(41.80, today))

        val first = check(spiking, bank(41.80), history)

        assertEquals(listOf(RateNews.Spike(41.30, 41.80)), first.news)
        assertEquals(
            "Долар за добу +1,2%: 41,30 → 41,80",
            rateNewsText(first.news.single()).replace(' ', ' ')
        )
        assertEquals(today, first.corridor.spikeDay)
        assertTrue(check(first.corridor, bank(41.95), history).news.isEmpty())
    }

    @Test
    fun `a move under one per cent, or no reading yesterday, is not a spike`() {
        val spiking = RateCorridor(spike = true)

        assertTrue(check(spiking, bank(41.60), listOf(PricePoint(41.30, today - 1))).news.isEmpty())
        assertTrue(check(spiking, bank(42.60), listOf(PricePoint(41.30, today - 3))).news.isEmpty())
        // A fall counts the same as a rise.
        assertEquals(1, check(spiking, bank(40.70), listOf(PricePoint(41.30, today - 1))).news.size)
    }

    @Test
    fun `an edge set already crossed starts quiet`() {
        val set = corridorAsSet(41.50, 0.0, spike = false, current = bank(41.30), previous = RateCorridor())

        assertFalse(set.below.armed)
        assertTrue(check(set, bank(41.20)).news.isEmpty())
        assertTrue(corridorAsSet(41.10, 42.00, false, bank(41.50), RateCorridor()).below.armed)
    }

    @Test
    fun `an edge needs the bank's rate, and the lower edge must be lower`() {
        assertEquals(
            "Межі стежать за курсом Monobank, а зараз є лише курс НБУ — оновіть курс трохи пізніше",
            corridorProblem(41.10, 0.0, nbu(41.0))
        )
        assertTrue(corridorProblem(0.0, 42.0, FxRate())!!.startsWith("Спершу має завантажитись"))
        assertEquals("Нижня межа має бути меншою за верхню", corridorProblem(42.0, 41.0, bank(41.5)))
        assertNull(corridorProblem(41.10, 42.00, bank(41.5)))
        // The spike alone asks nothing of the rate on the phone.
        assertNull(corridorProblem(0.0, 0.0, FxRate()))
    }

    @Test
    fun `«Готово» takes one edge away and leaves the rest`() {
        val all = corridor.copy(spike = true)

        assertEquals(RateBound(), withoutEdge(all, EDGE_BELOW).below)
        assertEquals(all.above, withoutEdge(all, EDGE_BELOW).above)
        assertFalse(withoutEdge(all, EDGE_SPIKE).spike)
        assertFalse(withoutEdge(withoutEdge(withoutEdge(all, EDGE_BELOW), EDGE_ABOVE), EDGE_SPIKE).watching)
    }

    @Test
    fun `the old single threshold becomes the matching edge`() {
        assertEquals(RateCorridor(above = RateBound(42.0)), corridorFrom(RateTarget(42.0, above = true)))
        assertEquals(RateCorridor(below = RateBound(41.1)), corridorFrom(RateTarget(41.1, above = false)))
        // One already announced waits for the rate to come back, like an edge that spoke.
        assertFalse(corridorFrom(RateTarget(41.1, above = false, hitDay = today - 2)).below.armed)
        assertEquals(RateCorridor(), corridorFrom(null))
    }

    @Test
    fun `the corridor survives its own storage`() {
        val stored = RateCorridor(RateBound(41.1, armed = false), RateBound(42.0), spike = true, spikeDay = today)

        assertEquals(stored, corridorOf(corridorJson(stored)))
        assertEquals(RateCorridor(), corridorOf("not json"))
    }

    @Test
    fun `the screen says what is watched and what an edge that spoke waits for`() {
        fun plain(text: String) = text.replace(' ', ' ')
        assertEquals(
            "Скажу, коли курс буде нижче 41,10 або вище 42,00",
            plain(corridorNote(corridor, bank(41.5)))
        )
        assertEquals(
            "Скажу, коли курс Monobank буде нижче 41,10 або вище 42,00, і про сплеск понад 1% за добу. " +
                "Курс уже нижче 41,10 — наступного разу скажу, коли повернеться вище",
            plain(corridorNote(corridor.copy(below = RateBound(41.10, armed = false), spike = true), nbu(40.9)))
        )
        assertTrue(corridorNote(RateCorridor(), bank(41.5)).startsWith("Скажу одразу"))
    }
}

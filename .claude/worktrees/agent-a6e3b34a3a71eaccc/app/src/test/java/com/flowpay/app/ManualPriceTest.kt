package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A price a person typed, and everything that must not quietly undo it.
 *
 * [Freshness.MANUAL] has always said the right thing — typed in by hand, nothing
 * is being read and nothing is claimed to be — but the twice-daily pass did not
 * know it. It re-read the shop, found no price there, and wrote
 * [Freshness.UNREADABLE] over the top, which is how a figure entered this morning
 * was greyed out by lunchtime and a purchase ended up being judged against a
 * number its own buyer had typed.
 */
class ManualPriceTest {

    private val silentPage = """
        <html><head><meta property="og:title" content="GameSir Super Nova"/></head>
        <body><div id="root"></div></body></html>
    """.trimIndent()

    private val pricedPage = """
        <html><head><meta property="product:price:amount" content="1299"/>
        <meta property="product:price:currency" content="UAH"/></head><body></body></html>
    """.trimIndent()

    private fun typedSource() = WishSource(
        url = "https://shop.example/x",
        price = 1458.0,
        freshness = Freshness.MANUAL,
        checkedDay = 20_000L,
        amount = 1458.0,
        currency = UAH,
        rate = 1.0
    )

    private fun typedWish() = Wish(
        id = "w1",
        name = "GameSir Super Nova",
        url = "https://shop.example/x",
        image = "",
        price = 1458.0,
        history = listOf(PricePoint(1458.0, 20_000L)),
        checkedDay = 20_000L,
        freshness = Freshness.MANUAL,
        addedDay = 20_000L,
        sources = listOf(typedSource())
    )

    @Test
    fun `a shop that still states no price leaves a typed price exactly as it was`() {
        val reading = readSource(typedSource(), silentPage, 20_001L, FxRate())

        assertTrue(reading is SourceReading.Stale)
        val next = (reading as SourceReading.Stale).source
        assertEquals(Freshness.MANUAL, next.freshness)
        assertEquals(1458.0, next.price, 0.001)
        // The shop was genuinely looked at, so how long ago that was still moves.
        assertEquals(20_001L, next.checkedDay)
    }

    @Test
    fun `a shop that starts stating a price takes over from the typed one`() {
        val reading = readSource(typedSource(), pricedPage, 20_001L, FxRate())

        assertTrue(reading is SourceReading.Priced)
        val next = (reading as SourceReading.Priced).source
        assertEquals(Freshness.OK, next.freshness)
        assertEquals(1299.0, next.price, 0.001)
    }

    @Test
    fun `a page that was never typed into still goes unreadable, as it has earned`() {
        val watched = WishSource(url = "https://shop.example/x", price = 100.0)

        val reading = readSource(watched, silentPage, 20_001L, FxRate())

        assertEquals(Freshness.UNREADABLE, (reading as SourceReading.Stale).source.freshness)
    }

    @Test
    fun `a whole pass over a typed wish changes nothing but the day`() {
        val wish = typedWish()
        val readings = wishSources(wish).map { readSource(it, silentPage, 20_001L, FxRate()) }

        val reading = mergeSources(wish, readings, 20_001L, FxRate())

        assertTrue(reading is Reading.Stale)
        val next = (reading as Reading.Stale).wish
        assertEquals(Freshness.MANUAL, next.freshness)
        assertEquals(1458.0, next.price, 0.001)
        assertEquals(20_001L, next.checkedDay)
        // And it is not one of the cards the app warns about after a run.
        assertEquals(0, staleCount(listOf(next)))
        assertFalse(isStale(next.freshness))
    }

    @Test
    fun `a typed price keeps reaching its target for as long as it is kept current`() {
        val wish = typedWish().copy(targetPrice = 1500.0)

        assertTrue(targetHit(wish, 20_000L))
        assertFalse(targetHit(wish, 20_000L + TARGET_FRESH_DAYS + 1))
        // Which is exactly what the pass above is preserving: the checked day
        // moves, so the pill keeps its right to announce the target.
        val kept = (mergeSources(
            wish,
            wishSources(wish).map { readSource(it, silentPage, 20_005L, FxRate()) },
            20_005L,
            FxRate()
        ) as Reading.Stale).wish
        assertTrue(targetHit(kept, 20_005L))
    }

    @Test
    fun `a typed price is judged against nothing, because it measures the typist`() {
        val wish = typedWish().copy(
            history = listOf(PricePoint(2000.0, 19_000L), PricePoint(1458.0, 20_000L))
        )

        assertEquals(0.0, lowestTracked(wish), 0.001)
        assertEquals(0, priceMovement(listOf(wish)).tracked)
    }

    @Test
    fun `a list of only typed prices does not make the pass report itself broken`() {
        val manual = typedWish()
        val watched = typedWish().copy(id = "w2", freshness = Freshness.OK)

        // Nothing there can be fetched, so a pass that fetched nothing succeeded.
        assertFalse(shouldRetryPass(listOf(manual), 0, 0, 0))
        assertFalse(shouldRetryPass(emptyList(), 0, 0, 0))
        // Something there could have been read and none of it arrived: retry.
        assertTrue(shouldRetryPass(listOf(manual, watched), 0, 0, 0))
        assertTrue(shouldRetryPass(emptyList(), 1, 0, 0))
        // Something did arrive, so the pass stands whatever else was on the list.
        assertFalse(shouldRetryPass(listOf(manual, watched), 0, 1, 0))
        assertFalse(shouldRetryPass(listOf(manual), 2, 0, 1))
    }
}

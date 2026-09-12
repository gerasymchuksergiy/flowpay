package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one figure reachable without leaving whatever is on screen.
 *
 * A Quick Settings tile has room for a label and a subtitle and nothing else, so
 * every judgement it makes has to be made here. The failure worth guarding is a
 * tile drawn as active with a dash on it: the whole value of this surface is that
 * a glance is enough, including the glance that has to say there is nothing yet.
 */
class TileTest {

    private fun shown(text: String) = text.replace(' ', ' ')

    private val market = FxRate(buy = 41.20, sell = 41.85, source = SOURCE_MONOBANK)
    private val official = FxRate(buy = 41.50, sell = 41.50, source = SOURCE_NBU)

    private val internet = listOf(Pay("Інтернет", 300.0, day = 16))

    private fun month(income: Double, expenses: List<Pay> = internet) =
        budget(income, monthlyTotal(expenses, 0.0))

    @Test
    fun `a tap swaps which figure is shown rather than opening the app`() {
        assertEquals(TILE_FREE, nextTileFace(TILE_RATE))
        assertEquals(TILE_RATE, nextTileFace(TILE_FREE))
        // Anything unrecognised falls back to the rate, which is the default face.
        assertEquals(TILE_RATE, nextTileFace(""))
    }

    @Test
    fun `the rate is shown with the feed it came from`() {
        val face = tileFace(TILE_RATE, market, month(40_000.0))

        assertEquals("41,85 ₴", shown(face.label))
        // The official rate and a bank's rate are different numbers, so the tile
        // never shows one without saying which it is.
        assertEquals("Долар · Monobank", face.subtitle)
        assertTrue(face.active)
    }

    @Test
    fun `the official rate is labelled as the official rate`() {
        assertEquals("Долар · НБУ", tileFace(TILE_RATE, official, month(40_000.0)).subtitle)
    }

    @Test
    fun `a rate that was never fetched leaves the tile switched off`() {
        val face = tileFace(TILE_RATE, FxRate(), month(40_000.0))

        assertEquals("—", face.label)
        assertEquals("Курс ще не завантажено", face.subtitle)
        // Drawn as inactive, because an active tile claims to be answering.
        assertFalse(face.active)
    }

    @Test
    fun `free cash is what the month has left after the standing costs`() {
        val face = tileFace(TILE_FREE, market, month(40_000.0))

        assertEquals("39 700 ₴", shown(face.label))
        assertEquals("Вільно на місяць", face.subtitle)
        assertTrue(face.active)
    }

    @Test
    fun `a month that does not fit says what is missing, not a negative figure`() {
        val face = tileFace(TILE_FREE, market, month(200.0))

        assertEquals("100 ₴", shown(face.label))
        assertEquals("Бракує до кінця місяця", face.subtitle)
        assertTrue(face.active)
    }

    @Test
    fun `with no income entered there is no figure to show`() {
        val face = tileFace(TILE_FREE, market, month(0.0))

        assertEquals("—", face.label)
        assertEquals("Дохід не вказано", face.subtitle)
        assertFalse(face.active)
    }
}

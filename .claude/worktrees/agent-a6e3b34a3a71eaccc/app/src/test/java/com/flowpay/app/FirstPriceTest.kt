package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The number behind the percentage.
 *
 * A card saying "−8%" without the figure it is eight percent of is a claim the
 * person cannot check, which is what these pin down.
 */
class FirstPriceTest {

    private fun wish(price: Double, history: List<Double>) = Wish(
        id = "w",
        name = "Навушники",
        url = "https://shop.example/x",
        image = "",
        price = price,
        history = history.mapIndexed { index, value ->
            PricePoint(price = value, day = 20_000L + index)
        }
    )

    @Test
    fun `the first price is the first reading, not today's`() {
        assertEquals(5300.0, firstPrice(wish(4876.0, listOf(5300.0, 5100.0, 4876.0))), 0.001)
    }

    @Test
    fun `a wish with no history falls back to the price it carries`() {
        assertEquals(4876.0, firstPrice(wish(4876.0, emptyList())), 0.001)
    }

    @Test
    fun `a zero first reading is not treated as the reference`() {
        // Zero is how the app records a price it could not read. Measuring against
        // it would report an infinite fall.
        assertEquals(4876.0, firstPrice(wish(4876.0, listOf(0.0))), 0.001)
    }

    @Test
    fun `the note names the price the percentage is measured from`() {
        val note = firstPriceNote(wish(4876.0, listOf(5300.0, 4876.0)))
        assertEquals("було 5 300 ₴", note?.replace(' ', ' '))
    }

    @Test
    fun `a wish seen once says nothing`() {
        assertNull(firstPriceNote(wish(4876.0, listOf(4876.0))))
    }

    @Test
    fun `a price that came back to where it started says nothing`() {
        // The percentage beside it reads zero, so there is no claim to support.
        assertNull(firstPriceNote(wish(5300.0, listOf(5300.0, 4876.0, 5300.0))))
    }

    @Test
    fun `the note and the percentage agree about direction`() {
        val fallen = wish(4876.0, listOf(5300.0, 4876.0))
        assertEquals("було 5 300 ₴", firstPriceNote(fallen)?.replace(' ', ' '))
        assert(priceChangePercent(fallen) < 0)
    }
}

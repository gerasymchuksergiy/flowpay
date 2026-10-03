package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A crossed-out price, read off the page and checked against thirty days.
 */
class DiscountTest {

    @Test
    fun `a strikethrough price in structured data is read`() {
        val page = """<script type="application/ld+json">{"@type":"Product","offers":{"@type":"Offer",
            "price":"2999","priceCurrency":"UAH","priceSpecification":[
            {"@type":"UnitPriceSpecification","priceType":"https://schema.org/StrikethroughPrice","price":"4999"}]}}</script>"""
        assertEquals(4999.0, declaredListPrice(page), 0.001)
    }

    @Test
    fun `a shop's own old price key is read`() {
        assertEquals(4999.0, declaredListPrice("""<script>{"price":"2999","oldPrice":"4 999"}</script>"""), 0.001)
        assertEquals(0.0, declaredListPrice("<html></html>"), 0.001)
    }

    @Test
    fun `an implausible was-price is not kept`() {
        val offer = Offer(2999.0, "", UAH)
        // Kopecks: 499 900 is not a former price of a 2 999 thing.
        assertEquals(0.0, listPriceIn("""<script>{"old_price":499900}</script>""", offer, FxRate()), 0.001)
        // Lower than the price is not a discount.
        assertEquals(0.0, listPriceIn("""<script>{"old_price":"1999"}</script>""", offer, FxRate()), 0.001)
        assertEquals(4999.0, listPriceIn("""<script>{"old_price":"4999"}</script>""", offer, FxRate()), 0.001)
    }

    @Test
    fun `the claimed list price survives storage`() {
        val source = WishSource(url = "https://shop.example/x", price = 2999.0, listPrice = 4999.0)
        assertEquals(source, sourceOf(sourceJson(source)))
        assertEquals(0.0, sourceOf(JSONObject().put("u", "https://shop.example/x")).listPrice, 0.001)
    }

    private val day = 20_000L

    @Test
    fun `an inflated discount is said against the thirty-day low`() {
        // 3 199 for weeks, raised to 4 999, then "−30%" down to 3 499 — still dearer than before.
        val history = listOf(
            PricePoint(3_199.0, day - 40),
            PricePoint(4_999.0, day - 10),
            PricePoint(3_499.0, day - 1)
        )
        val note = shopDiscountNote(4_999.0, 3_499.0, history, day)!!
        assertTrue(note, note.contains("−30%"))
        assertTrue(note, note.contains(money(3_199.0)))
        assertTrue(note, note.contains("дорожче"))
    }

    @Test
    fun `a real discount is called real, and sized against the low`() {
        val history = listOf(PricePoint(3_200.0, day - 40), PricePoint(2_880.0, day - 1))
        val note = shopDiscountNote(4_000.0, 2_880.0, history, day)!!
        assertTrue(note, note.contains("справжня знижка −10%"))
        assertTrue(discountIsReal(4_000.0, 2_880.0, history, day))
    }

    @Test
    fun `too little history is said, not hidden`() {
        val history = listOf(PricePoint(3_000.0, day - 5))
        assertTrue(shopDiscountNote(4_000.0, 3_000.0, history, day)!!.contains("Перевірити поки не можу"))
    }

    @Test
    fun `no claim, no note`() {
        assertNull(shopDiscountNote(0.0, 3_000.0, listOf(PricePoint(3_000.0, day - 50)), day))
    }

    @Test
    fun `black friday is the fourth friday of november`() {
        assertEquals(LocalDate.of(2026, 11, 27), blackFriday(2026))
        assertEquals(LocalDate.of(2027, 11, 26), blackFriday(2027))
    }

    @Test
    fun `the black friday note speaks only while it can still help`() {
        val early = LocalDate.of(2026, 10, 3)
        assertNull(blackFridayNote(early, daysTracked = 10))
        val late = LocalDate.of(2026, 11, 10)
        assertTrue(blackFridayNote(late, daysTracked = 2)!!.contains("27 листопада"))
        assertNull(blackFridayNote(LocalDate.of(2026, 9, 20), daysTracked = 0))
    }
}

package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A number shared in from an SMS, a charge that was not the plan, and a return
 * window that closes.
 */
class PurchaseFeaturesTest {

    @Test
    fun `a nova poshta number is found inside a carrier's message`() {
        assertEquals(
            "20450000000001",
            trackingNumberIn("Ваше відправлення 2045 0000 0000 01 прямує до відділення №12")
        )
        assertEquals("20450000000001", trackingNumberIn("ТТН: 20450000000001"))
        assertEquals("RL778364634EE", trackingNumberIn("tracking rl778364634ee, thanks"))
    }

    @Test
    fun `a phone number or a card is not a parcel`() {
        assertNull(trackingNumberIn("Дзвоніть +380 67 123 45 67"))
        assertNull(trackingNumberIn("Картка 4441 1111 2222 3333"))
        assertNull(trackingNumberIn(""))
    }

    @Test
    fun `a parcel added by number alone has a name to recognise`() {
        assertEquals("Посилка …0001", parcelNameFor("20450000000001"))
    }

    @Test
    fun `a paid month can say what was really charged`() {
        val marks = listOf(PaidMark("YouTube Premium", "2026-10", 99.0), PaidMark("Оренда", "2026-10", 11_000.0))
        val fixed = withMarkAmount(marks, "YouTube Premium", "2026-10", 179.0)
        assertEquals(179.0, fixed.first().amount, 0.001)
        assertEquals(11_000.0, fixed.last().amount, 0.001)
    }

    @Test
    fun `only a real move is offered as a new price`() {
        assertTrue(amountDrifted(99.0, 179.0))
        // Under ten hryvnia is rounding, whatever the percentage.
        assertFalse(amountDrifted(99.0, 105.0))
        // Under five per cent is a fee or a rate.
        assertFalse(amountDrifted(1_000.0, 1_030.0))
    }

    private fun order(url: String, digital: Boolean = false) =
        Order("o", "Контролер", url, RECEIVED, digital = digital)

    @Test
    fun `return windows start from the shop`() {
        assertEquals(90, defaultReturnDays(order("https://www.temu.com/ua/x.html")))
        assertEquals(14, defaultReturnDays(order("https://rozetka.com.ua/x")))
        assertEquals(0, defaultReturnDays(order("https://store.steampowered.com/app/1", digital = true)))
    }

    @Test
    fun `a closing return window is said in the morning, and only then`() {
        val today = 20_000L
        val closing = order("u").copy(id = "a", returnBy = today + 1, archivedDay = today - 13)
        val open = order("u").copy(id = "b", returnBy = today + 10, archivedDay = today - 4)
        val over = order("u").copy(id = "c", returnBy = today - 1, archivedDay = today - 15)
        val lines = returnLines(listOf(closing, open, over), today)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("завтра останній день"))
        assertNull(returnLine(over, today))
    }

    @Test
    fun `the return window survives storage`() {
        val kept = order("u").copy(returnBy = 20_014L, archivedDay = 20_000L)
        assertEquals(kept, orderOf(orderJson(kept)))
    }
}

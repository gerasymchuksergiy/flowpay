package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The small things that put time and a reason between the urge and the purchase,
 * and the reminder that arrives early enough to cancel.
 */
class NudgeFeaturesTest {

    private fun wish(price: Double, history: List<PricePoint>) = Wish(
        id = "w", name = "Навушники", url = "https://shop.example/x", image = "",
        price = price, history = history
    )

    @Test
    fun `the reason survives storage, the bin and a backup`() {
        val kept = wish(4_000.0, emptyList()).copy(why = "Старі зламались, їжджу щодня")
        assertEquals(kept.why, wishOf(wishJson(kept)).why)
        assertEquals("", wishOf(JSONObject().put("id", "w").put("n", "x").put("u", "u")).why)
    }

    @Test
    fun `the shortest pause offered is a day`() {
        assertEquals(1L, HOLD_PRESETS.first().second)
    }

    @Test
    fun `targets are offered from what the price has done`() {
        val day = 20_000L
        val history = listOf(
            PricePoint(4_400.0, day - 25),
            PricePoint(3_600.0, day - 15),
            PricePoint(4_000.0, day - 2)
        )
        val offered = targetSuggestions(history, 4_000.0, day)
        assertTrue(offered.any { it.price == 3_600.0 })
        assertTrue(offered.any { it.price == 3_600.0 && it.label.contains("30") })
        // The thirty-day low, the all-time low and ten per cent off are all 3 600
        // here, and a figure is offered once, under the name the history gives it.
        assertEquals(1, offered.size)
        // Nothing above the price now is offered as a target.
        assertTrue(offered.all { it.price < 4_000.0 })
    }

    @Test
    fun `a trial's last reminder says until when to cancel`() {
        val today = LocalDate.of(2026, 10, 17)
        val trial = Pay(
            "YouTube Premium", 179.0, day = 20, warnDays = 3,
            trialEnd = LocalDate.of(2026, 10, 20).toEpochDay()
        )
        val due = remindersDue(listOf(trial), today)
        assertEquals(LocalDate.of(2026, 10, 19), due.single().cancelBy)
        assertTrue(reminderText(due).contains("скасувати до 19 жовтня"))
    }

    @Test
    fun `the morning message itself says until when to cancel`() {
        // The message, not a helper: the first version of this line was written into
        // a function the digest does not call, and only the APK check caught it.
        val today = LocalDate.of(2026, 10, 17)
        val trial = Pay(
            "YouTube Premium", 179.0, day = 20, warnDays = 3,
            trialEnd = LocalDate.of(2026, 10, 20).toEpochDay()
        )
        val message = digest(
            wishes = emptyList(),
            pays = listOf(trial),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )
        assertTrue(message.body, (message.title + message.body).contains("скасувати до 19 жовтня"))
    }

    @Test
    fun `an ordinary charge does not talk about cancelling`() {
        val today = LocalDate.of(2026, 10, 19)
        val rent = Pay("Оренда", 11_000.0, day = 20)
        val due = remindersDue(listOf(rent), today)
        assertNull(due.single().cancelBy)
    }
}

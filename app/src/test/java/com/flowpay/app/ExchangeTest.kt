package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Buying and selling dollars on the rate screen. See Exchange.kt. */
class ExchangeTest {

    private val bank = FxRate(41.20, 41.60, SOURCE_MONOBANK)

    @Test
    fun `buying dollars is priced at the bank's sell rate`() {
        val cost = exchange(Deal.BUY, amountInUah = false, amount = 100.0, rate = bank)
        assertEquals(41.60, cost.rate, 1e-9)
        assertEquals(4_160.0, cost.result, 1e-9)
        assertTrue(cost.resultInUah)
        assertEquals("Заплатите", exchangeResultLabel(Deal.BUY, amountInUah = false))
    }

    @Test
    fun `selling dollars brings the bank's buy rate`() {
        val brings = exchange(Deal.SELL, amountInUah = false, amount = 100.0, rate = bank)
        assertEquals(41.20, brings.rate, 1e-9)
        assertEquals(4_120.0, brings.result, 1e-9)
        assertEquals("Отримаєте", exchangeResultLabel(Deal.SELL, amountInUah = false))
    }

    @Test
    fun `typed in hryvnias it answers in dollars`() {
        val buys = exchange(Deal.BUY, amountInUah = true, amount = 4_160.0, rate = bank)
        assertEquals(100.0, buys.result, 1e-9)
        assertFalse(buys.resultInUah)
        assertEquals("Купите доларів", exchangeResultLabel(Deal.BUY, amountInUah = true))
        val toSell = exchange(Deal.SELL, amountInUah = true, amount = 4_120.0, rate = bank)
        assertEquals(100.0, toSell.result, 1e-9)
        assertEquals("Треба продати доларів", exchangeResultLabel(Deal.SELL, amountInUah = true))
    }

    @Test
    fun `nothing is worked out without a rate or an amount`() {
        assertEquals(0.0, exchange(Deal.BUY, false, 100.0, FxRate()).result, 0.0)
        assertEquals(0.0, exchange(Deal.BUY, false, 0.0, bank).result, 0.0)
        assertEquals(0.0, exchange(Deal.BUY, false, Double.NaN, bank).result, 0.0)
    }

    @Test
    fun `the spread is said only for a bank's two figures`() {
        assertEquals("Різниця 0,40 ₴ на кожному доларі", spreadLine(bank))
        assertNull(spreadLine(FxRate(41.4, 41.4, SOURCE_NBU)))
        assertNull(spreadLine(FxRate()))
    }

    @Test
    fun `every recap card has a picture`() {
        RecapKind.entries.forEach { assertTrue(recapEmoji(it).isNotBlank()) }
    }
}

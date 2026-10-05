package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The few decisions in the October redesign that are logic rather than drawing.
 */
class RedesignTest {

    @Test
    fun `a sum splits into the part read first and the rest`() {
        assertEquals("433" to ",47 ₴", heroParts("433,47 ₴"))
        assertEquals("11 000" to " ₴", heroParts("11 000 ₴"))
        assertEquals("0" to ",00 USD", heroParts("0,00 USD"))
        assertEquals("—" to "", heroParts("—"))
    }

    @Test
    fun `the tab bar names the five tabs in their order`() {
        assertEquals(listOf("Бажання", "Курс", "Платежі", "Покупки", "Огляд"), NAV_DESTINATIONS.map { it.label })
        assertEquals("Покупки", NAV_DESTINATIONS[TAB_ORDERS].label)
    }
}

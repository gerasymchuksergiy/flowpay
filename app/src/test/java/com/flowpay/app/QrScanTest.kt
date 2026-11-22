package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** «Сканувати QR»: what a scan hands over, and the launcher's command. */
class QrScanTest {

    @Test
    fun `the raw text is what is handed over, trimmed`() {
        assertEquals(
            "https://rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649/",
            scannedText(" https://rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649/\n", null)
        )
    }

    @Test
    fun `an empty code falls back to what is displayed, else nothing`() {
        assertEquals("20450000000001", scannedText("", "20450000000001"))
        assertNull(scannedText(null, "  "))
        assertNull(scannedText(null, null))
    }

    @Test
    fun `the launcher shortcut opens the scanner`() {
        assertEquals(AppCommand.Scan, appCommand(ACTION_SCAN, null))
    }
}

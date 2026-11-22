package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The last of the October audit: trials in the month record, the chart axis, the
 * appraisal's compliance and its false refusals, and notifications that are off.
 */
class AuditFixesThirdTest {

    private val today = LocalDate.of(2026, 10, 5)
    private val rent = Pay("Оренда", 11_000.0, day = 5)
    // Free until the 20th of October, renews on the 20th.
    private val spotify = Pay(
        "Spotify", 199.0, day = 20,
        trialEnd = LocalDate.of(2026, 10, 20).toEpochDay()
    )

    @Test
    fun `a month in which a renewal was free is settled without it`() {
        val marks = listOf(PaidMark("Оренда", "2026-09", 11_000.0))
        val september = monthRecord(listOf(rent, spotify), marks, "2026-09", today, 0.0)
        assertEquals(MonthState.SETTLED, september.state)
        assertEquals(1, september.plannedCount)
        assertEquals(0.0, september.gap, 0.001)
        assertTrue(monthLines(listOf(rent, spotify), marks, "2026-09").none { it.pay.name == "Spotify" })
    }

    @Test
    fun `the axis never goes below nought`() {
        val axis = chartAxis(listOf(100.0, 1_200.0))
        assertTrue(axis.low >= 0.0)
    }

    @Test
    fun `both ends of the axis are written the same way`() {
        val note = axisNote(ChartAxis(39.5, 43.31)) { "${figure(it, 2)} ₴" }
        assertTrue(note, note.contains(figure(39.5, 2)))
        assertTrue(note, note.endsWith("${figure(43.31, 2)} ₴"))
        assertEquals(1, note.count { it == '₴' })
    }

    @Test
    fun `a wifi band is not a rating`() {
        assertFalse(contradictsOurFigures("Wi-Fi 6, 2,4/5 ГГц"))
        assertTrue(contradictsOurFigures("оцінка 4/5"))
    }

    @Test
    fun `search suggestions are read from the grounded answer`() {
        val json = """{"candidates":[{"groundingMetadata":{"searchEntryPoint":
            {"renderedContent":"<div class=\"chips\">x</div>"}}}]}"""
        assertEquals("<div class=\"chips\">x</div>", appraisalSuggestions(json))
        assertEquals("", appraisalSuggestions("""{"candidates":[{}]}"""))
    }

    @Test
    fun `a review older than two years is not shown`() {
        val written = Appraisal(kind = "Навушники", day = 1_000L)
        assertNotNull(appraisalKept(written, 1_000L + 730L))
        assertNull(appraisalKept(written, 1_000L + 731L))
    }

    @Test
    fun `notifications switched off are the first thing said`() {
        val health = WorkHealth(
            runs = listOf(WorkRun(WORK_PRICES, 1_000L)),
            pendingReasons = emptyList(),
            apiLevel = 36,
            nowMillis = 2_000L,
            notificationsOn = false
        )
        val line = healthLine(health)
        assertTrue(line.alarm)
        assertEquals("Сповіщення вимкнено", line.title)
    }

    @Test
    fun `a page that will not open still gives a purchase a name`() {
        assertTrue(placeholderName("https://www.temu.com/ua/x.html").contains("temu.com"))
    }
}

package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the charts are allowed to say about the numbers behind them.
 *
 * The old chart made three separate claims it could not support: that the history
 * was sampled at intervals, that the shortest bar meant the cheapest price, and
 * that every point was a month apart from the next. All three came out of the
 * maths rather than the drawing, so all three are testable here without a screen.
 */
class ChartsTest {

    private val day = 20_000L

    /** Thousands are grouped with a non-breaking space in this locale. */
    private fun shown(text: String) = text.replace(' ', ' ')

    private fun point(price: Double, on: Long, rate: Double = 0.0) =
        PricePoint(price, on, rate, if (rate > 0) SOURCE_NBU else "")

    // ------------------------------------------------------------- the axis

    @Test
    fun `a one percent move does not fill the chart the way a halving does`() {
        val small = chartAxis(listOf(4_900.0, 4_850.0))
        val large = chartAxis(listOf(4_900.0, 2_450.0))
        // The small move occupies a tenth of the height, not the whole of it.
        val smallShare = (4_900.0 - 4_850.0) / small.span
        val largeShare = (4_900.0 - 2_450.0) / large.span
        assertTrue("a 1% move should not fill the plot", smallShare < 0.15)
        assertTrue("a halving should fill most of the plot", largeShare > 0.7)
    }

    @Test
    fun `a price that never moved draws flat rather than as a cliff`() {
        val axis = chartAxis(listOf(5_000.0, 5_000.0, 5_000.0))
        assertTrue("the axis needs a span to place anything on", axis.span > 0.0)
        // Every reading lands at the same height, and that height is the middle.
        assertEquals(0.5f, axis.fraction(5_000.0), 0.001f)
    }

    @Test
    fun `the axis never zooms past a tenth of the price level`() {
        val axis = chartAxis(listOf(1_000.0, 1_001.0))
        assertTrue(axis.span >= 1_000.5 * MIN_AXIS_SPAN - 0.001)
    }

    @Test
    fun `the axis leaves the line room at both ends`() {
        val axis = chartAxis(listOf(100.0, 200.0))
        assertTrue("the top reading must not sit on the edge", axis.high > 200.0)
        assertTrue("the bottom reading must not sit on the edge", axis.low < 100.0)
    }

    @Test
    fun `an empty history gets an axis rather than a division by zero`() {
        val axis = chartAxis(emptyList())
        assertTrue("an axis with no span divides by zero", axis.span > 0.0)
        assertFalse(axis.fraction(0.0).isNaN())
    }

    @Test
    fun `an axis that somehow has no span still places a value`() {
        assertEquals(0.5f, ChartAxis(500.0, 500.0).fraction(500.0), 0.001f)
    }

    @Test
    fun `the axis says in words where it starts and stops`() {
        val note = shown(axisNote(ChartAxis(4_850.0, 5_350.0), ::money))
        assertTrue(note, note.contains("4 850"))
        assertTrue(note, note.contains("5 350 ₴"))
    }

    // -------------------------------------------------- spacing points by date

    @Test
    fun `points are spaced by date, so a long flat spell takes up its own width`() {
        val positions = chartPositions(
            listOf(point(400.0, day), point(390.0, day + 1), point(350.0, day + 100))
        )
        assertEquals(0f, positions[0], 0.001f)
        // The change made the next day sits next to the first, not a third of the
        // way across, which is what spacing by index did.
        assertEquals(0.01f, positions[1], 0.001f)
        assertEquals(1f, positions[2], 0.001f)
    }

    @Test
    fun `an undated history falls back to even spacing`() {
        val positions = chartPositions(listOf(point(400.0, 0L), point(390.0, 0L), point(350.0, 0L)))
        assertEquals(listOf(0f, 0.5f, 1f), positions)
    }

    @Test
    fun `one reading sits in the middle rather than against the left edge`() {
        assertEquals(listOf(0.5f), chartPositions(listOf(point(400.0, day))))
    }

    @Test
    fun `a history recorded all on one day does not divide by a zero span`() {
        val positions = chartPositions(listOf(point(400.0, day), point(390.0, day)))
        assertEquals(listOf(0f, 1f), positions)
    }

    // ---------------------------------------------- closing the line at today

    @Test
    fun `the line runs to today rather than stopping at the last change`() {
        val history = listOf(point(400.0, day), point(350.0, day + 10))
        val series = chartSeries(history, 350.0, day + 100)
        assertEquals(3, series.size)
        assertEquals(350.0, series.last().price, 0.001)
        // Flat from the change to today: the step holds, which is the point.
        assertEquals(day + 100, series.last().day)
    }

    @Test
    fun `today adds no second point when the last change is already today`() {
        val history = listOf(point(400.0, day), point(350.0, day + 10))
        assertEquals(2, chartSeries(history, 350.0, day + 10).size)
    }

    @Test
    fun `a wish whose price has never changed still draws one point`() {
        assertEquals(1, chartSeries(emptyList(), 400.0, day).size)
    }

    @Test
    fun `a price of zero adds nothing to draw`() {
        assertTrue(chartSeries(emptyList(), 0.0, day).isEmpty())
    }

    // ------------------------------------------------------- the range bar

    private fun insightOf(history: List<PricePoint>, current: Double, checked: Long) =
        priceInsight(history, current, checked)

    @Test
    fun `one reading degrades the range bar to a dot`() {
        val geometry = rangeBarGeometry(insightOf(emptyList(), 400.0, day))
        assertTrue("nothing has a span yet", geometry.single)
        assertFalse(geometry.hasBand)
    }

    @Test
    fun `two readings already place the marker at an end`() {
        val history = listOf(point(400.0, day), point(350.0, day + 20))
        val geometry = rangeBarGeometry(insightOf(history, 350.0, day + 20))
        assertFalse(geometry.single)
        // The cheapest it has been, so the marker is hard left.
        assertEquals(0f, geometry.marker, 0.001f)
    }

    @Test
    fun `twenty readings put the marker at its proportional place`() {
        val history = (0 until 20).map { point(400.0 + it * 10, day + it) }
        // 400 at the bottom, 590 at the top, sitting at 495 — the middle.
        val geometry = rangeBarGeometry(insightOf(history, 495.0, day + 19))
        assertEquals(0.5f, geometry.marker, 0.01f)
    }

    @Test
    fun `the band behind the marker is the thirty-day window, not the whole range`() {
        // A year-old high, then a quiet month well below it.
        val history = listOf(
            point(1_000.0, day),
            point(500.0, day + 300),
            point(520.0, day + 360)
        )
        val geometry = rangeBarGeometry(insightOf(history, 520.0, day + 365))
        assertTrue("the usual range should be shaded", geometry.hasBand)
        // The window covers 500..520 of a 500..1000 range: the left tenth of the bar.
        assertEquals(0f, geometry.bandStart, 0.01f)
        assertEquals(0.04f, geometry.bandEnd, 0.01f)
        assertTrue("today sits inside its usual range", geometry.marker <= geometry.bandEnd)
    }

    @Test
    fun `a marker is never placed outside the bar`() {
        val history = listOf(point(400.0, day), point(350.0, day + 5))
        val geometry = rangeBarGeometry(insightOf(history, 350.0, day + 5))
        assertTrue(geometry.marker in 0f..1f)
    }

    // ---------------------------------------------------------- rebasing

    @Test
    fun `both series start at a hundred`() {
        val history = listOf(point(400.0, day, 41.0), point(420.0, day + 30, 45.0))
        val series = rebasedToHundred(history)
        assertEquals(100.0, series.price.first().price, 0.001)
        assertEquals(100.0, series.rate.first().price, 0.001)
    }

    @Test
    fun `a price that only tracked the currency draws as two lines together`() {
        // Up ten percent in hryvnia, and the rate up ten percent too.
        val history = listOf(point(400.0, day, 40.0), point(440.0, day + 30, 44.0))
        val series = rebasedToHundred(history)
        assertEquals(110.0, series.price.last().price, 0.001)
        assertEquals(110.0, series.rate.last().price, 0.001)
    }

    @Test
    fun `a price that really rose pulls away from the rate line`() {
        val history = listOf(point(400.0, day, 40.0), point(600.0, day + 30, 40.0))
        val series = rebasedToHundred(history)
        assertEquals(150.0, series.price.last().price, 0.001)
        assertEquals(100.0, series.rate.last().price, 0.001)
    }

    @Test
    fun `readings with no rate behind them are left out rather than converted`() {
        val history = listOf(point(400.0, day), point(420.0, day + 30, 45.0))
        // One rated reading is not a series, so there is nothing to rebase against.
        assertTrue(rebasedToHundred(history).price.isEmpty())
    }

    @Test
    fun `rebasing keeps the dates, so the two lines share one horizontal scale`() {
        val history = listOf(
            point(400.0, day, 41.0),
            point(410.0, day + 1, 41.5),
            point(420.0, day + 100, 45.0)
        )
        val series = rebasedToHundred(history)
        assertEquals(chartPositions(series.price), chartPositions(series.rate))
    }

    // ------------------------------------------------------- the chart colours

    @Test
    fun `the chart ink is the accent calmed down, not a new colour`() {
        val ink = desaturated(Accent, 0.25f)
        assertEquals(ChartInk, ink)
        // Still lime: green stays the largest channel, blue the smallest.
        assertTrue(ink.green > ink.red)
        assertTrue(ink.red > ink.blue)
        // And genuinely less saturated than the accent it came from.
        assertTrue(ink.green - ink.blue < Accent.green - Accent.blue)
    }

    @Test
    fun `desaturating all the way leaves a grey`() {
        val grey = desaturated(Accent, 1f)
        assertEquals(grey.red, grey.green, 0.001f)
        assertEquals(grey.green, grey.blue, 0.001f)
    }

    @Test
    fun `the charts draw on a surface of their own, not on the page`() {
        assertTrue("a chart on near-black is the case that vibrates", ChartGround != AppBackground)
    }
}

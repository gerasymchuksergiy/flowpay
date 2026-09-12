package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * A subscription's own price history, in its row.
 *
 * The series has been stored since amounts started being recorded and nothing has
 * ever drawn it — the trackers worth checking all send an alert about a raise and
 * then show a list of charges instead. The one rule the row has to keep is the
 * refusal: an expense seen at one price has no history, and a line through a
 * single point would read as a price that was watched and held.
 */
class AmountTrendTest {

    private val september = LocalDate.of(2026, 9, 12).toEpochDay()
    private val july = LocalDate.of(2026, 7, 3).toEpochDay()

    private val raised = Pay(
        "Netflix",
        249.0,
        amounts = listOf(PricePoint(199.0, july), PricePoint(249.0, september))
    )

    @Test
    fun `an expense seen at one price says nothing at all`() {
        val fresh = Pay("Інтернет", 300.0, amounts = listOf(PricePoint(300.0, july)))

        assertTrue(amountStepPoints(fresh).isEmpty())
        assertNull(amountMoveLine(fresh))
    }

    @Test
    fun `an expense with no recorded history says nothing at all`() {
        val old = Pay("Комуналка", 2_400.0)

        assertTrue(amountStepPoints(old).isEmpty())
        assertNull(amountMoveLine(old))
    }

    @Test
    fun `a raise carries both figures, the percentage and the day it took effect`() {
        assertEquals(
            "було 199 → стало ${money(249.0)}, +25% · з 12 вересня",
            amountMoveLine(raised)
        )
    }

    @Test
    fun `a fall is stated in the same shape, with the minus Ukrainian typography uses`() {
        val cheaper = Pay(
            "Хмара",
            160.0,
            amounts = listOf(PricePoint(200.0, july), PricePoint(160.0, september))
        )

        assertEquals(
            "було 200 → стало ${money(160.0)}, −20% · з 12 вересня",
            amountMoveLine(cheaper)
        )
    }

    @Test
    fun `a figure the app never dated is not given a date it cannot vouch for`() {
        val undated = Pay(
            "Підписка",
            249.0,
            amounts = listOf(PricePoint(199.0, 0L), PricePoint(249.0, 0L))
        )

        assertEquals("було 199 → стало ${money(249.0)}, +25%", amountMoveLine(undated))
    }

    @Test
    fun `a dollar expense keeps its own currency on both halves of the move`() {
        val hosting = Pay(
            "Хостинг",
            7.0,
            currency = USD,
            amounts = listOf(PricePoint(5.0, july), PricePoint(7.0, september))
        )

        assertEquals("було 5 → стало ${dollars(7.0)}, +40% · з 12 вересня", amountMoveLine(hosting))
    }

    @Test
    fun `the row draws the last few points and never more`() {
        val long = Pay(
            "Підписка",
            600.0,
            amounts = (1..6).map { PricePoint(100.0 * it, july + it) }
        )

        val points = amountStepPoints(long)

        assertEquals(STEP_POINTS, points.size)
        // The end of the series, because the interesting shape is the last move.
        assertEquals(600.0, points.last().price, 0.001)
    }

    @Test
    fun `the drawn points end at what the expense costs now`() {
        val points = amountStepPoints(raised)

        assertEquals(2, points.size)
        assertEquals(199.0, points.first().price, 0.001)
        assertEquals(raised.amount, points.last().price, 0.001)
    }

    @Test
    fun `a restored expense whose amount outran its history still ends at the truth`() {
        // What a backup written before any of this produces: an amount with a
        // history that stops short of it.
        val restored = raised.copy(amount = 299.0)

        assertEquals(299.0, amountStepPoints(restored).last().price, 0.001)
        assertTrue(amountMoveLine(restored)!!.contains(money(299.0)))
    }
}

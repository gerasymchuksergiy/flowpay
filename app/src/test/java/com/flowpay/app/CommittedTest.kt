package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Committed spending, as a bite out of the month rather than a list of charges.
 *
 * The one case worth writing tests around is the third: with no income entered
 * there is no denominator, and both a full bar and an empty one would be a claim
 * the app cannot support. A bar with an invented denominator is exactly what this
 * project has refused to build before.
 */
class CommittedTest {

    private val today = LocalDate.of(2026, 9, 15)
    private val expenses = listOf(Pay("Інтернет", 300.0), Pay("Комуналка", 2_400.0))

    private fun barFor(income: Double) =
        committedOf(budget(income, monthlyTotal(expenses, usdSellRate = 0.0, today = today)))

    @Test
    fun `a month that fits leads with what survives, not with what went`() {
        val bar = barFor(20_000.0)

        assertEquals(CommittedState.KNOWN, bar.state)
        assertEquals(17_300.0, bar.left, 0.001)
        assertEquals(0.135f, bar.share, 0.0001f)
        assertEquals("Лишається ${money(17_300.0)}", committedHeadline(bar))
    }

    @Test
    fun `the standing costs are named under the bar, never above it`() {
        val bar = barFor(20_000.0)

        assertEquals(
            "Постійні витрати ${money(2_700.0)} з ${money(20_000.0)}",
            committedDetail(bar)
        )
    }

    @Test
    fun `a month that does not fit fills the bar and says how far short it is`() {
        val bar = barFor(2_000.0)

        assertEquals(CommittedState.OVERSPENT, bar.state)
        assertEquals(1f, bar.share, 0.0001f)
        assertEquals("Бракує ${money(700.0)}", committedHeadline(bar))
    }

    @Test
    fun `with no income there is no share to draw a bar from`() {
        val bar = barFor(0.0)

        assertEquals(CommittedState.UNKNOWN, bar.state)
        // Neither full nor empty as a proportion: the field means nothing here and
        // the renderer is told so by the state rather than by the number.
        assertEquals(0f, bar.share, 0.0001f)
    }

    @Test
    fun `with no income the committed figure still stands, because it is known`() {
        val bar = barFor(0.0)

        assertEquals(2_700.0, bar.committed, 0.001)
        assertEquals("${money(2_700.0)} уже зайнято", committedHeadline(bar))
        assertTrue(committedDetail(bar).contains("щойно буде вказано дохід"))
    }

    @Test
    fun `nothing committed leaves the whole month`() {
        val bar = committedOf(budget(20_000.0, monthlyTotal(emptyList(), 0.0, today)))

        assertEquals(0f, bar.share, 0.0001f)
        assertEquals("Лишається ${money(20_000.0)}", committedHeadline(bar))
    }

    @Test
    fun `an annual fee bites only out of the month it is charged in`() {
        val withDomain = expenses + Pay("Домен", 1_200.0, day = 14, billingMonth = 3)
        val quiet = committedOf(
            budget(20_000.0, monthlyTotal(withDomain, 0.0, today))
        )
        val march = LocalDate.of(2026, 3, 10)
        val loud = committedOf(
            budget(20_000.0, monthlyTotal(withDomain, 0.0, march))
        )

        assertEquals(2_700.0, quiet.committed, 0.001)
        assertEquals(3_900.0, loud.committed, 0.001)
        assertTrue(loud.share > quiet.share)
    }
}

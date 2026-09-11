package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Covers the three things added on top of the per-screen features: the reminder
 * schedule, the wishlist ordering, and the figures on the overview screen.
 */
class OverviewTest {

    private fun wish(
        id: String,
        price: Double,
        target: Double = 0.0,
        saved: Double = 0.0,
        history: List<Double> = listOf(price)
    ) = Wish(
        id, "Товар $id", "https://shop/$id", "", price, target, "Інше",
        history.mapIndexed { index, value -> PricePoint(value, 20_000L + index) },
        checkedDay = 20_100L,
        saved = saved
    )

    // ---------------------------------------------------------------- reminders

    @Test
    fun `a payment day beyond the month falls on the last day of it`() {
        assertEquals(28, effectivePaymentDay(31, 28))
        assertEquals(30, effectivePaymentDay(31, 30))
        assertEquals(31, effectivePaymentDay(31, 31))
        assertEquals(1, effectivePaymentDay(0, 31))
        assertEquals(15, effectivePaymentDay(15, 31))
    }

    @Test
    fun `payments due are matched by day of month`() {
        val items = listOf(
            Pay("Оренда", 250.0, day = 5, currency = USD),
            Pay("Інтернет", 300.0, day = 5),
            Pay("Комуналка", 2400.0, day = 20)
        )

        val due = paymentsDueOn(items, LocalDate.of(2026, 9, 5))

        assertEquals(listOf("Оренда", "Інтернет"), due.map { it.name })
        assertTrue(paymentsDueOn(items, LocalDate.of(2026, 9, 6)).isEmpty())
    }

    @Test
    fun `a payment set for the thirty first still fires in february`() {
        val items = listOf(Pay("Кредит", 5000.0, day = 31))

        // 2027 is not a leap year, so February ends on the 28th.
        assertEquals(1, paymentsDueOn(items, LocalDate.of(2027, 2, 28)).size)
        assertTrue(paymentsDueOn(items, LocalDate.of(2027, 2, 27)).isEmpty())
        // And in a leap February it moves to the 29th.
        assertEquals(1, paymentsDueOn(items, LocalDate.of(2028, 2, 29)).size)
    }

    @Test
    fun `the first reminder is scheduled for the next ten in the morning`() {
        val tenAm = LocalTime.of(10, 0)
        val hour = 60 * 60 * 1000L

        // Before the hour: later today.
        assertEquals(
            2 * hour,
            ReminderWorker.millisUntilNext(tenAm, LocalDateTime.of(2026, 9, 10, 8, 0))
        )
        // After it: the same time tomorrow.
        assertEquals(
            22 * hour,
            ReminderWorker.millisUntilNext(tenAm, LocalDateTime.of(2026, 9, 10, 12, 0))
        )
    }

    // ----------------------------------------------------------------- sorting

    @Test
    fun `the default order is the order things were added in`() {
        val items = listOf(wish("a", 100.0), wish("b", 50.0))

        assertEquals(listOf("a", "b"), sortWishes(items, WishSort.ADDED).map { it.id })
    }

    @Test
    fun `the biggest drop comes first`() {
        val fell = wish("fell", price = 800.0, history = listOf(1000.0, 800.0))
        val flat = wish("flat", price = 500.0, history = listOf(500.0))
        val rose = wish("rose", price = 1200.0, history = listOf(1000.0, 1200.0))

        val sorted = sortWishes(listOf(flat, rose, fell), WishSort.BIGGEST_DROP)

        assertEquals(listOf("fell", "flat", "rose"), sorted.map { it.id })
        assertEquals(-20.0, priceChangePercent(fell), 0.001)
        assertEquals(20.0, priceChangePercent(rose), 0.001)
        assertEquals(0.0, priceChangePercent(flat), 0.001)
    }

    @Test
    fun `price ordering runs both ways`() {
        val items = listOf(wish("mid", 500.0), wish("high", 900.0), wish("low", 100.0))

        assertEquals(listOf("low", "mid", "high"), sortWishes(items, WishSort.CHEAPEST).map { it.id })
        assertEquals(listOf("high", "mid", "low"), sortWishes(items, WishSort.DEAREST).map { it.id })
    }

    @Test
    fun `closest to the goal counts saved money against the target`() {
        val nearly = wish("nearly", price = 1000.0, saved = 900.0)
        val started = wish("started", price = 1000.0, saved = 100.0)
        // A target below the price makes the goal smaller and the progress larger.
        val targeted = wish("targeted", price = 1000.0, target = 200.0, saved = 100.0)

        val sorted = sortWishes(listOf(started, targeted, nearly), WishSort.CLOSEST)

        assertEquals(listOf("nearly", "targeted", "started"), sorted.map { it.id })
        assertEquals(0.5, wishProgress(targeted), 0.001)
    }

    @Test
    fun `sorting never loses or duplicates an item`() {
        val items = listOf(wish("a", 100.0), wish("b", 100.0), wish("c", 300.0, saved = 300.0))

        for (mode in WishSort.entries) {
            val sorted = sortWishes(items, mode)
            assertEquals(mode.name, items.size, sorted.size)
            assertEquals(mode.name, items.map { it.id }.toSet(), sorted.map { it.id }.toSet())
        }
    }

    @Test
    fun `an unknown stored sort name falls back to the default`() {
        assertEquals(WishSort.CHEAPEST, wishSortFrom("CHEAPEST"))
        assertEquals(WishSort.ADDED, wishSortFrom(""))
        assertEquals(WishSort.ADDED, wishSortFrom("SOMETHING_REMOVED_LATER"))
    }

    // ---------------------------------------------------------------- overview

    @Test
    fun `the overview adds up wishes, the month and the parcels`() {
        val wishes = listOf(
            wish("a", price = 2000.0, saved = 500.0),
            wish("b", price = 5000.0, target = 4000.0, saved = 4000.0)
        )
        val pays = listOf(Pay("Оренда", 250.0, currency = USD), Pay("Інтернет", 300.0))
        val orders = listOf(
            Order("1", "x", "u", IN_TRANSIT),
            Order("2", "y", "u", AT_BRANCH),
            Order("3", "z", "u", RECEIVED),
            Order("4", "w", "u", ORDERED)
        )

        val summary = overview(wishes, pays, orders, income = 45_000.0, usdSellRate = 44.8)

        assertEquals(2, summary.wishCount)
        assertEquals(6000.0, summary.wishTotal, 0.001)
        assertEquals(4500.0, summary.savedTotal, 0.001)
        assertEquals(0.75f, summary.savedProgress, 0.001f)
        assertEquals(1, summary.readyCount)
        assertEquals(11_500.0, summary.monthlyExpenses, 0.001)
        assertEquals(33_500.0, summary.freeCash, 0.001)
        assertEquals(2, summary.parcelsMoving)
        assertEquals(1, summary.parcelsAtBranch)
        assertEquals(1, summary.parcelsDone)
        assertFalse(summary.budgetUnknown)
    }

    @Test
    fun `funding everything is a month count only when there is free money`() {
        val wishes = listOf(wish("a", price = 10_000.0))
        val pays = listOf(Pay("Інтернет", 1000.0))

        // 9000 free a month against 10000 outstanding is two months.
        assertEquals(2, overview(wishes, pays, emptyList(), 10_000.0, 44.8).monthsToFundAll)
        // No income entered means the question cannot be answered.
        assertNull(overview(wishes, pays, emptyList(), 0.0, 44.8).monthsToFundAll)
        // Neither can it when the month already does not fit.
        assertNull(overview(wishes, pays, emptyList(), 500.0, 44.8).monthsToFundAll)
    }

    @Test
    fun `everything already saved needs no months`() {
        val wishes = listOf(wish("a", price = 1000.0, saved = 1000.0))

        assertEquals(0, overview(wishes, emptyList(), emptyList(), 20_000.0, 44.8).monthsToFundAll)
    }

    @Test
    fun `an empty app reports zeroes rather than dividing by them`() {
        val summary = overview(emptyList(), emptyList(), emptyList(), 0.0, 0.0)

        assertEquals(0, summary.wishCount)
        assertEquals(0f, summary.savedProgress, 0.001f)
        assertEquals(0, summary.readyCount)
        assertTrue(summary.budgetUnknown)
        assertEquals(0, summary.monthsToFundAll)
    }

    // ------------------------------------------- what the watched prices did

    @Test
    fun `a list that fell is reported as money and as a share`() {
        val moved = priceMovement(
            listOf(
                wish("a", price = 800.0, history = listOf(1000.0, 800.0)),
                wish("b", price = 1900.0, history = listOf(2000.0, 1900.0))
            )
        )

        assertEquals(2, moved.tracked)
        assertEquals(2, moved.cheaper)
        assertEquals(0, moved.dearer)
        assertEquals(3000.0, moved.firstTotal, 0.001)
        assertEquals(2700.0, moved.nowTotal, 0.001)
        assertEquals(-300.0, moved.change, 0.001)
        assertEquals(-10.0, moved.changePercent, 0.001)
    }

    @Test
    fun `falls and rises are counted apart and netted together`() {
        val moved = priceMovement(
            listOf(
                wish("a", price = 800.0, history = listOf(1000.0, 800.0)),
                wish("b", price = 2500.0, history = listOf(2000.0, 2500.0)),
                wish("c", price = 500.0, history = listOf(500.0, 500.0))
            )
        )

        assertEquals(3, moved.tracked)
        assertEquals(1, moved.cheaper)
        assertEquals(1, moved.dearer)
        assertEquals(1, moved.steady)
        assertEquals(300.0, moved.change, 0.001)
    }

    @Test
    fun `a wish measured once cannot have moved and is not counted`() {
        val moved = priceMovement(
            listOf(
                wish("a", price = 800.0, history = listOf(800.0)),
                wish("b", price = 900.0, history = emptyList())
            )
        )

        assertEquals(0, moved.tracked)
        assertEquals(0.0, moved.change, 0.001)
        assertEquals(0.0, moved.changePercent, 0.001)
        assertNull(moved.biggestDropName)
        assertNull(moved.biggestRiseName)
    }

    @Test
    fun `the steepest move in each direction is named`() {
        val moved = priceMovement(
            listOf(
                wish("a", price = 900.0, history = listOf(1000.0, 900.0)),
                wish("b", price = 500.0, history = listOf(1000.0, 500.0)),
                wish("c", price = 1300.0, history = listOf(1000.0, 1300.0))
            )
        )

        assertEquals("Товар b", moved.biggestDropName)
        assertEquals(-50.0, moved.biggestDropPercent, 0.001)
        assertEquals("Товар c", moved.biggestRiseName)
        assertEquals(30.0, moved.biggestRisePercent, 0.001)
    }

    @Test
    fun `an empty list says nothing rather than dividing by zero`() {
        val moved = priceMovement(emptyList())

        assertEquals(0, moved.tracked)
        assertEquals(0.0, moved.changePercent, 0.001)
    }

    @Test
    fun `positions are counted the way Ukrainian counts them`() {
        assertEquals("1 позиція", positionsLabel(1))
        assertEquals("3 позиції", positionsLabel(3))
        assertEquals("7 позицій", positionsLabel(7))
        assertEquals("12 позицій", positionsLabel(12))
        assertEquals("21 позиція", positionsLabel(21))
    }

}

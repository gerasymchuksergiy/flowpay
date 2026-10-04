package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Hiding sums. One sum missed is the whole mode undone, so the masking is tested
 * against what the app's own helpers actually print — whatever separators the
 * number format puts in — rather than against hand-typed strings.
 */
class PrivacyTest {

    /** A figure followed by a currency, in whatever spacing the formats use. */
    private val sumLeft = Regex("\\d[ \\u00A0\\u202F]*(₴|\\$|грн|тис)")

    private fun assertNoSums(text: String) =
        assertFalse("a sum is still showing in «$text»", sumLeft.containsMatchIn(text))

    // ------------------------------------------------------------ what counts as a sum

    @Test
    fun `hryvnia and dollars become the mask, the currency stays`() {
        assertEquals("$SUM_MASK ₴", maskSums(money(1_200.0)))
        assertEquals("$SUM_MASK ₴", maskSums(money(2_203.24)))
        assertEquals("$SUM_MASK ₴", maskSums(money(11_200_000.0)))
        assertEquals("$SUM_MASK $", maskSums(dollars(250.0)))
        assertEquals("$SUM_MASK ₴", maskSums(approxMoney(10_399.6)))
    }

    @Test
    fun `two currencies with no rate between them are both hidden`() {
        val total = MonthlyTotal(uah = 1_200.0, usd = 9.59, usdInUah = 0.0, total = 1_200.0, hasUsd = true, rateMissing = true)

        assertEquals("$SUM_MASK ₴ + $SUM_MASK $", maskSums(totalLabel(total)))
    }

    @Test
    fun `the weather's short figures are hidden, thousands included`() {
        assertEquals(SUM_MASK, maskSums(shortMoney(1_800.0)))
        assertEquals(SUM_MASK, maskSums(shortMoney(11_000.0)))
        assertEquals("$SUM_MASK ₴", maskSums(shortMoney(399.0)))
    }

    @Test
    fun `a sentence keeps its words, names and dates`() {
        val today = LocalDate.of(2026, 10, 20)
        val week = listOf(MoneyDay(today.plusDays(1), "🌧️", "дощ", 249.0, listOf("Netflix")))

        val line = weatherLine(week, today)

        assertEquals("Завтра дощ: Netflix, $SUM_MASK ₴", maskSums(line))
    }

    @Test
    fun `a price move hides both prices and keeps the percentage and the date`() {
        val netflix = Pay(
            "Netflix", 249.0, day = 5,
            amounts = listOf(PricePoint(199.0, 0L), PricePoint(249.0, LocalDate.of(2026, 9, 12).toEpochDay()))
        )

        val masked = maskSums(amountMoveLine(netflix)!!)

        assertEquals("було $SUM_MASK → стало $SUM_MASK ₴, +25% · з 12 вересня", masked)
        assertEquals("Netflix $SUM_MASK → $SUM_MASK ₴, +25%", maskSums(amountChangeLine(netflix, lastAmountChange(netflix)!!)))
    }

    @Test
    fun `percentages, counts, days, years and multiples are not sums`() {
        listOf(
            "3 платежі",
            "+25%",
            "12 жовтня",
            "у списку 120 днів",
            "Вересень 2026",
            "1,4 × «Ноутбук»",
            "за курсом 41,60",
            "платіж 3 з 6 · останній 15 січня"
        ).forEach { assertEquals(it, maskSums(it)) }
    }

    @Test
    fun `a figure on its own is hidden whatever its format`() {
        assertEquals(SUM_MASK, maskFigure("12"))
        assertEquals(SUM_MASK, maskFigure(shortMoney(1_800.0)))
        assertEquals("$SUM_MASK ₴", maskFigure(money(9_000.0)))
        // Nothing to hide in a dash: the empty state stays readable.
        assertEquals("—", maskFigure("—"))
    }

    @Test
    fun `the mask is stable when applied twice`() {
        val once = maskSums("Плани просять ${money(3_500.0)}, а вільно ${money(1_200.0)}")

        assertEquals(once, maskSums(once))
        assertNoSums(once)
    }

    // ------------------------------------------------------------ the recap picture

    @Test
    fun `a card whose headline is a sum is left out, not shown as dots`() {
        val card = RecapCard(RecapKind.MONTH_ON_MONTH, "Проти минулого місяця", "На ${money(1_200.0)} більше", "…")

        assertNull(recapCardWithoutSums(card))
        assertNull(recapCardWithoutSums(RecapCard(RecapKind.YEAR_IN_DOLLARS, "Рік", dollars(300.0))))
    }

    @Test
    fun `a detail that was only a sum is dropped, one with words keeps them`() {
        val dearest = RecapCard(RecapKind.DEAREST_WISH, "Найдорожче бажання", "Ноутбук", money(45_000.0))
        val drops = RecapCard(
            RecapKind.DROPS_CAUGHT, "Спіймано", "Подешевшало: 3 позиції",
            "Разом на ${money(1_250.0)}, поки ти на них не дивився"
        )

        assertEquals(dearest.copy(detail = ""), recapCardWithoutSums(dearest))
        assertEquals(
            drops.copy(detail = "Разом на $SUM_MASK ₴, поки ти на них не дивився"),
            recapCardWithoutSums(drops)
        )
    }

    @Test
    fun `a card with no sum at all is unchanged`() {
        val label = RecapCard(RecapKind.LABEL, "Звання місяця", "Патієнт", "«Ноутбук» у списку 120 днів і досі не куплено")

        assertEquals(label, recapCardWithoutSums(label))
    }

    @Test
    fun `a real month's deck keeps its names and percentages and loses every sum`() {
        val month = "2026-09"
        val today = LocalDate.of(2026, 10, 3)
        val sept = LocalDate.of(2026, 9, 1).toEpochDay()
        val wishes = listOf(
            Wish(
                "w1", "Навушники", "https://shop.example/a", "", 3_999.0,
                history = listOf(PricePoint(4_599.0, sept - 30), PricePoint(3_999.0, sept + 10))
            ),
            Wish("w2", "Ноутбук", "https://shop.example/b", "", 45_000.0, history = listOf(PricePoint(45_000.0, sept - 100)))
        )
        val pays = listOf(Pay("Оренда", 8_000.0, day = 1), Pay("Інтернет", 300.0, day = 5))
        val marks = listOf(
            PaidMark("Оренда", "2026-08", 8_000.0), PaidMark("Інтернет", "2026-08", 300.0),
            PaidMark("Оренда", month, 8_000.0), PaidMark("Інтернет", month, 300.0)
        )
        val recap = monthlyRecap(wishes, pays, emptyList(), marks, month, today, 40_000.0, 41.6)
        assertTrue("the sample has to say some sums", recap.cards.any { sumLeft.containsMatchIn(it.headline + it.detail) })

        val hidden = recapWithoutSums(recap)

        assertTrue(hidden.cards.isNotEmpty())
        hidden.cards.forEach { card ->
            assertNoSums(card.overline)
            assertNoSums(card.headline)
            assertNoSums(card.detail)
        }
        // The cards that survive are the same cards, in the same order.
        assertEquals(hidden.cards.map { it.kind }, recap.cards.map { it.kind }.filter { kind -> hidden.cards.any { it.kind == kind } })
        assertEquals(recap.title, hidden.title)
    }

    @Test
    fun `the payments' and the parcels' recap cards keep their words and lose their sums`() {
        val month = "2026-09"
        val today = LocalDate.of(2026, 10, 3)
        val promoEnd = LocalDate.of(2026, 9, 15).toEpochDay()
        // A promo that ran out on the 15th, written into its history the way the
        // morning pass writes it («150 → 300»).
        val internet = withPromoEnded(
            Pay("Інтернет", 300.0, day = 15, promoPrice = 150.0, trialEnd = promoEnd),
            today
        )
        // A purchase answered 😍, in the owner's own words.
        val headphones = Order(
            "o1", "Навушники JBL", "https://shop.example/jbl", RECEIVED,
            price = 1_599.0, paid = 1_499.0, archivedDay = LocalDate.of(2026, 8, 20).toEpochDay(),
            delight = 4, delightDay = LocalDate.of(2026, 9, 12).toEpochDay(), why = "щоб бігати з музикою"
        )
        val recap = monthlyRecap(emptyList(), listOf(internet), listOf(headphones), emptyList(), month, today, 40_000.0, 41.6)
        val promo = recap.cards.first { it.kind == RecapKind.SUB_PRICE_MOVED }
        assertEquals("Акція скінчилась", promo.overline)
        assertTrue("the sample's promo card has to carry sums", sumLeft.containsMatchIn(promo.detail))
        val delight = recap.cards.first { it.kind == RecapKind.DELIGHTED }

        val hidden = recapWithoutSums(recap)

        val hiddenPromo = hidden.cards.first { it.kind == RecapKind.SUB_PRICE_MOVED }
        assertEquals("Інтернет", hiddenPromo.headline)
        assertEquals("було $SUM_MASK → стало $SUM_MASK ₴, +100% · з 15 вересня", hiddenPromo.detail)
        // Nothing in it was a sum: it is shown exactly as it was.
        assertEquals(delight, hidden.cards.first { it.kind == RecapKind.DELIGHTED })
        hidden.cards.forEach { card -> assertNoSums(card.headline + " " + card.detail) }
    }

    @Test
    fun `a promo that ended without a recorded move keeps its card, not its prices`() {
        val card = RecapCard(
            RecapKind.TRIAL_ENDED, "Акція скінчилась", "Megogo",
            "Тепер ${amountLabel(199.0, UAH)} замість ${amountLabel(99.0, UAH)}"
        )

        assertEquals(card.copy(detail = "Тепер $SUM_MASK ₴ замість $SUM_MASK ₴"), recapCardWithoutSums(card))
    }

    // ------------------------------------------------------------ outside the app

    private val pays = listOf(Pay("Інтернет", 300.0, day = 21))
    private val today = LocalDate.of(2026, 10, 20)

    private fun month(income: Double) = budget(income, monthlyTotal(pays, 41.6, today))

    @Test
    fun `the widget says the name and the day, and whether there is money, without a figure`() {
        val summary = widgetSummary(pays, emptyList(), 40_000.0, 41.6, today)

        val hidden = widgetWithoutSums(summary, month(40_000.0))

        assertEquals("Інтернет", hidden.paymentName)
        assertEquals("завтра", hidden.paymentCountdown)
        assertEquals("", hidden.paymentAmount)
        assertEquals("Вільно: є", hidden.freeCash)
        listOf(hidden.paymentName, hidden.paymentDate, hidden.paymentCountdown, hidden.freeCash, hidden.parcels)
            .forEach(::assertNoSums)
    }

    @Test
    fun `a month that does not fit says so without saying by how much`() {
        assertEquals("Бракує до кінця місяця", hiddenFreeLine(month(100.0)))
        assertEquals("Дохід не вказано", hiddenFreeLine(month(0.0)))
        assertEquals("Вільно: немає", hiddenFreeLine(month(300.0)))
    }

    @Test
    fun `the tile hides the month and keeps the rate`() {
        val rate = FxRate(41.2, 41.6, SOURCE_MONOBANK)

        val free = tileFace(TILE_FREE, rate, month(40_000.0), hideSums = true)
        assertEquals("Вільно: є", free.label)
        assertTrue(free.active)
        assertNoSums(free.label + free.subtitle)

        assertEquals("Бракує", tileFace(TILE_FREE, rate, month(100.0), hideSums = true).label)
        assertFalse(tileFace(TILE_FREE, rate, month(0.0), hideSums = true).active)
        // The dollar is not anybody's secret.
        assertEquals(tileFace(TILE_RATE, rate, month(40_000.0)), tileFace(TILE_RATE, rate, month(40_000.0), hideSums = true))
    }

    @Test
    fun `with the switch off the tile is what it always was`() {
        val rate = FxRate(41.2, 41.6, SOURCE_MONOBANK)

        assertEquals(tileFace(TILE_FREE, rate, month(40_000.0)), tileFace(TILE_FREE, rate, month(40_000.0), hideSums = false))
        assertEquals(money(month(40_000.0).free), tileFace(TILE_FREE, rate, month(40_000.0)).label)
    }
}

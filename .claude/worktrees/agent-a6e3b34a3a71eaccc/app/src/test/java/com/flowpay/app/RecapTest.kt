package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The monthly recap, held to the one standard that matters at a single user.
 *
 * Two things are being proved here. That a card which cannot be filled is replaced
 * by a different card rather than shown empty or shown as "не вистачає даних" —
 * this phone is permanently in the thin-data case, so that is the main path and not
 * the degraded one. And that nothing volume-dependent got in: no percentile, no
 * rank, and above all no reproach, because a recap of spending sits much closer to
 * the one people found humiliating than to the one they shared.
 */
class RecapTest {

    private val today = LocalDate.of(2026, 10, 5)
    private val month = "2026-09"
    private val august = LocalDate.of(2026, 8, 15).toEpochDay()
    private val september = LocalDate.of(2026, 9, 20).toEpochDay()
    private val monthEnd = LocalDate.of(2026, 9, 30).toEpochDay()

    private fun wish(
        name: String,
        price: Double,
        history: List<PricePoint> = emptyList(),
        addedDay: Long = 0L,
        targetPrice: Double = 0.0
    ) = Wish(
        id = name,
        name = name,
        url = "https://shop.example/$name",
        image = "",
        price = price,
        targetPrice = targetPrice,
        history = history,
        checkedDay = monthEnd,
        addedDay = addedDay
    )

    private fun order(
        name: String,
        paid: Double,
        lowestSeen: Double,
        archivedDay: Long = september
    ) = Order(
        id = name,
        name = name,
        url = "",
        status = RECEIVED,
        paid = paid,
        lowestSeen = lowestSeen,
        archivedDay = archivedDay
    )

    /** A wish that got cheaper during September. */
    private val headphones = wish(
        "Навушники Sony",
        7_400.0,
        history = listOf(PricePoint(7_900.0, august), PricePoint(7_400.0, september)),
        addedDay = LocalDate.of(2026, 6, 1).toEpochDay()
    )
    private val kettle = wish("Чайник", 1_800.0, addedDay = LocalDate.of(2026, 9, 1).toEpochDay())

    private val internet = Pay("Інтернет", 300.0, day = 1)
    private val netflix = Pay(
        "Netflix",
        309.0,
        day = 12,
        amounts = listOf(PricePoint(269.0, august), PricePoint(309.0, september))
    )

    private val marks = listOf(
        PaidMark("Інтернет", "2026-08", 300.0),
        PaidMark("Netflix", "2026-08", 269.0),
        PaidMark("Інтернет", "2026-09", 300.0),
        PaidMark("Netflix", "2026-09", 309.0)
    )

    private fun recap(
        wishes: List<Wish> = listOf(headphones, kettle),
        pays: List<Pay> = listOf(internet, netflix),
        orders: List<Order> = listOf(order("Клавіатура", 2_100.0, 2_100.0)),
        paidMarks: List<PaidMark> = marks,
        income: Double = 24_000.0,
        rate: Double = 41.5
    ) = monthlyRecap(wishes, pays, orders, paidMarks, month, today, income, rate)

    private fun Recap.kinds() = cards.map { it.kind }

    // --------------------------------------------------------------- the deck

    @Test
    fun `the deck is six to eight cards, never sixteen`() {
        val deck = recap()

        assertTrue(deck.cards.size in MIN_RECAP_CARDS..MAX_RECAP_CARDS)
        assertFalse(deck.empty)
    }

    @Test
    fun `it opens by naming what was watched rather than what was spent`() {
        val first = recap().cards.first()

        assertEquals(RecapKind.OPENING, first.kind)
        assertEquals("Місяць уваги", first.headline)
        assertEquals("Вересень 2026", first.overline)
    }

    @Test
    fun `it ends on the label, which is the reveal`() {
        assertEquals(RecapKind.LABEL, recap().cards.last().kind)
    }

    @Test
    fun `no card is ever blank, and none of them admits to running out of data`() {
        recap().cards.forEach { card ->
            assertTrue(card.kind.name, card.overline.isNotBlank())
            assertTrue(card.kind.name, card.headline.isNotBlank())
            assertFalse(card.kind.name, card.detail.contains("даних"))
        }
    }

    @Test
    fun `nothing anywhere in the deck claims a rank or a percentile`() {
        // Every one of those is computed across a population. At one person there
        // is no population, so any figure of that shape would be fabricated.
        val words = listOf("топ", "рейтинг", "серед усіх", "відсоток користувач")
        recap().cards.forEach { card ->
            val text = "${card.overline} ${card.headline} ${card.detail}".lowercase()
            words.forEach { assertFalse(card.kind.name, text.contains(it)) }
        }
    }

    // ------------------------------------------------- the drops, and their stand-in

    @Test
    fun `a fall caught during the month leads the second card`() {
        val deck = recap()

        assertTrue(deck.kinds().contains(RecapKind.DROPS_CAUGHT))
        val card = deck.cards.first { it.kind == RecapKind.DROPS_CAUGHT }
        assertEquals("Подешевшало: ${positionsLabel(1)}", card.headline)
        assertTrue(card.detail.contains(money(500.0)))
    }

    @Test
    fun `a month in which nothing moved swaps the card rather than emptying it`() {
        val deck = recap(wishes = listOf(kettle))

        assertTrue(deck.kinds().contains(RecapKind.PRICES_HELD))
        assertFalse(deck.kinds().contains(RecapKind.DROPS_CAUGHT))
        val card = deck.cards.first { it.kind == RecapKind.PRICES_HELD }
        assertTrue(card.detail.contains("Теж відповідь"))
    }

    @Test
    fun `a wish added mid-month is not reported as having fallen by its whole price`() {
        val fresh = wish("Новинка", 1_000.0, history = listOf(PricePoint(1_000.0, september)))
        val deck = recap(wishes = listOf(fresh))

        assertFalse(deck.kinds().contains(RecapKind.DROPS_CAUGHT))
    }

    @Test
    fun `with nothing watched the slot is dropped, not filled with an apology`() {
        val deck = recap(wishes = emptyList())

        assertFalse(deck.kinds().contains(RecapKind.DROPS_CAUGHT))
        assertFalse(deck.kinds().contains(RecapKind.PRICES_HELD))
    }

    // ------------------------------------------------------------- superlatives

    @Test
    fun `the longest wait is a superlative out of the person's own list`() {
        val card = recap().cards.first { it.kind == RecapKind.LONGEST_WAIT }

        assertEquals("Навушники Sony", card.headline)
        assertTrue(card.detail.startsWith("У списку "))
    }

    @Test
    fun `a list nothing dates still gets a superlative, just a different one`() {
        val undated = wish("Стіл", 5_000.0)
        val deck = recap(wishes = listOf(undated))

        assertTrue(deck.kinds().contains(RecapKind.DEAREST_WISH))
        val card = deck.cards.first { it.kind == RecapKind.DEAREST_WISH }
        assertEquals("Стіл", card.headline)
        assertEquals(money(5_000.0), card.detail)
    }

    // ----------------------------------------------------------- the purchase

    @Test
    fun `patience is reported when it was earned`() {
        val card = recap().cards.first { it.kind == RecapKind.PATIENCE_PAID }

        assertEquals("Клавіатура", card.headline)
        assertEquals("Чекати було варто", card.overline)
    }

    @Test
    fun `a hurried purchase is never put in front of the person`() {
        // Paid 3 000 for something the tracker had seen at 2 000. The verdict
        // exists and has its own screen; it is not the monthly ceremony.
        val hasty = order("Монітор", 3_000.0, 2_000.0)
        val deck = recap(orders = listOf(hasty))

        assertFalse(deck.kinds().contains(RecapKind.PATIENCE_PAID))
        deck.cards.forEach {
            assertFalse(it.kind.name, it.detail.contains("Переплата"))
            assertFalse(it.kind.name, it.detail.contains("поспішив"))
        }
    }

    @Test
    fun `a hurried purchase is restated as the cheapest one rather than judged`() {
        val deck = recap(orders = listOf(order("Монітор", 3_000.0, 2_000.0)))
        val card = deck.cards.first { it.kind == RecapKind.CHEAPEST_BOUGHT }

        assertEquals("Монітор", card.headline)
        assertEquals(money(3_000.0), card.detail)
    }

    @Test
    fun `a month with no purchase at all drops the slot`() {
        val deck = recap(orders = emptyList())

        assertFalse(deck.kinds().contains(RecapKind.PATIENCE_PAID))
        assertFalse(deck.kinds().contains(RecapKind.CHEAPEST_BOUGHT))
    }

    // ---------------------------------------------- the comparison, and its stand-ins

    @Test
    fun `the comparison is against the person's own last month`() {
        val card = recap().cards.first { it.kind == RecapKind.MONTH_ON_MONTH }

        assertEquals("Проти минулого місяця", card.overline)
        assertEquals("На ${money(40.0)} більше", card.headline)
        assertTrue(card.detail.contains("Серпень 2026"))
        assertTrue(card.detail.contains("Вересень 2026"))
    }

    @Test
    fun `with no month to compare against, what survives the costs takes the slot`() {
        val deck = recap(paidMarks = emptyList())

        assertTrue(deck.kinds().contains(RecapKind.COMMITTED_SHARE))
        val card = deck.cards.first { it.kind == RecapKind.COMMITTED_SHARE }
        assertEquals("Лишається ${money(23_391.0)}", card.headline)
    }

    @Test
    fun `with no income there is no share, and no denominator is invented for one`() {
        val deck = recap(paidMarks = emptyList(), income = 0.0)
        val card = deck.cards.first { it.kind == RecapKind.STANDING_COSTS }

        assertEquals(paymentsLabel(2), card.headline)
        assertTrue(card.detail.endsWith("на рік"))
    }

    // ------------------------------------------------------------- conversion

    @Test
    fun `a year of standing costs converts into the wishlist, which is the same currency`() {
        val card = recap().cards.first { it.kind == RecapKind.YEAR_IN_WISHES }

        // (300 + 309) * 12 = 7 308 against a 7 400 pair of headphones.
        assertEquals("${figure(7_308.0 / 7_400.0, 1)} × «Навушники Sony»", card.headline)
    }

    @Test
    fun `with no wishlist to convert into, the rate answers instead`() {
        val deck = recap(wishes = emptyList())
        val card = deck.cards.first { it.kind == RecapKind.YEAR_IN_DOLLARS }

        assertEquals(dollars(kotlin.math.round(7_308.0 / 41.5)), card.headline)
    }

    @Test
    fun `with nothing standing and no rate, the conversion slot is simply dropped`() {
        val deck = recap(pays = emptyList(), rate = 0.0)

        assertFalse(deck.kinds().contains(RecapKind.YEAR_IN_WISHES))
        assertFalse(deck.kinds().contains(RecapKind.YEAR_IN_DOLLARS))
    }

    // ---------------------------------------------------------- subscriptions

    @Test
    fun `a subscription that raised its price is named, with both figures`() {
        val card = recap().cards.first { it.kind == RecapKind.SUB_PRICE_MOVED }

        assertEquals("Netflix", card.headline)
        assertEquals("Подорожчала — і не тихо", card.overline)
        assertTrue(card.detail.contains("було 269"))
        assertTrue(card.detail.contains("+15%"))
    }

    @Test
    fun `a free period that ran out takes the slot when no price moved`() {
        val trial = Pay("Музика", 199.0, day = 4, trialEnd = september)
        val deck = recap(pays = listOf(trial))
        val card = deck.cards.first { it.kind == RecapKind.TRIAL_ENDED }

        assertEquals("Музика", card.headline)
        assertTrue(card.detail.contains(money(199.0)))
    }

    @Test
    fun `subscriptions holding their price is said as the good news it is`() {
        val deck = recap(pays = listOf(internet))
        val card = deck.cards.first { it.kind == RecapKind.SUBS_STEADY }

        assertEquals("Жодна не подорожчала", card.headline)
    }

    // ----------------------------------------------------------------- labels

    @Test
    fun `the label is the most specific condition that fired, not the biggest number`() {
        // Ten positions would earn Картограф, but one of them has been wanted for a
        // hundred days, and that is the rarer, more particular fact.
        val patient = wish("Велосипед", 20_000.0, addedDay = monthEnd - 100)
        val crowd = (1..10).map { wish("Річ $it", 500.0) }
        val card = recap(wishes = crowd + patient).cards.last()

        assertEquals("Патієнт", card.headline)
        assertTrue(card.detail.contains("Велосипед"))
        assertTrue(card.detail.contains(daysLabel(100)))
    }

    @Test
    fun `a purchase at the best price ever seen earns its own label`() {
        val card = recap(wishes = listOf(kettle)).cards.last()

        assertEquals("Снайпер", card.headline)
    }

    @Test
    fun `catching a subscription's raise earns a label of its own`() {
        val card = recap(wishes = listOf(kettle), orders = emptyList()).cards.last()

        assertEquals("Ревізор", card.headline)
        assertTrue(card.detail.contains("Netflix"))
    }

    @Test
    fun `three falls in a month stop being luck`() {
        val fallen = (1..3).map {
            wish(
                "Річ $it",
                900.0,
                history = listOf(PricePoint(1_000.0, august), PricePoint(900.0, september))
            )
        }
        val card = recap(wishes = fallen, pays = listOf(internet), orders = emptyList())
            .cards.last()

        assertEquals("Мисливець", card.headline)
        assertEquals("За місяць спіймано 3 падіння ціни", card.detail)
    }

    @Test
    fun `five falls decline as five, not as three`() {
        assertEquals("3 падіння ціни", dropsLabel(3))
        assertEquals("5 падінь ціни", dropsLabel(5))
        assertEquals("1 падіння ціни", dropsLabel(1))
        assertEquals("11 падінь ціни", dropsLabel(11))
    }

    @Test
    fun `a month fully marked off earns the bookkeeper`() {
        val card = recap(wishes = listOf(kettle), pays = listOf(internet), orders = emptyList())
            .cards.last()

        assertEquals("Бухгалтер", card.headline)
    }

    @Test
    fun `a long list earns the cartographer`() {
        val card = recap(
            wishes = (1..10).map { wish("Річ $it", 500.0) },
            pays = emptyList(),
            orders = emptyList(),
            paidMarks = emptyList()
        ).cards.last()

        assertEquals("Картограф", card.headline)
    }

    @Test
    fun `a quiet month still gets a sentence of its own rather than a shrug`() {
        val card = recap(
            wishes = listOf(kettle),
            pays = emptyList(),
            orders = emptyList(),
            paidMarks = emptyList()
        ).cards.last()

        assertEquals("Спостерігач", card.headline)
        assertTrue(card.detail.isNotBlank())
    }

    @Test
    fun `every label outcome is a different sentence, so nobody meets a generic one`() {
        val names = listOf(
            recap(wishes = listOf(wish("Велик", 900.0, addedDay = monthEnd - 100))),
            recap(wishes = listOf(kettle)),
            recap(wishes = listOf(kettle), orders = emptyList()),
            recap(wishes = (1..10).map { wish("Річ $it", 500.0) }, pays = emptyList(), orders = emptyList(), paidMarks = emptyList())
        ).map { it.cards.last().headline }

        assertEquals(names.size, names.toSet().size)
    }

    // ------------------------------------------------------ when there is none

    @Test
    fun `an app with nothing in it produces no ceremony at all`() {
        val deck = monthlyRecap(
            emptyList(), emptyList(), emptyList(), emptyList(), month, today, 0.0, 0.0
        )

        assertTrue(deck.empty)
    }

    @Test
    fun `the recap is always for the month that has ended`() {
        assertEquals("2026-09", recapMonth(today))
        assertEquals("2025-12", recapMonth(LocalDate.of(2026, 1, 17)))
    }

    @Test
    fun `a recap already opened is never offered a second time`() {
        assertEquals("2026-09", recapDue("2026-08", today))
        assertNull(recapDue("2026-09", today))
        // A month never opened stays waiting: it is an artefact, not an
        // announcement, and an artefact does not expire at midnight.
        assertEquals("2026-09", recapDue("", today))
    }

    @Test
    fun `the invite says what it is offering and how long it takes`() {
        val deck = recap()

        assertEquals("Вересень 2026 — ${cardsLabel(deck.cards.size)}", recapInvite(deck))
    }
}

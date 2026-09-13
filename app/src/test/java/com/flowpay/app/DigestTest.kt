package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * One message a day, and only when there is something in it.
 *
 * The failure this guards against is not a badly worded line. It is a digest that
 * goes out every morning with nothing in it, because that is what teaches a person
 * to swipe the app's notifications away unread — and the two alerts that were kept
 * immediate are then swiped away with everything else.
 */
class DigestTest {

    // A Tuesday, so nothing in these tests is accidentally testing the weekend rule.
    private val today = LocalDate.of(2026, 9, 15)
    private val epochToday = today.toEpochDay()

    private fun shown(text: String) = text.replace(' ', ' ')

    private fun wish(
        name: String,
        price: Double,
        history: List<PricePoint>,
        freshness: Freshness = Freshness.OK,
        holdUntil: Long = 0L
    ) = Wish(
        id = name,
        name = name,
        url = "https://shop.example/$name",
        image = "",
        price = price,
        history = history,
        checkedDay = epochToday,
        freshness = freshness,
        holdUntil = holdUntil
    )

    private fun parcel(
        name: String,
        status: String,
        storageFrom: LocalDate?,
        problem: Boolean = false,
        archivedDay: Long = 0L
    ) = Order(
        id = name,
        name = name,
        url = "",
        status = status,
        problem = problem,
        paidStorageFrom = storageFrom?.toEpochDay() ?: 0L,
        archivedDay = archivedDay
    )

    private val internet = Pay("Інтернет", 300.0, day = 16, warnDays = 1)

    @Test
    fun `a day with nothing to report sends nothing at all`() {
        val quiet = digest(
            wishes = emptyList(),
            pays = listOf(Pay("Оренда", 8000.0, day = 1, warnDays = 1)),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 40_000.0
        )

        assertTrue(quiet.empty)
        assertEquals("", quiet.title)
        assertTrue(quiet.lines.isEmpty())
    }

    @Test
    fun `free cash on its own is never news`() {
        // It is the figure everything else is spent against, and it is true every
        // day. A daily message carrying only that is a daily interruption with no
        // information in it.
        val quiet = digest(
            wishes = emptyList(),
            pays = emptyList(),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 40_000.0
        )

        assertTrue(quiet.empty)
    }

    @Test
    fun `a single piece of news is its own headline`() {
        val one = digest(
            wishes = emptyList(),
            pays = listOf(internet),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertFalse(one.empty)
        assertEquals("Інтернет 300 ₴ — завтра", shown(one.title))
        // The trailer still rides along underneath it.
        assertEquals(listOf("Дохід не вказано"), one.lines)
    }

    @Test
    fun `several unrelated things are collected under one title`() {
        val full = digest(
            wishes = listOf(
                wish(
                    "Навушники",
                    2400.0,
                    listOf(PricePoint(2700.0, epochToday - 4), PricePoint(2400.0, epochToday))
                )
            ),
            pays = listOf(internet),
            orders = listOf(parcel("Клавіатура", AT_BRANCH, today.plusDays(3))),
            today = today,
            usdSellRate = 0.0,
            income = 40_000.0
        )

        assertEquals("Зведення за день", full.title)
        assertEquals(
            listOf(
                "Клавіатура — безкоштовно ще 3 дні",
                "Інтернет 300 ₴ — завтра",
                "Навушники — 2 400 ₴, дешевше на 300 ₴",
                "Вільно 39 700 ₴"
            ),
            full.lines.map { shown(it) }
        )
    }

    @Test
    fun `falls are named and rises are only counted`() {
        val moved = digest(
            wishes = listOf(
                wish("A", 100.0, listOf(PricePoint(200.0, epochToday - 3))),
                wish("B", 300.0, listOf(PricePoint(200.0, epochToday - 3))),
                wish("C", 400.0, listOf(PricePoint(200.0, epochToday - 3)))
            ),
            pays = emptyList(),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertEquals(
            listOf("A — 100 ₴, дешевше на 100 ₴", "Подорожчало: 2 позиції", "Дохід не вказано"),
            moved.lines.map { shown(it) }
        )
    }

    @Test
    fun `a wish with nothing recorded far enough back is not a change`() {
        // Added yesterday, so the only thing to compare its price with is itself.
        assertNull(
            recentChange(wish("Новий", 2400.0, listOf(PricePoint(2400.0, epochToday))), epochToday)
        )
        assertNull(recentChange(wish("Порожній", 2400.0, emptyList()), epochToday))
    }

    @Test
    fun `a price that has not moved is not reported as having moved`() {
        assertNull(
            recentChange(wish("Стабільний", 2400.0, listOf(PricePoint(2400.0, epochToday - 9))), epochToday)
        )
    }

    @Test
    fun `a price change is measured against how it stood before the window`() {
        val dropped = wish(
            "Навушники",
            2400.0,
            listOf(PricePoint(2700.0, epochToday - 4), PricePoint(2400.0, epochToday))
        )

        assertEquals(-300.0, recentChange(dropped, epochToday)!!, 0.001)
    }

    @Test
    fun `a parcel with several waiting names the one whose storage runs out first`() {
        val waiting = digest(
            wishes = emptyList(),
            pays = emptyList(),
            orders = listOf(
                parcel("Клавіатура", AT_BRANCH, today.plusDays(4)),
                parcel("Миша", AT_BRANCH, today.plusDays(1))
            ),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertEquals("2 посилки — безкоштовно ще 1 день (Миша)", shown(waiting.title))
    }

    @Test
    fun `a wish deliberately put aside stays silent in the digest too`() {
        // The immediate alerts already respect a hold. A digest that announced the
        // same fall the next morning would overrule the decision a day later.
        val held = digest(
            wishes = listOf(
                wish(
                    "Навушники",
                    2400.0,
                    listOf(PricePoint(2700.0, epochToday - 4)),
                    holdUntil = epochToday + 30
                )
            ),
            pays = emptyList(),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertTrue(held.empty)
    }

    @Test
    fun `a price nobody can read any more is not reported as having fallen`() {
        // The wish is showing its last known figure because the page stopped
        // stating one. A fall measured against that is an invention.
        val unreadable = digest(
            wishes = listOf(
                wish(
                    "Навушники",
                    2400.0,
                    listOf(PricePoint(2700.0, epochToday - 4)),
                    freshness = Freshness.UNREADABLE
                )
            ),
            pays = emptyList(),
            orders = emptyList(),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertTrue(unreadable.empty)
    }

    @Test
    fun `a parcel the carrier has a problem with is said before one merely waiting`() {
        val trouble = digest(
            wishes = emptyList(),
            pays = emptyList(),
            orders = listOf(
                parcel("Клавіатура", AT_BRANCH, today.plusDays(4)),
                parcel("Миша", IN_TRANSIT, null, problem = true)
            ),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertEquals("Зведення за день", trouble.title)
        assertEquals(
            listOf(
                "Миша — проблема з доставкою",
                "Клавіатура — безкоштовно ще 4 дні",
                "Дохід не вказано"
            ),
            trouble.lines.map { shown(it) }
        )
    }

    @Test
    fun `a purchase already filed away is a record rather than an errand`() {
        // An archived order keeps whatever status it was archived with, and that
        // is often "На відділенні". Reporting it every morning would be nagging
        // about something already done.
        val filed = digest(
            wishes = emptyList(),
            pays = emptyList(),
            orders = listOf(
                parcel("Клавіатура", AT_BRANCH, today.plusDays(1), archivedDay = epochToday - 5),
                parcel("Миша", IN_TRANSIT, null, problem = true, archivedDay = epochToday - 5)
            ),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertTrue(filed.empty)
    }

    @Test
    fun `a parcel still in transit is not something to collect`() {
        val moving = digest(
            wishes = emptyList(),
            pays = emptyList(),
            orders = listOf(parcel("Клавіатура", IN_TRANSIT, null)),
            today = today,
            usdSellRate = 0.0,
            income = 0.0
        )

        assertTrue(moving.empty)
    }

    @Test
    fun `storage that runs out tomorrow cannot wait for the morning`() {
        assertTrue(storageIsUrgent(0))
        assertTrue(storageIsUrgent(1))
        // Two days still leaves a whole day after the next digest, so it goes in
        // the digest rather than ringing the phone.
        assertFalse(storageIsUrgent(2))
        // A carrier that has not said is not a reason to interrupt.
        assertFalse(storageIsUrgent(null))
    }

    @Test
    fun `the urgent parcel alert does not say there is nought days left`() {
        assertEquals("Безкоштовне зберігання ще 1 день", urgentStorageText(1))
        assertEquals("Безкоштовне зберігання закінчилось", urgentStorageText(0))
    }

    @Test
    fun `the digest says when a payment was moved off a day off`() {
        // The 13th of September 2026 is a Sunday.
        val moved = digest(
            wishes = emptyList(),
            pays = listOf(Pay("Оренда", 8000.0, day = 13, warnDays = 3)),
            orders = emptyList(),
            today = LocalDate.of(2026, 9, 10),
            usdSellRate = 0.0,
            income = 0.0
        )

        assertEquals("Оренда 8 000 ₴ — завтра (перенесено з 13 вересня)", shown(moved.title))
    }
}

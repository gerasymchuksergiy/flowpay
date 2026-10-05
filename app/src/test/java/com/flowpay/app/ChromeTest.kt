package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The status pill interrupts, so it has to be right about when it is worth it.
 *
 * Two failures matter more than a wrong wording: saying nothing when storage is
 * about to start being billed, and saying something when there is nothing to say.
 * Both are pinned here, along with the order the three kinds of news come in.
 */
class ChromeTest {

    private val today = LocalDate.of(2026, 3, 10)

    private fun parcel(
        storageFrom: LocalDate?,
        status: String = AT_BRANCH,
        id: String = "p1"
    ) = Order(
        id = id,
        name = "Навушники",
        url = "https://shop.example/x",
        status = status,
        paidStorageFrom = storageFrom?.toEpochDay() ?: 0L
    )

    private fun wish(
        price: Double,
        target: Double,
        checked: LocalDate? = today,
        name: String = "Sony WH-1000XM5"
    ) = Wish(
        id = name,
        name = name,
        url = "https://shop.example/$name",
        image = "",
        price = price,
        targetPrice = target,
        history = emptyList(),
        checkedDay = checked?.toEpochDay() ?: 0L
    )

    @Test
    fun `nothing to say means no pill at all`() {
        assertNull(statusNote(emptyList(), emptyList(), emptyList(), today, 41.0))
    }

    @Test
    fun `a parcel sitting quietly is not news`() {
        // In transit, and a branch parcel whose free storage the carrier has not
        // reported: neither asks anything of you today.
        val orders = listOf(
            parcel(today.plusDays(1), status = IN_TRANSIT),
            parcel(null, id = "p2")
        )
        assertNull(statusNote(orders, emptyList(), emptyList(), today, 41.0))
    }

    @Test
    fun `free storage running out is the thing it says`() {
        val note = statusNote(
            listOf(parcel(today.plusDays(2))),
            emptyList(),
            emptyList(),
            today,
            41.0
        )
        assertEquals(StatusKind.PARCEL, note?.kind)
        assertEquals(TAB_ORDERS, note?.tab)
        assertEquals("безкоштовно ще 2 дні", note?.detail)
        assertFalse(note!!.urgent)
    }

    @Test
    fun `storage already being billed is urgent`() {
        val note = statusNote(listOf(parcel(today)), emptyList(), emptyList(), today, 41.0)
        assertEquals("зберігання вже платне", note?.detail)
        assertTrue(note!!.urgent)
    }

    @Test
    fun `storage still a week off is left alone`() {
        val note = statusNote(
            listOf(parcel(today.plusDays(STORAGE_WARNING_DAYS + 1L))),
            emptyList(),
            emptyList(),
            today,
            41.0
        )
        assertNull(note)
    }

    @Test
    fun `the tightest deadline is the one shown`() {
        val orders = listOf(
            parcel(today.plusDays(2), id = "later"),
            parcel(today.plusDays(1), id = "sooner")
        )
        val note = statusNote(orders, emptyList(), emptyList(), today, 41.0)
        assertEquals("безкоштовно ще 1 день", note?.detail)
    }

    @Test
    fun `a payment due today carries its amount`() {
        val pays = listOf(Pay("Оренда квартири", 12000.0, day = 10))
        val note = statusNote(emptyList(), pays, emptyList(), today, 41.0)
        assertEquals(StatusKind.PAYMENT, note?.kind)
        assertEquals(TAB_PAYMENTS, note?.tab)
        assertEquals("Платіж сьогодні", note?.title)
        assertTrue(note!!.urgent)
        assertTrue(note.detail.startsWith(money(12000.0)))
        assertTrue(note.detail.endsWith("Оренда квартири"))
    }

    @Test
    fun `a payment due tomorrow is still worth saying`() {
        val pays = listOf(Pay("Інтернет", 300.0, day = 11))
        val note = statusNote(emptyList(), pays, emptyList(), today, 41.0)
        assertEquals("Платіж завтра", note?.title)
        assertFalse(note!!.urgent)
    }

    @Test
    fun `a payment later in the month waits its turn`() {
        val pays = listOf(Pay("Інтернет", 300.0, day = 20))
        assertNull(statusNote(emptyList(), pays, emptyList(), today, 41.0))
    }

    @Test
    fun `a longer notice period brings the pill forward with it`() {
        // Seven days' warning is seven days in both places, not seven in the
        // notification and one here.
        val pays = listOf(Pay("Хостинг", 900.0, day = 15, warnDays = 7))

        val note = statusNote(emptyList(), pays, emptyList(), today, 41.0)

        assertEquals(StatusKind.PAYMENT, note?.kind)
        assertEquals("Платіж через 5 днів", note?.title)
        assertFalse(note!!.urgent)
    }

    @Test
    fun `no notice asked for means the pill waits for the day itself`() {
        val onTheDay = listOf(Pay("Інтернет", 300.0, day = 11, warnDays = 0))
        assertNull(statusNote(emptyList(), onTheDay, emptyList(), today, 41.0))

        val arrived = listOf(Pay("Інтернет", 300.0, day = 10, warnDays = 0))
        assertEquals("Платіж сьогодні", statusNote(emptyList(), arrived, emptyList(), today, 41.0)?.title)
    }

    @Test
    fun `dollars due with no rate are named rather than dropped`() {
        val pays = listOf(Pay("Оренда квартири", 300.0, day = 10, currency = USD))
        val note = statusNote(emptyList(), pays, emptyList(), today, 0.0)
        assertEquals(dollars(300.0), note?.detail)
    }

    @Test
    fun `a price that reached its target is news`() {
        val note = statusNote(
            emptyList(),
            emptyList(),
            listOf(wish(price = 11200.0, target = 12000.0)),
            today,
            41.0
        )
        assertEquals(StatusKind.TARGET, note?.kind)
        assertEquals(TAB_WISHES, note?.tab)
        assertTrue(note!!.detail.startsWith("Sony WH-1000XM5"))
        assertTrue(note.detail.endsWith(money(11200.0)))
    }

    @Test
    fun `a price above its target says nothing`() {
        val wishes = listOf(wish(price = 12500.0, target = 12000.0))
        assertNull(statusNote(emptyList(), emptyList(), wishes, today, 41.0))
    }

    @Test
    fun `a wish with no target can never reach one`() {
        val wishes = listOf(wish(price = 11200.0, target = 0.0))
        assertNull(statusNote(emptyList(), emptyList(), wishes, today, 41.0))
    }

    @Test
    fun `a target reached long ago stops being news`() {
        // The price has been under target for a fortnight, so it is a fact about
        // the item rather than something that just happened.
        val wishes = listOf(wish(price = 11200.0, target = 12000.0, checked = today.minusDays(14)))
        assertNull(statusNote(emptyList(), emptyList(), wishes, today, 41.0))
    }

    @Test
    fun `storage outranks a payment and a payment outranks a target`() {
        val orders = listOf(parcel(today.plusDays(1)))
        val pays = listOf(Pay("Оренда квартири", 12000.0, day = 10))
        val wishes = listOf(wish(price = 11200.0, target = 12000.0))

        assertEquals(
            StatusKind.PARCEL,
            statusNote(orders, pays, wishes, today, 41.0)?.kind
        )
        assertEquals(
            StatusKind.PAYMENT,
            statusNote(emptyList(), pays, wishes, today, 41.0)?.kind
        )
        assertEquals(
            StatusKind.TARGET,
            statusNote(emptyList(), emptyList(), wishes, today, 41.0)?.kind
        )
    }

    // -- a charge the person has already settled --------------------------------

    @Test
    fun `an expense already marked paid this month says nothing`() {
        // The complaint this was written for: the tick is on the payments screen
        // and the pill above it goes on announcing the same charge regardless.
        val pays = listOf(Pay("Підписка Adobe", 429.93, day = 10))
        val marks = listOf(PaidMark("Підписка Adobe", monthKey(today), 429.93))

        assertEquals(
            StatusKind.PAYMENT,
            statusNote(emptyList(), pays, emptyList(), today, 41.0)?.kind
        )
        assertNull(statusNote(emptyList(), pays, emptyList(), today, 41.0, marks))
    }

    @Test
    fun `a mark for another month does not settle this month's charge`() {
        val pays = listOf(Pay("Підписка Adobe", 429.93, day = 10))
        val february = listOf(
            PaidMark("Підписка Adobe", monthKey(today.minusMonths(1)), 429.93)
        )

        assertEquals(
            StatusKind.PAYMENT,
            statusNote(emptyList(), pays, emptyList(), today, 41.0, february)?.kind
        )
    }

    @Test
    fun `a settled charge does not hide an unsettled one behind it`() {
        // Adobe on the tenth is ticked off; the internet on the twelfth is not.
        // Had the marks been applied to the date [nextPayment] found rather than to
        // the expenses going into it, the earlier date would have been found,
        // emptied and the note dropped — and the bill that actually needed the
        // notice never looked at.
        val pays = listOf(
            Pay("Підписка Adobe", 429.93, day = 10),
            Pay("Інтернет", 300.0, day = 12, warnDays = 3)
        )
        val marks = listOf(PaidMark("Підписка Adobe", monthKey(today), 429.93))

        val note = statusNote(emptyList(), pays, emptyList(), today, 41.0, marks)

        assertEquals("Платіж через 2 дні", note?.title)
        assertTrue(note!!.detail.startsWith(money(300.0)))
        assertTrue(note.detail.endsWith("Інтернет"))
    }

    @Test
    fun `a charge falling next month is not settled by this month's mark`() {
        // Marked paid in March, and the next charge is the first of April. The
        // month the charge falls in is the one the mark has to be against, or every
        // monthly expense would go silent for good a few days after its first tick.
        val pays = listOf(Pay("Хостинг", 900.0, day = 1, warnDays = 30))
        val march = listOf(PaidMark("Хостинг", monthKey(today), 900.0))

        val note = statusNote(emptyList(), pays, emptyList(), today, 41.0, march)

        assertEquals(StatusKind.PAYMENT, note?.kind)
        assertEquals("Платіж через 22 дні", note?.title)
    }

    @Test
    fun `a purchase already filed stops counting down its storage`() {
        val filed = parcel(today).copy(archivedDay = today.toEpochDay())
        assertNull(statusNote(listOf(filed), emptyList(), emptyList(), today, 41.0))
    }

    @Test
    fun `a wish put aside on purpose does not announce its price`() {
        val held = wish(price = 11200.0, target = 12000.0)
            .copy(holdUntil = today.plusDays(20).toEpochDay())
        assertNull(statusNote(emptyList(), emptyList(), listOf(held), today, 41.0))

        // The day the hold runs out it is news again, because that is the day the
        // person asked to be asked.
        val ended = held.copy(holdUntil = today.toEpochDay())
        assertEquals(
            StatusKind.TARGET,
            statusNote(emptyList(), emptyList(), listOf(ended), today, 41.0)?.kind
        )
    }

    // -- waving one away --------------------------------------------------------

    @Test
    fun `a note waved away today stays away today`() {
        val orders = listOf(parcel(today.plusDays(2)))
        val shown = statusNote(orders, emptyList(), emptyList(), today, 41.0)!!
        val swiped = NoteDismissal(shown.key, today.toEpochDay())

        assertNull(statusNote(orders, emptyList(), emptyList(), today, 41.0, emptyList(), swiped))
    }

    @Test
    fun `the same note comes back tomorrow`() {
        // A price target stays reached, so nothing about the note itself would ever
        // bring it back. The day the dismissal was made is what does it, and that is
        // the whole reason the day is stored alongside the key.
        val wishes = listOf(wish(price = 11200.0, target = 12000.0))
        val shown = statusNote(emptyList(), emptyList(), wishes, today, 41.0)!!
        val swiped = NoteDismissal(shown.key, today.toEpochDay())

        assertNull(statusNote(emptyList(), emptyList(), wishes, today, 41.0, emptyList(), swiped))
        assertEquals(
            StatusKind.TARGET,
            statusNote(
                emptyList(), emptyList(), wishes, today.plusDays(1), 41.0, emptyList(), swiped
            )?.kind
        )
    }

    @Test
    fun `worse news the same day still gets through`() {
        // Two free days left, waved away. The carrier is asked again and the answer
        // is that storage is already being billed: a different matter, and the
        // swipe was never consent to be kept in the dark about it.
        val calm = statusNote(
            listOf(parcel(today.plusDays(2))), emptyList(), emptyList(), today, 41.0
        )!!
        val swiped = NoteDismissal(calm.key, today.toEpochDay())

        val note = statusNote(
            listOf(parcel(today)), emptyList(), emptyList(), today, 41.0, emptyList(), swiped
        )

        assertEquals("зберігання вже платне", note?.detail)
        assertTrue(note!!.urgent)
    }

    @Test
    fun `a dismissal covers one note and not the one under it`() {
        // The payment outranks the target, so if the winner were picked before the
        // dismissal were consulted, a reached price would go unmentioned all day on
        // the strength of a swipe that was about a bill.
        val pays = listOf(Pay("Оренда квартири", 12000.0, day = 10))
        val wishes = listOf(wish(price = 11200.0, target = 12000.0))
        val swiped = NoteDismissal(
            statusNote(emptyList(), pays, wishes, today, 41.0)!!.key,
            today.toEpochDay()
        )

        assertEquals(
            StatusKind.TARGET,
            statusNote(emptyList(), pays, wishes, today, 41.0, emptyList(), swiped)?.kind
        )
    }

    @Test
    fun `a dismissal from any other day silences nothing`() {
        val orders = listOf(parcel(today.plusDays(2)))
        val shown = statusNote(orders, emptyList(), emptyList(), today, 41.0)!!

        // Yesterday's swipe has expired; and one dated tomorrow — a clock that ran
        // ahead and was then corrected — must not buy silence until the calendar
        // catches up with the wrong reading.
        for (day in listOf(today.minusDays(1), today.plusDays(1))) {
            val stale = NoteDismissal(shown.key, day.toEpochDay())
            assertEquals(
                StatusKind.PARCEL,
                statusNote(orders, emptyList(), emptyList(), today, 41.0, emptyList(), stale)?.kind
            )
        }
    }

    @Test
    fun `the three kinds never share a key`() {
        val notes = statusNotes(
            listOf(parcel(today.plusDays(1))),
            listOf(Pay("Оренда квартири", 12000.0, day = 10)),
            listOf(wish(price = 11200.0, target = 12000.0)),
            today,
            41.0
        )
        assertEquals(3, notes.size)
        assertEquals(3, notes.map { it.key }.distinct().size)
        assertTrue(notes.none { it.key.isBlank() })
    }

    @Test
    fun `a key is structure rather than the wording on the pill`() {
        // Stored in the phone's preferences and compared across sessions, so it
        // must not carry anything a formatter decides: the date is ISO and the
        // countdown is a bare number, neither of which a locale can rewrite.
        val parcelKey = statusNote(
            listOf(parcel(today.plusDays(2))), emptyList(), emptyList(), today, 41.0
        )!!.key
        assertEquals("parcel:2", parcelKey)

        val paymentKey = statusNote(
            emptyList(), listOf(Pay("Оренда квартири", 12000.0, day = 10)),
            emptyList(), today, 41.0
        )!!.key
        assertEquals("payment:2026-03-10", paymentKey)

        val targetKey = statusNote(
            emptyList(), emptyList(), listOf(wish(price = 11200.0, target = 12000.0)), today, 41.0
        )!!.key
        assertEquals("target:Sony WH-1000XM5", targetKey)
    }

    @Test
    fun `the countdown moves the parcel key with it`() {
        fun keyWith(days: Long) = statusNote(
            listOf(parcel(today.plusDays(days))), emptyList(), emptyList(), today, 41.0
        )!!.key
        assertEquals(3, listOf(keyWith(0), keyWith(1), keyWith(2)).distinct().size)
    }

    @Test
    fun `the large title hands over exactly when it would slide under the bar`() {
        val bar = 56
        assertFalse(titleCollapsed(headerBottom = bar + 1, barHeight = bar))
        assertTrue(titleCollapsed(headerBottom = bar, barHeight = bar))
        assertTrue(titleCollapsed(headerBottom = 0, barHeight = bar))
        // Scrolled off the list entirely.
        assertTrue(titleCollapsed(headerBottom = null, barHeight = bar))
    }
}

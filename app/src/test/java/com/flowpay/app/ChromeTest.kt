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

package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The parcel page: the fields behind it, the three date formats in one response,
 * and the observation log that stands in for a route.
 *
 * The response fixture below is the real shape of `getStatusDocuments` with the
 * field names and formats taken from a live call, but with invented places and
 * numbers, so nothing about a real parcel or address is committed here.
 */
class ParcelDetailTest {

    private val response = """
        {"success":true,"data":[{
          "Number":"20450000000001",
          "StatusCode":"7",
          "Status":"Прибув у місто одержувача",
          "DateCreated":"14-09-2026 15:03:19",
          "DateScan":"15:24 14.09.2026",
          "TrackingUpdateDate":"2026-09-14 15:24:19",
          "ScheduledDeliveryDate":"16-09-2026 12:00:00",
          "DatePayedKeeping":"20-09-2026 00:00:00",
          "CitySender":"Львів",
          "CityRecipient":"Київ",
          "WarehouseSender":"Відділення №12",
          "WarehouseSenderAddress":"вул. Наукова, 1",
          "WarehouseRecipient":"Поштомат №30021",
          "WarehouseRecipientAddress":"просп. Перемоги, 5",
          "WarehouseRecipientNumber":"30021",
          "CategoryOfWarehouse":"Postomat",
          "ServiceType":"WarehouseWarehouse",
          "DocumentWeight":0.5,
          "FactualWeight":"0.54",
          "VolumeWeight":"1.2",
          "SeatsAmount":"1",
          "PayerType":"Recipient",
          "PaymentMethod":"Cash",
          "AmountToPay":"249.50",
          "CargoType":"Parcel",
          "PossibilityCreateRedirecting":true,
          "PossibilityCreateRefusal":false,
          "PossibilityTermExtension":true
        }]}
    """.trimIndent()

    private fun parsed() = parseNovaPoshtaStatus(response)!!

    // -- the three date formats -------------------------------------------------

    @Test
    fun `each of the three date formats in one response is read`() {
        val details = parsed().details
        assertEquals(LocalDateTime.of(2026, 9, 14, 15, 3, 19), details.createdAt)
        assertEquals(LocalDateTime.of(2026, 9, 14, 15, 24), details.scannedAt)
        assertEquals(LocalDateTime.of(2026, 9, 14, 15, 24, 19), details.trackingUpdatedAt)
    }

    @Test
    fun `a format read by the wrong parser is treated as absent, not as a wrong date`() {
        // The trap this response sets: 14-09-2026 and 2026-09-14 and 14.09.2026 all
        // describe the same day, and any parser lenient enough to take all three
        // would eventually read 09-10-2026 as the wrong month.
        assertNull(parseScanMoment("14-09-2026 15:03:19"))
        assertNull(parseCreatedMoment("15:24 14.09.2026"))
        assertNull(parseCreatedMoment("2026-09-14 15:24:19"))
        assertNull(parseUpdateMoment("14-09-2026 15:03:19"))
    }

    @Test
    fun `an empty or unreadable date is null rather than a raw string`() {
        for (raw in listOf("", "   ", "0000-00-00 00:00:00", "не вказано", "14-09-2026")) {
            assertNull(raw, parseCreatedMoment(raw))
            assertNull(raw, parseScanMoment(raw))
            assertNull(raw, parseUpdateMoment(raw))
        }
    }

    @Test
    fun `DateCreated is accepted with the dots the carrier also sends`() {
        assertEquals(
            LocalDateTime.of(2026, 8, 4, 8, 10, 48),
            parseCreatedMoment("04.08.2026 08:10:48")
        )
    }

    // -- the rest of the fields -------------------------------------------------

    @Test
    fun `the fields the page shows come off the response`() {
        val details = parsed().details
        assertEquals("Львів", details.citySender)
        assertEquals("Київ", details.cityRecipient)
        assertEquals("Відділення №12", details.warehouseSender)
        assertEquals("вул. Наукова, 1", details.warehouseSenderAddress)
        assertEquals("Поштомат №30021", details.warehouseRecipient)
        assertEquals("просп. Перемоги, 5", details.warehouseRecipientAddress)
        assertEquals("30021", details.warehouseNumber)
        assertEquals("Postomat", details.warehouseCategory)
        assertEquals("WarehouseWarehouse", details.serviceType)
        assertEquals(1, details.seats)
        assertEquals("Recipient", details.payerType)
        assertEquals("Cash", details.paymentMethod)
        assertEquals("Parcel", details.cargoType)
        assertTrue(details.canRedirect)
        assertFalse(details.canRefuse)
        assertTrue(details.canExtendTerm)
    }

    @Test
    fun `weights arrive both as numbers and as strings and both are read`() {
        val details = parsed().details
        assertEquals(0.5, details.documentWeight, 0.0001)
        assertEquals(0.54, details.factualWeight, 0.0001)
        assertEquals(1.2, details.volumeWeight, 0.0001)
        // A comma is what some parcels carry, and a blank field is not a weight.
        assertEquals(0.54, carrierNumber("0,54"), 0.0001)
        assertEquals(0.0, carrierNumber(""), 0.0001)
        assertEquals(0.0, carrierNumber("важка"), 0.0001)
        assertEquals(0.0, carrierNumber("-3"), 0.0001)
    }

    @Test
    fun `a response with nothing filled in yields an empty record rather than junk`() {
        val bare = parseNovaPoshtaStatus(
            """{"success":true,"data":[{"StatusCode":"1","Status":"Замовлення в дорозі"}]}"""
        )!!
        assertTrue(bare.details.isEmpty)
        assertNull(bare.details.createdAt)
        assertEquals(0.0, bare.details.factualWeight, 0.0001)
    }

    // -- the observation log ----------------------------------------------------

    private fun statusOf(code: Int, text: String) =
        parseNovaPoshtaStatus("""{"success":true,"data":[{"StatusCode":"$code","Status":"$text"}]}""")!!

    @Test
    fun `an unchanged status is not recorded again`() {
        val first = recordSighting(emptyList(), statusOf(4, "В дорозі"), 1_000L)
        assertEquals(1, first.size)
        val second = recordSighting(first, statusOf(4, "В дорозі"), 9_000L)
        assertEquals(1, second.size)
        assertEquals(1_000L, second[0].atMillis)
    }

    @Test
    fun `every change is recorded with the time the app saw it`() {
        var seen = recordSighting(emptyList(), statusOf(1, "Замовлення оформлено"), 1_000L)
        seen = recordSighting(seen, statusOf(4, "В дорозі"), 2_000L)
        seen = recordSighting(seen, statusOf(7, "Прибув у відділення"), 3_000L)
        assertEquals(listOf(1, 4, 7), seen.map { it.code })
        assertEquals(listOf(1_000L, 2_000L, 3_000L), seen.map { it.atMillis })
        assertEquals("Прибув у відділення", seen.last().text)
    }

    @Test
    fun `the same code with new wording is a change worth keeping`() {
        val seen = recordSighting(
            recordSighting(emptyList(), statusOf(4, "Прямує до Львова"), 1_000L),
            statusOf(4, "Прямує до Києва"),
            2_000L
        )
        assertEquals(2, seen.size)
    }

    @Test
    fun `a status with no wording is not recorded`() {
        assertTrue(recordSighting(emptyList(), statusOf(4, ""), 1_000L).isEmpty())
    }

    @Test
    fun `the log is bounded and the oldest go first`() {
        var seen = emptyList<Sighting>()
        for (step in 1..SIGHTING_LIMIT + 10) {
            seen = recordSighting(seen, statusOf(4, "Крок $step"), step.toLong())
        }
        assertEquals(SIGHTING_LIMIT, seen.size)
        assertEquals("Крок ${SIGHTING_LIMIT + 10}", seen.last().text)
        assertEquals(11L, seen.first().atMillis)
    }

    @Test
    fun `applying a status writes both the details and the observation`() {
        val order = Order("1", "Пам'ять", "https://shop", ORDERED, "20450000000001")
        val after = applyStatus(order, parsed(), 5_000L)
        assertEquals("Київ", after.details.cityRecipient)
        assertEquals(1, after.sightings.size)
        assertEquals("Прибув у місто одержувача", after.sightings[0].text)
        assertEquals(5_000L, after.sightings[0].atMillis)
        // A second identical check is not a second movement.
        assertEquals(1, applyStatus(after, parsed(), 9_000L).sightings.size)
    }

    // -- what the page says about a log it does not have ------------------------

    @Test
    fun `a parcel never checked is told the list has not started yet`() {
        val note = sightingsNote(emptyList(), 0L)
        assertTrue(note, note.contains("ще не питав"))
    }

    @Test
    fun `a parcel that predates the log does not look like a parcel that has not moved`() {
        val note = sightingsNote(emptyList(), 1_700_000_000_000L)
        // It has to say the list is empty because the list is young, not because
        // the parcel is still.
        assertTrue(note, note.contains("раніше, ніж FlowPay почав"))
        assertFalse(note, note.contains("ще не питав"))
    }

    @Test
    fun `every wording of the note disclaims being the carrier's own journal`() {
        val one = listOf(Sighting(4, "В дорозі", 1L))
        assertTrue(sightingsNote(one, 1L).contains("до неї сюди не потрапляють"))
        val many = one + Sighting(7, "У відділенні", 2L)
        val note = sightingsNote(many, 2L)
        assertTrue(note, note.contains("бачив FlowPay"))
        assertTrue(note, note.contains("не журнал Нової Пошти"))
    }

    // -- the two timestamps, which is the point of the screen -------------------

    @Test
    fun `the two timestamps are named for different events`() {
        // If these ever read the same way the screen has lost its reason to exist.
        assertTrue(SCAN_LABEL.contains("сканувала"))
        assertTrue(ASKED_LABEL.contains("FlowPay"))
        assertTrue(SCAN_LABEL != ASKED_LABEL)
        assertTrue(TIMES_NOTE.contains("рухалась"))
        assertTrue(TIMES_NOTE.contains("питав"))
    }

    @Test
    fun `a scan is worded by how recent it is`() {
        val today = LocalDate.of(2026, 9, 14)
        assertEquals(
            "сьогодні о 15:24",
            scanValue(LocalDateTime.of(2026, 9, 14, 15, 24), today)
        )
        assertEquals(
            "вчора о 09:05",
            scanValue(LocalDateTime.of(2026, 9, 13, 9, 5), today)
        )
        assertEquals(
            "1 вересня, 09:05",
            scanValue(LocalDateTime.of(2026, 9, 1, 9, 5), today)
        )
        assertEquals(
            "1 вересня 2025, 09:05",
            scanValue(LocalDateTime.of(2025, 9, 1, 9, 5), today)
        )
    }

    @Test
    fun `a parcel never scanned says so instead of borrowing the app's own time`() {
        assertEquals("ще не сканувала", scanValue(null, LocalDate.of(2026, 9, 14)))
        assertEquals("ще не питав", askedValue(0L))
    }

    @Test
    fun `the clock is ASCII whatever the phone is set to`() {
        assertEquals("09:05", clockLabel(LocalDateTime.of(2026, 9, 1, 9, 5)))
        assertEquals("00:00", clockLabel(LocalDateTime.of(2026, 9, 1, 0, 0)))
        assertEquals("23:59", clockLabel(LocalDateTime.of(2026, 9, 1, 23, 59)))
    }

    @Test
    fun `standing still is reported in whole days and only while it matters`() {
        val now = LocalDateTime.of(2026, 9, 14, 18, 0)
        assertNull(standstillNote(null, now, IN_TRANSIT))
        assertNull(standstillNote(LocalDateTime.of(2026, 9, 14, 6, 0), now, IN_TRANSIT))
        assertEquals(
            "Не рухалась 1 день",
            standstillNote(LocalDateTime.of(2026, 9, 13, 6, 0), now, IN_TRANSIT)
        )
        assertEquals(
            "Не рухалась 3 дні",
            standstillNote(LocalDateTime.of(2026, 9, 11, 6, 0), now, AT_BRANCH)
        )
        // A parcel in his hands is supposed to stop moving.
        assertNull(standstillNote(LocalDateTime.of(2026, 9, 1, 6, 0), now, RECEIVED))
    }

    @Test
    fun `an observation is worded in the zone the phone is in`() {
        val kyiv = ZoneId.of("Europe/Kyiv")
        val noon = LocalDateTime.of(2026, 9, 14, 12, 0).toInstant(ZoneOffset.UTC).toEpochMilli()
        assertEquals(
            "сьогодні о 15:00",
            sightingLabel(Sighting(4, "В дорозі", noon), LocalDate.of(2026, 9, 14), kyiv)
        )
        assertEquals(
            "сьогодні о 12:00",
            sightingLabel(Sighting(4, "В дорозі", noon), LocalDate.of(2026, 9, 14), ZoneOffset.UTC)
        )
    }

    // -- the plain words for the technical fields -------------------------------

    @Test
    fun `a locker is named as a locker and explained as an errand`() {
        assertEquals("Поштомат", warehouseCategoryLabel("Postomat"))
        assertEquals("Відділення", warehouseCategoryLabel("Branch"))
        assertEquals("Пункт видачі", warehouseCategoryLabel("Parcel Shop"))
        assertEquals("", warehouseCategoryLabel("SomethingNew"))
        val note = collectionNote("Postomat", storageKnown = true)!!
        assertTrue(note, note.contains("комірки"))
        // The days themselves come from DatePayedKeeping, which is already on the
        // screen, so the note points at that row rather than quoting a tariff.
        assertTrue(note, note.contains("сказано вище"))
        assertFalse(collectionNote("Postomat", storageKnown = false)!!.contains("сказано вище"))
        assertNull(collectionNote("Branch", storageKnown = true))
        assertNull(collectionNote("", storageKnown = true))
    }

    @Test
    fun `the technical enumerations become sentences or nothing at all`() {
        assertEquals("Відділення → відділення", serviceTypeLabel("WarehouseWarehouse"))
        assertEquals("Відділення → адреса", serviceTypeLabel("WarehouseDoors"))
        assertEquals("", serviceTypeLabel("SomethingNew"))
        assertEquals("Одержувач", payerLabel("Recipient"))
        assertEquals("Відправник", payerLabel("Sender"))
        assertEquals("", payerLabel(""))
        assertEquals("Готівкою", paymentMethodLabel("Cash"))
        assertEquals("Безготівково", paymentMethodLabel("NonCash"))
        assertEquals("", paymentMethodLabel("Crypto"))
        assertEquals("Посилка", cargoTypeLabel("Parcel"))
        assertEquals("Шини та диски", cargoTypeLabel("TiresWheels"))
        assertEquals("", cargoTypeLabel("Unknown"))
    }

    @Test
    fun `plurals of boxes and weights read correctly`() {
        assertEquals("1 місце", seatsLabel(1))
        assertEquals("2 місця", seatsLabel(2))
        assertEquals("5 місць", seatsLabel(5))
        assertEquals("11 місць", seatsLabel(11))
        assertEquals("21 місце", seatsLabel(21))
        assertTrue(weightLabel(0.54).endsWith(" кг"))
    }

    @Test
    fun `what can still be done is a list of things to do, empty when nothing can`() {
        val options = parcelOptions(parsed().details)
        assertEquals(2, options.size)
        assertTrue(options[0].contains("переадресувати"))
        assertTrue(options[1].contains("зберігання"))
        assertTrue(parcelOptions(ParcelDetails()).isEmpty())
    }

    // -- storage, which is where a field added to one side goes missing ---------

    @Test
    fun `details survive a round trip through their own json`() {
        val details = parsed().details
        val back = detailsOf(JSONObject(detailsJson(details).toString()))
        assertEquals(details, back)
    }

    @Test
    fun `an absent details block reads as nothing fetched rather than as a crash`() {
        assertEquals(ParcelDetails(), detailsOf(null))
        assertTrue(detailsOf(JSONObject()).isEmpty)
        // A moment written as nonsense by some future version is not a date.
        assertNull(detailsOf(JSONObject().put("ds", "вчора")).scannedAt)
    }

    @Test
    fun `the observation log survives a round trip through its own json`() {
        val seen = listOf(Sighting(1, "Оформлено", 1_000L), Sighting(7, "У відділенні", 2_000L))
        assertEquals(seen, sightingsOf(sightingsJson(seen)))
        assertEquals(emptyList<Sighting>(), sightingsOf(null))
    }

    @Test
    fun `a whole parcel survives the mapping both sides, which is how the bin works`() {
        val order = applyStatus(
            Order(
                id = "1", name = "Модуль пам'яті", url = "https://shop", status = ORDERED,
                tracking = "20450000000001", image = "https://img", price = 1499.0,
                paid = 1399.0, lowestSeen = 1350.0, uses = 2, archivedDay = 0L
            ),
            parsed(),
            5_000L
        )
        val back = orderOf(JSONObject(orderJson(order).toString()))
        // The whole record, not a spot check: a field added to the data class and
        // to only one side of this mapping is exactly the bug this asserts against.
        assertEquals(order, back)
        assertNotNull(back.details.scannedAt)
        assertEquals(1, back.sightings.size)
    }

    @Test
    fun `a parcel stored before the page existed loads with an empty history`() {
        val old = JSONObject()
            .put("id", "1").put("n", "Стара посилка").put("u", "https://shop")
            .put("s", IN_TRANSIT).put("t", "20450000000001").put("ca", 1_700_000_000_000L)
        val order = orderOf(old)
        assertTrue(order.details.isEmpty)
        assertTrue(order.sightings.isEmpty())
        // And the screen explains that rather than implying it has not moved.
        assertTrue(sightingsNote(order.sightings, order.checkedAt).contains("раніше, ніж FlowPay"))
    }
}

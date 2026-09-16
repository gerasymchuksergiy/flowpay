package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parcel tracking talks to a service this project does not control, so the code
 * mapping and the response parsing are pinned here. Fixtures are written by hand
 * rather than captured, so no real parcel or address ends up in the repository.
 */
class TrackingTest {

    private fun response(code: String, status: String, city: String = "", warehouse: String = "") = """
        {"success":true,"data":[{
          "Number":"20450000000001",
          "StatusCode":"$code",
          "Status":"$status",
          "CityRecipient":"$city",
          "WarehouseRecipient":"$warehouse",
          "RecipientDateTime":""
        }]}
    """.trimIndent()

    @Test
    fun `fourteen digits is a nova poshta number`() {
        assertEquals(CARRIER_NOVA_POSHTA, detectCarrier("20450000000001"))
        assertEquals(CARRIER_NOVA_POSHTA, detectCarrier("5900 0000 0000 01"))
    }

    @Test
    fun `anything else is not automatically checkable`() {
        assertEquals(CARRIER_UNKNOWN, detectCarrier(""))
        assertEquals(CARRIER_UNKNOWN, detectCarrier("RA123456789UA"))
        assertEquals(CARRIER_UNKNOWN, detectCarrier("2045000000000"))
        assertEquals(CARRIER_UNKNOWN, detectCarrier("204500000000012"))
        assertEquals(CARRIER_UNKNOWN, detectCarrier("2045000000000A"))
    }

    // Every code the carrier defines is pinned one by one in StatusLadderTest.
    // Deliberately not sampled again here: two partial lists of the same mapping
    // is how a code ends up decided in one place and forgotten in the other, which
    // is the exact failure this ladder was rebuilt to stop.

    @Test
    fun `a code the carrier has not published changes no stage`() {
        assertEquals("", stageForStatusCode(999))
        assertFalse(isProblemCode(999))
    }

    @Test
    fun `a normal response is parsed into a status`() {
        val status = parseNovaPoshtaStatus(
            response("7", "Прибув на відділення", "Київ", "Відділення №12")
        )!!

        assertEquals(7, status.code)
        assertEquals("Прибув на відділення", status.text)
        assertEquals(AT_BRANCH, status.stage)
        assertEquals("Київ", status.city)
        assertFalse(status.problem)
    }

    @Test
    fun `an unknown number parses as a problem rather than a stage`() {
        val status = parseNovaPoshtaStatus(response("3", "Номер не знайдено"))!!

        assertEquals("", status.stage)
        assertTrue(status.problem)
    }

    @Test
    fun `a failed or malformed call yields null instead of a fake status`() {
        assertNull(parseNovaPoshtaStatus("""{"success":false,"errors":["API key expired"]}"""))
        assertNull(parseNovaPoshtaStatus("""{"success":true,"data":[]}"""))
        assertNull(parseNovaPoshtaStatus("not json at all"))
        assertNull(parseNovaPoshtaStatus(""))
        // A row with no status code is unusable.
        assertNull(parseNovaPoshtaStatus("""{"success":true,"data":[{"Number":"1"}]}"""))
    }

    @Test
    fun `the card line names the place when the carrier knows it`() {
        val located = parseNovaPoshtaStatus(
            response("7", "Прибув на відділення", "Львів", "Відділення №3")
        )!!
        assertEquals("Прибув на відділення · Львів, Відділення №3", statusLine(located))

        val unlocated = parseNovaPoshtaStatus(response("1", "Створено ЕН"))!!
        assertEquals("Створено ЕН", statusLine(unlocated))
    }

    @Test
    fun `the card calls the pickup point what the detail page calls it`() {
        // One parcel was a відділення on the card and a Поштомат on its own page,
        // because the card read WarehouseRecipientNumber and the page read
        // CategoryOfWarehouse. Both now go through warehouseCategoryLabel.
        assertEquals("поштомат №36706", pickupPointLabel("Postomat", "36706"))
        assertEquals("відділення №12", pickupPointLabel("Branch", "12"))
        assertEquals("відділення №12", pickupPointLabel("Warehouse", "12"))
        assertEquals("пункт видачі №7", pickupPointLabel("Parcel Shop", "7"))
        assertEquals("поштовий сервіс №4", pickupPointLabel("Postal Service", "4"))
        // A branch is what the overwhelming majority are, and it is the word that
        // was there before a category was read at all.
        assertEquals("відділення №12", pickupPointLabel("", "12"))
        assertEquals("відділення №12", pickupPointLabel("SomethingNew", "12"))
        // Nothing to hang it on.
        assertEquals("", pickupPointLabel("Postomat", ""))
    }

    @Test
    fun `a locker is a locker on the card too`() {
        val json = """
            {"success":true,"data":[{
              "StatusCode":"6",
              "Status":"Відправлення у м. Чернівці. Очікуйте повідомлення про прибуття",
              "CityRecipient":"Чернівці",
              "WarehouseRecipient":"Поштомат \"Нова Пошта\" №36706: вул. Руська, 255а",
              "WarehouseRecipientNumber":"36706",
              "CategoryOfWarehouse":"Postomat"
            }]}
        """.trimIndent()

        val status = parseNovaPoshtaStatus(json)!!

        assertEquals(
            "Відправлення у м. Чернівці. Очікуйте повідомлення про прибуття · " +
                "Чернівці, поштомат №36706",
            statusLine(status)
        )
        assertEquals(IN_TRANSIT, status.stage)
        assertFalse(status.problem)
    }

    @Test
    fun `the carrier's own code is kept on the parcel`() {
        // Without it every trouble reads as the same trouble.
        val order = Order("1", "Кросівки", "https://shop/x", IN_TRANSIT, tracking = "20450000000001")
        val stuck = parseNovaPoshtaStatus(
            response("111", "Невдала спроба доставки")
        )!!

        val updated = applyStatus(order, stuck, atMillis = 1_700_000_000_000L)

        assertEquals(111, updated.statusCode)
        assertTrue(updated.problem)
        // Not overwritten with a guess: the parcel really was in transit.
        assertEquals(IN_TRANSIT, updated.status)
        assertEquals(
            "Кур'єр не застав нікого за адресою. Доставку треба узгодити з Новою Поштою.",
            problemNote(updated.statusCode)
        )
    }

    @Test
    fun `applying a status advances the parcel and records the time`() {
        val order = Order("1", "Кросівки", "https://shop/x", ORDERED, tracking = "20450000000001")
        val status = parseNovaPoshtaStatus(response("5", "Прямує до міста", "Одеса"))!!

        val updated = applyStatus(order, status, atMillis = 1_700_000_000_000L)

        assertEquals(IN_TRANSIT, updated.status)
        assertEquals("Прямує до міста · Одеса", updated.statusDetail)
        assertEquals(1_700_000_000_000L, updated.checkedAt)
    }

    @Test
    fun `applying a problem status keeps the stage the user had`() {
        val order = Order("1", "Кросівки", "https://shop/x", IN_TRANSIT, tracking = "20450000000001")
        val status = parseNovaPoshtaStatus(response("3", "Номер не знайдено"))!!

        val updated = applyStatus(order, status, atMillis = 1L)

        assertEquals(IN_TRANSIT, updated.status)
        assertEquals("Номер не знайдено", updated.statusDetail)
    }

    @Test
    fun `the stage list is in the order a parcel travels`() {
        assertEquals(listOf(ORDERED, IN_TRANSIT, AT_BRANCH, RECEIVED), PARCEL_STAGES)
    }
}

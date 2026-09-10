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

    @Test
    fun `status codes map onto the four stages`() {
        assertEquals(ORDERED, stageForStatusCode(1))
        assertEquals(ORDERED, stageForStatusCode(11))
        assertEquals(IN_TRANSIT, stageForStatusCode(4))
        assertEquals(IN_TRANSIT, stageForStatusCode(5))
        assertEquals(IN_TRANSIT, stageForStatusCode(101))
        assertEquals(AT_BRANCH, stageForStatusCode(7))
        assertEquals(AT_BRANCH, stageForStatusCode(8))
        assertEquals(RECEIVED, stageForStatusCode(9))
        assertEquals(RECEIVED, stageForStatusCode(10))
    }

    @Test
    fun `codes that are not progress map to no stage at all`() {
        // A missing number or a refusal must not be turned into a stage.
        assertEquals("", stageForStatusCode(3))
        assertEquals("", stageForStatusCode(12))
        assertEquals("", stageForStatusCode(103))
        assertEquals("", stageForStatusCode(999))
        assertTrue(isProblemCode(3))
        assertTrue(isProblemCode(103))
        assertFalse(isProblemCode(7))
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

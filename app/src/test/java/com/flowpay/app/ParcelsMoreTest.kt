package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * Parcels and purchases, second round: the waybill in a shared text, the phone
 * number, cash on delivery, the pickup point, a return, the warranty and «Як тобі
 * …?». Every number below is invented; the directory records are Nova Poshta's
 * public branch directory as a live call answered it on 4 October 2026 (public
 * data about a branch and a locker, nobody's parcel).
 */
class ParcelsMoreTest {

    private val sunday = LocalDate.of(2026, 10, 4)
    private val monday = LocalDate.of(2026, 10, 5)
    private val day = monday.toEpochDay()

    private fun parcel(
        id: String = "p1",
        name: String = "Навушники",
        status: String = IN_TRANSIT,
        amountToPay: Double = 0.0,
        scheduled: Long = 0L,
        archived: Long = 0L,
        tracking: String = "20450000000001"
    ) = Order(
        id = id, name = name, url = "https://rozetka.com.ua/p1/", status = status,
        tracking = tracking, price = 1_299.0, amountToPay = amountToPay,
        scheduledDelivery = scheduled, archivedDay = archived
    )

    // ------------------------------------------------------------ the shared waybill

    @Test
    fun `a shop's sms with a link and a waybill goes to the parcel, not the wishlist`() {
        val sms = "Ваше замовлення відправлено. ТТН 20450000000001. Деталі: https://rozetka.com.ua/ua/order/123/"
        assertEquals("20450000000001", sharedParcelNumber(sms))
    }

    @Test
    fun `a waybill written in groups is read whole`() {
        assertEquals("20450000000001", sharedParcelNumber("Посилка 2045 0000 0000 01 прямує до вас https://np.ua/x"))
    }

    @Test
    fun `a plain product link is still a wish`() {
        assertNull(sharedParcelNumber("Дивись https://rozetka.com.ua/ua/apple-airpods/p123456/"))
        // Fourteen digits inside a product address are an id, not a waybill.
        assertNull(sharedParcelNumber("https://shop.example/p/12345678901234/ класні"))
        assertNull(sharedParcelNumber("https://shop.example/item?sku=12345678901234"))
    }

    @Test
    fun `nova poshta's own tracking link gives its waybill`() {
        assertEquals(
            "20450000000001",
            sharedParcelNumber("Відстежуйте: https://novaposhta.ua/tracking/?cargo_number=20450000000001")
        )
        assertTrue(isNovaPoshtaLink("https://novapost.com/uk-ua/tracking/20450000000001"))
        assertFalse(isNovaPoshtaLink("https://rozetka.com.ua/"))
    }

    @Test
    fun `a postal number beside a link stays with the link, and phones are not waybills`() {
        assertNull(sharedParcelNumber("Tracking RL778364634EE https://temu.com/order/1"))
        assertNull(sharedParcelNumber("Дзвоніть +380 99 000 00 01 https://shop.example/p"))
        assertNull(sharedParcelNumber("Картка 4149 0000 0000 0001 https://shop.example/p"))
        assertNull(sharedParcelNumber(null))
        assertNull(sharedParcelNumber(""))
    }

    @Test
    fun `a message with no link at all is still found`() {
        assertEquals("20450000000001", sharedParcelNumber("Ваша посилка 20450000000001 у відділенні №12"))
    }

    @Test
    fun `a waybill already on the list is that parcel, the return waybill included`() {
        val going = parcel(id = "a")
        val back = parcel(id = "b", tracking = "20450000000002", archived = day - 5)
            .copy(refund = Refund(startedDay = day - 2, amount = 500.0, tracking = "20450000000099"))
        assertEquals("a", knownParcel(listOf(going, back), "2045 0000 0000 01")?.id)
        assertEquals("b", knownParcel(listOf(going, back), "20450000000099")?.id)
        assertNull(knownParcel(listOf(going, back), "20450000000555"))
        // A postal number is matched whole, letters and all.
        val temu = parcel(id = "t", tracking = "RL778364634EE")
        assertEquals("t", knownParcel(listOf(temu), "rl778364634ee")?.id)
        assertNull(knownParcel(listOf(temu), "LP778364634CN"))
        assertNull(knownParcel(listOf(parcel(id = "none", tracking = "")), " "))
    }

    // ------------------------------------------------------------ the phone number

    @Test
    fun `a phone number is read however it was typed`() {
        val expected = "380990000001"
        assertEquals(expected, normalizedPhone("099 000 00 01"))
        assertEquals(expected, normalizedPhone("+38 (099) 000-00-01"))
        assertEquals(expected, normalizedPhone("380990000001"))
        assertEquals(expected, normalizedPhone("80990000001"))
        assertEquals(expected, normalizedPhone("990000001"))
        assertEquals("", normalizedPhone("099 000"))
        assertEquals("", normalizedPhone("щось"))
        assertEquals("", normalizedPhone(""))
    }

    @Test
    fun `the settings row shows only the last two digits`() {
        assertEquals("+380 •• ••• •• 01", maskedPhone("0990000001"))
        assertEquals("", maskedPhone(""))
    }

    @Test
    fun `a parcel for somebody else is asked with their number, else with the owner's`() {
        val mine = "0990000001"
        assertEquals("380990000001", phoneFor(parcel(), mine))
        assertEquals("380990000002", phoneFor(parcel().copy(recipientPhone = "099 000 00 02"), mine))
        // A half-typed number of the parcel's own is not sent; the owner's is.
        assertEquals("380990000001", phoneFor(parcel().copy(recipientPhone = "099"), mine))
        assertEquals("", phoneFor(parcel(), ""))
    }

    @Test
    fun `the tracking request carries the phone in the shape checked live`() {
        val body = trackingRequest("2045 0000 0000 01", "099 000 00 01")
        assertEquals("", body.getString("apiKey"))
        assertEquals("TrackingDocument", body.getString("modelName"))
        assertEquals("getStatusDocuments", body.getString("calledMethod"))
        val doc = body.getJSONObject("methodProperties").getJSONArray("Documents").getJSONObject(0)
        assertEquals("20450000000001", doc.getString("DocumentNumber"))
        assertEquals("380990000001", doc.getString("Phone"))
        // Without a number the field is still sent, empty, as it always was.
        val bare = trackingRequest("20450000000001", "").getJSONObject("methodProperties")
            .getJSONArray("Documents").getJSONObject(0)
        assertEquals("", bare.getString("Phone"))
    }

    @Test
    fun `the directory request is by ref with an empty key`() {
        val body = pointRequest(" 731a002c-3ed2-11e6-a9f2-005056887b8d ")
        assertEquals("", body.getString("apiKey"))
        assertEquals("Address", body.getString("modelName"))
        assertEquals("getWarehouses", body.getString("calledMethod"))
        assertEquals("731a002c-3ed2-11e6-a9f2-005056887b8d", body.getJSONObject("methodProperties").getString("Ref"))
    }

    // ------------------------------------------------------------ the fuller answer

    // The shape of getStatusDocuments with the fields a matching phone fills in.
    // Places, names and sums are invented.
    private val fullAnswer = """
        {"success":true,"data":[{
          "Number":"20450000000001","StatusCode":"7","Status":"Прибув у відділення",
          "CityRecipient":"Київ","WarehouseRecipient":"Відділення №12",
          "WarehouseRecipientNumber":"12","CategoryOfWarehouse":"Branch",
          "WarehouseRecipientRef":"731a002c-3ed2-11e6-a9f2-005056887b8d",
          "AmountToPay":"1349.50","DocumentCost":"100","AfterpaymentOnGoodsCost":0,
          "RedeliverySum":"1249.50","StorageAmount":"","CounterpartySenderDescription":"Магазин Приклад",
          "RecipientDateTime":"","ScheduledDeliveryDate":"05-10-2026 12:00:00"
        }]}
    """.trimIndent()

    @Test
    fun `the fields a phone unlocks are read`() {
        val status = parseNovaPoshtaStatus(fullAnswer)!!
        assertEquals(1349.5, status.amountToPay, 0.001)
        assertEquals("731a002c-3ed2-11e6-a9f2-005056887b8d", status.details.warehouseRef)
        assertEquals(100.0, status.details.deliveryCost, 0.001)
        // Payment control first; the classic money transfer when that is nought.
        assertEquals(1249.5, status.details.goodsToPay, 0.001)
        assertEquals(0.0, status.details.storageCharged, 0.001)
        assertEquals("Магазин Приклад", status.details.sender)
        assertNull(status.details.receivedAt)
    }

    @Test
    fun `the moment it was collected is read in the carrier's dotted format`() {
        val collected = fullAnswer.replace("\"RecipientDateTime\":\"\"", "\"RecipientDateTime\":\"21.09.2026 14:30:12\"")
        assertEquals(LocalDateTime.of(2026, 9, 21, 14, 30, 12), parseNovaPoshtaStatus(collected)!!.details.receivedAt)
    }

    @Test
    fun `the new details survive their own json, and old details read as empty`() {
        val details = parseNovaPoshtaStatus(fullAnswer)!!.details.copy(
            storageCharged = 45.0,
            receivedAt = LocalDateTime.of(2026, 9, 21, 14, 30, 12)
        )
        assertEquals(details, detailsOf(JSONObject(detailsJson(details).toString())))
        val old = detailsOf(JSONObject().put("wn", "12").put("cr", "Київ"))
        assertEquals("", old.warehouseRef)
        assertEquals(0.0, old.deliveryCost, 0.0)
        assertNull(old.receivedAt)
    }

    // ------------------------------------------------------------ storage of everything new

    private fun everything() = Order(
        id = "o1", name = "Навушники", url = "https://rozetka.com.ua/p1/", status = RECEIVED,
        tracking = "20450000000001", price = 1_299.0, paid = 1_199.0, lowestSeen = 1_150.0, uses = 3,
        archivedDay = day - 30, returnBy = day - 16, emoji = "🎧",
        details = parseNovaPoshtaStatus(fullAnswer)!!.details.copy(receivedAt = LocalDateTime.of(2026, 9, 5, 10, 0)),
        recipientPhone = "380990000002",
        refund = Refund(
            startedDay = day - 20, amount = 1_199.0, tracking = "20450000000099", reason = "не той колір",
            days = 14, shopGotDay = day - 18, backDay = day - 2, statusCode = 9,
            statusText = "Відправлення отримано", checkedAt = 1_700_000_000_000L
        ),
        warrantyUntil = day + 300, delight = 4, again = -1, delightDay = day - 9,
        why = "щоб бігати з музикою", category = "Гаджети"
    )

    @Test
    fun `a purchase with every new field survives the mapping both ways`() {
        val order = everything()
        // The whole record, not a spot check — a field on one side only is the bug.
        assertEquals(order, orderOf(JSONObject(orderJson(order).toString())))
        // And through the bin, which stores exactly this.
        assertEquals(order, orderOf(JSONObject(binEntryOf(order, day).payload)))
    }

    @Test
    fun `every key added to a stored purchase starts with pk`() {
        // The keys this round added, by name: other work added keys to the same
        // record on the same day under prefixes of its own.
        val mine = setOf("pkPh", "pkRet", "pkWu", "pkJoy", "pkAgain", "pkJoyDay", "pkWhy", "pkCat")
        val keys = orderJson(everything()).keys().asSequence().toSet()
        assertTrue(keys.containsAll(mine))
        assertTrue(mine.all { it.startsWith("pk") })
        assertTrue(refundJson(everything().refund!!).keys().asSequence().all { it.startsWith("pk") })
        val detailKeys = detailsJson(everything().details).keys().asSequence().toSet()
        val oldDetail = setOf(
            "cs", "cr", "ws", "wsa", "wr", "wra", "wn", "wc", "st", "dc", "ds", "du",
            "dw", "fw", "vw", "sa", "pt", "pm", "ct", "rd", "rf", "te"
        )
        assertTrue((detailKeys - oldDetail).all { it.startsWith("pk") })
    }

    @Test
    fun `a purchase stored before any of this reads with nothing new set`() {
        val old = JSONObject().put("id", "1").put("n", "Стара").put("u", "").put("s", RECEIVED)
            .put("ar", day - 40).put("pd", 500.0)
        val order = orderOf(old)
        assertNull(order.refund)
        assertEquals("", order.recipientPhone)
        assertEquals(0L, order.warrantyUntil)
        assertEquals(0, order.delight)
        assertEquals("", order.why)
        assertEquals("", order.category)
    }

    @Test
    fun `nonsense in a stored answer is not believed`() {
        val odd = JSONObject(orderJson(everything()).toString()).put("pkJoy", 9).put("pkAgain", 7)
        val order = orderOf(odd)
        assertEquals(0, order.delight)
        assertEquals(1, order.again)
    }

    // ------------------------------------------------------------ cash on delivery

    @Test
    fun `cash on delivery falls on today at the branch, else on the promised day`() {
        val dues = codDues(
            listOf(
                parcel(id = "branch", status = AT_BRANCH, amountToPay = 249.5),
                parcel(id = "road", amountToPay = 1_249.0, scheduled = day + 2),
                parcel(id = "late", amountToPay = 300.0, scheduled = day - 3),
                parcel(id = "nodate", amountToPay = 100.0)
            ),
            monday
        )
        assertEquals(listOf("branch", "late", "road", "nodate"), dues.map { it.orderId })
        assertEquals(monday, dues.first { it.orderId == "branch" }.day)
        assertEquals(monday.plusDays(2), dues.first { it.orderId == "road" }.day)
        assertEquals(monday, dues.first { it.orderId == "late" }.day)
        assertNull(dues.first { it.orderId == "nodate" }.day)
    }

    @Test
    fun `money that is paid, collected, filed or not coming is not owed`() {
        val dues = codDues(
            listOf(
                parcel(id = "paid", status = AT_BRANCH, amountToPay = 0.0),
                parcel(id = "inhand", status = RECEIVED, amountToPay = 500.0),
                parcel(id = "filed", status = AT_BRANCH, amountToPay = 500.0, archived = day - 1),
                parcel(id = "refused", amountToPay = 500.0).copy(problem = true, statusCode = 103),
                parcel(id = "nobodyhome", amountToPay = 500.0).copy(problem = true, statusCode = 111),
                parcel(id = "game", amountToPay = 500.0).copy(digital = true)
            ),
            monday
        )
        assertEquals(listOf("nobodyhome"), dues.map { it.orderId })
    }

    @Test
    fun `the line under the free money counts the parcels and adds their money`() {
        val dues = codDues(
            listOf(
                parcel(id = "a", status = AT_BRANCH, amountToPay = 249.0),
                parcel(id = "b", amountToPay = 1_249.0, scheduled = day + 1)
            ),
            monday
        )
        assertEquals("ще 2 посилки до оплати: ${money(1_498.0)}", codLine(dues))
        assertEquals(1_498.0, codTotal(dues), 0.0)
        assertNull(codLine(emptyList()))
    }

    @Test
    fun `a parcel rains on its day by the same rules as a bill`() {
        val week = moneyWeather(emptyList(), emptyList(), monday, 41.0, 40_000.0, 20_000.0)
        val dues = listOf(CodDue("a", "Навушники", 1_249.0, monday.plusDays(2)))
        val wet = weatherWithParcels(week, dues, 40_000.0, 20_000.0)
        val wednesday = wet[2]
        assertEquals(1_249.0, wednesday.leaving, 0.0)
        // 1 249 of 40 000 is past the 3% drizzle line: rain, as for a bill of 1 249.
        assertEquals(moneySky(1_249.0, 40_000.0, 20_000.0).emoji, wednesday.emoji)
        assertEquals(MoneySky.RAIN.emoji, wednesday.emoji)
        assertEquals(listOf("Навушники"), wednesday.names)
        // The other days are untouched.
        assertEquals(week[0], wet[0])
        // A parcel with no day promised stays out of the forecast.
        assertEquals(week, weatherWithParcels(week, listOf(CodDue("b", "x", 900.0, null)), 40_000.0, 20_000.0))
    }

    @Test
    fun `the forecast's chips name the day unless it is today`() {
        val dues = listOf(
            CodDue("a", "Навушники", 1_249.0, monday),
            CodDue("b", "Кросівки", 900.0, monday.plusDays(1)),
            CodDue("c", "Чохол", 100.0, monday.plusDays(2)),
            CodDue("d", "Далеко", 100.0, monday.plusDays(9)),
            CodDue("e", "Без дня", 100.0, null)
        )
        val chips = codChips(dues, monday)
        assertEquals(
            listOf(
                "${money(1_249.0)} · Навушники",
                "завтра · ${money(900.0)} · Кросівки",
                "Ср · ${money(100.0)} · Чохол"
            ),
            chips.map { it.text }
        )
        assertTrue(chips.all { it.emoji == "📦" })
    }

    @Test
    fun `the treat leaves room for the parcels`() {
        val wish = Wish("w", "Кавоварка", "https://shop/k", "", 3_000.0, history = listOf(PricePoint(3_000.0, day - 10)))
        assertNotNull(monthTreat(listOf(wish), 4_000.0, day))
        val dues = listOf(CodDue("a", "x", 1_000.0, monday))
        assertNull(monthTreat(listOf(wish), 4_000.0 - codTotal(dues), day))
    }

    @Test
    fun `the morning line about a waiting parcel says what it will still take`() {
        val waiting = parcel(status = AT_BRANCH, amountToPay = 249.5)
        val summary = digest(listOf(), listOf(), listOf(waiting), monday, 41.0, 0.0)
        assertEquals("Навушники — чекає на відділенні, до сплати ${money(249.5)}", summary.title)
        // Paid in the carrier's app: the sum goes by itself.
        val paid = digest(listOf(), listOf(), listOf(waiting.copy(amountToPay = 0.0)), monday, 41.0, 0.0)
        assertEquals("Навушники — чекає на відділенні", paid.title)
    }

    // ------------------------------------------------------------ the pickup point

    // Kyiv branch №12 and a 24-hour locker, as Address.getWarehouses answered on
    // 4 October 2026 (trimmed to the fields read). Public directory data.
    private val branchAnswer = """
        {"success":true,"data":[{
          "Description":"Відділення №12: вул. Родини Бунге, 8","Number":"12",
          "Ref":"731a002c-3ed2-11e6-a9f2-005056887b8d","CategoryOfWarehouse":"Branch",
          "POSTerminal":"1","GeneratorEnabled":"1","HasFittingRoom":"1","WarehouseStatus":"Working",
          "Reception":{"Monday":"08:00-21:00","Tuesday":"08:00-21:00","Wednesday":"08:00-21:00","Thursday":"08:00-21:00","Friday":"08:00-21:00","Saturday":"09:00-19:00","Sunday":"09:00-19:00"},
          "Delivery":{"Monday":"08:00-20:00","Tuesday":"08:00-20:00","Wednesday":"08:00-20:00","Thursday":"08:00-20:00","Friday":"08:00-20:00","Saturday":"09:00-19:00","Sunday":"09:00-19:00"},
          "Schedule":{"Monday":"08:00-21:00","Tuesday":"08:00-21:00","Wednesday":"08:00-21:00","Thursday":"08:00-21:00","Friday":"08:00-21:00","Saturday":"08:00-19:00","Sunday":"08:00-19:00"}
        }]}
    """.trimIndent()

    private val lockerAnswer = """
        {"success":true,"data":[{
          "Number":"1001","Ref":"locker-ref","CategoryOfWarehouse":"Postomat","PostMachineType":"FullDayService",
          "POSTerminal":"0","GeneratorEnabled":"0","HasFittingRoom":"0","WarehouseStatus":"Working",
          "Reception":{"Monday":"-","Tuesday":"-","Wednesday":"-","Thursday":"-","Friday":"-","Saturday":"-","Sunday":"-"},
          "Delivery":{"Monday":"-","Tuesday":"-","Wednesday":"-","Thursday":"-","Friday":"-","Saturday":"-","Sunday":"-"},
          "Schedule":{"Monday":"00:01-23:59","Tuesday":"00:01-23:59","Wednesday":"00:01-23:59","Thursday":"00:01-23:59","Friday":"00:01-23:59","Saturday":"00:01-23:59","Sunday":"00:01-23:59"}
        }]}
    """.trimIndent()

    private fun branch() = parsePickupPoint(branchAnswer, 1_000L)!!
    private fun locker() = parsePickupPoint(lockerAnswer, 1_000L)!!

    @Test
    fun `the collecting hours are the schedule, never the same-day dispatch cut-off`() {
        val point = branch()
        // Delivery says 20:00 on a weekday; the point is open, and hands parcels
        // out, until 21:00 — see PickupPoint for the evidence.
        assertEquals(DayHours(LocalTime.of(8, 0), LocalTime.of(21, 0)), point.hours[DayOfWeek.MONDAY])
        assertEquals(DayHours(LocalTime.of(8, 0), LocalTime.of(19, 0)), point.hours[DayOfWeek.SUNDAY])
        assertTrue(point.generator && point.terminal && point.fittingRoom && point.working)
        assertEquals("Branch", point.category)
    }

    @Test
    fun `a locker that never shuts is open all day, though it never sends anything`() {
        val point = locker()
        assertTrue(point.hours[DayOfWeek.MONDAY]!!.allDay)
        assertEquals(PointHours.ALL_DAY, pointHours(point, monday.atTime(3, 0)))
        assertEquals(listOf("цілодобово"), pickupChips(point, monday.atTime(3, 0), familiar = false).map { it.text })
    }

    @Test
    fun `the time chip follows the clock`() {
        val point = branch()
        assertEquals("сьогодні 08:00–21:00", hoursChip(point, monday.atTime(7, 0))!!.text)
        assertEquals("сьогодні до 21:00", hoursChip(point, monday.atTime(12, 0))!!.text)
        assertFalse(hoursChip(point, monday.atTime(19, 59))!!.warn)
        // An hour before closing it warns.
        val soon = hoursChip(point, monday.atTime(20, 0))!!
        assertTrue(soon.warn)
        assertEquals("скоро зачиняється · до 21:00", soon.text)
        // After closing it says when it opens.
        assertEquals("відкриється завтра о 08:00", hoursChip(point, monday.atTime(21, 30))!!.text)
        assertEquals("відкриється завтра о 08:00", hoursChip(point, sunday.atTime(20, 0))!!.text)
    }

    @Test
    fun `a point shut on sunday names the day it opens`() {
        val shut = parsePickupPoint(branchAnswer.replace("\"Sunday\":\"08:00-19:00\"", "\"Sunday\":\"-\""), 1L)!!
        assertNull(shut.hours[DayOfWeek.SUNDAY])
        assertTrue(shut.hours.containsKey(DayOfWeek.SUNDAY))
        assertEquals("відкриється завтра о 08:00", hoursChip(shut, sunday.atTime(10, 0))!!.text)
        assertEquals("у понеділок о 08:00", nextOpening(shut, LocalDate.of(2026, 10, 3).atTime(20, 0)))
        // The morning message tells a day off from a day already over.
        assertEquals("сьогодні не працює, відкриється завтра о 08:00", pickupUntilLine(shut, sunday.atTime(9, 0)))
        assertEquals("сьогодні вже зачинено, відкриється завтра о 08:00", pickupUntilLine(branch(), monday.atTime(21, 5)))
    }

    @Test
    fun `a new point shows the whole row, a familiar one only the warning`() {
        val point = branch()
        val noon = monday.atTime(12, 0)
        assertEquals(
            listOf("сьогодні до 21:00", "генератор", "термінал", "примірочна"),
            pickupChips(point, noon, familiar = false).map { it.text }
        )
        assertEquals(listOf("🕘", "⚡", "💳", "👕"), pickupChips(point, noon, familiar = false).map { it.emoji })
        assertTrue(pickupChips(point, noon, familiar = true).isEmpty())
        assertEquals(listOf("скоро зачиняється · до 21:00"), pickupChips(point, monday.atTime(20, 30), familiar = true).map { it.text })
    }

    @Test
    fun `a point the directory calls closed says so first`() {
        val closed = branch().copy(working = false)
        val chips = pickupChips(closed, monday.atTime(12, 0), familiar = false)
        assertEquals("за довідником зараз не працює", chips.first().text)
        assertTrue(chips.first().warn)
    }

    @Test
    fun `odd or missing hours are unknown rather than guessed`() {
        assertNull(parseDayHours("з 8 до 9"))
        assertTrue(isClosedMark("-"))
        assertEquals(DayHours(LocalTime.of(9, 0), LocalTime.of(23, 59)), parseDayHours("09:00-24:00"))
        val blind = branch().copy(hours = emptyMap())
        assertNull(hoursChip(blind, monday.atTime(12, 0)))
        assertEquals(PointHours.UNKNOWN, pointHours(blind, monday.atTime(12, 0)))
        assertNull(parsePickupPoint("{\"success\":false}", 1L))
        assertNull(parsePickupPoint("не json", 1L))
    }

    @Test
    fun `the morning message says until when it can be collected`() {
        assertEquals("забрати можна до 21:00", pickupUntilLine(branch(), monday.atTime(9, 0)))
        assertEquals("забрати можна цілодобово", pickupUntilLine(locker(), monday.atTime(9, 0)))
        val ref = branch().ref
        val waiting = parcel(status = AT_BRANCH).copy(details = ParcelDetails(warehouseRef = ref))
        val summary = digest(
            listOf(), listOf(), listOf(waiting), monday, 41.0, 0.0,
            points = mapOf(ref to branch()), now = monday.atTime(9, 0)
        )
        assertEquals("Навушники — чекає на відділенні, забрати можна до 21:00", summary.title)
    }

    @Test
    fun `a point's answer is kept a week, and survives its own json`() {
        val point = branch()
        assertEquals(point, pickupPointOf(JSONObject(pickupPointJson(point).toString())))
        val closedSunday = point.copy(hours = point.hours + (DayOfWeek.SUNDAY to null))
        assertEquals(closedSunday, pickupPointOf(pickupPointJson(closedSunday)))
        assertTrue(pointFresh(point, point.fetchedAt + 6 * 86_400_000L))
        assertFalse(pointFresh(point, point.fetchedAt + 8 * 86_400_000L))
        assertFalse(pointFresh(null, 0L))
    }

    @Test
    fun `a point collected from before is familiar, by its id or by number and city`() {
        val here = parcel(id = "now", status = AT_BRANCH)
            .copy(details = ParcelDetails(warehouseRef = "r12", warehouseNumber = "12", cityRecipient = "Київ"))
        val before = parcel(id = "old", status = RECEIVED, archived = day - 40)
            .copy(details = ParcelDetails(warehouseRef = "r12"))
        val oldNoId = parcel(id = "older", status = RECEIVED, archived = day - 90)
            .copy(details = ParcelDetails(warehouseNumber = "12", cityRecipient = "київ"))
        val elsewhere = parcel(id = "far", status = RECEIVED, archived = day - 10)
            .copy(details = ParcelDetails(warehouseRef = "r99"))
        assertTrue(familiarPoint(listOf(here, before), here))
        assertTrue(familiarPoint(listOf(here, oldNoId), here.copy(details = here.details.copy(warehouseRef = ""))))
        assertFalse(familiarPoint(listOf(here, elsewhere), here))
        // Itself does not count, and neither does one still on its way there.
        assertFalse(familiarPoint(listOf(here, before.copy(status = IN_TRANSIT, archivedDay = 0L)), here))
    }

    @Test
    fun `only a parcel waiting at a point is asked about`() {
        val waiting = parcel(status = AT_BRANCH).copy(details = ParcelDetails(warehouseRef = "r12"))
        assertEquals("r12", pointToAsk(waiting))
        assertNull(pointToAsk(waiting.copy(status = IN_TRANSIT)))
        assertNull(pointToAsk(waiting.copy(archivedDay = day)))
        assertNull(pointToAsk(parcel(status = AT_BRANCH)))
    }

    // ------------------------------------------------------------ a return

    private fun filed() = parcel(status = RECEIVED, archived = day - 3).copy(paid = 1_199.0, returnBy = day + 11)

    @Test
    fun `a return starts from what was paid and keeps the purchase filed`() {
        assertEquals(1_199.0, refundDefaultAmount(filed()), 0.0)
        assertEquals(1_299.0, refundDefaultAmount(filed().copy(paid = 0.0)), 0.0)
        val going = startReturn(filed(), 1_199.0, "2045 0000 0000 99", " не той колір ", 30, day)
        val refund = going.refund!!
        assertEquals(day, refund.startedDay)
        assertEquals("20450000000099", refund.tracking)
        assertEquals("не той колір", refund.reason)
        assertEquals(day - 3, going.archivedDay)
        assertTrue(isReturning(going))
        assertFalse(countsAsBought(going))
        // The window has done its job: no more «повернути можна ще …».
        assertNull(returnDaysLeft(going, day))
        assertTrue(returnLines(listOf(going), day + 10).isEmpty())
    }

    @Test
    fun `not as in the photo, on a parcel just collected, files it and starts the return`() {
        val fresh = parcel(status = RECEIVED)
        val going = startReturn(fresh, 1_299.0, "", "", 30, day)
        assertEquals(day, going.archivedDay)
        assertEquals(RECEIVED, going.status)
        assertTrue(isReturning(going))
    }

    @Test
    fun `the return waybill is followed apart from the delivery's own history`() {
        val delivered = filed().copy(statusCode = 9, statusDetail = "Отримано · Київ")
        val going = startReturn(delivered, 1_199.0, "20450000000099", "", 30, day)
        assertTrue(followsReturn(going))
        val onTheWay = ParcelStatus(5, "Прямує до магазину", IN_TRANSIT, "", "", "", false, "", null, null, 0.0)
        val moving = applyReturnStatus(going, onTheWay, 5_000L, day + 1)
        assertEquals(0L, moving.refund!!.shopGotDay)
        assertEquals("Прямує до магазину", moving.refund!!.statusText)
        // The delivery's own fields are untouched.
        assertEquals(9, moving.statusCode)
        assertEquals("Отримано · Київ", moving.statusDetail)
        assertEquals(delivered.sightings, moving.sightings)
        val got = ParcelStatus(
            9, "Відправлення отримано", RECEIVED, "", "", "", false, "", null, null, 0.0,
            ParcelDetails(receivedAt = monday.plusDays(1).atTime(15, 0))
        )
        val there = applyReturnStatus(moving, got, 6_000L, day + 3)
        assertEquals(day + 1, there.refund!!.shopGotDay)
        assertFalse(followsReturn(there))
        // Without a date of its own the day it was seen is used.
        assertEquals(day + 3, applyReturnStatus(moving, got.copy(details = ParcelDetails()), 6_000L, day + 3).refund!!.shopGotDay)
    }

    @Test
    fun `without a nova poshta waybill the shop's receipt is a tap`() {
        val going = startReturn(filed(), 1_199.0, "", "", 30, day)
        assertFalse(followsReturn(going))
        val got = shopReceived(going, day + 2)
        assertEquals(day + 2, got.refund!!.shopGotDay)
        // A second tap does not move it.
        assertEquals(day + 2, shopReceived(got, day + 5).refund!!.shopGotDay)
    }

    @Test
    fun `the countdown to the money, and what it says when it is late`() {
        val got = shopReceived(startReturn(filed(), 1_199.0, "", "", 30, day), day)
        assertEquals("чекаю гроші: 3 з 30 днів", refundStatusLine(got.refund!!, day + 3))
        assertNull(refundOverdueDays(got.refund!!, day + 30))
        assertEquals(1, refundOverdueDays(got.refund!!, day + 31))
        assertEquals("чекаю гроші: 33 з 30 днів — строк минув", refundStatusLine(got.refund!!, day + 33))
        val notYet = startReturn(filed(), 1_199.0, "", "", 30, day)
        assertEquals("Відправлено · коли магазин отримає, позначте", refundStatusLine(notYet.refund!!, day))
    }

    @Test
    fun `the genitive after «з» is right for every count`() {
        assertEquals("з 30 днів", ofDaysLabel(30))
        assertEquals("з 21 дня", ofDaysLabel(21))
        assertEquals("з 1 дня", ofDaysLabel(1))
        assertEquals("з 11 днів", ofDaysLabel(11))
        assertEquals("з 7 днів", ofDaysLabel(7))
        assertEquals("з 14 днів", ofDaysLabel(14))
    }

    @Test
    fun `the shop is named the way people say it`() {
        assertEquals("Rozetka", shopName("https://rozetka.com.ua/ua/p1/"))
        assertEquals("Rozetka", shopName("https://m.rozetka.com.ua/p1"))
        assertEquals("Prom", shopName("https://prom.ua/p1"))
        assertEquals("Temu", shopName("https://www.temu.com/ua/x.html"))
        assertEquals("", shopName(""))
    }

    @Test
    fun `a late refund gets one morning line a week, worded without a gendered verb`() {
        val late = shopReceived(startReturn(filed(), 1_199.0, "", "", 14, day - 20), day - 16)
        assertEquals(
            "Rozetka: повернення отримано 16 днів тому — гроші прийшли?",
            refundDigestLine(late, day)
        )
        val first = purchaseOnceLines(listOf(late), day)
        assertEquals(1, first.size)
        // The same week, the same key: said once.
        assertEquals(first.single().key, purchaseOnceLines(listOf(late), day + 3).single().key)
        // A week on, a new key: said again.
        assertTrue(first.single().key != purchaseOnceLines(listOf(late), day + 7).single().key)
        // Not late: nothing.
        val onTime = shopReceived(startReturn(filed(), 1_199.0, "", "", 30, day), day)
        assertTrue(purchaseOnceLines(listOf(onTime), day + 5).isEmpty())
    }

    @Test
    fun `the money back files it as returned, out of every verdict`() {
        val back = moneyBack(startReturn(filed(), 1_000.0, "", "", 30, day), day + 9)
        assertTrue(isRefunded(back))
        assertFalse(isReturning(back))
        assertEquals(day + 9, back.refund!!.shopGotDay)
        assertEquals(0L, back.returnBy)
        assertFalse(countsAsBought(back))
        assertTrue(countsAsBought(cancelReturn(back)))
        assertNull(cancelReturn(back).refund)
        // Tapped too soon: back among the returns, the shop's receipt kept.
        val again = undoMoneyBack(back)
        assertTrue(isReturning(again))
        assertEquals(day + 9, again.refund!!.shopGotDay)
        assertEquals(filed(), undoMoneyBack(filed()))
    }

    @Test
    fun `«мені винні» adds up the returns still waiting`() {
        val a = shopReceived(startReturn(filed().copy(id = "a"), 1_199.0, "", "", 14, day - 30), day - 20)
        val b = startReturn(filed().copy(id = "b"), 1_949.0, "", "", 30, day)
        val done = moneyBack(startReturn(filed().copy(id = "c"), 500.0, "", "", 30, day), day)
        val owedNow = owed(listOf(a, b, done, filed()), day)!!
        assertEquals(2, owedNow.count)
        assertEquals(3_148.0, owedNow.total, 0.0)
        assertEquals(1, owedNow.overdue)
        assertEquals("${purchasesLabel(2)} · строк минув: 1", owedCaption(owedNow))
        assertNull(owed(listOf(filed()), day))
    }

    @Test
    fun `a returned purchase is out of the recap and the sniper's label`() {
        val start = LocalDate.of(2026, 9, 1).toEpochDay()
        val patient = Order(
            "r", "Навушники", "", RECEIVED, paid = 1_000.0, lowestSeen = 1_000.0, archivedDay = start + 5
        )
        val wishes = listOf(Wish("w", "Річ", "https://s/x", "", 100.0, history = listOf(PricePoint(100.0, start - 10))))
        fun kinds(order: Order) = monthlyRecap(
            wishes, emptyList(), listOf(order), emptyList(), "2026-09", LocalDate.of(2026, 10, 2), 0.0, 41.0
        ).cards.map { it.kind to it.headline }
        assertTrue(kinds(patient).contains(RecapKind.PATIENCE_PAID to "Навушники"))
        assertTrue(kinds(patient).contains(RecapKind.LABEL to "Снайпер"))
        val returned = moneyBack(startReturn(patient, 1_000.0, "", "", 30, start + 10), start + 20)
        assertFalse(kinds(returned).any { it.first == RecapKind.PATIENCE_PAID })
        assertFalse(kinds(returned).contains(RecapKind.LABEL to "Снайпер"))
    }

    @Test
    fun `money that came back is not counted as spent in the spreadsheet`() {
        val back = moneyBack(startReturn(filed().copy(archivedDay = LocalDate.of(2026, 9, 20).toEpochDay()), 1_199.0, "", "", 30, day), day)
        val row = expenseRows(emptyList(), emptyList(), listOf(back), 2026, 41.0).single()
        assertEquals("0,00", row[3])
        assertEquals("повернено 1199,00", row[6])
    }

    @Test
    fun `the return survives its own json`() {
        val refund = everything().refund!!
        assertEquals(refund, refundOf(JSONObject(refundJson(refund).toString())))
        assertNull(refundOf(null))
        // Days that make no sense fall back to the default.
        assertEquals(REFUND_DAYS_DEFAULT, refundOf(JSONObject(refundJson(refund).toString()).put("pkD", 0))!!.days)
    }

    // ------------------------------------------------------------ warranty

    @Test
    fun `a warranty runs from the day it was collected, else from filing`() {
        val collected = filed().copy(details = ParcelDetails(receivedAt = LocalDateTime.of(2026, 9, 21, 14, 0)))
        assertEquals(LocalDate.of(2026, 9, 21), warrantyStart(collected, day))
        val seen = filed().copy(
            sightings = listOf(
                Sighting(7, "У відділенні", LocalDate.of(2026, 9, 20).atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli()),
                Sighting(9, "Отримано", LocalDate.of(2026, 9, 22).atTime(9, 0).toInstant(ZoneOffset.UTC).toEpochMilli())
            )
        )
        assertEquals(LocalDate.of(2026, 9, 22), warrantyStart(seen, day, ZoneOffset.UTC))
        assertEquals(monday, warrantyStart(filed(), day))
    }

    @Test
    fun `the chips are months from that day, and a stored end finds its chip again`() {
        val start = LocalDate.of(2026, 9, 21)
        val year = warrantyEnd(start, 12)
        assertEquals(LocalDate.of(2027, 9, 21).toEpochDay(), year)
        assertEquals(12, warrantyChoice(year, start))
        assertEquals(0, warrantyChoice(0L, start))
        assertNull(warrantyChoice(LocalDate.of(2027, 1, 1).toEpochDay(), start))
        assertEquals(0L, warrantyEnd(start, 0))
        assertEquals("немає", warrantyChoiceLabel(0))
        assertEquals("24 міс", warrantyChoiceLabel(24))
    }

    @Test
    fun `under warranty until its last day, and never once given back`() {
        val covered = filed().copy(warrantyUntil = day + 40)
        assertTrue(onWarranty(covered, day))
        assertTrue(onWarranty(covered, day + 40))
        assertFalse(onWarranty(covered, day + 41))
        assertEquals("гарантія до ${formatDate(LocalDate.ofEpochDay(day + 40))}", warrantyChip(covered, day))
        val back = moneyBack(startReturn(covered, 1.0, "", "", 30, day), day)
        assertFalse(onWarranty(back, day))
        assertFalse(onWarranty(filed(), day))
    }

    @Test
    fun `a month before the end the morning message asks once whether it all works`() {
        // No return window, so the warranty is the only thing the message has to say.
        val covered = filed().copy(name = "Навушники", warrantyUntil = LocalDate.of(2026, 11, 14).toEpochDay(), returnBy = 0L)
        val today = LocalDate.of(2026, 10, 15).toEpochDay()
        assertEquals("Гарантія на «Навушники» до 14 листопада — усе працює?", warrantyDigestLine(covered, today))
        assertNull(warrantyDigestLine(covered, LocalDate.of(2026, 10, 14).toEpochDay()))
        assertNull(warrantyDigestLine(covered, LocalDate.of(2026, 11, 15).toEpochDay()))
        val lines = purchaseOnceLines(listOf(covered), today)
        assertEquals(1, lines.size)
        // Said once: a message that carried it is remembered and the next one is quiet.
        val first = digest(listOf(), listOf(), listOf(covered), LocalDate.ofEpochDay(today), 41.0, 0.0)
        assertEquals(lines.map { it.key }, first.said)
        val next = digest(
            listOf(), listOf(), listOf(covered), LocalDate.ofEpochDay(today + 1), 41.0, 0.0,
            said = first.said.toSet()
        )
        assertTrue(next.empty)
    }

    // ------------------------------------------------------------ «Як тобі …?»

    @Test
    fun `the question comes three weeks after filing and goes a week later`() {
        val bought = filed().copy(archivedDay = day)
        assertFalse(delightDue(bought, day + 20))
        assertTrue(delightDue(bought, day + 21))
        assertTrue(delightDue(bought, day + 27))
        assertFalse(delightDue(bought, day + 28))
        assertFalse(delightDue(answerDelight(bought, Delight.LOVE, day + 22), day + 23))
        assertFalse(delightDue(startReturn(bought, 1.0, "", "", 30, day + 1), day + 22))
        assertEquals("Як тобі «Навушники»?", delightQuestion(bought))
    }

    @Test
    fun `the answer is the owner's words beside the owner's emoji`() {
        val answered = answerAgain(
            answerDelight(filed().copy(why = "щоб бігати з музикою"), Delight.LOVE, day),
            yes = true
        )
        assertEquals(4, answered.delight)
        assertEquals(day, answered.delightDay)
        val answer = delightAnswer(answered)!!
        assertEquals("Хотілось, бо: «щоб бігати з музикою»", answer.why)
        assertEquals("😍", answer.emoji)
        assertEquals("купити ще раз — так", answer.again)
        assertEquals("купити ще раз — ні", delightAnswer(answerAgain(answered, yes = false))!!.again)
        assertNull(delightAnswer(filed()))
    }

    @Test
    fun `one morning reminder in the week, said once`() {
        val bought = filed().copy(archivedDay = day - 21)
        val lines = purchaseOnceLines(listOf(bought), day)
        assertEquals(listOf("Як тобі «Навушники»? Відповісти можна в архіві покупок"), lines.map { it.text })
        assertEquals(lines, purchaseOnceLines(listOf(bought), day + 1))
    }

    @Test
    fun `a category speaks once three purchases in it are rated`() {
        fun rated(id: String, joy: Int, category: String = "Гаджети") =
            filed().copy(id = id, delight = joy, delightDay = day, category = category)
        val three = listOf(rated("a", 4), rated("b", 4), rated("c", 2))
        assertEquals(CategoryJoy("Гаджети: 2 з 3 —", "😍"), categoryJoy(three, "гаджети"))
        assertNull(categoryJoy(three.take(2), "Гаджети"))
        assertNull(categoryJoy(three + rated("d", 4, "Одяг"), "Одяг"))
        // A tie goes to the warmer answer.
        val tie = listOf(rated("a", 4), rated("b", 4), rated("c", 1), rated("d", 1))
        assertEquals("😍", categoryJoy(tie, "Гаджети")!!.emoji)
        // «Інше» is the absence of a category.
        val other = listOf(rated("a", 4, OTHER_CATEGORY), rated("b", 4, OTHER_CATEGORY), rated("c", 4, OTHER_CATEGORY))
        assertNull(categoryJoy(other, OTHER_CATEGORY))
    }

    @Test
    fun `the recap's warm card takes 😍 first, 🙂 when there is none, and nothing cold`() {
        val first = LocalDate.of(2026, 9, 1).toEpochDay()
        val last = LocalDate.of(2026, 9, 30).toEpochDay()
        fun rated(id: String, joy: Int, on: Long) = filed().copy(id = id, name = id, delight = joy, delightDay = on)
        assertEquals(listOf("b", "a"), delightedIn(listOf(rated("a", 4, first + 1), rated("b", 4, first + 5), rated("c", 3, first + 6)), first, last).map { it.id })
        assertEquals(listOf("c"), delightedIn(listOf(rated("c", 3, first + 6), rated("d", 1, first + 6)), first, last).map { it.id })
        assertTrue(delightedIn(listOf(rated("d", 1, first + 6), rated("e", 2, first + 7)), first, last).isEmpty())
        assertTrue(delightedIn(listOf(rated("f", 4, last + 1)), first, last).isEmpty())
    }

    @Test
    fun `the recap shows what delighted, and the label still closes a full deck`() {
        val start = LocalDate.of(2026, 9, 1)
        val firstDay = start.toEpochDay()
        val wishes = listOf(
            Wish(
                "w", "Ноутбук", "https://s/x", "", 30_000.0,
                history = listOf(PricePoint(32_000.0, firstDay - 120), PricePoint(30_000.0, firstDay + 3)),
                addedDay = firstDay - 120
            )
        )
        val pays = listOf(Pay("Інтернет", 300.0, day = 1, amounts = listOf(PricePoint(250.0, firstDay - 40), PricePoint(300.0, firstDay + 2))))
        val orders = listOf(
            Order("o", "Навушники", "", RECEIVED, paid = 1_000.0, lowestSeen = 1_000.0, archivedDay = firstDay + 2),
            Order(
                "j", "Кросівки", "", RECEIVED, paid = 3_000.0, archivedDay = firstDay - 25,
                delight = 4, delightDay = firstDay + 4, why = "щоб бігати"
            )
        )
        val recap = monthlyRecap(
            wishes, pays, orders, listOf(PaidMark("Інтернет", "2026-08", 250.0), PaidMark("Інтернет", "2026-09", 300.0)),
            "2026-09", LocalDate.of(2026, 10, 2), 40_000.0, 41.0
        )
        val warm = recap.cards.first { it.kind == RecapKind.DELIGHTED }
        assertEquals("Що справді порадувало", warm.overline)
        assertEquals("Кросівки", warm.headline)
        assertEquals("Хотілось, бо: «щоб бігати». Через три тижні — у захваті", warm.detail)
        assertTrue(recap.cards.size <= MAX_RECAP_CARDS)
        assertEquals(RecapKind.LABEL, recap.cards.last().kind)
        assertEquals("😍", recapEmoji(RecapKind.DELIGHTED))
    }

    // ------------------------------------------------------------ Nova Poshta's own app

    @Test
    fun `the new app first, then the old one, then the website`() {
        assertEquals(listOf("eu.novapost", "ua.novaposhtaa"), NOVA_POSHTA_APPS)
        assertEquals("https://novaposhta.ua/tracking/20450000000001/", novaPoshtaPage("2045 0000 0000 01"))
        assertEquals("https://novaposhta.ua/", novaPoshtaPage(""))
    }
}

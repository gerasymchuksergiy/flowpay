package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The bin is the second exit from a mistake, and its only real risk is arithmetic.
 *
 * A bin advertised as thirty days that in fact keeps twenty-nine is worse than no
 * bin: the user goes looking on the last day and finds the thing gone, having been
 * told it would be there. The boundary is therefore pinned from both sides.
 */
class BinTest {

    private val deletedOn = LocalDate.of(2026, 9, 12).toEpochDay()

    private fun entry(day: Long = deletedOn, kind: String = BIN_WISH) = BinEntry(
        id = "w1",
        kind = kind,
        title = "Навушники Sony",
        detail = "7 999 ₴",
        payload = """{"id":"w1"}""",
        day = day
    )

    @Test
    fun `an entry survives every one of its thirty days`() {
        val wish = entry()

        assertFalse(binExpired(wish, deletedOn))
        assertEquals(BIN_DAYS, binDaysLeft(wish, deletedOn))
        assertFalse(binExpired(wish, deletedOn + 29))
        assertEquals(1, binDaysLeft(wish, deletedOn + 29))
    }

    @Test
    fun `on the boundary day itself it is gone`() {
        val wish = entry()

        // Deleted on day D, restorable on D through D+29, gone on D+30. One off in
        // either direction makes the promise on the screen a lie.
        assertEquals(deletedOn + 30, binExpiryDay(wish))
        assertTrue(binExpired(wish, deletedOn + 30))
        assertEquals(0, binDaysLeft(wish, deletedOn + 30))
        assertEquals("видаляється сьогодні", binLeftLabel(wish, deletedOn + 30))

        assertEquals(listOf(wish), pruneBin(listOf(wish), deletedOn + 29))
        assertTrue(pruneBin(listOf(wish), deletedOn + 30).isEmpty())
    }

    @Test
    fun `days left never goes negative once the day has passed`() {
        assertEquals(0, binDaysLeft(entry(), deletedOn + 400))
    }

    @Test
    fun `pruning keeps what is still in date and drops only what is not`() {
        val fresh = entry(deletedOn - 1)
        val old = entry(deletedOn - 30).copy(id = "w2")

        assertEquals(listOf(fresh), pruneBin(listOf(fresh, old), deletedOn))
    }

    @Test
    fun `an entry with no date is kept rather than treated as ancient`() {
        // Nothing writes a dateless entry, but a hand-edited backup could, and a bad
        // date must not be able to empty the bin on the next read.
        val dateless = entry(day = 0L)

        assertEquals(listOf(dateless), pruneBin(listOf(dateless), deletedOn))
    }

    @Test
    fun `the bin lists what was deleted most recently first`() {
        val older = entry(deletedOn - 5).copy(id = "a")
        val newer = entry(deletedOn).copy(id = "b")

        assertEquals(listOf("b", "a"), sortedBin(listOf(older, newer)).map { it.id })
    }

    @Test
    fun `the undo bar names the thing rather than its kind`() {
        assertEquals("Видалено «Навушники Sony»", binUndoMessage(entry()))
    }

    @Test
    fun `each kind is named in ukrainian, and an unknown one still has a word`() {
        assertEquals("Бажання", binKindLabel(BIN_WISH))
        assertEquals("Витрата", binKindLabel(BIN_PAY))
        assertEquals("Покупка", binKindLabel(BIN_ORDER))
        assertEquals("Запис", binKindLabel("z"))
    }

    @Test
    fun `the countdown declines correctly at every awkward number`() {
        assertEquals("лишилось 1 день", binLeftLabel(entry(deletedOn - 29), deletedOn))
        assertEquals("лишилось 2 дні", binLeftLabel(entry(deletedOn - 28), deletedOn))
        assertEquals("лишилось 11 днів", binLeftLabel(entry(deletedOn - 19), deletedOn))
        assertEquals("лишилось 30 днів", binLeftLabel(entry(), deletedOn))
    }

    // ------------------------------------------------ what the payload carries

    /**
     * The whole point of a bin is that what comes out is what went in.
     *
     * A field added to [Wish] and wired into [Store] but forgotten in [wishJson]
     * fails nowhere: the wish restores, the app runs, and the price history, the
     * hold or the freshness is simply gone. That is the exact loss this feature
     * exists to prevent, so the round trip is pinned field by field.
     */
    private val watched = Wish(
        id = "w1",
        name = "Навушники Sony WH-1000XM5",
        url = "https://shop.example/xm5",
        image = "https://shop.example/xm5.jpg",
        price = 11_499.0,
        targetPrice = 9_999.0,
        category = "Техніка",
        history = listOf(
            PricePoint(13_999.0, 20_100L, 41.2537, SOURCE_NBU),
            PricePoint(11_499.0, 20_240L, 44.8009, SOURCE_MONOBANK)
        ),
        checkedDay = 20_240L,
        saved = 4_000.0,
        monthlyPlan = 1_500.0,
        deadline = 20_400L,
        notifiedPrice = 11_499.0,
        variant = "Чорний",
        freshness = Freshness.OUT_OF_STOCK,
        addedDay = 20_100L,
        holdUntil = 20_260L
    )

    @Test
    fun `a wish goes into the bin and comes back out unchanged`() {
        val entry = binEntryOf(watched, deletedOn)

        assertEquals(BIN_WISH, entry.kind)
        assertEquals(watched, wishOf(JSONObject(entry.payload)))
    }

    @Test
    fun `the fields added after the bin was written still survive the round trip`() {
        // Named one at a time rather than left to the equality above, so that a
        // future field dropped from the mapping fails with a message that says
        // which one instead of dumping two wishes side by side.
        val back = wishOf(JSONObject(binEntryOf(watched, deletedOn).payload))

        assertEquals(Freshness.OUT_OF_STOCK, back.freshness)
        assertEquals(20_100L, back.addedDay)
        assertEquals(20_260L, back.holdUntil)
        assertEquals(watched.history, back.history)
        assertEquals(41.2537, back.history.first().rate, 0.0001)
        assertEquals(SOURCE_NBU, back.history.first().rateSource)
        assertEquals(44.8009, back.history.last().rate, 0.0001)
        assertEquals(SOURCE_MONOBANK, back.history.last().rateSource)
    }

    @Test
    fun `an expense and a parcel round trip whole too`() {
        val pay = Pay("Оренда квартири", 250.0, day = 5, currency = USD, warnDays = 7)
        val order = Order(
            id = "o1",
            name = "Клавіатура",
            url = "https://shop.example/kb",
            status = AT_BRANCH,
            tracking = "20450000000000",
            image = "https://shop.example/kb.jpg",
            price = 3_200.0,
            statusDetail = "Прибуло у відділення №12",
            checkedAt = 1_800_000_000_000L,
            problem = true,
            paidStorageFrom = 20_250L,
            scheduledDelivery = 20_246L,
            amountToPay = 3_200.0
        )

        assertEquals(pay, payOf(JSONObject(binEntryOf(pay, deletedOn).payload)))
        assertEquals(order, orderOf(JSONObject(binEntryOf(order, deletedOn).payload)))
    }

    @Test
    fun `a wish stored before the newer fields existed reads back with honest defaults`() {
        // Exactly the shape a version-1 backup carries: no "fr", "ad" or "hu", and
        // history points with no rate stamped on them. Restoring one must fill the
        // gaps the way the app already fills them, not refuse or blank the wish.
        val old = JSONObject(
            """
            {"id":"w9","n":"Чайник","u":"https://shop.example/k","i":"","p":1200.0,
             "t":0.0,"c":"Інше","h":[{"p":1400.0,"d":20100},{"p":1200.0,"d":20200}],
             "cd":20200,"s":0.0,"m":0.0,"dl":0,"np":0.0,"v":""}
            """.trimIndent()
        )

        val wish = wishOf(old)

        // Read from a page successfully the last time anyone looked, so OK — not a
        // doubt invented for every wish already on the phone.
        assertEquals(Freshness.OK, wish.freshness)
        assertEquals(0L, wish.addedDay)
        assertEquals(0L, wish.holdUntil)
        assertEquals(2, wish.history.size)
        assertEquals(0.0, wish.history.first().rate, 0.0001)
        assertEquals("", wish.history.first().rateSource)
        assertEquals(1200.0, wish.price, 0.0001)
    }

    @Test
    fun `a history written as bare numbers still survives the bin`() {
        // The oldest shape of all: a list of prices with no dates. It reached the
        // bin through the same mapping, so it has to come out of it too.
        val ancient = JSONObject(
            """{"id":"w8","n":"Лампа","u":"https://shop.example/l","p":800.0,"h":[900.0,800.0]}"""
        )

        val wish = wishOf(ancient)
        assertEquals(listOf(900.0, 800.0), wish.history.map { it.price })

        val back = wishOf(JSONObject(binEntryOf(wish, deletedOn).payload))
        assertEquals(wish, back)
    }

    @Test
    fun `an empty bin says what it is for instead of showing nothing`() {
        assertEquals("Порожній. Видалене зберігається 30 днів", binSummary(emptyList()))
        assertEquals("1 запис · зберігаються 30 днів", binSummary(listOf(entry())))
        assertEquals(
            "2 записи · зберігаються 30 днів",
            binSummary(listOf(entry(), entry().copy(id = "w2")))
        )
    }
}

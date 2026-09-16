package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict on a purchase that is already over.
 *
 * Every other judgement in this app is a forecast, and a forecast can be quietly
 * wrong for ever. This one is checkable, which is exactly why it has to be able to
 * say the tracker did not help — the temptation is to write a verdict that always
 * congratulates, and that is worth nothing at all.
 */
class PurchaseTest {

    private fun shown(text: String) = text.replace(' ', ' ')

    @Test
    fun `buying at the lowest price ever seen means waiting paid off`() {
        val review = purchaseReview(paid = 8_400.0, lowestSeen = 8_400.0)

        assertEquals(PurchaseVerdict.PATIENT, review.verdict)
        assertEquals(0.0, review.overpaid, 0.001)
        assertEquals(0.0, review.overpaidPercent, 0.001)
        assertEquals("Чекати було варто", purchaseVerdictLabel(review.verdict))
    }

    @Test
    fun `beating the lowest price is still patience, and is said differently`() {
        // A promo code, or a shop the tracker never watched. Crediting this to the
        // wait would be flattery, but scolding it would be plainly wrong.
        val review = purchaseReview(paid = 7_900.0, lowestSeen = 8_400.0)

        assertEquals(PurchaseVerdict.PATIENT, review.verdict)
        assertEquals(0.0, review.overpaid, 0.001)
        assertTrue(
            shown(purchaseVerdictDetail(review)).contains("дешевше за все, що бачив трекер")
        )
    }

    @Test
    fun `buying above the lowest price says so, and says what it cost`() {
        val review = purchaseReview(paid = 10_000.0, lowestSeen = 8_000.0)

        assertEquals(PurchaseVerdict.HASTY, review.verdict)
        assertEquals(2_000.0, review.overpaid, 0.001)
        assertEquals(25.0, review.overpaidPercent, 0.001)
        assertEquals("Ти поспішив", purchaseVerdictLabel(review.verdict))

        val detail = shown(purchaseVerdictDetail(review))
        assertTrue(detail, detail.contains("10 000 ₴"))
        assertTrue(detail, detail.contains("8 000 ₴"))
        assertTrue(detail, detail.contains("25%"))
    }

    @Test
    fun `a hryvnia over the best price is the same price, not a scolding`() {
        // Three hryvnia on eight thousand is rounding, a different shipping line or
        // a rate that moved. A verdict that nitpicks at that scale stops being read.
        val rounding = purchaseReview(paid = 8_003.0, lowestSeen = 8_000.0)
        assertEquals(PurchaseVerdict.PATIENT, rounding.verdict)

        // One per cent is where it starts counting, and just past it, it counts.
        val real = purchaseReview(paid = 8_100.0, lowestSeen = 8_000.0)
        assertEquals(PurchaseVerdict.HASTY, real.verdict)
        assertEquals(100.0, real.overpaid, 0.001)
    }

    @Test
    fun `with nothing tracked there is no verdict rather than a good one`() {
        val review = purchaseReview(paid = 10_000.0, lowestSeen = 0.0)

        assertEquals(PurchaseVerdict.UNJUDGED, review.verdict)
        assertEquals(0.0, review.overpaid, 0.001)
        assertEquals("Немає з чим порівняти", purchaseVerdictLabel(review.verdict))
        assertTrue(purchaseVerdictDetail(review).contains("не встигли відстежити"))
    }

    @Test
    fun `a purchase with no recorded sum is unjudged, not free`() {
        val review = purchaseReview(paid = 0.0, lowestSeen = 8_000.0)

        assertEquals(PurchaseVerdict.UNJUDGED, review.verdict)
        assertEquals("Не вказано, скільки заплачено", purchaseVerdictDetail(review))
    }

    @Test
    fun `no uses counted means no cost per use, not a division by zero`() {
        val review = purchaseReview(paid = 12_000.0, lowestSeen = 12_000.0, uses = 0)

        assertEquals(0, review.uses)
        assertNull(review.perUse)
        assertNull(costPerUseLine(review))
        // The verdict itself is unaffected: how much a thing gets used is a
        // different question from whether it was bought well.
        assertEquals(PurchaseVerdict.PATIENT, review.verdict)
    }

    @Test
    fun `a negative use count is read as none`() {
        val review = purchaseReview(paid = 12_000.0, lowestSeen = 12_000.0, uses = -4)

        assertEquals(0, review.uses)
        assertNull(review.perUse)
    }

    @Test
    fun `counting uses turns a price into a price per use`() {
        val review = purchaseReview(paid = 12_000.0, lowestSeen = 12_000.0, uses = 25)

        assertNotNull(review.perUse)
        assertEquals(480.0, review.perUse!!, 0.001)
        assertEquals("480 ₴ за раз · 25 разів", shown(costPerUseLine(review)!!))
    }

    @Test
    fun `uses decline the way Ukrainian requires`() {
        assertEquals("1 раз", usesLabel(1))
        assertEquals("2 рази", usesLabel(2))
        assertEquals("4 рази", usesLabel(4))
        assertEquals("5 разів", usesLabel(5))
        assertEquals("11 разів", usesLabel(11))
        assertEquals("12 разів", usesLabel(12))
        assertEquals("14 разів", usesLabel(14))
        assertEquals("21 раз", usesLabel(21))
        assertEquals("22 рази", usesLabel(22))
        assertEquals("25 разів", usesLabel(25))
        assertEquals("0 разів", usesLabel(0))
    }

    // ------------------------------------------------- what gets carried across

    private fun wish(price: Double, history: List<PricePoint>) =
        Wish(id = "1", name = "Ніж", url = "https://shop/x", image = "", price = price, history = history)

    @Test
    fun `one price is not a price history`() {
        // The number the shop happened to show on the day the link was pasted.
        // Calling that "the lowest ever seen" would grade every purchase a pass.
        assertEquals(0.0, lowestTracked(wish(9_000.0, emptyList())), 0.001)
        assertEquals(0.0, lowestTracked(wish(9_000.0, listOf(PricePoint(9_000.0, 100L)))), 0.001)
    }

    @Test
    fun `the lowest ever seen includes the price standing now`() {
        val watched = wish(
            price = 7_500.0,
            history = listOf(PricePoint(9_000.0, 100L), PricePoint(8_200.0, 140L))
        )

        assertEquals(7_500.0, lowestTracked(watched), 0.001)
    }

    @Test
    fun `the lowest ever seen survives the price climbing back`() {
        val watched = wish(
            price = 9_500.0,
            history = listOf(PricePoint(9_000.0, 100L), PricePoint(7_800.0, 140L))
        )

        assertEquals(7_800.0, lowestTracked(watched), 0.001)
        assertEquals(PurchaseVerdict.HASTY, purchaseReview(9_500.0, lowestTracked(watched)).verdict)
    }

    @Test
    fun `a hand-typed price is no benchmark at all`() {
        // A typed price is appended to the history like any other, so once one is
        // in there nothing downstream can separate what a shop stated from what a
        // person guessed. Grading the purchase against it would be grading the
        // buyer against themselves.
        val typed = wish(
            price = 9_500.0,
            history = listOf(PricePoint(9_000.0, 100L), PricePoint(7_800.0, 140L))
        ).copy(freshness = Freshness.MANUAL)

        assertEquals(0.0, lowestTracked(typed), 0.001)
        assertEquals(
            PurchaseVerdict.UNJUDGED,
            purchaseReview(9_500.0, lowestTracked(typed)).verdict
        )
    }

    @Test
    fun `a page that stopped answering keeps the prices it did report`() {
        // Staleness stops the price being updated; it does not put a wrong number
        // into the history. A 404 after months of readings is when the record is
        // worth most, so throwing it away would be the wrong kind of caution.
        val history = listOf(PricePoint(9_000.0, 100L), PricePoint(7_800.0, 140L))
        listOf(Freshness.GONE, Freshness.UNREADABLE, Freshness.OUT_OF_STOCK).forEach { state ->
            val stale = wish(price = 9_500.0, history = history).copy(freshness = state)
            assertEquals(state.name, 7_800.0, lowestTracked(stale), 0.001)
        }
    }

    @Test
    fun `a hold changes nothing about the benchmark`() {
        // A hold only stops the app asking about the thing. Every price already
        // recorded was still genuinely observed, so the minimum over them stands.
        val watched = wish(
            price = 9_500.0,
            history = listOf(PricePoint(9_000.0, 100L), PricePoint(7_800.0, 140L))
        )
        val held = watched.copy(holdUntil = 200L)

        assertEquals(lowestTracked(watched), lowestTracked(held), 0.001)
        assertTrue(onHold(held, today = 150L))
    }

    // -------------------------------------------------- surviving the bin

    private val filed = Order(
        id = "o1",
        name = "Ніж",
        url = "https://shop.example/knife",
        status = RECEIVED,
        tracking = "20450000000001",
        image = "https://shop.example/knife.jpg",
        price = 9_000.0,
        statusDetail = "Отримано · Одеса, відділення №12",
        checkedAt = 1_700_000_000_000L,
        problem = false,
        paidStorageFrom = 20_118L,
        scheduledDelivery = 20_115L,
        amountToPay = 0.0,
        paid = 8_200.0,
        lowestSeen = 7_800.0,
        uses = 12,
        archivedDay = 20_120L
    )

    @Test
    fun `an archived purchase survives the bin whole`() {
        // Deleting goes through the bin, and the bin stores the item in the app's
        // own JSON shape. A field left out of that mapping would come back as a
        // zero, the verdict would quietly read "немає з чим порівняти", and
        // nothing on screen would say the record had been thrown away.
        val entry = binEntryOf(filed, today = 20_130L)
        val restored = orderOf(JSONObject(entry.payload))

        assertEquals(filed, restored)
    }

    @Test
    fun `the purchase record is named field by field on the way back`() {
        // Spelled out as well as compared whole, so a failure says which of the
        // four went missing rather than printing two long orders side by side.
        val restored = orderOf(orderJson(filed))

        assertEquals(8_200.0, restored.paid, 0.001)
        assertEquals(7_800.0, restored.lowestSeen, 0.001)
        assertEquals(12, restored.uses)
        assertEquals(20_120L, restored.archivedDay)
        assertEquals(
            PurchaseVerdict.HASTY,
            purchaseReview(restored.paid, restored.lowestSeen, restored.uses).verdict
        )
    }

    @Test
    fun `a parcel stored before purchases could be closed comes back open`() {
        val old = JSONObject()
            .put("id", "o1").put("n", "Ніж").put("u", "https://shop.example/knife")
            .put("s", ORDERED).put("t", "20450000000001")

        val restored = orderOf(old)

        assertEquals(0.0, restored.paid, 0.001)
        assertEquals(0.0, restored.lowestSeen, 0.001)
        assertEquals(0, restored.uses)
        assertEquals(0L, restored.archivedDay)
    }

    // ------------------------------------------------------------ the tally

    @Test
    fun `the tally counts only what there was something to compare against`() {
        val tally = purchaseTally(
            listOf(
                purchaseReview(paid = 8_000.0, lowestSeen = 8_000.0),
                purchaseReview(paid = 10_000.0, lowestSeen = 8_000.0),
                purchaseReview(paid = 5_000.0, lowestSeen = 0.0)
            )
        )

        assertEquals(2, tally.judged)
        assertEquals(1, tally.patient)
        assertEquals(1, tally.hasty)
        assertEquals(2_000.0, tally.overpaid, 0.001)
        assertEquals("Куплено вчасно: 1 з 2 · переплата 2 000 ₴", shown(purchaseTallyLine(tally)))
    }

    @Test
    fun `a tally with nothing to compare says so plainly`() {
        val tally = purchaseTally(listOf(purchaseReview(paid = 5_000.0, lowestSeen = 0.0)))

        assertEquals(0, tally.judged)
        assertEquals("Порівнювати ще нема з чим", purchaseTallyLine(tally))
    }

    @Test
    fun `a clean run does not invent an overpayment to mention`() {
        val tally = purchaseTally(
            listOf(
                purchaseReview(paid = 8_000.0, lowestSeen = 8_000.0),
                purchaseReview(paid = 1_200.0, lowestSeen = 1_250.0)
            )
        )

        assertEquals("Куплено вчасно: 2 з 2", purchaseTallyLine(tally))
    }

    @Test
    fun `an empty archive is not an error`() {
        assertEquals("Порівнювати ще нема з чим", purchaseTallyLine(purchaseTally(emptyList())))
    }
}

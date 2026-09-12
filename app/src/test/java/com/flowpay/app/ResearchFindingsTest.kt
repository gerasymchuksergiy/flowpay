package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The changes that came out of researching what comparable apps get right and
 * wrong: reading prices from structured data, not crying wolf on alerts, using the
 * carrier fields that were already arriving, and noticing when savings plans add
 * up to more than the month can pay for.
 */
class ResearchFindingsTest {

    private val day = 20_000L

    /** The overview needs a date only to know which trials have run out; none here has one. */
    private val anyDay = LocalDate.of(2026, 9, 15)

    private fun wish(
        price: Double,
        target: Double = 0.0,
        notified: Double = 0.0,
        history: List<PricePoint> = emptyList()
    ) = Wish("id", "Товар", "https://shop/x", "", price, target, "Інше", history, notifiedPrice = notified)

    // -------------------------------------------------------- JSON-LD prices

    @Test
    fun `a price is read from json-ld when the meta tags have none`() {
        val html = """
            <html><head>
            <meta property="og:title" content="Кросівки"/>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Product","name":"Кросівки",
             "offers":{"@type":"Offer","price":"2499.00","priceCurrency":"UAH"}}
            </script>
            </head></html>
        """.trimIndent()

        assertEquals(2499.0, extractPrice(html), 0.001)
    }

    @Test
    fun `json-ld wins over a meta tag, since shops keep it current`() {
        val html = """
            <meta property="product:price:amount" content="9999"/>
            <script type="application/ld+json">
            {"@type":"Product","offers":{"price":1500}}
            </script>
        """.trimIndent()

        assertEquals(1500.0, extractPrice(html), 0.001)
    }

    @Test
    fun `a product inside an at-graph is found`() {
        val html = """
            <script type="application/ld+json">
            {"@context":"https://schema.org","@graph":[
              {"@type":"WebSite","name":"Shop"},
              {"@type":"Product","offers":{"@type":"Offer","price":"777,50"}}
            ]}
            </script>
        """.trimIndent()

        assertEquals(777.50, extractPrice(html), 0.001)
    }

    @Test
    fun `an offers array and a price specification are both handled`() {
        val array = """
            <script type="application/ld+json">
            [{"@type":"Product","offers":[{"price":"0"},{"price":"1299"}]}]
            </script>
        """.trimIndent()
        assertEquals(1299.0, extractPrice(array), 0.001)

        val spec = """
            <script type="application/ld+json">
            {"@type":"Product","offers":{"priceSpecification":{"price":"450.5"}}}
            </script>
        """.trimIndent()
        assertEquals(450.5, extractPrice(spec), 0.001)
    }

    @Test
    fun `broken json-ld falls through to the meta tag instead of failing`() {
        val html = """
            <script type="application/ld+json">{ this is not json </script>
            <meta property="product:price:amount" content="640"/>
        """.trimIndent()

        assertEquals(640.0, extractPrice(html), 0.001)
    }

    @Test
    fun `the legacy open graph price tag is accepted too`() {
        assertEquals(310.0, extractPrice("""<meta property="og:price:amount" content="310"/>"""), 0.001)
    }

    @Test
    fun `a thousands space inside a price does not break the number`() {
        val html = """<script type="application/ld+json">{"@type":"Product","offers":{"price":"12 499,50"}}</script>"""

        assertEquals(12_499.50, extractPrice(html), 0.001)
    }

    // ------------------------------------------------------------- alerts

    @Test
    fun `crossing the target always alerts`() {
        val decision = priceAlertFor(wish(price = 1000.0, target = 900.0), 880.0)

        assertEquals(AlertKind.TARGET_REACHED, decision.kind)
        assertEquals(880.0, decision.notifyPrice, 0.001)
    }

    @Test
    fun `a price below everything seen before is a new low`() {
        val previous = wish(
            price = 1000.0,
            history = listOf(PricePoint(1200.0, day - 20), PricePoint(1000.0, day - 5))
        )

        assertEquals(AlertKind.NEW_LOW, priceAlertFor(previous, 950.0).kind)
    }

    @Test
    fun `a small wobble is not worth waking anyone`() {
        val previous = wish(price = 1000.0, notified = 1000.0, history = listOf(PricePoint(900.0, day - 9)))

        // Two percent below the last announced price, and not a new low.
        assertEquals(AlertKind.NONE, priceAlertFor(previous, 980.0).kind)
    }

    @Test
    fun `a fall must beat the last announced price, not yesterday's`() {
        val previous = wish(price = 1000.0, notified = 950.0, history = listOf(PricePoint(900.0, day - 9)))

        // Below the current price, but the user was already told about 950.
        assertEquals(AlertKind.NONE, priceAlertFor(previous, 940.0).kind)
        // Comfortably below what was announced: worth saying.
        assertEquals(AlertKind.DROP, priceAlertFor(previous, 910.0).kind)
    }

    @Test
    fun `the announced price is remembered and carried when nothing is announced`() {
        val previous = wish(price = 1000.0, notified = 950.0, history = listOf(PricePoint(900.0, day - 9)))

        assertEquals(950.0, priceAlertFor(previous, 999.0).notifyPrice, 0.001)
        assertEquals(910.0, priceAlertFor(previous, 910.0).notifyPrice, 0.001)
    }

    @Test
    fun `a failed read alerts nothing`() {
        assertEquals(AlertKind.NONE, priceAlertFor(wish(price = 1000.0), 0.0).kind)
    }

    @Test
    fun `staleness is only reported once a day has actually passed`() {
        assertNull(stalenessDays(day, day))
        assertEquals(3, stalenessDays(day - 3, day))
        // Never checked at all: nothing to claim.
        assertNull(stalenessDays(0L, day))
    }

    // --------------------------------------------------- carrier dates

    @Test
    fun `both date separators the carrier uses are accepted`() {
        // The same response carries one field with dashes and another with dots.
        assertEquals(LocalDate.of(2026, 8, 1), parseCarrierDate("01-08-2026 19:18:12"))
        assertEquals(LocalDate.of(2026, 8, 4), parseCarrierDate("04.08.2026 08:10:48"))
        assertEquals(LocalDate.of(2026, 9, 10), parseCarrierDate("10.09.2026"))
    }

    @Test
    fun `an empty or nonsense date is null rather than a wrong date`() {
        assertNull(parseCarrierDate(""))
        assertNull(parseCarrierDate("   "))
        assertNull(parseCarrierDate("not a date"))
        assertNull(parseCarrierDate("32.13.2026"))
        assertNull(parseCarrierDate("01-08-1899"))
    }

    @Test
    fun `free storage counts down and stops at zero`() {
        val today = LocalDate.of(2026, 9, 10)

        assertEquals(3, freeStorageDaysLeft(LocalDate.of(2026, 9, 13), today))
        assertEquals(0, freeStorageDaysLeft(today, today))
        // Already past: not a negative number.
        assertEquals(0, freeStorageDaysLeft(LocalDate.of(2026, 9, 1), today))
        assertNull(freeStorageDaysLeft(null, today))
    }

    @Test
    fun `the branch number is preferred over the long address on a card`() {
        val json = """
            {"success":true,"data":[{"StatusCode":"7","Status":"Прибув на відділення",
             "CityRecipient":"Київ","WarehouseRecipient":"Відділення №12: вул. Довга, 1",
             "WarehouseRecipientNumber":"12","DatePayedKeeping":"17.09.2026"}]}
        """.trimIndent()

        val status = parseNovaPoshtaStatus(json)!!

        assertEquals("Прибув на відділення · Київ, відділення №12", statusLine(status))
        assertEquals(LocalDate.of(2026, 9, 17), status.paidStorageFrom)
    }

    // ------------------------------------------------- goal conflict

    @Test
    fun `plans that add up to more than the month is flagged`() {
        val wishes = listOf(
            wish(price = 20_000.0).copy(id = "a", monthlyPlan = 4000.0),
            wish(price = 10_000.0).copy(id = "b", monthlyPlan = 2200.0)
        )
        val pays = listOf(Pay("Інтернет", 1000.0))

        val summary = overview(wishes, pays, emptyList(), income = 5_100.0, usdSellRate = 44.8, today = anyDay)

        assertEquals(6200.0, summary.plannedMonthly, 0.001)
        assertEquals(4100.0, summary.freeCash, 0.001)
        assertEquals(2100.0, summary.plansOverBudget, 0.001)
        assertTrue(summary.plansConflict)
    }

    @Test
    fun `plans that fit are not flagged`() {
        val wishes = listOf(wish(price = 20_000.0).copy(monthlyPlan = 2000.0))
        val pays = listOf(Pay("Інтернет", 1000.0))

        val summary = overview(wishes, pays, emptyList(), income = 10_000.0, usdSellRate = 44.8, today = anyDay)

        assertFalse(summary.plansConflict)
        assertEquals(0.0, summary.plansOverBudget, 0.001)
    }

    @Test
    fun `no conflict is claimed when the income is unknown`() {
        val wishes = listOf(wish(price = 20_000.0).copy(monthlyPlan = 9000.0))

        val summary = overview(wishes, emptyList(), emptyList(), income = 0.0, usdSellRate = 44.8, today = anyDay)

        assertFalse(summary.plansConflict)
    }

    // ------------------------------------------- which build a release holds

    @Test
    fun `the version code written by the workflow is used when present`() {
        val body = """
            Автоматичне оновлення FlowPay.
            versionCode=42
        """.trimIndent()
        assertEquals(42, releaseVersionCode(body, "v1.0.42"))
        assertEquals(7, releaseVersionCode("versionCode = 7", "whatever"))
    }

    @Test
    fun `a release made by hand is read from its tag instead of being ignored`() {
        // Without this the in-app updater reported that nothing was published.
        assertEquals(20300, releaseVersionCode("", "v2.3.0"))
        assertEquals(20300, releaseVersionCode("Просто опис без коду", "2.3.0"))
        assertEquals(10000, releaseVersionCode("", "v1.0.0"))
    }

    @Test
    fun `a later tag always compares higher than an earlier one`() {
        val older = releaseVersionCode("", "v2.1.9")!!
        val newer = releaseVersionCode("", "v2.2.0")!!
        val newest = releaseVersionCode("", "v3.0.0")!!

        assertTrue(older < newer)
        assertTrue(newer < newest)
    }

    @Test
    fun `a release with neither a code nor a version in its tag is skipped`() {
        assertNull(releaseVersionCode("", "latest"))
        assertNull(releaseVersionCode("нічого", ""))
    }

    @Test
    fun `the apk matching the release version wins over one listed earlier`() {
        // A real release ended up with a stale build listed first, which would have
        // installed an older version and then offered the same update for ever.
        val names = listOf("FlowPay-debug-local.apk", "FlowPay-v2.3.0.apk")

        assertEquals("FlowPay-v2.3.0.apk", pickApkAsset(names, "v2.3.0"))
    }

    @Test
    fun `a single apk is taken whatever it is called`() {
        assertEquals("app-release.apk", pickApkAsset(listOf("app-release.apk"), "v2.3.0"))
        assertEquals("build.apk", pickApkAsset(listOf("notes.txt", "build.apk"), "v9.9.9"))
    }

    @Test
    fun `a release with no apk yields nothing to install`() {
        assertNull(pickApkAsset(listOf("notes.txt", "sources.zip"), "v2.3.0"))
        assertNull(pickApkAsset(emptyList(), "v2.3.0"))
    }

    @Test
    fun `without a version in the tag the first apk is used`() {
        val names = listOf("first.apk", "second.apk")

        assertEquals("first.apk", pickApkAsset(names, "latest"))
    }

    @Test
    fun `the lowest price carries the day it was seen`() {
        val history = listOf(
            PricePoint(1200.0, day - 30),
            PricePoint(800.0, day - 20),
            PricePoint(1000.0, day - 2)
        )

        assertEquals(day - 20, lowestPointDay(history))
        // Undated entries carried over from before cannot claim a date.
        assertNull(lowestPointDay(listOf(PricePoint(500.0, 0L))))
    }
}

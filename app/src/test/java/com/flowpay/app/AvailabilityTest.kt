package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A page that states a price and says in the same breath that nobody can buy it.
 *
 * The reported bug: the app announced a price drop, the owner went to the shop,
 * and the thing was not there. Availability was never read, so a listing declaring
 * `OutOfStock` beside a placeholder figure looked exactly like a live price — and a
 * sold-out listing is very often the cheapest number on the page, which is why the
 * wrong reading reached him as good news rather than as nothing at all.
 *
 * The damage to guard is the number, not the label, so most of what is pinned here
 * is that the figure stays out of the history, out of the alerts, out of the
 * benchmark and out of the cheapest-shop contest.
 */
class AvailabilityTest {

    private val today = 20_000L
    private val rate = FxRate(41.0, 41.34, SOURCE_MONOBANK)

    private fun jsonLd(price: String, availability: String?, name: String? = null): String {
        val stock = availability?.let { ""","availability":"$it"""" }.orEmpty()
        val label = name?.let { ""","name":"$it"""" }.orEmpty()
        return """
            <meta property="og:title" content="Навушники">
            <script type="application/ld+json">
            {"@type":"Product"$label,"offers":{"price":"$price","priceCurrency":"UAH"$stock}}
            </script>
        """.trimIndent()
    }

    private fun source(
        host: String,
        price: Double,
        freshness: Freshness = Freshness.OK,
        availability: Availability = Availability.UNKNOWN
    ) = WishSource(
        url = "https://$host/item",
        price = price,
        freshness = freshness,
        checkedDay = today - 1,
        amount = price,
        currency = UAH,
        rate = 1.0,
        availability = availability
    )

    private fun wish(vararg sources: WishSource, history: List<PricePoint> = emptyList()) = Wish(
        id = "id",
        name = "Навушники",
        url = sources.firstOrNull()?.url.orEmpty(),
        image = "",
        price = sources.firstOrNull()?.price ?: 0.0,
        history = history,
        checkedDay = today - 1,
        sources = sources.toList()
    )

    // ------------------------------------------------- reading the vocabulary

    @Test
    fun `every schema token maps to what this app can act on`() {
        listOf("InStock", "LimitedAvailability", "OnlineOnly", "InStoreOnly", "PreOrder", "BackOrder")
            .forEach { token ->
                assertEquals(
                    "$token should be buyable",
                    Availability.IN_STOCK,
                    availabilityFrom(token)
                )
            }
        assertEquals(Availability.SOLD_OUT, availabilityFrom("OutOfStock"))
        assertEquals(Availability.SOLD_OUT, availabilityFrom("SoldOut"))
        // Not buyable in the same way money is concerned, but the opposite advice
        // about whether to keep waiting, which is why it is its own value.
        assertEquals(Availability.DISCONTINUED, availabilityFrom("Discontinued"))
    }

    @Test
    fun `a pre-order is a price you can commit to, not an absence`() {
        // The judgement this app makes: PreOrder and BackOrder are both a shop
        // quoting a figure it will take money at. Tracking them is the point.
        assertFalse(blocksPrice(availabilityFrom("PreOrder")))
        assertFalse(blocksPrice(availabilityFrom("BackOrder")))
        assertFalse(blocksPrice(availabilityFrom("LimitedAvailability")))
        assertTrue(blocksPrice(availabilityFrom("OutOfStock")))
        assertTrue(blocksPrice(availabilityFrom("Discontinued")))
    }

    @Test
    fun `the bare token and the full url mean the same thing`() {
        listOf(
            "https://schema.org/OutOfStock",
            "http://schema.org/OutOfStock",
            "//schema.org/OutOfStock",
            "OutOfStock",
            "outofstock",
            "OUT_OF_STOCK",
            "out of stock",
            "  https://schema.org/outOfStock  "
        ).forEach { written ->
            assertEquals("«$written»", Availability.SOLD_OUT, availabilityFrom(written))
        }
    }

    @Test
    fun `a page that declares nothing stays exactly as it was`() {
        // Load-bearing. Most Ukrainian shops declare nothing, and treating "not
        // exactly InStock" as sold out would grey out the whole wishlist.
        assertEquals(Availability.UNKNOWN, availabilityFrom(""))
        assertEquals(Availability.UNKNOWN, availabilityFrom("   "))
        assertEquals(Availability.UNKNOWN, availabilityFrom("є в наявності"))
        assertEquals(Availability.UNKNOWN, availabilityFrom("https://shop.ua/maybe"))
        assertEquals(Availability.UNKNOWN, pageAvailability("<html><body>Товар</body></html>"))
        assertFalse(blocksPrice(Availability.UNKNOWN))
    }

    // -------------------------------------------------- where it is written

    @Test
    fun `json-ld availability rides beside the price`() {
        assertEquals(
            Availability.SOLD_OUT,
            extractOffers(jsonLd("2199", "https://schema.org/OutOfStock")).single().availability
        )
        assertEquals(
            Availability.IN_STOCK,
            extractOffers(jsonLd("2199", "https://schema.org/InStock")).single().availability
        )
        assertEquals(
            Availability.UNKNOWN,
            extractOffers(jsonLd("2199", null)).single().availability
        )
    }

    @Test
    fun `microdata writes it on a link href as often as on a meta content`() {
        val onLink = """
            <span itemprop="offers">
            <meta itemprop="price" content="2199"><meta itemprop="priceCurrency" content="UAH">
            <link itemprop="availability" href="https://schema.org/OutOfStock"/>
            </span>
        """.trimIndent()
        val onMeta = """
            <span itemprop="offers">
            <meta itemprop="price" content="2199">
            <meta itemprop="availability" content="OutOfStock">
            </span>
        """.trimIndent()
        val reversed = """
            <span itemprop="offers">
            <meta content="2199" itemprop="price">
            <link href="http://schema.org/SoldOut" itemprop="availability"/>
            </span>
        """.trimIndent()

        listOf(onLink, onMeta, reversed).forEach { html ->
            assertEquals(Availability.SOLD_OUT, pageAvailability(html))
            assertTrue(blocksPrice(extractOffers(html).first().availability))
        }
    }

    @Test
    fun `open graph availability is read too`() {
        val html = """<meta property="product:availability" content="out of stock">"""
        assertEquals(Availability.SOLD_OUT, pageAvailability(html))
    }

    @Test
    fun `a price with nothing of its own takes what the page declared`() {
        // A figure scraped out of a data attribute says nothing about itself, so
        // the page's own declaration is the only thing that can save it.
        val html = """
            <meta itemprop="availability" content="https://schema.org/OutOfStock">
            <div class="price" data-price-final="219900"></div>
        """.trimIndent()

        assertEquals(Availability.SOLD_OUT, extractOffers(html).single().availability)
    }

    @Test
    fun `one sold-out edition does not condemn the one beside it`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","offers":[
              {"name":"Standard","price":"2199","priceCurrency":"UAH",
               "availability":"https://schema.org/OutOfStock"},
              {"name":"Deluxe","price":"2999","priceCurrency":"UAH",
               "availability":"https://schema.org/InStock"}]}
            </script>
        """.trimIndent()

        val offers = extractOffers(html)
        assertEquals(Availability.SOLD_OUT, offers.first { it.label == "Standard" }.availability)
        assertEquals(Availability.IN_STOCK, offers.first { it.label == "Deluxe" }.availability)
    }

    @Test
    fun `an offer inherits a group declaration but overrides it with its own`() {
        val html = """
            <script type="application/ld+json">
            {"@type":"Product","offers":{"@type":"AggregateOffer",
              "availability":"https://schema.org/OutOfStock","offers":[
                {"name":"Standard","price":"2199","priceCurrency":"UAH"},
                {"name":"Deluxe","price":"2999","priceCurrency":"UAH",
                 "availability":"InStock"}]}}
            </script>
        """.trimIndent()

        val offers = extractOffers(html)
        assertEquals(Availability.SOLD_OUT, offers.first { it.label == "Standard" }.availability)
        assertEquals(Availability.IN_STOCK, offers.first { it.label == "Deluxe" }.availability)
    }

    // ------------------------------------------ the four ways the number does damage

    @Test
    fun `a sold-out figure never enters the price history`() {
        // The longest-lived damage of the four: a placeholder in the history
        // poisons priceInsight, the thirty-day window and priorLow for months.
        val previous = wish(
            source("rozetka.ua", 2_400.0),
            history = listOf(PricePoint(2_600.0, today - 20), PricePoint(2_400.0, today - 5))
        )
        val reading = readSource(
            wishSources(previous).single(),
            jsonLd("1199", "https://schema.org/OutOfStock"),
            today,
            rate
        )
        val merged = mergeSources(previous, listOf(reading), today, rate)

        assertTrue(merged is Reading.Stale)
        val after = (merged as Reading.Stale).wish
        assertEquals(listOf(2_600.0, 2_400.0), after.history.map { it.price })
        assertEquals(2_400.0, after.price, 0.001)
        assertEquals(Freshness.OUT_OF_STOCK, after.freshness)
        // The page was genuinely read, so how long ago that was is still true.
        assertEquals(today, after.checkedDay)
    }

    @Test
    fun `a sold-out figure raises no price-drop alert`() {
        val previous = wish(
            source("rozetka.ua", 2_400.0),
            history = listOf(PricePoint(2_600.0, today - 20), PricePoint(2_400.0, today - 5))
        )
        val reading = readSource(
            wishSources(previous).single(),
            jsonLd("1199", "OutOfStock"),
            today,
            rate
        )

        // The worker only ever asks [priceAlertFor] on a Priced reading, so keeping
        // the reading Stale is the whole guard.
        assertTrue(reading is SourceReading.Stale)
        // And this is what it is a guard against: had the figure been let through,
        // that is the notification the phone would have rung with — the exact
        // message that sent the owner to a shop for a thing that was not there.
        assertEquals(AlertKind.NEW_LOW, priceAlertFor(previous, 1_199.0).kind)
    }

    @Test
    fun `a sold-out figure cannot become the lowest ever tracked`() {
        val previous = wish(
            source("rozetka.ua", 2_400.0),
            history = listOf(PricePoint(2_600.0, today - 20), PricePoint(2_400.0, today - 5))
        )
        val before = lowestTracked(previous)
        val merged = mergeSources(
            previous,
            listOf(readSource(wishSources(previous).single(), jsonLd("1199", "SoldOut"), today, rate)),
            today,
            rate
        )
        val after = (merged as Reading.Stale).wish

        assertEquals(2_400.0, before, 0.001)
        // Unchanged, so a purchase is still judged against prices somebody could
        // actually have paid.
        assertEquals(2_400.0, lowestTracked(after), 0.001)
        assertEquals(
            PurchaseVerdict.PATIENT,
            purchaseReview(2_400.0, lowestTracked(after)).verdict
        )
    }

    @Test
    fun `a sold-out shop does not win the cheapest contest`() {
        // The path by which the bug reached the card: bestSource picks the cheapest
        // OK source, and a sold-out shop is routinely the cheapest number going.
        val previous = wish(source("rozetka.ua", 2_400.0), source("comfy.ua", 2_500.0))
        val readings = listOf(
            readSource(wishSources(previous)[0], jsonLd("1199", "OutOfStock"), today, rate),
            readSource(wishSources(previous)[1], jsonLd("2450", "InStock"), today, rate)
        )

        val merged = mergeSources(previous, readings, today, rate)
        val after = (merged as Reading.Priced).wish

        assertEquals(2_450.0, after.price, 0.001)
        assertEquals("https://comfy.ua/item", after.url)
        assertEquals(Freshness.OK, after.freshness)
        // And the shop that has it is the one the row marks as having it.
        assertEquals(Availability.SOLD_OUT, after.sources[0].availability)
        assertEquals(Availability.IN_STOCK, after.sources[1].availability)
        assertNull(bestSource(after.sources)?.takeIf { it.url.contains("rozetka") })
    }

    @Test
    fun `a wish added from a sold-out page starts with no history to poison`() {
        val wish = wishFromOffer(
            jsonLd("1199", "OutOfStock"),
            "https://rozetka.ua/item",
            "id",
            extractOffers(jsonLd("1199", "OutOfStock")).single(),
            today,
            rate
        )

        assertEquals(Freshness.OUT_OF_STOCK, wish.freshness)
        assertTrue(wish.history.isEmpty())
        // The figure is kept and shown, because knowing what it cost when it was
        // buyable is the reason to go on watching it.
        assertEquals(1_199.0, wish.price, 0.001)
        assertEquals(Availability.SOLD_OUT, wish.sources.single().availability)
    }

    @Test
    fun `an in-stock page is added exactly as it always was`() {
        val html = jsonLd("2199", "https://schema.org/InStock")
        val wish = wishFromOffer(html, "https://rozetka.ua/item", "id", extractOffers(html).single(), today, rate)

        assertEquals(Freshness.OK, wish.freshness)
        assertEquals(listOf(2_199.0), wish.history.map { it.price })
    }

    @Test
    fun `a page that says nothing behaves as it did before any of this`() {
        val previous = wish(source("rozetka.ua", 2_400.0))
        val merged = mergeSources(
            previous,
            listOf(readSource(wishSources(previous).single(), jsonLd("1199", null), today, rate)),
            today,
            rate
        )
        val after = (merged as Reading.Priced).wish

        assertEquals(1_199.0, after.price, 0.001)
        assertEquals(Freshness.OK, after.freshness)
        assertEquals(listOf(1_199.0), after.history.map { it.price })
        assertEquals(Availability.UNKNOWN, after.sources.single().availability)
    }

    // ------------------------------------------------------ coming back is news

    @Test
    fun `a thing that comes back into stock reaches the back-in-stock alert`() {
        val outOfStock = wish(source("rozetka.ua", 2_400.0))
        val gone = (
            mergeSources(
                outOfStock,
                listOf(
                    readSource(
                        wishSources(outOfStock).single(),
                        jsonLd("1199", "OutOfStock"),
                        today,
                        rate
                    )
                ),
                today,
                rate
            ) as Reading.Stale
            ).wish

        val back = mergeSources(
            gone,
            listOf(readSource(wishSources(gone).single(), jsonLd("2350", "InStock"), today + 1, rate)),
            today + 1,
            rate
        )

        assertTrue(back is Reading.Priced)
        val alert = priceAlertFor(gone, (back as Reading.Priced).wish.price)
        assertEquals(AlertKind.BACK_IN_STOCK, alert.kind)
        assertEquals(2_350.0, alert.notifyPrice, 0.001)
        // And the row stops claiming it is sold out the moment it is not.
        assertEquals(Availability.IN_STOCK, back.wish.sources.single().availability)
        assertEquals(Freshness.OK, back.wish.freshness)
    }

    @Test
    fun `a shop that restocks stops carrying its old declaration`() {
        val stale = source("rozetka.ua", 2_400.0, Freshness.OUT_OF_STOCK, Availability.DISCONTINUED)
        val reading = readSource(stale, jsonLd("2350", "InStock"), today, rate)

        assertTrue(reading is SourceReading.Priced)
        assertEquals(Availability.IN_STOCK, (reading as SourceReading.Priced).source.availability)
        assertNull(sourceStockNote(reading.source)?.takeIf { it.contains("зняв") })
    }

    @Test
    fun `a page that lost the variant altogether forgets what it once declared`() {
        val stale = source("rozetka.ua", 2_400.0, availability = Availability.IN_STOCK)
            .copy(variant = "Deluxe")
        val reading = readSource(stale, jsonLd("2350", "InStock", name = "Standard"), today, rate)

        // The declaration described an edition this page no longer lists, so the
        // honest answer is that nothing is known about it any more.
        assertTrue(reading is SourceReading.Stale)
        val after = (reading as SourceReading.Stale).source
        assertEquals(Freshness.OUT_OF_STOCK, after.freshness)
        assertEquals(Availability.UNKNOWN, after.availability)
    }

    // --------------------------------------------------------------- what it says

    @Test
    fun `the out-of-stock wording no longer blames a missing variant`() {
        val note = freshnessNote(Freshness.OUT_OF_STOCK).orEmpty()

        assertEquals("Немає в наявності", freshnessLabel(Freshness.OUT_OF_STOCK))
        // The state now arrives from a plain declaration as often as from a variant
        // vanishing, so the sentence may not name the variant as the cause.
        assertFalse(note.contains("варіант"))
        assertTrue(note.contains("остання відома"))
    }

    @Test
    fun `a discontinued shop tells you to stop waiting rather than to wait`() {
        val dropped = source("rozetka.ua", 2_400.0, Freshness.OUT_OF_STOCK, Availability.DISCONTINUED)
        val out = source("comfy.ua", 2_500.0, Freshness.OUT_OF_STOCK, Availability.SOLD_OUT)

        assertTrue(sourceNote(dropped).orEmpty().contains("зняв товар з продажу"))
        assertEquals(freshnessNote(Freshness.OUT_OF_STOCK), sourceNote(out))
    }

    @Test
    fun `the shop list says which shops have the thing`() {
        assertEquals("Є в наявності", sourceStockNote(source("comfy.ua", 2_500.0, availability = Availability.IN_STOCK)))
        // Silent where the shop said nothing, which is most shops: the app must not
        // make a claim on a shop's behalf.
        assertNull(sourceStockNote(source("rozetka.ua", 2_400.0)))
        assertEquals(
            freshnessNote(Freshness.OUT_OF_STOCK),
            sourceStockNote(source("allo.ua", 2_300.0, Freshness.OUT_OF_STOCK))
        )
    }

    @Test
    fun `the picker marks the editions that cannot be bought`() {
        assertEquals("немає в наявності", offerStockLabel(Offer(2_199.0, "Standard", UAH, Availability.SOLD_OUT)))
        assertEquals("знято з продажу", offerStockLabel(Offer(2_199.0, "Standard", UAH, Availability.DISCONTINUED)))
        assertEquals("є в наявності", offerStockLabel(Offer(2_999.0, "Deluxe", UAH, Availability.IN_STOCK)))
        assertNull(offerStockLabel(Offer(2_999.0, "Deluxe", UAH, Availability.UNKNOWN)))
    }

    // ------------------------------------------------------------------ storage

    @Test
    fun `a declaration survives being stored and read back`() {
        Availability.entries.forEach { value ->
            val stored = sourceJson(source("rozetka.ua", 2_400.0, availability = value))
            assertEquals(value, sourceOf(stored).availability)
        }
    }

    @Test
    fun `a source stored before this existed reads back as a silent page`() {
        val old = JSONObject()
            .put("u", "https://rozetka.ua/item").put("p", 2_400.0)
            .put("fr", "OK").put("cd", today)

        assertEquals(Availability.UNKNOWN, sourceOf(old).availability)
        assertEquals(Availability.UNKNOWN, availabilityStored("SomethingElse"))
    }

    @Test
    fun `a whole wish carries its shops' declarations through the bin`() {
        val before = wish(
            source("rozetka.ua", 2_400.0, Freshness.OUT_OF_STOCK, Availability.SOLD_OUT),
            source("comfy.ua", 2_500.0, availability = Availability.IN_STOCK)
        )

        val after = wishOf(wishJson(before))

        assertEquals(Availability.SOLD_OUT, after.sources[0].availability)
        assertEquals(Availability.IN_STOCK, after.sources[1].availability)
    }
}

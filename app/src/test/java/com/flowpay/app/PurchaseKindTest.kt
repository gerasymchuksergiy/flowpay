package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A download, a parcel the app follows, and a parcel it cannot.
 *
 * The owner's own two purchases are the fixtures: a game bought on Steam that sat
 * under «Замовлено» on a delivery rail, and a Temu parcel carried under RL…EE that
 * showed Nova Poshta's «ще не питав» rows. Neither could be closed.
 */
class PurchaseKindTest {

    private val steam = Order(
        id = "s1",
        name = "EA SPORTS FC™ 27 у Steam",
        url = "https://store.steampowered.com/app/3405690/EA_SPORTS_FC_27/",
        status = ORDERED,
        price = 2_199.0,
        digital = true
    )

    private val temu = Order(
        id = "t1",
        name = "nova 2 lite бездротовий ігровий",
        url = "https://www.temu.com/ua/goods.html?goods_id=1",
        status = ORDERED,
        tracking = "RL778364634EE",
        price = 1_117.0
    )

    private val nova = temu.copy(id = "n1", tracking = "20450000000001")

    // ------------------------------------------------------------ which shop

    @Test
    fun `game and key shops are recognised by their host`() {
        assertTrue(isDigitalStore("https://store.steampowered.com/app/1/"))
        assertTrue(isDigitalStore("https://www.gog.com/en/game/x"))
        assertTrue(isDigitalStore("https://store.epicgames.com/uk/p/x"))
        assertTrue(isDigitalStore("  https://STORE.PLAYSTATION.COM/uk-ua/product/x "))
    }

    @Test
    fun `a shop that sells boxes is not a download`() {
        assertFalse(isDigitalStore("https://www.temu.com/ua/goods.html?goods_id=1"))
        assertFalse(isDigitalStore("https://rozetka.com.ua/ua/x/p1/"))
        // Sells consoles. Calling one a download would hide the parcel.
        assertFalse(isDigitalStore("https://direct.playstation.com/uk-ua/x"))
    }

    @Test
    fun `a host that merely ends in the same letters is not the shop`() {
        // "ea.com" must not swallow every domain that happens to end in it.
        assertFalse(isDigitalStore("https://idea.com/x"))
        assertTrue(isDigitalStore("https://www.ea.com/games/x"))
    }

    @Test
    fun `nonsense is not a shop`() {
        assertFalse(isDigitalStore(""))
        assertFalse(isDigitalStore("not a link"))
    }

    // ------------------------------------------------------------ the card

    @Test
    fun `a download says what it is instead of a delivery stage`() {
        assertEquals(DIGITAL_LABEL, orderOverline(steam))
        assertEquals(ORDERED, orderOverline(temu))
    }

    @Test
    fun `every open purchase can be closed, whatever its stage`() {
        assertEquals("Завершити покупку", closeActionLabel(steam))
        assertEquals("Отримав — завершити", closeActionLabel(temu))
        assertEquals("Завершити покупку", closeActionLabel(temu.copy(status = RECEIVED)))
        assertTrue(closeActionDue(steam))
        assertFalse(closeActionDue(temu))
    }

    @Test
    fun `only Nova Poshta numbers are followed automatically`() {
        assertTrue(isAutoTracked(nova))
        assertFalse(isAutoTracked(temu))
        assertFalse(isAutoTracked(steam))
        // A download with a stray number left on it is still not a parcel.
        assertFalse(isAutoTracked(nova.copy(digital = true)))
    }

    @Test
    fun `the carrier blocks are drawn only where a carrier answered`() {
        assertTrue(carrierSectionsApply(nova))
        assertFalse(carrierSectionsApply(temu))
        assertFalse(carrierSectionsApply(steam))
        // Followed once, then the number was edited: what it learned stays visible.
        val learned = temu.copy(sightings = listOf(Sighting(5, "Прямує", 1L)))
        assertTrue(carrierSectionsApply(learned))
    }

    @Test
    fun `a parcel the app cannot follow says where to set its stage`() {
        assertNull(untrackedNote(nova))
        assertTrue(untrackedNote(temu)!!.contains("не номер Нової Пошти"))
        assertTrue(untrackedNote(temu.copy(tracking = ""))!!.contains("дотиком"))
        assertTrue(untrackedNote(steam)!!.contains("Без доставки"))
    }

    @Test
    fun `another post's number opens on a tracking site`() {
        val page = trackingPageUrl(temu)
        assertNotNull(page)
        assertTrue(page!!.startsWith("https://t.17track.net/uk#nums="))
        assertTrue(page.endsWith("RL778364634EE"))
        assertNull(trackingPageUrl(nova))
        assertNull(trackingPageUrl(steam))
        assertNull(trackingPageUrl(temu.copy(tracking = "  ")))
    }

    // ------------------------------------------------------------ the record

    @Test
    fun `the kind survives the round trip`() {
        assertEquals(steam, orderOf(orderJson(steam)))
        assertEquals(temu, orderOf(orderJson(temu)))
    }

    @Test
    fun `a purchase saved before the kind existed is judged by its shop`() {
        // The Steam game already on the phone was saved without the flag, and it
        // has to stop being a parcel without anyone touching it.
        val old = JSONObject()
            .put("id", "s1").put("n", "EA SPORTS FC 27").put("u", steam.url)
            .put("s", ORDERED)
        assertTrue(orderOf(old).digital)

        val oldParcel = JSONObject()
            .put("id", "t1").put("n", "nova").put("u", temu.url)
            .put("s", ORDERED).put("t", temu.tracking)
        assertFalse(orderOf(oldParcel).digital)
    }

    @Test
    fun `a kind set by hand wins over the shop guess`() {
        // Marked as a parcel on purpose — a boxed edition from a shop on the list.
        val boxed = orderOf(orderJson(steam.copy(digital = false)))
        assertFalse(boxed.digital)
    }

    @Test
    fun `a download is not counted as a parcel on the road`() {
        val summary = overview(
            wishes = emptyList(),
            pays = emptyList(),
            orders = listOf(steam, temu),
            income = 0.0,
            usdSellRate = 0.0,
            today = java.time.LocalDate.of(2026, 10, 3)
        )
        assertEquals(1, summary.parcelsMoving)
    }

    // ------------------------------------------------------------ words

    @Test
    fun `purchases are counted in Ukrainian`() {
        assertEquals("1 покупка", purchasesLabel(1))
        assertEquals("3 покупки", purchasesLabel(3))
        assertEquals("5 покупок", purchasesLabel(5))
        assertEquals("12 покупок", purchasesLabel(12))
        assertEquals("21 покупка", purchasesLabel(21))
    }

    @Test
    fun `after na the count takes the accusative`() {
        assertEquals("1 позицію", positionsAfterNa(1))
        assertEquals("2 позиції", positionsAfterNa(2))
        assertEquals("5 позицій", positionsAfterNa(5))
        assertEquals("11 позицій", positionsAfterNa(11))
        assertEquals("22 позиції", positionsAfterNa(22))
    }
}

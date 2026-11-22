package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import androidx.compose.ui.unit.sp

/**
 * The bento look's logic: which emoji a name gets, how a pack file is read, the
 * tile colours, and the hand-picked emoji surviving the trip through JSON.
 */
class EmojiTest {

    @Test
    fun `the owner's own payments get the emoji he described`() {
        // «інтернет — то браузер емодзі, якщо телефон — то трубка».
        assertEquals("🌐", payEmoji("Інтернет"))
        assertEquals("📞", payEmoji("Мобільний"))
        assertEquals("📞", payEmoji("Київстар"))
        assertEquals("🎨", payEmoji("Підписка Adobe"))
        assertEquals("📺", payEmoji("YouTube Premium"))
        assertEquals("🏠", payEmoji("Оренда квартири"))
        assertEquals("💡", payEmoji("Комуналка"))
        assertEquals("🍿", payEmoji("Netflix"))
    }

    @Test
    fun `the more specific word wins`() {
        // Music before YouTube, television before the phone company.
        assertEquals("🎧", payEmoji("YouTube Music"))
        assertEquals("📺", payEmoji("Київстар ТБ"))
        assertEquals("🌐", payEmoji("Київстар Інтернет"))
    }

    @Test
    fun `a word matches only at the start of a word`() {
        // «газ» is the gas bill, not the inside of «магазин».
        assertEquals("🔥", payEmoji("Газ"))
        assertEquals(PAY_EMOJI_DEFAULT, payEmoji("Магазин"))
        assertEquals(PAY_EMOJI_DEFAULT, payEmoji("Щось незрозуміле"))
    }

    @Test
    fun `purchases are judged by what they are`() {
        assertEquals("📱", orderEmoji("Чохол для телефона", digital = false))
        assertEquals("🎮", orderEmoji("nova 2 lite бездротовий ігровий для пк", digital = false))
        assertEquals("🎮", orderEmoji("EA SPORTS FC 27 у Steam", digital = true))
        assertEquals("💻", orderEmoji("Модуль пам'яті для ноутбука", digital = false))
        assertEquals(ORDER_EMOJI_PARCEL, orderEmoji("Щось з Temu", digital = false))
        assertEquals(ORDER_EMOJI_DIGITAL, orderEmoji("Ліцензія на програму", digital = true))
    }

    @Test
    fun `a hand-picked emoji wins over the guess`() {
        assertEquals("🌐", shownEmoji(Pay("Інтернет", 300.0)))
        assertEquals("🚀", shownEmoji(Pay("Інтернет", 300.0, emoji = "🚀")))
        val order = Order("o", "Чохол для телефона", "", ORDERED)
        assertEquals("📱", shownEmoji(order))
        assertEquals("🎁", shownEmoji(order.copy(emoji = "🎁")))
    }

    @Test
    fun `an emoji's key is its code points without the presentation selector`() {
        assertEquals("1f310", emojiKey("🌐"))
        assertEquals("2601", emojiKey("☁️"))
        assertEquals(emojiKey("☁"), emojiKey("☁️"))
        assertEquals("1f6e1", emojiKey("🛡️"))
        assertEquals("1f468-200d-1f4bb", emojiKey("👨‍💻"))
    }

    @Test
    fun `a pack entry is read by its name and nothing else`() {
        assertEquals("1f310", packEntryKey("1f310.png"))
        assertEquals("1f310", packEntryKey("emoji/1F310.PNG"))
        assertEquals("2601", packEntryKey("2601-fe0f.png"))
        assertEquals("1f468-200d-1f4bb", packEntryKey("1f468-200d-1f4bb.png"))
        // Anything else is skipped, and a name is never a path.
        assertNull(packEntryKey("../../evil.png"))
        assertNull(packEntryKey("readme.txt"))
        assertNull(packEntryKey("globe.png"))
        assertNull(packEntryKey("fe0f.png"))
    }

    @Test
    fun `typed text counts only when it is an emoji`() {
        assertEquals("🚀", typedEmoji(" 🚀 "))
        assertEquals("👨‍💻", typedEmoji("👨‍💻"))
        assertNull(typedEmoji("ракета"))
        assertNull(typedEmoji("123"))
        assertNull(typedEmoji(""))
        assertNull(typedEmoji("🚀 🚀"))
    }

    @Test
    fun `every emoji offered is offered once`() {
        assertEquals(EMOJI_CHOICES.size, EMOJI_CHOICES.map { emojiKey(it) }.toSet().size)
    }

    @Test
    fun `the hand-picked emoji survives the backup and the bin`() {
        val pay = Pay("Інтернет", 300.0, emoji = "🚀")
        assertEquals(pay, payOf(payJson(pay)))
        val order = Order("o1", "Чохол", "https://rozetka.com.ua/x", ORDERED, emoji = "🎁")
        assertEquals(order, orderOf(orderJson(order)))
    }

    @Test
    fun `entries saved before emoji existed come back guessing`() {
        val oldPay = JSONObject().put("n", "Інтернет").put("a", 300.0).put("d", 1)
        assertEquals("", payOf(oldPay).emoji)
        val oldOrder = JSONObject().put("id", "o1").put("n", "Чохол").put("u", "").put("s", ORDERED)
        assertEquals("", orderOf(oldOrder).emoji)
    }

    @Test
    fun `tiles in a run never repeat a neighbour's colour`() {
        val names = listOf("Інтернет", "Мобільний", "Оренда", "Adobe", "YouTube", "Netflix", "iCloud", "Газ", "Вода")
        val colours = tileColours(names)
        colours.forEachIndexed { index, colour ->
            if (index >= 1) assertNotEquals(colours[index - 1], colour)
            if (index >= 2) assertNotEquals(colours[index - 2], colour)
        }
        // The same names give the same colours every time.
        assertEquals(colours, tileColours(names))
    }

    @Test
    fun `a wide figure steps down as it grows`() {
        assertEquals(40f, displayFigureSize("398", 40.sp).value, 0.01f)
        val five = displayFigureSize("27 891", 40.sp).value
        val seven = displayFigureSize("1 234 567", 40.sp).value
        assert(five < 40f && seven < five)
    }

    @Test
    fun `the purchases tile leads with what needs doing soonest`() {
        val moving = Order("a", "Чохол", "", IN_TRANSIT, tracking = "20450000000001")
        val waiting = Order("b", "Кабель", "", AT_BRANCH, tracking = "20450000000002")
        val game = Order("c", "Гра", "", ORDERED, digital = true)
        assertNull(parcelsAtAGlance(emptyList()))
        parcelsAtAGlance(listOf(moving, game))!!.run {
            assertEquals("Зараз у дорозі", label)
            assertEquals("1 посилка", figure)
            assertEquals("1 покупка без доставки", caption)
        }
        parcelsAtAGlance(listOf(moving, waiting, game))!!.run {
            assertEquals("Чекають на відділенні", label)
            assertEquals("1 посилка", figure)
            assertEquals("ще 1 посилка у дорозі · 1 покупка без доставки", caption)
            assert(alarm)
        }
        parcelsAtAGlance(listOf(game))!!.run {
            assertEquals("Відкрито", label)
            assertEquals("1 покупка", figure)
        }
    }
}

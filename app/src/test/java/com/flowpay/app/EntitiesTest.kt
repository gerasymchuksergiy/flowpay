package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Character reference decoding, which used to be a list of spellings.
 *
 * The bug that prompted this: a shop wrote "&#039;" with a zero in it, the table
 * held "&#39;" without one, and a parcel sat on the purchases screen reading
 * «Модуль пам&#039;яті для ноутбука SoDIMM». There is no row that fixes that
 * class of bug, only a rule, so what is pinned here is the rule — including the
 * two orderings it must not break.
 */
class EntitiesTest {

    @Test
    fun `the zero padded apostrophe that started this decodes`() {
        assertEquals(
            "Модуль пам'яті для ноутбука SoDIMM",
            decodeEntities("Модуль пам&#039;яті для ноутбука SoDIMM")
        )
    }

    @Test
    fun `the same title decodes through the title cleaner`() {
        assertEquals(
            "Модуль пам'яті для ноутбука SoDIMM",
            cleanProductTitle("Модуль пам&#039;яті для ноутбука SoDIMM")
        )
    }

    @Test
    fun `any amount of zero padding is the same character`() {
        for (padding in 0..8) {
            val reference = "&#" + "0".repeat(padding) + "39;"
            assertEquals(reference, "'", decodeEntities(reference))
        }
    }

    @Test
    fun `hexadecimal references decode in either case and with padding`() {
        assertEquals("'", decodeEntities("&#x27;"))
        assertEquals("'", decodeEntities("&#X27;"))
        assertEquals("'", decodeEntities("&#x0027;"))
        assertEquals("*", decodeEntities("&#x2a;"))
        assertEquals("*", decodeEntities("&#x2A;"))
    }

    @Test
    fun `an escaped ampersand never becomes a second reference`() {
        // The hole the old ordering comment was written against, and the one a
        // numeric decoder could easily have reopened.
        assertEquals("&#39;", decodeEntities("&amp;#39;"))
        assertEquals("&#039;", decodeEntities("&amp;#039;"))
        assertEquals("&quot;", decodeEntities("&amp;quot;"))
        // The numeric spelling of the ampersand has to behave identically, which
        // the old replace-in-order table could not manage in any row order.
        assertEquals("&#39;", decodeEntities("&#38;#39;"))
        assertEquals("&quot;", decodeEntities("&#38;quot;"))
        assertEquals("&quot;", decodeEntities("&#x26;quot;"))
    }

    @Test
    fun `named entities still decode and ignore case`() {
        assertEquals("\"", decodeEntities("&quot;"))
        assertEquals("\"", decodeEntities("&QUOT;"))
        assertEquals("'", decodeEntities("&apos;"))
        assertEquals("<", decodeEntities("&lt;"))
        assertEquals(">", decodeEntities("&gt;"))
        assertEquals("&", decodeEntities("&amp;"))
        assertEquals("Dolce & Gabbana", decodeEntities("Dolce &amp; Gabbana"))
    }

    @Test
    fun `a non breaking space arrives as an ordinary one either way it is spelt`() {
        // Both spellings have to agree, or a title would collapse its whitespace
        // in one shop and keep a welded-in gap in the next.
        assertEquals(" ", decodeEntities("&nbsp;"))
        assertEquals(" ", decodeEntities("&#160;"))
        assertEquals(" ", decodeEntities("&#xA0;"))
        assertEquals("2 199 грн", cleanProductTitle("2&nbsp;199&#160;грн"))
    }

    @Test
    fun `a reference outside unicode is left as written`() {
        assertEquals("&#1114112;", decodeEntities("&#1114112;"))
        assertEquals("&#x110000;", decodeEntities("&#x110000;"))
        assertEquals("&#99999999;", decodeEntities("&#99999999;"))
        assertEquals("&#999999999999;", decodeEntities("&#999999999999;"))
        // The last legal code point does decode in both radixes, so the boundary
        // is not off by one — and decimal needs one more figure than hex for it.
        assertEquals("􏿿", decodeEntities("&#x10FFFF;"))
        assertEquals("􏿿", decodeEntities("&#1114111;"))
    }

    @Test
    fun `a lone surrogate is left as written`() {
        assertEquals("&#xD800;", decodeEntities("&#xD800;"))
        assertEquals("&#xDFFF;", decodeEntities("&#xDFFF;"))
        assertEquals("&#55296;", decodeEntities("&#55296;"))
        // Either side of the surrogate block is ordinary text and must decode.
        assertEquals("퟿", decodeEntities("&#xD7FF;"))
        assertEquals("", decodeEntities("&#xE000;"))
    }

    @Test
    fun `malformed references survive as the text they already were`() {
        assertEquals("&#;", decodeEntities("&#;"))
        assertEquals("&#x;", decodeEntities("&#x;"))
        assertEquals("&;", decodeEntities("&;"))
        assertEquals("&", decodeEntities("&"))
        assertEquals("&#", decodeEntities("&#"))
        assertEquals("&#39", decodeEntities("&#39"))
        assertEquals("&unknown;", decodeEntities("&unknown;"))
        assertEquals("&#-39;", decodeEntities("&#-39;"))
        assertEquals("&#3 9;", decodeEntities("&#3 9;"))
        assertEquals("&#xZZ;", decodeEntities("&#xZZ;"))
        // Arabic-Indic digits are digits to Integer.parseInt and not to HTML.
        assertEquals("&#٣٩;", decodeEntities("&#٣٩;"))
    }

    @Test
    fun `a bare ampersand in running text is not hunted down`() {
        assertEquals("Ціна 5 & 6 грн", decodeEntities("Ціна 5 & 6 грн"))
        assertEquals("R&D", decodeEntities("R&D"))
        // A semicolon far enough away is punctuation, not the end of an entity.
        val far = "&" + "x".repeat(40) + ";"
        assertEquals(far, decodeEntities(far))
    }

    @Test
    fun `control characters never reach the screen`() {
        assertEquals("&#0;", decodeEntities("&#0;"))
        assertEquals("&#00000;", decodeEntities("&#00000;"))
        assertEquals("&#8;", decodeEntities("&#8;"))
        assertEquals("&#127;", decodeEntities("&#127;"))
        // Tab and newline are real whitespace and the tidying collapses them.
        assertEquals("\t", decodeEntities("&#9;"))
        assertEquals("\n", decodeEntities("&#10;"))
    }

    @Test
    fun `the windows 1252 range reads the way every browser reads it`() {
        // Old shop back ends emit these constantly, and the alternative to
        // decoding them is a control character sitting inside a product name.
        assertEquals("’", decodeEntities("&#146;"))
        assertEquals("—", decodeEntities("&#151;"))
        assertEquals("–", decodeEntities("&#150;"))
        assertEquals("€", decodeEntities("&#128;"))
        assertEquals("…", decodeEntities("&#133;"))
        assertEquals("Nike’s", decodeEntities("Nike&#146;s"))
    }

    @Test
    fun `characters above the basic plane decode as a surrogate pair`() {
        assertEquals("😀", decodeEntities("&#128512;"))
        assertEquals("😀", decodeEntities("&#x1F600;"))
        assertEquals("😀", decodeEntities("&#x0001F600;"))
    }

    @Test
    fun `references run together and mixed with text all decode`() {
        assertEquals("\"'<>&", decodeEntities("&quot;&#39;&lt;&#x3E;&amp;"))
        assertEquals(
            """Куртка "Зима" — 50% знижки""",
            decodeEntities("Куртка &#34;Зима&#34; &#151; 50% знижки")
        )
    }

    @Test
    fun `text without an ampersand is returned unchanged`() {
        val plain = "iPhone 15 Pro 256 ГБ"
        assertSame(plain, decodeEntities(plain))
        assertEquals("", decodeEntities(""))
    }

    @Test
    fun `no input throws, whatever it is made of`() {
        val pieces = listOf(
            "&", "#", ";", "x", "0", "9", "F", "amp", "-", " ", "😀", "я"
        )
        for (a in pieces) {
            for (b in pieces) {
                for (c in pieces) {
                    // The only claim is that it returns; what it returns is pinned
                    // by the cases above.
                    decodeEntities(a + b + c)
                }
            }
        }
    }
}

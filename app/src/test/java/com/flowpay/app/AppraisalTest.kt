package com.flowpay.app

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * «Оцінка бажання», pinned.
 *
 * The section's whole argument is that the honesty lives in code rather than in a
 * prompt, so the code is what is tested: the gate that decides before any call
 * whether there is enough to say anything, the templated half that is true by
 * construction, and the checks that throw away an answer which states a figure or
 * speaks for buyers who were never read.
 */
class AppraisalTest {

    private val day = 20_000L

    private fun about(
        description: String = "",
        brand: String = "",
        rating: Double = 0.0,
        ratingCount: Int = 0,
        specs: List<Pair<String, String>> = emptyList()
    ) = ProductAbout(description, brand, rating, ratingCount, specs)

    /** Long enough to clear [APPRAISAL_DESCRIPTION_FLOOR] without being a wall. */
    private val blurb = "Бездротові навушники з активним шумозаглушенням, знімною " +
        "амбушюрою та чохлом для перенесення. Підходять для роботи в дорозі та " +
        "для дзвінків у гучному приміщенні."

    private val threeSpecs = listOf(
        "Тип" to "Накладні",
        "Підключення" to "Bluetooth",
        "Шумозаглушення" to "Активне"
    )

    private fun wish(
        name: String = "Навушники Sony WH-1000XM5",
        price: Double = 9_999.0,
        url: String = "https://rozetka.com.ua/x",
        freshness: Freshness = Freshness.OK,
        about: ProductAbout = about(blurb, brand = "Sony"),
        history: List<PricePoint> = emptyList(),
        added: Long = 0L,
        appraisal: Appraisal? = null
    ) = Wish(
        id = "id",
        name = name,
        url = url,
        image = "",
        price = price,
        history = history,
        freshness = freshness,
        about = about,
        addedDay = added,
        appraisal = appraisal
    )

    // ------------------------------------------------------------- the gate

    @Test
    fun `a build with no key gets no section at all`() {
        assertEquals(AppraisalGate.NO_KEY, appraisalGate(wish(), hasKey = false))
        assertFalse(appraisalShows(wish(), hasKey = false))
        assertFalse(hasAppraisalKey(""))
        assertTrue(hasAppraisalKey("AIza-something"))
    }

    @Test
    fun `a wish with no price has nothing to judge`() {
        assertEquals(AppraisalGate.NO_PRICE, appraisalGate(wish(price = 0.0), hasKey = true))
    }

    @Test
    fun `a price the shop has stopped answering for is not judged`() {
        listOf(Freshness.UNREADABLE, Freshness.OUT_OF_STOCK, Freshness.GONE).forEach { state ->
            assertEquals(
                "freshness $state",
                AppraisalGate.STALE_PRICE,
                appraisalGate(wish(freshness = state), hasKey = true)
            )
        }
        // A hand-typed price is exactly as current as the person who typed it.
        assertEquals(
            AppraisalGate.READY,
            appraisalGate(wish(freshness = Freshness.MANUAL), hasKey = true)
        )
    }

    @Test
    fun `the stand-in name for an unreadable page is not a thing to describe`() {
        val url = "https://temu.com/thing"
        val placeholder = wish(name = placeholderName(url), url = url)
        assertEquals(AppraisalGate.NO_NAME, appraisalGate(placeholder, hasKey = true))
        assertEquals(AppraisalGate.NO_NAME, appraisalGate(wish(name = "Товар"), hasKey = true))
        assertEquals(AppraisalGate.NO_NAME, appraisalGate(wish(name = "Нове бажання"), hasKey = true))
        assertEquals(AppraisalGate.NO_NAME, appraisalGate(wish(name = " X "), hasKey = true))
    }

    @Test
    fun `a shop that published nothing gets no section`() {
        assertEquals(AppraisalGate.TOO_LITTLE, appraisalGate(wish(about = about()), hasKey = true))
    }

    @Test
    fun `a brand and a rating cannot carry the section on their own`() {
        // Two grounds, but neither of them says what the thing actually is.
        val metadata = about(brand = "Sony", rating = 4.6, ratingCount = 900)
        assertEquals(2, appraisalGrounds(metadata))
        assertFalse(appraisalHasSubstance(metadata))
        assertEquals(AppraisalGate.TOO_LITTLE, appraisalGate(wish(about = metadata), hasKey = true))
    }

    @Test
    fun `substance alone is one ground and needs corroborating`() {
        val onlyBlurb = about(blurb)
        assertEquals(1, appraisalGrounds(onlyBlurb))
        assertTrue(appraisalHasSubstance(onlyBlurb))
        assertEquals(AppraisalGate.TOO_LITTLE, appraisalGate(wish(about = onlyBlurb), hasKey = true))
    }

    @Test
    fun `a description with a brand behind it is enough`() {
        assertEquals(AppraisalGate.READY, appraisalGate(wish(), hasKey = true))
        assertTrue(appraisalShows(wish(), hasKey = true))
    }

    @Test
    fun `a spec table with a weighted rating behind it is enough`() {
        val table = about(specs = threeSpecs, rating = 4.6, ratingCount = APPRAISAL_RATING_FLOOR)
        assertEquals(2, appraisalGrounds(table))
        assertEquals(AppraisalGate.READY, appraisalGate(wish(about = table), hasKey = true))
    }

    @Test
    fun `a rating with too few people behind it is not a ground`() {
        val thin = about(specs = threeSpecs, rating = 5.0, ratingCount = APPRAISAL_RATING_FLOOR - 1)
        assertEquals(1, appraisalGrounds(thin))
        assertEquals(AppraisalGate.TOO_LITTLE, appraisalGate(wish(about = thin), hasKey = true))
    }

    @Test
    fun `a short blurb and a couple of spec rows are both below their floors`() {
        val thin = about("Гарні навушники.", specs = listOf("Колір" to "Чорний", "Тип" to "Накладні"))
        assertEquals(0, appraisalGrounds(thin))
        assertFalse(appraisalHasSubstance(thin))
    }

    @Test
    fun `every ground counts once and they add up`() {
        val everything = about(blurb, "Sony", 4.6, 900, threeSpecs)
        assertEquals(4, appraisalGrounds(everything))
    }

    // ------------------------------------------- the half that is not the model

    private fun insight(
        current: Double = 9_999.0,
        lowest: Double = 9_000.0,
        highest: Double = 12_000.0,
        referenceLow: Double = 9_000.0,
        referenceHigh: Double = 12_000.0,
        referenceDays: Int = 30,
        atReferenceLow: Boolean = false,
        changes: Int = 4
    ) = PriceInsight(
        current = current,
        lowest = lowest,
        highest = highest,
        atLowest = current <= lowest,
        position = 0.0,
        offHighest = 0.0,
        daysTracked = referenceDays,
        changes = changes,
        verdict = BuyVerdict.FAIR,
        referenceLow = referenceLow,
        referenceHigh = referenceHigh,
        referenceDays = referenceDays,
        atReferenceLow = atReferenceLow
    )

    @Test
    fun `a window under a week says nothing rather than a nought`() {
        val young = insight(referenceDays = 6)
        assertFalse(appraisalWindowSpeaks(young))
        assertEquals("", appraisalHero(young))
        assertEquals("Власного діапазону цін ще замало, щоб порівнювати", appraisalStanding(young))
    }

    @Test
    fun `a window that never moved says nothing either`() {
        val flat = insight(referenceLow = 9_999.0, referenceHigh = 9_999.0)
        assertFalse(appraisalWindowSpeaks(flat))
        assertEquals("", appraisalHero(flat))
    }

    @Test
    fun `the hero is how far above its own floor the price is standing`() {
        assertEquals("+11%", appraisalHero(insight(current = 9_999.0, referenceLow = 9_000.0)))
        assertEquals("над найнижчою ціною за 30 днів", appraisalStanding(insight()))
    }

    @Test
    fun `a price at the floor of its own window is nought percent above it`() {
        assertEquals("0%", appraisalHero(insight(current = 9_000.0, atReferenceLow = true)))
    }

    @Test
    fun `the shut heading reads as one line and comes only from the templated half`() {
        val facts = appraisalFacts(wish(), insight(), freeCash = 20_000.0, today = day)
        assertEquals("+11% над найнижчою ціною за 30 днів", appraisalSummary(facts))

        val quiet = appraisalFacts(wish(), insight(referenceDays = 3), 20_000.0, day)
        assertEquals("Власного діапазону цін ще замало, щоб порівнювати", appraisalSummary(quiet))
    }

    @Test
    fun `the templated rows are the facts the app can prove`() {
        val item = wish(
            about = about(blurb, "Sony", 4.6, 900),
            added = day - 40
        )
        val facts = appraisalFacts(item, insight(), freeCash = 20_000.0, today = day)
        val labels = facts.rows.map { it.label }
        assertTrue(labels.contains("Діапазон за 30 днів"))
        assertTrue(labels.contains("Найнижча за весь час"))
        assertTrue(labels.contains("Записано цін"))
        assertEquals("40 днів", facts.rows.first { it.label == "У списку" }.value)
        assertEquals("4,6 / 5 · 900 відгуків", facts.rows.first { it.label == "Оцінка магазину" }.value)
        assertEquals("50%", facts.rows.first { it.label == "Частка вільних коштів" }.value)
    }

    @Test
    fun `a thing costing more than the month has free is the one row that alarms`() {
        val tight = appraisalFacts(wish(), insight(), freeCash = 4_000.0, today = day)
        val share = tight.rows.first { it.label == "Частка вільних коштів" }
        assertEquals("250%", share.value)
        assertTrue(share.alarm)

        val easy = appraisalFacts(wish(), insight(), freeCash = 20_000.0, today = day)
        assertFalse(easy.rows.first { it.label == "Частка вільних коштів" }.alarm)
    }

    @Test
    fun `nothing is claimed about free cash when there is no income to divide by`() {
        val facts = appraisalFacts(wish(), insight(), freeCash = 0.0, today = day)
        assertTrue(facts.rows.none { it.label == "Частка вільних коштів" })
    }

    @Test
    fun `a window that cannot speak drops its own row rather than printing a dash`() {
        val facts = appraisalFacts(wish(), insight(referenceDays = 4), 20_000.0, day)
        assertTrue(facts.rows.none { it.label.startsWith("Діапазон") })
    }

    @Test
    fun `every figure is Ukrainian whatever language the phone is set to`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val note = appraisalStaleNote(Appraisal(kind = "x", price = 2_499.5))
            assertTrue(note, note.contains("499,5"))
            assertFalse(note, note.contains("499.5"))
            // And the hero, which is the one figure set large on the screen.
            assertEquals("+11%", appraisalHero(insight()))
        } finally {
            Locale.setDefault(original)
        }
    }

    // ---------------------------------------------------- what the model is told

    @Test
    fun `the model is shown the words and never the numbers`() {
        val item = wish(
            price = 9_999.0,
            about = about(blurb, "Sony", 4.6, 900, threeSpecs)
        )
        val prompt = appraisalPrompt(item)
        assertTrue(prompt.contains("Навушники Sony WH-1000XM5"))
        assertTrue(prompt.contains("Бренд: Sony"))
        assertTrue(prompt.contains("Шумозаглушення: Активне"))
        assertTrue(prompt.contains("амбушюрою"))
        // The price and the rating stay out, so the model cannot write a paragraph
        // that contradicts either of them where they are shown beside it.
        assertFalse(prompt.contains("9999"))
        assertFalse(prompt.contains("9 999"))
        assertFalse(prompt.contains("4,6"))
        assertFalse(prompt.contains("4.6"))
        assertFalse(prompt.contains("900"))
    }

    @Test
    fun `the request asks for structured output at a fixed temperature`() {
        val body = JSONObject(appraisalBody(wish()))
        val config = body.getJSONObject("generationConfig")
        assertEquals(0.0, config.getDouble("temperature"), 0.0)
        assertEquals("application/json", config.getString("responseMimeType"))
        val required = config.getJSONObject("responseSchema").getJSONArray("required")
        assertEquals(3, required.length())
        assertTrue(
            body.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text")
                .contains("Заборонено")
        )
    }

    @Test
    fun `the endpoint names the model that was actually verified`() {
        assertEquals("gemini-3.1-flash-lite", APPRAISAL_MODEL)
        assertTrue(APPRAISAL_ENDPOINT.startsWith("https://generativelanguage.googleapis.com/"))
        assertTrue(APPRAISAL_ENDPOINT.endsWith("/models/$APPRAISAL_MODEL:generateContent"))
    }

    // -------------------------------------------------- checking what came back

    @Test
    fun `speaking for buyers is caught however it is inflected`() {
        listOf(
            "За відгуками покупців вони чудові",
            "Користувачі скаржаться на кріплення",
            "Більшість обирає саме цю модель",
            "Рекомендую до покупки",
            "Це найкращі навушники в класі",
            "Високий рейтинг у магазині"
        ).forEach { assertTrue(it, speaksForBuyers(it)) }

        assertFalse(speaksForBuyers("Накладні навушники із шумозаглушенням для роботи в дорозі."))
    }

    @Test
    fun `any digit at all counts as stating a figure`() {
        assertTrue(statesFigures("Тримають заряд до 30 годин"))
        assertTrue(statesFigures("Bluetooth 5.2"))
        assertFalse(statesFigures("Тримають заряд близько доби"))
    }

    @Test
    fun `a quotation has to be found in the shop's own description`() {
        assertTrue(quotedFromShop("знімною амбушюрою та чохлом для перенесення", blurb))
        // Collapsed whitespace, because the description arrives tidied and the
        // model does not always give it back the same way.
        assertTrue(quotedFromShop("знімною   амбушюрою\nта чохлом для перенесення", blurb))
        // A paraphrase is not a quotation, however plausible it sounds.
        assertFalse(quotedFromShop("зі зручною амбушюрою та футляром для перенесення", blurb))
        // Too short to be a quotation of anything.
        assertFalse(quotedFromShop("чохол", blurb))
        assertFalse(quotedFromShop("знімною амбушюрою та чохлом", ""))
    }

    // ------------------------------------------------------ reading a response

    private fun response(text: String) = JSONObject()
        .put(
            "candidates",
            org.json.JSONArray().put(
                JSONObject().put(
                    "content",
                    JSONObject().put(
                        "parts",
                        org.json.JSONArray().put(JSONObject().put("text", text))
                    )
                )
            )
        )
        .toString()

    private val goodAnswer = JSONObject()
        .put("kind", "Накладні бездротові навушники для роботи в дорозі та дзвінків.")
        .put(
            "weigh",
            org.json.JSONArray()
                .put("Наскільки щільно прилягають амбушюри")
                .put("Чи можна замінити амбушюри окремо")
        )
        .put("shopSays", "знімною амбушюрою та чохлом для перенесення")
        .toString()

    @Test
    fun `a clean answer is kept with the price and the model it was written for`() {
        val reading = readAppraisal(response(goodAnswer), about(blurb), price = 9_999.0, day = day)
        val written = (reading as AppraisalReading.Written).appraisal
        assertEquals(2, written.weigh.size)
        assertEquals("знімною амбушюрою та чохлом для перенесення", written.shopSays)
        assertEquals(9_999.0, written.price, 0.0)
        assertEquals(day, written.day)
        assertEquals(APPRAISAL_MODEL, written.model)
        assertFalse(written.isEmpty)
    }

    @Test
    fun `a paragraph in the voice of buyers is refused whole`() {
        val answer = JSONObject()
            .put("kind", "За відгуками покупців тримають заряд довго.")
            .put("weigh", org.json.JSONArray().put("Зручність"))
            .put("shopSays", "")
            .toString()
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal(response(answer), about(blurb), 9_999.0, day)
        )
    }

    @Test
    fun `one invented figure in one point refuses the whole answer`() {
        val answer = JSONObject()
            .put("kind", "Накладні бездротові навушники.")
            .put(
                "weigh",
                org.json.JSONArray()
                    .put("Зручність амбушюр")
                    .put("Автономність до 30 годин")
            )
            .put("shopSays", "")
            .toString()
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal(response(answer), about(blurb), 9_999.0, day)
        )
    }

    @Test
    fun `an answer with nothing to weigh is refused`() {
        val answer = JSONObject()
            .put("kind", "Накладні бездротові навушники.")
            .put("weigh", org.json.JSONArray())
            .put("shopSays", "")
            .toString()
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal(response(answer), about(blurb), 9_999.0, day)
        )
    }

    @Test
    fun `a quotation the shop never wrote is dropped without losing the answer`() {
        val answer = JSONObject()
            .put("kind", "Накладні бездротові навушники.")
            .put("weigh", org.json.JSONArray().put("Зручність амбушюр"))
            .put("shopSays", "Найтихіші навушники, які ми продавали за всю історію магазину")
            .toString()
        val written = (readAppraisal(response(answer), about(blurb), 9_999.0, day)
            as AppraisalReading.Written).appraisal
        assertEquals("", written.shopSays)
        assertEquals("Накладні бездротові навушники.", written.kind)
    }

    @Test
    fun `a malformed or empty response is a refusal rather than a crash`() {
        val about = about(blurb)
        assertEquals(AppraisalReading.Refused, readAppraisal("", about, 9_999.0, day))
        assertEquals(AppraisalReading.Refused, readAppraisal("not json at all", about, 9_999.0, day))
        assertEquals(AppraisalReading.Refused, readAppraisal("{}", about, 9_999.0, day))
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal(response("still not json"), about, 9_999.0, day)
        )
        // A model that was cut off mid-sentence leaves a part with no object in it.
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal("""{"candidates":[{"finishReason":"SAFETY"}]}""", about, 9_999.0, day)
        )
    }

    @Test
    fun `text split across several parts is read as one answer`() {
        val halves = JSONObject()
            .put(
                "candidates",
                org.json.JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            org.json.JSONArray()
                                .put(JSONObject().put("text", goodAnswer.take(30)))
                                .put(JSONObject().put("text", goodAnswer.drop(30)))
                        )
                    )
                )
            )
            .toString()
        assertTrue(readAppraisal(halves, about(blurb), 9_999.0, day) is AppraisalReading.Written)
    }

    // ------------------------------------------------------------- going stale

    @Test
    fun `a verdict written for a price that has since moved is marked stale`() {
        val written = Appraisal(kind = "x", weigh = listOf("y"), price = 10_000.0, day = day)
        assertFalse(appraisalStale(written, 10_000.0))
        assertFalse(appraisalStale(written, 10_400.0))
        assertTrue(appraisalStale(written, 10_500.0))
        assertTrue(appraisalStale(written, 9_500.0))
        assertFalse(appraisalStale(null, 10_000.0))
        assertFalse(appraisalStale(Appraisal(), 10_000.0))
        assertTrue(appraisalStaleNote(written).contains(money(10_000.0)))
    }

    // ------------------------------------------------------------ what is kept

    @Test
    fun `a verdict survives the round trip through storage`() {
        val written = Appraisal(
            kind = "Накладні бездротові навушники.",
            weigh = listOf("Зручність амбушюр", "Чи є змінні амбушюри"),
            shopSays = "знімною амбушюрою та чохлом для перенесення",
            price = 9_999.0,
            day = day,
            model = APPRAISAL_MODEL
        )
        val back = wishOf(wishJson(wish(appraisal = written)))
        assertEquals(written, back.appraisal)
    }

    @Test
    fun `a wish nobody has asked about comes back with nothing rather than an empty one`() {
        assertNull(wishOf(wishJson(wish())).appraisal)
        assertFalse(wishJson(wish()).has("ap"))
        assertNull(appraisalOf(null))
        assertNull(appraisalOf(JSONObject()))
        assertNotNull(appraisalOf(appraisalJson(Appraisal(kind = "x", price = 1.0))))
    }

    // ------------------------------------------------------------- the wording

    @Test
    fun `the button says which of the two things it is about to do`() {
        assertEquals("Оцінити", appraisalAskLabel(written = false))
        assertEquals("Оцінити ще раз", appraisalAskLabel(written = true))
    }

    @Test
    fun `the model's half says how it was produced and what it lacks`() {
        assertTrue(APPRAISAL_SOURCE_NOTE.contains("Gemini"))
        assertTrue(APPRAISAL_SOURCE_NOTE.contains("Не за відгуками"))
        assertEquals("З опису магазину: «щось»", shopQuoteLine("щось"))
    }

    @Test
    fun `no failure state is an empty space where a paragraph should be`() {
        listOf(
            APPRAISAL_IDLE_NOTE,
            APPRAISAL_BUSY_NOTE,
            APPRAISAL_REFUSED_NOTE,
            APPRAISAL_FAILED_NOTE
        ).forEach { assertTrue(it.isNotBlank()) }
    }
}

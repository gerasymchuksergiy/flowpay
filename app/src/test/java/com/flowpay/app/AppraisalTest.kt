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
        assertTrue(labels.contains("В історії"))
        assertEquals("4 зміни", facts.rows.first { it.label == "В історії" }.value)
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
        // The price and the rating stay out, and grounding did not change that: a
        // model that never saw the figure printed above the block cannot write a
        // sentence that disagrees with it.
        assertFalse(prompt.contains("9999"))
        assertFalse(prompt.contains("9 999"))
        assertFalse(prompt.contains("4,6"))
        assertFalse(prompt.contains("4.6"))
        assertFalse(prompt.contains("900"))
    }

    @Test
    fun `the user turn asks for every part of the review the owner asked for`() {
        val prompt = appraisalPrompt(wish())
        listOf(
            "ЩО ЦЕ",
            "СИЛЬНІ СТОРОНИ",
            "СЛАБКІ МІСЦЯ",
            "КОМУ ПІДІЙДЕ",
            "КОМУ НЕ ВАРТО",
            "ПЕРЕД ПОКУПКОЮ",
            "НІЧОГО НЕ ЗНАЙШОВ"
        ).forEach { assertTrue(it, prompt.contains(it)) }
        // And it says to work out what the thing is before searching, because a
        // shop title searched verbatim finds the shop.
        assertTrue(prompt.contains("огляди"))
    }

    @Test
    fun `the system instruction carries every rule the answer is judged by`() {
        // Search before judging.
        assertTrue(APPRAISAL_SYSTEM.contains("Спочатку шукай"))
        assertTrue(APPRAISAL_SYSTEM.contains("пошуком Google"))
        // Permission to say it found nothing, which is the failure mode the whole
        // feature is built around.
        assertTrue(APPRAISAL_SYSTEM.contains("не вдалося"))
        // Weaknesses concrete rather than hedged, named as a failure.
        assertTrue(APPRAISAL_SYSTEM.contains("компроміси"))
        // One person's decision, not a product page.
        assertTrue(APPRAISAL_SYSTEM.contains("Не для магазину"))
        // The two figures it was never shown.
        assertTrue(APPRAISAL_SYSTEM.contains("ціну"))
        assertTrue(APPRAISAL_SYSTEM.contains("рейтинг"))
    }

    @Test
    fun `the request turns on Google Search and sends nothing that would break it`() {
        val body = JSONObject(appraisalBody(wish()))

        // The current spelling of the tool. googleSearchRetrieval is the 1.5-era
        // one and would be quietly wrong here.
        val tools = body.getJSONArray("tools")
        assertEquals(1, tools.length())
        assertTrue(tools.getJSONObject(0).has("googleSearch"))
        assertFalse(tools.getJSONObject(0).has("googleSearchRetrieval"))

        // Structured output and the search tool cannot travel together on this
        // model — sending both is a rejected request, not a warning. Their absence
        // is the load-bearing part of this body.
        val config = body.getJSONObject("generationConfig")
        assertFalse(config.has("responseMimeType"))
        assertFalse(config.has("responseSchema"))
        assertFalse(config.has("responseFormat"))

        assertTrue(config.getDouble("temperature") < 0.5)
        assertTrue(config.getInt("maxOutputTokens") >= 1_000)
        assertTrue(
            body.getJSONObject("systemInstruction")
                .getJSONArray("parts").getJSONObject(0).getString("text")
                .contains("Спочатку шукай")
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
    fun `a digit is no longer an offence, because a real review is made of them`() {
        // The sentence the owner never got to read.
        val real = "27-дюймова IPS-матриця на 144 Гц з відгуком 1 мс."
        assertFalse(statesPrice(real))
        assertFalse(statesRating(real))
        assertFalse(contradictsOurFigures(real))
    }

    @Test
    fun `naming money is still refused however it is written`() {
        listOf(
            "Коштує помітно більше за сусідів по класу",
            "За ці гривні є цікавіші варіанти",
            "Ціна не відповідає матриці",
            "У цій ціновій категорії є кращі",
            "Дешевший за прямих конкурентів",
            "Дорожчий, ніж має бути",
            "Близько 200 $ за таку панель",
            "Вартість збірки видно одразу",
            "За неї легко переплатити"
        ).forEach { assertTrue(it, statesPrice(it)) }

        // And ordinary words that merely look like them are not money.
        assertFalse(statesPrice("Найцінніша його риса — рівномірність підсвітки"))
        assertFalse(statesPrice("Матриця дає рівний колір по всьому полю"))
    }

    @Test
    fun `naming the shop's star rating is still refused`() {
        listOf(
            "Рейтинг у цього класу високий",
            "Оцінка за кольоропередачу відмінна",
            "Заслуговує п'яти зірок",
            "4,6 з 5 за збірку",
            "Оглядачі ставлять 9/10 — а от 4/5 за підставку"
        ).forEach { assertTrue(it, statesRating(it)) }

        // The trap the shaped check exists for: "з 5" in an honest sentence.
        assertFalse(statesRating("Корпус із 5 портами USB на задній стінці"))
        assertFalse(statesRating("Підсвітка на 144 Гц тримає рівно"))
    }

    @Test
    fun `speaking for other people is caught however it is inflected`() {
        listOf(
            "За відгуками покупців вони чудові",
            "Користувачі скаржаться на кріплення",
            "Більшість обирає саме цю модель",
            "Оглядачі хвалять рівномірність",
            "У тестуванні показав себе рівно"
        ).forEach { assertTrue(it, speaksForBuyers(it)) }

        assertFalse(speaksForBuyers("Накладні навушники із шумозаглушенням для роботи в дорозі."))
    }

    // ------------------------------------------------------- pulling it apart

    @Test
    fun `the answer is split on the headings it was asked for`() {
        val draft = draftAppraisal(
            """
            ЩО ЦЕ: 27-дюймовий ігровий монітор середнього класу.
            СИЛЬНІ СТОРОНИ:
            - Рівна підсвітка без помітних засвітів по кутах
            - Швидка матриця, шлейфів майже не видно
            СЛАБКІ МІСЦЯ:
            - Підставка хитається від дотику до столу
            - Заводське калібрування кольору відчутно холодне
            КОМУ ПІДІЙДЕ: тим, хто грає в динамічні ігри й сидить за столом сам.
            КОМУ НЕ ВАРТО: тим, хто працює з кольором — краще взяти щось із заводським профілем.
            ПЕРЕД ПОКУПКОЮ:
            - Перевірити ревізію матриці, їх було дві
            """.trimIndent()
        )
        assertEquals("27-дюймовий ігровий монітор середнього класу.", draft.kind)
        assertEquals(2, draft.good.size)
        assertEquals(2, draft.weak.size)
        assertTrue(draft.weak[0].startsWith("Підставка"))
        assertTrue(draft.suits.startsWith("тим, хто грає"))
        assertTrue(draft.skip.contains("працює з кольором"))
        assertEquals(1, draft.check.size)
        assertEquals("", draft.unknown)
    }

    @Test
    fun `markdown and stray bullet characters do not cost a paid answer`() {
        val draft = draftAppraisal(
            """
            **ЩО ЦЕ:** 27-дюймовий ігровий монітор.
            ## СИЛЬНІ СТОРОНИ
            * Рівна підсвітка
            • Швидка матриця
            **СЛАБКІ МІСЦЯ:**
            — Підставка хитається
            """.trimIndent()
        )
        assertEquals("27-дюймовий ігровий монітор.", draft.kind)
        assertEquals(listOf("Рівна підсвітка", "Швидка матриця"), draft.good)
        assertEquals(listOf("Підставка хитається"), draft.weak)
    }

    @Test
    fun `a one-line section written under its heading is still one line`() {
        val draft = draftAppraisal(
            """
            КОМУ ПІДІЙДЕ:
            тим, хто грає
            і не працює з кольором
            """.trimIndent()
        )
        assertEquals("тим, хто грає і не працює з кольором", draft.suits)
    }

    @Test
    fun `text before the first heading is dropped rather than misfiled`() {
        val draft = draftAppraisal(
            """
            Ось що вдалося знайти про цю модель.

            ЩО ЦЕ: монітор.
            """.trimIndent()
        )
        assertEquals("монітор.", draft.kind)
    }

    @Test
    fun `tidying strips the marks a model reaches for uninvited`() {
        assertEquals("Рівна підсвітка", tidyAppraisalLine("  - **Рівна підсвітка**  "))
        assertEquals("СИЛЬНІ СТОРОНИ", tidyAppraisalLine("## СИЛЬНІ СТОРОНИ"))
        assertEquals("", tidyAppraisalLine("   "))
    }

    // ------------------------------------------------------ reading a response

    private fun response(
        text: String,
        sources: List<Pair<String, String>> = emptyList(),
        queries: List<String> = emptyList()
    ): String {
        val candidate = JSONObject().put(
            "content",
            JSONObject().put(
                "parts",
                org.json.JSONArray().put(JSONObject().put("text", text))
            )
        )
        if (sources.isNotEmpty() || queries.isNotEmpty()) {
            val chunks = org.json.JSONArray()
            sources.forEach { (title, uri) ->
                chunks.put(
                    JSONObject().put("web", JSONObject().put("title", title).put("uri", uri))
                )
            }
            val asked = org.json.JSONArray()
            queries.forEach { asked.put(it) }
            candidate.put(
                "groundingMetadata",
                JSONObject()
                    .put("groundingChunks", chunks)
                    .put("webSearchQueries", asked)
            )
        }
        return JSONObject().put("candidates", org.json.JSONArray().put(candidate)).toString()
    }

    private val goodAnswer = """
        ЩО ЦЕ: 27-дюймовий ігровий монітор середнього класу.
        СИЛЬНІ СТОРОНИ:
        - Рівна підсвітка без засвітів по кутах
        - Швидка матриця, шлейфів майже не видно
        СЛАБКІ МІСЦЯ:
        - Підставка хитається від дотику до столу
        - Заводське калібрування відчутно холодне
        КОМУ ПІДІЙДЕ: тим, хто грає в динамічні ігри.
        КОМУ НЕ ВАРТО: тим, хто працює з кольором.
        ПЕРЕД ПОКУПКОЮ:
        - Перевірити ревізію матриці, їх було дві
    """.trimIndent()

    private val twoSources = listOf(
        "rtings.com" to "https://vertexaisearch.cloud.google.com/grounding-api-redirect/a",
        "tomshardware.com" to "https://vertexaisearch.cloud.google.com/grounding-api-redirect/b"
    )

    @Test
    fun `a grounded answer is kept whole with what it read`() {
        val reading = readAppraisal(
            response(goodAnswer, twoSources, listOf("Dell S2721DGF review")),
            price = 9_999.0,
            day = day
        )
        val written = (reading as AppraisalReading.Written).appraisal
        assertEquals("27-дюймовий ігровий монітор середнього класу.", written.kind)
        assertEquals(2, written.good.size)
        assertEquals(2, written.weak.size)
        assertEquals(1, written.check.size)
        assertTrue(written.suits.isNotBlank())
        assertTrue(written.skip.isNotBlank())
        assertTrue(written.found)
        assertTrue(written.grounded)
        assertEquals(listOf("rtings.com", "tomshardware.com"), written.sources.map { it.title })
        assertEquals(listOf("Dell S2721DGF review"), written.queries)
        assertEquals(9_999.0, written.price, 0.0)
        assertEquals(day, written.day)
        assertEquals(APPRAISAL_MODEL, written.model)
        assertFalse(written.isEmpty)
    }

    @Test
    fun `a review full of specifications survives, which is the whole point`() {
        val specced = goodAnswer.replace(
            "Швидка матриця, шлейфів майже не видно",
            "165 Гц і 1 мс GtG, шлейфів майже не видно"
        )
        val written = (readAppraisal(response(specced, twoSources), 9_999.0, day)
            as AppraisalReading.Written).appraisal
        assertTrue(written.good.any { it.contains("165 Гц") })
    }

    @Test
    fun `an answer with no sources is kept but is not grounded`() {
        val written = (readAppraisal(response(goodAnswer), 9_999.0, day)
            as AppraisalReading.Written).appraisal
        assertFalse(written.grounded)
        assertTrue(written.sources.isEmpty())
    }

    @Test
    fun `searches that retrieved nothing count as no sources at all`() {
        // Queries recorded, nothing attributable: billed, and ungrounded.
        val written = (readAppraisal(
            response(goodAnswer, emptyList(), listOf("Dell S2721DGF")),
            9_999.0,
            day
        ) as AppraisalReading.Written).appraisal
        assertFalse(written.grounded)
        assertEquals(listOf("Dell S2721DGF"), written.queries)
    }

    @Test
    fun `speaking for other people is fair when grounded and invention when not`() {
        val hearsay = goodAnswer.replace(
            "Підставка хитається від дотику до столу",
            "Оглядачі скаржаться, що підставка хитається"
        )
        // With pages behind it, that is a report of something read.
        assertTrue(
            readAppraisal(response(hearsay, twoSources), 9_999.0, day)
                is AppraisalReading.Written
        )
        // With nothing behind it, it is the old offence again.
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal(response(hearsay), 9_999.0, day)
        )
    }

    @Test
    fun `naming our own price refuses the answer whether or not it is grounded`() {
        val priced = goodAnswer.replace(
            "Рівна підсвітка без засвітів по кутах",
            "Рівна підсвітка, і за ці гроші це дешевший варіант у класі"
        )
        assertEquals(AppraisalReading.Refused, readAppraisal(response(priced, twoSources), 9_999.0, day))
        assertEquals(AppraisalReading.Refused, readAppraisal(response(priced), 9_999.0, day))
    }

    @Test
    fun `naming our own star rating refuses the answer even when grounded`() {
        val rated = goodAnswer.replace(
            "КОМУ ПІДІЙДЕ: тим, хто грає в динамічні ігри.",
            "КОМУ ПІДІЙДЕ: тим, хто грає — рейтинг у класі високий."
        )
        assertEquals(AppraisalReading.Refused, readAppraisal(response(rated, twoSources), 9_999.0, day))
    }

    @Test
    fun `an answer with no weaknesses is marketing copy and is refused`() {
        val sunny = goodAnswer.substringBefore("СЛАБКІ МІСЦЯ:") +
            "КОМУ ПІДІЙДЕ: усім.\n"
        assertEquals(AppraisalReading.Refused, readAppraisal(response(sunny, twoSources), 9_999.0, day))
    }

    @Test
    fun `an answer with nothing good in it is refused too`() {
        val sour = goodAnswer.replace(
            "СИЛЬНІ СТОРОНИ:\n- Рівна підсвітка без засвітів по кутах\n" +
                "- Швидка матриця, шлейфів майже не видно\n",
            ""
        )
        assertEquals(AppraisalReading.Refused, readAppraisal(response(sour, twoSources), 9_999.0, day))
    }

    @Test
    fun `the model saying it found nothing is an answer and is kept`() {
        val nothing = "НІЧОГО НЕ ЗНАЙШОВ: оглядів саме цієї ревізії немає, " +
            "а назва збігається одразу з двома різними моделями."
        val written = (readAppraisal(response(nothing, emptyList(), listOf("щось")), 9_999.0, day)
            as AppraisalReading.Written).appraisal
        assertFalse(written.found)
        assertTrue(written.kind.contains("оглядів саме цієї ревізії"))
        assertTrue(written.good.isEmpty())
        assertTrue(written.weak.isEmpty())
        assertFalse(written.isEmpty)
    }

    @Test
    fun `a not-found line that goes on to review the thing anyway is only the line`() {
        val both = "НІЧОГО НЕ ЗНАЙШОВ: нічого певного.\n" + goodAnswer
        val written = (readAppraisal(response(both, twoSources), 9_999.0, day)
            as AppraisalReading.Written).appraisal
        assertFalse(written.found)
        assertTrue(written.good.isEmpty())
    }

    @Test
    fun `a malformed or empty response is a refusal rather than a crash`() {
        assertEquals(AppraisalReading.Refused, readAppraisal("", 9_999.0, day))
        assertEquals(AppraisalReading.Refused, readAppraisal("not json at all", 9_999.0, day))
        assertEquals(AppraisalReading.Refused, readAppraisal("{}", 9_999.0, day))
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal(response("проза без жодного заголовка"), 9_999.0, day)
        )
        // A model that was cut off mid-sentence leaves a part with no object in it.
        assertEquals(
            AppraisalReading.Refused,
            readAppraisal("""{"candidates":[{"finishReason":"MAX_TOKENS"}]}""", 9_999.0, day)
        )
        // And grounding metadata that is not there is not a crash either.
        assertTrue(appraisalSources("{}").isEmpty())
        assertTrue(appraisalQueries("nonsense").isEmpty())
    }

    @Test
    fun `one page cited for three sentences is one source, not three`() {
        val repeated = listOf(
            "rtings.com" to "https://vertexaisearch.cloud.google.com/grounding-api-redirect/a",
            "rtings.com" to "https://vertexaisearch.cloud.google.com/grounding-api-redirect/a",
            "tomshardware.com" to "https://vertexaisearch.cloud.google.com/grounding-api-redirect/b"
        )
        assertEquals(2, appraisalSources(response(goodAnswer, repeated)).size)
    }

    @Test
    fun `a chunk with no address is not a source`() {
        val json = JSONObject().put(
            "candidates",
            org.json.JSONArray().put(
                JSONObject().put(
                    "groundingMetadata",
                    JSONObject().put(
                        "groundingChunks",
                        org.json.JSONArray()
                            .put(JSONObject().put("web", JSONObject().put("title", "x")))
                            .put(JSONObject().put("retrievedContext", JSONObject()))
                    )
                )
            )
        ).toString()
        assertTrue(appraisalSources(json).isEmpty())
    }

    // ------------------------------------------------------------- going stale

    @Test
    fun `a review written for a price that has since moved is marked stale`() {
        val written = Appraisal(kind = "x", good = listOf("y"), weak = listOf("z"), price = 10_000.0, day = day)
        assertFalse(appraisalStale(written, 10_000.0))
        assertFalse(appraisalStale(written, 10_400.0))
        assertTrue(appraisalStale(written, 10_500.0))
        assertTrue(appraisalStale(written, 9_500.0))
        assertFalse(appraisalStale(null, 10_000.0))
        assertFalse(appraisalStale(Appraisal(), 10_000.0))
        assertTrue(appraisalStaleNote(written).contains(money(10_000.0)))
    }

    // ------------------------------------------------------------ what is kept

    private val stored = Appraisal(
        kind = "27-дюймовий ігровий монітор.",
        good = listOf("Рівна підсвітка", "Швидка матриця"),
        weak = listOf("Підставка хитається"),
        suits = "тим, хто грає",
        skip = "тим, хто працює з кольором",
        check = listOf("Перевірити ревізію матриці"),
        sources = listOf(AppraisalSource("rtings.com", "https://example.test/a")),
        queries = listOf("Dell S2721DGF review"),
        price = 9_999.0,
        day = day,
        model = APPRAISAL_MODEL
    )

    @Test
    fun `a review survives the round trip through storage, sources and all`() {
        val back = wishOf(wishJson(wish(appraisal = stored)))
        assertEquals(stored, back.appraisal)
        // Which is also the bin and the backup file: both carry the payload whole.
        assertTrue(wishJson(wish(appraisal = stored)).getJSONObject("ap").has("src"))
    }

    @Test
    fun `an answer that found nothing keeps that fact through storage`() {
        val unfound = stored.copy(
            kind = "нічого певного не знайшлося",
            good = emptyList(),
            weak = emptyList(),
            check = emptyList(),
            sources = emptyList(),
            found = false
        )
        val back = wishOf(wishJson(wish(appraisal = unfound)))
        assertEquals(unfound, back.appraisal)
        assertEquals(false, back.appraisal?.found)
    }

    @Test
    fun `a review written under the old rules is dropped rather than salvaged`() {
        // The shape version one wrote: a line, a list to weigh, a shop quotation,
        // and no marker. Its sentences were produced under rules that no longer
        // apply, so the honest state is "nobody has asked yet".
        val old = JSONObject()
            .put("k", "Накладні бездротові навушники.")
            .put("w", org.json.JSONArray().put("Зручність амбушюр"))
            .put("q", "знімною амбушюрою")
            .put("p", 9_999.0)
            .put("d", day)
            .put("m", "gemini-3.1-flash-lite")
        assertNull(appraisalOf(old))
        assertEquals(2, APPRAISAL_FORMAT)
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
    fun `a grounded block and an ungrounded one say different things about themselves`() {
        assertTrue(APPRAISAL_SOURCE_NOTE.contains("шукала"))
        assertTrue(APPRAISAL_SOURCE_NOTE.contains("джерела"))
        assertTrue(APPRAISAL_UNGROUNDED_NOTE.contains("жодного джерела"))
        assertTrue(APPRAISAL_UNGROUNDED_NOTE.contains("пам'яті"))
        assertTrue(APPRAISAL_SOURCE_NOTE != APPRAISAL_UNGROUNDED_NOTE)
    }

    @Test
    fun `the sources fold counts in Ukrainian`() {
        assertEquals("1 джерело", sourcesLabel(1))
        assertEquals("3 джерела", sourcesLabel(3))
        assertEquals("5 джерел", sourcesLabel(5))
        assertEquals("11 джерел", sourcesLabel(11))
        assertEquals("1 запит", searchesLabel(1))
        assertEquals("2 запити", searchesLabel(2))
        assertEquals("7 запитів", searchesLabel(7))

        assertEquals(
            "1 джерело · 1 запит",
            appraisalSourcesSummary(stored)
        )
        assertEquals(
            "1 джерело",
            appraisalSourcesSummary(stored.copy(queries = emptyList()))
        )
    }

    @Test
    fun `a source with no title is still openable`() {
        assertEquals("rtings.com", appraisalSourceLabel(AppraisalSource("rtings.com", "https://x")))
        assertEquals("Джерело", appraisalSourceLabel(AppraisalSource("", "https://x")))
    }

    @Test
    fun `the wording counts in Ukrainian whatever language the phone is set to`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("1 джерело", sourcesLabel(1))
            assertEquals("2 запити", searchesLabel(2))
        } finally {
            Locale.setDefault(original)
        }
    }

    @Test
    fun `no failure state is an empty space where a paragraph should be`() {
        listOf(
            APPRAISAL_IDLE_NOTE,
            APPRAISAL_BUSY_NOTE,
            APPRAISAL_REFUSED_NOTE,
            APPRAISAL_FAILED_NOTE,
            APPRAISAL_UNFOUND_NOTE,
            APPRAISAL_UNGROUNDED_NOTE,
            APPRAISAL_SOURCE_NOTE
        ).forEach { assertTrue(it.isNotBlank()) }
        // The refusal now names what actually gets an answer thrown away, which is
        // no longer "цифри".
        assertTrue(APPRAISAL_REFUSED_NOTE.contains("ціну"))
    }
}

package com.flowpay.app

import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Оцінка бажання»: what the app itself can prove about a price, and — separately,
 * and labelled — what a language model makes of the kind of thing it is.
 *
 * **Why the section is built inside out.** Every shipped review-summary feature
 * summarises a corpus of reviews its owner holds: Amazon restricts itself to
 * verified purchases and refuses to raise a point unless several customers make
 * it, Tripadvisor waits for a review count, and Yelp goes furthest by using the
 * model only to sort reviews into topics and then showing counted sentiment, so
 * that there is almost nothing left to invent. This app holds no review text at
 * all. It has a star rating, a review count, the shop's own blurb and a spec
 * table. A sentence here in the voice of buyers would therefore be a sentence
 * about buyers nobody has read.
 *
 * That is not a theoretical worry. CHOICE asked models to recommend specific
 * products and checked the answers against its own laboratory: a recommended
 * vacuum cleaner scored a quarter on pet hair, a praised air fryer came third
 * from last of thirty-seven, and two recommended baby sleep products had failed
 * CHOICE's safety testing. The models did not hedge; they repeated marketing copy
 * as if it were evaluation. And the input shape here is the worst one measured:
 * RAGTruth put response-level hallucination at 29% for question answering and
 * around 30% for news summarisation, but **68.6% for data-to-text** — attributes
 * plus a little review text, turned into prose, which is exactly this screen's
 * input minus the review text.
 *
 * Nor does thin context make a model careful. Joren et al. found Claude 3.5
 * Sonnet abstained on 84.1% of questions closed-book and only 52% once context
 * was supplied: context *reduces* abstention. And wording is a weak lever —
 * telling a model to ignore irrelevant information recovered about five points of
 * a twenty-point gap, scale does nothing for abstention, and reasoning tuning
 * makes it worse. So nothing in this file relies on the prompt to keep the model
 * honest.
 *
 * What works in shipped products is a **gate computed from the data before
 * generation**, and that is the shape here:
 *
 *  - [appraisalGate] decides, in pure arithmetic and before any network call,
 *    whether there is enough to say anything at all. When there is not, the
 *    section does not appear — not a disclaimer, not a thin paragraph, absent.
 *  - [appraisalFacts] is the first half of the section and is not AI at all. It
 *    is the app's own arithmetic about its own thirty-day window, rendered from
 *    templates. It is true by construction.
 *  - The model writes only the second half, it is told neither the price nor the
 *    rating, it may not state a figure, it may not speak for buyers, and any
 *    quotation it offers must be found verbatim in the shop's own description.
 *    [readAppraisal] enforces all of that after the fact, in code, because the
 *    research says the instruction alone will not.
 *
 * The one measured trust finding that bears on the layout: when a summary
 * disagrees with the aggregate rating shown beside it, trust drops, and saying
 * *how* the summary was produced measurably softens the damage. Hence
 * [APPRAISAL_SOURCE_NOTE], and hence withholding the rating from the model — it
 * cannot contradict a figure it was never shown.
 */

// --------------------------------------------------------------- the model

/**
 * The model the second half is written by, and the address it is written at.
 *
 * `gemini-3.1-flash-lite`, checked against Google's own model list rather than
 * remembered: it reached general availability on 7 May 2026, it is the cheapest
 * text model on the Gemini API at $0.25 per million input tokens and $1.50 per
 * million output, and it supports both structured output and a system
 * instruction, which are the two things [appraisalBody] depends on. A guessed
 * model name is a feature that ships broken, and model names change.
 *
 * Written as a constant that travels *into the stored answer*, so that the day
 * this changes, an answer written by the old one still says which model wrote it
 * rather than silently claiming to be the new one's work.
 */
const val APPRAISAL_MODEL = "gemini-3.1-flash-lite"

const val APPRAISAL_ENDPOINT =
    "https://generativelanguage.googleapis.com/v1beta/models/$APPRAISAL_MODEL:generateContent"

/**
 * What the model is told it is doing.
 *
 * Every prohibition below is also checked in code afterwards by [readAppraisal],
 * and the checks are what actually hold. This exists to make the checks fire
 * rarely, not to make them unnecessary.
 */
const val APPRAISAL_SYSTEM: String =
    "Ти пояснюєш, що це за річ і на що дивитися, коли обираєш таку. " +
        "Пиши українською, стисло й спокійно.\n" +
        "Заборонено: говорити від імені покупців або посилатися на відгуки, " +
        "оцінки, рейтинги чи чужу думку; називати будь-які числа, ціни, роки " +
        "чи відсотки; радити купувати або не купувати; називати цю річ " +
        "найкращою, надійною, бракованою чи порівнювати з конкретними іншими " +
        "моделями.\n" +
        "Дозволено: сказати, до якого класу речей це належить і для чого такі " +
        "беруть; перелічити, на що варто дивитися, обираючи річ цього класу."

/**
 * The shape the answer has to arrive in.
 *
 * Structured output rather than prose to be pulled apart later: three named
 * fields can each be checked on their own terms, and a quotation that has to sit
 * in its own field can be matched against the shop's description character for
 * character. A paragraph could not be.
 */
private fun appraisalSchema(): JSONObject = JSONObject()
    .put("type", "object")
    .put(
        "properties",
        JSONObject()
            .put(
                "kind",
                JSONObject().put("type", "string")
                    .put("description", "Що це за річ і для чого такі беруть. 1–2 речення.")
            )
            .put(
                "weigh",
                JSONObject().put("type", "array")
                    .put("description", "На що дивитися, обираючи річ цього класу.")
                    .put("items", JSONObject().put("type", "string"))
            )
            .put(
                "shopSays",
                JSONObject().put("type", "string")
                    .put(
                        "description",
                        "Дослівний уривок з опису магазину, або порожній рядок."
                    )
            )
    )
    .put("required", JSONArray().put("kind").put("weigh").put("shopSays"))

/**
 * Everything the model is shown about the thing.
 *
 * Deliberately **not** the price, the rating or the review count. Those are the
 * first half's business, and a model that never sees them cannot write a
 * paragraph that disagrees with the star rating printed a centimetre above it —
 * which is the one trust failure this screen's layout would otherwise invite.
 * Withholding them is cheaper and more reliable than asking the model not to use
 * them, which is the whole argument of this file.
 */
fun appraisalPrompt(wish: Wish): String {
    val about = wish.about
    val lines = mutableListOf("Назва в магазині: ${wish.name}")
    if (about.brand.isNotBlank()) lines += "Бренд: ${about.brand}"
    if (about.specs.isNotEmpty()) {
        lines += "Характеристики з магазину:"
        about.specs.forEach { (name, value) -> lines += "- $name: $value" }
    }
    if (about.description.isNotBlank()) {
        lines += "Опис магазину: ${about.description}"
    }
    return lines.joinToString("\n")
}

/**
 * The whole request body, built where it can be read in a test.
 *
 * Temperature at nought, because two openings of the same wish producing two
 * differently worded verdicts reads as noise rather than as thought, and the
 * answer is cached anyway. The output cap is the cost control that matters: the
 * prompt is short and the reply is what is billed at six times the rate.
 */
fun appraisalBody(wish: Wish): String = JSONObject()
    .put(
        "contents",
        JSONArray().put(
            JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", appraisalPrompt(wish))))
        )
    )
    .put(
        "systemInstruction",
        JSONObject().put("parts", JSONArray().put(JSONObject().put("text", APPRAISAL_SYSTEM)))
    )
    .put(
        "generationConfig",
        JSONObject()
            .put("temperature", 0.0)
            .put("maxOutputTokens", 600)
            .put("responseMimeType", "application/json")
            .put("responseSchema", appraisalSchema())
    )
    .toString()

// ------------------------------------------------------------ what is stored

/**
 * A verdict as it is kept on the wish, with what it was written about.
 *
 * [price] and [day] are not bookkeeping: regenerating on every open costs money
 * and makes the wording flicker, so the answer is cached — and a cached judgement
 * about a number that has since moved is exactly the quiet falsehood this whole
 * feature is written against. Keeping the price it was written for lets
 * [appraisalStale] say so instead.
 */
data class Appraisal(
    /** What kind of thing this is. The model's words. */
    val kind: String = "",
    /** What to weigh when choosing one of these. The model's words. */
    val weigh: List<String> = emptyList(),
    /** A verbatim run of the shop's own description, or empty. */
    val shopSays: String = "",
    /** Hryvnia price standing when this was written. */
    val price: Double = 0.0,
    /** Epoch day it was written. */
    val day: Long = 0L,
    /** Which model wrote it, so a later change of model is visible rather than silent. */
    val model: String = ""
) {
    val isEmpty: Boolean get() = kind.isBlank() && weigh.isEmpty()
}

/**
 * How far the price may drift before a stored verdict stops describing it.
 *
 * Five percent, which is the smallest move this app is anywhere willing to call
 * news, and well above the kopeck-level jitter a shop's own rounding produces.
 */
const val APPRAISAL_DRIFT_PERCENT = 5.0

/** Whether the price has moved far enough that the stored verdict is about another number. */
fun appraisalStale(appraisal: Appraisal?, price: Double): Boolean {
    if (appraisal == null || appraisal.isEmpty) return false
    if (appraisal.price <= 0.0 || price <= 0.0) return false
    val drift = kotlin.math.abs(price - appraisal.price) / appraisal.price * 100.0
    return drift >= APPRAISAL_DRIFT_PERCENT
}

/** Says which number the stored verdict was actually written about. */
fun appraisalStaleNote(appraisal: Appraisal): String =
    "Оцінку написано, коли ціна була ${money(appraisal.price)}. Відтоді вона змінилася."

// ------------------------------------------------------------------ the gate

/**
 * Why the section is or is not on the screen.
 *
 * Every refusal is a separate value rather than a boolean, because each of them
 * is a different thing being missing and the test for one must not accidentally
 * pass because of another.
 */
enum class AppraisalGate {
    /** There is enough. The section appears. */
    READY,

    /** This build has no Gemini key, so the only control in the section could never work. */
    NO_KEY,

    /** Nothing to judge: a wish nobody has put a price on. */
    NO_PRICE,

    /** The price standing is one the shop has stopped answering for. */
    STALE_PRICE,

    /** The page gave up no real title, so there is nothing to identify. */
    NO_NAME,

    /** The shop published too little about the thing for anything honest to be said. */
    TOO_LITTLE
}

/** A blurb shorter than this is a tagline, not a description of anything. */
const val APPRAISAL_DESCRIPTION_FLOOR = 120

/** One stray specification row is a shop's habit, not a table. */
const val APPRAISAL_SPEC_FLOOR = 3

/** A score out of five with fewer people than this behind it corroborates nothing. */
const val APPRAISAL_RATING_FLOOR = 10

/** How many of the four grounds below must hold. */
const val APPRAISAL_GROUNDS_FLOOR = 2

/**
 * How many independent things the shop actually published about this item.
 *
 * A count rather than a judgement, for the same reason Tripadvisor waits for a
 * review count and Amazon waits for several customers to agree: a threshold on
 * countable data is a decision the app can make before it spends anything, and it
 * is the only mitigation that measurably worked in any of the studies behind this
 * file.
 */
fun appraisalGrounds(about: ProductAbout): Int {
    var grounds = 0
    if (about.description.trim().length >= APPRAISAL_DESCRIPTION_FLOOR) grounds++
    if (about.specs.size >= APPRAISAL_SPEC_FLOOR) grounds++
    if (about.brand.isNotBlank()) grounds++
    if (about.rating > 0.0 && about.ratingCount >= APPRAISAL_RATING_FLOOR) grounds++
    return grounds
}

/**
 * Whether anything the shop published actually describes the thing.
 *
 * A brand and a star rating are facts *about* an item, not a description of one:
 * from "Xiaomi, 4,6 з 5" a model cannot say what kind of thing it is holding, and
 * it will not admit that — it will guess from the name. So the two corroborating
 * grounds can push a thin case over the line but may not carry it alone.
 */
fun appraisalHasSubstance(about: ProductAbout): Boolean =
    about.description.trim().length >= APPRAISAL_DESCRIPTION_FLOOR ||
        about.specs.size >= APPRAISAL_SPEC_FLOOR

/**
 * Whether the title is a real one or the stand-in the app writes for a page it
 * could not read.
 *
 * [placeholderName] produces "Товар з temu.com", which names a shop and no thing
 * at all. Handed that, a model writes about whatever temu.com sells most of.
 */
fun appraisalHasName(wish: Wish): Boolean {
    val name = wish.name.trim()
    if (name.length < 3) return false
    if (name == placeholderName(wish.url)) return false
    return name != "Товар" && name != "Нове бажання"
}

/**
 * Whether this wish gets the section at all, decided entirely from stored data.
 *
 * Nothing here touches the network, and nothing downstream may run without it
 * having returned [AppraisalGate.READY] first.
 */
fun appraisalGate(wish: Wish, hasKey: Boolean): AppraisalGate = when {
    // A section whose only control could never do anything is a broken control,
    // and a debug build has no key by design. Absent is the honest answer.
    !hasKey -> AppraisalGate.NO_KEY
    wish.price <= 0.0 -> AppraisalGate.NO_PRICE
    // A verdict about a price is a claim that the price can be paid, and the rest
    // of this screen already goes quiet when the reading is doubtful.
    isStale(wish.freshness) -> AppraisalGate.STALE_PRICE
    !appraisalHasName(wish) -> AppraisalGate.NO_NAME
    !appraisalHasSubstance(wish.about) -> AppraisalGate.TOO_LITTLE
    appraisalGrounds(wish.about) < APPRAISAL_GROUNDS_FLOOR -> AppraisalGate.TOO_LITTLE
    else -> AppraisalGate.READY
}

fun appraisalShows(wish: Wish, hasKey: Boolean): Boolean =
    appraisalGate(wish, hasKey) == AppraisalGate.READY

/** True only where the key was actually built into the APK. */
fun hasAppraisalKey(key: String): Boolean = key.isNotBlank()

// ------------------------------------------------- the half that is not AI

/** One templated fact, on the same terms as every other leader row in the app. */
data class AppraisalRow(val label: String, val value: String, val alarm: Boolean = false)

/**
 * The first half of the section, entirely from the data layer.
 *
 * [hero] is blank whenever the window cannot be measured, which is a state the
 * screen draws deliberately rather than papering over with a nought.
 */
data class AppraisalFacts(
    val hero: String,
    val standing: String,
    val rows: List<AppraisalRow>
)

/** The window says nothing until it covers a week and has actually moved. */
fun appraisalWindowSpeaks(insight: PriceInsight): Boolean =
    insight.referenceDays >= 7 &&
        insight.referenceLow > 0.0 &&
        insight.referenceHigh > insight.referenceLow

/**
 * How far above its own thirty-day floor this price is standing, as one figure.
 *
 * The single number on this screen no shopping assistant could produce: it is
 * measured against what this item cost on this phone's own record. Blank when
 * the window cannot speak.
 */
fun appraisalHero(insight: PriceInsight): String {
    if (!appraisalWindowSpeaks(insight)) return ""
    if (insight.atReferenceLow) return "0%"
    val over = (insight.current - insight.referenceLow) / insight.referenceLow * 100.0
    return "+${figure(over, 0)}%"
}

/** The sentence the hero is a figure of, or the reason there is no hero. */
fun appraisalStanding(insight: PriceInsight): String =
    if (appraisalWindowSpeaks(insight)) {
        "над найнижчою ціною за ${daysLabel(insight.referenceDays)}"
    } else {
        "Власного діапазону цін ще замало, щоб порівнювати"
    }

/**
 * Every templated fact, in the order they are read.
 *
 * Each value comes from a function the rest of the app already uses, so a figure
 * here cannot disagree with the same figure shown elsewhere on the page.
 */
fun appraisalFacts(
    wish: Wish,
    insight: PriceInsight,
    freeCash: Double,
    today: Long
): AppraisalFacts {
    val rows = mutableListOf<AppraisalRow>()
    if (appraisalWindowSpeaks(insight)) {
        rows += AppraisalRow(
            "Діапазон за ${daysLabel(insight.referenceDays)}",
            "${bareAmount(insight.referenceLow)} – ${money(insight.referenceHigh)}"
        )
    }
    if (insight.lowest > 0.0 && insight.lowest < wish.price) {
        rows += AppraisalRow("Найнижча за весь час", money(insight.lowest))
    }
    if (insight.changes > 0) {
        rows += AppraisalRow("Записано цін", changesLabel(insight.changes))
    }
    wantedDays(wish, today)?.let { rows += AppraisalRow("У списку", daysLabel(it)) }
    ratingLine(wish.about).takeIf { it.isNotBlank() }?.let {
        rows += AppraisalRow("Оцінка магазину", it)
    }
    if (freeCash > 0.0) {
        val share = wish.price / freeCash * 100.0
        rows += AppraisalRow(
            "Частка вільних коштів",
            "${figure(share, 0)}%",
            // Over the whole month's free cash is a fact worth the alarm colour:
            // it is the one row here that says the thing cannot be paid for out
            // of this month at all.
            alarm = share > 100.0
        )
    }
    return AppraisalFacts(appraisalHero(insight), appraisalStanding(insight), rows)
}

/**
 * What the shut heading says.
 *
 * From the templated half only, never from the model's text: a summary that could
 * contradict the block under it is worse than no summary, and the model's half is
 * the only part of this section that could.
 */
fun appraisalSummary(facts: AppraisalFacts): String =
    if (facts.hero.isBlank()) facts.standing else "${facts.hero} ${facts.standing}"

// ------------------------------------------- checking what the model sent back

/**
 * Turns of phrase that mean the model has started speaking for people.
 *
 * Stems rather than words, because Ukrainian inflects and "відгуки", "відгуків"
 * and "відгукам" are the same offence. The list is short and blunt on purpose: it
 * is a gate, not a grammar, and a false positive costs one retry while a false
 * negative costs the whole point of the feature.
 */
val APPRAISAL_BANNED: List<String> = listOf(
    "відгук", "покупц", "користувач", "за словами", "багато хто", "більшість",
    "рекоменд", "раджу", "радимо", "радять", "хвалят", "скаржат", "найкращ",
    "найнадійніш", "варто купити", "не варто купувати", "купуй", "оцінк", "рейтинг"
)

/** Whether a run of text claims to know what other people think. */
fun speaksForBuyers(text: String): Boolean {
    val lower = text.lowercase(UK)
    return APPRAISAL_BANNED.any { lower.contains(it) }
}

/**
 * Whether a run of text states a figure.
 *
 * Numbers are the most hallucination-prone token class there is and the most
 * visibly wrong when they break, and this half of the section has no business
 * stating one: every figure on this screen is the app's own arithmetic. Banning
 * the digit outright is cruder than checking each number, and that is the point —
 * it cannot be got round, and it forces the model to write "два роки" where it
 * would otherwise have invented "18 місяців".
 */
fun statesFigures(text: String): Boolean = text.any { it.isDigit() }

/**
 * Whether a quotation is really the shop's.
 *
 * Matched as a substring of the description after collapsing whitespace, so a
 * quotation cannot be a paraphrase wearing quotation marks. This is the one place
 * the model is allowed to produce a sentence about this specific item rather than
 * about its class, and it is allowed only because fabrication is impossible here
 * by construction rather than by instruction.
 */
fun quotedFromShop(quote: String, description: String): Boolean {
    val tidy = { text: String -> text.replace(Regex("""\s+"""), " ").trim().lowercase(UK) }
    val needle = tidy(quote)
    if (needle.length < 20) return false
    return tidy(description).contains(needle)
}

/**
 * What one call to the model came to.
 *
 * Three outcomes rather than two, for exactly the reason [Reading] has three one
 * screen over: an answer that arrived and failed the checks is a fact about the
 * model and is worth telling the person, while nothing arriving at all is a fact
 * about the phone. Collapsing them would leave a tunnel looking like a refusal.
 */
sealed interface AppraisalReading {
    data class Written(val appraisal: Appraisal) : AppraisalReading

    /** The model answered, and nothing in the answer survived the checks. */
    data object Refused : AppraisalReading

    /** Nothing came back: no key, no network, a timeout, a server that hung up. */
    data object Failed : AppraisalReading
}

/** Pulls the model's text out of whatever shape the response arrived in. */
fun appraisalText(json: String): String = runCatching {
    val parts = JSONObject(json)
        .optJSONArray("candidates")
        ?.optJSONObject(0)
        ?.optJSONObject("content")
        ?.optJSONArray("parts")
        ?: return@runCatching ""
    (0 until parts.length()).joinToString("") {
        parts.optJSONObject(it)?.optString("text").orEmpty()
    }
}.getOrDefault("")

/**
 * Reads a response and either gets a verdict out of it or refuses it whole.
 *
 * The checks run here rather than in the prompt because the research is
 * unambiguous that the prompt does not hold: "ignore irrelevant information"
 * recovered about five points of a twenty-point gap, and abstention gets worse
 * with scale and with reasoning tuning. A refusal costs one tap; a paragraph in
 * the voice of buyers who do not exist costs the feature its reason to be there.
 *
 * The quotation alone is dropped rather than refused when it does not check out:
 * it is an extra, and losing a quotation is not losing the answer.
 */
fun readAppraisal(
    json: String,
    about: ProductAbout,
    price: Double,
    day: Long
): AppraisalReading {
    val text = appraisalText(json)
    if (text.isBlank()) return AppraisalReading.Refused
    val answer = runCatching { JSONObject(text) }.getOrNull() ?: return AppraisalReading.Refused

    val kind = answer.optString("kind").trim()
    if (kind.isBlank() || kind.length > 400) return AppraisalReading.Refused
    if (speaksForBuyers(kind) || statesFigures(kind)) return AppraisalReading.Refused

    val listed = answer.optJSONArray("weigh") ?: JSONArray()
    val weigh = (0 until listed.length())
        .map { listed.optString(it).trim() }
        .filter { it.isNotBlank() }
    if (weigh.isEmpty() || weigh.size > 6) return AppraisalReading.Refused
    if (weigh.any { it.length > 160 || speaksForBuyers(it) || statesFigures(it) }) {
        return AppraisalReading.Refused
    }

    val quoted = answer.optString("shopSays").trim().trim('«', '»', '"')
    val says = if (quoted.isNotBlank() && quotedFromShop(quoted, about.description)) quoted else ""

    return AppraisalReading.Written(
        Appraisal(
            kind = kind,
            weigh = weigh,
            shopSays = says,
            price = price,
            day = day,
            model = APPRAISAL_MODEL
        )
    )
}

// ------------------------------------------------------------- the one call

/**
 * Asks the model, once, for the second half of one wish's section.
 *
 * Structured exactly like [fetchHolidays] and [parcelStatus]: a thin suspend
 * function that does nothing but the socket, with every decision in a pure
 * function beside it that a test can reach. The key travels in `x-goog-api-key`
 * rather than in the query string, so it never lands in a log line or a proxy's
 * access record.
 *
 * Returns [AppraisalReading.Failed] for every way the network can go wrong,
 * including a build with no key at all, and that state draws as a sentence rather
 * than as an error.
 */
suspend fun fetchAppraisal(
    wish: Wish,
    key: String,
    today: Long
): AppraisalReading = withContext(Dispatchers.IO) {
    if (!hasAppraisalKey(key)) return@withContext AppraisalReading.Failed
    runCatching {
        val connection = URL(APPRAISAL_ENDPOINT).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("x-goog-api-key", key)
        connection.outputStream.use { it.write(appraisalBody(wish).toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) {
            // Only the code is ever recorded: a Gemini error body can echo the
            // request back, and the request carries nothing that belongs in a log.
            Log.w("FlowPay", "appraisal refused with ${connection.responseCode}")
            return@runCatching AppraisalReading.Failed
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        readAppraisal(body, wish.about, wish.price, today)
    }.getOrDefault(AppraisalReading.Failed)
}

// ---------------------------------------------------------------- the words

/** The heading the owner asked for. */
const val APPRAISAL_TITLE = "Оцінка бажання"

/** What the app's own half is called, so the two halves are named differently. */
const val APPRAISAL_FACTS_TITLE = "Що знає застосунок"

/** What the model's half is called. */
const val APPRAISAL_MODEL_TITLE = "Думка моделі"

/**
 * How the model's half was produced, said in the block itself.
 *
 * Not a disclaimer and not small print. When a summary disagrees with the rating
 * beside it, trust in the whole screen drops, and that damage is measurably
 * softened by stating how the summary was made — so this line is part of the
 * claim, and it names the one thing this app does not have.
 */
const val APPRAISAL_SOURCE_NOTE =
    "Написала модель Gemini за назвою, характеристиками й описом з магазину. " +
        "Не за відгуками — їх у застосунку немає, і ціни модель не бачила."

/** Before anything has been asked for. */
const val APPRAISAL_IDLE_NOTE =
    "Модель ще нічого не писала про цю річ. Запит платний, тому лише на вимогу."

const val APPRAISAL_BUSY_NOTE = "Питаю модель…"

/** The answer arrived and did not survive the checks. */
const val APPRAISAL_REFUSED_NOTE =
    "Модель відповіла, але у відповіді були цифри або слова за покупців. " +
        "Такого застосунок не показує, тож тут нічого немає."

/** Nothing arrived at all. */
const val APPRAISAL_FAILED_NOTE =
    "Модель не відповіла. Перевір зв'язок і спробуй ще раз."

/** What the button says, which differs once there is already something under it. */
fun appraisalAskLabel(written: Boolean): String =
    if (written) "Оцінити ще раз" else "Оцінити"

/** The shop's own sentence, marked as the shop's wherever it is shown. */
fun shopQuoteLine(quote: String): String = "З опису магазину: «$quote»"

// ------------------------------------------------------------- the section

/** The name this section's folded state is stored under. Plain ASCII, like the rest. */
const val SECTION_APPRAISAL = "appraisal"

/**
 * The whole section, in two halves that are drawn to look like two kinds of claim.
 *
 * The first half is a [Card] like every other measured block on this page, with
 * [LeaderRow]s in it — the app's own idiom for a label and a figure, so the
 * arithmetic here looks exactly as native as the arithmetic in «Історія ціни»,
 * because it is the same kind of thing. One figure is spent at [Type.heroSize],
 * on where the price sits in its own window, which is the one number on this
 * screen no shopping assistant could produce.
 *
 * The second half is deliberately not a card. It is set in [TextSecondary] behind
 * a hairline rule down its left edge — the typographic mark for a quotation from
 * somewhere else — so that it reads as an aside rather than as a measurement
 * before a word of it has been read. That is half of the trust job: the research
 * says stating *how* a verdict was produced softens the damage when it disagrees
 * with the rating beside it, and the layout can carry as much of that as the
 * wording can.
 *
 * No second accent, no gradient and no lime: the wish page already spends its one
 * accent, and a machine-written paragraph is the last thing that should be
 * wearing the colour this app uses to mean "this is the thing".
 */
@Composable
fun AppraisalSection(
    wish: Wish,
    insight: PriceInsight,
    freeCash: Double,
    today: Long,
    key: String,
    open: Boolean,
    onToggle: (Boolean) -> Unit,
    onChange: (Wish) -> Unit
) {
    val facts = appraisalFacts(wish, insight, freeCash, today)
    val scope = rememberCoroutineScope()
    var busy by remember(wish.id) { mutableStateOf(false) }
    // What the last attempt came to, when it came to nothing worth keeping. Only
    // ever Refused or Failed: a written one goes straight onto the wish.
    var trouble by remember(wish.id) { mutableStateOf<AppraisalReading?>(null) }

    CollapsibleSection(
        title = APPRAISAL_TITLE,
        // From the templated half only. A shut heading quoting the model would be
        // the one claim on this screen that could turn out to be invented.
        summary = appraisalSummary(facts),
        open = open,
        onToggle = onToggle,
        icon = Icons.Default.AutoAwesome
    ) {
        Column(Modifier.padding(horizontal = Space.screen)) {
            Card(
                Modifier.fillMaxWidth().litEdge(Radius.md),
                colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
                shape = Radius.md
            ) {
                Column(Modifier.padding(Space.lg)) {
                    Text(
                        APPRAISAL_FACTS_TITLE,
                        color = TextSecondary,
                        fontSize = Type.overlineSize,
                        fontWeight = Type.strong,
                        letterSpacing = Type.overlineTracking
                    )
                    Spacer(Modifier.height(Space.sm))
                    if (facts.hero.isNotBlank()) {
                        Text(
                            facts.hero,
                            color = TextPrimary,
                            fontSize = Type.heroSize,
                            lineHeight = Type.heroLine,
                            letterSpacing = Type.heroTracking,
                            fontWeight = Type.strong,
                            style = Tabular
                        )
                    }
                    Text(
                        facts.standing,
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                    if (facts.rows.isNotEmpty()) {
                        Spacer(Modifier.height(Space.md))
                        facts.rows.forEach { LeaderRow(it.label, it.value, alarm = it.alarm) }
                    }
                }
            }

            Spacer(Modifier.height(Space.lg))

            // The aside. Height measured from its own content so the rule runs the
            // full length of the block and stops with it.
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Box(Modifier.width(1.dp).fillMaxHeight().background(HairLine))
                Spacer(Modifier.width(Space.lg))
                Column(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            null,
                            Modifier.height(14.dp).width(14.dp),
                            tint = TextSecondary
                        )
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            APPRAISAL_MODEL_TITLE,
                            color = TextSecondary,
                            fontSize = Type.overlineSize,
                            fontWeight = Type.strong,
                            letterSpacing = Type.overlineTracking
                        )
                    }
                    Spacer(Modifier.height(Space.sm))

                    val written = wish.appraisal
                    if (written != null) {
                        if (appraisalStale(written, wish.price)) {
                            Text(
                                appraisalStaleNote(written),
                                color = TextPrimary,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(bottom = Space.sm)
                            )
                        }
                        Text(
                            written.kind,
                            color = TextSecondary,
                            fontSize = Type.bodySize,
                            lineHeight = Type.bodyLine
                        )
                        written.weigh.forEach { point ->
                            Text(
                                "— $point",
                                color = TextSecondary,
                                fontSize = Type.bodySize,
                                lineHeight = Type.bodyLine,
                                modifier = Modifier.padding(top = Space.xs)
                            )
                        }
                        if (written.shopSays.isNotBlank()) {
                            Text(
                                shopQuoteLine(written.shopSays),
                                color = TextDisabled,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(top = Space.sm)
                            )
                        }
                        Text(
                            APPRAISAL_SOURCE_NOTE,
                            color = TextDisabled,
                            fontSize = Type.captionSize,
                            lineHeight = Type.captionLine,
                            modifier = Modifier.padding(top = Space.md)
                        )
                    }

                    // Each of these is a state he will meet more often than the
                    // happy path, so each is a sentence rather than an empty space
                    // where a paragraph should be.
                    val note = when {
                        busy -> APPRAISAL_BUSY_NOTE
                        trouble is AppraisalReading.Refused -> APPRAISAL_REFUSED_NOTE
                        trouble is AppraisalReading.Failed -> APPRAISAL_FAILED_NOTE
                        written == null -> APPRAISAL_IDLE_NOTE
                        else -> ""
                    }
                    if (note.isNotBlank()) {
                        Row(
                            Modifier.padding(top = if (written == null) 0.dp else Space.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (busy) {
                                // Not a bare spinner: under reduced motion this is
                                // a lime dot rather than an arc frozen mid-turn.
                                BusyMark(tint = TextSecondary)
                                Spacer(Modifier.width(Space.sm))
                            }
                            Text(
                                note,
                                color = TextSecondary,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine
                            )
                        }
                    }

                    if (!busy) {
                        OutlinedButton(
                            {
                                trouble = null
                                busy = true
                                scope.launch {
                                    val reading = fetchAppraisal(wish, key, today)
                                    busy = false
                                    when (reading) {
                                        is AppraisalReading.Written ->
                                            onChange(wish.copy(appraisal = reading.appraisal))
                                        // Nothing is cached for either of these, so
                                        // the button underneath is a real retry
                                        // rather than a second look at the same
                                        // stored disappointment.
                                        else -> trouble = reading
                                    }
                                }
                            },
                            Modifier.fillMaxWidth().padding(top = Space.md),
                            shape = Radius.sm,
                            border = BorderStroke(1.dp, HairLine),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                        ) { Text(appraisalAskLabel(written != null)) }
                    }
                }
            }
        }
    }
}

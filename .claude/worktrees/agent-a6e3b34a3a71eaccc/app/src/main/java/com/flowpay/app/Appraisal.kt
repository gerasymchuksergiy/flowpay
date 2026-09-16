package com.flowpay.app

import android.content.Intent
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * «Оцінка бажання»: what the app itself can prove about a price, and — separately,
 * and labelled — a short review of the thing, written after searching for it.
 *
 * **What this section used to be, and why it changed.** The first version was
 * built for a model generating from memory with a thin shop page in front of it,
 * and for that input it was right to be as defensive as it was. The evidence is
 * unpleasant: CHOICE asked models to recommend products and checked the answers in
 * its own laboratory — a recommended vacuum scored a quarter on pet hair, a
 * praised air fryer came third from last of thirty-seven, two recommended baby
 * sleep products had failed safety testing — and the models did not hedge. RAGTruth
 * put response-level hallucination at 29% for question answering and around 30% for
 * news summarisation but **68.6% for data-to-text**, which is exactly a shop's
 * attribute table turned into prose. And wording is a weak lever: Joren et al. found
 * abstention *falls* when context is supplied, "ignore irrelevant information"
 * recovered about five points of a twenty-point gap, and reasoning tuning makes
 * abstention worse rather than better.
 *
 * So the first version banned the digit outright, banned every sentence about what
 * anyone thought, and allowed exactly one sentence about the specific item — a
 * verbatim run of the shop's own description. It was honest. It was also useless:
 * asked about a monitor, it produced
 * «Модель відповіла, але у відповіді були цифри…», because any real word about a
 * monitor contains 27, 144 and 1.
 *
 * **Grounding changes the premise, not the principle.** `googleSearch` makes the
 * model retrieve pages before answering and the API returns metadata naming what it
 * retrieved, so a claim can be traced to a page rather than to the model's memory.
 * That makes a figure a reported figure instead of an invented one. It does not
 * make *our* figures safe, and it does not make an ungrounded answer any better
 * than it ever was. Hence the shape here:
 *
 *  - [appraisalGate] still decides, in arithmetic and before any network call,
 *    whether there is enough to ask about at all. Unchanged.
 *  - [appraisalFacts] is still the first half and is still not AI: the app's own
 *    arithmetic about its own thirty-day window, from templates, true by
 *    construction. Unchanged.
 *  - The model writes the second half, is still told neither the price nor the star
 *    rating, and [readAppraisal] still throws away an answer that names either of
 *    them — that is the structural reason the prose cannot disagree with the figures
 *    printed a centimetre above it, and it is the one rule that did not need
 *    rethinking.
 *  - What it retrieved is stored beside the answer and shown ([Appraisal.sources]),
 *    because a review you can check is a different object from a verdict you cannot.
 *  - An answer that came back with no sources is **drawn as the weaker thing it
 *    is**, and the old ban on speaking for other people is reapplied to it, since
 *    with nothing retrieved that is once again pure invention.
 */

// --------------------------------------------------------------- the model

/**
 * The model the second half is written by, and the address it is written at.
 *
 * `gemini-3.1-flash-lite`, re-checked against Google's own pages rather than
 * remembered, because the whole point of this change is that it turns on a tool the
 * model has to actually support:
 *
 *  - It is on the support table for Grounding with Google Search on the
 *    `generateContent` endpoint, so no model change was needed for this.
 *  - It is the cheapest way to run this feature in practice. Token prices are
 *    $0.25 per million in and $1.50 per million out, which is dearer per token than
 *    `gemini-2.5-flash-lite` — but grounding dominates the bill by about two orders
 *    of magnitude, and the 3.x grounding rate is $14 per 1,000 search queries
 *    against 2.5's $35 per 1,000 grounded prompts. One appraisal of roughly a
 *    thousand tokens in and five hundred out costs about $0.015 here against about
 *    $0.035 on 2.5-flash-lite. 2.5 only wins once the model fires three or more
 *    searches for a single prompt.
 *  - The first 5,000 search queries a month are free across all 3.x models, which
 *    for one person's wishlist is the entire bill.
 *
 * One caution that belongs next to the constant rather than in a report: on 3.x,
 * **billing is per search query, not per request**, and how many queries one prompt
 * fires is the model's decision. `webSearchQueries` in the response is the meter.
 *
 * Written as a constant that travels *into the stored answer*, so that the day this
 * changes, an answer written by the old one still says which model wrote it.
 */
const val APPRAISAL_MODEL = "gemini-3.1-flash-lite"

const val APPRAISAL_ENDPOINT =
    "https://generativelanguage.googleapis.com/v1beta/models/$APPRAISAL_MODEL:generateContent"

/**
 * What the model is told it is doing.
 *
 * Five things are being asked for here and each is in the instruction for a reason
 * the previous version paid for:
 *
 *  1. **Search first, judge after.** A grounded model is still perfectly capable of
 *     answering from memory and never calling the tool; the whole value of the
 *     change is lost silently if it does. The code notices — an answer with no
 *     [Appraisal.sources] is drawn as the weaker thing — but the instruction is
 *     what makes that the rare case rather than the usual one.
 *  2. **Say so when the thing cannot be found.** This is the failure mode the old
 *     version was built entirely around, and it has not gone away: a model handed a
 *     model number it cannot find will describe the class of thing and let the
 *     reader believe it is describing the item. Being allowed to answer "I did not
 *     find this" is what makes the rest of the answer worth anything.
 *  3. **Weaknesses, concretely.** The half people skip and the half worth money.
 *     "Є певні компроміси" is a shape, not a fault, and the instruction names that
 *     failure explicitly rather than asking for "detail", which every model already
 *     believes it is providing.
 *  4. **One named person's decision, not marketing.** The owner asked for what a
 *     knowledgeable friend writes. A model told to review a product writes a
 *     product page unless told otherwise.
 *  5. **Never the price, never the rating.** Both are printed above this block and
 *     neither was sent. The instruction and [readAppraisal] say the same thing
 *     twice, and the check is the half that holds.
 *
 * Kept free of location and time claims on purpose: Google documents that a system
 * instruction carrying conflicting location or time information can quietly degrade
 * the built-in tools.
 */
const val APPRAISAL_SYSTEM: String =
    "Ти пишеш коротку рецензію на одну річ для однієї людини, яка зараз вирішує, " +
        "брати її чи ні. Не для магазину, не для реклами, не для пошуковика.\n\n" +
        "Спочатку шукай, потім суди. Скористайся пошуком Google і спирайся на те, " +
        "що справді знайшов: огляди, тести, порівняння, обговорення. Не пиши з " +
        "пам'яті й не переказуй опис продавця.\n\n" +
        "Якщо надійних відомостей саме про цю модель знайти не вдалося — скажи це " +
        "прямо і не заповнюй порожнечу. Один чесний рядок кращий за сторінку " +
        "правдоподібного тексту. Не видавай загальні слова про клас речей за " +
        "відомості про цю річ.\n\n" +
        "Недоліки пиши конкретно. «Є певні компроміси» чи «підходить не всім» — це " +
        "не недолік. Недолік — це що саме працює гірше, ніж очікуєш, і де це " +
        "помітно. Це половина, яку зазвичай пропускають, і саме вона тут потрібна. " +
        "Якщо в оглядах серйозних недоліків не знайшлося, так і напиши — не вигадуй " +
        "їх для симетрії.\n\n" +
        "Пиши українською, як знайомий, який розібрався в темі: спокійно, по суті, " +
        "без захвату й без слів із реклами.\n\n" +
        "Ніколи не називай ціну, не згадуй гроші й не кажи, дорого це чи дешево: " +
        "ціну людина вже бачить на екрані, ти її не знаєш, і твоя цифра з нею не " +
        "збігатиметься. Так само не згадуй зірковий рейтинг, бали й кількість " +
        "відгуків у магазині — їх ти теж не бачиш.\n\n" +
        "Коротко: це кілька хвилин читання, а не стаття."

/** Every heading the answer may be built from, and what it fills in. */
private enum class AppraisalHead { UNKNOWN, KIND, GOOD, WEAK, SUITS, SKIP, CHECK }

/**
 * The headings the answer is asked for and read back by.
 *
 * Plain labelled text rather than structured output, and not by preference.
 * Combining a response schema with the search tool is a Gemini 3 **preview**
 * feature limited to `gemini-3.1-pro-preview` and `gemini-3.8-flash`; on
 * [APPRAISAL_MODEL] the two cannot be sent together. So the shape is carried by
 * headings the model writes and [draftAppraisal] splits on, which costs a parser
 * and buys the search tool.
 *
 * Longest first, so that a prefix can never shadow a longer heading that starts
 * with the same word.
 */
private val APPRAISAL_HEADS: List<Pair<String, AppraisalHead>> = listOf(
    "НІЧОГО НЕ ЗНАЙШОВ" to AppraisalHead.UNKNOWN,
    "СИЛЬНІ СТОРОНИ" to AppraisalHead.GOOD,
    "СЛАБКІ МІСЦЯ" to AppraisalHead.WEAK,
    "КОМУ НЕ ВАРТО" to AppraisalHead.SKIP,
    "ПЕРЕД ПОКУПКОЮ" to AppraisalHead.CHECK,
    "КОМУ ПІДІЙДЕ" to AppraisalHead.SUITS,
    "ЩО ЦЕ" to AppraisalHead.KIND
)

/**
 * Everything the model is shown about the thing, and the shape the answer must take.
 *
 * Still deliberately **not** the price, the star rating or the review count. That
 * was the strongest part of the old design and grounding does not weaken it: a
 * model that never saw the figure printed above the block cannot write a sentence
 * that disagrees with it. Everything else about this file changed; this did not.
 *
 * The instruction to work out what the thing actually is before searching for it
 * earns its line: shop titles are keyword soup — «Монітор 27" ігровий 165Гц IPS
 * Dell S2721DGF (210-AXKW) чорний» — and a model that searches the whole string
 * finds the listing it came from rather than a review of the monitor.
 */
fun appraisalPrompt(wish: Wish): String {
    val about = wish.about
    val card = mutableListOf("Назва в магазині: ${wish.name}")
    if (about.brand.isNotBlank()) card += "Бренд: ${about.brand}"
    if (about.specs.isNotEmpty()) {
        card += "Характеристики з магазину:"
        about.specs.forEach { (name, value) -> card += "- $name: $value" }
    }
    if (about.description.isNotBlank()) {
        card += "Опис магазину: ${about.description}"
    }
    return buildString {
        append("Ось річ із чужого вішліста. Усе, що про неї відомо, — картка магазину:\n\n")
        append(card.joinToString("\n"))
        append("\n\n")
        append(
            "Спершу розберися, що це насправді за модель: назва в магазині буває " +
                "неточною і повною зайвих слів. Потім знайди про неї огляди, тести " +
                "й порівняння з близькими моделями — і пиши вже за ними.\n\n"
        )
        append("Відповідь дай рівно в такому вигляді, без інших заголовків:\n\n")
        append("ЩО ЦЕ: одне речення — що це за річ і до якого класу належить.\n")
        append("СИЛЬНІ СТОРОНИ:\n- у чому вона справді хороша\n- ще один пункт\n")
        append("СЛАБКІ МІСЦЯ:\n- конкретно, у чому вона програє й де це помітно\n- ще один пункт\n")
        append("КОМУ ПІДІЙДЕ: одне речення.\n")
        append("КОМУ НЕ ВАРТО: одне речення — кому краще взяти інше і що саме.\n")
        append("ПЕРЕД ПОКУПКОЮ:\n- що перевірити або з чим порівняти\n\n")
        append(
            "Якщо надійних відомостей саме про цю модель знайти не вдалося, напиши " +
                "єдиний рядок «НІЧОГО НЕ ЗНАЙШОВ: » і далі одне речення про те, що " +
                "саме не вдалося знайти. Більше нічого не пиши."
        )
    }
}

/**
 * The whole request body, built where a test can read it.
 *
 * `googleSearch` is the current spelling of the tool; `googleSearchRetrieval` with
 * its `dynamicRetrievalConfig` is the Gemini 1.5-era one and is wrong here.
 *
 * **Nothing about structured output may appear in this body.** A response schema
 * and the search tool cannot travel together on this model, and sending both is not
 * a warning but a rejected request — so the absence of `responseMimeType` and
 * `responseSchema` is load-bearing and is pinned by a test.
 *
 * Temperature low but not nought. The old answer was three short JSON fields, where
 * greedy decoding is free; this one is a page of prose, and nought is where a model
 * repeats itself. The output cap is generous rather than tight because on this model
 * a cap that runs out mid-answer returns nothing usable at all, and an answer thrown
 * away is a search query already paid for.
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
    .put("tools", JSONArray().put(JSONObject().put("googleSearch", JSONObject())))
    .put(
        "generationConfig",
        JSONObject()
            .put("temperature", 0.2)
            .put("maxOutputTokens", 2000)
    )
    .toString()

// ------------------------------------------------------------ what is stored

/**
 * One page the model actually retrieved, as the API hands it over.
 *
 * [title] rather than a host parsed out of [url], because `groundingChunks[].web`
 * carries only those two fields and the uri is a `vertexaisearch.cloud.google.com`
 * redirect — parsing a domain out of it yields Google's, not the publisher's. The
 * title is the field that says who wrote the page.
 */
data class AppraisalSource(val title: String, val url: String)

/**
 * A review as it is kept on the wish, with what it was written about.
 *
 * [price] and [day] are not bookkeeping: regenerating on every open costs a search
 * query and makes the wording flicker, so the answer is cached — and a cached
 * judgement about a number that has since moved is exactly the quiet falsehood this
 * whole feature is written against. Keeping the price it was written for lets
 * [appraisalStale] say so instead.
 *
 * [found] is false when the model reported it could not find this exact thing. That
 * is stored rather than thrown away on purpose: it is an answer, it cost a search,
 * and showing it is the honest outcome — the alternative is a section that looks
 * like it failed when in fact it worked.
 */
data class Appraisal(
    /** What kind of thing this is, in one line. */
    val kind: String = "",
    /** What it is good at. */
    val good: List<String> = emptyList(),
    /** Where it is weak, specifically. */
    val weak: List<String> = emptyList(),
    /** Who it suits. */
    val suits: String = "",
    /** Who should buy something else, and what. */
    val skip: String = "",
    /** What to check or compare before committing. */
    val check: List<String> = emptyList(),
    /** The pages the model retrieved. Empty means it answered without searching. */
    val sources: List<AppraisalSource> = emptyList(),
    /** What it put into the search box, kept because it is also the cost meter. */
    val queries: List<String> = emptyList(),
    /** False when the model said it could not find this exact thing. */
    val found: Boolean = true,
    /** Hryvnia price standing when this was written. */
    val price: Double = 0.0,
    /** Epoch day it was written. */
    val day: Long = 0L,
    /** Which model wrote it, so a later change of model is visible rather than silent. */
    val model: String = ""
) {
    val isEmpty: Boolean get() = kind.isBlank() && good.isEmpty() && weak.isEmpty()

    /**
     * Whether this came out of search rather than out of the model's memory.
     *
     * Read from the sources rather than stored as its own flag, so the two can never
     * disagree: the block claims to be researched exactly when it can show what it
     * researched.
     */
    val grounded: Boolean get() = sources.isNotEmpty()
}

/**
 * How far the price may drift before a stored review stops describing it.
 *
 * Five percent, which is the smallest move this app is anywhere willing to call
 * news, and well above the kopeck-level jitter a shop's own rounding produces.
 */
const val APPRAISAL_DRIFT_PERCENT = 5.0

/** Whether the price has moved far enough that the stored review is about another number. */
fun appraisalStale(appraisal: Appraisal?, price: Double): Boolean {
    if (appraisal == null || appraisal.isEmpty) return false
    if (appraisal.price <= 0.0 || price <= 0.0) return false
    val drift = kotlin.math.abs(price - appraisal.price) / appraisal.price * 100.0
    return drift >= APPRAISAL_DRIFT_PERCENT
}

/** Says which number the stored review was actually written about. */
fun appraisalStaleNote(appraisal: Appraisal): String =
    "Оцінку написано, коли ціна була ${money(appraisal.price)}. Відтоді вона змінилася."

// ------------------------------------------------------------------ the gate

/**
 * Why the section is or is not on the screen.
 *
 * Every refusal is a separate value rather than a boolean, because each of them is
 * a different thing being missing and the test for one must not accidentally pass
 * because of another.
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
 * Kept exactly as it was, and it is worth saying why grounding does not retire it.
 * The gate is not a guess about whether the model will hallucinate; it is a
 * threshold on countable data that decides before the app spends anything. Search
 * makes a *good* answer better. It does nothing for a listing so thin that there is
 * no way to tell which thing to search for, and that is what this counts.
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
 * from "Xiaomi, 4,6 з 5" neither a model nor a search box can say what thing is
 * being held. So the two corroborating grounds can push a thin case over the line
 * but may not carry it alone.
 */
fun appraisalHasSubstance(about: ProductAbout): Boolean =
    about.description.trim().length >= APPRAISAL_DESCRIPTION_FLOOR ||
        about.specs.size >= APPRAISAL_SPEC_FLOOR

/**
 * Whether the title is a real one or the stand-in the app writes for a page it
 * could not read.
 *
 * [placeholderName] produces "Товар з temu.com", which names a shop and no thing at
 * all. Handed that, a model searches for temu.com.
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

// ------------------------------------------- the half that is not AI

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
 * measured against what this item cost on this phone's own record. Blank when the
 * window cannot speak.
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
        rows += AppraisalRow("В історії", changesLabel(insight.changes))
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
 * Turns of phrase that claim to know what other people think.
 *
 * **This list did not change; where it is applied did.** With grounding on, a
 * sentence like «в оглядах скаржаться на підставку» is a report of a page the model
 * actually read, and refusing it would throw away precisely what the search was
 * paid for. With nothing retrieved, the same sentence is about reviewers nobody
 * read — which is where this list came from. So [readAppraisal] applies it only to
 * an answer that came back with no sources.
 *
 * Stems rather than words, because Ukrainian inflects and "відгуки", "відгуків" and
 * "відгукам" are the same offence.
 */
val APPRAISAL_HEARSAY: List<String> = listOf(
    "відгук", "покупц", "користувач", "за словами", "багато хто", "більшість",
    "хвалят", "скаржат", "оглядач", "рецензент", "тестуванн", "огляди кажуть"
)

/** Whether a run of text claims to know what other people found, having read nobody. */
fun speaksForBuyers(text: String): Boolean {
    val lower = text.lowercase(UK)
    return APPRAISAL_HEARSAY.any { lower.contains(it) }
}

/**
 * Words that name money, in every form this is likely to meet.
 *
 * Explicit forms rather than the stem "цін", because "цінний" and "цінність" are
 * ordinary words in a review and a stem would refuse an honest answer over them.
 * The comparatives are here because "дешевший за конкурентів" is a claim about a
 * price, and the model does not know the price.
 */
val APPRAISAL_PRICE_WORDS: List<String> = listOf(
    "₴", "грн", "гривн", "гривен", "$", "€", "usd", "uah", "долар", "євро",
    "ціна", "ціни", "ціну", "ціні", "ціною", "цінах", "цінник", "цінов",
    "кошту", "вартіст", "дешевш", "дорожч", "переплат"
)

/**
 * Whether a run of text names or judges a price.
 *
 * **The digit ban this replaces had to go.** A review of a monitor says 27", 144 Гц
 * and 1 мс, and the old rule refused every one of them — which is literally what
 * the owner met. Under grounding those figures come off a page the model retrieved
 * and are the most useful sentences in the answer.
 *
 * What survives is the narrow rule that was actually doing the work. The hryvnia
 * price is printed a centimetre above this block, it is the app's own arithmetic,
 * and the model was never shown it. Anything it says about money is therefore a
 * figure invented next to a true one, which is the single worst thing this screen
 * can print — so the answer is thrown away whole.
 */
fun statesPrice(text: String): Boolean {
    val lower = text.lowercase(UK)
    return APPRAISAL_PRICE_WORDS.any { lower.contains(it) }
}

/** Words that name the shop's star rating, which the model was not shown either. */
val APPRAISAL_RATING_WORDS: List<String> = listOf("рейтинг", "оцінк", "зірок", "зірк", "балів")

/**
 * A score written as a figure out of five: "4,6 з 5", "4.6/5".
 *
 * Shaped rather than a bare search for "з 5", which would refuse «корпус з 5
 * портами» — a legitimate sentence about a legitimate thing. The decimal point is
 * what makes it a rating.
 */
private val APPRAISAL_RATING_SHAPE =
    Regex("""\d+[.,]\d+\s*(?:з|із)\s*5(?!\d)|\d+\s*/\s*5(?!\d)""")

/**
 * Whether a run of text states the star rating.
 *
 * Same argument as [statesPrice] and the same consequence: «4,6 з 5» in this block
 * beside «4,3 з 5» in the block above is the one measured way to lose trust in the
 * whole screen, and the model cannot get it right because it was not told.
 */
fun statesRating(text: String): Boolean {
    val lower = text.lowercase(UK)
    if (APPRAISAL_RATING_WORDS.any { lower.contains(it) }) return true
    return APPRAISAL_RATING_SHAPE.containsMatchIn(lower)
}

/** Both of our own figures at once, which is how every check below asks. */
fun contradictsOurFigures(text: String): Boolean = statesPrice(text) || statesRating(text)

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

/** How many sources the block will show before it stops being a list and starts being a wall. */
const val APPRAISAL_SOURCE_CAP = 8

/**
 * The pages the model actually retrieved, or nothing at all.
 *
 * `groundingMetadata` is **absent** rather than empty when the model did not ground
 * its answer, and it can also arrive with search queries recorded but no chunks
 * attached — searches that happened, were billed, and produced nothing attributable.
 * Both read as an empty list here, which is what the screen needs: the question the
 * UI asks is not "did it search" but "can it show me what it read".
 *
 * Deduplicated on the address, because one page cited for three separate sentences
 * arrives as three chunks and a list repeating the same site three times looks like
 * three corroborating sources.
 */
fun appraisalSources(json: String): List<AppraisalSource> = runCatching {
    val chunks = JSONObject(json)
        .optJSONArray("candidates")
        ?.optJSONObject(0)
        ?.optJSONObject("groundingMetadata")
        ?.optJSONArray("groundingChunks")
        ?: return@runCatching emptyList()
    (0 until chunks.length())
        .mapNotNull { chunks.optJSONObject(it)?.optJSONObject("web") }
        .map { AppraisalSource(it.optString("title").trim(), it.optString("uri").trim()) }
        .filter { it.url.isNotBlank() }
        .distinctBy { it.url }
        .take(APPRAISAL_SOURCE_CAP)
}.getOrDefault(emptyList())

/**
 * What the model put into the search box.
 *
 * Shown because it answers "did it look for the right thing", which is the one
 * question a person can check faster than the model can — and kept because on this
 * model the bill is one charge per query in this array, so it is also the meter.
 */
fun appraisalQueries(json: String): List<String> = runCatching {
    val queries = JSONObject(json)
        .optJSONArray("candidates")
        ?.optJSONObject(0)
        ?.optJSONObject("groundingMetadata")
        ?.optJSONArray("webSearchQueries")
        ?: return@runCatching emptyList()
    (0 until queries.length())
        .map { queries.optString(it).trim() }
        .filter { it.isNotBlank() }
        .distinct()
}.getOrDefault(emptyList())

/** Strips the markdown a model reaches for whether or not it was asked to. */
fun tidyAppraisalLine(line: String): String = line
    .replace("**", "")
    .replace("__", "")
    .trim()
    .trimStart('#', '-', '—', '–', '*', '•', '·', ' ')
    .trim()

/** The answer pulled apart along its headings, before any of it has been judged. */
data class AppraisalDraft(
    val unknown: String = "",
    val kind: String = "",
    val good: List<String> = emptyList(),
    val weak: List<String> = emptyList(),
    val suits: String = "",
    val skip: String = "",
    val check: List<String> = emptyList()
)

/**
 * Splits the answer on the headings it was asked for.
 *
 * Deliberately forgiving about everything except the headings themselves: bullets
 * may be dashes or asterisks or nothing, a heading may arrive bold, a one-line
 * section may be written on the line after its heading rather than beside it. None
 * of that is worth a refusal, because a refusal costs a search query that has
 * already been paid for. What is not forgiven is a missing heading, which is the
 * only signal that the model wrote something other than what was asked.
 */
fun draftAppraisal(text: String): AppraisalDraft {
    val buckets = mutableMapOf<AppraisalHead, MutableList<String>>()
    var head: AppraisalHead? = null
    text.lines().forEach { raw ->
        val line = tidyAppraisalLine(raw)
        if (line.isBlank()) return@forEach
        val match = APPRAISAL_HEADS.firstOrNull { (label, _) ->
            line.startsWith(label, ignoreCase = true)
        }
        val body = if (match != null) {
            head = match.second
            line.drop(match.first.length).trimStart(':', '—', '-', ' ').trim()
        } else {
            line
        }
        if (body.isNotBlank()) {
            buckets.getOrPut(head ?: return@forEach) { mutableListOf() } += body
        }
    }
    val one = { which: AppraisalHead -> buckets[which]?.joinToString(" ").orEmpty().trim() }
    val many = { which: AppraisalHead -> buckets[which].orEmpty().toList() }
    return AppraisalDraft(
        unknown = one(AppraisalHead.UNKNOWN),
        kind = one(AppraisalHead.KIND),
        good = many(AppraisalHead.GOOD),
        weak = many(AppraisalHead.WEAK),
        suits = one(AppraisalHead.SUITS),
        skip = one(AppraisalHead.SKIP),
        check = many(AppraisalHead.CHECK)
    )
}

/** One line of the answer may be this long before it stops being a line. */
const val APPRAISAL_LINE_CAP = 400

/** A list longer than this is an essay, which is the one thing that was not asked for. */
const val APPRAISAL_LIST_CAP = 6

/**
 * Reads a response and either gets a review out of it or refuses it whole.
 *
 * The checks run here rather than in the prompt for the reason the whole file is
 * built on: "ignore irrelevant information" recovered about five points of a
 * twenty-point gap, and abstention gets worse with scale and with reasoning tuning.
 * What changed is which checks are worth running.
 *
 *  - **Our own figures, always.** A price or a star rating anywhere in the answer
 *    refuses it whole, grounded or not. The model was never shown either, so any
 *    figure it offers is invented beside a true one.
 *  - **Speaking for other people, only when ungrounded.** With sources, that is a
 *    report; without them it is the invention the old version existed to stop.
 *  - **No digit ban.** 27", 144 Гц and 1 мс are the answer.
 *
 * An answer whose only content is the model saying it could not find this exact
 * thing is kept rather than refused. It is the outcome the instruction asks for,
 * it cost a search query, and a section that hides it looks broken when it worked.
 */
fun readAppraisal(json: String, price: Double, day: Long): AppraisalReading {
    val text = appraisalText(json)
    if (text.isBlank()) return AppraisalReading.Refused
    val sources = appraisalSources(json)
    val queries = appraisalQueries(json)
    val draft = draftAppraisal(text)

    val keep = { one: Appraisal -> AppraisalReading.Written(one) }
    val base = Appraisal(
        sources = sources,
        queries = queries,
        price = price,
        day = day,
        model = APPRAISAL_MODEL
    )

    // The model taking the way out it was offered. Nothing else in the answer is
    // read: it was told to write this line and nothing else, and a "not found"
    // followed by a review is a review of something it did not find.
    if (draft.unknown.isNotBlank()) {
        val said = draft.unknown.take(APPRAISAL_LINE_CAP)
        if (contradictsOurFigures(said)) return AppraisalReading.Refused
        return keep(base.copy(kind = said, found = false))
    }

    if (draft.kind.isBlank() || draft.kind.length > APPRAISAL_LINE_CAP) {
        return AppraisalReading.Refused
    }
    // Weaknesses are the half worth paying for, so an answer with none of them is
    // not a shorter answer, it is the marketing copy this was written against.
    if (draft.weak.isEmpty() || draft.good.isEmpty()) return AppraisalReading.Refused

    val lists = listOf(draft.good, draft.weak, draft.check)
    if (lists.any { it.size > APPRAISAL_LIST_CAP }) return AppraisalReading.Refused

    val everything = listOf(draft.kind, draft.suits, draft.skip) + lists.flatten()
    if (everything.any { it.length > APPRAISAL_LINE_CAP }) return AppraisalReading.Refused
    if (everything.any { contradictsOurFigures(it) }) return AppraisalReading.Refused
    // Nothing was retrieved, so every sentence is once again the model's memory and
    // the old rule is back on.
    if (sources.isEmpty() && everything.any { speaksForBuyers(it) }) {
        return AppraisalReading.Refused
    }

    return keep(
        base.copy(
            kind = draft.kind,
            good = draft.good,
            weak = draft.weak,
            suits = draft.suits,
            skip = draft.skip,
            check = draft.check
        )
    )
}

// ------------------------------------------------------------- the one call

/**
 * Asks the model, once, for the second half of one wish's section.
 *
 * Structured exactly like [fetchHolidays] and [parcelStatus]: a thin suspend
 * function that does nothing but the socket, with every decision in a pure function
 * beside it that a test can reach. The key travels in `x-goog-api-key` rather than
 * in the query string, so it never lands in a log line or a proxy's access record.
 *
 * The read timeout is longer than it was. A grounded turn runs one or more searches
 * before the first token, and thirty seconds was sized for a model answering
 * straight out of memory.
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
        connection.readTimeout = 60_000
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
        readAppraisal(body, wish.price, today)
    }.getOrDefault(AppraisalReading.Failed)
}

// ---------------------------------------------------------------- the words

/** The heading the owner asked for. */
const val APPRAISAL_TITLE = "Оцінка бажання"

/** What the app's own half is called, so the two halves are named differently. */
const val APPRAISAL_FACTS_TITLE = "Що знає застосунок"

/** What the model's half is called. */
const val APPRAISAL_MODEL_TITLE = "Рецензія"

const val APPRAISAL_GOOD_TITLE = "Сильні сторони"
const val APPRAISAL_WEAK_TITLE = "Слабкі місця"
const val APPRAISAL_SUITS_TITLE = "Кому підійде"
const val APPRAISAL_SKIP_TITLE = "Кому краще інше"
const val APPRAISAL_CHECK_TITLE = "Перевірити перед покупкою"
const val APPRAISAL_SOURCES_TITLE = "Джерела"

/** Ukrainian needs three forms, so "1 джерел" has to be impossible. */
fun sourcesLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "джерел"
        last == 1 -> "джерело"
        last in 2..4 -> "джерела"
        else -> "джерел"
    }
    return "$count $word"
}

/** What the sources fold says while it is shut. */
fun appraisalSourcesSummary(appraisal: Appraisal): String {
    val sources = sourcesLabel(appraisal.sources.size)
    val queries = appraisal.queries.size
    return if (queries > 0) "$sources · ${searchesLabel(queries)}" else sources
}

/** Ukrainian plural for how many times the model went to the search box. */
fun searchesLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "запитів"
        last == 1 -> "запит"
        last in 2..4 -> "запити"
        else -> "запитів"
    }
    return "$count $word"
}

/** How the publisher of a source is named, which is all the API gives. */
fun appraisalSourceLabel(source: AppraisalSource): String =
    source.title.ifBlank { "Джерело" }

/**
 * How a grounded review was produced, said in the block itself.
 *
 * Not a disclaimer and not small print. When a summary disagrees with the rating
 * beside it, trust in the whole screen drops, and that damage is measurably
 * softened by stating how the summary was made — so this line is part of the claim,
 * and it names the one thing that keeps the two halves from contradicting each
 * other.
 */
const val APPRAISAL_SOURCE_NOTE =
    "Модель Gemini шукала цю річ у Google і писала за тим, що знайшла — джерела " +
        "нижче. Ціни й оцінки магазину вона не бачила, тому з цифрами вгорі не сперечається."

/**
 * How an ungrounded review was produced, which is a different claim entirely.
 *
 * The owner has to be able to tell the two apart at a glance, or the sources are
 * decoration: a review that shows its working and a verdict that cannot are not
 * the same kind of thing, and only one of them is what was paid for.
 */
const val APPRAISAL_UNGROUNDED_NOTE =
    "Модель не показала жодного джерела — це написано з її пам'яті, а не знайдено " +
        "в пошуку. Стався до цього як до здогаду й перевір сам."

/** The model taking the way out, which is a working answer rather than a failure. */
const val APPRAISAL_UNFOUND_NOTE =
    "Це відповідь, а не помилка: краще так, ніж сторінка правдоподібного тексту " +
        "про якусь іншу річ."

/** Before anything has been asked for. */
const val APPRAISAL_IDLE_NOTE =
    "Модель ще нічого не писала про цю річ. Запит платний, тому лише на вимогу."

const val APPRAISAL_BUSY_NOTE = "Шукаю й читаю…"

/** The answer arrived and did not survive the checks. */
const val APPRAISAL_REFUSED_NOTE =
    "Модель відповіла, але або назвала ціну чи оцінку — а свої цифри в застосунку " +
        "вже є вгорі, — або написала не те, що просили. Тут нічого не показано."

/** Nothing arrived at all. */
const val APPRAISAL_FAILED_NOTE =
    "Модель не відповіла. Перевір зв'язок і спробуй ще раз."

/** What the button says, which differs once there is already something under it. */
fun appraisalAskLabel(written: Boolean): String =
    if (written) "Оцінити ще раз" else "Оцінити"

// ------------------------------------------------------------- the section

/** The name this section's folded state is stored under. Plain ASCII, like the rest. */
const val SECTION_APPRAISAL = "appraisal"

/**
 * One titled run of the review: a heading and either a line or a list under it.
 *
 * Drawn from a function rather than written out five times, because the five parts
 * of the review differ only in their words and a copy each is five places for them
 * to drift apart.
 */
@Composable
private fun ReviewPart(
    title: String,
    lines: List<String>,
    dim: Boolean,
    /** A list is marked as one; a part that is a single sentence is not. */
    bullet: Boolean = true
) {
    if (lines.none { it.isNotBlank() }) return
    Spacer(Modifier.height(Space.md))
    Text(
        title,
        color = if (dim) TextDisabled else TextSecondary,
        fontSize = Type.overlineSize,
        fontWeight = Type.strong,
        letterSpacing = Type.overlineTracking
    )
    lines.filter { it.isNotBlank() }.forEach { line ->
        Text(
            if (bullet) "— $line" else line,
            color = if (dim) TextDisabled else TextSecondary,
            fontSize = Type.bodySize,
            lineHeight = Type.bodyLine,
            modifier = Modifier.padding(top = Space.xs)
        )
    }
}

/**
 * The whole section, in two halves that are drawn to look like two kinds of claim.
 *
 * The first half is a [Card] like every other measured block on this page, with
 * [LeaderRow]s in it — the app's own idiom for a label and a figure, so the
 * arithmetic here looks exactly as native as the arithmetic in «Історія ціни»,
 * because it is the same kind of thing. One figure is spent at [Type.heroSize], on
 * where the price sits in its own window, which is the one number on this screen no
 * shopping assistant could produce.
 *
 * The second half is deliberately not a card. It is set behind a hairline rule down
 * its left edge — the typographic mark for a quotation from somewhere else — so
 * that it reads as an aside rather than as a measurement before a word of it has
 * been read.
 *
 * **What is new is that the aside has two strengths.** A review that can name the
 * pages it read is set in [TextSecondary] and carries a fold listing them, which
 * opens. One that cannot is set in [TextDisabled] under a line saying so. That
 * difference is the point of the whole change: the layout has to be able to say
 * "this was researched" and "this was remembered" without the reader having to know
 * the difference exists.
 *
 * No second accent, no gradient and no lime: the wish page already spends its one
 * accent, and a machine-written paragraph is the last thing that should be wearing
 * the colour this app uses to mean "this is the thing".
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember(wish.id) { mutableStateOf(false) }
    // What the last attempt came to, when it came to nothing worth keeping. Only
    // ever Refused or Failed: a written one goes straight onto the wish.
    var trouble by remember(wish.id) { mutableStateOf<AppraisalReading?>(null) }
    // Shut by default and not remembered across wishes: the list of pages is the
    // thing you open when you doubt a specific sentence, not a standing preference.
    var sourcesOpen by remember(wish.id) { mutableStateOf(false) }

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
                        // An answer the model could not ground is drawn a step
                        // quieter than one it could, and says why before it says
                        // anything else.
                        val dim = !written.grounded
                        if (dim) {
                            Text(
                                APPRAISAL_UNGROUNDED_NOTE,
                                color = Negative,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(bottom = Space.sm)
                            )
                        }
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
                            color = if (dim) TextDisabled else TextSecondary,
                            fontSize = Type.bodySize,
                            lineHeight = Type.bodyLine
                        )
                        if (!written.found) {
                            Text(
                                APPRAISAL_UNFOUND_NOTE,
                                color = TextDisabled,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(top = Space.sm)
                            )
                        } else {
                            ReviewPart(APPRAISAL_GOOD_TITLE, written.good, dim)
                            ReviewPart(APPRAISAL_WEAK_TITLE, written.weak, dim)
                            ReviewPart(
                                APPRAISAL_SUITS_TITLE,
                                listOf(written.suits),
                                dim,
                                bullet = false
                            )
                            ReviewPart(
                                APPRAISAL_SKIP_TITLE,
                                listOf(written.skip),
                                dim,
                                bullet = false
                            )
                            ReviewPart(APPRAISAL_CHECK_TITLE, written.check, dim)
                        }

                        if (written.grounded) {
                            Spacer(Modifier.height(Space.md))
                            CardFold(
                                title = APPRAISAL_SOURCES_TITLE,
                                summary = appraisalSourcesSummary(written),
                                open = sourcesOpen,
                                onToggle = { sourcesOpen = it }
                            ) {
                                Column {
                                    written.sources.forEach { source ->
                                        Text(
                                            appraisalSourceLabel(source),
                                            color = Accent,
                                            fontSize = Type.captionSize,
                                            lineHeight = Type.captionLine,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    context.startActivity(
                                                        Intent(
                                                            Intent.ACTION_VIEW,
                                                            source.url.toUri()
                                                        )
                                                    )
                                                }
                                                .padding(vertical = Space.xs)
                                        )
                                    }
                                    if (written.queries.isNotEmpty()) {
                                        Text(
                                            "Шукала: ${written.queries.joinToString("; ")}",
                                            color = TextDisabled,
                                            fontSize = Type.captionSize,
                                            lineHeight = Type.captionLine,
                                            modifier = Modifier.padding(top = Space.sm)
                                        )
                                    }
                                }
                            }
                        }

                        // Only for a grounded block. The ungrounded one already led
                        // with its warning in the alarm colour, and saying the same
                        // thing again in grey underneath is how a warning stops
                        // being read.
                        if (written.grounded) {
                            Text(
                                APPRAISAL_SOURCE_NOTE,
                                color = TextDisabled,
                                fontSize = Type.captionSize,
                                lineHeight = Type.captionLine,
                                modifier = Modifier.padding(top = Space.md)
                            )
                        }
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

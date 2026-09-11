package com.flowpay.app

import org.json.JSONArray

/**
 * The parsing FlowPay depends on, kept away from the network calls that feed it.
 *
 * Reading a price out of a shop page is the most fragile thing the app does: it is
 * the part that breaks first when a shop changes its markup, and it used to be
 * impossible to test because it was welded to an HttpURLConnection. Everything
 * here takes a string and returns a value, so it can be tested against saved
 * fragments without touching the network.
 */

private val ENTITIES = listOf(
    "&quot;" to "\"",
    "&#34;" to "\"",
    "&apos;" to "'",
    "&#39;" to "'",
    "&nbsp;" to " ",
    "&#160;" to " ",
    "&lt;" to "<",
    "&gt;" to ">",
    "&amp;" to "&"
)

/** Decodes the handful of entities that actually turn up in og: tags. */
fun decodeEntities(value: String): String {
    var text = value
    // Ampersand last, so "&amp;quot;" cannot be turned into a quote mark.
    for ((entity, char) in ENTITIES) text = text.replace(entity, char, ignoreCase = true)
    return text
}

/**
 * Reads a meta tag's content, accepting either attribute order, since shops write
 * both `property=... content=...` and `content=... property=...`.
 */
fun metaContent(html: String, key: String): String {
    val escaped = Regex.escape(key)
    val patterns = listOf(
        Regex("""<meta[^>]+(?:property|name)=["']$escaped["'][^>]+content=["']([^"']*)""", RegexOption.IGNORE_CASE),
        Regex("""<meta[^>]+content=["']([^"']*)["'][^>]+(?:property|name)=["']$escaped["']""", RegexOption.IGNORE_CASE)
    )
    return patterns.firstNotNullOfOrNull { it.find(html)?.groupValues?.get(1) }.orEmpty()
}

// Non-breaking spaces are what shops actually put between the digits, and one
// of them anywhere in this pattern used to leave the whole price in the title.
private val TITLE_PRICE_SUFFIX = Regex(
    """[\s\u00a0\u202f,;:|\-–—]+ціна[\s\u00a0\u202f]*[0-9][0-9\s\u00a0\u202f.,]*""" +
        """(?:грн\.?|₴|uah)?[\s\u00a0\u202f]*$""",
    RegexOption.IGNORE_CASE
)

/**
 * Tidies a scraped product title.
 *
 * Shops routinely append the price to og:title, which left cards reading
 * "ASICS Gel 1130 Dark Grey демісезонні, ціна 2203.24" directly above the same
 * price rendered on its own line. The price belongs to the price field, not the
 * name, so a trailing one is dropped.
 */
fun cleanProductTitle(raw: String): String {
    val decoded = decodeEntities(raw)
    val collapsed = decoded.replace(Regex("""\s+"""), " ").trim()
    val withoutPrice = TITLE_PRICE_SUFFIX.replace(collapsed, "").trim()
    val trimmed = withoutPrice.trimEnd(',', ';', ':', '|', '-', '–', '—', ' ')
    return trimmed.ifBlank { collapsed }
}

/**
 * Finds the price on a product page.
 *
 * Prefers the Open Graph amount, then falls back to a JSON "price" field, which is
 * what most shop templates leave behind in embedded structured data.
 */
fun extractPrice(html: String): Double {
    // Structured data first: it is the only source shops are actually pushed to fill
    // in, and it is unambiguous. Meta tags next, then a bare JSON field as a guess.
    val fromJsonLd = extractJsonLdPrice(html)
    if (fromJsonLd > 0) return fromJsonLd

    val fromMeta = listOf("product:price:amount", "og:price:amount")
        .firstNotNullOfOrNull { metaContent(html, it).takeIf { value -> value.isNotBlank() } }
        .orEmpty()
    val raw = fromMeta.ifBlank {
        Regex(""""price"\s*:\s*["']?([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1).orEmpty()
    }
    return raw.replace(" ", "").replace(',', '.').toDoubleOrNull() ?: 0.0
}

/**
 * Builds a wish from a product page. Throws when there is no price, because a
 * price tracker with no price to track is worse than a clear failure.
 */
fun parseProduct(html: String, url: String, id: String, today: Long = 0L): Wish {
    val price = extractPrice(html)
    require(price > 0) { "Не вдалося знайти ціну на сторінці" }
    val name = cleanProductTitle(metaContent(html, "og:title")).ifBlank { "Новий товар" }
    return Wish(
        id = id,
        name = name,
        url = url,
        image = decodeEntities(metaContent(html, "og:image")),
        price = price,
        history = listOf(PricePoint(price, today))
    )
}

/** Monobank: what a bank actually buys and sells dollars at today. */
const val SOURCE_MONOBANK = "mono"

/** The National Bank: the official rate, which nobody trades at. */
const val SOURCE_NBU = "nbu"

data class FxRate(
    val buy: Double = 0.0,
    val sell: Double = 0.0,
    /**
     * Which feed the figure came from. Empty means there is no figure yet.
     *
     * Carried with the numbers rather than kept beside them, because the whole
     * point is that a rate must never reach the screen without its source.
     */
    val source: String = "",
    /** The day the source itself dated the rate, in its own wording. Empty when it did not say. */
    val date: String = ""
)

// Everything up to the first whitespace or quote. Shop links carry commas and
// brackets inside their query strings, so they cannot be excluded here; the
// trailing ones get trimmed afterwards instead.
private val URL_IN_TEXT = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

// Punctuation that ends the sentence rather than the address. A closing bracket
// only ever belongs to the link when an opening one does too, and no shop the
// app deals with writes one, so it is safe to drop.
private const val SENTENCE_TAIL = ".,;:!?)]}»’”"

/**
 * Pulls the link out of a shared message.
 *
 * Almost nothing arrives as a bare address: browsers share "Назва товару
 * https://…", messengers wrap the link in a sentence, and shop apps put their
 * own slogan after it. Returns null when there is no usable http link, so a
 * share of plain text can be answered rather than silently dropped.
 */
fun extractUrl(text: String?): String? {
    val match = URL_IN_TEXT.find(text.orEmpty()) ?: return null
    val trimmed = match.value.trimEnd { it in SENTENCE_TAIL }
    return trimmed.takeIf { isSupportedWebUrl(it) }
}

/**
 * The name to show for a link whose page could not be read.
 *
 * The shop is the one thing the address itself can say, and it is enough to
 * recognise the row by until a later refresh fills in the real title.
 */
fun placeholderName(url: String): String {
    val host = runCatching { java.net.URL(url.trim()).host }
        .getOrNull().orEmpty()
        .removePrefix("www.")
    return if (host.isBlank()) "Нове бажання" else "Товар з $host"
}

/** Picks the USD to UAH pair out of the Monobank currency feed. */
fun parseUsdRate(json: String): FxRate {
    val array = JSONArray(json)
    val item = (0 until array.length())
        .map { array.getJSONObject(it) }
        .firstOrNull { it.optInt("currencyCodeA") == 840 && it.optInt("currencyCodeB") == 980 }
        ?: return FxRate()
    val buy = item.optDouble("rateBuy", 0.0)
    val sell = item.optDouble("rateSell", 0.0)
    if (sell <= 0.0) return FxRate()
    return FxRate(buy, sell, SOURCE_MONOBANK)
}

/**
 * Reads the National Bank's own feed.
 *
 * Monobank allows about one request a minute and answers a rejection after that,
 * which used to leave the screen showing yesterday's figure with no way to refresh
 * it. The National Bank publishes the official rate with no key and no limit, so
 * it is what the app falls back to.
 *
 * Buy and sell come back as the same number on purpose. The official rate is a
 * single published figure, not a price with two sides, and inventing a spread for
 * it would make a converted amount look more exact than it is.
 */
fun parseNbuRate(json: String): FxRate {
    val array = JSONArray(json)
    val item = (0 until array.length())
        .map { array.getJSONObject(it) }
        .firstOrNull { it.optString("cc").equals("USD", ignoreCase = true) }
        ?: return FxRate()
    val rate = item.optDouble("rate", 0.0)
    if (rate <= 0.0) return FxRate()
    return FxRate(rate, rate, SOURCE_NBU, item.optString("exchangedate"))
}

/**
 * Reads a price out of JSON-LD structured data.
 *
 * Open Graph price tags are the exception rather than the rule: `product:price:amount`
 * and the legacy `og:price:amount` are rarely filled in. JSON-LD with `@type: Product`
 * is what Google asks shops for, so it is present on pages where the meta tags are
 * not, and it carries the currency and availability alongside the number.
 *
 * Returns zero when nothing usable is found, so the caller can fall through to the
 * meta tags.
 */
fun extractJsonLdPrice(html: String): Double {
    val blocks = Regex(
        """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).findAll(html).map { it.groupValues[1] }

    for (block in blocks) {
        val price = runCatching { priceFromJsonLd(block.trim()) }.getOrNull() ?: continue
        if (price > 0) return price
    }
    return 0.0
}

/** Walks a JSON-LD document looking for a Product offer, including inside @graph. */
private fun priceFromJsonLd(raw: String): Double {
    val roots: List<Any> = when {
        raw.startsWith("[") -> {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { array.opt(it) }
        }
        raw.startsWith("{") -> listOf(org.json.JSONObject(raw))
        else -> emptyList()
    }
    for (root in roots) {
        val price = priceInNode(root, depth = 0)
        if (price > 0) return price
    }
    return 0.0
}

private fun priceInNode(node: Any?, depth: Int): Double {
    if (depth > 6) return 0.0
    when (node) {
        is JSONArray -> {
            for (i in 0 until node.length()) {
                val found = priceInNode(node.opt(i), depth + 1)
                if (found > 0) return found
            }
        }
        is org.json.JSONObject -> {
            // offers may be an object, an array, or nested inside a graph.
            node.opt("offers")?.let { offers ->
                val direct = offerPrice(offers, depth + 1)
                if (direct > 0) return direct
            }
            node.opt("@graph")?.let { graph ->
                val found = priceInNode(graph, depth + 1)
                if (found > 0) return found
            }
            val own = node.optString("price").takeIf { it.isNotBlank() }
            if (own != null) {
                val parsed = own.replace(" ", "").replace(',', '.').toDoubleOrNull()
                if (parsed != null && parsed > 0) return parsed
            }
        }
    }
    return 0.0
}

private fun offerPrice(offers: Any?, depth: Int): Double {
    when (offers) {
        is JSONArray -> {
            for (i in 0 until offers.length()) {
                val found = offerPrice(offers.opt(i), depth + 1)
                if (found > 0) return found
            }
        }
        is org.json.JSONObject -> {
            val raw = listOf("price", "lowPrice", "highPrice")
                .firstNotNullOfOrNull { offers.optString(it).takeIf { value -> value.isNotBlank() } }
            val parsed = raw?.replace(" ", "")?.replace(',', '.')?.toDoubleOrNull()
            if (parsed != null && parsed > 0) return parsed
            // priceSpecification wraps the number on some templates.
            offers.opt("priceSpecification")?.let { return offerPrice(it, depth + 1) }
        }
    }
    return 0.0
}

/**
 * Works out which build a GitHub release contains.
 *
 * The workflow writes `versionCode=N` into the release body, which is exact. But a
 * release created by hand has no such line, and without a fallback the in-app
 * updater would ignore it and report that nothing was published. So a tag like
 * `v2.3.0` is also accepted, read as major, minor and patch and folded into one
 * comparable number the same way the build does.
 */
fun releaseVersionCode(body: String, tag: String): Int? {
    Regex("""versionCode\s*=\s*(\d+)""", RegexOption.IGNORE_CASE)
        .find(body)?.groupValues?.get(1)?.toIntOrNull()
        ?.let { return it }

    val parts = Regex("""(\d+)\.(\d+)\.(\d+)""").find(tag)?.groupValues ?: return null
    val major = parts[1].toIntOrNull() ?: return null
    val minor = parts[2].toIntOrNull() ?: return null
    val patch = parts[3].toIntOrNull() ?: return null
    return major * 10_000 + minor * 100 + patch
}

/**
 * Chooses which file to download from a release.
 *
 * A release can end up holding more than one APK, and taking whichever happens to
 * be listed first is a coin toss: picking a stale build makes the app install an
 * older version, then see the release as newer again and offer the same update for
 * ever. So an asset whose name carries the release's own version wins, and the
 * first APK is only a fallback.
 */
fun pickApkAsset(names: List<String>, tag: String): String? {
    val apks = names.filter { it.endsWith(".apk", ignoreCase = true) }
    if (apks.isEmpty()) return null
    val version = Regex("""\d+\.\d+\.\d+""").find(tag)?.value
    if (version != null) {
        apks.firstOrNull { it.contains(version) }?.let { return it }
    }
    return apks.first()
}

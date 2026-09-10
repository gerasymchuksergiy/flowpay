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

private val TITLE_PRICE_SUFFIX = Regex(
    """[\s,;:|\-–—]+ціна\s*[0-9][0-9\s.,]*(?:грн\.?|₴|uah)?\s*$""",
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
    val fromMeta = metaContent(html, "product:price:amount")
    val raw = fromMeta.ifBlank {
        Regex(""""price"\s*:\s*["']?([0-9]+(?:[.,][0-9]+)?)""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1).orEmpty()
    }
    return raw.replace(',', '.').toDoubleOrNull() ?: 0.0
}

/**
 * Builds a wish from a product page. Throws when there is no price, because a
 * price tracker with no price to track is worse than a clear failure.
 */
fun parseProduct(html: String, url: String, id: String): Wish {
    val price = extractPrice(html)
    require(price > 0) { "Не вдалося знайти ціну на сторінці" }
    val name = cleanProductTitle(metaContent(html, "og:title")).ifBlank { "Новий товар" }
    return Wish(
        id = id,
        name = name,
        url = url,
        image = decodeEntities(metaContent(html, "og:image")),
        price = price,
        history = listOf(price)
    )
}

/** Picks the USD to UAH pair out of the Monobank currency feed. */
fun parseUsdRate(json: String): FxRate {
    val array = JSONArray(json)
    val item = (0 until array.length())
        .map { array.getJSONObject(it) }
        .firstOrNull { it.optInt("currencyCodeA") == 840 && it.optInt("currencyCodeB") == 980 }
        ?: return FxRate()
    return FxRate(item.optDouble("rateBuy", 0.0), item.optDouble("rateSell", 0.0))
}

/**
 * Ukrainian needs three forms where English needs two, so "1 вимірювань" was
 * simply wrong on screen.
 */
fun measurementsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "вимірювань"
        last == 1 -> "вимірювання"
        last in 2..4 -> "вимірювання"
        else -> "вимірювань"
    }
    return "$count $word"
}

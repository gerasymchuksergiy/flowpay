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

/**
 * The named references worth carrying by hand.
 *
 * Short on purpose. HTML defines over two thousand names, and a table typed out
 * from memory only ever covers the spellings somebody happened to think of —
 * which is how "&#039;" sat unread in a parcel's title while "&#39;" one row away
 * was fine. Numeric references are decoded by rule below, so this list only has
 * to carry the names that actually turn up in og: tags.
 *
 * "&nbsp;" becomes an ordinary space rather than U+00A0 deliberately: the title
 * tidying downstream collapses whitespace with `\s+`, which in Java does not
 * match a non-breaking space, so keeping one here would leave it welded into the
 * middle of a product name. The same normalisation is applied to code point 160
 * in [decodeCodePoint], so the two spellings cannot disagree.
 */
private val NAMED_ENTITIES = mapOf(
    "quot" to "\"",
    "apos" to "'",
    "nbsp" to " ",
    "lt" to "<",
    "gt" to ">",
    "amp" to "&"
)

/**
 * What HTML says a reference in the C1 range means.
 *
 * Numerically 0x80..0x9F are control characters, and a control character dropped
 * into a product title is worse than the raw "&#151;" it replaced. Every browser
 * reads these as the windows-1252 characters the author meant, because that is
 * what the HTML standard tells them to do, and old shop back ends emit them
 * constantly — "&#146;" for an apostrophe and "&#151;" for a dash above all.
 */
private val WINDOWS_1252 = mapOf(
    0x80 to '€', 0x82 to '‚', 0x83 to 'ƒ', 0x84 to '„',
    0x85 to '…', 0x86 to '†', 0x87 to '‡', 0x88 to 'ˆ',
    0x89 to '‰', 0x8A to 'Š', 0x8B to '‹', 0x8C to 'Œ',
    0x8E to 'Ž', 0x91 to '‘', 0x92 to '’', 0x93 to '“',
    0x94 to '”', 0x95 to '•', 0x96 to '–', 0x97 to '—',
    0x98 to '˜', 0x99 to '™', 0x9A to 'š', 0x9B to '›',
    0x9C to 'œ', 0x9E to 'ž', 0x9F to 'Ÿ'
)

/**
 * The longest "&...;" this will even consider.
 *
 * A stray ampersand in running text is far more common than an entity, and with
 * no bound the scanner would hunt to the next semicolon anywhere in the string —
 * across a whole title — before giving up. Thirty-one characters clears the
 * longest real name and any amount of zero padding a shop plausibly writes.
 */
private const val REFERENCE_LIMIT = 31

/**
 * Turns character references back into the characters they stand for.
 *
 * One left-to-right pass, and output is never looked at again. That is the whole
 * defence against double decoding: "&amp;quot;" yields a literal "&" followed by
 * the plain text "quot;", and because the scanner has already moved past what it
 * wrote, the "&" it just produced cannot begin a second reference. The same holds
 * for "&amp;#39;", which has to come out as the six characters "&#39;" and not as
 * an apostrophe, and for "&#38;#39;", which the old replace-in-order table would
 * have got wrong however the rows were sorted.
 *
 * Anything that is not a reference is copied through untouched, so a title
 * reading "5 & 6" and a malformed "&#;" both survive exactly as typed.
 */
fun decodeEntities(value: String): String {
    if (!value.contains('&')) return value
    val out = StringBuilder(value.length)
    var index = 0
    while (index < value.length) {
        val char = value[index]
        if (char != '&') {
            out.append(char)
            index++
            continue
        }
        val close = value.indexOf(';', index + 1)
        val decoded = if (close < 0 || close - index > REFERENCE_LIMIT) {
            null
        } else {
            decodeReference(value.substring(index + 1, close))
        }
        if (decoded == null) {
            // Not a reference this understands, so the ampersand is ordinary text.
            out.append(char)
            index++
        } else {
            out.append(decoded)
            index = close + 1
        }
    }
    return out.toString()
}

/** The body of one "&...;", without the ampersand and the semicolon. */
private fun decodeReference(body: String): String? {
    if (body.isEmpty()) return null
    if (body[0] != '#') {
        // Kotlin's no-argument lowercase() is locale independent, unlike Java's
        // toLowerCase(), so "&QUOT;" reads the same on a Turkish phone as here.
        return NAMED_ENTITIES[body.lowercase()]
    }
    val digits = body.substring(1)
    val hex = digits.firstOrNull() == 'x' || digits.firstOrNull() == 'X'
    val figures = if (hex) digits.substring(1) else digits
    // "&#;" and "&#x;" are malformed, not references.
    if (figures.isEmpty()) return null
    // ASCII figures only. toIntOrNull would accept a leading sign and Arabic-Indic
    // digits, and neither of those is a character reference.
    val valid = figures.all {
        if (hex) it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' else it in '0'..'9'
    }
    if (!valid) return null
    // Zero padding is unbounded in the standard, so "&#000000039;" is legal and the
    // raw string can overflow an Int. Trimming first keeps the conversion honest;
    // anything still long enough to exceed U+10FFFF is out of range whatever it says.
    val trimmed = figures.trimStart('0')
    if (trimmed.isEmpty()) return null
    // U+10FFFF is six hexadecimal figures and seven decimal ones, so the cap has
    // to follow the radix rather than being one number for both.
    if (trimmed.length > if (hex) 6 else 7) return null
    val code = trimmed.toIntOrNull(if (hex) 16 else 10) ?: return null
    return decodeCodePoint(code)
}

/**
 * One code point as text, or null when it cannot honestly be one.
 *
 * Out of Unicode's range, half of a surrogate pair, or a control character: each
 * of those would either throw or land a replacement box in the middle of a
 * product name, so the reference is left standing as the text it already was.
 * That is always readable, which a black diamond is not.
 */
private fun decodeCodePoint(code: Int): String? {
    // A non-breaking space normalises to an ordinary one, for the reason given
    // over NAMED_ENTITIES.
    if (code == 0x00A0) return " "
    WINDOWS_1252[code]?.let { return it.toString() }
    if (code > 0x10FFFF) return null
    // A surrogate is half of a pair and means nothing on its own.
    if (code in 0xD800..0xDFFF) return null
    // Controls, NUL included. Tab and newline are let through because they are
    // real whitespace that the tidying downstream knows how to collapse.
    if (code < 0x20 && code != 0x09 && code != 0x0A) return null
    if (code in 0x7F..0x9F) return null
    return String(Character.toChars(code))
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
 * What a page says about the thing, beyond its price.
 *
 * Every field is optional and blank when the page did not state it, because the
 * honest answer for most shops is that they did not. Nothing here is guessed from
 * the visible text: a description scraped out of layout is a paragraph of markup,
 * and a specification table has a different shape in every shop. Only what the
 * page declares about itself in a standard place is read.
 */
data class ProductAbout(
    val description: String = "",
    val brand: String = "",
    /** Out of five, as shops state it. Zero when there is no rating. */
    val rating: Double = 0.0,
    val ratingCount: Int = 0,
    /** Name and value pairs the shop itself published as structured data. */
    val specs: List<Pair<String, String>> = emptyList()
) {
    val isEmpty: Boolean
        get() = description.isBlank() && brand.isBlank() && rating <= 0 && specs.isEmpty()
}

// Long enough to be worth reading, short enough not to become the page itself.
private const val DESCRIPTION_LIMIT = 600

/** Collapses a scraped run of text into one readable paragraph. */
private fun tidyText(raw: String): String {
    val text = decodeEntities(raw).replace(Regex("""<[^>]+>"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    if (text.length <= DESCRIPTION_LIMIT) return text
    // Cut at a sentence if one is near the limit, otherwise at a word.
    val window = text.take(DESCRIPTION_LIMIT)
    val sentence = window.lastIndexOfAny(charArrayOf('.', '!', '?', '…'))
    val cut = if (sentence > DESCRIPTION_LIMIT / 2) sentence + 1 else window.lastIndexOf(' ')
    return window.take(if (cut > 0) cut else DESCRIPTION_LIMIT).trim() + "…"
}

/**
 * Everything the page declares about the thing itself.
 *
 * og:description is the one field nearly every shop fills, because it decides how
 * their link looks when someone pastes it into a messenger. The rest — brand,
 * rating, specifications — is filled only by shops that want Google to show it,
 * so those blocks are absent far more often than present, and the screen has to
 * be built for their absence rather than around their presence.
 */
fun extractAbout(html: String): ProductAbout {
    val fromJsonLd = aboutFromJsonLd(html)
    val description = listOf(
        metaContent(html, "og:description"),
        fromJsonLd.description,
        metaContent(html, "description")
    ).firstOrNull { it.isNotBlank() }.orEmpty()

    val microRating = priceNumber(
        Regex("""itemprop=["']ratingValue["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1).orEmpty()
    ) ?: 0.0
    val microCount = priceNumber(
        Regex("""itemprop=["'](?:reviewCount|ratingCount)["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1).orEmpty()
    )?.toInt() ?: 0

    return ProductAbout(
        description = tidyText(description),
        brand = tidyText(fromJsonLd.brand),
        rating = fromJsonLd.rating.takeIf { it > 0 } ?: microRating,
        ratingCount = fromJsonLd.ratingCount.takeIf { it > 0 } ?: microCount,
        specs = fromJsonLd.specs
    )
}

private fun aboutFromJsonLd(html: String): ProductAbout {
    var about = ProductAbout()
    Regex(
        """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).findAll(html).map { it.groupValues[1].trim() }.forEach { block ->
        runCatching {
            val root: Any = when {
                block.startsWith("[") -> JSONArray(block)
                block.startsWith("{") -> org.json.JSONObject(block)
                else -> return@runCatching
            }
            about = aboutInNode(root, about, depth = 0)
        }
    }
    return about
}

private fun aboutInNode(node: Any?, found: ProductAbout, depth: Int): ProductAbout {
    if (depth > 6) return found
    var about = found
    when (node) {
        is JSONArray -> (0 until node.length()).forEach {
            about = aboutInNode(node.opt(it), about, depth + 1)
        }
        is org.json.JSONObject -> {
            if (about.description.isBlank()) {
                node.optString("description").takeIf { it.isNotBlank() }
                    ?.let { about = about.copy(description = it) }
            }
            if (about.brand.isBlank()) {
                val brand = node.opt("brand")
                val name = when (brand) {
                    is org.json.JSONObject -> brand.optString("name")
                    is String -> brand
                    else -> ""
                }
                if (name.isNotBlank()) about = about.copy(brand = name)
            }
            if (about.rating <= 0 && node.has("ratingValue")) {
                val value = priceNumber(node.opt("ratingValue").toString()) ?: 0.0
                val count = priceNumber(
                    node.opt("reviewCount")?.toString().orEmpty().ifBlank {
                        node.opt("ratingCount")?.toString().orEmpty()
                    }
                )?.toInt() ?: 0
                if (value > 0) about = about.copy(rating = value, ratingCount = count)
            }
            // additionalProperty is the only place a shop states a specification in
            // a shape every shop states it the same way.
            if (about.specs.isEmpty() && node.has("additionalProperty")) {
                val list = mutableListOf<Pair<String, String>>()
                fun collect(value: Any?) {
                    when (value) {
                        is JSONArray -> (0 until value.length()).forEach { collect(value.opt(it)) }
                        is org.json.JSONObject -> {
                            val name = value.optString("name")
                            val text = value.opt("value")?.toString().orEmpty()
                            if (name.isNotBlank() && text.isNotBlank() && text != "null") {
                                list.add(decodeEntities(name) to decodeEntities(text))
                            }
                        }
                    }
                }
                collect(node.opt("additionalProperty"))
                if (list.isNotEmpty()) about = about.copy(specs = list)
            }
            node.keys().forEach { key ->
                if (key != "additionalProperty") {
                    about = aboutInNode(node.opt(key), about, depth + 1)
                }
            }
        }
    }
    return about
}

/**
 * One price a page states, and whatever the page called it.
 *
 * A page rarely has a single price. Editions of a game, sizes of a shoe, storage
 * of a phone: each is an offer, and picking the first one found means tracking
 * whichever the shop happened to list first.
 */
data class Offer(
    val price: Double,
    /** Edition, size, colour — empty when the page gave the figure no name. */
    val label: String = "",
    /**
     * The ISO code the page put beside the figure, empty when it named none.
     *
     * A number with no currency is the bug this field exists to close: `$59.99`
     * used to land as `59,99 ₴` and the app announced a ninety-nine per cent
     * drop on an item that had not moved. Left empty rather than defaulted here,
     * because "the page said nothing" and "the page said UAH" are different
     * facts and only the reader downstream should decide what to do about it.
     */
    val currency: String = "",
    /**
     * Whether the page said this particular offer can be bought.
     *
     * Defaults to [Availability.UNKNOWN] for the same reason [currency] defaults to
     * empty: most Ukrainian shops declare nothing, and "the page said nothing" has
     * to stay distinguishable from "the page said yes".
     */
    val availability: Availability = Availability.UNKNOWN
)

/**
 * What a page declared about being able to buy the thing.
 *
 * schema.org has nine tokens for this and the app has three answers, because
 * three is how many distinct things it can actually do with the information: go
 * on as normal, refuse to believe the number, or say the thing is not coming
 * back. Every token is folded into one of them here, once, so that no part of the
 * app downstream has to have an opinion about what `BackOrder` means.
 *
 * The mapping, and why:
 *
 * - `InStock` → [IN_STOCK]. The plain case.
 * - `LimitedAvailability` → [IN_STOCK]. "Few left" is a stock level, not an
 *   absence; the price is a price and the thing is in a basket today.
 * - `OnlineOnly` → [IN_STOCK]. It is buyable, and the page being read *is* the
 *   online channel.
 * - `InStoreOnly` → [IN_STOCK]. Not buyable through this link, but the figure is
 *   a real price somebody can pay by walking in, and the whole point of this
 *   wishlist is deciding when to go and get something.
 * - `PreOrder` → [IN_STOCK]. A pre-order price is a price the shop will take
 *   money at today; it is a commitment, not a guess.
 * - `BackOrder` → [IN_STOCK]. The same bargain as a pre-order for a thing that
 *   already exists: the shop is quoting a figure it will honour. It is a promise
 *   about delivery, which is a fact about waiting rather than about the price.
 * - `OutOfStock`, `SoldOut` → [SOLD_OUT]. The figure beside these is not a price
 *   anybody can pay, and it is very often lower than the real one.
 * - `Discontinued` → [DISCONTINUED]. Also not buyable, and handled identically
 *   everywhere money is concerned — but it is the one of these that is not going
 *   to end, and "знято з продажу" tells the person to stop waiting where "немає
 *   в наявності" tells them to keep waiting.
 * - Anything else, including nothing at all → [UNKNOWN], which must behave in
 *   every respect exactly as the app behaved before availability was read. Most
 *   shops this app is pointed at declare nothing, and treating "not exactly
 *   InStock" as sold out would grey out the entire wishlist.
 */
enum class Availability {
    /** The page said nothing usable. Changes nothing, anywhere. */
    UNKNOWN,

    /** The page said it can be bought, now or on a promise it named a price for. */
    IN_STOCK,

    /** The page said it cannot be bought. The number beside it is not a price. */
    SOLD_OUT,

    /** The page said it is not sold any more, and will not be again. */
    DISCONTINUED
}

/**
 * Whether an availability means the figure beside it must not be believed.
 *
 * One predicate rather than a comparison repeated at each of the places that has
 * to make this decision, because the whole bug was that those places disagreed
 * about a question nobody had written down.
 */
fun blocksPrice(availability: Availability): Boolean = when (availability) {
    Availability.SOLD_OUT, Availability.DISCONTINUED -> true
    Availability.UNKNOWN, Availability.IN_STOCK -> false
}

/** The schema.org tokens, already reduced to letters, and what each one means here. */
private val AVAILABILITY_TOKENS = mapOf(
    "instock" to Availability.IN_STOCK,
    "limitedavailability" to Availability.IN_STOCK,
    "onlineonly" to Availability.IN_STOCK,
    "instoreonly" to Availability.IN_STOCK,
    "preorder" to Availability.IN_STOCK,
    "presale" to Availability.IN_STOCK,
    "backorder" to Availability.IN_STOCK,
    "outofstock" to Availability.SOLD_OUT,
    "soldout" to Availability.SOLD_OUT,
    "discontinued" to Availability.DISCONTINUED
)

/**
 * Reads whatever a page wrote for availability into one of the three answers.
 *
 * Shops write every one of `https://schema.org/InStock`, `http://schema.org/InStock`,
 * `InStock`, `inStock`, `in_stock` and `out of stock`, sometimes two of them on the
 * same page, so the token is reduced to its letters before it is looked up: the
 * vocabulary word is what carries the meaning and the rest is punctuation.
 *
 * Anything unrecognised comes back [Availability.UNKNOWN] rather than being
 * guessed at. A shop that invents its own word must leave the app exactly as it
 * would have been had the attribute been absent.
 */
fun availabilityFrom(raw: String): Availability {
    val text = decodeEntities(raw).trim()
    if (text.isBlank()) return Availability.UNKNOWN
    val token = text.substringAfterLast('/').substringAfterLast('#')
        .filter { it.isLetter() }
        .lowercase()
    return AVAILABILITY_TOKENS[token] ?: Availability.UNKNOWN
}

/**
 * Reads a stored availability back, defaulting to what a silent page means.
 *
 * The sibling of [freshnessFrom], and unknown is the right fallback for both the
 * absent key of older data and a name this build no longer has: a value the app
 * cannot interpret must leave every judgement exactly where a shop's silence
 * leaves it.
 */
fun availabilityStored(name: String): Availability =
    Availability.entries.firstOrNull { it.name == name } ?: Availability.UNKNOWN

/** Symbols shops write instead of a code, and the code each one means. */
private val CURRENCY_SYMBOLS = mapOf(
    "₴" to UAH, "грн" to UAH, "грн." to UAH, "uah" to UAH,
    "$" to USD, "us$" to USD, "usd" to USD,
    "€" to "EUR", "£" to "GBP", "zł" to "PLN"
)

/**
 * Normalises whatever a page called its currency into an ISO code.
 *
 * Any three letters are accepted as they stand rather than being checked against
 * a list of currencies the app knows: a page priced in SEK has to come back as
 * "SEK" so that the reader can say it has no rate for it. Folding an unknown code
 * into "nothing stated" would put it straight back into the hryvnia bucket, which
 * is the whole failure being fixed.
 */
fun currencyCode(raw: String): String {
    val text = decodeEntities(raw).trim()
    if (text.isBlank()) return ""
    CURRENCY_SYMBOLS[text.lowercase()]?.let { return it }
    val letters = text.filter { it.isLetter() }
    if (letters.length == 3) return letters.uppercase()
    return CURRENCY_SYMBOLS[text.take(1)] ?: ""
}

// schema.org states the currency beside the price, in attributes as often as in
// a script, and Open Graph has its own pair of spellings for it.
private val MICRODATA_CURRENCY = listOf(
    Regex("""itemprop=["']priceCurrency["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    Regex("""content=["']([^"']+)["'][^>]*itemprop=["']priceCurrency["']""", RegexOption.IGNORE_CASE)
)

/**
 * The currency the page states for its prices, page-wide.
 *
 * One answer for the whole page rather than one per offer, because a shop that
 * lists three editions prices all three in the same money — no shop sells one
 * edition in dollars and the next in hryvnia. Where JSON-LD does state a currency
 * per offer that wins; this is the fallback for everything else.
 */
fun pageCurrency(html: String): String {
    MICRODATA_CURRENCY.forEach { pattern ->
        val found = currencyCode(pattern.find(html)?.groupValues?.get(1).orEmpty())
        if (found.isNotBlank()) return found
    }
    listOf("product:price:currency", "og:price:currency").forEach { property ->
        val found = currencyCode(metaContent(html, property))
        if (found.isNotBlank()) return found
    }
    return ""
}

/**
 * schema.org availability written as an attribute rather than in a script.
 *
 * Unlike the price, this is written as often on a `<link href>` as on a
 * `<meta content>`: the value is a URL, so the markup that carries URLs is the
 * one the vocabulary's own examples use. Both attribute orders, because shops
 * write both.
 */
private val MICRODATA_AVAILABILITY = listOf(
    Regex("""itemprop=["']availability["'][^>]*(?:content|href)=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    Regex("""(?:content|href)=["']([^"']+)["'][^>]*itemprop=["']availability["']""", RegexOption.IGNORE_CASE)
)

/**
 * Whether the page, taken as a whole, says the thing can be bought.
 *
 * One answer per page for the same reason [pageCurrency] gives one: this is the
 * fallback for offers that did not state it themselves. Where JSON-LD states it
 * per offer that wins, because a page of editions genuinely can have one edition
 * sold out and the next in stock, and that distinction is the whole reason the
 * picker needs to say anything.
 *
 * A page that states nothing comes back [Availability.UNKNOWN] and every reader
 * downstream then behaves exactly as it did before any of this was read.
 */
fun pageAvailability(html: String): Availability {
    MICRODATA_AVAILABILITY.forEach { pattern ->
        val found = availabilityFrom(pattern.find(html)?.groupValues?.get(1).orEmpty())
        if (found != Availability.UNKNOWN) return found
    }
    listOf("product:availability", "og:availability", "availability").forEach { property ->
        val found = availabilityFrom(metaContent(html, property))
        if (found != Availability.UNKNOWN) return found
    }
    return Availability.UNKNOWN
}

/** Any space a shop might put inside a number, including the ones that are not spaces. */
private const val PRICE_SPACES = " \u00a0\u202f\u2009"

/** "2 199" / "2199,50" / "2 199.00" -> the number, or null when it is not one. */
internal fun priceNumber(raw: String): Double? {
    var text = raw.trim()
    PRICE_SPACES.forEach { text = text.replace(it.toString(), "") }
    text = if (text.count { it == ',' } == 1 && !text.contains('.')) {
        text.replace(',', '.')
    } else {
        text.replace(",", "")
    }
    if (!Regex("""^\d+(\.\d+)?$""").matches(text)) return null
    return text.toDoubleOrNull()?.takeIf { it > 0 }
}

// Attributes whose name mentions price. Shops put the figure the page actually
// renders in one of these, and on a page with variants it is often the only place
// each variant's own price appears.
private val PRICE_ATTRIBUTE = Regex("""data-([a-z-]*price[a-z-]*)=["'](\d+)["']""", RegexOption.IGNORE_CASE)

// schema.org in attributes rather than in a script. Steam carries this and no
// JSON-LD at all, which is why its pages used to yield nothing.
private val MICRODATA_PRICE = listOf(
    Regex("""itemprop=["']price["'][^>]*content=["']([^"']+)["']""", RegexOption.IGNORE_CASE),
    Regex("""content=["']([^"']+)["'][^>]*itemprop=["']price["']""", RegexOption.IGNORE_CASE)
)

// ------------------------------------- prices a page renders instead of declaring

/**
 * Every `<script>` body on the page, including the ones holding data rather than code.
 *
 * Bounded to scripts on purpose. A shop's rendered price also appears in its
 * layout, but the layout is where the prose lives — "від 199 ₴ за доставку",
 * a banner, a related item's tag — and reading figures out of it is how a price
 * tracker starts tracking a delivery charge.
 */
private val SCRIPT_BODY = Regex(
    """<script[^>]*>(.*?)</script>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
)

/**
 * One double-quoted value out of a script body, and never a bare number.
 *
 * The quotes are the second bound. A page carries thousands of bare integers —
 * ids, timestamps, pixel sizes, prices in minor units — and any rule that reads
 * them would find a price on a page that has none. A figure a shop has already
 * formatted for a person to read, with its own money written beside it, is a
 * different kind of thing: it was written to be shown.
 *
 * The quantifier is possessive so the scan stays linear over a page that is
 * mostly minified JavaScript. Without it, every string longer than the limit
 * costs sixty-four failed attempts before the engine gives up on it, and that is
 * a measurable pause on a six-hundred-kilobyte page.
 */
private val QUOTED_VALUE = Regex("\"([^\"\\\\\\n]{1,64}+)\"")

/** The money words shops write, longest first so "грн." cannot be cut to "грн". */
private const val CURRENCY_MARK =
    """(?:грн\.|грн|₴|UAH|USD|EUR|GBP|PLN|us\$|\$|€|£|zł)"""

/** The space characters that turn up inside a formatted figure. */
private const val SPACE_CHARS = """    """
private const val GAP = "[$SPACE_CHARS]"

/**
 * A figure, from a digit to a digit.
 *
 * Loose about what sits between, because grouping is written with spaces, commas
 * and full stops depending on the shop, and [priceNumber] is the single place
 * that decides whether a given run of those is actually a number. What it must
 * not do is end on punctuation, which is why both ends are a digit.
 */
private const val FIGURE = """\d(?:[\d$SPACE_CHARS.,]*\d)?"""

/** A figure with its money on one side or the other: "1 458.21₴", "$59.99". */
private const val MONEY = """(?:$CURRENCY_MARK$GAP*$FIGURE|$FIGURE$GAP*$CURRENCY_MARK)"""

/**
 * A quoted string that is a price and nothing else.
 *
 * The third and most important bound: the *whole* string has to be the price. A
 * sentence that merely mentions a figure — a delivery rule, a coupon condition,
 * a promise about a discount — is rejected outright, and that is what keeps this
 * from finding a dozen numbers on every page in the world.
 *
 * A short leading word is allowed through because the shop's own label is the
 * most useful thing on offer here: "РРЦ 3 366.84₴" is a crossed-out recommended
 * price, and carrying "РРЦ" into [Offer.label] is what lets the picker warn the
 * person off it rather than the app quietly tracking a price nobody pays.
 *
 * Group 1 is that label, group 2 the first figure and group 3 the second, which
 * a page writes when the variants span a range.
 */
private val PRICE_STRING = Regex(
    """$GAP*(\p{L}[\p{L}.$SPACE_CHARS]{0,15}?)?$GAP*($MONEY)""" +
        """(?:$GAP*[-–—]$GAP*($MONEY|$FIGURE))?$GAP*""",
    RegexOption.IGNORE_CASE
)

private val FIGURE_ONLY = Regex(FIGURE)
private val CURRENCY_ONLY = Regex(CURRENCY_MARK, RegexOption.IGNORE_CASE)

/** Longer than this and it is prose with a number in it, not a price. */
private const val PRICE_STRING_LIMIT = 64

/**
 * How many inferred prices are worth offering.
 *
 * A picker is a question, and a question with thirty answers is not one. Beyond a
 * handful the person cannot tell which row is their item anyway, so the list is
 * cut rather than allowed to become a wall of numbers.
 */
private const val INLINE_PRICE_LIMIT = 8

/**
 * Reads one quoted string as the price or prices it states.
 *
 * A range — "1 458.21₴-1 603.45₴" — is two offers rather than one, because that
 * is a page telling you the variants cost different amounts, and collapsing it to
 * a single figure would pick one of them at random.
 */
fun priceStringOffers(raw: String): List<Offer> {
    val text = decodeEntities(raw)
    if (text.length > PRICE_STRING_LIMIT) return emptyList()
    val match = PRICE_STRING.matchEntire(text) ?: return emptyList()
    val label = match.groupValues[1].trim().trimEnd('.').trim()
    val currency = currencyCode(CURRENCY_ONLY.find(text)?.value.orEmpty())
    return listOfNotNull(match.groups[2], match.groups[3])
        .mapNotNull { part -> priceNumber(FIGURE_ONLY.find(part.value)?.value.orEmpty()) }
        .map { Offer(it, label, currency) }
}

/**
 * Every price the page renders out of its own scripts.
 *
 * The universal answer to a shop that ships its price as data for its own
 * JavaScript instead of declaring it where a reader can find it. It asks nothing
 * about the shop and nothing about the key the figure is stored under — the key
 * names change between two fetches of the same page — only whether a string the
 * shop formatted for a person to read is sitting in the page.
 *
 * This is inference, and it is treated as such: [extractOffers] consults it only
 * when the page declared no price at all, and what it finds is offered as a
 * choice rather than adopted as a fact.
 */
fun inlinePriceOffers(html: String): List<Offer> {
    val found = LinkedHashMap<Long, Offer>()
    SCRIPT_BODY.findAll(html).forEach { script ->
        QUOTED_VALUE.findAll(script.groupValues[1]).forEach { quoted ->
            if (found.size >= INLINE_PRICE_LIMIT) return found.values.toList()
            priceStringOffers(quoted.groupValues[1]).forEach { offer ->
                val key = Math.round(offer.price * 100)
                if (found.size < INLINE_PRICE_LIMIT && !found.containsKey(key)) {
                    found[key] = offer
                }
            }
        }
    }
    return found.values.toList()
}

/**
 * Every distinct price the page states, richest source first.
 *
 * Sources are tried in order of how much they can be trusted, and a price already
 * seen is not added again: the same figure commonly appears as microdata and as an
 * attribute, and offering the user the same number twice would be nonsense.
 */
fun extractOffers(html: String): List<Offer> {
    val found = LinkedHashMap<Long, Offer>()
    // Read once and used for every offer that did not name its own, so a page
    // whose currency lives in microdata still labels prices found in attributes.
    val stated = pageCurrency(html)
    // The same arrangement for availability, and it matters more here: a price
    // found in a `data-price` attribute carries nothing about itself, so the only
    // thing that can tell the app the page is sold out is the page's own
    // declaration elsewhere in the markup.
    val declared = pageAvailability(html)

    fun add(
        price: Double?,
        label: String,
        currency: String = "",
        availability: Availability = Availability.UNKNOWN
    ) {
        val value = price ?: return
        val key = Math.round(value * 100)
        if (!found.containsKey(key)) {
            found[key] = Offer(
                value,
                label,
                currency.ifBlank { stated },
                if (availability == Availability.UNKNOWN) declared else availability
            )
        }
    }

    jsonLdOffers(html).forEach { add(it.price, it.label, it.currency, it.availability) }
    MICRODATA_PRICE.forEach { pattern ->
        pattern.findAll(html).forEach { add(priceNumber(it.groupValues[1]), "") }
    }
    listOf("product:price:amount", "og:price:amount").forEach { property ->
        add(priceNumber(metaContent(html, property)), "")
    }
    PRICE_ATTRIBUTE.findAll(html).forEach { match ->
        val raw = priceNumber(match.groupValues[2]) ?: return@forEach
        // These are frequently in minor units: Steam writes 219900 for 2 199 ₴.
        val value = if (raw >= 10_000 && raw % 100.0 == 0.0) raw / 100 else raw
        add(value, "")
    }
    if (found.isEmpty()) {
        add(
            priceNumber(
                Regex(""""price"\s*:\s*["']?([0-9][0-9\s.,]*)""", RegexOption.IGNORE_CASE)
                    .find(html)?.groupValues?.get(1).orEmpty()
            ),
            ""
        )
    }
    // Last of all, and only on a page that declared nothing: the figures the shop
    // formatted for its own scripts to render. Kept behind the emptiness check
    // rather than merely ordered after the rest, because appending inferred prices
    // to a declared one would turn every clean single-price page into a picker and
    // would give a later refresh a crowd of near-misses to snap its variant onto.
    if (found.isEmpty()) {
        inlinePriceOffers(html).forEach { add(it.price, it.label, it.currency) }
    }
    return found.values.toList()
}

/**
 * Finds the price on a product page.
 *
 * The first offer, which is the one from the most trustworthy source the page
 * had. Not the cheapest: a stray small number in some unrelated attribute would
 * then beat a correct price from structured data. Which variant to follow is a
 * choice the user makes once; this is only the answer before anyone has chosen.
 */
fun extractPrice(html: String): Double = extractOffers(html).firstOrNull()?.price ?: 0.0

/**
 * The outcome of looking for a followed variant on a page that has been re-read.
 *
 * Three outcomes, not two: a page that lost the variant is a different thing from
 * a page that lost its prices, and neither may quietly become "the price changed".
 */
sealed interface OfferMatch {
    data class Found(val offer: Offer) : OfferMatch

    /** The page still lists prices, but not the one being followed. */
    data object Missing : OfferMatch

    /** The page states no price at all: out of stock, redesigned, or gone. */
    data object None : OfferMatch
}

/**
 * Finds, among a page's offers, the one a wish is actually following.
 *
 * A name is the only anchor a page gives that survives a price change, so it wins
 * when there is one. Without a name the nearest price to the last reading is the
 * best available anchor — on a page of editions the gap between them is far wider
 * than any sale, so this keeps following the same line rather than jumping to a
 * cheaper edition the moment one is discounted.
 */
fun matchOffer(offers: List<Offer>, variant: String, lastPrice: Double): OfferMatch {
    if (offers.isEmpty()) return OfferMatch.None
    if (variant.isNotBlank()) {
        val named = offers.firstOrNull { it.label == variant }
        return if (named != null) OfferMatch.Found(named) else OfferMatch.Missing
    }
    if (offers.size == 1 || lastPrice <= 0) return OfferMatch.Found(offers.first())
    return OfferMatch.Found(offers.minByOrNull { kotlin.math.abs(it.price - lastPrice) }!!)
}

/**
 * Builds a wish from a product page. Throws when there is no price, because a
 * price tracker with no price to track is worse than a clear failure.
 */
fun parseProduct(
    html: String,
    url: String,
    id: String,
    today: Long = 0L,
    rate: FxRate = FxRate()
): Wish {
    val offers = extractOffers(html)
    require(offers.isNotEmpty()) { "Не вдалося знайти ціну на сторінці" }
    return wishFromOffer(html, url, id, offers.first(), today, rate)
}

/**
 * What a page turned out to be worth adding.
 *
 * Three outcomes, because the add flow used to have two and the missing one was
 * the common case at a shop that renders its price in the browser: a page read
 * perfectly well, stating what the thing is and what it looks like, and stating no
 * price. That is not the same event as a page nobody could read, and it does not
 * ask the same thing of the person — one wants the link checked, the other wants a
 * number typed.
 */
sealed interface PageAdd {
    data class Priced(val wish: Wish) : PageAdd

    /** Everything but the price. The wish is worth keeping; the figure is typed. */
    data class Described(val wish: Wish) : PageAdd

    /** No price, no name, no photograph: a listing page, or a wall. */
    data object Blank : PageAdd
}

/**
 * Reads a fetched page into whichever of the three outcomes it is.
 *
 * Pure, so that every one of the three can be tested against a saved page rather
 * than against whatever a shop happens to be serving today.
 */
fun readForAdd(
    html: String,
    url: String,
    id: String,
    today: Long = 0L,
    rate: FxRate = FxRate()
): PageAdd {
    val offers = extractOffers(html)
    if (offers.isNotEmpty()) {
        return PageAdd.Priced(wishFromOffer(html, url, id, offers.first(), today, rate))
    }
    val facts = pageFacts(html)
    if (!facts.describable) return PageAdd.Blank
    return PageAdd.Described(wishFromFacts(facts, url, id, 0.0, today))
}

/**
 * Builds a wish around one particular offer from a page.
 *
 * Separate from [parseProduct] because on a page with editions the price is not a
 * fact to be read but a choice to be made, and the choice has to be recorded with
 * the wish or every later check would have to guess again.
 */
fun wishFromOffer(
    html: String,
    url: String,
    id: String,
    offer: Offer,
    today: Long = 0L,
    rate: FxRate = FxRate()
): Wish {
    val converted = toHryvnia(offer.price, offer.currency, rate)
    val facts = pageFacts(html)
    // A page that declares the thing sold out states a figure that is not a price:
    // very often a placeholder, and very often lower than the real one. It is kept
    // and shown as the last known figure, because that is what the person is
    // watching for, but it may not start a history — a benchmark whose very first
    // point is a sold-out placeholder would report a rise on the day the thing
    // actually came back and could be bought.
    val blocked = blocksPrice(offer.availability)
    val state = when {
        converted.noRate -> Freshness.UNREADABLE
        blocked -> Freshness.OUT_OF_STOCK
        else -> Freshness.OK
    }
    return Wish(
        id = id,
        name = facts.name.ifBlank { "Новий товар" },
        url = url,
        image = facts.image,
        price = converted.uah,
        // A price nobody can convert is not a price to start a history with. The
        // wish is still worth keeping — the link is the part that cannot be typed
        // again — so it comes back the way an unreadable page does, and the card
        // offers the same way out of it.
        history = if (converted.noRate || blocked) {
            emptyList()
        } else {
            listOf(PricePoint(converted.uah, today, rate.sell, rate.source))
        },
        freshness = state,
        variant = offer.label,
        addedDay = today,
        about = facts.about,
        sources = listOf(
            WishSource(
                url = url,
                price = converted.uah,
                variant = offer.label,
                freshness = state,
                checkedDay = today,
                amount = converted.amount,
                currency = converted.currency,
                rate = converted.rate,
                availability = offer.availability
            )
        )
    )
}

/**
 * What a page said about the thing, when it would not say what it costs.
 *
 * A shop that renders its price behind JavaScript, or hides it behind a
 * challenge, still fills in its Open Graph tags: they decide how its link looks
 * when someone pastes it into a messenger, so they are the one part of the page
 * the shop cannot afford to leave out. The app used to read the title, read the
 * photograph, read the description — and throw all three away over the one
 * missing number.
 */
data class PageFacts(
    val name: String = "",
    val image: String = "",
    val about: ProductAbout = ProductAbout()
) {
    /**
     * Whether there is enough here to show a person what they are about to add.
     *
     * A name or a photograph is enough; the description alone is not, because a
     * row reading "Товар з temu.com" under a paragraph of marketing is not a
     * thing anybody would recognise a week later.
     */
    val describable: Boolean
        get() = name.isNotBlank() || image.isNotBlank()
}

/** Everything the page gave up apart from the price. */
fun pageFacts(html: String): PageFacts = PageFacts(
    name = cleanProductTitle(metaContent(html, "og:title")),
    image = decodeEntities(metaContent(html, "og:image")),
    about = extractAbout(html)
)

/**
 * How to name an offer in a list of them.
 *
 * The shop's own word for it where there is one. Where there is not — and there
 * usually is not, because the name sits in the page's layout rather than in its
 * data — the price has to identify the row by itself.
 */
fun offerLabel(offer: Offer, index: Int): String =
    offer.label.ifBlank { "Варіант ${index + 1}" }

/**
 * What to say beside an edition the page declared unbuyable, or null for the rest.
 *
 * Only the two states that are a warning get a line. A page that declared
 * `InStock` says so in the picker too — on a page where one edition is sold out,
 * silence on the others would read as the app having no opinion about them rather
 * than as the shop having said they are fine.
 *
 * The row stays choosable. Somebody looking at a sold-out edition is usually
 * looking at exactly the thing they want and are waiting for, and refusing to
 * track it would remove the one feature that tells them when it returns — so the
 * picker states the fact and lets them decide, and the wish it builds is marked
 * from its first day rather than starting out pretending to be a live price.
 */
fun offerStockLabel(offer: Offer): String? = when (offer.availability) {
    Availability.SOLD_OUT -> "немає в наявності"
    Availability.DISCONTINUED -> "знято з продажу"
    Availability.IN_STOCK -> "є в наявності"
    Availability.UNKNOWN -> null
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

/**
 * Every offer a JSON-LD document states, with the name the shop gave each.
 *
 * Unlike the single-price walk below, this keeps going after the first hit: a
 * page with editions or sizes carries them all in one offers array, and the name
 * beside each is the only label a page reliably provides.
 */
private fun jsonLdOffers(html: String): List<Offer> {
    val blocks = Regex(
        """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).findAll(html).map { it.groupValues[1] }

    val offers = mutableListOf<Offer>()
    blocks.forEach { block ->
        runCatching { collectOffers(block.trim(), offers, depth = 0) }
    }
    return offers
}

private fun collectOffers(raw: String, into: MutableList<Offer>, depth: Int) {
    val root: Any = when {
        raw.startsWith("[") -> JSONArray(raw)
        raw.startsWith("{") -> org.json.JSONObject(raw)
        else -> return
    }
    offersInNode(root, into, depth, label = "", currency = "", stock = Availability.UNKNOWN)
}

private fun offersInNode(
    node: Any?,
    into: MutableList<Offer>,
    depth: Int,
    label: String,
    currency: String,
    stock: Availability
) {
    if (depth > 6) return
    when (node) {
        is JSONArray -> (0 until node.length()).forEach {
            offersInNode(node.opt(it), into, depth + 1, label, currency, stock)
        }
        is org.json.JSONObject -> {
            // A node names itself, and that name belongs to any price directly on it.
            val own = node.optString("name").ifBlank { node.optString("sku") }.ifBlank { label }
            // Inherited the same way, because the currency is usually stated once on
            // the Product and the prices hang off the offers nested inside it.
            val money = currencyCode(node.optString("priceCurrency")).ifBlank { currency }
            // Availability rides along the same path and for the same reason: an
            // AggregateOffer commonly declares it once for the group, and the
            // individual offers nested under it state only their own price. A node
            // that does state its own always wins over what it inherited, which is
            // what lets one sold-out edition sit beside an available one.
            val here = availabilityFrom(node.optString("availability"))
            val stocked = if (here == Availability.UNKNOWN) stock else here
            listOf("price", "lowPrice", "highPrice").forEach { key ->
                if (node.has(key)) {
                    priceNumber(node.opt(key).toString())
                        ?.let { into.add(Offer(it, own, money, stocked)) }
                }
            }
            node.keys().forEach { key ->
                if (key !in setOf("price", "lowPrice", "highPrice")) {
                    offersInNode(node.opt(key), into, depth + 1, own, money, stocked)
                }
            }
        }
    }
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

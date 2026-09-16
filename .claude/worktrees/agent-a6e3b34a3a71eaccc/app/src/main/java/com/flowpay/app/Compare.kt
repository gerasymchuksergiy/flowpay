package com.flowpay.app

/**
 * Looking for a thing somewhere other than where it is being watched.
 *
 * The app can now say a thing cannot be bought — sold out, taken off sale, or on
 * a page that has stopped answering. The question that forms the instant it says
 * so is "well, who else has it", and until now the app had no answer to it: the
 * wishlist only knows the shops it was pointed at, and all of them have just gone
 * quiet at once.
 *
 * Hotline is Ukraine's price comparison site, so the answer is a search there. The
 * whole mechanism is a URL handed to the person's own browser. **Nothing here
 * fetches anything**, and that is deliberate rather than unfinished: hotline
 * renders its results in JavaScript and serves a plain client a page with no
 * products in it, there is no API, and a scraper aimed at it would be a network
 * call that could only ever return nothing. The browser runs the JavaScript. The
 * app's job is to build a good query and get out of the way.
 *
 * So the quality of this feature is entirely the quality of the query, which is
 * why [searchTerms] is the long part of this file and the short part of the screen.
 *
 * One site, not a list of them. If a second ever earns its place the shape to
 * reach for is [hotlineSearch] becoming one of several builders behind a picker —
 * but a second site today would turn one button into a menu, and a menu is a
 * question asked of someone who came here to be given an answer.
 */

/**
 * The search address, verified against the live site rather than guessed.
 *
 * The obvious spelling, `hotline.ua/ua/search/?q=`, is dead: it answers 200 with a
 * forty-byte body reading "Legacy home controller has been disabled", so a button
 * built on it would land on a blank page and look like the app's fault. This is
 * the form the site's own search box produces.
 */
const val HOTLINE_SEARCH = "https://hotline.ua/ua/sr/?q="

/**
 * A search address for these words.
 *
 * Percent-encoded rather than left with the `+` that [java.net.URLEncoder] writes
 * for a space. The site accepts both; this one survives being read aloud, pasted
 * into a message, or shown in a browser's address bar without looking like part of
 * the query.
 */
fun hotlineSearch(terms: String): String =
    HOTLINE_SEARCH + java.net.URLEncoder.encode(terms.trim(), "UTF-8").replace("+", "%20")

/**
 * How many words the query is allowed before the model number.
 *
 * Three, and the number is measured rather than chosen. A comparison site matches
 * a handful of tokens: "gamesir super nova" finds the thing, and the same title's
 * next two words — "multi platform" — find nothing at all. The same happened to
 * "ASICS Gel 1130 Dark Grey демісезонні", which finds nothing, against "ASICS Gel
 * 1130", which finds the shoe. Every extra word is a chance to be wrong and almost
 * never a chance to be right, because the brand and the first word after it are
 * already as specific as the thing gets.
 */
const val SEARCH_TERM_LIMIT = 3

/**
 * Words that are never part of what a thing is called.
 *
 * Two kinds only, both safe to drop anywhere: grammar, and the furniture shops put
 * in a page title to be found by search engines. Deliberately *not* a list of
 * category nouns and marketing adjectives — those are the words that actually
 * break a search, but there are thousands of them, a list of them would be
 * permanently half-finished, and half of them are somebody's brand. They are dealt
 * with by [searchTerms] structurally instead.
 */
private val SEARCH_STOPWORDS = setOf(
    "з", "із", "зі", "в", "у", "на", "та", "і", "й", "для", "від", "до", "по",
    "при", "під", "над", "без", "the", "and", "with", "for", "of",
    "купити", "купить", "ціна", "цена", "недорого", "доставка", "відгуки",
    "отзывы", "інтернет-магазин", "интернет-магазин", "магазин", "акція",
    "знижка", "розпродаж", "гарантія", "наявності",
    "києві", "киеве", "україні", "украине", "ukraine"
)

/**
 * The shop's tail on a page title, and where it starts.
 *
 * A spaced separator, which is the one thing every shop's suffix has in common:
 * " - Temu Ukraine", " | Rozetka", " — Цифрус". Spaced on purpose — a model number
 * is full of hyphens with no spaces around them, and WH-1000XM5 must survive.
 */
private val TITLE_TAIL = Regex("""\s+[|—–·•]\s+|\s+-\s+""")

// A word is letters and digits, keeping the punctuation that lives inside a model
// number — EC685.M, WH-1000XM5, USB-C — and dropping the punctuation between words.
private val SEARCH_TOKEN = Regex("""[\p{L}\p{N}][\p{L}\p{N}.+-]*""")

private fun isCyrillic(token: String): Boolean = token.any { it in 'Ѐ'..'ӿ' }

private fun hasLetter(token: String): Boolean = token.any { it.isLetter() }

private fun hasDigit(token: String): Boolean = token.any { it.isDigit() }

/**
 * What to type into a comparison site to find this thing.
 *
 * A shop page title is not a product name. It is a product name with a category in
 * front of it, a colour and a condition behind it, and the shop's own name after
 * that — «ігровий контролер gamesir super nova multi platform з hall - Temu
 * Ukraine» is one real example, and hotline finds nothing whatsoever for it. It
 * finds the thing immediately for the three words in the middle.
 *
 * Getting from one to the other is four rules, in order:
 *
 * 1. **Drop the shop's tail.** Everything after the first spaced separator.
 *
 * 2. **Drop grammar and shop furniture.** [SEARCH_STOPWORDS], which is short on
 *    purpose.
 *
 * 3. **Prefer the Latin run.** This is the rule that does the real work, and it is
 *    a fact about Ukrainian shop listings rather than a heuristic: the category,
 *    the adjectives and the condition are written in Cyrillic, and the brand and
 *    the model are written in Latin. So where a title mixes the two scripts, the
 *    longest unbroken run of non-Cyrillic words *is* the product's identity and
 *    everything else is description. «Навушники Sony WH-1000XM5 Black бездротові»
 *    reduces to three words without needing to know that «навушники» is a category
 *    or «бездротові» an adjective. A run has to be two words long and contain a
 *    letter somewhere to count, so that a lone foreign word in an otherwise
 *    Ukrainian name — «Кава Чорна Карта Gold» — does not become the whole query,
 *    and a title with no Latin in it at all is simply used as it stands.
 *
 * 4. **Keep it to [SEARCH_TERM_LIMIT] words, plus the model number.** If the word
 *    that falls just past the limit has a digit in it, it comes too: "Logitech MX
 *    Master" and "Logitech MX Master 3S" are different mice, and a model number is
 *    the one token worth more than the limit it breaks.
 *
 * [brand] goes in front when the page declared one, because it is the strongest
 * single token there is — except when it is the shop wearing a brand's clothes.
 * Temu publishes `"brand":"Temu"` on other people's products, and "Temu gamesir
 * super" searches a comparison site for a shop that is not on it. So a brand that
 * is a piece of [shop]'s own address is not a brand and is dropped.
 *
 * Comes back empty only for a name with nothing word-like in it at all; the caller
 * treats that as nothing to search for.
 */
fun searchTerms(name: String, brand: String = "", shop: String = ""): String {
    val head = TITLE_TAIL.split(cleanProductTitle(name)).firstOrNull()?.trim().orEmpty()
        .ifBlank { cleanProductTitle(name) }
    val words = SEARCH_TOKEN.findAll(head).map { it.value.trim('.', '-') }
        .filter { it.isNotBlank() }.toList()
    // Falling back to the unfiltered words rather than to nothing: a thing whose
    // whole name is stopwords is not a thing the app should refuse to search for.
    val kept = words.filter { it.lowercase() !in SEARCH_STOPWORDS }.ifEmpty { words }

    val runs = mutableListOf<List<String>>()
    var run = mutableListOf<String>()
    kept.forEach { word ->
        if (isCyrillic(word)) {
            if (run.isNotEmpty()) runs.add(run)
            run = mutableListOf()
        } else {
            run.add(word)
        }
    }
    if (run.isNotEmpty()) runs.add(run)
    // maxByOrNull keeps the first of equal-length runs, so a title that names the
    // thing before it describes it is read left to right, the way it was written.
    val latin = runs.filter { it.size >= 2 && it.any(::hasLetter) }.maxByOrNull { it.size }
    val identity = latin ?: kept

    val shopAddress = shop.lowercase()
    val brandWords = SEARCH_TOKEN.findAll(brand).map { it.value.trim('.', '-') }
        .filter { it.isNotBlank() && it.lowercase() !in SEARCH_STOPWORDS }
        .filterNot { shopAddress.isNotBlank() && shopAddress.contains(it.lowercase()) }
        .toList()

    val ordered = mutableListOf<String>()
    (brandWords + identity).forEach { word ->
        if (ordered.none { it.equals(word, ignoreCase = true) }) ordered.add(word)
    }

    val chosen = ordered.take(SEARCH_TERM_LIMIT).toMutableList()
    ordered.getOrNull(SEARCH_TERM_LIMIT)?.takeIf(::hasDigit)?.let { chosen.add(it) }
    return chosen.joinToString(" ")
}

/** The query the app would build for this wish, before the person has a say. */
fun builtSearchTerms(wish: Wish): String =
    searchTerms(wish.name, wish.about.brand, sourceName(wish.url))

/**
 * The query this wish will actually be searched with.
 *
 * A query the person corrected wins over a query the app worked out, which is the
 * whole reason [Wish.searchQuery] is stored. The stored one is empty on every wish
 * nobody has corrected — so improving [searchTerms] later reaches all of them, and
 * reaches none of the ones where the person has already said what they want.
 */
fun wishSearchTerms(wish: Wish): String =
    wish.searchQuery.trim().ifBlank { builtSearchTerms(wish) }

/**
 * Whether this wish has nowhere left to be bought.
 *
 * Not "is something wrong with it" — that is [isStale], and it is a wider net than
 * this one on purpose. The three states differ in what the person should do next:
 *
 * - [Freshness.OUT_OF_STOCK] is the case asked for. The shop has the page and has
 *   said outright that the thing is not on the shelf, or has stopped listing the
 *   edition being followed. Either way nobody can buy it here today.
 * - [Freshness.GONE] is included, and the argument is that a 404 is not a
 *   different kind of news. The page has gone, the app will never learn anything
 *   about this thing from this address again, and the only remaining move is the
 *   same one: find it somewhere else. Calling that "a broken link" rather than
 *   "you cannot buy this here" would be the app describing its own plumbing
 *   instead of the person's problem.
 * - [Freshness.UNREADABLE] is **not** included, and that is the line. A page that
 *   answered without a price the app could find is a failure of the reading, not
 *   of the shelf — the shop is almost certainly still selling the thing, and the
 *   fix already exists and is a price typed in by hand. Offering to go looking
 *   elsewhere there would be the app mistaking its own parser for an empty shop.
 *
 * And none of it applies while any watched shop still has a price, whatever the
 * others are doing: a thing one shop has sold out of and another has in a box is a
 * thing that can be bought.
 */
fun cannotBeBought(wish: Wish): Boolean {
    if (bestSource(wishSources(wish)) != null) return false
    return wish.freshness == Freshness.OUT_OF_STOCK || wish.freshness == Freshness.GONE
}

/** Whether to offer the search at all: there is a need, and something to type. */
fun worthComparing(wish: Wish): Boolean =
    cannotBeBought(wish) && wishSearchTerms(wish).isNotBlank()

/**
 * The one line above the search, in the terms of what actually happened.
 *
 * Three sentences rather than one, because the news they follow is different and
 * the person has already read it: repeating "немає в наявності" under a warning
 * saying "немає в наявності" spends a line saying nothing. What each says instead
 * is what is worth doing about it, and that genuinely differs — a thing that is
 * out of stock may well come back to the shop already being watched, and a thing
 * taken off sale or on a dead page will not.
 */
fun compareNote(wish: Wish): String = when {
    wish.freshness == Freshness.GONE ->
        "Ця сторінка зникла. Пошукайте товар в інших магазинах."
    wishSources(wish).any { it.availability == Availability.DISCONTINUED } ->
        "Цей магазин більше його не продає. Пошукайте в інших."
    else -> "Тут його зараз не купити. Подивіться, у кого він є."
}

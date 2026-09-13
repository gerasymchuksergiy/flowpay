package com.flowpay.app

import java.text.Collator

/**
 * Finding one wish among thirty: categories, search and the totals behind them.
 *
 * A wishlist is the one list in this app that only grows. Ordering it was already
 * possible; narrowing it was not, so the only way back to the headphones added in
 * spring was to scroll past everything wanted since. Everything here exists to cut
 * the grid down to what is being looked for.
 */

/** The bucket a wish falls into when nothing better was typed. */
const val OTHER_CATEGORY = "Інше"

/**
 * The apostrophes that all mean the same thing in Ukrainian.
 *
 * A shop writes "П'ятниця" with whichever mark its CMS produced and the phone
 * keyboard produces another, so two spellings of one word would never match each
 * other. This is the whole of the folding done here — і, и and ї are separate
 * letters of the alphabet, not decorated versions of one, and treating them as
 * interchangeable would make "мій" find "мий" and call that a feature.
 */
private const val APOSTROPHES = "’ʼ‘´`"

private val WHITESPACE = Regex("\\s+")

/**
 * The form of a string that comparisons happen in.
 *
 * Lower-cased against Ukrainian explicitly rather than the phone's default: on a
 * Turkish-locale phone `"Iphone".lowercase()` is "ıphone", so a search for
 * "iphone" would find nothing on a list that worked yesterday.
 */
fun searchKey(raw: String): String {
    val folded = StringBuilder(raw.length)
    raw.forEach { char -> folded.append(if (char in APOSTROPHES) '\'' else char) }
    return folded.toString().lowercase(UK).trim()
}

/** The key two spellings of one category share: this is what stops the splitting. */
fun categoryKey(raw: String): String = searchKey(raw).replace(WHITESPACE, " ")

/** The spelling a category is shown in: trimmed, single-spaced, never empty. */
fun categoryName(raw: String): String =
    raw.trim().replace(WHITESPACE, " ").ifBlank { OTHER_CATEGORY }

/**
 * Every category the list actually uses, each under a single spelling.
 *
 * Where "Техніка" and "техніка" both occur the more common one wins, because the
 * odd one out is nearly always the slip. [OTHER_CATEGORY] is held back to the end
 * however it sorts: it is the absence of a category rather than one of them, and a
 * catch-all sitting between two real names invites being tapped by mistake.
 */
fun knownCategories(items: List<Wish>): List<String> {
    val spellings = items.map { categoryName(it.category) }
    val names = spellings.groupBy(::categoryKey).values.map { group ->
        group.groupingBy { it }.eachCount().entries
            .maxByOrNull { it.value }
            ?.key
            ?: group.first()
    }
    val order = Collator.getInstance(UK)
    return names.sortedWith(
        compareBy<String> { categoryKey(it) == categoryKey(OTHER_CATEGORY) }
            .thenComparator { a, b -> order.compare(a, b) }
    )
}

/**
 * The spelling to save for a category someone has just typed.
 *
 * Free text is the right field here — a fixed list would be wrong about this
 * person's things inside a week — but free text alone means every stray capital
 * starts a new category. Matching what is already in use keeps the freedom and
 * loses the duplicates.
 */
fun canonicalCategory(typed: String, known: List<String>): String {
    val name = categoryName(typed)
    val key = categoryKey(name)
    return known.firstOrNull { categoryKey(it) == key } ?: name
}

/**
 * Whether a wish answers to what was typed in the search box.
 *
 * Every word has to appear somewhere in the name, in any order, so "навушники
 * sony" finds "Sony WH-1000XM5 бездротові навушники". Demanding the words in the
 * typed order would mean knowing how the shop chose to word its own title.
 */
fun matchesSearch(wish: Wish, query: String): Boolean {
    val words = searchKey(query).split(WHITESPACE).filter { it.isNotEmpty() }
    if (words.isEmpty()) return true
    val name = searchKey(wish.name)
    return words.all { name.contains(it) }
}

/** The grid's contents: everything, narrowed by the chosen chip and the search. */
fun filterWishes(items: List<Wish>, category: String?, query: String): List<Wish> {
    val key = category?.let(::categoryKey)
    return items.filter { wish ->
        (key == null || categoryKey(wish.category) == key) && matchesSearch(wish, query)
    }
}

/** What one category is holding: how many things, and what they add up to. */
data class CategoryTotal(
    val name: String,
    val count: Int,
    /** Summed over [wishGoal], so a target counts for what it asks rather than today's price. */
    val total: Double
)

/**
 * Money per category, in the order the chips are shown in.
 *
 * Held wishes count. A hold postpones the decision, not the price tag, and a
 * category that looked cheap because half of it was set aside would be lying about
 * the one thing this figure exists to say.
 */
fun categoryTotals(items: List<Wish>): List<CategoryTotal> =
    knownCategories(items).map { name ->
        val key = categoryKey(name)
        val group = items.filter { categoryKey(it.category) == key }
        CategoryTotal(name, group.size, group.sumOf { wishGoal(it) })
    }

/** What the whole list adds up to, for the chip that selects all of it. */
fun allCategoriesTotal(items: List<Wish>): Double = items.sumOf { wishGoal(it) }

/** A single chip is not a choice, so the row only earns its height at two. */
fun showsCategoryRow(items: List<Wish>): Boolean = knownCategories(items).size > 1

/**
 * Why the grid is empty, when the list behind it is not.
 *
 * A filter that matches nothing looks identical to a list with nothing in it, and
 * the second one is alarming. Naming the search or the category says which of the
 * two just happened, and therefore what to undo.
 */
fun browseEmptyNote(query: String, category: String?): String = when {
    query.isNotBlank() -> "Нічого не знайшлося за запитом «${query.trim()}»"
    category != null -> "У категорії «${categoryName(category)}» поки порожньо"
    else -> "Нічого не знайшлося"
}

/**
 * Wishes by name, in Ukrainian alphabetical order.
 *
 * A [Collator] rather than plain string comparison, because і, и and ї sit at
 * settled places in the alphabet and their code points do not agree about where.
 */
fun wishNameOrder(): Comparator<Wish> {
    val order = Collator.getInstance(UK)
    return Comparator { a, b -> order.compare(a.name, b.name) }
}

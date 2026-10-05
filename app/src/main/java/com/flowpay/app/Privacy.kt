package com.flowpay.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Hiding sums: where other people see the screen, the amounts give way to «•••»
 * while names, dates, emoji and percentages stay. Research «Без сум» / «Сховати
 * суми» (YNAB, monobank); the owner said «додай все» on 4 October 2026.
 *
 * Three places, three switches, one rule for what a sum looks like:
 *
 * - **The recap as a picture** — «Без сум» on the deck. A card that is nothing but
 *   a sum is left out rather than shown as «•••» ([recapWithoutSums]).
 * - **The widget and the Quick Settings tile** — «Ховати суми поза застосунком»:
 *   «Інтернет · завтра», «Вільно: є». The shade opens on a locked phone.
 * - **The app's own screens** — the eye on the Огляд hero ([SumsMask]). Only
 *   personal sums: income, free money, savings, payments, what a purchase cost.
 *   A shop's price and the dollar rate are not secret and stay.
 *
 * The mask is put on at the screen, never inside [money] or [bareAmount]: those
 * also write the morning message, the price alerts and every test.
 */

/** What a hidden sum shows. */
const val SUM_MASK = "•••"

private const val GAP = "[ \\u00A0\\u202F]"

/** A figure as the app writes one: «1 200», «2 203,24», «41,6», «9.99». */
private const val NUMBER = "(?<![\\d,.])(?:\\d{1,3}(?:$GAP\\d{3})+|\\d+)(?:[,.]\\d+)?"

/**
 * The ISO letters of every currency the phone knows — a monobank account in
 * złoty reads «1 234,56 PLN» — and only those: «iPhone 16 PRO» is a name.
 */
private val CODES = java.util.Currency.getAvailableCurrencies().map { it.currencyCode }.sorted().joinToString("|")

/**
 * What makes a figure money: a currency — a sign, «грн», or a currency's letters
 * ([CODES]) — or «тис» from [shortMoney].
 */
private val UNIT = "₴|\\$|€|грн\\.?|(?:$CODES)(?![A-Za-z])|тис\\.?(?:$GAP*₴)?"

/** A figure followed by what makes it money. */
private val AMOUNT = Regex("$NUMBER($GAP*)($UNIT)")

/**
 * The bare first half of «2 140 з 6 400 ₴» — what a fund holds of its goal — and
 * of «покрив 800 з 1 199 ₴». Only when the second half is money: «платіж 3 з 6»
 * and «6 з 30 днів» are counts and stay.
 */
private val BEFORE_OF = Regex("$NUMBER(?=$GAP+з$GAP+[~≈]?$GAP?$NUMBER$GAP*(?:$UNIT))")

/** «$12» — a currency in front, the way some letters write it. */
private val DOLLAR_FIRST = Regex("\\$$GAP?$NUMBER")

/** The bare left side of «було 199 → стало 249 ₴»: a figure right before an arrow. */
private val BEFORE_ARROW = Regex("$NUMBER(?=$GAP*→)")

/**
 * Every amount of money in [text] as «•••», everything else as it was.
 *
 * «Завтра дощ: Netflix, ~249 ₴» → «Завтра дощ: Netflix, ~••• ₴». The currency is
 * kept — it says a sum was here, so the line does not read as broken — except
 * after «тис», which would still tell the size. Percentages, counts, days and
 * dates have no currency after them and are left alone.
 */
fun maskSums(text: String): String {
    // The bare halves first, while the money beside them still reads as money.
    val pairs = BEFORE_OF.replace(text, SUM_MASK)
    val amounts = AMOUNT.replace(pairs) { match ->
        val unit = match.groupValues[2]
        if (unit.startsWith("тис")) SUM_MASK else SUM_MASK + match.groupValues[1] + unit
    }
    return BEFORE_ARROW.replace(DOLLAR_FIRST.replace(amounts) { "$$SUM_MASK" }, SUM_MASK)
}

/**
 * A figure standing on its own — a hero, a tile — hidden whole.
 *
 * The same masking as a sentence where it finds a sum; any other figure that is
 * still digits is replaced outright, because on a figure-only line a missed
 * format would be the one sum left on the screen.
 */
fun maskFigure(text: String): String {
    val masked = maskSums(text)
    return if (masked != text || text.none { it.isDigit() }) masked else SUM_MASK
}

/** Nothing left but masks, currencies and punctuation: the line said a sum and only that. */
fun onlyMasks(text: String): Boolean =
    text.replace(SUM_MASK, "").none { it.isLetterOrDigit() }

// ------------------------------------------------------------ the recap picture

/**
 * One card with its sums hidden, or null when the card is nothing but a sum.
 *
 * A headline that carried an amount is the card's whole point («На 1 200 ₴
 * більше», «Лишається 9 000 ₴», «300 $»), so «•••» there would be a card about
 * nothing: it is left out. A detail that was only an amount is dropped; one that
 * says more keeps its words with «•••» in place of the figures.
 */
fun recapCardWithoutSums(card: RecapCard): RecapCard? {
    if (maskSums(card.headline) != card.headline) return null
    val detail = maskSums(card.detail)
    return card.copy(
        overline = maskSums(card.overline),
        detail = if (detail != card.detail && onlyMasks(detail)) "" else detail
    )
}

/** The deck for «Без сум»: the same order, the sum-only cards left out. */
fun recapWithoutSums(recap: Recap): Recap =
    recap.copy(cards = recap.cards.mapNotNull(::recapCardWithoutSums))

// ------------------------------------------------------------ outside the app

/** «Вільно: є» — the month's answer without its figure. */
fun hiddenFreeLine(month: Budget): String = when {
    month.unknown -> "Дохід не вказано"
    month.overspent -> "Бракує до кінця місяця"
    month.free > 0.0 -> "Вільно: є"
    else -> "Вільно: немає"
}

/** The widget's three lines with names and dates only: «Інтернет · завтра», «Вільно: є». */
fun widgetWithoutSums(summary: WidgetSummary, month: Budget): WidgetSummary =
    summary.copy(paymentAmount = "", freeCash = hiddenFreeLine(month))

// ------------------------------------------------------------ the app's own screens

/**
 * The eye on the Огляд hero: whether personal sums on the app's screens are
 * hidden. Read once at launch from `tc_hide_in` (MainActivity) and kept until it
 * is switched off; snapshot state, so every screen redraws when it changes.
 */
object SumsMask {
    var on by mutableStateOf(false)
        private set

    fun set(value: Boolean) {
        on = value
    }
}

/**
 * A line holding a personal sum, as the screen may show it. Put at the screen —
 * the line is built by the same helpers whichever way the eye looks.
 */
@Composable
@ReadOnlyComposable
fun personal(text: String): String = if (SumsMask.on) maskSums(text) else text

/** A figure that is a personal sum, as the screen may show it. */
@Composable
@ReadOnlyComposable
fun personalFigure(text: String): String = if (SumsMask.on) maskFigure(text) else text

/**
 * The same, for a line said once rather than drawn — a snackbar, from a tap
 * handler where a composable cannot be called: «Фонд покрив …», «Записано:
 * відкладено …».
 */
fun personalNow(text: String): String = if (SumsMask.on) maskSums(text) else text

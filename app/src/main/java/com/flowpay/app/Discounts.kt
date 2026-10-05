package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/**
 * The shop's own "discount", checked against what the app actually saw.
 *
 * Ahead of every Black Friday the same thing happens: a price goes up for a few
 * weeks, and comes back down with a crossed-out figure beside it. An EU sweep in
 * March 2026 found a third of the traders it checked quoting a discount against
 * the wrong reference; Ekonomichna Pravda found the average Black Friday "−14,5%"
 * on hotline to be about −3,5% once the inflated starting prices were taken out.
 *
 * Ukrainian law 3153-IX defines the honest reference as the lowest price of the
 * previous thirty days — the EU rule — but it is not in force while martial law
 * lasts. So the app does not quote the law at a shop. It states the two figures
 * side by side: what the page claims, and the lowest price its own checks recorded
 * over the same thirty days, "за перевірками FlowPay". The checks run twice a day,
 * so a low that lasted a few hours can be missed — which is why it says what it
 * saw rather than what was true.
 */

/** Below this the claim is noise: a "discount" of under one per cent. */
private const val LIST_PRICE_FLOOR = 1.01

/**
 * Above this the "was" figure is not a price at all: minor units, a bundle, a
 * different product's figure that happened to match a key.
 */
private const val LIST_PRICE_CEILING = 5.0

/** How long a history has to be before the claim can be checked. */
const val DISCOUNT_WINDOW_DAYS = 30

// Keys shops use in their own scripts for the crossed-out figure. Value quoted or
// not; the plausibility check against the real price is what keeps a bare id or a
// figure in kopecks out.
private val OLD_PRICE_KEY = Regex(
    """"(?:oldPrice|old_price|priceOld|price_old|regularPrice|regular_price|listPrice|list_price|""" +
        """originalPrice|original_price|crossedPrice|crossed_price|strikePrice|strike_price)"""" +
        """\s*:\s*"?([0-9][0-9\s.,]*)"?"""
)

/**
 * The crossed-out price a page declares, in its own money, or nought.
 *
 * In the order of how deliberately a shop states it: schema.org's
 * `priceSpecification` with a StrikethroughPrice or ListPrice type, which is what
 * Google's merchant listings read; a meta tag; then the shop's own script keys.
 */
fun declaredListPrice(html: String): Double {
    Regex(
        """<script[^>]+type=["']application/ld\+json["'][^>]*>(.*?)</script>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    ).findAll(html).map { it.groupValues[1].trim() }.forEach { block ->
        val root: Any = runCatching {
            when {
                block.startsWith("[") -> JSONArray(block)
                block.startsWith("{") -> JSONObject(block)
                else -> null
            }
        }.getOrNull() ?: return@forEach
        strikethroughIn(root, 0).takeIf { it > 0.0 }?.let { return it }
    }
    listOf("product:original_price:amount", "og:price:standard_amount").forEach { key ->
        priceNumber(metaContent(html, key))?.let { return it }
    }
    OLD_PRICE_KEY.find(html)?.groupValues?.get(1)?.let { raw ->
        priceNumber(raw)?.let { return it }
    }
    return 0.0
}

private fun strikethroughIn(node: Any?, depth: Int): Double {
    if (depth > 8) return 0.0
    return when (node) {
        is JSONArray -> (0 until node.length()).firstNotNullOfOrNull { index ->
            strikethroughIn(node.opt(index), depth + 1).takeIf { it > 0.0 }
        } ?: 0.0
        is JSONObject -> {
            val type = node.optString("priceType")
            if (type.contains("Strikethrough", ignoreCase = true) || type.contains("ListPrice", ignoreCase = true)) {
                priceNumber(node.opt("price")?.toString().orEmpty()) ?: 0.0
            } else {
                node.keys().asSequence()
                    .filter { it != "review" && it != "reviews" }
                    .firstNotNullOfOrNull { key ->
                        strikethroughIn(node.opt(key), depth + 1).takeIf { it > 0.0 }
                    } ?: 0.0
            }
        }
        else -> 0.0
    }
}

/**
 * The crossed-out price for this offer, in hryvnia, or nought.
 *
 * Kept only when it is plausibly a former price of this very thing: above the
 * price by more than a rounding, and not five times it.
 */
fun listPriceIn(html: String, offer: Offer, rate: FxRate): Double {
    val claimed = declaredListPrice(html)
    if (claimed <= 0.0 || offer.price <= 0.0) return 0.0
    if (claimed < offer.price * LIST_PRICE_FLOOR || claimed > offer.price * LIST_PRICE_CEILING) return 0.0
    val converted = toHryvnia(claimed, offer.currency, rate)
    return if (converted.noRate) 0.0 else converted.uah
}

/**
 * The lowest price of the thirty days before the current one took effect, or null.
 *
 * Before it, not including it: the EU rule measures a reduction against the lowest
 * price of the thirty days *preceding* it, and a window that contains the new price
 * would always find the new price to be its own low. Null when the app was not
 * watching for the whole of those thirty days — then it cannot say.
 */
fun lowBeforeCurrent(history: List<PricePoint>, current: Double, today: Long): Double? {
    val dated = history.filter { it.price > 0.0 && it.day > 0L }.sortedBy { it.day }
    if (dated.isEmpty() || current <= 0.0) return null
    // When the standing price began: the last recorded change, if it is this price.
    val start = if (dated.last().price == current) dated.last().day else today
    val before = dated.filter { it.day < start }
    if (before.isEmpty()) return null
    val windowStart = start - DISCOUNT_WINDOW_DAYS
    if (before.first().day > windowStart) return null
    val standingThen = before.lastOrNull { it.day <= windowStart }
    val inside = before.filter { it.day > windowStart }
    return (listOfNotNull(standingThen) + inside).minOf { it.price }
}

/**
 * The shop's claim and the app's own record, in one or two sentences, or null.
 *
 * Null when the page claims nothing. When there is too little history to check the
 * claim, it says so rather than nothing — a crossed-out figure with no comment beside
 * it reads as one the app has looked at and accepted.
 */
fun shopDiscountNote(listPrice: Double, current: Double, history: List<PricePoint>, today: Long): String? {
    if (listPrice <= 0.0 || current <= 0.0 || listPrice < current * LIST_PRICE_FLOOR) return null
    val claimed = (listPrice - current) / listPrice * 100
    val claim = "Магазин пише −${figure(claimed, 0)}% від ${money(listPrice)}."
    val low = lowBeforeCurrent(history, current, today)
        ?: return "$claim Перевірити поки не можу: чесне порівняння — з найнижчою ціною " +
            "за $DISCOUNT_WINDOW_DAYS днів до знижки, а FlowPay стежив коротше."
    return when {
        current < low -> {
            val real = (low - current) / low * 100
            "$claim Найнижча за $DISCOUNT_WINDOW_DAYS днів до цього за перевірками FlowPay — " +
                "${money(low)}, тож справжня знижка −${figure(real, 0)}%."
        }
        current == low ->
            "$claim Але стільки ж — ${money(low)} — ця річ уже коштувала протягом " +
                "$DISCOUNT_WINDOW_DAYS днів до того за перевірками FlowPay: дешевше не стало."
        else ->
            "$claim Але за $DISCOUNT_WINDOW_DAYS днів до цього за перевірками FlowPay вона вже була " +
                "${money(low)} — зараз на ${figure((current - low) / low * 100, 0)}% дорожче за той мінімум."
    }
}

/** Whether the note above found the claimed discount to be a real one. */
fun discountIsReal(listPrice: Double, current: Double, history: List<PricePoint>, today: Long): Boolean {
    if (listPrice < current * LIST_PRICE_FLOOR) return false
    val low = lowBeforeCurrent(history, current, today) ?: return false
    return current < low
}

/** Black Friday: the fourth Friday of November, which is the day Ukrainian shops use too. */
fun blackFriday(year: Int): LocalDate =
    LocalDate.of(year, 11, 1).with(TemporalAdjusters.dayOfWeekInMonth(4, DayOfWeek.FRIDAY))

/**
 * A word about Black Friday while it is still useful, or null.
 *
 * From the 1st of October to the day itself, and only for a wish that will not have
 * thirty days of history by then — which is the one thing that can still be fixed
 * by acting now, by keeping the wish rather than adding it the week before.
 */
fun blackFridayNote(today: LocalDate, daysTracked: Int): String? {
    val day = blackFriday(today.year)
    if (today.isBefore(LocalDate.of(today.year, 10, 1)) || today.isAfter(day)) return null
    val untilThen = java.time.temporal.ChronoUnit.DAYS.between(today, day).toInt()
    val atThen = daysTracked + untilThen
    if (atThen >= DISCOUNT_WINDOW_DAYS) return null
    return "До Чорної п'ятниці (${dayMonth(day)}) FlowPay бачитиме цю ціну лише ${daysLabel(atThen)} — " +
        "менше за $DISCOUNT_WINDOW_DAYS, яких треба, щоб перевірити знижку."
}

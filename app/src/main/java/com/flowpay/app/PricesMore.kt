package com.flowpay.app

/**
 * The second pass over the wishlist's prices, from the ten-app research of
 * 4 October 2026 («додай все»). Every part is a plain function over plain data;
 * the drawing is in PricesUi.kt and the background work in PriceWorker.kt.
 */

// ------------------------------------------------------------ the Rozetka card

/**
 * The programme Rozetka names its card's prices with, as it writes it in the page:
 * `"validForMemberTier": {"@id": "https://rozetka.com.ua/#rozetka-card"}`.
 */
const val ROZETKA_CARD_TIER = "rozetka-card"

fun isRozetkaCard(tier: String): Boolean = tier.contains(ROZETKA_CARD_TIER, ignoreCase = true)

/**
 * The price with the Rozetka card, in hryvnia, or nought.
 *
 * Nought unless the owner has said they hold the card (Налаштування → «У мене є
 * Картка Rozetka»): a finance app has no business advertising a shop's card to
 * someone without one, so without the setting the figure is read and kept but never
 * shown and never counted. Among several shops the cheapest card price of the ones
 * that answered, which in practice is the one Rozetka row.
 */
fun cardPrice(wish: Wish, hasCard: Boolean): Double {
    if (!hasCard) return 0.0
    return wishSources(wish)
        .filter {
            it.freshness == Freshness.OK && it.price > 0.0 &&
                it.memberPrice > 0.0 && isRozetkaCard(it.memberTier)
        }
        .minOfOrNull { it.memberPrice } ?: 0.0
}

/**
 * The price the target is measured against: the card's where it is lower.
 *
 * Only the target. The chart, the history, the verdicts and every other figure stay
 * on the price anybody pays — the card is a condition of one buyer, not the price of
 * the thing.
 */
fun targetBasis(wish: Wish, hasCard: Boolean): Double {
    val card = cardPrice(wish, hasCard)
    return if (card > 0.0 && card < wish.price) card else wish.price
}

/**
 * The dim line under the price: «1 519 ₴ з Карткою Rozetka», or null.
 *
 * Said only where it is cheaper than the price above it — on a wish whose other
 * shop is already below the card price the line would be a figure to ignore — and
 * never on a stale wish, whose price is not one anybody can pay today. When the
 * card reaches a target the ordinary price has not, the line says so.
 */
fun cardLine(wish: Wish, hasCard: Boolean): String? {
    if (isStale(wish.freshness)) return null
    val card = cardPrice(wish, hasCard)
    if (card <= 0.0 || card >= wish.price) return null
    val reached = wish.targetPrice > 0.0 && card <= wish.targetPrice && wish.price > wish.targetPrice
    return "${money(card)} з Карткою Rozetka" + if (reached) " — у межах цілі" else ""
}

/**
 * Whether this reading brought the target within reach with the card, while the
 * ordinary price is still above it.
 *
 * The ordinary crossing is [priceAlertFor]'s and keeps its own wording; this is the
 * other one, and it fires once — on the reading that crosses, measured from the
 * figure the target was last measured against ([targetBasis]), card included.
 */
fun cardTargetReached(previous: Wish, current: Wish, hasCard: Boolean): Boolean {
    val target = previous.targetPrice
    if (!hasCard || target <= 0.0) return false
    if (current.price <= target) return false
    val card = cardPrice(current, true)
    if (card <= 0.0 || card > target) return false
    val before = targetBasis(previous, true)
    return before <= 0.0 || before > target
}

/** The push for [cardTargetReached], saying which price reached it and which did not. */
fun cardTargetText(target: Double, card: Double, regular: Double): String =
    "Досягнуто ціль ${money(target)} — ${money(card)} при оплаті Карткою Rozetka " +
        "(звичайна ${money(regular)})"

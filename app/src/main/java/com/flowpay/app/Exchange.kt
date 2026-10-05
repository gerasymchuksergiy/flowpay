package com.flowpay.app

/**
 * Buying and selling dollars, as the rate screen asks it.
 *
 * The owner asked for the screen to say both sides — «і продаж, і покупку
 * валюти» — because a bank's two figures answer two different questions: what a
 * dollar costs you when you buy one (the bank's *sell* rate), and what you get for
 * one when you sell it (the bank's *buy* rate). The converter used to be a single
 * «UAH → USD» switch that quietly picked between them; now the deal is named and
 * the rate it uses follows from it.
 */
enum class Deal {
    /** You buy dollars: the bank sells, at [FxRate.sell]. */
    BUY,

    /** You sell dollars: the bank buys, at [FxRate.buy]. */
    SELL
}

/** One conversion: the rate it used and what it came to. */
data class Exchange(
    /** Nought when no rate has loaded. */
    val rate: Double,
    /** What the amount comes to, in the other currency. Nought when it cannot be worked out. */
    val result: Double,
    /** True when [result] is hryvnias, false when it is dollars. */
    val resultInUah: Boolean
)

/** The bank's rate for [deal]. */
fun dealRate(deal: Deal, rate: FxRate): Double = if (deal == Deal.BUY) rate.sell else rate.buy

/**
 * [amount] converted for [deal]. Typed in dollars it gives hryvnias — what buying
 * costs, what selling brings — and typed in hryvnias it gives dollars: how many a
 * sum buys, or how many must be sold to raise it.
 */
fun exchange(deal: Deal, amountInUah: Boolean, amount: Double, rate: FxRate): Exchange {
    val used = dealRate(deal, rate)
    if (used <= 0.0 || amount <= 0.0 || !amount.isFinite()) return Exchange(used, 0.0, !amountInUah)
    return if (amountInUah) {
        Exchange(used, amount / used, resultInUah = false)
    } else {
        Exchange(used, amount * used, resultInUah = true)
    }
}

/** What the result panel is called, so its figure cannot be read the wrong way round. */
fun exchangeResultLabel(deal: Deal, amountInUah: Boolean): String = when (deal) {
    Deal.BUY -> if (amountInUah) "Купите доларів" else "Заплатите"
    Deal.SELL -> if (amountInUah) "Треба продати доларів" else "Отримаєте"
}

/** The amount field's label. */
fun exchangeFieldLabel(amountInUah: Boolean): String =
    if (amountInUah) "Сума у гривнях" else "Сума у доларах"

/**
 * What the bank keeps between its two figures, per dollar. Null for the NBU's
 * single official rate, which has no two sides, and while nothing has loaded.
 */
fun spreadLine(rate: FxRate): String? {
    if (rate.source == SOURCE_NBU || rate.buy <= 0.0 || rate.sell <= rate.buy) return null
    return "Різниця ${rateFigure(rate.sell - rate.buy)} ₴ на кожному доларі"
}

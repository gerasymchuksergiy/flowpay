package com.flowpay.app

import android.content.Context
import androidx.core.content.edit

/**
 * The preferences the second pass over prices added, kept beside [Store] rather than
 * inside it.
 *
 * The same `flowpay` file, so there is still one place the phone keeps its state,
 * but its own class: five builders added to the app on 4 October at once, and every
 * one of them adding methods to the end of [Store] would have been five edits to the
 * same lines. Every key here starts with `wp_` for the same reason.
 *
 * None of this is app data in the sense of §7.2 — each is a standing instruction to
 * this phone or a memory of what it has already said — so none of it is in
 * [Store.exportJson], for the reasons the rate target and the recap mark are not.
 */
class PriceStore(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    /** «У мене є Картка Rozetka»: whether the card's prices are shown and counted. */
    fun rozetkaCard(): Boolean = prefs.getBoolean("wp_rozetka_card", false)

    fun saveRozetkaCard(on: Boolean) = prefs.edit { putBoolean("wp_rozetka_card", on) }

    /** Each bound market's low as the last morning message saw it — see [marketTargetLines]. */
    fun digestMarket(): Map<String, Double> = runCatching {
        val o = org.json.JSONObject(prefs.getString("wp_digest_market", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optDouble(it, 0.0) }
    }.getOrDefault(emptyMap())

    fun saveDigestMarket(lows: Map<String, Double>) = prefs.edit {
        putString(
            "wp_digest_market",
            org.json.JSONObject().apply { lows.forEach { (id, low) -> put(id, low) } }.toString()
        )
    }
}

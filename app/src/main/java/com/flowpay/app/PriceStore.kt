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
}

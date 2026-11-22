package com.flowpay.app

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import java.time.LocalDate

/**
 * The Android half of PaymentsLife.kt: what the morning message has already said
 * once, and the sweep that applies a day passing to the stored list.
 *
 * The said-keys live in the main `flowpay` preferences under `pl_said`. They are a
 * record of what this phone has told its owner, not app data, so — like the
 * dismissed pill and the recap mark — they stay out of the backup: restoring a
 * year-old file must not make the morning repeat itself.
 */
class LifeMemory(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    /** Keys of the say-once lines already said ([OnceLine.key]). */
    fun said(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_SAID, "[]"))
        (0 until array.length()).map { array.optString(it) }.filter { it.isNotBlank() }.toSet()
    }.getOrDefault(emptySet())

    /** Adds [keys] to what was said, keeping the newest [SAID_KEPT]. */
    fun markSaid(keys: Collection<String>) {
        if (keys.isEmpty()) return
        val all = rememberSaid(said(), keys)
        prefs.edit { putString(KEY_SAID, JSONArray(all).toString()) }
    }

    private companion object {
        const val KEY_SAID = "pl_said"
    }
}

/**
 * Writes every promo that has run out into its payment's history — what the
 * morning worker does before it builds the message, so the «150 → 300» line can
 * be said the morning after even if the app was not opened. Saved only when
 * something changed; repeatable, see [withPromoEnded].
 */
fun recordPromoEnds(store: Store, today: LocalDate): Boolean {
    val pays = store.pays()
    val recorded = pays.map { withPromoEnded(it, today) }
    if (recorded == pays) return false
    store.savePays(recorded)
    return true
}

/**
 * The whole sweep, as the app runs it on every return to the front: promo ends
 * recorded, and each cancelled payment whose question has waited a week moved to
 * the bin — restorable for thirty days, and coming back running. Its confirmed
 * monobank merchant is remembered for three months, so a charge from it after the
 * cancellation is still caught (Mono.kt).
 *
 * Read and written straight away with nothing slow between, like every other path
 * that touches the stored list.
 */
fun sweepPayments(context: Context, store: Store, today: LocalDate): Boolean {
    val pays = store.pays()
    val swept = sweepLife(pays, today)
    if (swept.pays == pays && swept.toBin.isEmpty()) return false
    store.savePays(swept.pays)
    val day = today.toEpochDay()
    swept.toBin.forEach { pay ->
        val entry = endedBinEntry(pay, day)
        if (store.bin().none { it.id == entry.id }) store.recycle(entry)
        rememberGone(context, pay)
    }
    return true
}

/**
 * Keeps a cancelled payment's confirmed merchant for three months once the payment
 * itself has gone — to the bin by the sweep, or deleted by hand.
 */
fun rememberGone(context: Context, pay: Pay) {
    if (pay.monoMerchant.isBlank() || pay.stopReason != STOP_CANCELLED || pay.stopsAfter <= 0L) return
    val mono = MonoStore(context)
    if (!mono.connected()) return
    mono.saveGone(withGone(mono.gone(), pay, LocalDate.now()))
}

package com.flowpay.app

/**
 * Folding the result of slow work back into a list that kept living meanwhile.
 *
 * A price pass reads a page per shop, and a parcel check asks Nova Poshta once per
 * parcel. Both take seconds on the phone and minutes in the background, and the
 * list they started from is not the list that exists when they finish: a wish was
 * deleted, another was added, a savings figure was typed. Every one of these paths
 * used to save the list it started from with the readings applied — so the deleted
 * wish came back (while its copy sat in the bin too, under the same id, which is a
 * duplicate key in the grid and a crash on every launch after), the new one
 * vanished, and the typed figure was quietly reverted.
 *
 * The rule here is the one each caller now goes through: readings are applied by
 * id onto the list as it is *now*, and only to items nobody touched while the work
 * ran. An item edited in the meantime keeps the edit — one reading is cheap to take
 * again, a person's typing is not.
 */
fun <T> mergeById(current: List<T>, before: List<T>, after: List<T>, id: (T) -> String): List<T> {
    val started = before.associateBy(id)
    val finished = after.associateBy(id)
    return current.map { item ->
        val key = id(item)
        val was = started[key]
        val now = finished[key]
        if (was != null && now != null && item == was) now else item
    }
}

/**
 * The list with any second copy of an id dropped, first one kept.
 *
 * Every list on the phone is drawn by a lazy list keyed on id, and a repeated key is
 * not a glitch there — it throws, on every launch, because the list is saved. The
 * paths that could produce one are fixed, but a backup written by an older build,
 * or a restore racing an undo, must never be able to lock the owner out of the app.
 */
fun <T> withoutRepeatedIds(items: List<T>, id: (T) -> String): List<T> = items.distinctBy(id)

/**
 * A bin entry's own id, distinct from the item's.
 *
 * A bought wish becomes a parcel under the wish's id, and both used to enter the bin
 * under that one id — so restoring the parcel restored the wish instead, and then
 * the drop that followed deleted both entries, the wish's whole price history
 * included. The item's id stays inside the payload, where restoring reads it.
 */
fun binEntryId(kind: String, itemId: String, nanos: Long = System.nanoTime()): String =
    "$kind-$itemId-$nanos"

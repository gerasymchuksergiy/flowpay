package com.flowpay.app

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * The Android half of ParcelsMore.kt: one preference file's worth of parcel
 * settings and caches, the directory call, and opening Nova Poshta's own app.
 *
 * Everything here lives in the same `flowpay` preferences as the [Store], under
 * keys that start with `pk_`. None of it is app data: the owner's phone number, a
 * week-old answer about a branch's hours and what the morning message has already
 * said are all about this phone, so none of it is in [Store.exportJson].
 */
class ParcelPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    /** «Мій номер для Нової пошти», 380XXXXXXXXX, or empty. */
    fun phone(): String = normalizedPhone(prefs.getString(KEY_PHONE, "").orEmpty())

    /** Saves the number when it is one, clears it when the field is emptied. False for a number that is not one. */
    fun savePhone(raw: String): Boolean {
        val clean = normalizedPhone(raw)
        if (raw.isNotBlank() && clean.isEmpty()) return false
        prefs.edit { if (clean.isEmpty()) remove(KEY_PHONE) else putString(KEY_PHONE, clean) }
        return true
    }

    /** Every pickup point asked about, by its directory id. */
    fun points(): Map<String, PickupPoint> = runCatching {
        val all = JSONObject(prefs.getString(KEY_POINTS, "{}") ?: "{}")
        all.keys().asSequence().mapNotNull { key -> pickupPointOf(all.optJSONObject(key))?.let { key to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun point(ref: String): PickupPoint? = points()[ref]

    /**
     * Keeps one answer, and drops answers a month old: a parcel collected in
     * spring has no reason to keep its branch's hours on the phone.
     */
    fun savePoint(point: PickupPoint, nowMillis: Long = System.currentTimeMillis()) {
        val kept = points().filterValues { nowMillis - it.fetchedAt < POINT_FORGET_MS } + (point.ref to point)
        val json = JSONObject()
        kept.forEach { (ref, one) -> json.put(ref, pickupPointJson(one)) }
        prefs.edit { putString(KEY_POINTS, json.toString()) }
    }

    /** The keys of the said-once lines the morning message has already carried. */
    fun digestSaid(): Set<String> = runCatching {
        val array = JSONArray(prefs.getString(KEY_SAID, "[]"))
        (0 until array.length()).map { array.optString(it) }.toSet()
    }.getOrDefault(emptySet())

    fun saveDigestSaid(keys: Collection<String>) =
        prefs.edit { putString(KEY_SAID, JSONArray(keys.toList().takeLast(ONCE_MEMORY)).toString()) }

    private companion object {
        const val KEY_PHONE = "pk_np_phone"
        const val KEY_POINTS = "pk_points"
        const val KEY_SAID = "pk_said"
        const val POINT_FORGET_MS = 30L * 86_400_000L
    }
}

/**
 * Asks Nova Poshta's public directory about one pickup point. Null on any failure:
 * a branch's hours are a nicety, and a dropped connection must not cost anything
 * but their absence.
 */
suspend fun fetchPickupPoint(ref: String): PickupPoint? = withContext(Dispatchers.IO) {
    if (ref.isBlank()) return@withContext null
    runCatching {
        val connection = URL("https://api.novaposhta.ua/v2.0/json/").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.outputStream.use { it.write(pointRequest(ref).toString().toByteArray(Charsets.UTF_8)) }
        if (connection.responseCode !in 200..299) return@runCatching null
        parsePickupPoint(connection.inputStream.bufferedReader().use { it.readText() }, System.currentTimeMillis())
    }.getOrNull()
}

/**
 * Asks about each point a waiting parcel sits at, unless a week-old answer is kept.
 * At most [limit] calls, so a pass with a dozen parcels at a dozen branches does
 * not spend its minutes here. Returns how many answers were saved.
 */
suspend fun refreshPickupPoints(context: Context, orders: List<Order>, limit: Int = 3): Int {
    val prefs = ParcelPrefs(context)
    val now = System.currentTimeMillis()
    val known = prefs.points()
    var saved = 0
    orders.mapNotNull { pointToAsk(it) }.distinct()
        .filterNot { pointFresh(known[it], now) }
        .take(limit)
        .forEach { ref ->
            fetchPickupPoint(ref)?.let {
                prefs.savePoint(it, now)
                saved++
            }
        }
    return saved
}

/**
 * «Відкрити в Новій пошті»: the new app, else the old one, else the tracking page.
 *
 * The two packages are declared under `<queries>` in the manifest; without that,
 * Android 11 and later hide other apps and the launcher intent comes back null.
 */
fun openNovaPoshta(context: Context, number: String) {
    for (pkg in NOVA_POSHTA_APPS) {
        val launch = context.packageManager.getLaunchIntentForPackage(pkg) ?: continue
        if (runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
    }
    openLink(context, novaPoshtaPage(number))
}

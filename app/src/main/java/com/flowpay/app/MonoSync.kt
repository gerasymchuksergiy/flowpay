package com.flowpay.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * monobank, the Android half: the token, the stored statement, the calls and the
 * background pass. The decisions are in Mono.kt.
 *
 * **Where the owner's data is.** The token is encrypted with an AES key that lives
 * in the phone's keystore and never leaves it, so the stored text is useless
 * anywhere else. Token, accounts and statement sit in their own preferences file,
 * `flowpay-mono`, which no export writes and no backup reads (the manifest turns
 * Android's own backup off entirely). «Відключити» deletes the file and the key.
 */

// ------------------------------------------------------------ the token

/** Seals and opens the token with a keystore key that cannot be taken off the phone. */
object MonoVault {
    private const val ALIAS = "flowpay-mono-token"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }

    fun seal(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val body = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(body, Base64.NO_WRAP)
    }

    fun open(sealed: String): String? = runCatching {
        val (iv, body) = sealed.split(':', limit = 2)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        String(cipher.doFinal(Base64.decode(body, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    fun forget() {
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS) }
    }
}

// ------------------------------------------------------------ what is kept

/** Everything monobank-related on the phone, apart from the fields on payments and wishes. */
class MonoStore(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay-mono", Context.MODE_PRIVATE)

    fun connected(): Boolean = prefs.contains("tok")

    fun token(): String? = prefs.getString("tok", null)?.let { MonoVault.open(it) }

    fun saveToken(token: String) = prefs.edit { putString("tok", MonoVault.seal(token)) }

    fun client(): MonoClient? = prefs.getString("client", null)?.let { runCatching { parseMonoClient(it) }.getOrNull() }

    fun saveClient(client: MonoClient) = prefs.edit {
        putString("client", monoClientJson(client).toString())
        putLong("cat", System.currentTimeMillis())
    }

    /** When the account information was last read, epoch ms. */
    fun clientAt(): Long = prefs.getLong("cat", 0L)

    /** When the API was last called, epoch ms: the next call waits a minute from it. */
    fun lastCall(): Long = prefs.getLong("call", 0L)

    fun saveLastCall(at: Long) = prefs.edit { putLong("call", at) }

    /**
     * Statement requests still to make in the load under way, or 0. A count saved
     * more than a quarter of an hour ago is stale — the phone stopped that pass and
     * has not started the next — and reads as 0.
     */
    fun loadingLeft(now: Long = System.currentTimeMillis()): Int =
        if (now - prefs.getLong("leftAt", 0L) < 15 * 60_000L) prefs.getInt("left", 0) else 0

    fun saveLoadingLeft(left: Int) = prefs.edit {
        putInt("left", left)
        putLong("leftAt", System.currentTimeMillis())
    }

    /** The accounts whose statements are read. Empty means every hryvnia account. */
    fun chosen(): Set<String> = prefs.getStringSet("acc", emptySet())?.toSet() ?: emptySet()

    fun saveChosen(ids: Set<String>) = prefs.edit { putStringSet("acc", ids) }

    fun txs(): List<MonoTx> = runCatching {
        val array = JSONArray(prefs.getString("tx", "[]"))
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }.map { monoTxOf(it) }
    }.getOrDefault(emptyList())

    fun saveTxs(items: List<MonoTx>) = prefs.edit { putString("tx", JSONArray(items.map(::monoTxJson)).toString()) }

    /** «operation|payment» pairs the owner said no to. */
    fun rejected(): Set<String> = prefs.getStringSet("rej", emptySet())?.toSet() ?: emptySet()

    fun reject(pair: String) = prefs.edit { putStringSet("rej", rejected() + pair) }

    /** Merchants the owner said are not subscriptions. */
    fun ignored(): Set<String> = prefs.getStringSet("ign", emptySet())?.toSet() ?: emptySet()

    fun ignore(key: String) = prefs.edit { putStringSet("ign", ignored() + key) }

    /** Whether confirmed merchants tick their payments by themselves. On unless turned off. */
    fun auto(): Boolean = prefs.getBoolean("auto", true)

    fun saveAuto(on: Boolean) = prefs.edit { putBoolean("auto", on) }

    fun lastSync(): Long = prefs.getLong("sync", 0L)

    /** What went wrong on the last pass, in words for the owner. Empty when nothing did. */
    fun lastError(): String = prefs.getString("err", "") ?: ""

    fun saveSync(at: Long, error: String) = prefs.edit {
        if (at > 0L) putLong("sync", at)
        putString("err", error)
    }

    /** Per account: the moment up to which its statement has been read, unix seconds. */
    fun fetchedUntil(): MutableMap<String, Long> = runCatching {
        val o = JSONObject(prefs.getString("until", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }.toMutableMap()
    }.getOrDefault(mutableMapOf())

    fun saveFetchedUntil(map: Map<String, Long>) = prefs.edit { putString("until", JSONObject(map).toString()) }

    // What the owner answered to the statement's questions (PaymentsLife.kt,
    // Mono.kt). Kept here with the statement they are about, so «Відключити»
    // forgets them together.

    /** Cancelled payments' merchants watched for three months after they went. */
    fun gone(): List<GoneMerchant> = goneOf(prefs.getString("pl_gone", "[]"))

    fun saveGone(items: List<GoneMerchant>) = prefs.edit { putString("pl_gone", goneJson(items).toString()) }

    /** «Ще чекаю»: payment name to the epoch day the «Мовчать» question may come back. */
    fun waiting(): Map<String, Long> = runCatching {
        val o = JSONObject(prefs.getString("pl_wait", "{}") ?: "{}")
        o.keys().asSequence().associateWith { o.optLong(it) }
    }.getOrDefault(emptyMap())

    fun saveWaiting(name: String, until: Long) = prefs.edit {
        val next = waiting().filterValues { it > java.time.LocalDate.now().toEpochDay() } + (name to until)
        putString("pl_wait", JSONObject(next).toString())
    }

    /** Double charges the owner said «Усе гаразд» about, by [DoubleCharge.key]. */
    fun doublesOk(): Set<String> = prefs.getStringSet("pl_dup", emptySet())?.toSet() ?: emptySet()

    fun doubleOk(key: String) = prefs.edit { putStringSet("pl_dup", doublesOk() + key) }

    /** Charges after a cancellation the owner said «Це не воно» about, by operation id. */
    fun afterOk(): Set<String> = prefs.getStringSet("pl_after", emptySet())?.toSet() ?: emptySet()

    fun afterIsOk(id: String) = prefs.edit { putStringSet("pl_after", afterOk() + id) }

    /** Jar alerts already sent, by [JarAlert.key]. */
    fun jarSaid(): Set<String> = prefs.getStringSet("pl_jar", emptySet())?.toSet() ?: emptySet()

    fun saveJarSaid(keys: Set<String>) = prefs.edit { putStringSet("pl_jar", keys.toList().takeLast(200).toSet()) }

    /** Everything, and the key the token was sealed with. */
    fun forget() {
        prefs.edit { clear() }
        MonoVault.forget()
    }

    /** Hryvnia accounts read when the owner chose none. */
    fun accountsToRead(client: MonoClient): Set<String> =
        chosen().ifEmpty { client.accounts.filter { it.currencyCode == UAH_CODE }.map { it.id }.toSet() }

    companion object {
        /** Bumped after every pass and every answer, so the screens read again. */
        var version by mutableIntStateOf(0)
            private set

        fun bump() {
            version++
        }
    }
}

/** Each account's currency, for reading its amounts. */
fun accountCurrencies(client: MonoClient?): Map<String, Int> =
    client?.accounts?.associate { it.id to it.currencyCode } ?: emptyMap()

// ------------------------------------------------------------ the calls

/** monobank said no, with its status code and a sentence for the owner. */
class MonoApiError(val code: Int, message: String) : java.io.IOException(message)

private fun monoGet(path: String, token: String): String {
    val connection = URL("https://api.monobank.ua$path").openConnection() as HttpURLConnection
    connection.connectTimeout = 20_000
    connection.readTimeout = 30_000
    connection.setRequestProperty("X-Token", token)
    try {
        val code = connection.responseCode
        if (code in 200..299) return connection.inputStream.bufferedReader().use { it.readText() }
        throw when (code) {
            429 -> MonoApiError(code, "monobank просить зачекати хвилину")
            401, 403 -> MonoApiError(code, "Токен не підходить або його відкликали")
            else -> MonoApiError(code, "monobank відповів кодом $code")
        }
    } finally {
        connection.disconnect()
    }
}

// ------------------------------------------------------------ the pass

object MonoSync {
    private const val WORK_PERIODIC = "mono"
    private const val WORK_NOW = "mono-now"
    private const val WORK_MORE = "mono-more"

    /** A little over the API's one request a minute. */
    private const val GAP_MS = 61_000L

    /**
     * How long one pass may read. WorkManager stops a worker at ten minutes, and a
     * first load is a request a minute for every month of every card — twelve
     * minutes for four cards — so a pass stops itself here and queues the rest.
     */
    private const val PASS_BUDGET_MS = 8 * 60_000L

    /** What one request may take on top of its wait. */
    private const val CALL_ALLOWANCE_MS = 30_000L

    /** Account information this fresh is not asked for again: the pass before has it. */
    private const val CLIENT_FRESH_MS = 5 * 60_000L

    /** How long operations are kept on the phone. */
    private const val KEEP_DAYS = 100L

    /** One statement answer at most; more means the window was cut short. */
    private const val PAGE = 500

    private val lock = Mutex()

    /**
     * Checks a token by asking for the client's own information, and keeps both.
     * Throws [MonoApiError] with a sentence for the owner when the token is wrong.
     */
    suspend fun connect(context: Context, token: String): MonoClient {
        val clean = token.trim()
        val client = withContext(Dispatchers.IO) { parseMonoClient(monoGet("/personal/client-info", clean)) }
        val mono = MonoStore(context)
        mono.saveToken(clean)
        mono.saveClient(client)
        mono.saveLastCall(System.currentTimeMillis())
        mono.saveSync(0L, "")
        // A periodic pass runs at once when it is first enqueued: that is the first sync.
        schedule(context)
        MonoStore.bump()
        return client
    }

    /**
     * Reads the account information and the statements, then applies them.
     *
     * Progress is saved after every request, so a pass the phone stops loses
     * nothing; a pass that would outrun [PASS_BUDGET_MS] stops itself and queues
     * the rest. When another pass is already reading, this one leaves it to it.
     */
    suspend fun run(context: Context) {
        if (!lock.tryLock()) return
        try {
            pass(context)
        } finally {
            lock.unlock()
        }
    }

    private suspend fun pass(context: Context) {
        val started = System.currentTimeMillis()
        val mono = MonoStore(context)
        val token = mono.token() ?: return
        // This pass is the news now: an old error would only mislead while it runs.
        mono.saveSync(0L, "")
        MonoStore.bump()
        try {
            val client = mono.client()?.takeIf { started - mono.clientAt() < CLIENT_FRESH_MS }
                ?: parseMonoClient(call(mono, "/personal/client-info", token)).also { mono.saveClient(it) }
            val now = System.currentTimeMillis() / 1000
            val until = mono.fetchedUntil()
            var cache = mono.txs()
            while (true) {
                // Asked afresh after every request, so a card ticked meanwhile joins in.
                val plan = statementPlan(mono.accountsToRead(client), until, now)
                val step = plan.firstOrNull() ?: break
                mono.saveLoadingLeft(plan.size)
                MonoStore.bump()
                var to = step.to
                var complete = false
                while (!complete) {
                    if (!fitsInPass(mono, started)) {
                        // The rest in a minute, in a new pass; what was read is kept.
                        continueSoon(context)
                        apply(context)
                        return
                    }
                    val part = parseMonoStatement(call(mono, "/personal/statement/${step.account}/${step.from}/$to", token), step.account)
                    if (!mono.connected()) return
                    cache = mergeMonoTx(cache, part, now - KEEP_DAYS * 86_400)
                    mono.saveTxs(cache)
                    to = (part.minOfOrNull { it.time } ?: step.from) - 1
                    complete = part.size < PAGE || to <= step.from
                }
                until[step.account] = step.to
                mono.saveFetchedUntil(until)
            }
            mono.saveLoadingLeft(0)
            apply(context)
            mono.saveSync(System.currentTimeMillis(), "")
        } catch (stopped: CancellationException) {
            // The phone stopped the worker. Not a fault, and what was read is saved.
            throw stopped
        } catch (problem: Exception) {
            mono.saveLoadingLeft(0)
            mono.saveSync(0L, (problem as? MonoApiError)?.message ?: "Немає зв'язку з monobank. Спробую ще раз пізніше.")
            throw problem
        } finally {
            MonoStore.bump()
        }
    }

    /**
     * One request, a minute after the last one. monobank counts every call against
     * the token, the check on connecting included, so the minute is kept across
     * passes. Asked to wait anyway, it waits once more and tries again.
     */
    private suspend fun call(mono: MonoStore, path: String, token: String): String {
        var refused = 0
        while (true) {
            val wait = mono.lastCall() + GAP_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            try {
                return withContext(Dispatchers.IO) { monoGet(path, token) }
            } catch (busy: MonoApiError) {
                if (busy.code != 429 || ++refused > 1) throw busy
            } finally {
                if (mono.connected()) mono.saveLastCall(System.currentTimeMillis())
            }
        }
    }

    /** Whether one more request, its wait included, still ends inside this pass's budget. */
    private fun fitsInPass(mono: MonoStore, started: Long): Boolean {
        val now = System.currentTimeMillis()
        val wait = maxOf(0L, mono.lastCall() + GAP_MS - now)
        return now + wait + CALL_ALLOWANCE_MS - started <= PASS_BUDGET_MS
    }

    /**
     * The rest of a long load, in a new pass that starts once this one ends (it
     * waits out the minute itself). Appended when this pass is itself the rest.
     */
    private fun continueSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<MonoWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_MORE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * What a pass changes on the owner's data: confirmed merchants tick their
     * payments with what was actually charged, and wishes kept in a jar take the
     * jar's balance. Read and written straight away, with nothing slow between.
     */
    fun apply(context: Context) {
        val mono = MonoStore(context)
        val client = mono.client() ?: return
        val store = Store(context)
        val today = LocalDate.now()
        // A paused payment whose confirmed merchant charged again runs again —
        // before the matching below, so the charge that ended the pause is ticked
        // in this same pass.
        val pays = store.pays()
        val running = bankResumed(pays, mono.txs(), accountCurrencies(client), store.fxRate().first.sell, today)
        if (running != pays) store.savePays(running)
        if (mono.auto()) {
            val marks = store.paidMarks(today)
            val learned = monoMatches(
                store.pays(), mono.txs(), marks, mono.rejected(), accountCurrencies(client),
                today, store.fxRate().first.sell
            ).filter { it.kind == MonoMatchKind.LEARNED }
            if (learned.isNotEmpty()) {
                store.savePaidMarks(learned.fold(marks) { all, match -> withMonoMark(all, match) }, today)
            }
        }
        val jars = client.jars.associateBy { it.id }
        val wishes = store.wishes()
        val updated = wishes.map { wish -> jars[wish.jar]?.let(::jarUah)?.let { wish.copy(saved = it) } ?: wish }
        if (updated != wishes) store.saveWishes(updated)
        // The one interruption monobank earns: a jar that now holds a wish's whole
        // live price. Once per price level, like the target-price alert.
        val said = mono.jarSaid()
        val alerts = jarAlerts(updated, client.jars, said, today.toEpochDay())
        if (alerts.isNotEmpty()) {
            alerts.forEach { notifyJar(context, it) }
            mono.saveJarSaid(said + alerts.map { it.key })
        }
    }

    /** «На банці 4 200 ₴, а ціна вже 3 999 ₴ — можна купувати», on the price alerts' channel. */
    private fun notifyJar(context: Context, alert: JarAlert) {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL_PRICES, "Зміни цін", android.app.NotificationManager.IMPORTANCE_DEFAULT)
        )
        val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!allowed) return
        val text = jarAlertText(alert)
        androidx.core.app.NotificationManagerCompat.from(context).notify(
            ("jar" + alert.key).hashCode(),
            androidx.core.app.NotificationCompat.Builder(context, CHANNEL_PRICES)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(alert.wish.name)
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(openTabIntent(context, TAB_WISHES))
                .setOnlyAlertOnce(true)
                .setAutoCancel(true)
                .build()
        )
    }

    /** The channel the price alerts already use, so a person silences both in one place. */
    private const val CHANNEL_PRICES = "price_changes"

    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<MonoWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /** «Оновити зараз». Runs in the background: a pass waits a minute between calls. */
    fun runNow(context: Context) {
        val request = OneTimeWorkRequestBuilder<MonoWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NOW, ExistingWorkPolicy.KEEP, request)
    }

    /** «Відключити»: no more passes, and nothing of monobank left on the phone. */
    fun disconnect(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_PERIODIC)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NOW)
        WorkManager.getInstance(context).cancelUniqueWork(WORK_MORE)
        MonoStore(context).forget()
        val store = Store(context)
        val wishes = store.wishes()
        if (wishes.any { it.jar.isNotBlank() }) store.saveWishes(wishes.map { it.copy(jar = "") })
        MonoStore.bump()
    }
}

class MonoWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!MonoStore(applicationContext).connected()) return Result.success()
        return try {
            MonoSync.run(applicationContext)
            Result.success()
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (busy: MonoApiError) {
            // Asked to wait: try again later. A refused token will not get better by retrying.
            if (busy.code == 429) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

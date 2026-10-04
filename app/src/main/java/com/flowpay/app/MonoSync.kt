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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    fun saveClient(client: MonoClient) = prefs.edit { putString("client", monoClientJson(client).toString()) }

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

    /** A little over the API's one request a minute. */
    private const val GAP_MS = 61_000L

    /** How far back the first pass reads, for the subscription finder to see three months. */
    private const val HISTORY_DAYS = 93L

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
        mono.saveSync(0L, "")
        // A periodic pass runs at once when it is first enqueued: that is the first sync.
        schedule(context)
        MonoStore.bump()
        return client
    }

    /** Reads the account information and the statements, then applies them. */
    suspend fun run(context: Context) = lock.withLock {
        val mono = MonoStore(context)
        val token = mono.token() ?: return@withLock
        try {
            val client = withContext(Dispatchers.IO) { parseMonoClient(monoGet("/personal/client-info", token)) }
            mono.saveClient(client)
            val now = System.currentTimeMillis() / 1000
            val until = mono.fetchedUntil()
            var cache = mono.txs()
            for (account in mono.accountsToRead(client)) {
                // Two days of overlap: a hold settles under the same id, a day late.
                val from = maxOf(until[account]?.minus(2L * 86_400) ?: 0L, now - HISTORY_DAYS * 86_400)
                for ((start, end) in statementWindows(from, now)) {
                    var to = end
                    while (true) {
                        delay(GAP_MS)
                        val part = withContext(Dispatchers.IO) {
                            parseMonoStatement(monoGet("/personal/statement/$account/$start/$to", token), account)
                        }
                        cache = mergeMonoTx(cache, part, now - KEEP_DAYS * 86_400)
                        if (part.size < PAGE) break
                        to = part.minOf { it.time } - 1
                        if (to <= start) break
                    }
                }
                until[account] = now
                // Saved per account, so a pass stopped half way keeps what it read.
                mono.saveTxs(cache)
                mono.saveFetchedUntil(until)
            }
            apply(context)
            mono.saveSync(System.currentTimeMillis(), "")
        } catch (problem: Exception) {
            mono.saveSync(0L, (problem as? MonoApiError)?.message ?: "Немає зв'язку з monobank")
            throw problem
        } finally {
            MonoStore.bump()
        }
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
        if (mono.auto()) {
            val today = LocalDate.now()
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
    }

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
        } catch (busy: MonoApiError) {
            // Asked to wait: try again later. A refused token will not get better by retrying.
            if (busy.code == 429) Result.retry() else Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

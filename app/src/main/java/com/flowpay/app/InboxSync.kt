package com.flowpay.app

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.SecureRandom
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The Telegram inbox, the Android half: the bot's token, the private preferences, the
 * calls and the pass. Every decision is in Inbox.kt.
 *
 * **Where the owner's data is.** The token is sealed with an AES key that lives in
 * the phone's keystore under its own alias and never leaves it. Token, chat, offset,
 * code and the replies still to send sit in their own preferences file, `flowpay-tg`,
 * which no export writes and no backup reads (the manifest turns Android's backup off,
 * and [Store.exportJson] names its keys one by one). Nothing here is logged — and no
 * exception leaves [call] with the address in it, because the token is part of every
 * address the Bot API is called at, and the platform's own exceptions quote the URL.
 */

// ------------------------------------------------------------ the token

/**
 * Seals and opens the bot token with a keystore key that cannot be taken off the phone.
 *
 * [MonoVault]'s arrangement exactly, under an alias of its own. Written out again
 * rather than shared on purpose: the owner's live monobank token is opened by
 * MonoVault, and a refactor of it that nothing on the JVM can test is not worth that.
 */
object TgVault {
    private const val ALIAS = "flowpay-tg-token"
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

/** Everything the inbox keeps on the phone, in `flowpay-tg`. Every key starts with `tg_`. */
class InboxStore(context: Context) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun connected(): Boolean = prefs.contains(KEY_TOKEN)

    fun token(): String? = prefs.getString(KEY_TOKEN, null)?.let { TgVault.open(it) }

    /** The bot's own username, without the «@». */
    fun username(): String = prefs.getString(KEY_USER, "").orEmpty()

    /** The owner's chat with the bot; nought until it is bound. */
    fun chat(): Long = prefs.getLong(KEY_CHAT, 0L)

    fun bound(): Boolean = chat() != 0L

    /** The six digits that bind a chat, while one is waiting. Empty once bound. */
    fun code(): String = prefs.getString(KEY_CODE, "").orEmpty()

    /** The next update to ask for, and when it was saved — see [offsetToSend]. */
    fun offset(): Long = prefs.getLong(KEY_OFFSET, 0L)

    fun offsetAt(): Long = prefs.getLong(KEY_OFFSET_AT, 0L)

    /** When Telegram last answered a pass, epoch ms. */
    fun lastPass(): Long = prefs.getLong(KEY_LAST, 0L)

    /** A real fault, in words for the owner. Empty when there is none. */
    fun lastError(): String = prefs.getString(KEY_ERROR, "").orEmpty()

    /** Replies not sent yet. */
    fun outbox(): List<TgReply> = repliesOf(prefs.getString(KEY_OUTBOX, null))

    /** A new bot: anything of an earlier one forgotten, the token sealed, a fresh code waiting. */
    fun connect(token: String, username: String, code: String) = prefs.edit {
        clear()
        putString(KEY_TOKEN, TgVault.seal(token))
        putString(KEY_USER, username)
        putString(KEY_CODE, code)
    }

    fun bind(chat: Long) = write {
        putLong(KEY_CHAT, chat)
        remove(KEY_CODE)
    }

    fun saveOffset(offset: Long, at: Long) = write {
        putLong(KEY_OFFSET, offset)
        putLong(KEY_OFFSET_AT, at)
    }

    /** Telegram answered: the check the row reports, and any old fault cleared. */
    fun saveChecked(at: Long) = write {
        putLong(KEY_LAST, at)
        remove(KEY_ERROR)
    }

    fun saveError(error: String) = write { putString(KEY_ERROR, error) }

    fun queue(reply: TgReply) = write { putString(KEY_OUTBOX, repliesJson(outbox() + reply)) }

    fun saveOutbox(items: List<TgReply>) = write { putString(KEY_OUTBOX, repliesJson(items)) }

    /** Everything, and the key the token was sealed with. */
    fun forget() {
        prefs.edit { clear() }
        TgVault.forget()
    }

    /** Written only while connected, so a pass still running at «Відключити» leaves nothing behind. */
    private fun write(change: SharedPreferences.Editor.() -> Unit) {
        if (connected()) prefs.edit(action = change)
    }

    companion object {
        const val FILE = "flowpay-tg"
        private const val KEY_TOKEN = "tg_tok"
        private const val KEY_USER = "tg_user"
        private const val KEY_CHAT = "tg_chat"
        private const val KEY_CODE = "tg_code"
        private const val KEY_OFFSET = "tg_off"
        private const val KEY_OFFSET_AT = "tg_off_at"
        private const val KEY_LAST = "tg_last"
        private const val KEY_ERROR = "tg_err"
        private const val KEY_OUTBOX = "tg_out"

        /** Bumped after every pass and every change, so the row and the sheet read again. */
        var version by mutableIntStateOf(0)
            private set

        fun bump() {
            version++
        }
    }
}

// ------------------------------------------------------------ the calls

/** Telegram answered no. Its error code, and nothing else: never the address, which holds the token. */
class TgRefused(val code: Int, val retryAfter: Int = 0) : java.io.IOException("Telegram refused: $code")

/** Telegram did not answer: no connection, a timeout, or a body that is not its JSON. */
class TgOffline : java.io.IOException("Telegram did not answer")

private const val TG_API = "https://api.telegram.org/bot"

/**
 * One Bot API call: GET with [query], or POST with a JSON [body]. Comes back with an
 * answer whose `ok` is true, or throws [TgRefused] or [TgOffline] — only those, so no
 * platform exception quoting the address (and the token in it) can reach a log.
 */
private fun tgCall(token: String, method: String, query: String = "", body: JSONObject? = null): TgAnswer {
    try {
        val address = TG_API + token + "/" + method + if (query.isEmpty()) "" else "?$query"
        val connection = URL(address).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 20_000
            connection.useCaches = false
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            // An error's JSON is on the error stream; the input stream would throw.
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val answer = tgAnswer(text) ?: throw if (code in 200..299) TgOffline() else TgRefused(code)
            if (!answer.ok) throw TgRefused(answer.errorCode.takeIf { it > 0 } ?: code, answer.retryAfter)
            return answer
        } finally {
            connection.disconnect()
        }
    } catch (refused: TgRefused) {
        throw refused
    } catch (_: Exception) {
        throw TgOffline()
    }
}

private suspend fun tgAsk(token: String, method: String, query: String = "", body: JSONObject? = null): TgAnswer =
    withContext(Dispatchers.IO) { tgCall(token, method, query, body) }

// ------------------------------------------------------------ the pass

object InboxSync {
    private const val WORK_PERIODIC = "telegram"
    private const val WORK_MORE = "telegram-more"

    /**
     * How long one pass may work through messages. WorkManager stops a worker at ten
     * minutes, and a new link can take most of a minute (the page, a second try, the
     * fill): past this the rest is left for a pass queued straight after.
     */
    private const val PASS_BUDGET_MS = 7 * 60_000L

    /** getUpdates rounds in one pass, a hundred updates each. */
    private const val MAX_ROUNDS = 10

    /** One pass at a time: the worker, the sheet's polling and «Перевірити зараз» all take it. */
    private val lock = Mutex()

    /**
     * Passes started from a screen run here rather than in the screen's own scope, so
     * closing the sheet halfway through a message does not cut the pass off between
     * adding a thing and saying so.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** While a pass runs — the sheet's «Перевіряю…». */
    var running by mutableStateOf(false)
        private set

    /**
     * Checks a token with getMe, which gives the bot's username, and removes any
     * webhook (getUpdates answers 409 while one is set), then keeps the token sealed
     * with a fresh binding code and starts the pass every 15 minutes. Returns the
     * username; throws [TgRefused] or [TgOffline].
     */
    suspend fun connect(context: Context, token: String): String {
        val app = context.applicationContext
        val username = withContext(Dispatchers.IO) {
            val name = botUsername(tgCall(token, "getMe").result) ?: throw TgOffline()
            tgCall(token, "deleteWebhook")
            InboxStore(app).connect(token, name, newBindingCode(SecureRandom()))
            name
        }
        schedule(app)
        InboxStore.bump()
        return username
    }

    /**
     * One pass, unless another is already running — that one is left to finish.
     * False when it was left. Never throws but for cancellation: a fault is kept for
     * the settings row, and no connection is simply tried again next time.
     */
    suspend fun run(context: Context): Boolean {
        if (!lock.tryLock()) return false
        try {
            running = true
            pass(context.applicationContext)
        } finally {
            running = false
            lock.unlock()
        }
        return true
    }

    /** A pass now: the app coming to the front, «Перевірити зараз», the sheet waiting for Start. */
    fun passNow(context: Context): Job? {
        if (!InboxStore(context).connected()) return null
        val app = context.applicationContext
        return scope.launch { run(app) }
    }

    private suspend fun pass(context: Context) {
        val inbox = InboxStore(context)
        if (!inbox.connected()) return
        val token = inbox.token()
        if (token == null) {
            // The keystore lost its key — a restored phone, a cleared lock screen.
            inbox.saveError(INBOX_TOKEN_LOST)
            InboxStore.bump()
            return
        }
        val started = System.currentTimeMillis()
        try {
            flush(inbox, token)
            var offset = offsetToSend(inbox.offset(), inbox.offsetAt(), started)
            for (round in 1..MAX_ROUNDS) {
                val batch = updates(token, offset)
                if (round == 1) inbox.saveChecked(System.currentTimeMillis())
                if (batch.isEmpty()) break
                for (update in batch) {
                    if (!inbox.connected()) return
                    if (System.currentTimeMillis() - started > PASS_BUDGET_MS) {
                        continueSoon(context)
                        return
                    }
                    take(context, inbox, update)
                    // Saved after each, so a pass the phone stops loses nothing; the next
                    // getUpdates with it confirms the update to Telegram.
                    offset = offsetAfter(offset, update)
                    inbox.saveOffset(offset, System.currentTimeMillis())
                    flush(inbox, token)
                }
            }
        } catch (stopped: CancellationException) {
            throw stopped
        } catch (refused: TgRefused) {
            // Only the real faults are said: a refused token, another program reading
            // the bot. Telegram's own trouble is asked about again next pass.
            inboxFault(refused.code)?.let { inbox.saveError(it) }
        } catch (_: Exception) {
            // No connection, or no answer: the next pass asks again. Not a fault to show.
        } finally {
            InboxStore.bump()
        }
    }

    /** The updates after [offset] (every unconfirmed one when null). A webhook in the way is removed once. */
    private suspend fun updates(token: String, offset: Long?): List<TgUpdate> {
        val query = updatesQuery(offset)
        val answer = try {
            tgAsk(token, "getUpdates", query)
        } catch (conflict: TgRefused) {
            if (conflict.code != 409) throw conflict
            tgAsk(token, "deleteWebhook")
            tgAsk(token, "getUpdates", query)
        }
        return updatesIn(answer.result)
    }

    /** One update: decided, acted on, and its reply queued. */
    private suspend fun take(context: Context, inbox: InboxStore, update: TgUpdate) {
        when (val step = triage(update, inbox.chat(), inbox.code())) {
            Triage.Ignore -> Unit
            is Triage.Bind -> {
                inbox.bind(step.message.chatId)
                inbox.queue(replyTo(step.message, INBOX_READY))
            }
            is Triage.Hello -> inbox.queue(replyTo(step.message, INBOX_READY))
            is Triage.NotText -> inbox.queue(replyTo(step.message, INBOX_NOT_TEXT))
            is Triage.Handle -> {
                // A message that breaks the handling is answered and passed over, never
                // retried for ever with every message behind it waiting.
                val said = try {
                    act(context, step.text)
                } catch (stopped: CancellationException) {
                    throw stopped
                } catch (_: Exception) {
                    INBOX_FAILED
                }
                inbox.queue(replyTo(step.message, said))
            }
        }
    }

    private fun replyTo(message: TgMessage, text: String) = TgReply(message.chatId, message.messageId, text)

    /**
     * What a message from the owner does, as the share router would do it on the
     * phone — [inboxStep] decides — and the reply that says so. Every write re-reads
     * its list from the store right before it, and tells an open app to read again.
     */
    private suspend fun act(context: Context, text: String): String {
        val store = Store(context)
        val today = LocalDate.now()
        val step = inboxStep(text, store.wishes(), store.pays(), store.orders(), today, TouchPrefs(context).hideOutside())
        return when (step) {
            is InboxStep.Say -> step.text
            is InboxStep.AddPay -> {
                store.savePays(store.pays() + step.pay)
                changed(context, payments = true)
                step.reply
            }
            is InboxStep.NewPrice -> {
                // As «Оновити ціну» on the phone: one price per payment, through its history.
                val day = today.toEpochDay()
                store.savePays(store.pays().map { if (it.name == step.pay.name) withAmount(it, step.amount, day) else it })
                changed(context, payments = true)
                step.reply
            }
            is InboxStep.AddParcel -> addParcel(context, store, step.number, today.toEpochDay())
            is InboxStep.AddWish -> withNote(addWish(context, store, step.url, today.toEpochDay()), step.note)
        }
    }

    /** A parcel onto the list as it is now, then asked about once, as «Перевірити» on its card would. */
    private suspend fun addParcel(context: Context, store: Store, number: String, today: Long): String {
        val now = store.orders()
        knownParcel(now, number)?.let { return knownParcelReply(it, number, today) }
        val order = parcelOrder(number, freshId(System.currentTimeMillis(), now.map { it.id }))
        store.saveOrders(now + order)
        changed(context)
        if (!isAutoTracked(order)) return newParcelReply(order, checked = false, today = today)
        val status = quietly { parcelStatus(order.tracking, phoneFor(order, ParcelPrefs(context).phone())) }
            ?: return newParcelReply(order, checked = false, today = today)
        val at = System.currentTimeMillis()
        val list = store.orders()
        val current = list.firstOrNull { it.id == order.id }
            ?: return newParcelReply(applyStatus(order, status, at), checked = true, today = today)
        val updated = applyStatus(current, status, at)
        store.saveOrders(list.map { if (it.id == order.id) updated else it })
        changed(context)
        return newParcelReply(updated, checked = true, today = today)
    }

    /**
     * A shop's page, the way the share does it: the link saved first — through
     * [addFromSheet], onto the list as it is now — so a shop that blocks the fetch
     * costs a name and a price, never the item; then the page read and the row filled.
     */
    private suspend fun addWish(context: Context, store: Store, url: String, today: Long): String {
        val rate = store.fxRate().first
        val id = freshId(System.currentTimeMillis(), store.wishes().map { it.id })
        val added = withContext(Dispatchers.Main) { addFromSheet(context, placeholderWish(url, id, today)) }
        if (!added) {
            // Watched already — added on the phone a moment ago.
            return store.wishes().firstOrNull { hasSource(it, url) }?.let(::knownWishReply) ?: newWishReply(null, url)
        }
        val read = quietly { readForAdd(pricedPageHtml(url), url, id, today, rate) }
        val fetched = when (read) {
            is PageAdd.Priced -> read.wish
            is PageAdd.Described -> read.wish
            PageAdd.Blank, null -> null
        } ?: return newWishReply(null, url)
        val list = store.wishes()
        val mine = list.firstOrNull { it.id == id } ?: return newWishReply(fetched, url)
        val filled = filledWish(mine, fetched, today, rate)
        store.saveWishes(list.map { if (it.id == id) filled else it })
        changed(context)
        return newWishReply(filled, url)
    }

    /**
     * After a write: an open FlowPay reads the store again — FlowPayApp watches
     * [ShopSheetSignal], the sheet over the shop's own «written from outside» signal —
     * and the widget redraws when payments changed.
     */
    private suspend fun changed(context: Context, payments: Boolean = false) {
        withContext(Dispatchers.Main) { ShopSheetSignal.bump() }
        if (payments) refreshWidget(context)
    }

    /** Sends the replies waiting, oldest first; what cannot go now waits for the next pass. */
    private suspend fun flush(inbox: InboxStore, token: String) {
        var left = inbox.outbox()
        while (left.isNotEmpty()) {
            try {
                send(token, left.first())
            } catch (refused: TgRefused) {
                when (refused.code) {
                    // The token itself: the pass reports it; the replies wait for a good one.
                    401, 404 -> throw refused
                    // Too many at once: the rest go next pass.
                    429 -> return
                    // 400, 403 — a chat that is gone, a bot the owner blocked: this one never can.
                    else -> Unit
                }
            }
            left = left.drop(1)
            inbox.saveOutbox(left)
        }
    }

    private suspend fun send(token: String, reply: TgReply) {
        val body = sendMessageBody(reply.chatId, reply.text, reply.replyTo)
        try {
            tgAsk(token, "sendMessage", body = body)
        } catch (busy: TgRefused) {
            if (busy.code != 429 || busy.retryAfter !in 1..10) throw busy
            delay(busy.retryAfter * 1_000L)
            tgAsk(token, "sendMessage", body = body)
        }
    }

    private suspend fun <T> quietly(block: suspend () -> T): T? = try {
        block()
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: Exception) {
        null
    }

    private fun online() = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /**
     * Every 15 minutes — WorkManager's shortest period — with a network. Telegram keeps
     * an update 24 hours, so the pass has to keep running whether or not the app is
     * opened. On connecting, and in MainActivity.onCreate while connected.
     */
    fun schedule(context: Context) {
        val request = PeriodicWorkRequestBuilder<TelegramWorker>(15, TimeUnit.MINUTES)
            .setConstraints(online())
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(WORK_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    /**
     * The rest of a long batch, in a pass of its own. Half a minute on, so the pass
     * that queued it has let go of the lock; appended when that pass is itself one.
     */
    private fun continueSoon(context: Context) {
        val request = OneTimeWorkRequestBuilder<TelegramWorker>()
            .setConstraints(online())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_MORE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /** «Відключити»: no more passes, and nothing of the bot left on the phone. */
    fun disconnect(context: Context) {
        val work = WorkManager.getInstance(context)
        work.cancelUniqueWork(WORK_PERIODIC)
        work.cancelUniqueWork(WORK_MORE)
        InboxStore(context).forget()
        InboxStore.bump()
    }
}

class TelegramWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        if (!InboxStore(applicationContext).connected()) return Result.success()
        // A pass keeps its own faults for the settings row and tries again in fifteen
        // minutes; a retry here would only knock on a Telegram that is not answering.
        InboxSync.run(applicationContext)
        return Result.success()
    }
}

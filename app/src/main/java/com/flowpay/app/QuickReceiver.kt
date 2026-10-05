package com.flowpay.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDate

/**
 * The Android half of «Сплачено» from outside the app — see QuickActions.kt for
 * the rules. The buttons under the morning message land in [QuickMarkReceiver];
 * the widget's tick lands in its own callbacks in Widget.kt; both write through
 * [quickMark] and [quickUnmark] and finish with [afterQuickWrite].
 */

const val ACTION_QUICK_MARK = "com.flowpay.app.action.QUICK_MARK"
const val ACTION_QUICK_UNDO = "com.flowpay.app.action.QUICK_UNDO"
const val EXTRA_PAY_NAME = "pay_name"
const val EXTRA_PAY_MONTH = "pay_month"

/**
 * How many times a mark was written from outside the app.
 *
 * The open app watches it and reads the marks again, the way it watches
 * [MonoStore.version] after a monobank pass: without it a screen left open under
 * the notification shade would go on showing the payment as unpaid.
 */
object QuickMarks {
    var version by mutableIntStateOf(0)
        private set

    fun bump() {
        version++
    }
}

private val paidLock = Any()

/**
 * The marks re-read, changed and saved under one lock.
 *
 * Every writer of the marks in the app's own process goes through here — the
 * screens, the widget's tick, the buttons under the morning message — so none of
 * them can save a list it read before another one wrote. Glance runs a widget's
 * callback on a background thread, which is why this is a lock and not only "do
 * it on the main thread".
 */
fun Store.updatePaidMarks(
    today: LocalDate = LocalDate.now(),
    change: (List<PaidMark>) -> List<PaidMark>
): List<PaidMark> = synchronized(paidLock) {
    val now = paidMarks(today)
    val next = prunePaidMarks(change(now), today)
    // A pass that changed nothing writes nothing.
    if (next != now) savePaidMarks(next, today)
    next
}

/** One «Сплачено» from outside the app, by the app's own rule. */
fun quickMark(context: Context, mark: QuickMark): QuickOutcome {
    val store = Store(context)
    val pays = store.pays()
    var outcome = QuickOutcome.GONE
    store.updatePaidMarks { marks -> quickMarked(marks, pays, mark).also { outcome = it.outcome }.marks }
    return outcome
}

/** One «Скасувати»: that mark off, nothing else. */
fun quickUnmark(context: Context, mark: QuickMark) {
    Store(context).updatePaidMarks { unmarkPaid(it, mark) }
}

/** After a write from outside: the open app reads the marks again, the widget redraws. */
suspend fun afterQuickWrite(context: Context) {
    withContext(Dispatchers.Main) { QuickMarks.bump() }
    refreshWidget(context)
}

/**
 * The buttons under the morning message. Not exported: only the app's own
 * notification, through its PendingIntent, can reach it.
 */
class QuickMarkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(EXTRA_PAY_NAME) ?: return
        val month = intent.getStringExtra(EXTRA_PAY_MONTH) ?: return
        val mark = QuickMark(name, month)
        val action = intent.action
        val app = context.applicationContext
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                when (action) {
                    ACTION_QUICK_MARK -> {
                        val outcome = quickMark(app, mark)
                        redrawDigest(app, mark) { card ->
                            if (outcome == QuickOutcome.GONE) digestGone(card, mark)
                            else digestPressed(card, mark, added = outcome == QuickOutcome.ADDED)
                        }
                    }
                    ACTION_QUICK_UNDO -> {
                        quickUnmark(app, mark)
                        redrawDigest(app, mark) { card -> digestUndone(card, mark) }
                    }
                }
                afterQuickWrite(app)
            } catch (stopped: CancellationException) {
                throw stopped
            } catch (_: Exception) {
                // A button that fails leaves the message as it was; the app still
                // has the tick, and nothing here is worth a crash in the shade.
            } finally {
                pending.finish()
            }
        }
    }

    private fun redrawDigest(context: Context, mark: QuickMark, change: (DigestCard) -> DigestCard) {
        val prefs = TouchPrefs(context)
        val card = synchronized(cardLock) {
            // A message from before this version, or one whose card was lost, is
            // redrawn from the one button that was pressed.
            val stored = prefs.digestCard() ?: DigestCard("Зведення за день", "", listOf(mark))
            change(stored).also { prefs.saveDigestCard(it) }
        }
        postDigest(context, card)
    }

    private companion object {
        val cardLock = Any()
    }
}

/**
 * The SharedPreferences keys this work added, all `tc_`, all in the app's own
 * `flowpay` file. None of them is app data: none is in [Store.exportJson].
 */
class TouchPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("flowpay", Context.MODE_PRIVATE)

    /** The morning message as last drawn, so a button can redraw it. */
    fun digestCard(): DigestCard? =
        prefs.getString("tc_digest", null)?.let { runCatching { digestCardOf(JSONObject(it)) }.getOrNull() }

    fun saveDigestCard(card: DigestCard?) = prefs.edit {
        if (card == null) remove("tc_digest") else putString("tc_digest", digestCardJson(card).toString())
    }

    /** The last mark made from the widget, and the epoch day it was made on. */
    fun widgetUndo(): Pair<QuickMark, Long>? {
        val mark = prefs.getString("tc_widget_undo", null)
            ?.let { runCatching { quickMarkOf(JSONObject(it)) }.getOrNull() }
            ?: return null
        return mark to prefs.getLong("tc_widget_undo_day", 0L)
    }

    fun saveWidgetUndo(mark: QuickMark?, day: Long) = prefs.edit {
        if (mark == null) {
            remove("tc_widget_undo")
            remove("tc_widget_undo_day")
        } else {
            putString("tc_widget_undo", quickMarkJson(mark).toString())
            putLong("tc_widget_undo_day", day)
        }
    }

    /** «Ховати суми поза застосунком»: the widget and the tile say names and dates, not sums. */
    fun hideOutside(): Boolean = prefs.getBoolean("tc_hide_out", false)

    fun saveHideOutside(on: Boolean) = prefs.edit { putBoolean("tc_hide_out", on) }

    /** The eye on Огляд: personal sums on the app's own screens drawn as «•••». */
    fun hideInside(): Boolean = prefs.getBoolean("tc_hide_in", false)

    fun saveHideInside(on: Boolean) = prefs.edit { putBoolean("tc_hide_in", on) }

    /** «Без сум» on the recap deck, as it was last left. */
    fun recapWithoutSums(): Boolean = prefs.getBoolean("tc_recap_nosums", false)

    fun saveRecapWithoutSums(on: Boolean) = prefs.edit { putBoolean("tc_recap_nosums", on) }
}

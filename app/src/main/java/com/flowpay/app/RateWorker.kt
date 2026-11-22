package com.flowpay.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * The light check behind the dollar's corridor (RateWatch.kt): about once an hour,
 * the rate alone, and a notification at once when an edge is crossed or the rate
 * jumps.
 *
 * Light on purpose. It asks Monobank's public `/bank/currency` — one small request —
 * and only when the reading on the phone is older than [RATE_ASK_AFTER_MS]: the
 * Курс screen and the price pass ask the same endpoint, and Monobank answers a second
 * request inside a minute with 429. What is kept goes through [refreshUsdRate], so
 * the NBU fallback never reaches the chart; and [checkRate] turns the NBU's figure
 * away again, so it never crosses an edge either.
 *
 * Scheduled only while something is watched, cancelled when nothing is — a phone
 * without a corridor runs nothing new. HyperOS may still hold a periodic job back
 * for hours; nothing here can promise the minute.
 */
class RateWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val prices = PriceStore(applicationContext)
        val started = prices.rateCorridor(store)
        if (!started.watching) return Result.success()

        val (held, heldAt) = store.fxRate()
        val recentBank = held.source == SOURCE_MONOBANK && heldAt > 0L &&
            System.currentTimeMillis() - heldAt in 0 until RATE_ASK_AFTER_MS
        if (!recentBank) runCatching { refreshUsdRate(store) }

        val (rate, rateAt) = store.fxRate()
        val check = checkRate(
            started, rate, rateAt, System.currentTimeMillis(), store.rateHistory(), LocalDate.now().toEpochDay()
        )
        // Laid onto the corridor as it is now. Edited on the phone while this ran: the
        // edit wins, and the next check measures against it.
        if (prices.rateCorridor(store) != started) return Result.success()
        prices.saveRateCorridor(check.corridor)
        check.news.forEach { notifyRate(applicationContext, it) }
        return Result.success()
    }

    companion object {
        private const val WORK_RATE = "rate-watch"

        /**
         * Runs the check about hourly while the corridor watches anything, and stops
         * it when it does not. Called on launch and whenever the corridor changes.
         */
        fun schedule(context: Context) {
            val watching = PriceStore(context).rateCorridor(Store(context)).watching
            val work = WorkManager.getInstance(context)
            if (!watching) {
                work.cancelUniqueWork(WORK_RATE)
                return
            }
            val request = PeriodicWorkRequestBuilder<RateWorker>(1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_RATE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

/**
 * How recent a bank reading on the phone has to be for the check to use it rather
 * than ask again: the screen or the price pass asked a moment ago.
 */
const val RATE_ASK_AFTER_MS = 45 * 60_000L

private const val CHANNEL_RATE = "rate_watch"
const val ACTION_RATE_KEEP = "com.flowpay.app.action.RATE_KEEP"
const val ACTION_RATE_DONE = "com.flowpay.app.action.RATE_DONE"
const val EXTRA_RATE_EDGE = "edge"

/** One notification per edge, so a second crossing replaces the first. */
private fun rateNotificationId(edge: String): Int = 7_100 + when (edge) {
    EDGE_BELOW -> 1
    EDGE_ABOVE -> 2
    else -> 3
}

private fun notifyRate(context: Context, news: RateNews) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(CHANNEL_RATE, "Курс долара", NotificationManager.IMPORTANCE_DEFAULT)
    )
    val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!allowed) return
    val id = rateNotificationId(news.edge)
    fun answer(action: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        id * 10 + code,
        Intent(context, RateActionReceiver::class.java)
            .setAction(action)
            .putExtra(EXTRA_RATE_EDGE, news.edge),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )
    val text = rateNewsText(news)
    NotificationManagerCompat.from(context).notify(
        id,
        NotificationCompat.Builder(context, CHANNEL_RATE)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentTitle("Курс долара")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openTabIntent(context, TAB_RATE))
            // «Стежити далі» leaves the edge as it is: it speaks again after the
            // rate has come back inside. «Готово» takes the edge away.
            .addAction(0, "Стежити далі", answer(ACTION_RATE_KEEP, 1))
            .addAction(0, "Готово", answer(ACTION_RATE_DONE, 2))
            .setAutoCancel(true)
            .build()
    )
}

/**
 * The two answers on a rate notification. Not exported: only this app's own
 * PendingIntents reach it.
 */
class RateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val edge = intent.getStringExtra(EXTRA_RATE_EDGE) ?: return
        if (intent.action == ACTION_RATE_DONE) {
            val prices = PriceStore(context)
            prices.saveRateCorridor(withoutEdge(prices.rateCorridor(Store(context)), edge))
            RateWorker.schedule(context)
        }
        NotificationManagerCompat.from(context).cancel(rateNotificationId(edge))
    }
}

package com.flowpay.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * The one message a day.
 *
 * This used to be the payment reminder alone, and it sat beside a price channel
 * and a parcel channel that each rang whenever a background pass happened to
 * finish. Three unrelated interruptions, none of them urgent, is how a person
 * learns to swipe a notification away before reading it.
 *
 * Now everything ordinary is collected here and said once, at an hour the user
 * picked. The immediate alerts that remain live in [PriceWorker] and are only the
 * two that a morning would be too late for.
 *
 * The class keeps its old name on purpose. WorkManager stores the worker's class
 * name in its own database against the enqueued request, so a phone that is killed
 * between the rename and the next [schedule] would be left with a periodic job
 * pointing at a class that no longer exists.
 */
class ReminderWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val today = LocalDate.now()

        // Periodic work has no guaranteed time of day and can be run more than once,
        // so the last digested day is recorded and the same day is never repeated.
        if (store.lastReminderDay() == today.toEpochDay()) return Result.success()

        val summary = digest(
            wishes = store.wishes(),
            pays = store.pays(),
            orders = store.orders(),
            today = today,
            usdSellRate = store.fxRate().first.sell,
            income = store.income(),
            holidays = store.holidays(today.year)
        )
        // Nothing happened, so nothing is sent. A daily message saying there is no
        // news is a daily interruption carrying no information.
        if (!summary.empty) notify(summary.title, summary.body)

        store.saveLastReminderDay(today.toEpochDay())
        store.saveLastRunAt(WORK_DIGEST, System.currentTimeMillis())
        return Result.success()
    }

    private fun notify(title: String, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Щоденне зведення", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            NotificationManagerCompat.from(applicationContext).notify(
                CHANNEL.hashCode(),
                NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_popup_reminder)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {
        private const val CHANNEL = "payment_reminders"

        /**
         * Schedules the digest for the hour the user chose.
         *
         * Called again whenever that hour changes: the initial delay is what puts
         * the run at the right time of day, and a periodic request that is already
         * enqueued keeps its old delay until it is replaced.
         */
        fun schedule(context: Context) = enqueue(context, ExistingPeriodicWorkPolicy.UPDATE)

        /**
         * Moves the digest after the user picked a different hour.
         *
         * Cancels rather than updates, because an update keeps the period the
         * existing request is already counting and would leave the new hour taking
         * effect a day late, or not at all.
         */
        fun reschedule(context: Context) =
            enqueue(context, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE)

        private fun enqueue(context: Context, policy: ExistingPeriodicWorkPolicy) {
            val hour = Store(context).digestHour()
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(millisUntilNext(LocalTime.of(hour, 0)), TimeUnit.MILLISECONDS)
                // No network needed: this only reads what is already on the phone.
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_DIGEST, policy, request)
        }

        /** Delay that lands the first run on the next occurrence of [time]. */
        internal fun millisUntilNext(time: LocalTime, from: LocalDateTime = LocalDateTime.now()): Long {
            val next = if (from.toLocalTime() < time) {
                from.toLocalDate().atTime(time)
            } else {
                from.toLocalDate().plusDays(1).atTime(time)
            }
            val zone = ZoneId.systemDefault()
            return next.atZone(zone).toInstant().toEpochMilli() -
                from.atZone(zone).toInstant().toEpochMilli()
        }
    }
}

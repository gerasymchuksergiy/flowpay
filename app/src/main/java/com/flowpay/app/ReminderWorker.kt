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
 * Tells you the day before a standing payment is due.
 *
 * A day of notice is the useful amount: enough to move money or top up a card,
 * not so early that it gets forgotten again.
 */
class ReminderWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val store = Store(applicationContext)
        val today = LocalDate.now()

        // Periodic work has no guaranteed time of day and can be run more than once,
        // so the last reminded day is recorded and the same day is never repeated.
        if (store.lastReminderDay() == today.toEpochDay()) return Result.success()

        val tomorrow = today.plusDays(1)
        val due = paymentsDueOn(store.pays(), tomorrow)
        if (due.isNotEmpty()) {
            val listed = due.joinToString(", ") { "${it.name} ${amountLabel(it.amount, it.currency)}" }
            notify(
                if (due.size == 1) "Завтра оплата" else "Завтра ${due.size} платежі",
                listed
            )
        }
        store.saveLastReminderDay(today.toEpochDay())
        return Result.success()
    }

    private fun notify(title: String, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Нагадування про платежі", NotificationManager.IMPORTANCE_DEFAULT)
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

        /** Roughly when a reminder is worth reading. */
        private val REMIND_AT = LocalTime.of(10, 0)

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setInitialDelay(millisUntilNext(REMIND_AT), TimeUnit.MILLISECONDS)
                // No network needed: this only reads what is already on the phone.
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("payment-reminders", ExistingPeriodicWorkPolicy.UPDATE, request)
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

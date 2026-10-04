package com.flowpay.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Instant
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

        val cachedRate = store.fxRate()
        val rate = cachedRate.first.sell
        // The day the figure was fetched, derived the same way the item page does
        // it, so "how old is the rate" is one answer across the app rather than two.
        val rateDay = cachedRate.second.takeIf { it > 0L }
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate().toEpochDay() }
            ?: 0L
        val rateTarget = store.rateTarget()
        // A promo that ran out is written into its history first, so this morning
        // can say «150 → 300» like any other raise — see PaymentsLife.kt.
        recordPromoEnds(store, today)
        val pays = store.pays()
        val marks = store.paidMarks(today)
        // What payments' lives, the bank statement and the purchases have to say
        // once — said lines are remembered in one place (LifeMemory) and left out.
        val memory = LifeMemory(applicationContext)
        val said = memory.said()
        val mono = MonoStore(applicationContext)
        val client = mono.client()?.takeIf { mono.connected() }
        val once = unsaid(lifeLines(pays, today) + monoMorning(mono, client, pays, marks, today, rate), said)
        // Tomorrow's charges the card cannot cover lead the message — on a balance
        // read within the last day, never an older one.
        val lead = client?.takeIf { System.currentTimeMillis() - mono.clientAt() < 24 * 3_600_000L }
            ?.let { shortTomorrowLine(pays, marks, today, ownUah(it, mono.accountsToRead(it)), rate) }
        val parcelPrefs = ParcelPrefs(applicationContext)
        val summary = digest(
            wishes = store.wishes(),
            pays = pays,
            orders = store.orders(),
            today = today,
            usdSellRate = rate,
            income = store.income(),
            holidays = store.holidaysAround(today),
            rateTarget = rateTarget,
            rateDay = rateDay,
            // The phone holds the NBU's figure whenever Monobank did not answer, and
            // only the bank's can cross the threshold — see [rateTargetLine].
            rateSource = cachedRate.first.source,
            // Read here rather than defaulted, because a default of "nothing was
            // ever paid" is the behaviour this call is being fixed out of: the
            // morning message named bills that had been ticked off on the payments
            // screen days earlier, and a notification cannot be waved away in place
            // the way the pill now can.
            paid = marks,
            lastSaid = store.digestPrices(),
            // A cache of the directory, read here and never fetched: the morning
            // message only reads what is already on the phone.
            points = parcelPrefs.points(),
            now = LocalDateTime.now(),
            said = said,
            lead = listOfNotNull(lead),
            once = once
        )
        // Nothing happened, so nothing is sent. A daily message saying there is no
        // news is a daily interruption carrying no information.
        if (!summary.empty) {
            notify(summary.title, summary.body, summary.actions)
            // Asked of the message, like the rate threshold below: only what it
            // carried is remembered as said.
            memory.markSaid(summary.said)
        }

        // Disarmed after the message rather than before it, and only when the message
        // carried the line — asked of the message itself rather than worked out a
        // second time beside it, because two answers to one question is how a
        // crossing gets stamped as said on a morning that never said it. A
        // threshold silenced for a stale rate, or for the NBU's figure, is still
        // waiting, not still spent.
        if (rateTarget != null && summary.rateTargetSaid) {
            store.saveRateTarget(disarmRateTarget(rateTarget, today.toEpochDay()))
        }

        // What this message saw, so tomorrow's compares against it rather than
        // against a calendar day — see [recentChange].
        store.saveDigestPrices(store.wishes().filter { it.price > 0.0 }.associate { it.id to it.price })
        store.saveLastReminderDay(today.toEpochDay())
        store.saveLastRunAt(WORK_DIGEST, System.currentTimeMillis())
        // Pins tomorrow's run to the chosen hour again. A twenty-four-hour period
        // is absolute time, so each late start and each clock change used to carry
        // over into every day after — on the 25th of October the message would
        // have moved from nine to eight for good.
        schedule(applicationContext)
        return Result.success()
    }

    private fun notify(title: String, text: String, actions: List<DigestAction> = emptyList()) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Щоденне зведення", NotificationManager.IMPORTANCE_DEFAULT)
        )
        val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            val builder = NotificationCompat.Builder(applicationContext, CHANNEL)
                .setSmallIcon(R.drawable.ic_tile)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                // The digest is mostly about money going out, so it opens
                // the payments tab. See Notifications.kt.
                .setContentIntent(openTabIntent(applicationContext, TAB_PAYMENTS))
                .setAutoCancel(true)
            // «Як скасувати» under a trial about to charge: the service's own page,
            // opened in the browser straight from the notification.
            actions.forEachIndexed { index, action ->
                val open = android.app.PendingIntent.getActivity(
                    applicationContext,
                    ACTION_REQUEST_BASE + index,
                    android.content.Intent(android.content.Intent.ACTION_VIEW, action.url.toUri())
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT
                )
                builder.addAction(0, action.label, open)
            }
            NotificationManagerCompat.from(applicationContext).notify(CHANNEL.hashCode(), builder.build())
        }
    }

    /**
     * What the stored statement adds to the morning, each line said once: a
     * charge after a cancellation, a double charge, a missed charge. Nothing
     * without monobank; nothing about a statement never read.
     */
    private fun monoMorning(
        mono: MonoStore,
        client: MonoClient?,
        pays: List<Pay>,
        marks: List<PaidMark>,
        today: LocalDate,
        usdSell: Double
    ): List<OnceLine> {
        if (client == null || mono.lastSync() <= 0L) return emptyList()
        val txs = mono.txs()
        val currencies = accountCurrencies(client)
        val readUpTo = Instant.ofEpochMilli(mono.lastSync()).atZone(ZoneId.systemDefault()).toLocalDate()
        return monoLines(
            missed = missedCharges(pays, txs, marks, today, readUpTo),
            doubles = doubleCharges(pays, txs, mono.doublesOk(), System.currentTimeMillis() / 1000),
            after = chargedAfterCancel(pays, mono.gone(), txs, mono.afterOk(), currencies, usdSell),
            accountCurrency = currencies
        )
    }

    companion object {
        private const val CHANNEL = "payment_reminders"

        /** Request codes for the message's buttons, apart from every other PendingIntent. */
        private const val ACTION_REQUEST_BASE = 7_100

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
            val store = Store(context)
            val now = LocalDateTime.now()
            val nextAt = System.currentTimeMillis() + nextDigestDelay(
                now,
                store.digestHour(),
                ranToday = store.lastReminderDay() == now.toLocalDate().toEpochDay()
            )
            // The exact moment of the next run, rather than an initial delay. An
            // update keeps the original enqueue time and counts the new delay from
            // it, so opening the app at two with the hour set to six fired the
            // message at once, and every day at two after that.
            val request = PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS)
                .setNextScheduleTimeOverride(nextAt)
                // No network needed: this only reads what is already on the phone.
                .setConstraints(Constraints.Builder().build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_DIGEST, policy, request)
        }

        /**
         * How long until the next message should go.
         *
         * The next occurrence of the hour — unless the hour has passed today and
         * today's message has not gone yet, which is what a phone asleep through
         * nine looks like when it is opened at ten. Then soon, rather than pushing
         * the day's message to tomorrow.
         */
        internal fun nextDigestDelay(now: LocalDateTime, hour: Int, ranToday: Boolean): Long {
            val time = LocalTime.of(hour.coerceIn(0, 23), 0)
            return if (!ranToday && now.toLocalTime() >= time) {
                LATE_DIGEST_DELAY_MS
            } else {
                millisUntilNext(time, now)
            }
        }

        /** A minute, so a late message is not sent in the middle of opening the app. */
        private const val LATE_DIGEST_DELAY_MS = 60_000L

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

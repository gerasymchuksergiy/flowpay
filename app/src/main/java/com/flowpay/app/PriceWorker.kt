package com.flowpay.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.glance.appwidget.updateAll
import androidx.work.*
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.TimeUnit

/**
 * The twice-daily background pass: what did prices do, and where are the parcels.
 *
 * Both exist so the app can tell you rather than needing to be opened and asked.
 */
class PriceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val store = Store(applicationContext)

        val today = java.time.LocalDate.now().toEpochDay()

        // Fetched before the prices rather than after, because every price recorded
        // in this pass has to carry the rate of the day it was read. A rate fetched
        // afterwards would be stamped onto readings taken before it.
        val rate = runCatching { usdRate() }.getOrNull()?.takeIf { it.sell > 0 }
        if (rate != null) {
            store.saveFxRate(rate, System.currentTimeMillis())
            // The rate chart needs a point a day. Recording it only when the currency
            // screen is opened would leave the axis full of holes on every day the app
            // was not used, and the cached rate the expenses screen converts with would
            // go stale in exactly the same way.
            store.saveRateHistory(appendRate(store.rateHistory(), rate.sell, today))
        }
        val stamp = rate ?: store.fxRate().first

        val old = store.wishes()
        var pricesRead = 0
        val fresh = old.map { previous ->
            when (val reading = refreshed(previous, today, stamp)) {
                Reading.Failed -> previous
                // A page that answered without a price is still news about the item,
                // so the new freshness is saved. It is deliberately not counted as a
                // price read: a whole list of these is a shop outage, not a success.
                is Reading.Stale -> reading.wish
                is Reading.Priced -> {
                    val current = reading.wish
                    pricesRead++
                    // A price that wobbles by a few hryvnia must not ring the phone twice a
                    // day, so an alert has to beat the last price already announced.
                    val alert = priceAlertFor(previous, current.price)
                    // A wish put aside on purpose is silent until its day. Ringing
                    // the phone about a thing you decided not to think about until
                    // March is the app overruling a decision the user already made.
                    if (!onHold(previous, today)) {
                        // Two of the four still interrupt, and both for the same
                        // reason: they are windows that close. A target that was
                        // asked for by name, and stock that came back and can go
                        // again by morning. A new low and an ordinary fall are good
                        // news that keeps, so they go into the digest instead.
                        when (alert.kind) {
                            AlertKind.TARGET_REACHED -> notify(
                                previous.name,
                                "Досягнуто ціль ${money(previous.targetPrice)} — зараз ${money(current.price)}",
                                CHANNEL_PRICES,
                                "Зміни цін"
                            )
                            AlertKind.BACK_IN_STOCK -> notify(
                                previous.name,
                                "Знову в наявності — ${money(current.price)}",
                                CHANNEL_PRICES,
                                "Зміни цін"
                            )
                            AlertKind.NEW_LOW, AlertKind.DROP, AlertKind.NONE -> Unit
                        }
                    }
                    // Still recorded for the falls that are no longer announced: it
                    // is the benchmark the next target decision is measured against.
                    current.copy(notifiedPrice = alert.notifyPrice)
                }
            }
        }
        store.saveWishes(fresh)

        // A hold that ran out while the app was closed has to announce itself, or
        // the pause quietly becomes a deletion: the card would sit at the foot of
        // the list with nobody ever told it was waiting for an answer.
        fresh.filter { holdEnded(it, today) && it.holdUntil == today }.forEach { wish ->
            notify(wish.name, "Ще хочеш? Пауза скінчилась", CHANNEL_PRICES, "Зміни цін")
        }

        val parcels = store.orders()
        val trackable = parcels.filter { detectCarrier(it.tracking) == CARRIER_NOVA_POSHTA }
        var parcelsRead = 0
        val checkedAt = System.currentTimeMillis()
        val freshParcels = parcels.map { order ->
            if (detectCarrier(order.tracking) != CARRIER_NOVA_POSHTA) return@map order
            val status = runCatching { parcelStatus(order.tracking) }.getOrNull() ?: return@map order
            parcelsRead++
            // A parcel changing stage is news, not an emergency: it goes into the
            // morning digest, which says which parcel needs collecting rather than
            // narrating every leg of its journey.
            val left = freeStorageDaysLeft(status.paidStorageFrom, java.time.LocalDate.now())
            // Storage running out tomorrow is the exception, because by the next
            // digest it is already being billed.
            if (status.stage == AT_BRANCH && left != null && storageIsUrgent(left)) {
                notify(order.name, urgentStorageText(left), CHANNEL_PARCELS, "Статус посилок")
            }
            applyStatus(order, status, checkedAt)
        }
        if (trackable.isNotEmpty()) store.saveOrders(freshParcels)

        // Once a year, and never in a way that can fail the pass. The payment
        // reminder shifts off weekends with or without this; the calendar only
        // adds the days a weekend rule cannot know about.
        val year = java.time.LocalDate.now().year
        if (store.holidays(year).isEmpty()) {
            runCatching { fetchHolidays(year) }.getOrNull()
                ?.takeIf { it.isNotEmpty() }
                ?.let { store.saveHolidays(year, it) }
        }

        // The widget reads the same store, so it is stale the moment this pass
        // writes to it, and nothing else would wake it before its half-hourly turn.
        FlowPayWidget().updateAll(applicationContext)
        FlowPayTileService.refresh(applicationContext)

        // Retry only when there was something to fetch and none of it arrived,
        // which is what a dropped connection looks like from here.
        if (shouldRetryPass(old, trackable.size, pricesRead, parcelsRead)) {
            // Not stamped: a pass that fetched nothing is exactly the pass the
            // health panel exists to make visible, and recording it as a success
            // would paper over the silence it is meant to expose.
            Result.retry()
        } else {
            store.saveLastRunAt(WORK_PRICES, System.currentTimeMillis())
            Result.success()
        }
    }

    private fun notify(name: String, text: String, channelId: String, channelName: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        )
        val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
            applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (allowed) {
            NotificationManagerCompat.from(applicationContext).notify(
                (name + text).hashCode(),
                NotificationCompat.Builder(applicationContext, channelId)
                    .setSmallIcon(android.R.drawable.star_big_on)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {
        private const val CHANNEL_PRICES = "price_changes"

        // Separate channels so parcel news can be silenced without losing price
        // alerts, and the other way round.
        private const val CHANNEL_PARCELS = "parcel_status"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_PRICES, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

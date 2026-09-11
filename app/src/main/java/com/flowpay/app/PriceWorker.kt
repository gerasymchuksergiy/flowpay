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

        val old = store.wishes()
        var pricesRead = 0
        val fresh = old.map { previous ->
            runCatching { refreshed(previous) }.getOrNull()?.let { current ->
                pricesRead++
                // A price that wobbles by a few hryvnia must not ring the phone twice a
                // day, so an alert has to beat the last price already announced.
                val alert = priceAlertFor(previous, current.price)
                when (alert.kind) {
                    AlertKind.TARGET_REACHED -> notify(
                        previous.name,
                        "Досягнуто ціль ${money(previous.targetPrice)} — зараз ${money(current.price)}",
                        CHANNEL_PRICES,
                        "Зміни цін"
                    )
                    AlertKind.NEW_LOW -> notify(
                        previous.name,
                        "Найнижча ціна за весь час: ${money(current.price)}",
                        CHANNEL_PRICES,
                        "Зміни цін"
                    )
                    AlertKind.DROP -> notify(
                        previous.name,
                        "Ціна впала: ${money(previous.price)} → ${money(current.price)}",
                        CHANNEL_PRICES,
                        "Зміни цін"
                    )
                    AlertKind.NONE -> Unit
                }
                current.copy(notifiedPrice = alert.notifyPrice)
            } ?: previous
        }
        store.saveWishes(fresh)

        // The rate chart needs a point a day. Recording it only when the currency
        // screen is opened would leave the axis full of holes on every day the app
        // was not used, and the cached rate the expenses screen converts with would
        // go stale in exactly the same way.
        runCatching { usdRate() }.getOrNull()?.takeIf { it.sell > 0 }?.let { rate ->
            store.saveFxRate(rate, System.currentTimeMillis())
            store.saveRateHistory(
                appendRate(store.rateHistory(), rate.sell, java.time.LocalDate.now().toEpochDay())
            )
        }

        val parcels = store.orders()
        val trackable = parcels.filter { detectCarrier(it.tracking) == CARRIER_NOVA_POSHTA }
        var parcelsRead = 0
        val checkedAt = System.currentTimeMillis()
        val freshParcels = parcels.map { order ->
            if (detectCarrier(order.tracking) != CARRIER_NOVA_POSHTA) return@map order
            val status = runCatching { parcelStatus(order.tracking) }.getOrNull() ?: return@map order
            parcelsRead++
            // Only a genuine change is worth a notification. Re-announcing the same
            // stage twice a day would train you to ignore the channel.
            if (status.stage.isNotBlank() && status.stage != order.status) {
                notify(order.name, statusLine(status), CHANNEL_PARCELS, "Статус посилок")
            }
            // The one number here that costs money to ignore.
            val left = freeStorageDaysLeft(status.paidStorageFrom, java.time.LocalDate.now())
            if (left != null && left in 0..2 && status.stage == AT_BRANCH) {
                notify(
                    order.name,
                    if (left == 0) "Безкоштовне зберігання закінчилось"
                    else "Безкоштовне зберігання ще ${daysLabel(left)}",
                    CHANNEL_PARCELS,
                    "Статус посилок"
                )
            }
            applyStatus(order, status, checkedAt)
        }
        if (trackable.isNotEmpty()) store.saveOrders(freshParcels)

        // The widget reads the same store, so it is stale the moment this pass
        // writes to it, and nothing else would wake it before its half-hourly turn.
        FlowPayWidget().updateAll(applicationContext)

        // Retry only when there was something to fetch and none of it arrived,
        // which is what a dropped connection looks like from here.
        val hadWork = old.isNotEmpty() || trackable.isNotEmpty()
        val gotSomething = pricesRead > 0 || parcelsRead > 0
        if (hadWork && !gotSomething) Result.retry() else Result.success()
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
                .enqueueUniquePeriodicWork("prices", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

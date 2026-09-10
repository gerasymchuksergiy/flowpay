package com.flowpay.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
            runCatching { product(previous.url) }.getOrNull()?.let { current ->
                pricesRead++
                if (current.price < previous.price) {
                    notify(
                        previous.name,
                        "Ціна впала: ${previous.price.toInt()} ₴ → ${current.price.toInt()} ₴",
                        CHANNEL_PRICES,
                        "Зміни цін"
                    )
                }
                if (previous.targetPrice > 0 &&
                    previous.price > previous.targetPrice &&
                    current.price <= previous.targetPrice
                ) {
                    notify(
                        previous.name,
                        "Досягнуто ціль ${previous.targetPrice.toInt()} ₴",
                        CHANNEL_PRICES,
                        "Зміни цін"
                    )
                }
                refreshedWish(previous, current)
            } ?: previous
        }
        store.saveWishes(fresh)

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
            applyStatus(order, status, checkedAt)
        }
        if (trackable.isNotEmpty()) store.saveOrders(freshParcels)

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

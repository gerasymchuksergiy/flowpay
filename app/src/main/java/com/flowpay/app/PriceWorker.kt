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

class PriceWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = coroutineScope {
        val store = Store(applicationContext)
        val old = store.wishes()
        var updated = 0
        val fresh = old.map { previous ->
            runCatching { product(previous.url) }.getOrNull()?.let { current ->
                updated++
                if (current.price < previous.price) notify(previous.name, "Ціна впала: ${previous.price.toInt()} ₴ → ${current.price.toInt()} ₴")
                if (previous.targetPrice > 0 && previous.price > previous.targetPrice && current.price <= previous.targetPrice) {
                    notify(previous.name, "Досягнуто ціль ${previous.targetPrice.toInt()} ₴")
                }
                previous.copy(
                    name = current.name.ifBlank { previous.name },
                    image = current.image.ifBlank { previous.image },
                    price = current.price,
                    history = (previous.history + current.price).filter { it > 0 }.takeLast(90)
                )
            } ?: previous
        }
        store.saveWishes(fresh)
        if (old.isNotEmpty() && updated == 0) Result.retry() else Result.success()
    }

    private fun notify(name: String, text: String) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Зміни цін", NotificationManager.IMPORTANCE_DEFAULT))
        if (android.os.Build.VERSION.SDK_INT < 33 || applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(applicationContext).notify(
                (name + text).hashCode(),
                NotificationCompat.Builder(applicationContext, CHANNEL)
                    .setSmallIcon(android.R.drawable.star_big_on)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {
        private const val CHANNEL = "price_changes"
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<PriceWorker>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("prices", ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}

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
 * Reads a wish's bound Hotline page and folds the reading into its market.
 *
 * The product page and nothing else: hotline's robots.txt disallows `/sr/`, the
 * search, and the app never fetches it — the search only ever opens in the owner's
 * browser. A page that does not answer, or answers without an aggregate offer,
 * leaves the market exactly as it was, the way a shop that times out keeps its price.
 */
suspend fun withMarketRead(wish: Wish, today: Long): Wish {
    val market = wish.market ?: return wish
    val reading = runCatching { parseMarket(pageHtml(market.url)) }.getOrNull() ?: return wish
    return wish.copy(market = withMarketReading(market, reading, today))
}

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
        //
        // And recorded here, not only when the currency screen is opened: the rate
        // chart needs a point a day, and recording it only on a visit would leave the
        // axis full of holes on every day the app was not used, and the cached rate
        // the expenses screen converts with would go stale in exactly the same way.
        // What is kept, and what reaches the chart, is refreshUsdRate's decision.
        val stamp = runCatching { refreshUsdRate(store) }.getOrNull() ?: store.fxRate().first

        // WorkManager stops a worker at ten minutes, and nothing is saved until the
        // end — so on a bad network a long list used to lose the whole pass. Past
        // this budget the remaining wishes keep what they had and the pass saves
        // what it did read; the next pass starts from the top again.
        val deadline = System.currentTimeMillis() + PASS_BUDGET_MS
        // «У мене є Картка Rozetka»: the target is then also met by the card's price.
        val hasCard = PriceStore(applicationContext).rozetkaCard()
        val old = store.wishes()
        var pricesRead = 0
        var pagesAnswered = 0
        val fresh = old.map { previous ->
            if (System.currentTimeMillis() > deadline) return@map previous
            val read = when (val reading = refreshed(previous, today, stamp)) {
                Reading.Failed -> previous
                // A page that answered without a price is still news about the item,
                // so the new freshness is saved. It is deliberately not counted as a
                // price read: a whole list of these is a shop outage, not a success.
                is Reading.Stale -> {
                    pagesAnswered++
                    reading.wish
                }
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
                        when {
                            alert.kind == AlertKind.TARGET_REACHED -> notify(
                                previous.name,
                                "Досягнуто ціль ${money(previous.targetPrice)} — зараз ${money(current.price)}",
                                CHANNEL_PRICES,
                                "Зміни цін"
                            )
                            // Reached only with the Rozetka card: said as exactly that,
                            // with the ordinary price beside it — see PricesMore.kt.
                            cardTargetReached(previous, current, hasCard) -> notify(
                                previous.name,
                                cardTargetText(previous.targetPrice, cardPrice(current, true), current.price),
                                CHANNEL_PRICES,
                                "Зміни цін"
                            )
                            alert.kind == AlertKind.BACK_IN_STOCK -> notify(
                                previous.name,
                                "Знову в наявності — ${money(current.price)}",
                                CHANNEL_PRICES,
                                "Зміни цін"
                            )
                            else -> Unit
                        }
                    }
                    // Still recorded for the falls that are no longer announced: it
                    // is the benchmark the next target decision is measured against.
                    current.copy(notifiedPrice = alert.notifyPrice)
                }
            }
            // The market on Hotline, read with the prices and only its product page.
            // Never a push, whatever it says — the morning digest has the one line.
            if (System.currentTimeMillis() > deadline) read else withMarketRead(read, today)
        }
        // Laid onto the list as it is now, not saved over it. A pass takes minutes,
        // and a wish added, deleted or edited on the phone meanwhile used to be
        // undone by this one line — see Merge.kt.
        store.saveWishes(mergeById(store.wishes(), old, fresh) { it.id })

        // A hold that ran out while the app was closed has to announce itself, or
        // the pause quietly becomes a deletion: the card would sit at the foot of
        // the list with nobody ever told it was waiting for an answer.
        //
        // Once per pause, and within a few days of its end rather than only on the
        // day itself: twice a day it used to ring again, and on a day no pass got
        // to run it never rang at all.
        val alerted = store.alerted().toMutableList()
        fun once(key: String, send: () -> Unit) {
            if (key in alerted) return
            send()
            alerted += key
        }
        fresh.filter { holdEnded(it, today) && it.holdUntil in (today - HOLD_GRACE_DAYS)..today }
            .forEach { wish ->
                once("hold-${wish.id}-${wish.holdUntil}") {
                    val reason = wish.why.takeIf { it.isNotBlank() }?.let { " Ти писав: «$it»." }.orEmpty()
                    notify(wish.name, "Ще хочеш? Пауза скінчилась.$reason", CHANNEL_PRICES, "Зміни цін")
                }
            }

        val parcels = store.orders()
        // The owner's number for Nova Poshta, when given — see ParcelsMore.kt.
        val myPhone = ParcelPrefs(applicationContext).phone()
        // A filed purchase is history and a download has no carrier, so neither is
        // asked about. The filed ones used to be asked twice a day for ever.
        fun followed(order: Order) = order.archivedDay == 0L && isAutoTracked(order)
        // A purchase being sent back is filed, so the line above skips it; its
        // return waybill is followed on its own, into its own fields.
        val trackable = parcels.filter { followed(it) || followsReturn(it) }
        var parcelsRead = 0
        val checkedAt = System.currentTimeMillis()
        val freshParcels = parcels.map { order ->
            if (System.currentTimeMillis() > deadline + PARCEL_BUDGET_MS) return@map order
            if (followsReturn(order)) {
                val refund = order.refund ?: return@map order
                val back = runCatching { parcelStatus(refund.tracking, myPhone) }.getOrNull() ?: return@map order
                parcelsRead++
                return@map applyReturnStatus(order, back, checkedAt, today)
            }
            if (!followed(order)) return@map order
            val status = runCatching { parcelStatus(order.tracking, phoneFor(order, myPhone)) }.getOrNull()
                ?: return@map order
            parcelsRead++
            // A parcel changing stage is news, not an emergency: it goes into the
            // morning digest, which says which parcel needs collecting rather than
            // narrating every leg of its journey.
            val left = freeStorageDaysLeft(status.paidStorageFrom, java.time.LocalDate.now())
            // Storage running out tomorrow is the exception, because by the next
            // digest it is already being billed.
            // Once per day of the countdown: after free storage ran out it used to
            // ring twice a day, every day, until the parcel was collected.
            if (status.stage == AT_BRANCH && left != null && storageIsUrgent(left)) {
                once("storage-${order.id}-${status.paidStorageFrom}-$left") {
                    notify(order.name, urgentStorageText(left), CHANNEL_PARCELS, "Статус посилок")
                }
            }
            applyStatus(order, status, checkedAt)
        }
        if (trackable.isNotEmpty()) {
            store.saveOrders(mergeById(store.orders(), parcels, freshParcels) { it.id })
        }
        store.saveAlerted(alerted)
        // The hours of the points parcels are waiting at, asked once a week each.
        // Never allowed to fail the pass: they are a nicety on the parcel page.
        try {
            refreshPickupPoints(applicationContext, freshParcels)
        } catch (stopped: kotlinx.coroutines.CancellationException) {
            throw stopped
        } catch (failed: Exception) {
            // Nothing: the next pass asks again.
        }

        // Once a year, and never in a way that can fail the pass. The payment
        // reminder shifts off weekends with or without this; the calendar only
        // adds the days a weekend rule cannot know about.
        // In December, next year's as well — see [Store.holidaysAround].
        val now = java.time.LocalDate.now()
        val years = if (now.monthValue == 12) listOf(now.year, now.year + 1) else listOf(now.year)
        years.filter { store.holidays(it).isEmpty() }.forEach { year ->
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
        if (shouldRetryPass(old, trackable.size, pricesRead, parcelsRead, pagesAnswered)) {
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
        val tab = if (channelId == CHANNEL_PARCELS) TAB_ORDERS else TAB_WISHES
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
                    .setSmallIcon(R.drawable.ic_tile)
                    .setContentTitle(name)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(openTabIntent(applicationContext, tab))
                    // The same alert posted again by a later pass replaces the
                    // first silently rather than ringing a second time.
                    .setOnlyAlertOnce(true)
                    .setAutoCancel(true)
                    .build()
            )
        }
    }

    companion object {
        private const val CHANNEL_PRICES = "price_changes"

        /** What the price half of a pass may spend before it stops asking shops. */
        private const val PASS_BUDGET_MS = 7 * 60_000L

        /** And the parcels after it, leaving a minute for saving and the widget. */
        private const val PARCEL_BUDGET_MS = 90_000L

        /** How long after a pause ends it may still be announced, if no pass ran. */
        private const val HOLD_GRACE_DAYS = 3L

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

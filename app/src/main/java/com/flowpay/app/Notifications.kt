package com.flowpay.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.net.toUri

/**
 * Where tapping a notification goes.
 *
 * Nowhere, until now: none of the app's notifications carried a content intent,
 * so a tap on «Досягнуто ціль» or on the morning digest did nothing at all — and
 * `setAutoCancel` only clears a notification that has one, so it did not even go
 * away. «Ще хочеш? Пауза скінчилась» asked a question that could not be answered
 * by touching it.
 *
 * Each notification opens the tab it is about. The request code is the tab, so
 * the parcel and the price notifications get separate PendingIntents rather than
 * one overwriting the other's extra.
 */
fun openTabIntent(context: Context, tab: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        tab,
        Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_TAB)
            .putExtra(EXTRA_TAB, tab)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

// ------------------------------------------------------------ the morning message

/** The morning message's channel. Named when it was the payment reminder alone, and kept. */
const val DIGEST_CHANNEL = "payment_reminders"

/** The one id the morning message lives under, so a button redraws it in place. */
val DIGEST_ID = DIGEST_CHANNEL.hashCode()

/** Request codes of the buttons, one per slot, clear of the tabs' codes above. */
private const val BUTTON_REQUEST = 100

/**
 * Posts the morning message, or redraws it after one of its buttons.
 *
 * The text is the morning's; the title and the buttons follow what was pressed
 * (see [digestTitle] and [digestButtons]). A redraw never rings a second time.
 */
fun postDigest(context: Context, card: DigestCard) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
        NotificationChannel(DIGEST_CHANNEL, "Щоденне зведення", NotificationManager.IMPORTANCE_DEFAULT)
    )
    val allowed = android.os.Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    if (!allowed) return
    val builder = NotificationCompat.Builder(context, DIGEST_CHANNEL)
        .setSmallIcon(R.drawable.ic_tile)
        .setContentTitle(digestTitle(card))
        .setContentText(card.body)
        .setStyle(NotificationCompat.BigTextStyle().bigText(card.body))
        // The digest is mostly about money going out, so it opens the payments tab.
        .setContentIntent(openTabIntent(context, TAB_PAYMENTS))
        .setAutoCancel(true)
        // The first post rings; a button redrawing it in place does not.
        .setOnlyAlertOnce(true)
    digestButtons(card).forEachIndexed { slot, button ->
        builder.addAction(0, digestButtonLabel(button), buttonIntent(context, button, slot))
    }
    // After the payments' buttons, so «Скасувати» stays first; digestButtons left
    // room for these.
    digestLinks(card).forEachIndexed { slot, link ->
        builder.addAction(0, link.label, linkIntent(context, link, slot))
    }
    NotificationManagerCompat.from(context).notify(DIGEST_ID, builder.build())
}

/** «Як скасувати …»: the service's own page in the browser, straight from the shade. */
private fun linkIntent(context: Context, link: DigestAction, slot: Int): PendingIntent =
    PendingIntent.getActivity(
        context,
        LINK_REQUEST + slot,
        Intent(Intent.ACTION_VIEW, link.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

/** Request codes of the link buttons, clear of the payment buttons' slots. */
private const val LINK_REQUEST = 7_100

/**
 * One button, as a broadcast to [QuickMarkReceiver]. Explicit, so the receiver
 * needs no intent filter and stays unexported; a request code per slot, so the
 * three buttons do not overwrite each other's extras.
 */
private fun buttonIntent(context: Context, button: DigestButton, slot: Int): PendingIntent =
    PendingIntent.getBroadcast(
        context,
        BUTTON_REQUEST + slot,
        Intent(context, QuickMarkReceiver::class.java)
            .setAction(if (button is DigestButton.Undo) ACTION_QUICK_UNDO else ACTION_QUICK_MARK)
            .putExtra(EXTRA_PAY_NAME, button.mark.name)
            .putExtra(EXTRA_PAY_MONTH, button.mark.month),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

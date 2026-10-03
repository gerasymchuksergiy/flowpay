package com.flowpay.app

import android.app.PendingIntent
import android.content.Context
import android.content.Intent

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

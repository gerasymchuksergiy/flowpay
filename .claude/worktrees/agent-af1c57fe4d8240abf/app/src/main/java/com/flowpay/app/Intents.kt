package com.flowpay.app

import android.content.Intent

/**
 * The ways FlowPay can be opened to do something, rather than just to be looked at.
 *
 * Three entry points arrive here — the share sheet, and the two launcher
 * shortcuts — and each carries its instruction in the intent's action alone.
 * Turning an intent into one of these is the whole of the logic, so it is a
 * function over two strings and can be tested without an Activity.
 */

// Explicit component intents, so neither action needs an <intent-filter> that
// would also let any other app trigger them.
const val ACTION_ADD_WISH = "com.flowpay.app.action.ADD_WISH"
const val ACTION_REFRESH_PRICES = "com.flowpay.app.action.REFRESH_PRICES"

sealed interface AppCommand {
    /** A message shared into the app. The link still has to be found inside it. */
    data class AddShared(val text: String) : AppCommand

    data object AddWish : AppCommand

    data object RefreshPrices : AppCommand
}

/**
 * Null for an ordinary launch, which is every case the app already handled.
 *
 * A share with no text at all is nothing to act on: answering it with the add
 * dialog would look like the app had misread something it never received.
 */
fun appCommand(action: String?, sharedText: String?): AppCommand? = when (action) {
    Intent.ACTION_SEND -> sharedText?.takeIf { it.isNotBlank() }?.let { AppCommand.AddShared(it) }
    ACTION_ADD_WISH -> AppCommand.AddWish
    ACTION_REFRESH_PRICES -> AppCommand.RefreshPrices
    else -> null
}

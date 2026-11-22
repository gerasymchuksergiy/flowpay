package com.flowpay.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

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
const val ACTION_SCAN = "com.flowpay.app.action.SCAN"

/** Opens one tab. Sent only by this app's own notifications, as a PendingIntent. */
const val ACTION_OPEN_TAB = "com.flowpay.app.action.OPEN_TAB"
const val EXTRA_TAB = "tab"

sealed interface AppCommand {
    /** A message shared into the app. The link still has to be found inside it. */
    data class AddShared(val text: String) : AppCommand

    data object AddWish : AppCommand

    data object RefreshPrices : AppCommand

    /** «Сканувати QR» from the launcher: open the scanner. See QrScan.kt. */
    data object Scan : AppCommand

    /** A notification was tapped: show the tab it was about. */
    data class OpenTab(val tab: Int) : AppCommand
}

/**
 * Null for an ordinary launch, which is every case the app already handled.
 *
 * A share with no text at all is nothing to act on: answering it with the add
 * dialog would look like the app had misread something it never received.
 */
fun appCommand(action: String?, sharedText: String?, tab: Int = -1): AppCommand? = when (action) {
    Intent.ACTION_SEND -> sharedText?.takeIf { it.isNotBlank() }?.let { AppCommand.AddShared(it) }
    ACTION_ADD_WISH -> AppCommand.AddWish
    ACTION_REFRESH_PRICES -> AppCommand.RefreshPrices
    ACTION_SCAN -> AppCommand.Scan
    ACTION_OPEN_TAB -> AppCommand.OpenTab(tab.takeIf { it in TAB_WISHES..TAB_OVERVIEW } ?: TAB_WISHES)
    else -> null
}

/**
 * Opens a web address in whatever the phone uses for one, or does nothing.
 *
 * Every "До магазину" and "Відстежити" used to call startActivity directly, and a
 * phone with no app willing to take the address — a stripped browser, a broken
 * default — threw on the tap and took FlowPay down with it.
 */
fun openLink(context: Context, url: String): Boolean {
    if (url.isBlank()) return false
    return runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.trim().toUri()))
    }.isSuccess
}

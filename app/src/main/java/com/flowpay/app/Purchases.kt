package com.flowpay.app

import java.net.URI
import java.net.URLEncoder

/**
 * What kind of purchase an order is, and therefore what its card can honestly say.
 *
 * The purchases tab was built for one shape of thing: a box with a Nova Poshta
 * number on it. Two other shapes arrived on the phone and the tab described both
 * of them as that box. A game bought on Steam sat under «Замовлено» with a four-stop
 * delivery rail it would never move along; a Temu parcel carried by another post
 * had the same rail plus a card of Nova Poshta timings saying «ще не питав» about a
 * carrier the app cannot ask. Neither could be closed, because the only way to
 * close a purchase was to reach the last stop of a rail that had no reason to move.
 *
 * Everything here is a plain function over an [Order], so the card and the detail
 * page decide the same way and a test pins each decision.
 */

/**
 * Shops that only ever sell things that do not travel.
 *
 * Matched on the host and its subdomains. Each entry is a shop whose whole catalogue
 * is codes, keys and downloads; a shop that also sells hardware — the PlayStation
 * Direct store, xbox.com, nintendo.com — is left out on purpose, because calling a
 * console a download would hide the one parcel that really needs following. Anything
 * missed here is one switch away on the add and edit forms.
 */
val DIGITAL_STORE_HOSTS = listOf(
    "steampowered.com",
    "steamcommunity.com",
    "store.epicgames.com",
    "gog.com",
    "store.playstation.com",
    "store.ubisoft.com",
    "shop.battle.net",
    "ea.com",
    "humblebundle.com",
    "fanatical.com",
    "itch.io",
    "play.google.com",
    "apps.apple.com",
    "apps.microsoft.com",
    "kinguin.net",
    "g2a.com"
)

/** Whether this link is a shop that sells nothing with a delivery. */
fun isDigitalStore(url: String): Boolean {
    val host = runCatching { URI(url.trim()).host }.getOrNull()
        ?.lowercase()
        ?.removePrefix("www.")
        ?: return false
    return DIGITAL_STORE_HOSTS.any { host == it || host.endsWith(".$it") }
}

/** The overline a digital purchase carries instead of a delivery stage. */
const val DIGITAL_LABEL = "Цифрова покупка"

/** What the card says above the name: the stage, or that there is no delivery. */
fun orderOverline(order: Order): String = if (order.digital) DIGITAL_LABEL else order.status

/** Whether this is a parcel the app reads from Nova Poshta itself. */
fun isAutoTracked(order: Order): Boolean =
    !order.digital && detectCarrier(order.tracking) == CARRIER_NOVA_POSHTA

/**
 * Whether the Nova Poshta blocks have anything true to say.
 *
 * The scan time, the route, the weight, the observation log — all of it comes from
 * one carrier's answer. On anything that carrier has never answered for, every row
 * is a blank or a «ще не», and a page of those reads as a parcel that is lost
 * rather than as a parcel the app simply does not follow. A parcel that was
 * followed once and has since had its number edited keeps what it learned.
 */
fun carrierSectionsApply(order: Order): Boolean =
    !order.digital &&
        (isAutoTracked(order) || !order.details.isEmpty || order.sightings.isNotEmpty())

/**
 * The one sentence a purchase the app cannot follow needs, or null when it can.
 *
 * Its job is to stop a still rail reading as a stuck parcel, and to say where the
 * stage is set from instead — the stops were always tappable, and nothing on the
 * screen ever said so.
 */
fun untrackedNote(order: Order): String? = when {
    order.digital -> "Без доставки — стежити нема за чим. Коли все прийшло на акаунт, " +
        "завершіть покупку."
    isAutoTracked(order) -> null
    order.tracking.isBlank() -> "Трек-номера ще немає. Етап можна змінити дотиком."
    else -> "Це не номер Нової Пошти, тож FlowPay сам його не перевіряє. " +
        "Етап змінюється дотиком, а де посилка — видно на 17TRACK."
}

/**
 * The label on the button that files a purchase away.
 *
 * Offered at every stage rather than only at the last one. It used to appear only
 * once the rail reached «Отримано», which on a parcel the app cannot follow — and on
 * a download that has no rail at all — meant never.
 */
fun closeActionLabel(order: Order): String =
    if (order.digital || order.status == RECEIVED) "Завершити покупку" else "Отримав — завершити"

/**
 * Whether the close button is the thing the card is waiting for.
 *
 * Lime only then: a received parcel and a finished download have nothing else left
 * to do. On a parcel still on its way the same button is a quieter offer, because
 * one lime surface per card on a list of them is how an accent stops meaning anything.
 */
fun closeActionDue(order: Order): Boolean = order.digital || order.status == RECEIVED

/**
 * Where a number this app cannot read is followed instead.
 *
 * 17TRACK reads several thousand carriers, including Ukrposhta and the European
 * posts that carry Temu's parcels into it, and it opens in Ukrainian. Null for an
 * empty number and for Nova Poshta, which the app follows itself.
 */
fun trackingPageUrl(order: Order): String? {
    val number = order.tracking.filter { !it.isWhitespace() }
    if (order.digital || number.isEmpty() || isAutoTracked(order)) return null
    return "https://t.17track.net/uk#nums=" + URLEncoder.encode(number, "UTF-8")
}

/** Where the open/shut state of the purchase archive is remembered. */
const val SECTION_ORDER_ARCHIVE = "orderarchive"

/** Ukrainian plural for how many purchases are filed. */
fun purchasesLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "покупок"
        last == 1 -> "покупка"
        last in 2..4 -> "покупки"
        else -> "покупок"
    }
    return "$count $word"
}

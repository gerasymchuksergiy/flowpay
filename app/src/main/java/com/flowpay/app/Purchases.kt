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
        "Етап змінюється дотиком, а де посилка — видно на сайті ${trackingSite(order)?.name ?: "перевізника"}."
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

/** A carrier's own tracking page, and what to call it on a button. */
data class TrackingSite(val name: String, val url: String)

// A UPU S10 number: two letters, nine digits, the country of the post that
// accepted it. RL778364634EE is Estonia's; a number ending UA is Ukrposhta's own.
private val S10 = Regex("""^[A-Z]{2}\d{9}[A-Z]{2}$""")
private val UKRPOSHTA_DOMESTIC = Regex("""^\d{13}$""")

/**
 * Where a number this app cannot read is followed instead, or null.
 *
 * The carrier's own page where the number says who it is: thirteen digits, or an
 * S10 ending in UA — the suffix only Ukraine's designated post may issue — is
 * Ukrposhta. Everything else — a foreign
 * post's S10, a marketplace's own number — goes to 17TRACK, which reads several
 * thousand carriers, follows a Temu parcel across both posts, and opens in
 * Ukrainian. None of these has a public API this app could ask instead: Ukrposhta
 * and Meest both want a contract token. Null for an empty number, for a download,
 * and for Nova Poshta, which the app follows itself.
 */
fun trackingSite(order: Order): TrackingSite? {
    val number = order.tracking.filter { !it.isWhitespace() }.uppercase()
    if (order.digital || number.isEmpty() || isAutoTracked(order)) return null
    val encoded = URLEncoder.encode(number, "UTF-8")
    return when {
        UKRPOSHTA_DOMESTIC.matches(number) || (S10.matches(number) && number.endsWith("UA")) ->
            TrackingSite("Укрпошти", "https://track.ukrposhta.ua/tracking_UA.html?barcode=$encoded")
        else -> TrackingSite("17TRACK", "https://t.17track.net/uk#nums=$encoded")
    }
}

/** Just the address of [trackingSite]. */
fun trackingPageUrl(order: Order): String? = trackingSite(order)?.url

// A Nova Poshta number as people paste it: fourteen digits, sometimes in groups.
private val SPACED_NP = Regex("""(?<![\d])(?:\d[  ]?){13}\d(?![\d])""")
private val S10_ANYWHERE = Regex("""\b[A-Z]{2}\d{9}[A-Z]{2}\b""")

/**
 * A tracking number inside shared or pasted text, or null.
 *
 * What arrives from Viber or an SMS is a sentence — «Ваше відправлення 2045 0000
 * 0000 01 прямує…» — not a number. A Nova Poshta number first, because it is the
 * one the app can follow; then a postal S10 number. Thirteen-digit Ukrposhta
 * numbers are not looked for in free text: a phone number with a country code is
 * twelve or thirteen digits, and a parcel is not worth mistaking one for.
 */
fun trackingNumberIn(text: String?): String? {
    if (text.isNullOrBlank()) return null
    SPACED_NP.findAll(text).forEach { match ->
        val digits = match.value.filter { it.isDigit() }
        if (digits.length == 14) return digits
    }
    return S10_ANYWHERE.find(text.uppercase())?.value
}

/** What a parcel added from its number alone is called until it is renamed. */
fun parcelNameFor(number: String): String = "Посилка …${number.takeLast(4)}"

// ------------------------------------------------------------ returns

/** The return windows offered on filing. Nought is "not tracking it". */
val RETURN_CHOICES = listOf(14, 30, 90, 0)

/** Two weeks: the statutory window for a distance purchase in Ukraine. */
const val RETURN_DAYS_DEFAULT = 14

/** Temu's own policy is ninety days. */
const val RETURN_DAYS_TEMU = 90

/** Said under the chips, because the law has exceptions the app cannot know about. */
const val RETURN_NOTE = "Для покупок онлайн закон дає 14 днів, але є винятки — перевір умови магазину."

/**
 * The window a purchase starts with when it is filed.
 *
 * None for a download: once a game key is revealed it is usually not returnable,
 * and a reminder about it would be noise. Temu's ninety days for Temu; the
 * statutory fourteen for everything else, including a Black Friday buy — the
 * consumer service says a «акційний товар поверненню не підлягає» label is unlawful.
 */
fun defaultReturnDays(order: Order): Int = when {
    order.digital -> 0
    order.url.contains("temu.", ignoreCase = true) -> RETURN_DAYS_TEMU
    else -> RETURN_DAYS_DEFAULT
}

fun returnChoiceLabel(days: Int): String = if (days <= 0) "не стежити" else daysLabel(days)

/** Days left to send it back on [today], or null when nothing is tracked or it is over. */
fun returnDaysLeft(order: Order, today: Long): Int? =
    order.returnBy.takeIf { it > 0L && it >= today }?.let { (it - today).toInt() }

/** The line on the archived card while the window is open. */
fun returnLine(order: Order, today: Long): String? {
    val left = returnDaysLeft(order, today) ?: return null
    val until = formatDate(java.time.LocalDate.ofEpochDay(order.returnBy))
    return if (left == 0) "Повернути можна ще сьогодні" else "Повернути можна до $until · ще ${daysLabel(left)}"
}

/** How close to the end a window has to be before the morning message says so. */
const val RETURN_WARN_DAYS = 2

/** One line per window about to close, for the morning message. */
fun returnLines(orders: List<Order>, today: Long): List<String> =
    orders.mapNotNull { order ->
        val left = returnDaysLeft(order, today) ?: return@mapNotNull null
        if (left > RETURN_WARN_DAYS) return@mapNotNull null
        val whenText = when (left) {
            0 -> "сьогодні останній день"
            1 -> "завтра останній день"
            else -> "ще ${daysLabel(left)}"
        }
        "Повернення «${order.name}»: $whenText"
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

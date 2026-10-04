package com.flowpay.app

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Parcels and purchases, the second round (4 October 2026, the owner's «додай все»).
 *
 * A purchase used to end at «Залишаю». This file is what comes before and after
 * that: a waybill shared from an SMS, the money a parcel will still take at the
 * counter, when the pickup point is open, sending a thing back and waiting for the
 * money, the warranty, and — three weeks on — whether the thing was any good.
 *
 * Pure functions over plain data, as everywhere else; the drawing is in
 * PurchasesUi.kt and the two network calls in ParcelsNet.kt. Every JSON key and
 * preference key added here starts with `pk`, because other work was adding keys
 * to the same records on the same day.
 */

// ------------------------------------------------------------ a waybill in a shared text

private val ANY_LINK = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)

/** Nova Poshta's own sites: a tracking link there carries the waybill inside it. */
val NOVA_POSHTA_HOSTS = listOf("novaposhta.ua", "novapost.com", "np.ua")

/**
 * The Nova Poshta waybill a shared message is about, or null.
 *
 * Checked before the link, which is the whole fix: an SMS or a Viber message from
 * a shop says «Ваше замовлення відправлено, ТТН 2045 0000 0000 01» and then gives
 * a link to the order — and the link used to win, so the message tried to become a
 * wish. The waybill is the errand; the link is the shop's.
 *
 * Digits inside an address do not count. A product page's id is a run of digits
 * too, and fourteen of them in a row inside `https://shop/p/…` must not turn a
 * plain product link into a parcel. The one exception is Nova Poshta's own link,
 * where the number in the address is exactly the waybill.
 *
 * Only the fourteen-digit Nova Poshta number takes this road. A postal S10 number
 * («RL…EE») next to a link stays with the link, as before: a marketplace message
 * carries both, and the app cannot follow the S10 anyway.
 */
fun sharedParcelNumber(text: String?): String? {
    if (text.isNullOrBlank()) return null
    novaPoshtaNumberIn(ANY_LINK.replace(text, " "))?.let { return it }
    ANY_LINK.findAll(text).forEach { match ->
        if (isNovaPoshtaLink(match.value)) novaPoshtaNumberIn(match.value)?.let { return it }
    }
    return null
}

/** Whether a link is Nova Poshta's own site. */
fun isNovaPoshtaLink(url: String): Boolean {
    val host = runCatching { java.net.URI(url.trim().trimEnd('.', ',', ')', '»')).host }.getOrNull()
        ?.lowercase(Locale.ROOT) ?: return false
    return NOVA_POSHTA_HOSTS.any { host == it || host.endsWith(".$it") }
}

/**
 * The parcel a shared waybill is already on the list as, or null.
 *
 * The same SMS arrives three times for one parcel — sent, arrived, storage ending
 * — and sharing the second should open the parcel already there rather than add it
 * again. A return waybill counts too: that parcel is the purchase being returned.
 */
fun knownParcel(orders: List<Order>, number: String): Order? {
    val wanted = trackingKey(number)
    if (wanted.isEmpty()) return null
    return orders.firstOrNull { order ->
        trackingKey(order.tracking) == wanted || order.refund?.let { trackingKey(it.tracking) } == wanted
    }
}

/** A tracking number as compared: no spaces, one case — «2045 0000 0000 01» is 20450000000001. */
private fun trackingKey(number: String): String = number.filter { !it.isWhitespace() }.uppercase(Locale.ROOT)

// ------------------------------------------------------------ the owner's phone

/**
 * A phone number in the one shape Nova Poshta takes it: 380 and nine digits.
 *
 * Typed any way a person types it — «050 123 45 67», «+38 (050) 123-45-67»,
 * «501234567». Empty for anything that is not a Ukrainian mobile or landline, so a
 * half-typed number is never sent as if it were one.
 */
fun normalizedPhone(raw: String): String {
    val digits = raw.filter { it.isDigit() }
    return when {
        digits.length == 12 && digits.startsWith("380") -> digits
        digits.length == 11 && digits.startsWith("80") -> "3$digits"
        digits.length == 10 && digits.startsWith("0") -> "38$digits"
        digits.length == 9 && !digits.startsWith("0") -> "380$digits"
        else -> ""
    }
}

/**
 * The number with all but its last two digits hidden: «+380 •• ••• •• 67».
 *
 * What the settings row shows. Enough to recognise one's own number by, and not
 * the number itself on a screen that gets shown to people.
 */
fun maskedPhone(phone: String): String {
    val clean = normalizedPhone(phone)
    if (clean.isEmpty()) return ""
    return "+380 •• ••• •• ${clean.takeLast(2)}"
}

/**
 * Which number a parcel is asked about with.
 *
 * Its own «Номер одержувача» when it has one — a parcel for somebody else is
 * answered in full only for that somebody's number — and the owner's otherwise.
 * Empty when neither is known, which is how every parcel was asked before.
 */
fun phoneFor(order: Order, mine: String): String =
    normalizedPhone(order.recipientPhone).ifEmpty { normalizedPhone(mine) }

/** Said in the «Оплата» fold when no number is set and the carrier said little. */
const val PHONE_HINT = "Без номера телефону Нова пошта показує не все: суму післяплати, " +
    "вартість доставки й відправника. Номер вказується тут: Огляд → Налаштування."

/** Under the field itself, so the reason to type it and where it goes are one read. */
const val PHONE_NOTE = "Нова пошта віддає повну інформацію про посилку тільки з номером " +
    "одержувача: скільки доплатити при отриманні, вартість доставки, платне зберігання, " +
    "відправника. Номер лишається на телефоні — його немає ні в таблиці витрат, ні в " +
    "резервній копії, ні в картинці підсумку."

// ------------------------------------------------------------ cash on delivery

/** A parcel that will still take money at the counter, and on which day. */
data class CodDue(
    val orderId: String,
    val name: String,
    val amount: Double,
    /**
     * Today when it is already waiting, else the day the carrier expects to deliver
     * — today again if that day has passed. Null when the carrier has named no day:
     * the money is still owed, but no single day of the forecast can claim it.
     */
    val day: LocalDate?
)

/**
 * Whether a parcel in trouble is still coming at all.
 *
 * A deleted number, a refusal, a return to the sender — none of those will ever be
 * paid for. A courier who found nobody home (111) is the exception: that parcel is
 * still on its way to being paid for.
 */
private fun stillComing(order: Order): Boolean = !order.problem || order.statusCode == 111

/**
 * Every open parcel that still asks for money on collection.
 *
 * What the carrier says is owed (`AmountToPay`), never an estimate. When Nova
 * Poshta answers nought — paid in its app, or collected — the parcel simply drops
 * out, so this needs no «оплачено» of its own. A parcel already in hand is not
 * counted even if the last answer still carried a sum: the money left with it.
 */
fun codDues(orders: List<Order>, today: LocalDate): List<CodDue> = orders
    .filter {
        it.archivedDay == 0L && !it.digital && it.amountToPay > 0.0 &&
            it.status != RECEIVED && stillComing(it)
    }
    .map { order ->
        val day = when {
            order.status == AT_BRANCH -> today
            order.scheduledDelivery > 0L ->
                LocalDate.ofEpochDay(order.scheduledDelivery).let { if (it.isBefore(today)) today else it }
            else -> null
        }
        CodDue(order.id, order.name, order.amountToPay, day)
    }
    .sortedWith(compareBy(nullsLast()) { it.day })

/** Everything the parcels will still take, together. */
fun codTotal(dues: List<CodDue>): Double = dues.sumOf { it.amount }

/**
 * The line under «Вільно до кінця місяця»: «ще 2 посилки до оплати: 1 498 ₴».
 *
 * A line of its own rather than a change to the big figure, which stays what it has
 * always been — income less the standing costs. Null when nothing is owed.
 */
fun codLine(dues: List<CodDue>): String? {
    if (dues.isEmpty()) return null
    return "ще ${parcelsLabel(dues.size)} до оплати: ${money(codTotal(dues))}"
}

/**
 * The forecast with the parcels' money added to its days.
 *
 * Judged by the very same [moneySky] as the payments, so a 1 249 ₴ parcel rains
 * exactly as a 1 249 ₴ bill would. Applied on top of [moneyWeather] rather than
 * inside it, so the forecast's own rules stay where they are.
 */
fun weatherWithParcels(days: List<MoneyDay>, dues: List<CodDue>, income: Double, free: Double): List<MoneyDay> =
    days.map { day ->
        val here = dues.filter { it.day == day.date }
        if (here.isEmpty()) {
            day
        } else {
            val leaving = day.leaving + codTotal(here)
            val sky = moneySky(leaving, income, free)
            day.copy(emoji = sky.emoji, word = sky.word, leaving = leaving, names = day.names + here.map { it.name })
        }
    }

/** One chip in the forecast: «📦 1 249 ₴ · Навушники», with the day when it is not today. */
data class CodChip(val emoji: String, val text: String)

/** The chips for the parcels that fall inside the forecast's [days]. */
fun codChips(dues: List<CodDue>, today: LocalDate, days: Int = 7): List<CodChip> = dues
    .filter { it.day != null && !it.day.isBefore(today) && it.day.isBefore(today.plusDays(days.toLong())) }
    .map { due ->
        val whenText = when (due.day) {
            today -> null
            today.plusDays(1) -> "завтра"
            else -> weekdayShort(due.day!!)
        }
        CodChip("📦", listOfNotNull(whenText, money(due.amount), due.name).joinToString(" · "))
    }

// ------------------------------------------------------------ the pickup point

/** Opening hours on one day. [allDay] for a locker that never shuts. */
data class DayHours(val open: LocalTime, val close: LocalTime) {
    val allDay: Boolean get() = open <= LocalTime.of(0, 1) && close >= LocalTime.of(23, 59)
}

/**
 * What Nova Poshta's public directory says about one branch or locker.
 *
 * Asked once by its `Ref` (`Address.getWarehouses`, no key) and kept a week —
 * opening hours and a generator do not change between two checks of a parcel.
 *
 * **Which of the three schedules is the pickup one.** The directory sends
 * `Schedule`, `Reception` and `Delivery`, and on a branch they disagree. Live
 * answers on 4 October 2026 settled it: on all 200 Kyiv parcel lockers asked —
 * places parcels are collected from at any hour the locker is reachable —
 * `Delivery` is "-" (never), and on 162 of them `Reception` is "-" too, while
 * `Schedule` is "00:01-23:59" (166 of them) or the hours of the shop the locker
 * stands in. Were either of the other two the collecting hours, no locker could
 * ever be collected from. On 279 of 300 Kyiv branches `Schedule` and `Reception`
 * end together on weekdays (21:00) while `Delivery` ends an hour earlier; at the
 * weekend `Reception` and `Delivery` start an hour after `Schedule`. That reads as
 * sending: `Reception` is
 * when parcels are taken in, `Delivery` most likely the same-day dispatch cut-off
 * (Nova Poshta's own field descriptions could not be read — their developer site
 * answers 403 to a script). So the app uses `Schedule`.
 */
data class PickupPoint(
    val ref: String,
    /** `Branch`, `Postomat`, … */
    val category: String,
    /** By day of week; a day mapped to null is closed, a missing day is unknown. */
    val hours: Map<DayOfWeek, DayHours?>,
    val generator: Boolean,
    val terminal: Boolean,
    val fittingRoom: Boolean,
    /** `WarehouseStatus` was "Working". The directory is not live; this is its word. */
    val working: Boolean,
    /** When this was asked, epoch millis. */
    val fetchedAt: Long
)

/** How long one answer about a point is trusted. */
const val POINT_KEEP_DAYS = 7L

fun pointFresh(point: PickupPoint?, nowMillis: Long): Boolean =
    point != null && nowMillis - point.fetchedAt in 0 until POINT_KEEP_DAYS * 86_400_000L

private val DAY_NAMES = mapOf(
    DayOfWeek.MONDAY to "Monday", DayOfWeek.TUESDAY to "Tuesday", DayOfWeek.WEDNESDAY to "Wednesday",
    DayOfWeek.THURSDAY to "Thursday", DayOfWeek.FRIDAY to "Friday", DayOfWeek.SATURDAY to "Saturday",
    DayOfWeek.SUNDAY to "Sunday"
)

private val SPAN = Regex("""^\s*(\d{1,2}):(\d{2})\s*-\s*(\d{1,2}):(\d{2})\s*$""")

/** "-" is the directory's word for a day the point does not open. */
fun isClosedMark(raw: String): Boolean = raw.trim().let { it == "-" || it.isEmpty() }

/**
 * "08:00-21:00" as hours. Null for anything else — "-" included, which
 * [isClosedMark] answers — so text the app does not understand is left unknown
 * rather than guessed at.
 */
fun parseDayHours(raw: String): DayHours? {
    val match = SPAN.matchEntire(raw) ?: return null
    val (h1, m1, h2, m2) = match.destructured
    val open = runCatching { LocalTime.of(h1.toInt(), m1.toInt()) }.getOrNull() ?: return null
    // "24:00" is how some write the end of the day.
    val close = if (h2.toInt() == 24 && m2.toInt() == 0) {
        LocalTime.of(23, 59)
    } else {
        runCatching { LocalTime.of(h2.toInt(), m2.toInt()) }.getOrNull() ?: return null
    }
    return DayHours(open, close)
}

/** The schedule object out of a directory record. A day the text of which is not understood is left out. */
private fun hoursOf(schedule: JSONObject?): Map<DayOfWeek, DayHours?> {
    if (schedule == null) return emptyMap()
    val out = LinkedHashMap<DayOfWeek, DayHours?>()
    for ((day, name) in DAY_NAMES) {
        if (!schedule.has(name)) continue
        val raw = schedule.optString(name, "")
        if (isClosedMark(raw)) out[day] = null else parseDayHours(raw)?.let { out[day] = it }
    }
    return out
}

/**
 * The body of one directory request: `Address.getWarehouses` by `Ref`, empty key.
 * Checked live on 4 October 2026 — no key is needed, and one Ref answers with one
 * record of about 3 KB.
 */
fun pointRequest(ref: String): JSONObject = JSONObject()
    .put("apiKey", "")
    .put("modelName", "Address")
    .put("calledMethod", "getWarehouses")
    .put("methodProperties", JSONObject().put("Ref", ref.trim()))

/** One directory record as a point. */
fun pickupPointFrom(record: JSONObject, fetchedAt: Long): PickupPoint? {
    val ref = record.optString("Ref").trim()
    if (ref.isEmpty()) return null
    fun flag(name: String) = record.optString(name).trim() == "1"
    return PickupPoint(
        ref = ref,
        category = record.optString("CategoryOfWarehouse").trim(),
        hours = hoursOf(record.optJSONObject("Schedule")),
        generator = flag("GeneratorEnabled"),
        terminal = flag("POSTerminal"),
        fittingRoom = flag("HasFittingRoom"),
        working = record.optString("WarehouseStatus").trim().let { it.isEmpty() || it.equals("Working", true) },
        fetchedAt = fetchedAt
    )
}

/** A `getWarehouses` answer, or null for a failed or empty one. */
fun parsePickupPoint(json: String, fetchedAt: Long): PickupPoint? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (!root.optBoolean("success", false)) return null
    val record = root.optJSONArray("data")?.optJSONObject(0) ?: return null
    return pickupPointFrom(record, fetchedAt)
}

/** The stored form, both halves together. Not app data: a cache, not in any backup. */
fun pickupPointJson(point: PickupPoint): JSONObject = JSONObject()
    .put("pkRef", point.ref).put("pkCat", point.category)
    .put("pkH", JSONObject().apply {
        for ((day, name) in DAY_NAMES) {
            if (!point.hours.containsKey(day)) continue
            val span = point.hours[day]
            put(name, span?.let { "${clockOf(it.open)}-${clockOf(it.close)}" } ?: "-")
        }
    })
    .put("pkGen", point.generator).put("pkPos", point.terminal).put("pkFit", point.fittingRoom)
    .put("pkWork", point.working).put("pkAt", point.fetchedAt)

fun pickupPointOf(o: JSONObject?): PickupPoint? {
    if (o == null) return null
    val ref = o.optString("pkRef")
    if (ref.isBlank()) return null
    return PickupPoint(
        ref = ref,
        category = o.optString("pkCat"),
        hours = hoursOf(o.optJSONObject("pkH")),
        generator = o.optBoolean("pkGen", false),
        terminal = o.optBoolean("pkPos", false),
        fittingRoom = o.optBoolean("pkFit", false),
        working = o.optBoolean("pkWork", true),
        fetchedAt = o.optLong("pkAt", 0L)
    )
}

/** "08:00", in ASCII digits whatever the phone is set to. */
fun clockOf(time: LocalTime): String = String.format(Locale.ROOT, "%02d:%02d", time.hour, time.minute)

private val WEEKDAY_ACCUSATIVE = mapOf(
    DayOfWeek.MONDAY to "у понеділок", DayOfWeek.TUESDAY to "у вівторок",
    DayOfWeek.WEDNESDAY to "у середу", DayOfWeek.THURSDAY to "у четвер",
    DayOfWeek.FRIDAY to "у п'ятницю", DayOfWeek.SATURDAY to "у суботу",
    DayOfWeek.SUNDAY to "у неділю"
)

/** How long before closing the time chip turns into a warning. */
const val CLOSING_SOON_MINUTES = 60L

/** Where the point stands at one moment. */
enum class PointHours { ALL_DAY, BEFORE_OPENING, OPEN, CLOSING_SOON, CLOSED, UNKNOWN }

/** One chip under the address. [warn] is the one drawn in the warm colour. */
data class PointChip(val emoji: String, val text: String, val warn: Boolean = false)

/** The state of the point at [now]. */
fun pointHours(point: PickupPoint, now: LocalDateTime): PointHours {
    if (!point.hours.containsKey(now.dayOfWeek)) return PointHours.UNKNOWN
    val today = point.hours[now.dayOfWeek] ?: return PointHours.CLOSED
    if (today.allDay) return PointHours.ALL_DAY
    val time = now.toLocalTime()
    return when {
        time < today.open -> PointHours.BEFORE_OPENING
        time >= today.close -> PointHours.CLOSED
        ChronoUnit.MINUTES.between(time, today.close) <= CLOSING_SOON_MINUTES -> PointHours.CLOSING_SOON
        else -> PointHours.OPEN
    }
}

/**
 * When it next opens after [now], in words: «завтра о 08:00», «у понеділок о 09:00».
 * Null when the directory gives no open day in the coming week.
 */
fun nextOpening(point: PickupPoint, now: LocalDateTime): String? {
    for (ahead in 1..7L) {
        val date = now.toLocalDate().plusDays(ahead)
        val hours = point.hours[date.dayOfWeek] ?: continue
        val at = if (hours.allDay) "00:00" else clockOf(hours.open)
        val whenText = if (ahead == 1L) "завтра" else WEEKDAY_ACCUSATIVE.getValue(date.dayOfWeek)
        return "$whenText о $at"
    }
    return null
}

/** The time chip on its own, or null when the directory says nothing about today. */
fun hoursChip(point: PickupPoint, now: LocalDateTime): PointChip? {
    val today = point.hours[now.dayOfWeek]
    return when (pointHours(point, now)) {
        PointHours.UNKNOWN -> null
        PointHours.ALL_DAY -> PointChip("🕘", "цілодобово")
        PointHours.OPEN -> PointChip("🕘", "сьогодні до ${clockOf(today!!.close)}")
        PointHours.CLOSING_SOON -> PointChip("🕘", "скоро зачиняється · до ${clockOf(today!!.close)}", warn = true)
        PointHours.BEFORE_OPENING -> PointChip("🕘", "сьогодні ${clockOf(today!!.open)}–${clockOf(today.close)}")
        PointHours.CLOSED -> nextOpening(point, now)?.let { PointChip("🕘", "відкриється $it", warn = true) }
            ?: PointChip("🕘", "сьогодні зачинено", warn = true)
    }
}

/**
 * The row of chips under the address: «🕘 сьогодні до 21:00 · ⚡ генератор ·
 * 💳 термінал · 👕 примірочна».
 *
 * At a point the owner has collected from before, they know the generator and the
 * terminal, so only the one thing they cannot know shows: that it is about to shut.
 * The directory is not live — «генератор» says the point has one, not that the
 * lights are on — and the chips say what the directory says, nothing more.
 */
fun pickupChips(point: PickupPoint, now: LocalDateTime, familiar: Boolean): List<PointChip> {
    val time = hoursChip(point, now)
    if (familiar) {
        return listOfNotNull(time?.takeIf { pointHours(point, now) == PointHours.CLOSING_SOON })
    }
    return listOfNotNull(
        PointChip("⚠️", "за довідником зараз не працює", warn = true).takeIf { !point.working },
        time,
        PointChip("⚡", "генератор").takeIf { point.generator },
        PointChip("💳", "термінал").takeIf { point.terminal },
        PointChip("👕", "примірочна").takeIf { point.fittingRoom }
    )
}

/**
 * For the morning message: «забрати можна до 21:00».
 *
 * Said at the hour the message goes out, so «до 21:00» is today's closing time.
 * Null when there is nothing worth adding — unknown hours, or already open all day
 * and said elsewhere.
 */
fun pickupUntilLine(point: PickupPoint, now: LocalDateTime): String? {
    val today = point.hours[now.dayOfWeek]
    return when (pointHours(point, now)) {
        PointHours.ALL_DAY -> "забрати можна цілодобово"
        PointHours.OPEN, PointHours.CLOSING_SOON, PointHours.BEFORE_OPENING ->
            "забрати можна до ${clockOf(today!!.close)}"
        // A day it does not open at all is not one it has «already» shut on.
        PointHours.CLOSED -> nextOpening(point, now)?.let {
            if (today == null) "сьогодні не працює, відкриється $it" else "сьогодні вже зачинено, відкриється $it"
        }
        PointHours.UNKNOWN -> null
    }
}

/** Two records of the carrier's naming the same pickup point. */
fun samePoint(a: ParcelDetails, b: ParcelDetails): Boolean = when {
    a.warehouseRef.isNotBlank() && b.warehouseRef.isNotBlank() -> a.warehouseRef == b.warehouseRef
    else -> a.warehouseNumber.isNotBlank() && a.warehouseNumber == b.warehouseNumber &&
        a.cityRecipient.isNotBlank() && a.cityRecipient.equals(b.cityRecipient, ignoreCase = true)
}

/**
 * Whether the owner has collected a parcel from this point before.
 *
 * Any other purchase that reached the owner from the same point counts; the
 * number and city stand in for the directory's id on purchases stored before the
 * id was kept.
 */
fun familiarPoint(orders: List<Order>, order: Order): Boolean = orders.any { other ->
    other.id != order.id && !other.digital &&
        (other.status == RECEIVED || other.archivedDay > 0L) &&
        samePoint(other.details, order.details)
}

/** The point to ask the directory about, for a parcel waiting at one. */
fun pointToAsk(order: Order): String? =
    order.details.warehouseRef.takeIf { order.archivedDay == 0L && order.status == AT_BRANCH && it.isNotBlank() }

// ------------------------------------------------------------ sending it back

/** «Повертаю»: from pressing it to the money arriving. */
data class Refund(
    /** Epoch day it was started. */
    val startedDay: Long,
    /** What should come back, in hryvnia. */
    val amount: Double,
    /** The return waybill, when there is one. */
    val tracking: String = "",
    val reason: String = "",
    /** How many days the shop has to pay back, counted from receiving the thing. */
    val days: Int = REFUND_DAYS_DEFAULT,
    /** Epoch day the shop received it. Zero until then. */
    val shopGotDay: Long = 0L,
    /** Epoch day the money came back. Zero until then. */
    val backDay: Long = 0L,
    /**
     * The return waybill's own last status: kept here, apart from the delivery's
     * [Order.statusCode] and [Order.sightings], which are the history of the
     * parcel that came to the owner and must not be overwritten by the one going
     * back.
     */
    val statusCode: Int = 0,
    val statusText: String = "",
    val checkedAt: Long = 0L
)

/**
 * Thirty days.
 *
 * The consumer law has both a 7 and a 30 (articles 9 and 12), shops have rules of
 * their own, and an earlier draft's fourteen rested on nothing. Thirty is the one
 * that never calls a refund late before any of them would, and the sheet says to
 * check the shop's terms.
 */
const val REFUND_DAYS_DEFAULT = 30

/** The choices on the sheet. */
val REFUND_DAY_CHOICES = listOf(7, 14, 30)

const val REFUND_NOTE = "Строк — від дня, коли магазин отримав річ. У законі є і 7, і 30 днів, " +
    "а в магазинів свої правила — перевірте умови магазину."

/** The three stops of a return, shown as a rail of their own. */
val RETURN_STAGES = listOf("Відправив", "Магазин отримав", "Гроші повернулись")

/** A return is under way: started, and the money not back yet. */
fun isReturning(order: Order): Boolean = order.refund != null && order.refund.backDay == 0L

/** The money came back. */
fun isRefunded(order: Order): Boolean = (order.refund?.backDay ?: 0L) > 0L

/**
 * Whether a purchase still counts as bought — in «Куплено вчасно», the verdict,
 * the cost per use, the month recap.
 *
 * A thing on its way back, or already back, was not kept, so judging the wait for
 * it or dividing its price by its uses would be grading a purchase that did not
 * happen.
 */
fun countsAsBought(order: Order): Boolean = order.refund == null

/** What the return sheet starts from: what was actually paid, else the listed price. */
fun refundDefaultAmount(order: Order): Double = if (order.paid > 0.0) order.paid else order.price

/** Which of [RETURN_STAGES] a return has reached. */
fun returnStageIndex(refund: Refund): Int = when {
    refund.backDay > 0L -> 2
    refund.shopGotDay > 0L -> 1
    else -> 0
}

/**
 * The purchase with a return started on it.
 *
 * Filed on the spot if it was not already: «Як на фото? Ні» is pressed on a parcel
 * just collected, and a return is about a thing that was bought.
 */
fun startReturn(
    order: Order,
    amount: Double,
    tracking: String,
    reason: String,
    days: Int,
    today: Long
): Order {
    val number = tracking.filter { !it.isWhitespace() }
    val previous = order.refund
    val refund = (previous ?: Refund(startedDay = today, amount = amount)).copy(
        amount = amount.coerceAtLeast(0.0),
        tracking = number,
        reason = reason.trim(),
        days = days.coerceIn(1, 365),
        // A new waybill is a new parcel to follow: what the old one said is not
        // about it.
        statusCode = if (previous != null && previous.tracking == number) previous.statusCode else 0,
        statusText = if (previous != null && previous.tracking == number) previous.statusText else "",
        checkedAt = if (previous != null && previous.tracking == number) previous.checkedAt else 0L
    )
    return order.copy(
        refund = refund,
        archivedDay = order.archivedDay.takeIf { it > 0L } ?: today,
        status = RECEIVED
    )
}

/** «Магазин отримав», set by hand — there is no waybill, or the carrier is slow to say. */
fun shopReceived(order: Order, today: Long): Order {
    val refund = order.refund ?: return order
    return order.copy(refund = refund.copy(shopGotDay = refund.shopGotDay.takeIf { it > 0L } ?: today))
}

/**
 * «Гроші повернулись ✓»: the return is done and the purchase goes back into the
 * archive marked «повернено». The return window is closed with it — there is
 * nothing left to send back.
 */
fun moneyBack(order: Order, today: Long): Order {
    val refund = order.refund ?: return order
    return order.copy(
        refund = refund.copy(
            shopGotDay = refund.shopGotDay.takeIf { it > 0L } ?: today,
            backDay = today
        ),
        returnBy = 0L
    )
}

/** «Не повертаю»: the return undone, the purchase as it was. */
fun cancelReturn(order: Order): Order = order.copy(refund = null)

/**
 * «Гроші ще не прийшли»: a «Гроші повернулись» tapped too soon, taken back.
 *
 * The pill that files a return sits on its tile one tap away, and a money-back
 * marked by mistake would quietly drop the reminder that the shop still owes it.
 * The shop's receipt stays, and so does the closed return window.
 */
fun undoMoneyBack(order: Order): Order {
    val refund = order.refund ?: return order
    return order.copy(refund = refund.copy(backDay = 0L))
}

/** Whether the background pass should ask the carrier about the return waybill. */
fun followsReturn(order: Order): Boolean {
    val refund = order.refund ?: return false
    return refund.backDay == 0L && refund.shopGotDay == 0L &&
        detectCarrier(refund.tracking) == CARRIER_NOVA_POSHTA
}

/**
 * A fresh answer about the return waybill, folded in.
 *
 * Its own status fields, never the delivery's. When the carrier says the shop has
 * it — any of the received codes — «Магазин отримав» is set from the day the
 * carrier names, or today when it names none, and the countdown starts.
 */
fun applyReturnStatus(order: Order, status: ParcelStatus, atMillis: Long, today: Long): Order {
    val refund = order.refund ?: return order
    val got = when {
        refund.shopGotDay > 0L -> refund.shopGotDay
        status.stage == RECEIVED -> status.details.receivedAt?.toLocalDate()?.toEpochDay()
            ?.takeIf { it in refund.startedDay..today } ?: today
        else -> 0L
    }
    return order.copy(
        refund = refund.copy(
            statusCode = status.code,
            statusText = status.text,
            checkedAt = atMillis,
            shopGotDay = got
        )
    )
}

/** Days since the shop received it, or null before then. */
fun refundWaitedDays(refund: Refund, today: Long): Int? =
    refund.shopGotDay.takeIf { it > 0L }?.let { (today - it).toInt().coerceAtLeast(0) }

/** Days past the shop's deadline, or null while it is not past. */
fun refundOverdueDays(refund: Refund, today: Long): Int? {
    if (refund.backDay > 0L) return null
    val waited = refundWaitedDays(refund, today) ?: return null
    return (waited - refund.days).takeIf { it > 0 }
}

/** "з 30 днів", "з 21 дня" — the genitive after «з». */
fun ofDaysLabel(count: Int): String {
    val lastTwo = count % 100
    val word = if (count % 10 == 1 && lastTwo != 11) "дня" else "днів"
    return "з $count $word"
}

/**
 * The line on the tile: what the return is waiting for.
 *
 * «чекаю гроші: 3 з 30 днів» once the shop has it; before that, what is known
 * about the parcel going back.
 */
fun refundStatusLine(refund: Refund, today: Long): String {
    if (refund.backDay > 0L) return "Гроші повернулись ${formatDate(LocalDate.ofEpochDay(refund.backDay))}"
    val waited = refundWaitedDays(refund, today)
    return when {
        waited == null && refund.tracking.isBlank() -> "Відправлено · коли магазин отримає, позначте"
        waited == null -> refund.statusText.takeIf { it.isNotBlank() }?.let { "Зворотна посилка: $it" }
            ?: "Відправлено · стежу за зворотною накладною"
        waited <= refund.days -> "чекаю гроші: $waited ${ofDaysLabel(refund.days)}"
        else -> "чекаю гроші: $waited ${ofDaysLabel(refund.days)} — строк минув"
    }
}

/**
 * The shop's name as people say it: «Rozetka» out of `rozetka.com.ua`.
 *
 * The last part of the address that is not a domain ending, capitalised. Empty
 * when there is no address.
 */
fun shopName(url: String): String {
    val host = runCatching { java.net.URI(url.trim()).host }.getOrNull()?.lowercase(Locale.ROOT) ?: return ""
    val endings = setOf("com", "ua", "net", "org", "co", "in", "kiev", "kyiv", "biz", "info", "eu", "shop", "store")
    val parts = host.removePrefix("www.").removePrefix("m.").split('.').filter { it.isNotBlank() }
    val name = parts.dropLastWhile { it in endings }.lastOrNull() ?: parts.firstOrNull() ?: return ""
    return name.replaceFirstChar { it.titlecase(Locale.ROOT) }
}

/**
 * The morning line for a refund that is late:
 * «Rozetka: повернення отримано 16 днів тому — гроші прийшли?».
 *
 * Worded without a verb agreeing with the shop, whose grammatical gender the app
 * cannot know («Rozetka отримала», «Prom отримав»).
 */
fun refundDigestLine(order: Order, today: Long): String? {
    val refund = order.refund ?: return null
    refundOverdueDays(refund, today) ?: return null
    val waited = refundWaitedDays(refund, today) ?: return null
    val who = shopName(order.url).ifBlank { "«${order.name}»" }
    return "$who: повернення отримано ${daysLabel(waited)} тому — гроші прийшли?"
}

/** «Мені винні» on Огляд: every return still waiting for its money. */
data class Owed(val count: Int, val total: Double, val overdue: Int)

fun owed(orders: List<Order>, today: Long): Owed? {
    val waiting = orders.filter { isReturning(it) }
    if (waiting.isEmpty()) return null
    return Owed(
        count = waiting.size,
        total = waiting.sumOf { it.refund?.amount ?: 0.0 },
        overdue = waiting.count { order -> order.refund?.let { refundOverdueDays(it, today) } != null }
    )
}

/** The caption under «Мені винні». */
fun owedCaption(owed: Owed): String = when {
    owed.overdue > 0 -> "${purchasesLabel(owed.count)} · строк минув: ${owed.overdue}"
    else -> "${purchasesLabel(owed.count)} · чекаю гроші"
}

fun refundJson(refund: Refund): JSONObject = JSONObject()
    .put("pkS", refund.startedDay).put("pkA", refund.amount)
    .put("pkT", refund.tracking).put("pkR", refund.reason)
    .put("pkD", refund.days).put("pkG", refund.shopGotDay).put("pkB", refund.backDay)
    .put("pkC", refund.statusCode).put("pkX", refund.statusText).put("pkK", refund.checkedAt)

fun refundOf(o: JSONObject?): Refund? {
    if (o == null) return null
    return Refund(
        startedDay = o.optLong("pkS", 0L),
        amount = o.optDouble("pkA", 0.0).takeIf { it.isFinite() } ?: 0.0,
        tracking = o.optString("pkT"),
        reason = o.optString("pkR"),
        days = o.optInt("pkD", REFUND_DAYS_DEFAULT).takeIf { it > 0 } ?: REFUND_DAYS_DEFAULT,
        shopGotDay = o.optLong("pkG", 0L),
        backDay = o.optLong("pkB", 0L),
        statusCode = o.optInt("pkC", 0),
        statusText = o.optString("pkX"),
        checkedAt = o.optLong("pkK", 0L)
    )
}

// ------------------------------------------------------------ warranty

/** The chips on the filing sheet, in months. Nought is «немає»; «своя дата» is separate. */
val WARRANTY_CHOICES = listOf(0, 12, 24, 36)

/**
 * The day a warranty is counted from: the day the carrier says the parcel was
 * collected, else the day the app first saw it collected, else the day it was
 * filed — a download or a parcel from another post knows only that.
 */
fun warrantyStart(order: Order, filedDay: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): LocalDate {
    order.details.receivedAt?.toLocalDate()?.let { return it }
    order.sightings.firstOrNull { stageForStatusCode(it.code) == RECEIVED }?.let {
        return java.time.Instant.ofEpochMilli(it.atMillis).atZone(zone).toLocalDate()
    }
    return LocalDate.ofEpochDay(filedDay)
}

/** The last day of a warranty of [months] from [start]. */
fun warrantyEnd(start: LocalDate, months: Int): Long =
    if (months <= 0) 0L else start.plusMonths(months.toLong()).toEpochDay()

/** Which chip a stored end matches, or null for «своя дата». Nought for none. */
fun warrantyChoice(until: Long, start: LocalDate): Int? = when {
    until <= 0L -> 0
    else -> WARRANTY_CHOICES.firstOrNull { it > 0 && warrantyEnd(start, it) == until }
}

fun warrantyChoiceLabel(months: Int): String = if (months <= 0) "немає" else "$months міс"

/** Still under warranty on [today]. A purchase given back is not. */
fun onWarranty(order: Order, today: Long): Boolean =
    order.warrantyUntil >= today && order.warrantyUntil > 0L && !isRefunded(order)

/** The archive chip: «гарантія до 14 листопада 2027». */
fun warrantyChip(order: Order, today: Long): String? =
    if (onWarranty(order, today)) "гарантія до ${formatDate(LocalDate.ofEpochDay(order.warrantyUntil))}" else null

/** How long before the end the morning message mentions it. */
const val WARRANTY_WARN_DAYS = 30

/** «Гарантія на «Навушники» до 14 листопада — усе працює?» */
fun warrantyDigestLine(order: Order, today: Long): String? {
    if (!onWarranty(order, today)) return null
    if (order.warrantyUntil - today > WARRANTY_WARN_DAYS) return null
    return "Гарантія на «${order.name}» до ${dayMonth(LocalDate.ofEpochDay(order.warrantyUntil))} — усе працює?"
}

// ------------------------------------------------------------ «Як тобі покупка?»

/** The four answers, warmest first, as the card offers them. */
enum class Delight(val value: Int, val emoji: String) {
    LOVE(4, "😍"), GOOD(3, "🙂"), MEH(2, "😐"), BAD(1, "😞");

    companion object {
        fun of(value: Int): Delight? = entries.firstOrNull { it.value == value }
    }
}

/** Three weeks: long enough to have used the thing, short enough to remember buying it. */
const val DELIGHT_AFTER_DAYS = 21

/** And a week to answer, after which the question goes quietly. */
const val DELIGHT_WINDOW_DAYS = 7

/**
 * Whether the archived card asks «Як тобі …?» on [today].
 *
 * Once, in a week that starts three weeks after filing; never for a purchase
 * given back, and never again once answered. Unanswered, it is gone after the
 * week without a word — the app does not chase.
 */
fun delightDue(order: Order, today: Long): Boolean {
    if (order.archivedDay <= 0L || order.delight != 0 || order.refund != null) return false
    val since = today - order.archivedDay
    return since in DELIGHT_AFTER_DAYS.toLong() until (DELIGHT_AFTER_DAYS + DELIGHT_WINDOW_DAYS).toLong()
}

/** The question itself. */
fun delightQuestion(order: Order): String = "Як тобі «${order.name}»?"

/** The answer recorded. */
fun answerDelight(order: Order, delight: Delight, today: Long): Order =
    order.copy(delight = delight.value, delightDay = today)

/** «Купити таке ще раз?» — yes is 1, no is -1. */
fun answerAgain(order: Order, yes: Boolean): Order = order.copy(again = if (yes) 1 else -1)

/**
 * What the card says once answered, in three parts so the emoji is drawn as one:
 * «Хотілось, бо: «щоб бігати з музикою»» over «Через три тижні: 😍 · купити ще
 * раз — так».
 *
 * The owner's own words beside the owner's own answer; the app adds nothing.
 */
data class DelightAnswer(val why: String?, val emoji: String, val again: String?)

fun delightAnswer(order: Order): DelightAnswer? {
    val delight = Delight.of(order.delight) ?: return null
    return DelightAnswer(
        why = order.why.takeIf { it.isNotBlank() }?.let { "Хотілось, бо: «$it»" },
        emoji = delight.emoji,
        again = when (order.again) {
            1 -> "купити ще раз — так"
            -1 -> "купити ще раз — ні"
            else -> null
        }
    )
}

/** The answer in words, for the recap, which sets its emoji as a sticker of its own. */
fun delightWords(delight: Int): String = when (delight) {
    Delight.LOVE.value -> "у захваті"
    Delight.GOOD.value -> "подобається"
    Delight.MEH.value -> "так собі"
    Delight.BAD.value -> "не те"
    else -> ""
}

/** The one morning reminder: «Як тобі «Навушники»? Відповісти можна в архіві покупок». */
fun delightDigestLine(order: Order): String = "${delightQuestion(order)} Відповісти можна в архіві покупок"

/** Below this many answers a category says nothing: two is an anecdote. */
const val CATEGORY_JOY_MIN = 3

/** «Гаджети: 3 з 4 —» and the emoji after it, drawn as one. */
data class CategoryJoy(val text: String, val emoji: String)

/**
 * The quiet line under the category of a new wish: «Гаджети: 3 з 4 — 😍».
 *
 * The most common answer among the purchases of that category that were rated,
 * the warmer one on a tie, and how many gave it. Nothing for «Інше», which is the
 * absence of a category, and nothing below [CATEGORY_JOY_MIN] answers.
 */
fun categoryJoy(orders: List<Order>, category: String): CategoryJoy? {
    val key = categoryKey(category)
    if (key.isBlank() || key == categoryKey(OTHER_CATEGORY)) return null
    val rated = orders.filter { it.delight in 1..4 && it.category.isNotBlank() && categoryKey(it.category) == key }
    if (rated.size < CATEGORY_JOY_MIN) return null
    val top = rated.groupingBy { it.delight }.eachCount().entries
        .maxWithOrNull(compareBy<Map.Entry<Int, Int>>({ it.value }, { it.key })) ?: return null
    val emoji = Delight.of(top.key)?.emoji ?: return null
    // Named as the purchases spell it, not as it is being typed — «гаджети» half
    // way through typing is still «Гаджети».
    val spelling = rated.map { categoryName(it.category) }.groupingBy { it }.eachCount()
        .maxByOrNull { it.value }?.key ?: categoryName(category)
    return CategoryJoy("$spelling: ${top.value} з ${rated.size} —", emoji)
}

/**
 * «Що справді порадувало» for the month recap: the purchases answered 😍 in that
 * month, or 🙂 when none were. Only ever the warm half — the recap does not
 * reproach — and null when nothing was answered warmly.
 */
fun delightedIn(orders: List<Order>, firstDay: Long, lastDay: Long): List<Order> {
    val answered = orders.filter { it.delightDay in firstDay..lastDay && countsAsBought(it) }
    val loved = answered.filter { it.delight == Delight.LOVE.value }
    val pick = loved.ifEmpty { answered.filter { it.delight == Delight.GOOD.value } }
    return pick.sortedByDescending { it.delightDay }
}

// ------------------------------------------------------------ the morning message, said once

// [OnceLine] is declared in PaymentsLife.kt; both kinds of line share it and the
// one memory of what was said (LifeMemory).

/**
 * What the purchases have to say this morning, each line under the key that stops
 * it being said again.
 *
 * - a refund past the shop's deadline — once a week while it stays late, because
 *   money owed is worth a reminder and a daily one would be nagging;
 * - a warranty ending within a month — once;
 * - «Як тобі …?» — once, in its week.
 */
fun purchaseOnceLines(orders: List<Order>, today: Long): List<OnceLine> = buildList {
    orders.filter { isReturning(it) }.forEach { order ->
        val refund = order.refund ?: return@forEach
        val late = refundOverdueDays(refund, today) ?: return@forEach
        refundDigestLine(order, today)?.let { add(OnceLine("pkRefund-${order.id}-${(late - 1) / 7}", it)) }
    }
    orders.forEach { order ->
        warrantyDigestLine(order, today)?.let { add(OnceLine("pkWarranty-${order.id}-${order.warrantyUntil}", it)) }
    }
    orders.filter { delightDue(it, today) }.forEach { order ->
        add(OnceLine("pkJoy-${order.id}", delightDigestLine(order)))
    }
}

// ------------------------------------------------------------ Nova Poshta's own app

/** The new app first, then the old one, which Google Play still lists. */
val NOVA_POSHTA_APPS = listOf("eu.novapost", "ua.novaposhtaa")

/** And the website when neither is installed. */
fun novaPoshtaPage(number: String): String {
    val digits = number.filter { it.isDigit() }
    return if (digits.isEmpty()) "https://novaposhta.ua/" else "https://novaposhta.ua/tracking/$digits/"
}

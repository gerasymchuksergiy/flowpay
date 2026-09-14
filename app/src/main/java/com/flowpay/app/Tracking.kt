package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Reading a parcel's status from its carrier.
 *
 * Nova Poshta answers its tracking method with an empty API key, so this needs no
 * registration and nothing secret ends up in the APK. Everything here is a
 * function over a string, so the code mapping and the response parsing are tested
 * against fixtures rather than against somebody's live parcel.
 */

// The four states a parcel can be in as far as this app is concerned. The fourth
// is the one that asks something of you, so it is worth separating from "in transit".
const val ORDERED = "Замовлено"
const val IN_TRANSIT = "В дорозі"
const val AT_BRANCH = "На відділенні"
const val RECEIVED = "Отримано"

/** Statuses in the order a parcel moves through them. */
val PARCEL_STAGES = listOf(ORDERED, IN_TRANSIT, AT_BRANCH, RECEIVED)

const val CARRIER_NOVA_POSHTA = "novaposhta"
const val CARRIER_UNKNOWN = ""

data class ParcelStatus(
    val code: Int,
    /** The carrier's own wording, shown as-is because it says more than a stage. */
    val text: String,
    /** One of [PARCEL_STAGES], or empty when the code does not map to normal progress. */
    val stage: String,
    val city: String,
    val warehouse: String,
    val receivedAt: String,
    /** The number is unknown to the carrier, or the parcel was refused or returned. */
    val problem: Boolean,
    /** Branch number, which is what you actually tell a driver or look for on a door. */
    val warehouseNumber: String,
    /** When the carrier expects to deliver, if it says. */
    val scheduledDelivery: LocalDate?,
    /**
     * The day storage stops being free. Nova Poshta reports this directly, so it is
     * read rather than computed from a tariff that could change.
     */
    val paidStorageFrom: LocalDate?,
    /** Cash on delivery still owed, if any. */
    val amountToPay: Double,
    /**
     * Everything else the response carries that is worth a person's attention.
     *
     * Kept as one nested record rather than another twenty fields here, because
     * this is also exactly what gets stored on the parcel — see [ParcelDetails].
     */
    val details: ParcelDetails = ParcelDetails()
)

/**
 * The rest of what Nova Poshta says, in the shape it is stored and shown in.
 *
 * `getStatusDocuments` answers with a hundred and twenty-eight fields. Most are
 * references, internal ids, the sender's own paperwork, or a phone number he
 * already knows because it is his. What is here is the subset a person opening a
 * parcel would actually read: where it is going, when it was last touched, what
 * it weighs, who pays, and what can still be done with it.
 *
 * Every field defaults to empty, because a parcel that has only just been created
 * fills in perhaps half of them and the screen has to be built for their absence.
 * The three moments are [LocalDateTime] rather than strings: the response writes
 * each of them in a different format, and a field that could not be parsed is
 * null here rather than a raw string shown to somebody as if it were a date.
 */
data class ParcelDetails(
    val citySender: String = "",
    val cityRecipient: String = "",
    val warehouseSender: String = "",
    val warehouseSenderAddress: String = "",
    val warehouseRecipient: String = "",
    val warehouseRecipientAddress: String = "",
    val warehouseNumber: String = "",
    /** `Postomat`, `Branch` and the rest — a locker is a different errand. */
    val warehouseCategory: String = "",
    /** `WarehouseWarehouse` and friends: which end of each journey is a door. */
    val serviceType: String = "",
    /** `DateCreated`, when the parcel was handed over. */
    val createdAt: LocalDateTime? = null,
    /** `DateScan` — when the carrier last physically scanned it. */
    val scannedAt: LocalDateTime? = null,
    /** `TrackingUpdateDate` — when the carrier last touched this record. */
    val trackingUpdatedAt: LocalDateTime? = null,
    val documentWeight: Double = 0.0,
    val factualWeight: Double = 0.0,
    val volumeWeight: Double = 0.0,
    val seats: Int = 0,
    val payerType: String = "",
    val paymentMethod: String = "",
    val cargoType: String = "",
    val canRedirect: Boolean = false,
    val canRefuse: Boolean = false,
    val canExtendTerm: Boolean = false
) {
    /** Nothing has ever been fetched, so the detail has nothing to lay out. */
    val isEmpty: Boolean
        get() = this == ParcelDetails()
}

/**
 * One moment at which the app saw the carrier say something different.
 *
 * This is the app's own record, not the carrier's. `getStatusDocuments` returns
 * the current status and nothing before it — the scan-by-scan list in Nova
 * Poshta's own app comes from an authenticated internal API this project does not
 * have and will not pretend to. So what can be built honestly is a log of
 * observations: every time a check comes back saying something other than what
 * the last one said, the new wording is kept with the time the app saw it.
 *
 * On a parcel followed from the day it was added that becomes a real movement
 * history within a day or two. On one that already existed it starts empty, and
 * the screen says so rather than implying the parcel has not moved.
 */
data class Sighting(
    /** The carrier's status code at that moment. */
    val code: Int,
    /** The carrier's own wording, as it read then. */
    val text: String,
    /** When the app observed it, epoch millis. */
    val atMillis: Long
)

/**
 * How many observations a parcel keeps.
 *
 * A parcel goes through well under a dozen real statuses, so this is only a bound
 * on a number that cannot otherwise be bounded: a mistyped number checked twice a
 * day for a year would otherwise grow the stored record without limit. The oldest
 * go first, because the recent end is the part anybody reads.
 */
const val SIGHTING_LIMIT = 40

/**
 * Folds a fresh reading into the observation log.
 *
 * Only a genuine change is recorded. The background pass runs several times a
 * day, and a log with an entry per check would say nothing at all about when the
 * parcel moved — which is the only question it exists to answer.
 */
fun recordSighting(seen: List<Sighting>, status: ParcelStatus, atMillis: Long): List<Sighting> {
    if (status.text.isBlank()) return seen
    val last = seen.lastOrNull()
    if (last != null && last.code == status.code && last.text == status.text) return seen
    val next = seen + Sighting(status.code, status.text, atMillis)
    return if (next.size > SIGHTING_LIMIT) next.takeLast(SIGHTING_LIMIT) else next
}

/**
 * Works out who carries a parcel from the shape of its number.
 *
 * Nova Poshta uses fourteen digits. Ukrposhta uses either two letters, nine digits
 * and two letters, or thirteen digits, and its API needs a token nobody has here,
 * so it is recognised only to explain why it cannot be checked automatically.
 */
fun detectCarrier(number: String): String {
    val clean = number.filter { !it.isWhitespace() }
    return if (clean.length == 14 && clean.all { it.isDigit() }) CARRIER_NOVA_POSHTA else CARRIER_UNKNOWN
}

/**
 * Maps a Nova Poshta status code onto one of this app's stages.
 *
 * Returns an empty stage for codes that are not forward progress: a deleted or
 * unknown number, a refusal, a return. Those keep whatever stage the user had set,
 * because overwriting it with a guess would lose information.
 */
fun stageForStatusCode(code: Int): String = when (code) {
    1, 11 -> ORDERED
    4, 5, 6, 101, 104 -> IN_TRANSIT
    7, 8 -> AT_BRANCH
    9, 10, 106 -> RECEIVED
    else -> ""
}

/** Codes that mean something went wrong rather than something moved. */
fun isProblemCode(code: Int): Boolean = code in setOf(2, 3, 12, 102, 103, 105)

/**
 * Pulls the status out of a Nova Poshta tracking response.
 *
 * Returns null when the call itself failed, so a network hiccup is never mistaken
 * for a parcel that stopped moving.
 */
fun parseNovaPoshtaStatus(json: String): ParcelStatus? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    if (!root.optBoolean("success", false)) return null
    val data = root.optJSONArray("data") ?: return null
    if (data.length() == 0) return null
    val item = data.optJSONObject(0) ?: return null
    val code = item.optString("StatusCode").toIntOrNull() ?: return null
    return ParcelStatus(
        code = code,
        text = item.optString("Status").trim(),
        stage = stageForStatusCode(code),
        city = item.optString("CityRecipient").trim(),
        warehouse = item.optString("WarehouseRecipient").trim(),
        receivedAt = item.optString("RecipientDateTime").trim(),
        problem = isProblemCode(code),
        warehouseNumber = item.optString("WarehouseRecipientNumber").trim(),
        scheduledDelivery = parseCarrierDate(item.optString("ScheduledDeliveryDate")),
        paidStorageFrom = parseCarrierDate(item.optString("DatePayedKeeping")),
        amountToPay = item.optString("AmountToPay").replace(',', '.').toDoubleOrNull() ?: 0.0,
        details = ParcelDetails(
            citySender = item.optString("CitySender").trim(),
            cityRecipient = item.optString("CityRecipient").trim(),
            warehouseSender = item.optString("WarehouseSender").trim(),
            warehouseSenderAddress = item.optString("WarehouseSenderAddress").trim(),
            warehouseRecipient = item.optString("WarehouseRecipient").trim(),
            warehouseRecipientAddress = item.optString("WarehouseRecipientAddress").trim(),
            warehouseNumber = item.optString("WarehouseRecipientNumber").trim(),
            warehouseCategory = item.optString("CategoryOfWarehouse").trim(),
            serviceType = item.optString("ServiceType").trim(),
            createdAt = parseCreatedMoment(item.optString("DateCreated")),
            scannedAt = parseScanMoment(item.optString("DateScan")),
            trackingUpdatedAt = parseUpdateMoment(item.optString("TrackingUpdateDate")),
            documentWeight = carrierNumber(item.optString("DocumentWeight")),
            factualWeight = carrierNumber(item.optString("FactualWeight")),
            volumeWeight = carrierNumber(item.optString("VolumeWeight")),
            seats = carrierNumber(item.optString("SeatsAmount")).toInt(),
            payerType = item.optString("PayerType").trim(),
            paymentMethod = item.optString("PaymentMethod").trim(),
            cargoType = item.optString("CargoType").trim(),
            canRedirect = item.optBoolean("PossibilityCreateRedirecting", false),
            canRefuse = item.optBoolean("PossibilityCreateRefusal", false),
            canExtendTerm = item.optBoolean("PossibilityTermExtension", false)
        )
    )
}

/**
 * A figure out of a carrier field, which may be a number, a string, or empty.
 *
 * The same response sends `DocumentWeight` as a JSON number and `FactualWeight`
 * as a quoted string, and the weights arrive with a comma on some parcels. Zero
 * for anything unreadable, which every caller already treats as "not stated".
 */
fun carrierNumber(raw: String): Double =
    raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0 } ?: 0.0

// Three formats in one response, so three formatters, each spelt out. Locale.ROOT
// throughout: these are ASCII digits from a server, and a phone set to a locale
// with its own numerals or its own calendar would otherwise fail to read them —
// or, worse, read them as a different date.
private val CREATED_DASH = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss", Locale.ROOT)
private val CREATED_DOT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss", Locale.ROOT)
private val SCAN_FORMAT = DateTimeFormatter.ofPattern("HH:mm dd.MM.yyyy", Locale.ROOT)
private val UPDATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

/**
 * Reads one moment, or null.
 *
 * Null rather than the raw string, always. A date the app cannot parse is a date
 * the app does not know, and printing "14-09-2026 15:03:19" on a screen where
 * every other date reads "14 вересня" is worse than printing nothing.
 */
private fun carrierMoment(raw: String, vararg formats: DateTimeFormatter): LocalDateTime? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    for (format in formats) {
        runCatching { LocalDateTime.parse(text, format) }.getOrNull()?.let { return it }
    }
    return null
}

/** `DateCreated`, written "14-09-2026 15:03:19" — and with dots on some parcels. */
fun parseCreatedMoment(raw: String): LocalDateTime? = carrierMoment(raw, CREATED_DASH, CREATED_DOT)

/** `DateScan`, written the other way round: "15:24 14.09.2026". */
fun parseScanMoment(raw: String): LocalDateTime? = carrierMoment(raw, SCAN_FORMAT)

/** `TrackingUpdateDate`, written in a third format again: "2026-09-14 15:24:19". */
fun parseUpdateMoment(raw: String): LocalDateTime? = carrierMoment(raw, UPDATE_FORMAT)

/**
 * One line for the card: what the carrier says, plus where, when it knows where.
 */
fun statusLine(status: ParcelStatus): String {
    val place = listOfNotNull(
        status.city.takeIf { it.isNotBlank() },
        status.warehouseNumber.takeIf { it.isNotBlank() }?.let { "відділення №$it" }
            ?: status.warehouse.takeIf { it.isNotBlank() }
    ).joinToString(", ")
    return if (place.isBlank()) status.text else "${status.text} · $place"
}

/**
 * Folds a fetched status into the stored parcel.
 *
 * A code that is not forward progress leaves the stage alone: the number may be
 * mistyped or the parcel returned, and replacing what the user set with a guess
 * would throw away the better information.
 */
fun applyStatus(order: Order, status: ParcelStatus, atMillis: Long): Order = order.copy(
    status = status.stage.ifBlank { order.status },
    statusDetail = statusLine(status),
    checkedAt = atMillis,
    problem = status.problem,
    paidStorageFrom = status.paidStorageFrom?.toEpochDay() ?: 0L,
    scheduledDelivery = status.scheduledDelivery?.toEpochDay() ?: 0L,
    amountToPay = status.amountToPay,
    details = status.details,
    // Written here rather than at the call sites, so a status fetched by the
    // background pass, by the button on the card and by the one on the detail all
    // leave the same trace. A log that only filled in when the screen was open
    // would miss exactly the movements nobody was watching.
    sightings = recordSighting(order.sightings, status, atMillis)
)

/**
 * Reads a date out of a carrier field.
 *
 * Nova Poshta is inconsistent about its own format: the same response carries
 * "01-08-2026 19:18:12" in one field and "04.08.2026 08:10:48" in another, so both
 * separators have to be accepted. Anything unparseable becomes null rather than a
 * wrong date.
 */
fun parseCarrierDate(raw: String): LocalDate? {
    val head = raw.trim().take(10)
    val parts = head.split('.', '-')
    if (parts.size != 3) return null
    val day = parts[0].toIntOrNull() ?: return null
    val month = parts[1].toIntOrNull() ?: return null
    val year = parts[2].toIntOrNull() ?: return null
    if (year < 2000 || month !in 1..12 || day !in 1..31) return null
    return runCatching { LocalDate.of(year, month, day) }.getOrNull()
}

/**
 * Days of free storage left, or null when the carrier has not said.
 *
 * Zero means free storage has already run out. This is the only number in the app
 * that costs money to ignore, which is why it is worth surfacing at all.
 */
fun freeStorageDaysLeft(paidStorageFrom: LocalDate?, today: LocalDate): Int? {
    if (paidStorageFrom == null) return null
    val days = java.time.temporal.ChronoUnit.DAYS.between(today, paidStorageFrom).toInt()
    return days.coerceAtLeast(0)
}

/** Ukrainian plural for how many parcels are involved. */
fun parcelsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "посилок"
        last == 1 -> "посилка"
        last in 2..4 -> "посилки"
        else -> "посилок"
    }
    return "$count $word"
}

/** How many parcels are sitting at a branch waiting to be collected. */
fun branchLine(count: Int): String =
    if (count == 0) "На відділенні порожньо" else "${parcelsLabel(count)} на відділенні"

/** "15:24", the clock face, in ASCII digits whatever the phone is set to. */
fun clockLabel(moment: LocalDateTime): String =
    String.format(Locale.ROOT, "%02d:%02d", moment.hour, moment.minute)

/**
 * A moment in the words a person would use for it.
 *
 * "сьогодні о 15:24" and "вчора о 15:24" carry the thing that matters about a
 * scan — whether the parcel moved recently — in a way "14.09.2026 15:24" does
 * not. Anything older falls back to the date, with the year only when it is not
 * this one.
 */
fun momentLabel(moment: LocalDateTime, today: LocalDate): String {
    val day = moment.toLocalDate()
    val clock = clockLabel(moment)
    return when {
        day == today -> "сьогодні о $clock"
        day == today.minusDays(1) -> "вчора о $clock"
        day.year == today.year -> "${dayMonth(day)}, $clock"
        else -> "${formatDate(day)}, $clock"
    }
}

// The two labels that the whole detail screen exists for. They are constants
// rather than inline strings because the card and the detail both say them, and
// two screens wording this differently would undo the distinction they are for.

/** When the parcel itself last moved — the carrier's own scan. */
const val SCAN_LABEL = "Пошта сканувала посилку"

/** When *this app* last asked the carrier. Not the same event, ever. */
const val ASKED_LABEL = "FlowPay питав пошту"

/**
 * The sentence that says the two apart.
 *
 * The card used to say only «перевірено 18:11», which is when the app asked. A
 * parcel that had not moved since yesterday therefore looked like a parcel that
 * had just moved, and that is the confusion this whole screen was built for.
 */
const val TIMES_NOTE = "Перший час — коли посилка справді рухалась. " +
    "Другий — коли додаток про неї питав."

/** What the carrier's last scan is worth saying, or the fact that there is none. */
fun scanValue(scannedAt: LocalDateTime?, today: LocalDate): String =
    scannedAt?.let { momentLabel(it, today) } ?: "ще не сканувала"

/** What the app's last check is worth saying. */
fun askedValue(checkedAt: Long): String =
    if (checkedAt > 0) timeLabel(checkedAt) else "ще не питав"

/**
 * How long the parcel has stood still, when that is long enough to be news.
 *
 * Null under a day, and null once it has been collected: a parcel in your hands
 * is supposed to stop moving, and counting the days since would be the app
 * nagging about something that already went right.
 */
fun standstillNote(scannedAt: LocalDateTime?, now: LocalDateTime, stage: String): String? {
    if (scannedAt == null || stage == RECEIVED) return null
    val days = ChronoUnit.DAYS.between(scannedAt, now).toInt()
    if (days < 1) return null
    return "Не рухалась ${daysLabel(days)}"
}

/**
 * What the observation list is, in the one line that stops it being a lie.
 *
 * It is never Nova Poshta's own journal, and on a parcel added before the app
 * started keeping one it is empty through no fault of the parcel. Both of those
 * have to be said on the screen, or a short list reads as a parcel that has
 * barely moved.
 */
fun sightingsNote(seen: List<Sighting>, checkedAt: Long): String = when {
    seen.isEmpty() && checkedAt <= 0L ->
        "FlowPay ще не питав перевізника. Список почне наповнюватись після першої перевірки."
    seen.isEmpty() ->
        "Ця посилка з'явилась раніше, ніж FlowPay почав вести цей список. " +
            "Того, що було до першої перевірки, Нова Пошта в цій відповіді не віддає."
    seen.size == 1 ->
        "Поки що одна відмітка — та, що була на момент першої перевірки. " +
            "Рухи до неї сюди не потрапляють."
    else ->
        "Це те, що бачив FlowPay, а не журнал Нової Пошти. " +
            "Рухи до першої перевірки сюди не потрапляють."
}

/** The heading for that list, worded so it cannot be mistaken for the carrier's. */
const val SIGHTINGS_TITLE = "Що бачив FlowPay"

/**
 * When one observation was made, in the phone's own zone.
 *
 * The zone is a parameter rather than read inside, so the wording can be pinned
 * in a test instead of depending on the machine the test runs on — which for this
 * project is a CI box in UTC and a phone in Kyiv.
 */
fun sightingLabel(seen: Sighting, today: LocalDate, zone: java.time.ZoneId): String =
    momentLabel(
        java.time.Instant.ofEpochMilli(seen.atMillis).atZone(zone).toLocalDateTime(),
        today
    )

/**
 * Which two ends of the journey are a counter and which are a doorstep.
 *
 * Blank for anything unrecognised, and blank means the row is not drawn: a line
 * reading "ServiceType: WarehouseWarehouse" is the field, not the answer.
 */
fun serviceTypeLabel(raw: String): String = when (raw.trim().lowercase()) {
    "warehousewarehouse" -> "Відділення → відділення"
    "warehousedoors" -> "Відділення → адреса"
    "doorswarehouse" -> "Адреса → відділення"
    "doorsdoors" -> "Адреса → адреса"
    "warehousepostomat" -> "Відділення → поштомат"
    "doorspostomat" -> "Адреса → поштомат"
    else -> ""
}

/** What sort of place it is waiting at, in the word he would use for it. */
fun warehouseCategoryLabel(raw: String): String = when (raw.trim().lowercase()) {
    "postomat" -> "Поштомат"
    "branch", "warehouse" -> "Відділення"
    "store", "parcel shop", "parcelshop" -> "Пункт видачі"
    "postal service", "postfinance" -> "Поштовий сервіс"
    else -> ""
}

/**
 * What collecting it actually involves, which is not the same errand everywhere.
 *
 * A locker is opened with a code and has no queue and no one to ask; a pickup
 * point is somebody else's shop with somebody else's opening hours. The days of
 * free storage differ too, but those come from `DatePayedKeeping` and are already
 * on the screen, so this points at that row instead of quoting a tariff that
 * could change under it.
 */
fun collectionNote(raw: String, storageKnown: Boolean): String? {
    val tail = if (storageKnown) " Безкоштовне зберігання тут коротше, ніж у відділенні — скільки лишилось, сказано вище." else ""
    return when (raw.trim().lowercase()) {
        "postomat" -> "Забирати з комірки, а не в каси: код приходить у SMS.$tail"
        "store", "parcel shop", "parcelshop" ->
            "Це пункт видачі в чужому магазині, а не відділення — графік у нього свій."
        else -> null
    }
}

/** Who settles the delivery bill. */
fun payerLabel(raw: String): String = when (raw.trim().lowercase()) {
    "sender" -> "Відправник"
    "recipient" -> "Одержувач"
    "thirdperson" -> "Третя особа"
    else -> ""
}

/** How it is settled. */
fun paymentMethodLabel(raw: String): String = when (raw.trim().lowercase()) {
    "cash" -> "Готівкою"
    "noncash" -> "Безготівково"
    else -> ""
}

/** What the carrier thinks it is carrying. */
fun cargoTypeLabel(raw: String): String = when (raw.trim().lowercase()) {
    "cargo" -> "Вантаж"
    "parcel" -> "Посилка"
    "documents" -> "Документи"
    "tireswheels" -> "Шини та диски"
    "pallet" -> "Палета"
    "moneytransfer", "money transfer" -> "Грошовий переказ"
    else -> ""
}

/** A weight for reading, with the kopeck-grade decimals the carrier sends dropped. */
fun weightLabel(kg: Double): String = "${bareAmount(kg)} кг"

/** Ukrainian plural for how many boxes one parcel is. */
fun seatsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "місць"
        last == 1 -> "місце"
        last in 2..4 -> "місця"
        else -> "місць"
    }
    return "$count $word"
}

/**
 * What can still be done with the parcel, as things to do rather than as flags.
 *
 * Empty when the carrier allows none of them, and an empty list is not drawn: a
 * heading over three greyed-out lines saying "no" three times is worse than no
 * heading.
 */
fun parcelOptions(details: ParcelDetails): List<String> = listOfNotNull(
    "Можна переадресувати на інше відділення".takeIf { details.canRedirect },
    "Можна відмовитись і повернути відправнику".takeIf { details.canRefuse },
    "Можна продовжити безкоштовне зберігання".takeIf { details.canExtendTerm }
)

/**
 * The stored form of the two records above.
 *
 * Both halves live here, next to each other, because a field written by one and
 * not read by the other is silently lost — and losing them costs more than a
 * field usually would: the observation log cannot be re-fetched from anywhere.
 * `orderJson`/`orderOf` call straight into these, so the thirty-day bin and the
 * backup restore carry a parcel whole.
 *
 * A moment is stored as its ISO text. `LocalDateTime.toString()` and `parse` are
 * both locale independent and both lossless, and the alternative — epoch millis —
 * would need a time zone that the carrier never states.
 */
fun detailsJson(details: ParcelDetails): JSONObject = JSONObject()
    .put("cs", details.citySender).put("cr", details.cityRecipient)
    .put("ws", details.warehouseSender).put("wsa", details.warehouseSenderAddress)
    .put("wr", details.warehouseRecipient).put("wra", details.warehouseRecipientAddress)
    .put("wn", details.warehouseNumber).put("wc", details.warehouseCategory)
    .put("st", details.serviceType)
    .put("dc", details.createdAt?.toString().orEmpty())
    .put("ds", details.scannedAt?.toString().orEmpty())
    .put("du", details.trackingUpdatedAt?.toString().orEmpty())
    .put("dw", details.documentWeight).put("fw", details.factualWeight)
    .put("vw", details.volumeWeight).put("sa", details.seats)
    .put("pt", details.payerType).put("pm", details.paymentMethod)
    .put("ct", details.cargoType)
    .put("rd", details.canRedirect).put("rf", details.canRefuse)
    .put("te", details.canExtendTerm)

fun detailsOf(o: JSONObject?): ParcelDetails {
    if (o == null) return ParcelDetails()
    return ParcelDetails(
        citySender = o.optString("cs"), cityRecipient = o.optString("cr"),
        warehouseSender = o.optString("ws"), warehouseSenderAddress = o.optString("wsa"),
        warehouseRecipient = o.optString("wr"),
        warehouseRecipientAddress = o.optString("wra"),
        warehouseNumber = o.optString("wn"), warehouseCategory = o.optString("wc"),
        serviceType = o.optString("st"),
        createdAt = storedMoment(o.optString("dc")),
        scannedAt = storedMoment(o.optString("ds")),
        trackingUpdatedAt = storedMoment(o.optString("du")),
        documentWeight = o.optDouble("dw", 0.0), factualWeight = o.optDouble("fw", 0.0),
        volumeWeight = o.optDouble("vw", 0.0), seats = o.optInt("sa", 0),
        payerType = o.optString("pt"), paymentMethod = o.optString("pm"),
        cargoType = o.optString("ct"),
        canRedirect = o.optBoolean("rd", false), canRefuse = o.optBoolean("rf", false),
        canExtendTerm = o.optBoolean("te", false)
    )
}

/** A stored moment back out again, or null for one that was never written. */
private fun storedMoment(raw: String): LocalDateTime? =
    raw.takeIf { it.isNotBlank() }?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

fun sightingsJson(seen: List<Sighting>): JSONArray {
    val array = JSONArray()
    for (one in seen) {
        array.put(JSONObject().put("c", one.code).put("t", one.text).put("a", one.atMillis))
    }
    return array
}

fun sightingsOf(array: JSONArray?): List<Sighting> {
    if (array == null) return emptyList()
    val out = mutableListOf<Sighting>()
    for (index in 0 until array.length()) {
        val one = array.optJSONObject(index) ?: continue
        out.add(Sighting(one.optInt("c", 0), one.optString("t"), one.optLong("a", 0L)))
    }
    return out
}

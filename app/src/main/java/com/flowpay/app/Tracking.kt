package com.flowpay.app

import org.json.JSONObject
import java.time.LocalDate

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
    val amountToPay: Double
)

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
        amountToPay = item.optString("AmountToPay").replace(',', '.').toDoubleOrNull() ?: 0.0
    )
}

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
    amountToPay = status.amountToPay
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

package com.flowpay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.LocalDate

/**
 * The drawing for ParcelsMore.kt: the pickup point's chips, the parcels' money in
 * the forecast, a return from its tile to its sheet, the warranty chips, «Як тобі
 * …?», and the phone number dialog. Every decision is made over there; these only
 * lay it out, in the bento language of §16.
 */

// ------------------------------------------------------------ the pickup point

/**
 * «🕘 сьогодні до 21:00 · ⚡ генератор · 💳 термінал · 👕 примірочна» under the
 * address. Sand pills, and peach for the one that says hurry: the closest the six
 * pastels come to amber, so no colour is added for it.
 */
@Composable
fun PointChipsRow(chips: List<PointChip>, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        verticalArrangement = Arrangement.spacedBy(Space.sm)
    ) {
        chips.forEach { chip ->
            val fill = if (chip.warn) TilePeach else TileSand
            Row(
                Modifier
                    .clip(Radius.pill)
                    .background(fill)
                    .padding(horizontal = Space.md, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EmojiGlyph(chip.emoji, 16.dp)
                Spacer(Modifier.width(Space.xs))
                Text(
                    chip.text,
                    color = inkOn(fill),
                    fontSize = Type.captionSize,
                    fontWeight = if (chip.warn) Type.strong else Type.medium,
                    maxLines = 1
                )
            }
        }
    }
}

// ------------------------------------------------------------ cash on delivery

/** The parcels' money inside the forecast tile: «📦 1 249 ₴ · Навушники». */
@Composable
fun CodChipsRow(chips: List<CodChip>, colour: Color, modifier: Modifier = Modifier) {
    if (chips.isEmpty()) return
    val ink = inkOn(colour)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        chips.forEach { chip ->
            Row(
                Modifier
                    .clip(Radius.pill)
                    .background(ink.copy(alpha = 0.1f))
                    .padding(horizontal = Space.sm, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EmojiGlyph(chip.emoji, 16.dp)
                Spacer(Modifier.width(Space.xs))
                Text(
                    chip.text,
                    color = ink,
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = Tabular
                )
            }
        }
    }
}

/** «📦 ще 2 посилки до оплати: 1 498 ₴», under the free-money figure and apart from it. */
@Composable
fun ParcelsToPayLine(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        EmojiGlyph("📦", 18.dp)
        Spacer(Modifier.width(Space.sm))
        Text(text, color = TextSecondary, fontSize = Type.captionSize, lineHeight = Type.captionLine)
    }
}

// ------------------------------------------------------------ a return

/** Three short bars for the three stops of a return. */
@Composable
fun ReturnSegments(reached: Int, filled: Color, empty: Color) {
    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        repeat(RETURN_STAGES.size) { index ->
            androidx.compose.foundation.layout.Box(
                Modifier
                    .width(14.dp)
                    .height(4.dp)
                    .background(if (index <= reached) filled else empty, Radius.pill)
            )
        }
    }
}

/** The dark pill inside a tile: the one thing the tile is waiting for. */
@Composable
private fun TileAction(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(Radius.pill)
            .background(TileInk)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.TaskAlt, null, Modifier.size(18.dp), tint = Accent)
        Spacer(Modifier.width(Space.sm))
        Text(text, color = Accent, fontSize = Type.captionSize, fontWeight = Type.strong)
    }
}

/**
 * A purchase on its way back, as a tile among the parcels: it is an errand again
 * until the money is back. The pill is the next step when it is a tap — «Магазин
 * отримав» without a waybill to follow, «Гроші повернулись ✓» once the shop has it.
 */
@Composable
fun ReturnRow(
    order: Order,
    today: Long,
    colour: Color,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
    onShopGot: () -> Unit,
    onMoneyBack: () -> Unit
) {
    val refund = order.refund ?: return
    val ink = inkOn(colour)
    val late = refundOverdueDays(refund, today) != null
    BentoTile(
        colour,
        modifier.padding(horizontal = Space.screen).fillMaxWidth(),
        onClick = onOpen,
        onClickLabel = "Детальніше"
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (order.image.isNotBlank()) {
                AsyncImage(
                    crossfadeImage(order.image),
                    order.name,
                    Modifier.size(52.dp).clip(Radius.sm),
                    contentScale = ContentScale.Crop
                )
            } else {
                EmojiGlyph(shownEmoji(order), 44.dp, Modifier.padding(4.dp))
            }
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiGlyph("↩️", 14.dp)
                    Spacer(Modifier.width(Space.xs))
                    Text("Повернення", color = softInkOn(colour), fontSize = Type.captionSize, fontWeight = Type.medium)
                    Spacer(Modifier.width(Space.sm))
                    ReturnSegments(returnStageIndex(refund), ink, ink.copy(alpha = 0.18f))
                }
                Text(
                    order.name,
                    fontSize = Type.bodySize,
                    lineHeight = Type.bodyLine,
                    fontWeight = Type.medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp)
                )
                // The waybill is on the page; on the tile it wrapped the sum in two.
                Text(
                    "до повернення ${money(refund.amount)}",
                    color = softInkOn(colour),
                    fontSize = Type.captionSize,
                    maxLines = 1,
                    style = Tabular
                )
                Text(
                    refundStatusLine(refund, today),
                    color = if (late) TileAlarm else softInkOn(colour),
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = softInkOn(colour))
        }
        val followed = detectCarrier(refund.tracking) == CARRIER_NOVA_POSHTA
        when {
            refund.shopGotDay > 0L -> {
                Spacer(Modifier.height(Space.md))
                TileAction("Гроші повернулись", onMoneyBack)
            }
            !followed -> {
                Spacer(Modifier.height(Space.md))
                TileAction("Магазин отримав", onShopGot)
            }
        }
    }
}

/**
 * The return on the purchase's own page: its three stops, what is owed, the
 * waybill going back with its own status, and the buttons for what is done by hand.
 */
@Composable
fun ReturnBlock(
    order: Order,
    today: Long,
    modifier: Modifier = Modifier,
    onShopGot: () -> Unit,
    onMoneyBack: () -> Unit,
    onEdit: () -> Unit
) {
    val refund = order.refund ?: return
    val late = refundOverdueDays(refund, today) != null
    Column(modifier.fillMaxWidth()) {
        StageRail(
            stages = RETURN_STAGES,
            current = RETURN_STAGES[returnStageIndex(refund)],
            modifier = Modifier.padding(vertical = Space.sm)
        ) { picked ->
            when (RETURN_STAGES.indexOf(picked)) {
                1 -> onShopGot()
                2 -> onMoneyBack()
            }
        }
        Text(
            refundStatusLine(refund, today),
            color = if (late) Negative else TextPrimary,
            fontSize = Type.bodySize,
            lineHeight = Type.bodyLine,
            fontWeight = Type.medium
        )
        Spacer(Modifier.height(Space.sm))
        LeaderRow("До повернення", money(refund.amount))
        if (refund.tracking.isNotBlank()) LeaderRow("Зворотна накладна", refund.tracking)
        if (refund.statusText.isNotBlank() && refund.shopGotDay == 0L) {
            LeaderRow("Що каже пошта", refund.statusText)
        }
        if (refund.shopGotDay > 0L) {
            LeaderRow("Магазин отримав", formatDate(LocalDate.ofEpochDay(refund.shopGotDay)))
            LeaderRow("Строк на гроші", "${daysLabel(refund.days)}, до ${formatDate(LocalDate.ofEpochDay(refund.shopGotDay + refund.days))}", alarm = late)
        }
        if (refund.reason.isNotBlank()) {
            Text(
                "Причина: ${refund.reason}",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
        Row(Modifier.padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            if (refund.shopGotDay == 0L) {
                OutlinedButton(
                    onShopGot,
                    shape = Radius.sm,
                    border = BorderStroke(1.dp, HairLine),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) { Text("Магазин отримав") }
            }
            OutlinedButton(
                onMoneyBack,
                shape = Radius.sm,
                border = BorderStroke(1.dp, if (refund.shopGotDay > 0L) Accent else HairLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = if (refund.shopGotDay > 0L) Accent else TextPrimary)
            ) {
                Icon(Icons.Default.TaskAlt, null)
                Text("  Гроші повернулись")
            }
        }
        TextButton(onEdit, Modifier.padding(top = Space.xs)) { Text("Змінити повернення") }
    }
}

/**
 * «↩️ Повертаю»: how much should come back (what was paid, to start with), the
 * return waybill — pasted out of any message, the same way as on the add form —
 * an optional reason, and the shop's days. On a return already under way the same
 * sheet edits it, and is where it can be called off.
 */
@Composable
fun ReturnSheet(
    order: Order,
    today: Long,
    close: () -> Unit,
    save: (Order) -> Unit
) {
    val existing = order.refund
    var amountText by remember(order.id) { mutableStateOf(amountText(existing?.amount ?: refundDefaultAmount(order))) }
    var tracking by remember(order.id) { mutableStateOf(existing?.tracking.orEmpty()) }
    var reason by remember(order.id) { mutableStateOf(existing?.reason.orEmpty()) }
    var days by remember(order.id) { mutableIntStateOf(existing?.days ?: REFUND_DAYS_DEFAULT) }
    val context = LocalContext.current
    val amount = parseAmount(amountText)
    FormSheet(
        title = if (existing == null) "Повертаю" else "Повернення",
        // Short: «Почати повернення» broke over two lines in the sheet's button.
        confirmLabel = if (existing == null) "Повертаю" else "Зберегти",
        confirmEnabled = amount > 0.0,
        onConfirm = { save(startReturn(order, amount, tracking, reason, days, today)) },
        onDismiss = close
    ) {
        Text(order.name, fontSize = Type.captionSize, color = TextSecondary)
        NumberField("Скільки мають повернути, ₴", amountText) { amountText = it }
        OutlinedTextField(
            tracking,
            { tracking = it },
            Modifier.fillMaxWidth().padding(top = Space.md),
            label = { Text("Номер зворотної накладної, якщо є") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                // Read only when tapped, as on the add form: Android shows its own
                // notice whenever the clipboard is read.
                IconButton({
                    val clip = runCatching {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
                    }.getOrNull()
                    trackingNumberIn(clip)?.let { tracking = it }
                }) { Icon(Icons.Default.ContentPaste, "Вставити номер") }
            }
        )
        Text(
            if (detectCarrier(tracking) == CARRIER_NOVA_POSHTA) {
                "FlowPay сам побачить, коли магазин отримає посилку."
            } else {
                "Без накладної Нової пошти «Магазин отримав» позначається дотиком."
            },
            color = TextDisabled,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            modifier = Modifier.padding(top = Space.xs)
        )
        OutlinedTextField(
            reason,
            { reason = it.take(200) },
            Modifier.fillMaxWidth().padding(top = Space.md),
            label = { Text("Причина (необов'язково)") }
        )
        Text(
            "Гроші мають повернути за",
            color = TextSecondary,
            fontSize = Type.captionSize,
            modifier = Modifier.padding(top = Space.md)
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            (REFUND_DAY_CHOICES + listOfNotNull(days.takeIf { it !in REFUND_DAY_CHOICES })).forEach { choice ->
                FilterChip(
                    selected = days == choice,
                    onClick = { days = choice },
                    label = { Text(daysLabel(choice)) }
                )
            }
        }
        Text(
            REFUND_NOTE,
            color = TextDisabled,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            modifier = Modifier.padding(top = Space.xs)
        )
        if (existing != null) {
            TextButton({ save(cancelReturn(order)) }, Modifier.padding(top = Space.sm)) {
                Text("Не повертаю — скасувати повернення", color = Negative)
            }
        }
    }
}

// ------------------------------------------------------------ warranty

/**
 * «Гарантія: немає · 12 · 24 · 36 міс · своя дата», counted from [start] — the day
 * the carrier says it was collected, or the day it was filed. Says back the end.
 */
@Composable
fun WarrantyPicker(start: LocalDate, until: Long, today: LocalDate, set: (Long) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    val choice = warrantyChoice(until, start)
    Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
        Text("Гарантія", color = TextSecondary, fontSize = Type.captionSize)
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            WARRANTY_CHOICES.forEach { months ->
                FilterChip(
                    selected = choice == months,
                    onClick = { set(warrantyEnd(start, months)) },
                    label = { Text(warrantyChoiceLabel(months)) }
                )
            }
            FilterChip(
                selected = choice == null,
                onClick = { picking = true },
                label = { Text("своя дата") }
            )
        }
        Text(
            if (until > 0L) {
                "До ${formatDate(LocalDate.ofEpochDay(until))} · від ${formatDate(start)}. За місяць до кінця нагадаю."
            } else {
                "Рахується від ${formatDate(start)} — дня, коли посилку забрали, або коли покупку завершено."
            },
            color = TextDisabled,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            modifier = Modifier.padding(top = Space.xs)
        )
    }
    if (picking) {
        val millisPerDay = 86_400_000L
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (until.takeIf { it > 0L } ?: start.plusYears(1).toEpochDay()) * millisPerDay,
            selectableDates = object : SelectableDates {
                // A warranty that has already run out is not worth recording.
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis / millisPerDay >= today.toEpochDay()
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { set(it / millisPerDay) }
                    picking = false
                }) { Text("Обрати") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Скасувати") } }
        ) {
            DatePicker(state)
        }
    }
}

// ------------------------------------------------------------ «Як тобі …?»

/**
 * The question on an archived card, three weeks on: four emoji, and the optional
 * «Купити таке ще раз?». A tap answers; there is no confirm, and nothing is
 * celebrated — the answer simply takes the question's place.
 */
@Composable
fun DelightQuestion(order: Order, onDelight: (Delight) -> Unit, onAgain: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = Space.sm)) {
        Text(delightQuestion(order), fontSize = Type.bodySize, fontWeight = Type.medium)
        Row(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Delight.entries.forEach { delight ->
                EmojiGlyph(
                    delight.emoji,
                    36.dp,
                    Modifier
                        .clip(Radius.pill)
                        .clickable(onClickLabel = "Відповісти ${delight.emoji}") { onDelight(delight) }
                        .padding(4.dp)
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Купити таке ще раз?", color = TextSecondary, fontSize = Type.captionSize, modifier = Modifier.weight(1f))
            FilterChip(selected = order.again == 1, onClick = { onAgain(true) }, label = { Text("Так") })
            Spacer(Modifier.width(Space.sm))
            FilterChip(selected = order.again == -1, onClick = { onAgain(false) }, label = { Text("Ні") })
        }
    }
}

/** The answer in the question's place. */
@Composable
fun DelightAnswerView(answer: DelightAnswer) {
    Column(Modifier.fillMaxWidth().padding(top = Space.xs)) {
        answer.why?.let {
            Text(it, color = TextSecondary, fontSize = Type.captionSize, lineHeight = Type.captionLine)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Через три тижні:", color = TextSecondary, fontSize = Type.captionSize)
            Spacer(Modifier.width(Space.xs))
            EmojiGlyph(answer.emoji, 18.dp)
            answer.again?.let {
                Spacer(Modifier.width(Space.xs))
                Text("· $it", color = TextSecondary, fontSize = Type.captionSize)
            }
        }
    }
}

/** «Гаджети: 3 з 4 — 😍», quietly, under the category of a new wish. */
@Composable
fun CategoryJoyLine(joy: CategoryJoy) {
    Row(Modifier.padding(top = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(joy.text, color = TextSecondary, fontSize = Type.captionSize)
        Spacer(Modifier.width(Space.xs))
        EmojiGlyph(joy.emoji, 16.dp)
    }
}

// ------------------------------------------------------------ the phone number

/**
 * «Мій номер для Нової пошти». Saved only when it reads as a number; emptied, it
 * is forgotten. The note says what it is for and where it does not go.
 */
@Composable
fun NovaPhoneDialog(current: String, close: () -> Unit, save: (String) -> Boolean) {
    var text by remember { mutableStateOf(current.takeIf { it.isNotBlank() }?.let { "+$it" }.orEmpty()) }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Мій номер для Нової пошти") },
        text = {
            Column {
                OutlinedTextField(
                    text,
                    { text = it; wrong = false },
                    Modifier.fillMaxWidth(),
                    label = { Text("Номер телефону") },
                    singleLine = true,
                    isError = wrong,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    supportingText = if (wrong) {
                        { Text("Схоже, це не номер. Наприклад: 050 123 45 67") }
                    } else {
                        null
                    }
                )
                Text(
                    PHONE_NOTE,
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine,
                    modifier = Modifier.padding(top = Space.sm)
                )
            }
        },
        confirmButton = {
            Button({ if (save(text)) close() else wrong = true }) { Text("Зберегти") }
        },
        dismissButton = {
            Row {
                if (current.isNotBlank()) {
                    TextButton({ save(""); close() }) { Text("Прибрати", color = Negative) }
                }
                TextButton(close) { Text("Скасувати") }
            }
        }
    )
}

/** The per-parcel number on the parcel's edit dialog. */
@Composable
fun RecipientPhoneField(value: String, set: (String) -> Unit) {
    OutlinedTextField(
        value,
        set,
        Modifier.fillMaxWidth().padding(top = Space.md),
        label = { Text("Номер одержувача, якщо посилка не на вас") },
        singleLine = true,
        isError = value.isNotBlank() && normalizedPhone(value).isEmpty(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
    )
}

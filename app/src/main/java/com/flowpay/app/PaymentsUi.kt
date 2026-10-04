package com.flowpay.app

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.ZoneId

/**
 * The screens of a payment's life (PaymentsLife.kt): the promo field in the
 * forms, «Як скасувати», cancel and pause on the payment's own sheet, a plan's
 * «Погасив достроково» and «Повернув», the question an ended cancellation asks,
 * and the monobank cards. Everything shown comes from PaymentsLife.kt and Mono.kt;
 * nothing here decides anything.
 */

private const val MILLIS_PER_DAY = 86_400_000L

// ------------------------------------------------------------ the forms

/**
 * «Пробний період або акція»: a date, and what is charged until it — nothing for a
 * free trial, as before, or a promo price («пів ціни», «150 ₴ до 1 лютого»).
 *
 * A date rather than a number of days, because a trial or a discount is sold in
 * days and charged on a date, and that conversion is the arithmetic nobody does.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromoField(
    trialEnd: Long,
    promo: String,
    amount: Double,
    currency: String,
    today: LocalDate,
    setEnd: (Long) -> Unit,
    setPromo: (String) -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    val ends = trialEnd.takeIf { it > 0L }?.let { LocalDate.ofEpochDay(it) }
    val price = parseAmount(promo)
    Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
        Text("Пробний період або акція", color = TextSecondary, fontSize = Type.captionSize)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton({ picking = true }, Modifier.weight(1f)) {
                Text(
                    when {
                        ends == null -> "Додати безкоштовний період чи знижку"
                        ends.isAfter(today) -> "До ${formatDate(ends)}"
                        else -> "Скінчилось ${formatDate(ends)}"
                    }
                )
            }
            if (ends != null) {
                TextButton({
                    setEnd(0L)
                    setPromo("")
                }) { Text("Прибрати", color = Negative) }
            }
        }
        if (ends != null) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                FilterChip(price <= 0.0, { setPromo("") }, { Text("Безкоштовно") })
                if (amount > 0.0) {
                    val half = kotlin.math.round(amount / 2 * 100) / 100.0
                    FilterChip(price > 0.0 && price == half, { setPromo(amountText(half)) }, { Text("Пів ціни") })
                }
            }
            NumberField(if (currency == USD) "Ціна до цієї дати, $" else "Ціна до цієї дати, ₴", promo, setPromo)
            promoNote(amount, price, trialEnd, currency)?.let { note ->
                Text(
                    note,
                    Modifier.padding(top = Space.xs),
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
            }
        }
    }
    if (picking) {
        val state = rememberDatePickerState(
            // A month out: the length almost every trial actually runs.
            initialSelectedDateMillis = (ends?.takeIf { it.isAfter(today) } ?: today.plusMonths(1)).toEpochDay() * MILLIS_PER_DAY,
            selectableDates = object : SelectableDates {
                // A period that ran out yesterday is not a trial or a promo, it is
                // the full price, and entering one would hide it behind a discount.
                override fun isSelectableDate(utcTimeMillis: Long) =
                    utcTimeMillis / MILLIS_PER_DAY > today.toEpochDay()
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    // The picker works in UTC midnights, so this is an exact day.
                    state.selectedDateMillis?.let { setEnd(it / MILLIS_PER_DAY) }
                    picking = false
                }) { Text("Обрати") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Скасувати") } }
        ) {
            DatePicker(state)
        }
    }
}

/**
 * «Як скасувати»: the service's own page from the built-in directory, the
 * owner's own link, or a web search. The link can be replaced, because a
 * service's pages move and the owner is the one who notices.
 */
@Composable
fun CancelHelp(pay: Pay, cancelUrl: String, setCancelUrl: (String) -> Unit) {
    val context = LocalContext.current
    val link = cancelLink(pay.copy(cancelUrl = cancelUrl))
    var editing by remember { mutableStateOf(cancelUrl.isNotBlank()) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = Space.md)
            .clip(Radius.sm)
            .clickable(onClickLabel = "Відкрити") { openLink(context, link.url) }
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EmojiGlyph("🚪", 32.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text("Як скасувати", fontSize = Type.bodySize, fontWeight = Type.medium)
            Text(
                if (link.searched) "Відкриє ${link.label}" else "Відкриє сторінку керування: ${link.label}",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
    }
    if (editing) {
        OutlinedTextField(
            cancelUrl,
            setCancelUrl,
            Modifier.fillMaxWidth(),
            label = { Text("Своє посилання для скасування") },
            singleLine = true,
            isError = cancelUrl.isNotBlank() && !isSupportedWebUrl(cancelUrl.trim()),
            supportingText = { Text("Порожнє — FlowPay відкриє своє") }
        )
    } else {
        TextButton({ editing = true }) { Text("Вказати своє посилання") }
    }
}

/**
 * What can happen to a payment from its own sheet: «Скасував ✓» and «Пауза», or —
 * once one of them has — taking it back.
 */
@Composable
fun LifeActions(
    pay: Pay,
    today: LocalDate,
    onCancel: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onRestart: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
        when (lifeOf(pay, today)) {
            PayLife.CANCELLED, PayLife.ENDED -> StateRow(
                cancelledLine(pay, today)
                    ?: "скасовано · оплачено до ${dayMonth(LocalDate.ofEpochDay(pay.stopsAfter))}",
                "Відновити",
                onRestart
            )
            PayLife.PAUSED -> StateRow(pausedLine(pay).orEmpty(), "Відновити", onResume)
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    QuietButton("Скасував ✓", onCancel)
                    QuietButton("Пауза", onPause)
                }
                Text(
                    "Скасовану підписку видно до кінця оплаченого періоду, а далі вона не рахується. " +
                        "Платіж на паузі не рахується й не нагадує, доки його не відновити.",
                    Modifier.padding(top = Space.xs),
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
            }
        }
    }
}

/**
 * A plan's own ending: «Погасив достроково», «Повернув товар», which purchase it
 * pays for — or, once ended by hand, taking that back.
 */
@Composable
fun PlanActions(
    pay: Pay,
    today: LocalDate,
    orders: List<Order>,
    order: String,
    setOrder: (String) -> Unit,
    onPaidOff: () -> Unit,
    onReturned: () -> Unit,
    onRestart: () -> Unit
) {
    var picking by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
        if (pay.stopReason == STOP_PAID_OFF || pay.stopReason == STOP_RETURNED) {
            StateRow(
                if (isFinished(pay, today)) finishedPlanLine(pay) else instalmentLine(pay, today).orEmpty(),
                "Відновити розстрочку",
                onRestart
            )
        } else if (isInstalment(pay) && isLive(pay, today)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                QuietButton("Погасив достроково", onPaidOff)
                QuietButton("Повернув товар", onReturned)
            }
        }
        if (orders.isNotEmpty()) {
            val linked = orders.firstOrNull { it.id == order }
            Row(
                Modifier.fillMaxWidth().padding(top = Space.sm).clip(Radius.sm).clickable { picking = true }.padding(vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically
            ) {
                EmojiGlyph(linked?.let { shownEmoji(it) } ?: "📦", 28.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text("За покупку", fontSize = Type.bodySize, fontWeight = Type.medium)
                    Text(
                        linked?.name ?: "Не вказано · торкніться, щоб вибрати з Покупок",
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("За яку покупку ця розстрочка") },
            text = {
                Column(Modifier.padding(top = Space.xs)) {
                    // Newest first: a plan is set up soon after the thing is bought.
                    orders.sortedByDescending { it.archivedDay.takeIf { day -> day > 0L } ?: Long.MAX_VALUE }
                        .take(12)
                        .forEach { item ->
                            Text(
                                item.name,
                                Modifier
                                    .fillMaxWidth()
                                    .clip(Radius.sm)
                                    .clickable {
                                        setOrder(item.id)
                                        picking = false
                                    }
                                    .padding(vertical = Space.sm),
                                fontSize = Type.bodySize,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                }
            },
            confirmButton = {
                TextButton({
                    setOrder("")
                    picking = false
                }) { Text("Не вказувати") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Скасувати") } }
        )
    }
}

/**
 * A state said in a line, with the one way back from it underneath — beside it, a
 * long state wrapped into a column four words wide.
 */
@Composable
private fun StateRow(line: String, action: String, onAction: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        // The tile's caption, standing on its own here, so it starts a sentence.
        Text(line.replaceFirstChar { it.uppercaseChar() }, fontSize = Type.bodySize, lineHeight = Type.bodyLine)
        Row(Modifier.fillMaxWidth().padding(top = Space.xs), horizontalArrangement = Arrangement.End) {
            QuietButton(action, onAction)
        }
    }
}

/** The outlined button the sheets already use for their secondary actions. */
@Composable
private fun QuietButton(label: String, onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        shape = Radius.sm,
        border = BorderStroke(1.dp, HairLine),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
    ) { Text(label) }
}

// ------------------------------------------------------------ dialogs

/**
 * «Скасував ✓», with the last day it still works — the end of what was paid for,
 * which the owner can move. A date already behind means it is over: the payment
 * goes straight to the bin («Прибрати» from «Мовчать»).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CancelDialog(
    name: String,
    initial: LocalDate,
    today: LocalDate,
    onDismiss: () -> Unit,
    onConfirm: (LocalDate) -> Unit
) {
    var until by remember { mutableStateOf(initial) }
    var picking by remember { mutableStateOf(false) }
    val over = until.isBefore(today)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (over) "Прибрати «$name»?" else "«$name» скасовано?") },
        text = {
            Column {
                Text(
                    if (over) {
                        "Оплачений період уже скінчився ${formatDate(until)}. Платіж піде в кошик на 30 днів — " +
                            "звідти його можна повернути."
                    } else {
                        "До ${formatDate(until)} — кінця вже оплаченого періоду — платіж лишиться в списку з позначкою " +
                            "«скасовано», а далі не рахуватиметься ніде. Наступного дня ранкове зведення спитає, чи справді " +
                            "нічого не списали, а за тиждень платіж сам піде в кошик (30 днів, можна повернути)."
                    },
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
                TextButton({ picking = true }, Modifier.padding(top = Space.sm)) {
                    Text(if (over) "Діяло до ${formatDate(until)} · змінити" else "Діє до ${formatDate(until)} · змінити")
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(until) }) { Text(if (over) "Прибрати" else "Скасував") } },
        dismissButton = { TextButton(onDismiss) { Text("Назад") } }
    )
    if (picking) {
        val state = rememberDatePickerState(initialSelectedDateMillis = until.toEpochDay() * MILLIS_PER_DAY)
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { until = LocalDate.ofEpochDay(it / MILLIS_PER_DAY) }
                    picking = false
                }) { Text("Обрати") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Скасувати") } }
        ) {
            DatePicker(state)
        }
    }
}

/**
 * «Погасити достроково?» with what it comes to: [recorded] is the month's mark
 * as [payOffMarks] will write it — this month's payment and everything after it.
 */
@Composable
fun PayOffDialog(pay: Pay, recorded: Double, today: LocalDate, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Погасити «${pay.name}» достроково?") },
        text = {
            Text(
                "У «По місяцях» за ${monthName(today.monthValue).lowercase()} буде записано " +
                    "${amountLabel(recorded, pay.currency)} — платіж цього місяця і все, що лишалося, разом. " +
                    "Після цього розстрочка більше ніде не рахуватиметься.",
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        },
        confirmButton = { TextButton(onConfirm) { Text("Погасив") } },
        dismissButton = { TextButton(onDismiss) { Text("Назад") } }
    )
}

/** «Товар повернули?» — the plan closes, and the purchase it paid for says so. */
@Composable
fun ReturnDialog(pay: Pay, order: Order?, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Товар повернули?") },
        text = {
            Text(
                "Розстрочку «${pay.name}» буде закрито: платежів більше не буде, а вже сплачене лишиться в історії." +
                    (order?.let { " Покупка «${it.name}» отримає позначку «повернуто»." } ?: ""),
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        },
        confirmButton = { TextButton(onConfirm) { Text("Закрити розстрочку") } },
        dismissButton = { TextButton(onDismiss) { Text("Назад") } }
    )
}

// ------------------------------------------------------------ Платежі

/**
 * The question an ended cancellation asks, the day what was paid for ran out —
 * the day the next charge would have come. «Не було ✓» sends it to the bin;
 * «Списали» brings it back, with the month ticked.
 */
@Composable
fun EndedTile(pay: Pay, modifier: Modifier = Modifier, onNotCharged: () -> Unit, onCharged: () -> Unit) {
    BentoTile(TileSky, modifier.fillMaxWidth()) {
        TileHeader("Скасовано — перевірте списання", "🧾")
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiGlyph(shownEmoji(pay), 30.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(pay.name, fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TileCaption(endedQuestion(pay), TileSky, maxLines = 3)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onCharged) { Text("Списали — повернути", color = TileInk) }
            Spacer(Modifier.width(Space.sm))
            InkPill("Не було ✓", onNotCharged)
        }
    }
}

/**
 * The quiet states below the timeline: cancelled payments still running out what
 * was paid for, and paused ones. Each says its state and has its one way back.
 */
@Composable
fun LifeTile(pay: Pay, line: String, action: String, modifier: Modifier = Modifier, onAction: () -> Unit, onOpen: () -> Unit) {
    BentoTile(SurfaceRaised, modifier.fillMaxWidth(), onClick = onOpen, onClickLabel = "Змінити") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiGlyph(shownEmoji(pay), 32.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text(pay.name, fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TileCaption("$line · ${amountLabel(pay.amount, pay.currency)}", SurfaceRaised)
            }
            TextButton(onAction) { Text(action, color = TextPrimary) }
        }
    }
}

/** A tile's quiet heading with its emoji at the end, as the monobank tiles have. */
@Composable
private fun TileHeader(text: String, emoji: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), color = TileInkSoft, fontSize = Type.captionSize, fontWeight = Type.medium)
        EmojiGlyph(emoji, 24.dp)
    }
}

/** «Мовчать»: confirmed payments the bank has gone quiet about. */
@Composable
fun SilentTile(items: List<SilentPay>, modifier: Modifier = Modifier, onWait: (SilentPay) -> Unit, onRemove: (SilentPay) -> Unit) {
    BentoTile(TileSand, modifier.fillMaxWidth()) {
        TileHeader("monobank: давно не списувалось", "🔕")
        items.forEach { item ->
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(shownEmoji(item.pay), 30.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(silentLine(item), fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    TileCaption("Скасовано?", TileSand)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onWait(item) }) { Text("Ще чекаю", color = TileInk) }
                Spacer(Modifier.width(Space.sm))
                InkPill("Прибрати") { onRemove(item) }
            }
        }
    }
}

/** «Схоже на подвійне списання», with the bank's own app one tap away. */
@Composable
fun DoubleChargeTile(
    items: List<DoubleCharge>,
    accountCurrency: Map<String, Int>,
    modifier: Modifier = Modifier,
    onOk: (DoubleCharge) -> Unit
) {
    val context = LocalContext.current
    BentoTile(TilePink, modifier.fillMaxWidth()) {
        TileHeader("Схоже на подвійне списання", "⚠️")
        items.forEach { item ->
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(shownEmoji(item.pay), 30.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(item.pay.name, fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TileCaption(doubleChargeLine(item, accountCurrency).substringAfter("${item.pay.name} "), TilePink, maxLines = 3)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onOk(item) }) { Text("Усе гаразд", color = TileInk) }
                Spacer(Modifier.width(Space.sm))
                InkPill("Відкрити monobank") { openMonobank(context) }
            }
        }
    }
}

/** «Списали після скасування?» — open the bank, bring the payment back, or «це не воно». */
@Composable
fun AfterCancelTile(
    items: List<AfterCancel>,
    modifier: Modifier = Modifier,
    onNotIt: (AfterCancel) -> Unit,
    onBringBack: (AfterCancel) -> Unit
) {
    val context = LocalContext.current
    BentoTile(TilePink, modifier.fillMaxWidth()) {
        TileHeader("Списали після скасування?", "⚠️")
        items.forEach { item ->
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(item.pay?.let { shownEmoji(it) } ?: payEmoji(item.name), 30.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TileCaption(
                        listOf(
                            amountLabel(item.charged, item.currency),
                            dayMonth(txDay(item.tx, ZoneId.systemDefault())),
                            item.tx.description
                        ).joinToString(" · "),
                        TilePink,
                        maxLines = 3
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onNotIt(item) }) { Text("Це не воно", color = TileInk) }
                TextButton({ onBringBack(item) }) { Text("Повернути платіж", color = TileInk) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                InkPill("Відкрити monobank") { openMonobank(context) }
            }
        }
    }
}

/**
 * Opens the monobank app, or its site when the app is not there. Asked by package
 * rather than looked up first: starting an app needs no permission to see it.
 */
fun openMonobank(context: Context) {
    val app = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setPackage(MONOBANK_PACKAGE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(app) }.isFailure) openLink(context, "https://www.monobank.ua/")
}

/** The monobank app's package on Google Play. */
const val MONOBANK_PACKAGE = "com.ftband.mono"

package com.flowpay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

/**
 * The tiles and sheets of MoneyPlan.kt, Funds.kt and Afford.kt. Drawing only:
 * every figure and every sentence comes from those files, where it is tested.
 */

/** What the plan's tiles and sheets need, built once in FlowPayApp. */
data class MoneyHost(
    val inputs: MoneyInputs,
    val plan: MoneyPlan,
    val settings: PlanSettings,
    /** monobank's own money on the ticked hryvnia cards; null when it is not connected. */
    val monoBalance: Double? = null,
    /** When that balance was read, epoch ms. */
    val monoAt: Long = 0L,
    val declinedFunds: Set<String> = emptySet(),
    /** The month's «Подарунок собі», so «Чи потягну?» can say whether it survives. */
    val treat: Treat? = null,
    val saveSettings: (PlanSettings) -> Unit = {},
    val saveIncome: (Double) -> Unit = {},
    val updateWishes: ((List<Wish>) -> List<Wish>) -> Unit = {},
    val updateFunds: ((List<Fund>) -> List<Fund>) -> Unit = {},
    val deleteFund: (Fund) -> Unit = {},
    val addPay: (Pay) -> Unit = {},
    val saveRitual: (RitualRecord?) -> Unit = {},
    val declineFund: (String) -> Unit = {},
    val say: (String) -> Unit = {}
) {
    val today: LocalDate get() = inputs.today
    val usdSell: Double get() = inputs.usdSell
}

/** A small dark pill on a pastel — the tile's own ink, never the accent. */
@Composable
fun InkPill(text: String, colour: Color, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = inkOn(colour)
    Box(
        modifier
            .heightIn(min = 40.dp)
            .clip(Radius.pill)
            .background(if (enabled) ink else ink.copy(alpha = 0.3f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.lg),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = colour, fontSize = Type.captionSize, fontWeight = Type.strong, maxLines = 1)
    }
}

/** A switch drawn in the tile's ink, so a pastel tile does not grow a lime thumb. */
@Composable
private fun InkSwitch(checked: Boolean, colour: Color, onChange: (Boolean) -> Unit) {
    val ink = inkOn(colour)
    Switch(
        checked,
        onChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = colour,
            checkedTrackColor = ink,
            checkedBorderColor = ink,
            uncheckedThumbColor = ink.copy(alpha = 0.55f),
            uncheckedTrackColor = ink.copy(alpha = 0.08f),
            uncheckedBorderColor = ink.copy(alpha = 0.4f)
        )
    )
}

// ------------------------------------------------------------ «На життя»

/** The switch and the one number. Off: the app works exactly as it did. */
@Composable
fun LifeDialog(life: LifeCost, close: () -> Unit, save: (LifeCost) -> Unit) {
    var on by remember { mutableStateOf(life.on || life.monthly <= 0.0) }
    var text by remember { mutableStateOf(amountText(life.monthly)) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Витрати на життя") },
        text = {
            Column {
                Text(
                    "Їжа, транспорт, кафе — одне число на місяць, приблизно. Тоді «Вільно» стане «після " +
                        "платежів і життя», і саме від нього рахуватимуть «Подарунок собі», «Плани не сходяться», " +
                        "погода, віджет і плитка в шторці.",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = Space.md).clip(Radius.sm).clickable { on = !on },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Враховувати витрати на життя", Modifier.weight(1f), fontSize = Type.bodySize)
                    Switch(on, { on = it })
                }
                if (on) NumberField("На місяць, ₴", text) { text = it }
            }
        },
        confirmButton = { Button({ save(LifeCost(on, parseAmount(text))) }) { Text("Зберегти") } },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

// ------------------------------------------------------------ the payday

/** «День зарплати» inside the income dialog: a day, the last working day, or none; and an advance. */
@Composable
fun PaydayFields(payday: Payday, today: LocalDate, holidays: Set<Long>, set: (Payday) -> Unit) {
    // Kept here rather than read back from [payday]: «Число місяця» with no
    // number typed yet is not a payday, and reading it back would snap the
    // chips to «Не вказано» before the number could be typed.
    var mode by remember {
        mutableIntStateOf(
            when {
                payday.salary == LAST_WORKING_DAY -> 2
                payday.salary in 1..31 -> 1
                else -> 0
            }
        )
    }
    var dayText by remember { mutableStateOf(if (payday.salary in 1..31) payday.salary.toString() else "") }
    var advanceText by remember { mutableStateOf(if (payday.advance in 1..31) payday.advance.toString() else "") }
    var advanceOn by remember { mutableStateOf(payday.advance in 1..31) }
    fun dayOf(text: String) = text.trim().toIntOrNull()?.takeIf { it in 1..31 } ?: 0
    fun emit(newMode: Int, day: String = dayText, advance: String = advanceText, withAdvance: Boolean = advanceOn) {
        mode = newMode
        val salary = when (newMode) {
            1 -> dayOf(day)
            2 -> LAST_WORKING_DAY
            else -> 0
        }
        set(Payday(salary, if (withAdvance && salary != 0) dayOf(advance) else 0))
    }
    Column(Modifier.fillMaxWidth().padding(top = Space.lg)) {
        Text("День зарплати", color = TextSecondary, fontSize = Type.captionSize)
        // Three plain rows rather than a scrolling row of chips: a lazy row inside
        // an AlertDialog never settled on the JVM renderer, and three choices
        // read better one under another in a dialog this narrow anyway.
        listOf("Не вказано", "Певного числа", "В останній робочий день").forEachIndexed { index, label ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(Radius.sm)
                    .selectable(selected = mode == index, role = Role.RadioButton) { emit(index) }
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = mode == index, onClick = null)
                Spacer(Modifier.width(Space.sm))
                Text(label, fontSize = Type.bodySize)
            }
        }
        if (mode == 1) {
            NumberField("Число місяця, 1–31", dayText) {
                dayText = it
                emit(1, day = it)
            }
        }
        if (mode != 0) {
            Row(
                Modifier.fillMaxWidth().padding(top = Space.sm).clip(Radius.sm).clickable {
                    advanceOn = !advanceOn
                    emit(mode, withAdvance = advanceOn)
                },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Ще аванс", Modifier.weight(1f), fontSize = Type.bodySize)
                Switch(advanceOn, {
                    advanceOn = it
                    emit(mode, withAdvance = it)
                })
            }
            if (advanceOn) {
                NumberField("Число авансу", advanceText) {
                    advanceText = it
                    emit(mode, advance = it)
                }
            }
            nextPaydayNote(payday, today, holidays)?.let {
                Text(
                    "$it. Якщо випадає на вихідний — день перед ним.",
                    Modifier.padding(top = Space.xs),
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
            }
        }
    }
}

// ------------------------------------------------------------ «Скільки можна сьогодні»

/** «Можна ~620 ₴ на день · до зарплати 9 днів», from the card's own money. */
@Composable
fun AllowanceTile(allowance: Allowance, updatedAt: Long, modifier: Modifier = Modifier) {
    val colour = TileMint
    BentoTile(colour, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Скільки можна сьогодні", Modifier.weight(1f), color = TileInkSoft, fontSize = Type.captionSize, fontWeight = Type.medium)
            EmojiGlyph("💳", 28.dp)
        }
        Spacer(Modifier.height(Space.xs))
        if (allowance.left >= 0.0) {
            Row(verticalAlignment = Alignment.Bottom) {
                SplitFigure("~" + money(perDayFigure(allowance.perDay)), 26.sp)
                Spacer(Modifier.width(Space.sm))
                Text("на день", Modifier.padding(bottom = 3.dp), color = TileInkSoft, fontSize = Type.captionSize)
            }
        } else {
            Text(
                allowanceHeadline(allowance),
                color = TileAlarm,
                fontSize = Type.bodySize,
                lineHeight = Type.bodyLine,
                fontWeight = Type.strong
            )
        }
        TileCaption(if (allowance.left >= 0.0) allowanceWhen(allowance) else "ще ${daysLabel(allowance.days)}", colour)
        Spacer(Modifier.height(Space.xs))
        TileCaption(allowanceDetail(allowance, updatedAt), colour, maxLines = 3)
    }
}

// ------------------------------------------------------------ «Розкласти зарплату»

/**
 * The payday tile: what leaves before the next payday, each plan with its sum
 * and a switch, and «Я відклав». Nothing moves money — it is bookkeeping, which
 * is why the button says what already happened.
 */
@Composable
fun RitualTile(
    ritual: Ritual,
    today: LocalDate,
    modifier: Modifier = Modifier,
    onDone: (List<Pair<PlanAsk, Double>>) -> Unit,
    onUndo: (RitualRecord) -> Unit,
    onLater: () -> Unit
) {
    val colour = TileSand
    val touch = rememberTouch()
    BentoTile(colour, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiGlyph("💰", 32.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Розкласти зарплату", fontSize = Type.bodySize, fontWeight = Type.medium)
                TileCaption(ritualHeading(ritual, today), colour)
            }
            if (!ritual.done) {
                IconButton(onLater) { Icon(Icons.Default.Close, "Не зараз", tint = TileInkSoft) }
            }
        }
        val record = ritual.record
        if (ritual.done && record != null) {
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ritualDoneLine(record), Modifier.weight(1f), fontSize = Type.captionSize, fontWeight = Type.medium)
                TextButton({ onUndo(record) }) { Text("Скасувати", color = TileInk) }
            }
            return@BentoTile
        }
        Spacer(Modifier.height(Space.sm))
        TileCaption(ritualPaymentsLine(ritual), colour, maxLines = 3)
        var full by remember(ritual.anchor) { mutableStateOf(false) }
        val on = remember(ritual.anchor, ritual.lines.size) { mutableStateListOf<Boolean>().apply { repeat(ritual.lines.size) { add(true) } } }
        val sums = ritual.lines.map { if (full || !ritual.scaled) it.full else it.proposed }
        Spacer(Modifier.height(Space.sm))
        ritual.lines.forEachIndexed { index, line ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(line.ask.emoji, 24.dp)
                Spacer(Modifier.width(Space.sm))
                Column(Modifier.weight(1f)) {
                    Text(line.ask.name, fontSize = Type.captionSize, fontWeight = Type.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (line.ask.jar) TileCaption("банка monobank — оновиться сама", colour, maxLines = 1)
                }
                Spacer(Modifier.width(Space.sm))
                Text(money(sums[index]), fontSize = Type.captionSize, fontWeight = Type.strong, style = Tabular)
                Spacer(Modifier.width(Space.sm))
                if (index < on.size) InkSwitch(on[index], colour) { on[index] = it }
            }
        }
        if (ritual.scaled) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TileCaption(
                    if (full) "Як у плані — більше, ніж вільно" else "Плани не влазять у вільні ${money(ritual.free.coerceAtLeast(0.0))} — суми зменшено порівну",
                    colour,
                    Modifier.weight(1f),
                    maxLines = 3
                )
                TextButton({ full = !full }) { Text(if (full) "Менші суми" else "Як у плані", color = TileInk) }
            }
        }
        val total = ritual.lines.indices.filter { it < on.size && on[it] }.sumOf { sums[it] }
        Spacer(Modifier.height(Space.xs))
        Text(
            ritualSummary(total, ritual.free, ritual.incomeKnown),
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            fontWeight = Type.medium
        )
        Spacer(Modifier.height(Space.sm))
        Row(verticalAlignment = Alignment.CenterVertically) {
            InkPill("Я відклав", colour, enabled = total > 0.0) {
                touch.committed()
                onDone(ritual.lines.indices.filter { it < on.size && on[it] }.map { ritual.lines[it].ask to sums[it] })
            }
            Spacer(Modifier.width(Space.md))
            TileCaption("Гроші нікуди не переказуються — це облік", colour, Modifier.weight(1f))
        }
    }
}

// ------------------------------------------------------------ «Чи потягну?» and «Місяць наперед»

/** The way into «Чи потягну?» on Огляд. */
@Composable
fun AffordInvite(modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val colour = TileLavender
    BentoTile(colour, modifier.fillMaxWidth(), onClick = onOpen, onClickLabel = "Перевірити покупку") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiGlyph("🧮", 32.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Чи потягну?", fontSize = Type.bodySize, fontWeight = Type.medium)
                TileCaption("Що станеться з грошима до зарплати, якщо купити", colour)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TileInkSoft)
        }
    }
}

/** «Платежі листопада покриті на 64%» — a ring, and what it is made of. */
@Composable
fun MonthAheadTile(ahead: MonthAhead, modifier: Modifier = Modifier) {
    val colour = TilePeach
    BentoTile(colour, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = Space.md)) {
                Text("Місяць наперед", color = TileInkSoft, fontSize = Type.captionSize, fontWeight = Type.medium)
                Spacer(Modifier.height(Space.xs))
                Text(monthAheadHeadline(ahead), fontSize = Type.bodySize, lineHeight = Type.bodyLine, fontWeight = Type.medium)
                cushionDaysLine(ahead)?.let { Text(it, fontSize = Type.captionSize, fontWeight = Type.strong) }
            }
            ProgressRing(
                entranceFraction(ahead.share.coerceIn(0.0, 1.0).toFloat(), delayMs = 300L),
                diameter = 64.dp,
                stroke = 7.dp,
                trackColor = TileInk.copy(alpha = 0.12f),
                ringColor = TileInk
            ) {
                Text("${ahead.percent}%", fontSize = Type.captionSize, fontWeight = Type.strong, style = Tabular)
            }
        }
        Spacer(Modifier.height(Space.sm))
        TileCaption(monthAheadDetail(ahead), colour)
        dearerLine(ahead)?.let { TileCaption(it, colour, Modifier.padding(top = Space.xs), maxLines = 3) }
    }
}

/** The sheet: a price, a day, a balance — and what the purchase does. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AffordSheet(
    host: MoneyHost,
    initialName: String = "",
    initialPrice: Double = 0.0,
    wishId: String? = null,
    onClose: () -> Unit
) {
    val today = host.today
    var name by remember { mutableStateOf(initialName) }
    var priceText by remember { mutableStateOf(amountText(initialPrice)) }
    var whenChoice by remember { mutableIntStateOf(0) }
    var picked by remember { mutableStateOf<LocalDate?>(null) }
    var picking by remember { mutableStateOf(false) }
    var cashText by remember { mutableStateOf(amountText(host.settings.cash)) }
    var partsOpen by remember { mutableStateOf(false) }
    var parts by remember { mutableIntStateOf(6) }
    val mono = host.monoBalance
    val date = when (whenChoice) {
        1 -> thisWeekend(today)
        2 -> picked ?: today
        else -> today
    }
    val price = parseAmount(priceText)
    val typed = parseAmount(cashText).takeIf { cashText.isNotBlank() }
    val balance = mono ?: typed
    val result = if (price > 0.0) affordability(host.inputs, host.plan, price, date, balance, host.treat, wishId) else null

    fun keepBalance() {
        if (mono == null && typed != null && (typed != host.settings.cash || host.settings.cashDay != today.toEpochDay())) {
            host.saveSettings(host.settings.copy(cash = typed, cashDay = today.toEpochDay()))
        }
    }
    FormSheet(
        title = "Чи потягну?",
        confirmLabel = "Готово",
        confirmEnabled = true,
        onConfirm = { keepBalance(); onClose() },
        onDismiss = { keepBalance(); onClose() }
    ) {
        OutlinedTextField(
            name,
            { name = it },
            Modifier.fillMaxWidth(),
            label = { Text("Що купуєте (можна не писати)") },
            singleLine = true
        )
        NumberField("Ціна, ₴", priceText) { priceText = it }
        Text("Коли", Modifier.padding(top = Space.md), color = TextSecondary, fontSize = Type.captionSize)
        LazyRow(Modifier.padding(top = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            items(listOf(0, 1, 2)) { index ->
                val label = when (index) {
                    0 -> "Сьогодні"
                    1 -> "Ці вихідні"
                    else -> picked?.let { dayMonth(it) } ?: "Інша дата"
                }
                FilterChip(whenChoice == index, {
                    whenChoice = index
                    if (index == 2) picking = true
                }, { Text(label, fontSize = Type.captionSize) })
            }
        }
        if (mono != null) {
            Text(
                "На картках monobank ${money(kotlin.math.round(mono))}" + if (host.monoAt > 0L) " · оновлено ${timeLabel(host.monoAt)}" else "",
                Modifier.padding(top = Space.md),
                color = TextSecondary,
                fontSize = Type.captionSize
            )
        } else {
            NumberField("Зараз на картці, ₴", cashText) { cashText = it }
            val age = typedBalanceAge(host.settings.cashDay, today)
            Text(
                if (cashText.isBlank()) "Без залишку відповідь буде лише за планом" else age.ifBlank { "запам'ятаю до наступного разу" },
                Modifier.padding(top = Space.xs),
                color = TextSecondary,
                fontSize = Type.captionSize
            )
        }

        result?.let { AffordResult(it) }

        if (price > 0.0) {
            Spacer(Modifier.height(Space.md))
            TextButton({ partsOpen = !partsOpen }) { Text(if (partsOpen) "Сховати «частинами»" else "Купив частинами") }
            if (partsOpen) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    items(listOf(3, 4, 6, 10, 12, 24)) { count ->
                        FilterChip(parts == count, { parts = count }, { Text("$count", fontSize = Type.captionSize) })
                    }
                }
                val pay = instalmentPayFor(name.ifBlank { "Покупка частинами" }, price, parts, date, wishEmoji(name))
                Text(
                    "${pay.instalments} × ${money(pay.amount)} · перший платіж ${dayMonth(date)}, останній ${dayMonth(instalmentLast(pay))}",
                    Modifier.padding(top = Space.sm),
                    fontSize = Type.captionSize,
                    fontWeight = Type.medium
                )
                Text("Вільно по місяцях:", Modifier.padding(top = Space.xs), color = TextSecondary, fontSize = Type.captionSize)
                instalmentPreview(host.inputs, host.plan, pay).forEach { line ->
                    Text(monthFreeLine(line), color = if (line.after < 0.0) Negative else TextPrimary, fontSize = Type.captionSize, style = Tabular)
                }
                OutlinedButton(
                    {
                        host.addPay(pay)
                        host.say("Додано «${pay.name}» — ${paymentsLabel(pay.instalments)} по ${money(pay.amount)}")
                        keepBalance()
                        onClose()
                    },
                    Modifier.fillMaxWidth().padding(top = Space.sm),
                    shape = Radius.sm,
                    border = BorderStroke(1.dp, HairLine),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) { Text("Додати платіж «частинами»") }
            }
        }
    }
    if (picking) {
        val millisPerDay = 86_400_000L
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (picked ?: today).toEpochDay() * millisPerDay,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / millisPerDay >= today.toEpochDay()
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { picked = LocalDate.ofEpochDay(it / millisPerDay) }
                    picking = false
                }) { Text("Обрати") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Скасувати") } }
        ) { DatePicker(state) }
    }
}

/** What the purchase does, in the order a person asks it. */
@Composable
private fun ColumnScope.AffordResult(result: Affordability) {
    Spacer(Modifier.height(Space.lg))
    Text(
        result.headline,
        color = when (result.verdict) {
            AffordVerdict.SHORT_PAYMENTS, AffordVerdict.SHORT_LIFE -> Negative
            else -> TextPrimary
        },
        fontSize = Type.sectionSize,
        lineHeight = Type.sectionLine,
        fontWeight = Type.strong
    )
    result.basis?.let { Text(it, color = TextSecondary, fontSize = Type.captionSize) }
    result.perDay?.let { Text(it, Modifier.padding(top = Space.xs), fontSize = Type.captionSize, fontWeight = Type.medium, style = Tabular) }
    result.weather?.let { Text(it, Modifier.padding(top = Space.xs), fontSize = Type.captionSize) }
    if (result.moves.isNotEmpty()) {
        Text("Менший внесок цього місяця:", Modifier.padding(top = Space.sm), color = TextSecondary, fontSize = Type.captionSize)
        result.moves.forEach { move ->
            Text(
                "«${move.ask.name}» −${money(askRounded(move.taken))} — ${move.effect}",
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
    }
    result.treat?.let { Text(it, Modifier.padding(top = Space.xs), color = TextSecondary, fontSize = Type.captionSize) }
    if (result.levers.isNotEmpty()) {
        Text("Що допоможе:", Modifier.padding(top = Space.sm), color = TextSecondary, fontSize = Type.captionSize)
        result.levers.forEach { lever ->
            LeaderRow(lever.text, leverGain(lever))
        }
    }
}

// ------------------------------------------------------------ «Пропустити»

/** «Пропустити «Ноутбук» у жовтні?» with the price said before it is done. */
@Composable
fun SkipDialog(ask: PlanAsk, today: LocalDate, close: () -> Unit, confirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Пропустити «${ask.name}» у ${monthLocative(today.monthValue)}?") },
        text = {
            Column {
                Text(skipPrice(ask, today).replaceFirstChar { it.uppercase() } + ".", fontSize = Type.bodySize, lineHeight = Type.bodyLine)
                Text(
                    "Цього місяця план не проситиме грошей і не рахуватиметься в «Плани не сходяться». " +
                        "З 1 ${monthGenitive(today.plusMonths(1).monthValue)} повернеться сам. " +
                        if (ask.kind == PlanKind.WISH) "Бажання не ховається — ціни й сповіщення працюють." else "",
                    Modifier.padding(top = Space.sm),
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
            }
        },
        confirmButton = { Button(confirm) { Text("Пропустити") } },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

/** The row in a wish's «План накопичення»: skip this month, or take the skip back. */
@Composable
fun WishSkipRow(wish: Wish, today: LocalDate, onChange: (Wish) -> Unit) {
    val ask = wishPlanAsk(wish, today) ?: return
    Row(Modifier.fillMaxWidth().padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
        EmojiGlyph("💤", 28.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            if (ask.skipped) {
                Text(skippedLine(today), fontSize = Type.captionSize, fontWeight = Type.medium)
            } else {
                Text("Пропустити цього місяця", fontSize = Type.captionSize, fontWeight = Type.medium)
                Text(skipPrice(ask, today), color = TextSecondary, fontSize = Type.captionSize, lineHeight = Type.captionLine)
            }
        }
        TextButton({ onChange(skippedWish(wish, today, !ask.skipped)) }) {
            // White, not lime: the lime on this page is «Я купив це».
            Text(if (ask.skipped) "Повернути" else "Пропустити", color = TextPrimary)
        }
    }
}

// ------------------------------------------------------------ Фонди

/**
 * 🫙 Фонди on Платежі: every fund with its ring and its line, «✅ Відклав», and
 * the one-time offer for each annual payment that has no fund yet.
 */
@Composable
fun FundsTile(host: MoneyHost, modifier: Modifier = Modifier) {
    val pays = host.inputs.pays
    val today = host.today
    val offers = fundOffers(pays, host.plan.funds, host.declinedFunds, today, host.usdSell)
    var editing by remember { mutableStateOf<Fund?>(null) }
    var creating by remember { mutableStateOf(false) }
    var putting by remember { mutableStateOf<Fund?>(null) }
    val colour = TileMint
    val ink = inkOn(colour)
    BentoTile(colour, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiGlyph(FUND_EMOJI, 32.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Фонди", fontSize = Type.bodySize, fontWeight = Type.medium)
                TileCaption(
                    if (host.plan.funds.isEmpty()) "Відкладати потроху на річні платежі й на своє: подушка, ТО авто, подарунки"
                    else fundsSummary(host.plan),
                    colour,
                    maxLines = 3
                )
            }
            TextButton({ creating = true }) { Text("+ Фонд", color = ink) }
        }
        host.plan.funds.forEach { fund ->
            val pay = fundPay(fund, pays)
            Spacer(Modifier.height(Space.md))
            Row(
                Modifier.fillMaxWidth().clip(Radius.sm).clickable(onClickLabel = "Змінити фонд") { editing = fund },
                verticalAlignment = Alignment.CenterVertically
            ) {
                ProgressRing(
                    fundProgress(fund, pay, host.usdSell),
                    diameter = 40.dp,
                    stroke = 4.dp,
                    trackColor = ink.copy(alpha = 0.15f),
                    ringColor = ink
                ) { EmojiGlyph(fundEmoji(fund, pay), 20.dp) }
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        fund.name + if (fund.cushion && !fund.name.contains("подушк", ignoreCase = true)) " · подушка" else "",
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TileCaption(fundProgressLine(fund, pay, host.usdSell), colour, maxLines = 1)
                    TileCaption(fundPlanLine(fund, pay, today, host.usdSell), colour)
                    fundCoveredLine(fund, pay, today, host.usdSell)?.let { TileCaption(it, colour) }
                    val put = putThisMonth(fund, today)
                    if (put > 0.0) TileCaption("цього місяця відкладено ${money(put)}", colour)
                }
                Spacer(Modifier.width(Space.sm))
                InkPill("✅ Відклав", colour) { putting = fund }
            }
        }
        offers.forEach { offer ->
            Spacer(Modifier.height(Space.md))
            Column(Modifier.fillMaxWidth().clip(Radius.sm).background(ink.copy(alpha = 0.07f)).padding(Space.md)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    EmojiGlyph(shownEmoji(offer.pay), 24.dp)
                    Spacer(Modifier.width(Space.sm))
                    Text(fundOfferLine(offer), Modifier.weight(1f), fontSize = Type.captionSize, lineHeight = Type.captionLine)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.padding(top = Space.sm)) {
                    InkPill("Створити", colour) {
                        host.updateFunds { it + fundFromOffer(offer, today, "f-${System.currentTimeMillis()}") }
                        host.say("Фонд «${offer.pay.name}» створено")
                    }
                    TextButton({ host.declineFund(offer.pay.name) }) { Text("Не треба", color = softInkOn(colour)) }
                }
            }
        }
    }
    putting?.let { fund ->
        PutDialog(fund, fundAsk(fund, fundPay(fund, pays), today, host.usdSell), close = { putting = null }) { amount ->
            host.updateFunds { list -> list.map { if (it.id == fund.id) putInto(it, amount, today) else it } }
            host.say("У «${fund.name}» відкладено ${money(amount)}")
            putting = null
        }
    }
    if (creating || editing != null) {
        FundSheet(host, editing, close = { creating = false; editing = null })
    }
}

/** «✅ Відклав»: how much went in, prefilled with what the fund asks this month. */
@Composable
fun PutDialog(fund: Fund, ask: Double, close: () -> Unit, save: (Double) -> Unit) {
    var text by remember { mutableStateOf(amountText(askRounded(ask))) }
    AlertDialog(
        onDismissRequest = close,
        title = { Text("Відклав у «${fund.name}»") },
        text = {
            Column {
                Text(
                    "Скільки відкладено. FlowPay гроші не переказує — це ваш облік.",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
                NumberField("Сума, ₴", text) { text = it }
            }
        },
        confirmButton = { Button({ save(parseAmount(text)) }, enabled = parseAmount(text) > 0.0) { Text("Відклав") } },
        dismissButton = { TextButton(close) { Text("Скасувати") } }
    )
}

/** A fund made or changed: a preset or a name, a goal by sum or by date, what is in it, a skip, a delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FundSheet(host: MoneyHost, fund: Fund?, close: () -> Unit) {
    val today = host.today
    val pay = fund?.let { fundPay(it, host.inputs.pays) }
    var name by remember { mutableStateOf(fund?.name ?: "") }
    var emoji by remember { mutableStateOf(fund?.emoji ?: "") }
    var goalText by remember { mutableStateOf(amountText(fund?.goal ?: 0.0)) }
    var savedText by remember { mutableStateOf(amountText(fund?.saved ?: 0.0)) }
    var monthlyText by remember { mutableStateOf(amountText(fund?.monthly ?: 0.0)) }
    var deadline by remember { mutableLongStateOf(fund?.deadline ?: 0L) }
    var byDate by remember { mutableStateOf((fund?.deadline ?: 0L) > 0L) }
    var cushion by remember { mutableStateOf(fund?.cushion ?: false) }
    var picking by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    FormSheet(
        title = if (fund == null) "Новий фонд" else fund.name,
        confirmLabel = if (fund == null) "Створити" else "Зберегти",
        confirmEnabled = name.isNotBlank(),
        onConfirm = {
            val base = fund ?: Fund(id = "f-${System.currentTimeMillis()}", name = name.trim(), createdDay = today.toEpochDay())
            val changed = base.copy(
                name = name.trim(),
                emoji = emoji,
                goal = if (pay != null) base.goal else parseAmount(goalText),
                saved = parseAmount(savedText),
                monthly = if (pay != null || byDate) 0.0 else parseAmount(monthlyText),
                deadline = if (pay == null && byDate) deadline else 0L,
                cushion = pay == null && cushion
            )
            host.updateFunds { list ->
                val next = if (list.any { it.id == changed.id }) list.map { if (it.id == changed.id) changed else it } else list + changed
                if (changed.cushion) withCushion(next, changed.id) else next
            }
            close()
        },
        onDismiss = close
    ) {
        if (fund == null) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                items(FUND_PRESETS) { preset ->
                    FilterChip(name == preset.name, {
                        name = preset.name
                        emoji = preset.emoji
                        cushion = preset.cushion
                    }, { Text("${preset.emoji} ${preset.name}", fontSize = Type.captionSize) })
                }
            }
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().padding(top = Space.sm), label = { Text("Назва") }, singleLine = true)
        EmojiField(emoji, fundEmoji(Fund("", name), pay)) { emoji = it }
        if (pay != null) {
            Text(
                "Для платежу «${pay.name}» · ${money(chargeUah(pay, host.usdSell))} · ${annualChargeDay(pay)}. " +
                    "Скільки відкладати щомісяця, рахується від дати, як «Знаю дату» в бажанні.",
                Modifier.padding(top = Space.sm),
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        } else {
            NumberField("Ціль, ₴ (можна без цілі)", goalText) { goalText = it }
            Row(Modifier.fillMaxWidth().padding(top = Space.md), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                FilterChip(!byDate, { byDate = false; deadline = 0L }, { Text("Знаю суму", fontSize = Type.captionSize) }, Modifier.weight(1f))
                FilterChip(byDate, { byDate = true }, { Text("Знаю дату", fontSize = Type.captionSize) }, Modifier.weight(1f))
            }
            if (byDate) {
                OutlinedButton(
                    { picking = true },
                    Modifier.fillMaxWidth().padding(top = Space.sm),
                    shape = Radius.sm,
                    border = BorderStroke(1.dp, HairLine),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
                ) {
                    Icon(Icons.Default.CalendarMonth, null)
                    Text(if (deadline > 0L) "  До ${formatDate(LocalDate.ofEpochDay(deadline))}" else "  Обрати дату")
                }
            } else {
                NumberField("Відкладаю щомісяця, ₴", monthlyText) { monthlyText = it }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Space.md).clip(Radius.sm).clickable { cushion = !cushion },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Це подушка", fontSize = Type.bodySize, fontWeight = Type.medium)
                    Text(
                        "Запас на наступний місяць — на Огляді видно, скільки його вже покрито",
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                }
                Switch(cushion, { cushion = it })
            }
        }
        NumberField("Зібрано, ₴", savedText) { savedText = it }
        if (fund != null) {
            val ask = fundPlanAsk(fund, host.inputs.pays, today, host.usdSell)
            if (ask != null) {
                Row(Modifier.fillMaxWidth().padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
                    EmojiGlyph("💤", 24.dp)
                    Spacer(Modifier.width(Space.sm))
                    Column(Modifier.weight(1f)) {
                        Text(if (ask.skipped) skippedLine(today) else "Пропустити цього місяця", fontSize = Type.captionSize, fontWeight = Type.medium)
                        if (!ask.skipped) Text(skipPrice(ask, today), color = TextSecondary, fontSize = Type.captionSize)
                    }
                    TextButton({
                        host.updateFunds { list -> list.map { if (it.id == fund.id) skippedFund(it, today, !ask.skipped) else it } }
                        close()
                    }) { Text(if (ask.skipped) "Повернути" else "Пропустити") }
                }
            }
            TextButton({ deleting = true }, Modifier.padding(top = Space.sm)) { Text("Видалити фонд", color = Negative) }
        }
    }
    if (picking) {
        val millisPerDay = 86_400_000L
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (if (deadline > 0L) deadline else today.plusMonths(3).toEpochDay()) * millisPerDay,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long) = utcTimeMillis / millisPerDay > today.toEpochDay()
            }
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton({
                    state.selectedDateMillis?.let { deadline = it / millisPerDay }
                    picking = false
                }) { Text("Обрати") }
            },
            dismissButton = { TextButton({ picking = false }) { Text("Скасувати") } }
        ) { DatePicker(state) }
    }
    if (deleting && fund != null) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Видалити «${fund.name}»?") },
            text = { Text("Фонд полежить у кошику 30 днів. Гроші, які ви відклали, нікуди не дінуться — FlowPay лише забуде облік.") },
            confirmButton = {
                Button({
                    host.deleteFund(fund)
                    deleting = false
                    close()
                }) { Text("Видалити") }
            },
            dismissButton = { TextButton({ deleting = false }) { Text("Скасувати") } }
        )
    }
}

/** The thin ring on an annual payment's tile: «зібрано 2 140 з 6 400 ₴». */
@Composable
fun FundOnTile(fund: Fund, pay: Pay, usdSell: Double, colour: Color) {
    val ink = inkOn(colour)
    Row(Modifier.padding(top = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        ProgressRing(
            fundProgress(fund, pay, usdSell),
            diameter = 16.dp,
            stroke = 2.5.dp,
            trackColor = ink.copy(alpha = 0.18f),
            ringColor = ink
        )
        Spacer(Modifier.width(Space.xs))
        TileCaption("зібрано ${fundProgressLine(fund, pay, usdSell)}", colour, maxLines = 2)
    }
}

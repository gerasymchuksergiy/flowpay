package com.flowpay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * «Зарплата»: the tile on Огляд and the sheet behind it.
 *
 * Kept to three things on purpose — the owner found the first version, with a
 * month switcher, three add tiles and a bar chart, confusing («зроби зрозуміліше»):
 * this month's total, one button to write a payment down, and the list of months.
 * The arithmetic is in Salary.kt.
 */

/** The tile on Огляд: what this month has brought so far. */
@Composable
fun SalaryOverviewTile(today: LocalDate, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val context = LocalContext.current
    val version = SalaryStore.version
    val entries = remember(version) { SalaryStore(context).entries() }
    val tile = salaryTile(entries, today)
    OverviewTile(
        "Зарплата за ${monthName(today.monthValue).lowercase()}",
        if (tile.value == "—") tile.value else personalFigure(tile.value),
        personal(tile.caption),
        emoji = "💼",
        // Sand: mint is «Мені винні», which can sit right under this tile.
        colour = TileSand,
        modifier = modifier
    ) { onOpen() }
}

/** The pastel a kind wears when it is the one picked in the form. */
private fun kindColour(kind: SalaryKind): Color = when (kind) {
    SalaryKind.ADVANCE -> TileSky
    SalaryKind.SALARY -> TileMint
    SalaryKind.OTHER -> TileLavender
}

/** The sheet: the log, and the form for one payment in its place while that is open. */
@Composable
fun SalarySheet(
    payday: Payday,
    /** Hryvnias for a dollar today — what a new payment's rate starts at. Nought when none has loaded. */
    todayRate: Double,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val store = remember { SalaryStore(context) }
    val version = SalaryStore.version
    val entries = remember(version) { store.entries() }
    val today = remember { LocalDate.now() }
    // Null: the log. Otherwise the payment being written — new, or one being changed.
    var editing by remember { mutableStateOf<SalaryEntry?>(null) }
    var isNew by remember { mutableStateOf(false) }
    // «Вставити списком»: the history, many payments at once.
    var pasting by remember { mutableStateOf(false) }
    var said by remember { mutableStateOf<String?>(null) }

    if (pasting) {
        SalaryPasteForm(
            entries = entries,
            onAdd = { added ->
                store.save(entries + added)
                said = "Додано виплат: ${added.size}"
                pasting = false
            },
            onBack = { pasting = false }
        )
        return
    }

    val current = editing
    if (current != null) {
        SalaryEntryForm(
            start = current,
            isNew = isNew,
            entries = entries,
            onSave = { saved ->
                store.save(entries.filterNot { it.id == saved.id } + saved)
                editing = null
            },
            onDelete = {
                store.save(entries.filterNot { it.id == current.id })
                editing = null
            },
            onBack = { editing = null }
        )
        return
    }

    val thisMonth = monthKey(today)
    val months = salaryMonths(entries)
    val now = months.firstOrNull { it.month == thisMonth }

    FormSheet(
        title = "Зарплата",
        confirmLabel = "Готово",
        confirmEnabled = true,
        onConfirm = onClose,
        onDismiss = onClose
    ) {
        // 1. This month.
        HeroPanel(
            label = "Отримано за ${monthName(today.monthValue).lowercase()}",
            // In hryvnias, which the pay is agreed in; the dollars that came, under it.
            value = personalFigure(now?.let { salaryMainFigure(it) } ?: money(0.0)),
            caption = personal(
                now?.let { listOfNotNull(salaryDollarLine(it), salaryBreakdown(it)).joinToString("\n") }
                    ?: "Ще нічого не записано"
            ),
            muted = now == null,
            emoji = "💼"
        )

        // 2. The one way in.
        Button(
            onClick = {
                isNew = true
                editing = SalaryEntry(
                    month = thisMonth,
                    kind = suggestedKind(entries, thisMonth, payday),
                    amount = 0.0,
                    day = today.toEpochDay(),
                    rate = todayRate
                )
            },
            modifier = Modifier.fillMaxWidth().padding(top = Space.md).height(Space.touchRow),
            shape = Radius.pill,
            colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = TextPrimary)
        ) {
            Icon(Icons.Default.Add, null, Modifier.size(20.dp))
            Spacer(Modifier.width(Space.sm))
            Text("Записати виплату", fontWeight = Type.medium)
        }
        TextButton({ pasting = true }, Modifier.fillMaxWidth()) {
            Text("Вставити кілька виплат списком", color = TextSecondary)
        }
        said?.let {
            Text(it, color = TextPrimary, fontSize = Type.captionSize, modifier = Modifier.padding(bottom = Space.xs))
        }

        // 3. Every month, newest first.
        if (months.isEmpty()) {
            Spacer(Modifier.height(Space.lg))
            EmptyInvite(
                "Тут буде список по місяцях",
                "Прийшов аванс чи зарплата — натисніть «Записати виплату» і вкажіть суму."
            )
        } else {
            Row(Modifier.padding(top = Space.xl), verticalAlignment = Alignment.CenterVertically) {
                Text("По місяцях", Modifier.weight(1f), fontSize = Type.bodySize, fontWeight = Type.medium)
                val year = salaryYear(entries, today.year)
                if (year.months > 0) {
                    Text(
                        personal("за ${year.year} рік ${if (year.uahKnown) money(year.uah) else dollars(year.total)}"),
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        style = Tabular
                    )
                }
            }
            months.forEach { month ->
                SalaryMonthCard(month) { entry ->
                    isNew = false
                    editing = entry
                }
            }
            Text(
                "Натисніть на виплату, щоб змінити або видалити її.",
                Modifier.padding(top = Space.sm),
                color = TextDisabled,
                fontSize = Type.captionSize
            )
        }
    }
}

/** One month: name and total on one line, then each payment on its own. */
@Composable
private fun SalaryMonthCard(month: SalaryMonth, onEdit: (SalaryEntry) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = Space.sm).litEdge(Radius.md),
        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
        shape = Radius.md
    ) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    monthTitle(month.month),
                    Modifier.weight(1f),
                    fontSize = Type.cardTitleSize,
                    fontWeight = Type.medium
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        personalFigure(salaryMainFigure(month)),
                        fontSize = Type.cardTitleSize,
                        fontWeight = FontWeight.Bold,
                        style = Tabular
                    )
                    if (month.uahKnown) {
                        Text(personal(dollars(month.total)), color = TextSecondary, fontSize = Type.captionSize, style = Tabular)
                    }
                }
            }
            Spacer(Modifier.height(Space.xs))
            month.entries.forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button, onClickLabel = "Змінити") { onEdit(entry) }
                        .padding(vertical = Space.xs),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EmojiGlyph(entry.kind.emoji, 20.dp)
                    Spacer(Modifier.width(Space.sm))
                    Text(
                        // «Зарплата · 580 $ × 41,50» — the dollars and the rate that made the hryvnias.
                        personal(
                            listOfNotNull(
                                entry.kind.label,
                                entry.note.takeIf { it.isNotBlank() },
                                if (entry.rate > 0.0) "${dollars(entry.amount)} × ${figure(entry.rate, 2)}" else null
                            ).joinToString(" · ")
                        ),
                        Modifier.weight(1f).padding(end = Space.sm),
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        personalFigure(if (entry.rate > 0.0) money(entry.uah) else dollars(entry.amount)),
                        fontSize = Type.captionSize,
                        style = Tabular
                    )
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(16.dp), tint = TextDisabled)
                }
            }
        }
    }
}

/** «‹ Листопад 2026 ›» — in the form, for a payment that belongs to another month. */
@Composable
private fun MonthStepper(month: String, onChange: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SurfaceRaised, Radius.pill),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton({ onChange(shiftMonthKey(month, -1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Попередній місяць", tint = TextPrimary)
        }
        Text(
            monthTitle(month),
            Modifier.weight(1f),
            fontSize = Type.bodySize,
            fontWeight = Type.medium,
            textAlign = TextAlign.Center
        )
        IconButton({ onChange(shiftMonthKey(month, 1)) }) {
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Наступний місяць", tint = TextPrimary)
        }
    }
}

/** Writing one payment down: the month it is for, what it was, how much, and a note. */
@Composable
private fun SalaryEntryForm(
    start: SalaryEntry,
    isNew: Boolean,
    entries: List<SalaryEntry>,
    onSave: (SalaryEntry) -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit
) {
    var month by remember { mutableStateOf(start.month) }
    var kind by remember { mutableStateOf(start.kind) }
    var amount by remember { mutableStateOf(amountText(start.amount)) }
    var note by remember { mutableStateOf(start.note) }
    var rateText by remember { mutableStateOf(amountText(start.rate)) }
    var confirmDelete by remember { mutableStateOf(false) }
    val value = parseAmount(amount)
    val rate = parseAmount(rateText)

    FormSheet(
        title = if (isNew) "Нова виплата" else "Виплата",
        confirmLabel = if (isNew) "Записати" else "Зберегти",
        confirmEnabled = value > 0.0,
        onConfirm = { onSave(start.copy(month = month, kind = kind, amount = value, note = note.trim(), rate = rate)) },
        onDismiss = onBack
    ) {
        Text("Що прийшло", color = TextSecondary, fontSize = Type.captionSize)
        Spacer(Modifier.height(Space.xs))
        Row(
            Modifier.height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(Space.sm)
        ) {
            SalaryKind.entries.forEach { option ->
                val chosen = option == kind
                val colour = if (chosen) kindColour(option) else SurfaceRaised
                BentoTile(
                    colour,
                    Modifier.weight(1f).fillMaxHeight(),
                    onClick = { kind = option },
                    onClickLabel = option.label
                ) {
                    EmojiGlyph(option.emoji, 24.dp)
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        option.label,
                        color = inkOn(colour),
                        fontSize = Type.captionSize,
                        fontWeight = if (chosen) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1
                    )
                }
            }
        }

        // The amount right under the kind: it is what the form is for.
        NumberField("Сума, $", amount) { amount = it }
        // The same figure as last time is the usual case: one tap instead of typing it.
        lastAmount(entries.filterNot { it.id == start.id }, kind)?.takeIf { it != value }?.let { last ->
            OutlinedButton(
                onClick = { amount = amountText(last) },
                modifier = Modifier.padding(top = Space.sm),
                shape = Radius.pill,
                border = BorderStroke(1.dp, HairLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text(personal("Як минулого разу · ${dollars(last)}")) }
        }

        // The rate of the day the money came: the hryvnias the pay was agreed in.
        NumberField("Курс, ₴ за \$1", rateText) { rateText = it }
        Text(
            when {
                value > 0.0 && rate > 0.0 -> personal("= ${money(value * rate)}")
                rate <= 0.0 -> "Без курсу сума буде лише в доларах"
                else -> "Курс підставлено сьогоднішній — поправте, якщо рахували за іншим"
            },
            Modifier.padding(top = Space.xs),
            color = if (value > 0.0 && rate > 0.0) TextPrimary else TextSecondary,
            fontSize = if (value > 0.0 && rate > 0.0) Type.bodySize else Type.captionSize,
            fontWeight = if (value > 0.0 && rate > 0.0) Type.medium else FontWeight.Normal,
            style = Tabular
        )

        Spacer(Modifier.height(Space.md))
        Text("За який місяць", color = TextSecondary, fontSize = Type.captionSize)
        Spacer(Modifier.height(Space.xs))
        MonthStepper(month) { month = it }
        OutlinedTextField(
            note,
            { note = it.take(60) },
            Modifier.fillMaxWidth().padding(top = Space.md),
            label = { Text("Примітка, необов'язково") },
            placeholder = { Text("премія, відпускні…") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        )
        if (!isNew) {
            TextButton({ confirmDelete = true }, Modifier.padding(top = Space.sm)) {
                Text("Видалити цю виплату", color = Negative)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Видалити виплату?") },
            text = { Text(personal("${start.kind.label} ${dollars(start.amount)}${if (start.rate > 0.0) " (${money(start.uah)})" else ""} за ${monthTitle(start.month).lowercase()} зникне зі списку.")) },
            confirmButton = {
                TextButton({
                    confirmDelete = false
                    onDelete()
                }) { Text("Видалити", color = Negative) }
            },
            dismissButton = { TextButton({ confirmDelete = false }) { Text("Скасувати") } }
        )
    }
}

/** An example the paste field shows until something is typed. */
private const val PASTE_EXAMPLE = "16.09.2026 аванс 500\$ курс 41,2\n03.10.2026 зарплата 520\$ курс 41,3 за 09.2026"

/**
 * «Вставити списком»: a whole history at once, one payment a line. What was read
 * is said back — how many, the total, and any line not understood — before
 * anything is added, and payments already in the log are not added twice.
 */
@Composable
private fun SalaryPasteForm(
    entries: List<SalaryEntry>,
    onAdd: (List<SalaryEntry>) -> Unit,
    onBack: () -> Unit
) {
    var text by remember { mutableStateOf("") }
    val paste = remember(text) { parseSalaryLines(text) }
    val fresh = remember(paste, entries) { newPayments(entries, paste.entries) }
    val repeated = paste.entries.size - fresh.size

    FormSheet(
        title = "Вставити списком",
        confirmLabel = if (fresh.isEmpty()) "Додати" else "Додати ${fresh.size}",
        confirmEnabled = fresh.isNotEmpty(),
        onConfirm = { onAdd(fresh) },
        onDismiss = onBack
    ) {
        Text(
            "Одна виплата — один рядок: дата, аванс чи зарплата, сума в доларах, курс. " +
                "«за ММ.РРРР» — якщо виплата за інший місяць.",
            color = TextSecondary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine
        )
        OutlinedTextField(
            text,
            { text = it },
            Modifier.fillMaxWidth().padding(top = Space.md).height(180.dp),
            placeholder = { Text(PASTE_EXAMPLE, color = TextDisabled, fontSize = Type.captionSize) },
            textStyle = Tabular.copy(fontSize = Type.captionSize)
        )
        if (paste.entries.isNotEmpty()) {
            val months = salaryMonths(fresh)
            Text(
                personal(
                    "Знайдено: ${paste.entries.size}" +
                        (if (repeated > 0) " · уже є в списку: $repeated" else "") +
                        (if (fresh.isNotEmpty()) " · нових на " + (
                            if (fresh.all { it.rate > 0.0 }) "${money(fresh.sumOf { it.uah })} (${dollars(fresh.sumOf { it.amount })})"
                            else dollars(fresh.sumOf { it.amount })
                        ) else "")
                ),
                Modifier.padding(top = Space.md),
                fontSize = Type.bodySize,
                fontWeight = Type.medium
            )
            months.forEach { month ->
                Text(
                    personal("${monthTitle(month.month)} — ${salaryMainFigure(month)} · ${salaryBreakdown(month)}"),
                    Modifier.padding(top = Space.xs),
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
            }
        }
        if (paste.unreadable.isNotEmpty()) {
            Text(
                "Не зрозумів ${if (paste.unreadable.size == 1) "рядок" else "рядки"}:\n" + paste.unreadable.joinToString("\n"),
                Modifier.padding(top = Space.md),
                color = Negative,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
    }
}

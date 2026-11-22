package com.flowpay.app

import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
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
 * «Зарплата»: the third view of Платежі.
 *
 * Kept to three things on purpose — the owner found the first version, with a
 * month switcher, three add tiles and a bar chart, confusing («зроби зрозуміліше»):
 * this month's total, one button to write a payment down, and the list of months.
 * The arithmetic is in Salary.kt.
 */

/** The pastel a kind wears when it is the one picked in the form. */
private fun kindColour(kind: SalaryKind): Color = when (kind) {
    SalaryKind.ADVANCE -> TileSky
    SalaryKind.SALARY -> TileMint
    SalaryKind.OTHER -> TileLavender
}

/**
 * «Зарплата» as the third view of Платежі (owner's call, 22 November 2026 — it
 * first opened as a sheet from a tile on Огляд). The log on the page; the form for
 * one payment and the paste form rise over it as sheets.
 */
@Composable
fun SalaryPanel(
    payday: Payday,
    /** Hryvnias for a dollar today — what a new payment's rate starts at. Nought when none has loaded. */
    todayRate: Double,
    /** The header's «+» on Платежі while this view shows: write a payment down. */
    adding: Boolean,
    setAdding: (Boolean) -> Unit,
    modifier: Modifier = Modifier
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

    val thisMonth = monthKey(today)
    fun startNew() {
        isNew = true
        editing = SalaryEntry(
            month = thisMonth,
            kind = suggestedKind(entries, thisMonth, payday),
            amount = 0.0,
            day = today.toEpochDay(),
            rate = todayRate
        )
    }
    LaunchedEffect(adding) {
        if (adding) {
            if (editing == null) startNew()
            setAdding(false)
        }
    }

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
    }

    val months = salaryMonths(entries)
    val now = months.firstOrNull { it.month == thisMonth }
    val previous = months.firstOrNull { it.month == shiftMonthKey(thisMonth, -1) }
    val year = salaryYear(entries, today.year)
    // The tallest month, for the thin bar under each month's figure.
    val tallest = months.maxOfOrNull { if (it.uahKnown) it.uah else it.total } ?: 0.0

    Column(modifier) {
        // 1. This month, in the one lime panel — with where each kind stands inside it.
        HeroPanel(
            label = "Отримано за ${monthName(today.monthValue).lowercase()}",
            // In hryvnias, which the pay is agreed in; the dollars that came, under it.
            value = personalFigure(now?.let { salaryMainFigure(it) } ?: money(0.0)),
            caption = personal(now?.let { salaryDollarLine(it) } ?: "Ще нічого не записано"),
            muted = now == null,
            emoji = "💼",
            footer = {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    listOf(SalaryKind.ADVANCE, SalaryKind.SALARY).forEach { kind ->
                        val got = now?.entries?.filter { it.kind == kind }.orEmpty()
                        KindChip(
                            kind,
                            when {
                                got.isEmpty() -> "ще ні"
                                got.all { it.rate > 0.0 } -> personal(money(got.sumOf { it.uah }))
                                else -> personal(dollars(got.sumOf { it.amount }))
                            },
                            done = got.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        )

        // 2. The year and the month before, as two tiles — the same pair Розклад has.
        if (months.isNotEmpty()) {
            Spacer(Modifier.height(Space.md))
            Row(
                Modifier.height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(Space.md)
            ) {
                FigureTile(
                    "За ${year.year} рік",
                    personalFigure(if (year.uahKnown) money(year.uah) else dollars(year.total)),
                    when (year.months) {
                        0 -> "ще нічого"
                        1 -> "1 місяць"
                        else -> personal(
                            "≈ ${if (year.uahKnown) money(kotlin.math.round(year.uah / year.months)) else dollars(kotlin.math.round(year.average))} на місяць"
                        )
                    },
                    "📅",
                    TileSky,
                    Modifier.weight(1f).fillMaxHeight()
                )
                FigureTile(
                    previous?.let { "За ${monthName(monthKeyDate(it.month)!!.monthValue).lowercase()}" } ?: "Минулий місяць",
                    previous?.let { personalFigure(salaryMainFigure(it)) } ?: "—",
                    previous?.let { p -> salaryDollarLine(p)?.let { personal(it) } } ?: "не записано",
                    "🗓️",
                    TileLavender,
                    Modifier.weight(1f).fillMaxHeight()
                )
            }
        }

        // 3. The ways in: one payment, or a whole history.
        Spacer(Modifier.height(Space.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Button(
                onClick = { startNew() },
                modifier = Modifier.weight(1f).height(Space.touchRow),
                shape = Radius.pill,
                colors = ButtonDefaults.buttonColors(containerColor = SurfaceRaised, contentColor = TextPrimary)
            ) {
                Icon(Icons.Default.Add, null, Modifier.size(20.dp))
                Spacer(Modifier.width(Space.sm))
                Text("Записати виплату", fontWeight = Type.medium, maxLines = 1)
            }
            OutlinedButton(
                onClick = { pasting = true },
                modifier = Modifier.height(Space.touchRow),
                shape = Radius.pill,
                border = BorderStroke(1.dp, HairLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text("Списком", maxLines = 1) }
        }
        said?.let {
            Text(it, Modifier.padding(top = Space.sm), color = TextSecondary, fontSize = Type.captionSize)
        }

        // 4. Every month, newest first.
        if (months.isEmpty()) {
            Card(
                Modifier.fillMaxWidth().padding(top = Space.lg).litEdge(Radius.md),
                colors = CardDefaults.cardColors(containerColor = SurfaceBase),
                shape = Radius.md
            ) {
                Row(Modifier.padding(Space.lg), verticalAlignment = Alignment.CenterVertically) {
                    EmojiGlyph("🗂️", 36.dp)
                    Spacer(Modifier.width(Space.md))
                    Column(Modifier.weight(1f)) {
                        Text("Тут буде список по місяцях", fontSize = Type.cardTitleSize, fontWeight = Type.medium)
                        Text(
                            "Прийшов аванс чи зарплата — «Записати виплату». Кілька старих одразу — «Списком».",
                            color = TextSecondary,
                            fontSize = Type.captionSize,
                            lineHeight = Type.captionLine
                        )
                    }
                }
            }
        } else {
            Text(
                "По місяцях",
                Modifier.padding(top = Space.xl, bottom = Space.xs),
                fontSize = Type.bodySize,
                fontWeight = Type.medium
            )
            months.forEach { month ->
                val size = if (month.uahKnown) month.uah else month.total
                SalaryMonthCard(month, if (tallest > 0.0) (size / tallest).toFloat() else 0f) { entry ->
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

/** «💵 Аванс · 24 720 ₴» or «💼 Зарплата · ще ні», on the lime panel. */
@Composable
private fun KindChip(kind: SalaryKind, value: String, done: Boolean, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(AccentInk.copy(alpha = if (done) 0.12f else 0.06f), Radius.pill)
            .padding(horizontal = Space.md, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EmojiGlyph(kind.emoji, 18.dp)
        Spacer(Modifier.width(Space.xs))
        Column {
            Text(kind.label, color = AccentInk.copy(alpha = 0.65f), fontSize = 11.sp, maxLines = 1)
            Text(
                value,
                color = AccentInk.copy(alpha = if (done) 1f else 0.5f),
                fontSize = Type.captionSize,
                fontWeight = Type.strong,
                maxLines = 1,
                style = Tabular
            )
        }
    }
}

/** A pastel tile with one figure: the year, the month before. */
@Composable
private fun FigureTile(label: String, value: String, caption: String, emoji: String, colour: Color, modifier: Modifier) {
    BentoTile(colour, modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), color = softInkOn(colour), fontSize = Type.captionSize, maxLines = 1)
            EmojiGlyph(emoji, 24.dp)
        }
        Spacer(Modifier.height(Space.xs))
        SplitFigure(value, 20.sp, colour = inkOn(colour))
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(Space.sm))
        TileCaption(caption, colour)
    }
}

/**
 * One month: its name, its figure in the display face with the dollars beside it,
 * a thin bar against the best month, then each payment — tap one to change it.
 */
@Composable
private fun SalaryMonthCard(month: SalaryMonth, share: Float, onEdit: (SalaryEntry) -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(top = Space.sm).litEdge(Radius.md),
        colors = CardDefaults.cardColors(containerColor = SurfaceBase),
        shape = Radius.md
    ) {
        Column(Modifier.padding(horizontal = Space.lg, vertical = Space.md)) {
            Text(monthTitle(month.month), color = TextSecondary, fontSize = Type.captionSize)
            Row(verticalAlignment = Alignment.Bottom) {
                SplitFigure(personalFigure(salaryMainFigure(month)), 22.sp, Modifier.weight(1f), colour = TextPrimary)
                if (month.uahKnown) {
                    Text(
                        personal(dollars(month.total)),
                        Modifier.padding(bottom = 2.dp),
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        style = Tabular
                    )
                }
            }
            // How this month stands against the best one — a glance, not a chart.
            Box(
                Modifier
                    .padding(top = Space.sm)
                    .fillMaxWidth()
                    .height(4.dp)
                    .background(HairLine, Radius.pill)
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(share.coerceIn(0.02f, 1f))
                        .height(4.dp)
                        .background(TileMint, Radius.pill)
                )
            }
            Spacer(Modifier.height(Space.sm))
            month.entries.forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Radius.sm)
                        .clickable(role = Role.Button, onClickLabel = "Змінити") { onEdit(entry) }
                        .padding(vertical = Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier.size(32.dp).background(SurfaceRaised, Radius.pill),
                        contentAlignment = Alignment.Center
                    ) { EmojiGlyph(entry.kind.emoji, 18.dp) }
                    Spacer(Modifier.width(Space.md))
                    Column(Modifier.weight(1f).padding(end = Space.sm)) {
                        Text(
                            listOfNotNull(entry.kind.label, entry.note.takeIf { it.isNotBlank() }).joinToString(" · "),
                            fontSize = Type.bodySize,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            personal(
                                listOfNotNull(
                                    // «16.11» rather than «16 листопада»: the rate has to fit beside it.
                                    LocalDate.ofEpochDay(entry.day).let { d ->
                                        String.format(java.util.Locale.ROOT, "%02d.%02d", d.dayOfMonth, d.monthValue)
                                    }.takeIf { entry.day > 0 },
                                    if (entry.rate > 0.0) "${dollars(entry.amount)} × ${figure(entry.rate, 2)}" else null
                                ).joinToString(" · ")
                            ),
                            color = TextSecondary,
                            fontSize = Type.captionSize,
                            maxLines = 1
                        )
                    }
                    Text(
                        personalFigure(if (entry.rate > 0.0) money(entry.uah) else dollars(entry.amount)),
                        fontSize = Type.bodySize,
                        fontWeight = Type.medium,
                        style = Tabular
                    )
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp), tint = TextDisabled)
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

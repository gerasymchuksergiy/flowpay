package com.flowpay.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** The monobank screens: connecting, the questions on Платежі, the jar on a wish. */

/** What Платежі shows from the statement: questions to answer, subscriptions found. */
data class MonoView(
    val matches: List<MonoMatch>,
    val found: List<FoundSubscription>,
    /** Confirmed payments the bank now charges differently for. */
    val drifts: List<Pair<Pay, Double>> = emptyList()
)

// ------------------------------------------------------------ settings

/** The row under Налаштування: connected or not, and when it last looked. */
@Composable
fun MonoSettingsItem(onOpen: () -> Unit) {
    val context = LocalContext.current
    val version = MonoStore.version
    val mono = remember { MonoStore(context) }
    val (connected, line, alarm) = remember(version) {
        val client = mono.client()
        val error = mono.lastError()
        val left = mono.loadingLeft()
        when {
            !mono.connected() -> Triple(false, "Сам бачитиме, що оплачено, і знайде забуті підписки. Потрібен ваш токен", false)
            left > 0 -> Triple(true, loadingLine(left), false)
            error.isNotBlank() -> Triple(true, error, true)
            mono.lastSync() == 0L -> Triple(true, "Підключено. Перша виписка завантажується — кілька хвилин", false)
            else -> Triple(
                true,
                listOfNotNull(
                    "Оновлено ${timeLabel(mono.lastSync())}",
                    client?.let { "на картці ${approxMoney(ownUah(it, mono.accountsToRead(it)))}" }
                ).joinToString(" · "),
                false
            )
        }
    }
    ListItem(
        leadingContent = { EmojiGlyph("🏦", 28.dp) },
        headlineContent = { Text("monobank", fontWeight = FontWeight.Bold) },
        supportingContent = { Text(line, color = if (alarm) Negative else TextSecondary) },
        trailingContent = {
            OutlinedButton(
                onClick = onOpen,
                shape = Radius.sm,
                border = BorderStroke(1.dp, HairLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text(if (connected) "Налаштувати" else "Підключити") }
        }
    )
}

/**
 * Connecting with a token, or — once connected — which cards to read, whether a
 * confirmed merchant ticks by itself, a pass now, and disconnecting.
 */
@Composable
fun MonoSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = MonoStore.version
    val mono = remember { MonoStore(context) }
    val connected = remember(version) { mono.connected() }
    val client = remember(version) { mono.client() }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmOff by remember { mutableStateOf(false) }
    FormSheet(
        title = "monobank",
        confirmLabel = if (connected) "Готово" else "Підключити",
        confirmEnabled = connected || (token.trim().length >= 20 && !busy),
        onConfirm = {
            if (connected) {
                onClose()
            } else {
                scope.launch {
                    busy = true
                    problem = null
                    runCatching { MonoSync.connect(context, token) }
                        .onSuccess {
                            token = ""
                            note = "Підключено: ${it.name}. Перша виписка за три місяці завантажується у фоні — monobank дозволяє один запит на хвилину, тож це близько трьох хвилин на кожну картку."
                        }
                        .onFailure { problem = (it as? MonoApiError)?.message ?: "Не вдалося з'єднатися з monobank. Перевірте інтернет і токен." }
                    busy = false
                }
            }
        },
        onDismiss = onClose
    ) {
        if (!connected) {
            Text(
                "FlowPay читатиме виписку вашої картки, щоб сам ставити «сплачено», знаходити забуті підписки й показувати баланс. " +
                    "Токен дає лише читання: ним не можна нічого оплатити чи переказати.",
                fontSize = Type.bodySize,
                lineHeight = Type.bodyLine
            )
            Text(
                "1. Відкрийте api.monobank.ua і підтвердіть вхід у застосунку monobank.\n" +
                    "2. Скопіюйте токен — довгий рядок літер і цифр.\n" +
                    "3. Вставте його нижче.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
            TextButton({ openLink(context, "https://api.monobank.ua/") }) { Text("Відкрити api.monobank.ua") }
            OutlinedTextField(
                token,
                { token = it },
                Modifier.fillMaxWidth(),
                label = { Text("Токен monobank") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )
            Text(
                "Токен зберігається лише на цьому телефоні, зашифрований ключем, який не можна з нього забрати. У резервні копії він не потрапляє.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
            if (busy) BusyMark()
        } else {
            client?.let { info ->
                Text(info.name, fontSize = Type.cardTitleSize, fontWeight = Type.medium)
                Text("Які картки читати", color = TextSecondary, fontSize = Type.captionSize)
                val reading = mono.accountsToRead(info)
                info.accounts.forEach { account ->
                    val on = account.id in reading
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(Radius.sm)
                            .clickable {
                                val next = if (on) reading - account.id else reading + account.id
                                mono.saveChosen(next)
                                MonoStore.bump()
                            }
                            .padding(vertical = Space.xs),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(on, null)
                        Spacer(Modifier.width(Space.sm))
                        Column(Modifier.weight(1f)) {
                            Text("${account.label} · ${currencyLabel(account.currencyCode)}", fontSize = Type.bodySize)
                            Text(
                                "${account.type} · ${amountLabelMinor(account.own, account.currencyCode)}",
                                color = TextSecondary,
                                fontSize = Type.captionSize
                            )
                        }
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = Space.sm),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f).padding(end = Space.md)) {
                    Text("Ставити «сплачено» самостійно", fontSize = Type.bodySize, fontWeight = Type.medium)
                    Text(
                        "Лише для списань, які ви вже раз підтвердили для цього платежу. Решту FlowPay спершу спитає.",
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                }
                Switch(mono.auto(), { mono.saveAuto(it); MonoStore.bump() })
            }
            val left = remember(version) { mono.loadingLeft() }
            val error = remember(version) { mono.lastError() }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                TextButton({
                    MonoSync.runNow(context)
                    // While a load runs, its own line already says how long is left.
                    note = if (left > 0) null else "Оновлюю у фоні — кілька хвилин: monobank дозволяє один запит на хвилину."
                }) { Text("Оновити зараз") }
                TextButton({ confirmOff = true }) { Text("Відключити", color = Negative) }
            }
            when {
                left > 0 -> Text(loadingLine(left), color = TextSecondary, fontSize = Type.captionSize, lineHeight = Type.captionLine)
                error.isNotBlank() -> Text(error, color = Negative, fontSize = Type.captionSize, lineHeight = Type.captionLine)
            }
        }
        problem?.let { Text(it, color = Negative, fontSize = Type.captionSize, lineHeight = Type.captionLine) }
        note?.let { Text(it, color = TextSecondary, fontSize = Type.captionSize, lineHeight = Type.captionLine) }
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Відключити monobank?") },
            text = { Text("Токен, виписка й прив'язки банок зникнуть із телефона. Позначки «сплачено» лишаться.") },
            confirmButton = {
                TextButton({
                    MonoSync.disconnect(context)
                    confirmOff = false
                    onClose()
                }) { Text("Відключити", color = Negative) }
            },
            dismissButton = { TextButton({ confirmOff = false }) { Text("Скасувати") } }
        )
    }
}

/** «UAH», «USD», «EUR», or the code. */
fun currencyLabel(code: Int): String = when (code) {
    UAH_CODE -> "UAH"
    USD_CODE -> "USD"
    EUR_CODE -> "EUR"
    else -> code.toString()
}

/** A minor-unit amount in its own currency. */
fun amountLabelMinor(minor: Long, code: Int): String = when (code) {
    UAH_CODE -> money(minor / 100.0)
    USD_CODE -> dollars(minor / 100.0)
    else -> "${bareAmount(minor / 100.0)} ${currencyLabel(code)}"
}

// ------------------------------------------------------------ Платежі

/**
 * «Це Netflix?» — one row per charge that looks like a payment, with the bank's
 * own description, what it took and when. «Так» ticks the month at the real
 * amount and remembers the merchant; «Ні» is never asked again.
 */
@Composable
fun MonoMatchesTile(
    matches: List<MonoMatch>,
    modifier: Modifier = Modifier,
    onYes: (MonoMatch) -> Unit,
    onNo: (MonoMatch) -> Unit
) {
    BentoTile(TileMint, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "monobank: схоже, це оплати",
                Modifier.weight(1f),
                color = TileInkSoft,
                fontSize = Type.captionSize,
                fontWeight = Type.medium
            )
            EmojiGlyph("🏦", 24.dp)
        }
        matches.forEach { match ->
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(shownEmoji(match.pay), 30.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (match.kind == MonoMatchKind.AMOUNT_ONLY) "Можливо, ${match.pay.name}?" else "${match.pay.name}?",
                        fontSize = Type.bodySize,
                        fontWeight = Type.medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    TileCaption(matchLine(match), TileMint, maxLines = 3)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onNo(match) }) { Text("Ні", color = TileInk) }
                Spacer(Modifier.width(Space.sm))
                InkPill("Так, сплачено") { onYes(match) }
            }
        }
    }
}

/** «NETFLIX.COM · 329 ₴ · 28 жовтня · у FlowPay 299 ₴» */
fun matchLine(match: MonoMatch): String {
    val day = Instant.ofEpochSecond(match.tx.time).atZone(ZoneId.systemDefault()).toLocalDate()
    return listOfNotNull(
        match.tx.description,
        amountLabel(kotlin.math.round(match.charged * 100) / 100.0, match.pay.currency),
        dayMonth(day),
        "у FlowPay ${amountLabel(match.pay.amount, match.pay.currency)}".takeIf { match.drifted },
        "ще в обробці".takeIf { match.tx.hold }
    ).joinToString(" · ")
}

/** «Netflix тепер 329 ₴» — a confirmed payment the bank charged differently, with «Оновити суму». */
@Composable
fun MonoDriftsTile(drifts: List<Pair<Pay, Double>>, modifier: Modifier = Modifier, onUpdate: (Pay, Double) -> Unit) {
    BentoTile(TilePeach, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Ціна змінилась — так списав monobank",
                Modifier.weight(1f),
                color = TileInkSoft,
                fontSize = Type.captionSize,
                fontWeight = Type.medium
            )
            EmojiGlyph("📈", 24.dp)
        }
        drifts.forEach { (pay, charged) ->
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(shownEmoji(pay), 30.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(pay.name, fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TileCaption(
                        "у FlowPay ${amountLabel(pay.amount, pay.currency)} → списано ${amountLabel(charged, pay.currency)}",
                        TilePeach
                    )
                }
                InkPill("Оновити") { onUpdate(pay, charged) }
            }
        }
    }
}

/** Charges that come back every month and are not on the list. */
@Composable
fun FoundSubscriptionsTile(
    found: List<FoundSubscription>,
    modifier: Modifier = Modifier,
    onAdd: (FoundSubscription) -> Unit,
    onIgnore: (FoundSubscription) -> Unit
) {
    BentoTile(TileLavender, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Регулярні списання, яких немає в списку",
                Modifier.weight(1f),
                color = TileInkSoft,
                fontSize = Type.captionSize,
                fontWeight = Type.medium
            )
            EmojiGlyph("🔎", 24.dp)
        }
        found.forEach { item ->
            Spacer(Modifier.height(Space.sm))
            Row(verticalAlignment = Alignment.CenterVertically) {
                EmojiGlyph(payEmoji(item.title), 30.dp)
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(prettyMerchant(item.title), fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    TileCaption(
                        "${amountLabel(item.amount, item.currency)} щомісяця, близько ${item.day} числа · списань поспіль: ${item.times}",
                        TileLavender
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton({ onIgnore(item) }) { Text("Не підписка", color = TileInk) }
                Spacer(Modifier.width(Space.sm))
                InkPill("Додати") { onAdd(item) }
            }
        }
    }
}

/** A dark pill button on a pastel tile. */
@Composable
fun InkPill(label: String, onClick: () -> Unit) {
    Text(
        label,
        Modifier
            .clip(Radius.pill)
            .background(TileInk)
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = Space.lg, vertical = Space.sm),
        color = Accent,
        fontSize = Type.captionSize,
        fontWeight = Type.strong
    )
}

// ------------------------------------------------------------ a wish's jar

/**
 * The monobank jar a wish is saved in. Linked, «Вже відкладено» follows the jar
 * on every pass; unlinked, it offers the owner's jars to choose from.
 */
@Composable
fun JarLink(wish: Wish, onLink: (jar: String, saved: Double?) -> Unit, onUnlink: () -> Unit) {
    val context = LocalContext.current
    val version = MonoStore.version
    val client = remember(version) { MonoStore(context).let { if (it.connected()) it.client() else null } } ?: return
    if (client.jars.isEmpty()) return
    var picking by remember { mutableStateOf(false) }
    val linked = client.jars.firstOrNull { it.id == wish.jar }
    Row(
        Modifier.fillMaxWidth().padding(top = Space.md).clip(Radius.sm).clickable { picking = true }.padding(vertical = Space.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EmojiGlyph("🫙", 32.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                if (linked != null) "Банка «${linked.title}»" else "Відкладаєте в банку monobank?",
                fontSize = Type.bodySize,
                fontWeight = Type.medium
            )
            Text(
                if (linked != null) {
                    "${amountLabelMinor(linked.balance, linked.currencyCode)}" +
                        (if (linked.goal > 0) " з ${amountLabelMinor(linked.goal, linked.currencyCode)}" else "") +
                        " · «Вже відкладено» береться з банки"
                } else {
                    "Прив'яжіть — і відкладене братиметься з банки само"
                },
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
    }
    if (picking) {
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("Банка для «${wish.name}»") },
            text = {
                Column {
                    client.jars.forEach { jar ->
                        Row(
                            Modifier.fillMaxWidth().clip(Radius.sm).clickable {
                                onLink(jar.id, jarUah(jar))
                                picking = false
                            }.padding(vertical = Space.sm),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(jar.title, Modifier.weight(1f), fontSize = Type.bodySize)
                            Text(amountLabelMinor(jar.balance, jar.currencyCode), color = TextSecondary, fontSize = Type.captionSize)
                        }
                    }
                }
            },
            confirmButton = {
                if (linked != null) {
                    TextButton({ onUnlink(); picking = false }) { Text("Відв'язати", color = Negative) }
                } else {
                    TextButton({ picking = false }) { Text("Скасувати") }
                }
            },
            dismissButton = if (linked != null) {
                { TextButton({ picking = false }) { Text("Скасувати") } }
            } else {
                null
            }
        )
    }
}

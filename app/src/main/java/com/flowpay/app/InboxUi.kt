package com.flowpay.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The Telegram inbox's screens: the row under Налаштування and the sheet behind it —
 * making a bot, binding the owner's chat, and the connected state. The rules are in
 * Inbox.kt; the token, the calls and the pass in InboxSync.kt.
 */

/** How often the sheet asks while the bot waits for Start, and for how long. */
private const val BIND_POLL_MS = 5_000L
private const val BIND_WAIT_MS = 3 * 60_000L

/** The row under Налаштування, in the idiom of monobank's beside it. */
@Composable
fun InboxSettingsItem(onOpen: () -> Unit) {
    val context = LocalContext.current
    val version = InboxStore.version
    val inbox = remember { InboxStore(context) }
    val row = remember(version) {
        inboxRow(inbox.connected(), inbox.bound(), inbox.username(), inbox.lastError(), inbox.lastPass(), System.currentTimeMillis())
    }
    ListItem(
        leadingContent = { EmojiGlyph("📥", 28.dp) },
        // A zero-width space after the hyphen: beside «Налаштувати» the name does not
        // fit one line at 393 dp, and without a break there it split as «скриньк / а»
        // (screens/InboxShots, i4).
        headlineContent = { Text("Telegram-​скринька", fontWeight = FontWeight.Bold) },
        supportingContent = { Text(row.line, color = if (row.alarm) Negative else TextSecondary) },
        trailingContent = {
            OutlinedButton(
                onClick = onOpen,
                shape = Radius.sm,
                border = BorderStroke(1.dp, HairLine),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text(if (row.connected) "Налаштувати" else "Підключити") }
        }
    )
}

/**
 * Three states: making a bot and pasting its token; the bot waiting for Start (asked
 * every five seconds for three minutes, so binding feels instant); and connected.
 */
@Composable
fun InboxSheet(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val version = InboxStore.version
    val inbox = remember { InboxStore(context) }
    val connected = remember(version) { inbox.connected() }
    val bound = remember(version) { inbox.bound() }
    var token by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var problem by remember { mutableStateOf<String?>(null) }
    var confirmOff by remember { mutableStateOf(false) }
    var waitedOut by remember { mutableStateOf(false) }
    val found = botTokenIn(token)

    // While the bot waits for Start: a pass every five seconds, up to three minutes.
    // The passes run in InboxSync's own scope, so closing the sheet stops the asking,
    // never a pass halfway through a message.
    LaunchedEffect(connected, bound) {
        if (!connected || bound) return@LaunchedEffect
        waitedOut = false
        val until = System.currentTimeMillis() + BIND_WAIT_MS
        while (System.currentTimeMillis() < until) {
            InboxSync.passNow(context)?.join()
            if (inbox.bound()) return@LaunchedEffect
            delay(BIND_POLL_MS)
        }
        waitedOut = true
    }

    FormSheet(
        title = "Telegram-скринька",
        confirmLabel = if (connected) "Готово" else "Підключити",
        confirmEnabled = connected || (found != null && !busy),
        onConfirm = {
            if (connected) {
                onClose()
            } else if (found != null) {
                scope.launch {
                    busy = true
                    problem = null
                    problem = try {
                        InboxSync.connect(context, found)
                        token = ""
                        null
                    } catch (stopped: CancellationException) {
                        throw stopped
                    } catch (refused: TgRefused) {
                        connectProblem(refused.code)
                    } catch (_: Exception) {
                        INBOX_CONNECT_OFFLINE
                    }
                    busy = false
                }
            }
        },
        onDismiss = onClose
    ) {
        when {
            !connected -> InboxSetup(token, busy) {
                token = it
                problem = null
            }
            else -> InboxConnected(inbox, version, bound, waitedOut) { confirmOff = true }
        }
        problem?.let { Text(it, color = Negative, fontSize = Type.captionSize, lineHeight = Type.captionLine) }
    }
    if (confirmOff) {
        AlertDialog(
            onDismissRequest = { confirmOff = false },
            title = { Text("Відключити Telegram-скриньку?") },
            text = {
                Text(
                    "FlowPay забуде токен бота і чат. Те, що вже додано, лишиться. " +
                        "Самого бота можна видалити в @BotFather командою /deletebot."
                )
            },
            confirmButton = {
                TextButton({
                    InboxSync.disconnect(context)
                    confirmOff = false
                    onClose()
                }) { Text("Відключити", color = Negative) }
            },
            dismissButton = { TextButton({ confirmOff = false }) { Text("Скасувати") } }
        )
    }
}

/** Not connected: what the inbox is, how to make a bot, and the masked field for its token. */
@Composable
private fun InboxSetup(token: String, busy: Boolean, onToken: (String) -> Unit) {
    val context = LocalContext.current
    Text(
        "Надсилайте з комп'ютера своєму боту в Telegram посилання на товари, номери посилок і листи про " +
            "підписки — FlowPay на телефоні додасть їх сам і відповість у тому ж чаті.",
        fontSize = Type.bodySize,
        lineHeight = Type.bodyLine
    )
    Text(
        INBOX_STEPS,
        Modifier.padding(top = Space.md),
        color = TextSecondary,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine
    )
    TextButton({ openLink(context, BOT_FATHER_LINK) }) { Text("Відкрити @BotFather") }
    OutlinedTextField(
        token,
        onToken,
        Modifier.fillMaxWidth(),
        label = { Text("Токен бота") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation()
    )
    // The whole @BotFather message pasted is fine — the token is found inside it.
    if (token.isNotBlank() && botTokenIn(token) == null) {
        Text(
            "Це не схоже на токен — він має вигляд «123456789:AA…»",
            Modifier.padding(top = Space.xs),
            color = Negative,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine
        )
    }
    Text(
        "Токен зберігається лише на цьому телефоні, зашифрований ключем, який не можна з нього забрати. " +
            "У резервні копії він не потрапляє.",
        Modifier.padding(top = Space.sm),
        color = TextSecondary,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine
    )
    if (busy) BusyMark(Modifier.padding(top = Space.sm))
}

/**
 * Connected: waiting for Start, or bound — the bot, the last check, and the two
 * actions. Internal, not private, so the JVM screenshots can draw the waiting state
 * without the sheet's polling (screens/InboxShots.kt).
 */
@Composable
internal fun InboxConnected(inbox: InboxStore, version: Int, bound: Boolean, waitedOut: Boolean, onDisconnect: () -> Unit) {
    val context = LocalContext.current
    val username = remember(version) { inbox.username() }
    val code = remember(version) { inbox.code() }
    val error = remember(version) { inbox.lastError() }
    val lastPass = remember(version) { inbox.lastPass() }
    Text("@$username", fontSize = Type.cardTitleSize, fontWeight = Type.medium)
    if (!bound) {
        Text("Відкрийте свого бота і натисніть Start", Modifier.padding(top = Space.xs), fontSize = Type.bodySize, lineHeight = Type.bodyLine)
        OutlinedButton(
            onClick = { openLink(context, startLink(username, code)) },
            modifier = Modifier.padding(top = Space.md),
            shape = Radius.sm,
            border = BorderStroke(1.dp, HairLine),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) { Text("Відкрити @$username") }
        Text(
            "На комп'ютері: знайдіть у Telegram @$username, натисніть Start або надішліть йому код $code.",
            Modifier.padding(top = Space.sm),
            color = TextSecondary,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine
        )
        Row(Modifier.padding(top = Space.md), verticalAlignment = Alignment.CenterVertically) {
            if (!waitedOut) {
                BusyMark()
                Spacer(Modifier.width(Space.sm))
                Text("Чекаю на Start…", color = TextSecondary, fontSize = Type.captionSize)
            } else {
                Text(
                    "Start ще не видно. Натисніть Start у боті, тоді «Перевірити зараз».",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
            }
        }
    } else {
        Text(
            lastCheckLine(lastPass, System.currentTimeMillis()).replaceFirstChar { it.uppercase() },
            color = TextSecondary,
            fontSize = Type.captionSize
        )
        Text(
            "Надсилайте боту з комп'ютера посилання на товар, номер посилки або лист про підписку — " +
                "FlowPay додасть і відповість у чаті.",
            Modifier.padding(top = Space.sm),
            fontSize = Type.bodySize,
            lineHeight = Type.bodyLine
        )
    }
    if (error.isNotBlank()) {
        Text(error, Modifier.padding(top = Space.sm), color = Negative, fontSize = Type.captionSize, lineHeight = Type.captionLine)
    }
    Row(Modifier.padding(top = Space.sm), horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
        TextButton({ InboxSync.passNow(context) }) { Text("Перевірити зараз") }
        TextButton(onDisconnect) { Text("Відключити", color = Negative) }
        if (InboxSync.running && bound) BusyMark()
    }
    Text(
        INBOX_BATTERY_LINE,
        Modifier.padding(top = Space.sm),
        color = TextSecondary,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine
    )
    TextButton({ openBackgroundSettings(context) }) { Text("Налаштування батареї") }
}

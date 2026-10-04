package com.flowpay.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * The few controls the acting-without-opening and hiding-sums work adds to the
 * app's own screens. The rules are in QuickActions.kt, Privacy.kt and
 * SubscriptionText.kt; these only draw.
 */

/**
 * The eye on the Огляд hero. Shut, the personal sums on every screen are «•••»
 * until it is opened again; it stays where it is either way, so a screen of dots
 * never looks broken — the way back is in sight.
 */
@Composable
fun SumsEye(tint: Color = AccentInk) {
    val context = LocalContext.current
    val hidden = SumsMask.on
    IconButton(
        {
            SumsMask.set(!hidden)
            TouchPrefs(context).saveHideInside(!hidden)
        },
        Modifier.size(Space.touchRow)
    ) {
        Icon(
            if (hidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            if (hidden) "Показати суми" else "Сховати суми",
            tint = tint
        )
    }
}

/**
 * [NumberField] for a personal sum that sits on a screen rather than in a sheet
 * opened to edit it — savings on a wish's page. While the eye is shut it shows
 * dots; typing still works.
 */
@Composable
fun PersonalNumberField(label: String, value: String, set: (String) -> Unit) = OutlinedTextField(
    value,
    set,
    Modifier.fillMaxWidth().padding(top = Space.md),
    label = { Text(label) },
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
    visualTransformation = if (SumsMask.on) PasswordVisualTransformation() else VisualTransformation.None
)

/**
 * «Ховати суми поза застосунком» under Налаштування: the widget and the tile in
 * the shade say names and dates, not sums. The shade opens on a locked phone.
 */
@Composable
fun HideSumsOutsideRow() {
    val context = LocalContext.current
    val prefs = remember { TouchPrefs(context) }
    var on by remember { mutableStateOf(prefs.hideOutside()) }
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = on, role = Role.Switch) { value ->
                on = value
                prefs.saveHideOutside(value)
                // Both redrawn now rather than at the next pass: the switch is
                // usually flipped just before the phone is handed to someone.
                scope.launch { refreshWidget(context) }
                FlowPayTileService.refresh(context)
            }
            .padding(horizontal = Space.screen, vertical = Space.md),
        verticalAlignment = Alignment.Top
    ) {
        Icon(Icons.Default.VisibilityOff, null, tint = TextSecondary)
        Spacer(Modifier.width(Space.lg))
        Column(Modifier.weight(1f)) {
            Text("Ховати суми поза застосунком", fontSize = Type.cardTitleSize, fontWeight = Type.medium)
            Text(
                "Віджет і плитка в шторці показують лише назви й дати: «Інтернет · завтра», " +
                    "«Вільно: є». Шторку видно й на заблокованому екрані.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
        }
        Spacer(Modifier.width(Space.md))
        Switch(on, null, Modifier.padding(top = 2.dp))
    }
}

/**
 * «Вставити з буфера» at the top of «Новий платіж»: a letter copied from the mail
 * fills the form by the same reading a shared one gets. [note] says what was
 * read, or why nothing was.
 */
@Composable
fun PasteLetterButton(note: String?, onDraft: (SubscriptionDraft) -> Unit, onNote: (String) -> Unit) {
    val context = LocalContext.current
    Column {
        OutlinedButton(
            onClick = {
                val text = clipboardText(context)
                val draft = parseSubscription(text, LocalDate.now())
                when {
                    draft != null -> onDraft(draft)
                    text.isNullOrBlank() -> onNote("У буфері нічого немає — скопіюй лист і натисни ще раз")
                    else -> onNote("У буфері не лист про підписку: шукаю слова «підписка», «пробний», «абонплата» і ціну")
                }
            },
            shape = Radius.sm,
            border = BorderStroke(1.dp, HairLine),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
        ) {
            Icon(Icons.Default.ContentPaste, null, Modifier.size(18.dp))
            Spacer(Modifier.width(Space.sm))
            Text("Вставити з буфера")
        }
        note?.let {
            Text(
                it,
                Modifier.padding(top = Space.xs),
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
    }
}

/** The text on the clipboard, or null. Read only when the button is pressed. */
fun clipboardText(context: Context): String? = runCatching {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
    if (clip == null || clip.itemCount == 0) null else clip.getItemAt(0).coerceToText(context)?.toString()
}.getOrNull()

/**
 * A letter names a payment already on the list, at a new price: «Оновити ціну з
 * 1 лист.: 200 → 250 ₴». Through the payment's own history, so «було → стало»,
 * the tile and the morning message see it like any typed change.
 */
@Composable
fun PriceChangeDialog(change: SharedLetter.PriceChange, today: LocalDate, close: () -> Unit, confirm: () -> Unit) {
    val pay = change.pay
    val since = change.draft.from
    // The app holds one price per payment: a charge before the new date would be
    // counted at the new price once this is pressed, so it is said out loud.
    val stillOld = oldPriceChargeBefore(pay, since, today)
    AlertDialog(
        onDismissRequest = close,
        icon = { EmojiGlyph(shownEmoji(pay), 40.dp) },
        title = { Text("Нова ціна: ${pay.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                Text(
                    (since?.let { "З ${dayMonth(it)} — " } ?: "") +
                        "${amountLabel(change.draft.amount, pay.currency)} замість ${amountLabel(pay.amount, pay.currency)}.",
                    fontSize = Type.bodySize,
                    lineHeight = Type.bodyLine
                )
                stillOld?.let {
                    Text(
                        "Списання ${dayMonth(it)} ще за старою ціною, ${amountLabel(pay.amount, pay.currency)}.",
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine
                    )
                }
                Text(
                    "Стара ціна лишиться в історії платежу.",
                    color = TextSecondary,
                    fontSize = Type.captionSize,
                    lineHeight = Type.captionLine
                )
            }
        },
        confirmButton = { Button(confirm) { Text(priceChangeLabel(change)) } },
        dismissButton = { TextButton(close) { Text("Не треба") } }
    )
}

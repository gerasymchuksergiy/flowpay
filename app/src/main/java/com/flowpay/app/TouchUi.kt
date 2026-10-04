package com.flowpay.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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

package com.flowpay.app

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * The drawing for PricesMore.kt and RateWatch.kt. Nothing here decides anything:
 * every sentence and every figure comes from those two files.
 */

// ------------------------------------------------------------ the Rozetka card

/**
 * «У мене є Картка Rozetka», under Налаштування.
 *
 * Off until switched on, and with it off nothing about the card appears anywhere —
 * not a line, not a hint that the card would be cheaper. With it on, the wish page
 * shows the card price under the ordinary one and the target is checked against it.
 */
@Composable
fun RozetkaCardRow(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prices = remember(context) { PriceStore(context) }
    var on by remember { mutableStateOf(prices.rozetkaCard()) }
    val touch = rememberTouch()
    // The same shape as the emoji and update rows beside it.
    ListItem(
        modifier = modifier,
        leadingContent = { EmojiGlyph("💳", 28.dp) },
        headlineContent = { Text("У мене є Картка Rozetka", fontWeight = FontWeight.Bold) },
        supportingContent = {
            Text(
                "Тоді під ціною Rozetka видно ціну з карткою, і ціль перевіряється й за нею. " +
                    "Графік та історія — завжди звичайна ціна.",
                color = TextSecondary
            )
        },
        trailingContent = {
            Switch(on, { checked ->
                on = checked
                prices.saveRozetkaCard(checked)
                touch.switched(checked)
            })
        }
    )
}

/** One quiet line under the price on the wish page. */
@Composable
fun PriceAside(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(top = Space.xs),
        color = TextSecondary,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine
    )
}

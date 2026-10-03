package com.flowpay.app

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The bento look, chosen by the owner on 3 October 2026 from ten styles tried on
 * his own screens: everything is a tile, tiles come in six pastels, and every
 * payment and purchase wears an emoji. The colours are in Theme.kt with the
 * reason they are allowed; the emoji are in Emoji.kt.
 */

// ------------------------------------------------------------ colour

/** True for a fill light enough that the ink on it has to be dark. */
fun isLightFill(colour: Color): Boolean = colour.luminance() > 0.4f

/** Text on [colour]: near-black on a pastel or the lime, the usual light text on the dark ground. */
fun inkOn(colour: Color): Color = if (isLightFill(colour)) TileInk else TextPrimary

/** The quieter text on [colour], held to 4.5:1 on either. */
fun softInkOn(colour: Color): Color = if (isLightFill(colour)) TileInkSoft else TextSecondary

/**
 * A pastel for each tile in a run, the same one for the same name every time,
 * and never the colour of the tile beside it or of the one above it in a grid of
 * two.
 *
 * By name rather than by position, so adding a payment does not repaint every
 * tile after it; the neighbour rule only nudges where two would touch.
 */
fun tileColours(keys: List<String>): List<Color> {
    val out = ArrayList<Color>(keys.size)
    keys.forEachIndexed { index, key ->
        var pick = Math.floorMod(key.hashCode(), TILE_COLOURS.size)
        val avoid = listOfNotNull(out.getOrNull(index - 1), out.getOrNull(index - 2))
        repeat(TILE_COLOURS.size) {
            if (TILE_COLOURS[pick] in avoid) pick = (pick + 1) % TILE_COLOURS.size
        }
        out += TILE_COLOURS[pick]
    }
    return out
}

// ------------------------------------------------------------ tiles

/** The corner every tile shares. Rounder than a card: these are objects, not forms. */
val TileRadius = Radius.lg

/**
 * One tile: a coloured block with the right ink inside it.
 *
 * The ink is provided as the content colour, so a plain `Text` inside picks the
 * dark ink on a pastel and the light one on the dark ground without being told.
 */
@Composable
fun BentoTile(
    colour: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    val press = remember { MutableInteractionSource() }
    Column(
        modifier
            .then(if (onClick != null) Modifier.pressScale(press) else Modifier)
            .clip(TileRadius)
            .background(colour)
            .then(
                if (onClick != null) {
                    Modifier.clickable(
                        interactionSource = press,
                        indication = LocalIndication.current,
                        onClickLabel = onClickLabel,
                        role = Role.Button,
                        onClick = onClick
                    )
                } else {
                    Modifier
                }
            )
            .padding(Space.lg)
    ) {
        CompositionLocalProvider(LocalContentColor provides inkOn(colour)) { content() }
    }
}

/**
 * The size a figure in [Display] can take in [room]: [roomy] while its whole
 * part is short, stepping down as it grows so a large sum still fits on one line.
 *
 * Unbounded is wide — a digit is about three quarters of an em — and a figure
 * that wraps or clips is worse than a smaller one.
 */
fun displayFigureSize(whole: String, roomy: TextUnit): TextUnit {
    val digits = whole.count { it.isDigit() }
    return when {
        digits <= 4 -> roomy
        digits == 5 -> roomy * 0.92f
        digits == 6 -> roomy * 0.82f
        else -> roomy * 0.7f
    }
}

/**
 * A figure in the display face, with its kopecks and currency at half size:
 * «27 891» then «,06 ₴». The same cut as the hero panel's, see [heroParts].
 */
@Composable
fun SplitFigure(value: String, size: TextUnit, modifier: Modifier = Modifier, colour: Color = LocalContentColor.current) {
    // «1 200 ₴ + 9,59 $» — two currencies with no rate between them — is not one
    // figure to cut in half; it is set whole, smaller.
    if ('+' in value) {
        Text(
            value,
            modifier,
            color = colour,
            fontSize = size * 0.6f,
            fontWeight = FontWeight.Bold,
            style = Tabular.copy(fontFamily = Display),
            maxLines = 2
        )
        return
    }
    val (whole, rest) = heroParts(value)
    val shown = displayFigureSize(whole, size)
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        RollingText(
            whole,
            color = colour,
            fontSize = shown,
            fontWeight = FontWeight.Bold,
            letterSpacing = (-0.6).sp,
            style = Tabular.copy(fontFamily = Display)
        )
        if (rest.isNotEmpty()) {
            RollingText(
                rest,
                Modifier.padding(bottom = 2.dp),
                color = colour.copy(alpha = colour.alpha * 0.65f),
                fontSize = shown * 0.55f,
                fontWeight = FontWeight.Bold,
                style = Tabular.copy(fontFamily = Display)
            )
        }
    }
}

/** A small rounded label for a date or a state, in the tile's own ink. */
@Composable
fun TileChip(text: String, colour: Color, strong: Boolean = false) {
    val ink = inkOn(colour)
    Text(
        text,
        Modifier
            .clip(Radius.pill)
            .background(if (strong) ink else ink.copy(alpha = 0.1f))
            .padding(horizontal = Space.sm, vertical = 2.dp),
        color = if (strong) colour else ink,
        fontSize = Type.overlineSize,
        fontWeight = Type.strong,
        maxLines = 1
    )
}

/** An emoji set at an angle, the way a sticker sits on a panel. */
@Composable
fun EmojiSticker(emoji: String, size: Dp, modifier: Modifier = Modifier) {
    EmojiGlyph(emoji, size, modifier.rotate(9f))
}

// ------------------------------------------------------------ choosing an emoji

/**
 * The row in a form that shows the emoji and opens the picker.
 *
 * [chosen] empty means the guess from the name is in force, and the row says so,
 * because "why is my rent a light bulb" is answered by «підібрано за назвою».
 */
@Composable
fun EmojiField(chosen: String, guessed: String, onChange: (String) -> Unit) {
    var picking by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(Radius.sm)
            .clickable(onClickLabel = "Змінити емодзі") { picking = true }
            .padding(vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        EmojiGlyph(chosen.ifBlank { guessed }, 40.dp)
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text("Емодзі", fontSize = Type.bodySize, fontWeight = Type.medium)
            Text(
                if (chosen.isBlank()) "Підібрано за назвою · торкніться, щоб змінити" else "Вибране вами · торкніться, щоб змінити",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine
            )
        }
    }
    if (picking) {
        EmojiPickerDialog(
            guessed = guessed,
            onPick = {
                onChange(it)
                picking = false
            },
            onDismiss = { picking = false }
        )
    }
}

/**
 * A grid of the usual emoji, «як за назвою» to go back to the guess, and a field
 * for anything else from the keyboard's emoji panel.
 *
 * [onPick] gets "" for the guess, so the stored field goes back to empty rather
 * than freezing today's guess in place.
 */
@Composable
fun EmojiPickerDialog(guessed: String, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    var typed by remember { mutableStateOf("") }
    val own = typedEmoji(typed)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Емодзі") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(Radius.sm)
                        .background(SurfaceRaised)
                        .clickable { onPick("") }
                        .padding(Space.sm),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    EmojiGlyph(guessed, 32.dp)
                    Spacer(Modifier.width(Space.md))
                    Text("Як за назвою", fontWeight = Type.medium)
                }
                LazyVerticalGrid(
                    GridCells.Adaptive(48.dp),
                    Modifier.height(232.dp)
                ) {
                    items(EMOJI_CHOICES) { emoji ->
                        EmojiGlyph(
                            emoji,
                            40.dp,
                            Modifier
                                .padding(4.dp)
                                .clip(CircleShape)
                                .clickable { onPick(emoji) }
                                .semantics { contentDescription = emoji }
                        )
                    }
                }
                OutlinedTextField(
                    typed,
                    { typed = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Або своє з клавіатури") },
                    singleLine = true,
                    isError = typed.isNotBlank() && own == null,
                    supportingText = if (typed.isNotBlank() && own == null) {
                        { Text("Тут потрібне одне емодзі, без літер") }
                    } else {
                        null
                    }
                )
            }
        },
        confirmButton = {
            TextButton({ own?.let(onPick) }, enabled = own != null) { Text("Взяти своє") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Скасувати") } }
    )
}

/** A page's picture when the thing has no photo: its emoji on a pastel band. */
@Composable
fun EmojiHeader(emoji: String, colour: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(horizontal = Space.screen)
            .fillMaxWidth()
            .height(160.dp)
            .clip(TileRadius)
            .background(colour),
        contentAlignment = Alignment.Center
    ) {
        EmojiSticker(emoji, 72.dp)
    }
}

/** The quieter text of a tile, in one place so every tile reads alike. */
@Composable
fun TileCaption(text: String, colour: Color, modifier: Modifier = Modifier, maxLines: Int = 2) {
    Text(
        text,
        modifier,
        color = softInkOn(colour),
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis
    )
}

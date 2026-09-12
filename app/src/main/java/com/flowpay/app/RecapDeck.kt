package com.flowpay.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay

/**
 * The recap, as the deck of cards it is.
 *
 * Tap to advance, tap the left edge to go back, hold to pause: the grammar people
 * already know from every story player, and the reason none of it is labelled. A
 * card holds for seven seconds, so eight of them is under a minute — a ceiling
 * rather than a target, because the deck that had to grow a speed control and a
 * jump-back had already made itself too long to sit through.
 *
 * Nothing here rings the phone. The deck is built when the app is opened and waits
 * on the overview until it is tapped.
 */

/** How long a card holds before the deck moves on. */
private const val CARD_MILLIS = 7000f

/** How often the progress bar is advanced. Fine enough to read as movement. */
private const val TICK_MILLIS = 40L

/** The left share of the screen that goes back rather than forward. */
private const val BACK_ZONE = 0.3f

@Composable
fun RecapDeck(recap: Recap, onClose: () -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        var index by remember { mutableIntStateOf(0) }
        var held by remember { mutableStateOf(false) }
        var progress by remember { mutableFloatStateOf(0f) }
        // A phone told to stop animating gets no timer at all. Advancing on its own
        // is motion, and shortening it would still be motion: the deck simply waits
        // for a tap, which it was always going to accept anyway.
        val reduced = LocalReducedMotion.current
        val card = recap.cards.getOrNull(index) ?: recap.cards.lastOrNull() ?: return@Dialog

        fun forward() {
            if (index < recap.cards.lastIndex) index++ else onClose()
        }

        fun back() {
            if (index > 0) index-- else progress = 0f
        }

        LaunchedEffect(index) { progress = 0f }
        LaunchedEffect(index, held, reduced) {
            if (held || reduced) return@LaunchedEffect
            while (progress < 1f) {
                delay(TICK_MILLIS)
                progress = (progress + TICK_MILLIS / CARD_MILLIS).coerceAtMost(1f)
            }
            forward()
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(AppBackground)
                .pointerInput(index, recap.cards.size) {
                    detectTapGestures(
                        // Pausing on press rather than on a long press, because the
                        // gesture people actually make is to hold still while they
                        // finish reading.
                        onPress = {
                            held = true
                            tryAwaitRelease()
                            held = false
                        },
                        onTap = { where ->
                            if (where.x < size.width * BACK_ZONE) back() else forward()
                        }
                    )
                }
        ) {
            Column(Modifier.fillMaxSize().systemBarsPadding().padding(Space.screen)) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.xs)
                ) {
                    recap.cards.indices.forEach { at ->
                        Box(
                            Modifier
                                .weight(1f)
                                .height(3.dp)
                                .background(SurfaceHigh, Radius.pill)
                        ) {
                            val filled = when {
                                at < index -> 1f
                                at > index -> 0f
                                reduced -> 1f
                                else -> progress
                            }
                            if (filled > 0f) {
                                Box(
                                    Modifier
                                        .fillMaxWidth(filled)
                                        .height(3.dp)
                                        .background(Accent, Radius.pill)
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        recap.title,
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium
                    )
                    Spacer(Modifier.weight(1f))
                    IconButton(onClose) {
                        Icon(Icons.Default.Close, "Закрити", tint = TextSecondary)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.CenterStart) {
                    Column {
                        Text(
                            card.overline,
                            color = Accent,
                            fontSize = Type.overlineSize,
                            letterSpacing = Type.overlineTracking,
                            fontWeight = Type.medium
                        )
                        Spacer(Modifier.height(Space.md))
                        Text(
                            card.headline,
                            fontSize = Type.screenTitleSize,
                            lineHeight = Type.screenTitleLine,
                            letterSpacing = Type.screenTitleTracking,
                            fontWeight = Type.strong
                        )
                        if (card.detail.isNotBlank()) {
                            Spacer(Modifier.height(Space.md))
                            Text(
                                card.detail,
                                color = TextSecondary,
                                fontSize = Type.bodySize,
                                lineHeight = Type.bodyLine
                            )
                        }
                    }
                }
                Text(
                    if (index == recap.cards.lastIndex) "Торкніться, щоб закрити" else "Торкніться, щоб далі",
                    color = TextDisabled,
                    fontSize = Type.captionSize
                )
            }
        }
    }
}

/**
 * The line that offers the deck, and the only place it ever asks for attention.
 *
 * A card on a screen rather than a notification. The recap is an artefact, and an
 * artefact that taps you on the shoulder is the failure every one of these
 * features eventually had to add a switch for.
 */
@Composable
fun RecapInvite(recap: Recap, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = SurfaceRaised),
        shape = Radius.md
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(Space.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "МІСЯЦЬ ГОТОВИЙ",
                    color = Accent,
                    fontSize = Type.overlineSize,
                    letterSpacing = Type.overlineTracking,
                    fontWeight = Type.medium
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    recapInvite(recap),
                    fontSize = Type.cardTitleSize,
                    lineHeight = Type.cardTitleLine,
                    fontWeight = Type.medium
                )
            }
            Spacer(Modifier.width(Space.md))
            Box(
                Modifier
                    .width(Space.touchRow)
                    .height(Space.touchRow)
                    .background(Accent, Radius.pill),
                contentAlignment = Alignment.Center
            ) {
                Text("▶", color = AccentInk, fontSize = Type.bodySize)
            }
        }
    }
}

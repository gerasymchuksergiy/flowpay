package com.flowpay.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import java.time.LocalDate

/** The tiles and the sheet for the ideas in Ideas.kt. */

// ------------------------------------------------------------ money weather

/**
 * The week as a forecast: a column per day with its weather emoji and what
 * leaves, and one sentence naming the worst day.
 */
@Composable
fun WeatherTile(
    days: List<MoneyDay>,
    today: LocalDate,
    modifier: Modifier = Modifier,
    /** The card's own money from monobank, when connected — see Mono.kt. */
    balance: Double? = null
) {
    BentoTile(TileSky, modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Фінансова погода",
                Modifier.weight(1f),
                color = TileInkSoft,
                fontSize = Type.captionSize,
                fontWeight = Type.medium
            )
            Text("на тиждень", color = TileInkSoft, fontSize = Type.captionSize)
        }
        Spacer(Modifier.height(Space.sm))
        Row(Modifier.fillMaxWidth()) {
            days.forEach { day ->
                val isToday = day.date == today
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (isToday) "Сьог." else weekdayShort(day.date),
                        color = if (isToday) TileInk else TileInkSoft,
                        fontSize = 11.sp,
                        fontWeight = if (isToday) Type.strong else Type.medium,
                        maxLines = 1
                    )
                    Spacer(Modifier.height(Space.xs))
                    EmojiGlyph(day.emoji, 30.dp)
                    Spacer(Modifier.height(Space.xs))
                    Text(
                        if (day.leaving > 0.0) shortMoney(day.leaving) else "—",
                        color = TileInkSoft,
                        fontSize = 10.sp,
                        maxLines = 1,
                        style = Tabular
                    )
                }
            }
        }
        Spacer(Modifier.height(Space.sm))
        TileCaption(weatherLine(days, today), TileSky)
        balance?.let {
            Text(
                balanceLine(it, days),
                Modifier.padding(top = Space.xs),
                color = TileInk,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                fontWeight = Type.medium
            )
        }
    }
}

// ------------------------------------------------------------ a treat

/** «Можна дозволити собі»: the wish, its price, why now, and what stays free. */
@Composable
fun TreatTile(treat: Treat, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    BentoTile(TilePink, modifier.fillMaxWidth(), onClick = onOpen, onClickLabel = "Відкрити бажання") {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = Space.sm)) {
                Text("Можна дозволити собі", color = TileInkSoft, fontSize = Type.captionSize, fontWeight = Type.medium)
                Spacer(Modifier.height(Space.xs))
                Text(
                    treat.wish.name,
                    fontSize = Type.bodySize,
                    lineHeight = Type.bodyLine,
                    fontWeight = Type.medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(Space.xs))
                SplitFigure(money(treat.wish.price), 22.sp)
            }
            EmojiSticker("🎁", 48.dp)
        }
        Spacer(Modifier.height(Space.sm))
        TileCaption(
            listOfNotNull(
                treat.reason.takeIf { it.isNotBlank() }?.replaceFirstChar { it.uppercase() },
                "і ще лишиться ${money(treat.leftAfter)} вільних"
            ).joinToString(" · "),
            TilePink,
            maxLines = 3
        )
    }
}

// ------------------------------------------------------------ the duel

/** How many duels make a round. Enough to learn something, few enough to finish. */
const val DUEL_ROUND = 5

/** «Відкласти»: how long a fading wish is put aside from the duel's result. */
const val DUEL_HOLD_DAYS = 30L

/** The way into a duel, under the wishlist's total. */
@Composable
fun DuelInvite(modifier: Modifier = Modifier, onStart: () -> Unit) {
    BentoTile(TilePeach, modifier.fillMaxWidth(), onClick = onStart, onClickLabel = "Почати дуель") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            EmojiGlyph("⚔️", 36.dp)
            Spacer(Modifier.width(Space.md))
            Column(Modifier.weight(1f)) {
                Text("Дуель бажань", fontSize = Type.bodySize, fontWeight = Type.medium)
                TileCaption("Що хочеш більше? $DUEL_ROUND швидких виборів — і видно, що справді потрібне", TilePeach)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = TileInkSoft)
        }
    }
}

/**
 * Two wishes side by side, five times; then the top three and, if one keeps
 * losing, a quiet offer to put it aside for a month.
 */
@Composable
fun DuelSheet(
    items: List<Wish>,
    today: Long,
    onPick: (winner: String, loser: String) -> Unit,
    onSetAside: (String) -> Unit,
    onClose: () -> Unit
) {
    val touch = rememberTouch()
    var round by remember { mutableIntStateOf(0) }
    var last by remember { mutableStateOf<Pair<String, String>?>(null) }
    // Picked once per round from the list as it stands, so the ratings the last
    // answer changed already steer the next pairing.
    val pair = remember(round) { nextDuel(duelPool(items, today), last) }
    FormSheet(
        title = if (round < DUEL_ROUND && pair != null) "Що хочеш більше?" else "Що вийшло",
        confirmLabel = "Готово",
        confirmEnabled = true,
        onConfirm = onClose,
        onDismiss = onClose
    ) {
        if (round < DUEL_ROUND && pair != null) {
            Text("Дуель ${round + 1} з $DUEL_ROUND", color = TextSecondary, fontSize = Type.captionSize)
            val grow = Motion.fastSpatial<Float>()
            val fade = Motion.effects<Float>()
            AnimatedContent(
                targetState = pair,
                transitionSpec = {
                    (scaleIn(grow, initialScale = 0.85f) + fadeIn(fade)) togetherWith
                        (scaleOut(fade, targetScale = 1.05f) + fadeOut(fade))
                },
                label = "duel"
            ) { (left, right) ->
                Row(
                    Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                    horizontalArrangement = Arrangement.spacedBy(Space.md),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DuelCard(left, Modifier.weight(1f).fillMaxHeight()) {
                        touch.switched(true)
                        onPick(left.id, right.id)
                        last = left.id to right.id
                        round++
                    }
                    DuelCard(right, Modifier.weight(1f).fillMaxHeight()) {
                        touch.switched(true)
                        onPick(right.id, left.id)
                        last = left.id to right.id
                        round++
                    }
                }
            }
        } else {
            val result = duelResult(items)
            if (result.top.isEmpty()) {
                Text("Для дуелі потрібно хоча б два бажання з ціною.", color = TextSecondary)
            }
            result.top.forEachIndexed { place, wish ->
                Row(Modifier.fillMaxWidth().padding(vertical = Space.xs), verticalAlignment = Alignment.CenterVertically) {
                    EmojiGlyph(listOf("🥇", "🥈", "🥉")[place], 28.dp)
                    Spacer(Modifier.width(Space.md))
                    Text(
                        wish.name,
                        Modifier.weight(1f),
                        fontSize = Type.bodySize,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        "${wish.duelWins} з ${wish.duelsPlayed}",
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        style = Tabular
                    )
                }
            }
            result.fading?.let { fading ->
                Spacer(Modifier.height(Space.sm))
                BentoTile(TileSand, Modifier.fillMaxWidth()) {
                    Text("Найчастіше програє", color = TileInkSoft, fontSize = Type.captionSize)
                    Text(fading.name, fontSize = Type.bodySize, fontWeight = Type.medium, maxLines = 2)
                    TileCaption("Може, вже не так і хочеться? Можна відкласти на місяць — і подивитись, чи згадаєте.", TileSand, maxLines = 3)
                    TextButton({ onSetAside(fading.id); onClose() }) { Text("Відкласти на місяць", color = TileInk) }
                }
            }
            Spacer(Modifier.height(Space.sm))
            TextButton({ round = 0 }) { Text("Ще раунд") }
        }
    }
}

/** One side of a duel: the photo or the emoji, the name, the price. */
@Composable
private fun DuelCard(wish: Wish, modifier: Modifier = Modifier, onPick: () -> Unit) {
    val colour = tileColours(listOf(wish.id)).first()
    BentoTile(colour, modifier, onClick = onPick, onClickLabel = "Хочу більше") {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(Radius.sm),
            contentAlignment = Alignment.Center
        ) {
            if (wish.image.isNotBlank()) {
                AsyncImage(crossfadeImage(wish.image), wish.name, Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
            } else {
                EmojiGlyph(wishEmoji(wish.name), 56.dp)
            }
        }
        Spacer(Modifier.height(Space.sm))
        Text(
            wish.name,
            fontSize = Type.captionSize,
            lineHeight = Type.captionLine,
            fontWeight = Type.medium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Start
        )
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.height(Space.xs))
        SplitFigure(money(wish.price), 16.sp)
    }
}

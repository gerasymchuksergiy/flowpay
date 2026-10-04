package com.flowpay.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import java.time.LocalDate

/**
 * «Аркуш поверх магазину»: «Поділитися» from a shop's app opens a half-height sheet
 * over that app instead of switching to FlowPay — Keepa's chart on the product page,
 * as near as a phone gets to it.
 *
 * A thing already watched shows its chart, the range bar, the verdict («нижче
 * звичайного на 6%») and the target, and «Готово» puts the owner back in the shop.
 * A new one shows its name, photograph and today's price, says honestly that there
 * is no history yet, and offers a target of ten per cent off and «Стежити».
 *
 * Everything else that can be shared — a parcel number, a subscription's e-mail, a
 * Hotline page, a text with no link — goes on to [MainActivity] as before
 * ([shopSheetRoute]), so the app's own router keeps every rule it has.
 *
 * The window is translucent and kept out of recents (see the manifest). It writes
 * only through [Store], onto the list as it is at that moment, and bumps
 * [ShopSheetSignal] so a running main screen reads the list again rather than saving
 * its older copy over the new wish.
 */
class ShopSheetActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent?.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        val route = shopSheetRoute(text, Store(this).wishes())
        if (route == SheetRoute.Forward) {
            forward(text)
            return
        }
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        setContent {
            FlowPayOverlayTheme {
                ShopSheet(route, onClose = { finish() }, onOpenApp = { forward(text) })
            }
        }
    }

    /**
     * The share, handed to the app's own router as though it had been sent there:
     * the whole intent — every extra, the subject of an e-mail included — with only
     * its destination changed. A share with no text is the router's to refuse.
     */
    private fun forward(text: String?) {
        val shared = intent
        if (shared != null && (shared.action == Intent.ACTION_SEND || !text.isNullOrBlank())) {
            startActivity(
                Intent(shared)
                    .setComponent(ComponentName(this, MainActivity::class.java))
                    .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            (shared.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
            )
        }
        finish()
    }
}

/**
 * Bumped whenever the sheet has written a wish. The main screen watches it and reads
 * the store again — the same arrangement as [MonoStore.version] — because a screen
 * that saved its own older copy of the list afterwards would drop the new wish.
 */
object ShopSheetSignal {
    var version by mutableIntStateOf(0)
        private set

    fun bump() {
        version++
    }
}

/**
 * Adds [wish] onto the list as it is in the store right now — never onto a copy read
 * earlier — unless one of its shops is already watched. True when it was added.
 */
fun addFromSheet(context: Context, wish: Wish): Boolean {
    val store = Store(context)
    val now = store.wishes()
    if (now.any { hasSource(it, wish.url) }) return false
    store.saveWishes(now + wish)
    ShopSheetSignal.bump()
    return true
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopSheet(route: SheetRoute, onClose: () -> Unit, onOpenApp: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        containerColor = SurfaceLow,
        contentColor = TextPrimary,
        scrimColor = Color.Black.copy(alpha = 0.45f),
        dragHandle = { BottomSheetDefaults.DragHandle(color = HairLine) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.screen)
                .padding(bottom = Space.xl)
        ) {
            when (route) {
                is SheetRoute.Known -> KnownSheet(route.id, onClose, onOpenApp)
                is SheetRoute.New -> NewSheet(route.url, onClose, onOpenApp)
                SheetRoute.Forward -> Unit
            }
        }
    }
}

/** The thing, at the top of either sheet: its photograph or emoji, its name, the shop. */
@Composable
private fun SheetHeader(name: String, image: String, url: String, key: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (image.isNotBlank()) {
            AsyncImage(
                image,
                name,
                Modifier.size(64.dp).clip(Radius.sm).background(SurfaceRaised),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(
                Modifier.size(64.dp).clip(Radius.sm).background(tileColours(listOf(key)).first()),
                contentAlignment = Alignment.Center
            ) { EmojiGlyph(wishEmoji(name), 36.dp) }
        }
        Spacer(Modifier.width(Space.md))
        Column(Modifier.weight(1f)) {
            Text(
                name,
                fontSize = Type.bodySize,
                lineHeight = Type.bodyLine,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(sourceName(url), color = TextSecondary, fontSize = Type.captionSize, maxLines = 1)
        }
    }
}

/** Two buttons at the foot of the sheet, the quiet one first. */
@Composable
private fun SheetButtons(quiet: String, onQuiet: () -> Unit, main: String, onMain: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = Space.xl),
        horizontalArrangement = Arrangement.spacedBy(Space.md)
    ) {
        OutlinedButton(onQuiet, Modifier.weight(1f), shape = Radius.pill) {
            Text(quiet, maxLines = 1, softWrap = false)
        }
        Button(onMain, Modifier.weight(1f), shape = Radius.pill) { Text(main, maxLines = 1, softWrap = false) }
    }
}

/** A thing already watched: what it has done, without leaving the shop. */
@Composable
private fun KnownSheet(id: String, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val context = LocalContext.current
    val wish = remember(id) { Store(context).wishes().firstOrNull { it.id == id } }
    val hasCard = remember { PriceStore(context).rozetkaCard() }
    val today = remember { LocalDate.now().toEpochDay() }
    if (wish == null) {
        Text("Цього бажання вже немає у списку", color = TextSecondary, fontSize = Type.bodySize)
        SheetButtons("У FlowPay", onOpenApp, "Готово", onClose)
        return
    }
    val stale = isStale(wish.freshness)
    val insight = remember(wish) { priceInsight(wish.history, wish.price, wish.checkedDay) }
    val verdict = if (stale) BuyVerdict.UNKNOWN else insight.verdict
    SheetHeader(wish.name, wish.image, wish.url, wish.id)
    Row(Modifier.padding(top = Space.lg), verticalAlignment = Alignment.Bottom) {
        Text(
            money(wish.price),
            fontSize = Type.heroSize,
            fontWeight = Type.strong,
            color = if (stale) TextSecondary else TextPrimary,
            style = Tabular
        )
        checkedLine(wish.checkedDay, today)?.let {
            Spacer(Modifier.width(Space.sm))
            Text(it, color = TextSecondary, fontSize = Type.captionSize, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
    freshnessNote(wish.freshness)?.let {
        Text(it, color = Negative, fontSize = Type.captionSize, lineHeight = Type.captionLine)
    }
    cardLine(wish, hasCard)?.let { PriceAside(it) }
    marketLine(wish.market, today)?.let { PriceAside(it) }
    if (!stale && insight.highest > insight.lowest) {
        Spacer(Modifier.height(Space.lg))
        PriceRangeBar(insight)
    }
    Spacer(Modifier.height(Space.lg))
    WishPriceChart(
        points = remember(wish) { chartSeries(wish.history, wish.price, wish.checkedDay) },
        gaps = wish.stockGaps,
        height = 96.dp
    )
    Text(
        verdictLabel(verdict),
        color = when (verdict) {
            BuyVerdict.GOOD -> Accent
            BuyVerdict.POOR -> Negative
            else -> TextSecondary
        },
        fontSize = Type.cardTitleSize,
        fontWeight = Type.medium,
        modifier = Modifier.padding(top = Space.md)
    )
    val usual = remember(wish) { usualPrice(wish.history, wish.price, wish.checkedDay) }
    (if (stale) null else usualLine(insight, usual))?.let {
        Text(it, color = TextPrimary, fontSize = Type.captionSize, lineHeight = Type.captionLine)
    }
    Text(
        sheetTargetLine(wish),
        color = TextSecondary,
        fontSize = Type.captionSize,
        lineHeight = Type.captionLine,
        modifier = Modifier.padding(top = Space.sm)
    )
    SheetButtons("У FlowPay", onOpenApp, "Готово", onClose)
}

/** A shop's page not on the list: what it costs today, and an offer to watch it. */
@Composable
private fun NewSheet(url: String, onClose: () -> Unit, onOpenApp: () -> Unit) {
    val context = LocalContext.current
    val touch = rememberTouch()
    val today = remember { LocalDate.now().toEpochDay() }
    val id = remember { System.currentTimeMillis().toString() }
    var read by remember { mutableStateOf<PageAdd?>(null) }
    var loading by remember { mutableStateOf(true) }
    var watching by remember { mutableStateOf(false) }
    var target by remember { mutableDoubleStateOf(0.0) }
    // One request to the page, as the share always made.
    LaunchedEffect(url) {
        read = runCatching {
            readForAdd(pricedPageHtml(url), url, id, today, Store(context).fxRate().first)
        }.getOrNull()
        loading = false
    }
    val priced = read as? PageAdd.Priced
    when {
        loading -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BusyMark()
                Spacer(Modifier.width(Space.sm))
                Text("Читаю сторінку…", color = TextSecondary, fontSize = Type.captionSize)
            }
            SheetButtons("У FlowPay", onOpenApp, "Готово", onClose)
        }
        priced != null -> {
            val wish = priced.wish
            SheetHeader(wish.name, wish.image, url, wish.id)
            Text(
                money(wish.price),
                fontSize = Type.heroSize,
                fontWeight = Type.strong,
                style = Tabular,
                modifier = Modifier.padding(top = Space.lg)
            )
            freshnessNote(wish.freshness)?.let {
                Text(it, color = Negative, fontSize = Type.captionSize, lineHeight = Type.captionLine)
            }
            Text(
                "Історії ще нема — лише сьогоднішня ціна. Далі FlowPay перевірятиме її двічі на день.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
            if (watching) {
                Text(
                    "Стежу за цим товаром.",
                    color = TextPrimary,
                    fontSize = Type.bodySize,
                    modifier = Modifier.padding(top = Space.lg)
                )
                SheetButtons("У FlowPay", onOpenApp, "Готово", onClose)
            } else {
                val tenOff = kotlin.math.floor(wish.price * 0.9)
                if (wish.price > 0.0) {
                    FilterChip(
                        selected = target == tenOff,
                        onClick = { target = if (target == tenOff) 0.0 else tenOff },
                        label = { Text("Ціль −10% · ${money(tenOff)}") },
                        modifier = Modifier.padding(top = Space.md)
                    )
                }
                SheetButtons("Скасувати", onClose, "Стежити") {
                    if (addFromSheet(context, wish.copy(targetPrice = target))) touch.landed()
                    watching = true
                }
            }
        }
        else -> {
            // Read, but no price on it — or no page at all. The app's own add flow
            // keeps the link and asks for the number; this sheet does not repeat it.
            Text(
                (read as? PageAdd.Described)?.wish?.let { "Ціни на сторінці «${it.name}» немає." }
                    ?: "Сторінку не вдалося прочитати.",
                color = TextSecondary,
                fontSize = Type.bodySize,
                lineHeight = Type.bodyLine
            )
            Text(
                "У FlowPay посилання збережеться, а ціну можна вписати вручну.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )
            SheetButtons("Готово", onClose, "У FlowPay", onOpenApp)
        }
    }
}

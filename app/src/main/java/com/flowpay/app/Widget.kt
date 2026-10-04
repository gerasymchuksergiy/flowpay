package com.flowpay.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * FlowPay on the home screen: the next payment, what the month has left, and
 * whether anything is waiting to be collected.
 *
 * Those three are what the app gets opened to check, and checking them is the
 * part that does not need the app. Everything shown is read from the same
 * SharedPreferences the screens read, through [Store], so the widget cannot
 * drift from what the app would say.
 */
class FlowPayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store(context)
        val summary = widgetSummary(
            pays = store.pays(),
            orders = store.orders(),
            income = store.income(),
            // The widget never fetches: a rate it cannot refresh is one the app
            // already has, and totalLabel says so out loud when it is missing.
            usdSellRate = store.fxRate().first.sell,
            today = LocalDate.now(),
            marks = store.paidMarks(),
            // The same «Вільно» as Огляд: «На життя» and the funds in it (MoneyPlan.kt).
            month = honestBudget(context, store, store.fxRate().first.sell, LocalDate.now())
        )
        // The next payment's emoji, from the imported pack when there is one —
        // decoded here because a widget draws a bitmap, not a composable glyph.
        val emoji = store.pays().firstOrNull { it.name == summary.paymentName }?.let { shownEmoji(it) } ?: "🗓️"
        val picture = runCatching {
            java.io.File(java.io.File(context.filesDir, "emoji"), "${emojiKey(emoji)}.png")
                .takeIf { it.isFile }
                ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
        }.getOrNull()
        provideContent { WidgetBody(summary, emoji, picture) }
    }
}

class FlowPayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlowPayWidget()
}

@Composable
private fun WidgetBody(summary: WidgetSummary, emoji: String, picture: android.graphics.Bitmap?) {
    // A lime tile on the home screen, the app's hero panel in miniature: dark ink,
    // and the next payment's emoji in the corner.
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(Accent)
            .appWidgetBackground()
            .cornerRadius(Radius.card)
            .padding(Space.lg)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Row(GlanceModifier.fillMaxWidth()) {
            Text(
                "FLOWPAY",
                GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = ColorProvider(AccentInk.copy(alpha = 0.65f)),
                    fontSize = Type.overlineSize,
                    fontWeight = FontWeight.Bold
                )
            )
            if (picture != null) {
                Image(ImageProvider(picture), null, GlanceModifier.size(28.dp))
            } else {
                Text(emoji, style = TextStyle(fontSize = Type.sectionSize))
            }
        }
        Spacer(GlanceModifier.height(Space.sm))

        Text(
            summary.paymentName,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(AccentInk),
                fontSize = Type.cardTitleSize,
                fontWeight = FontWeight.Bold
            )
        )
        if (summary.hasPayment) {
            Spacer(GlanceModifier.height(Space.xs))
            Row(GlanceModifier.fillMaxWidth()) {
                Text(
                    summary.paymentAmount,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(AccentInk),
                        fontSize = Type.bodySize,
                        fontWeight = FontWeight.Bold
                    )
                )
                Spacer(GlanceModifier.width(Space.sm))
                Text(
                    "${summary.paymentDate} · ${summary.paymentCountdown}",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(AccentInk.copy(alpha = 0.7f)), fontSize = Type.captionSize)
                )
            }
        }

        // The break between the payment and the two standing figures: the pair
        // below answer a different question from the one above them.
        Spacer(GlanceModifier.height(Space.lg))
        Text(
            summary.freeCash,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(AccentInk), fontSize = Type.bodySize, fontWeight = FontWeight.Medium)
        )
        Spacer(GlanceModifier.height(Space.xs))
        Text(
            summary.parcels,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(AccentInk.copy(alpha = 0.7f)), fontSize = Type.captionSize)
        )
    }
}

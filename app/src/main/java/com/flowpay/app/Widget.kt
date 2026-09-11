package com.flowpay.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
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
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
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
            today = LocalDate.now()
        )
        provideContent { WidgetBody(summary) }
    }
}

class FlowPayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlowPayWidget()
}

@Composable
private fun WidgetBody(summary: WidgetSummary) {
    Column(
        GlanceModifier
            .fillMaxSize()
            .background(SurfaceLow)
            .appWidgetBackground()
            .cornerRadius(Radius.card)
            .padding(Space.lg)
            .clickable(actionStartActivity<MainActivity>())
    ) {
        Text(
            "FLOWPAY",
            style = TextStyle(
                color = ColorProvider(Accent),
                fontSize = Type.overlineSize,
                fontWeight = FontWeight.Bold
            )
        )
        Spacer(GlanceModifier.height(Space.md))

        Text(
            summary.paymentName,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(TextPrimary),
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
                        color = ColorProvider(Accent),
                        fontSize = Type.bodySize,
                        fontWeight = FontWeight.Medium
                    )
                )
                Spacer(GlanceModifier.width(Space.sm))
                Text(
                    "${summary.paymentDate} · ${summary.paymentCountdown}",
                    maxLines = 1,
                    style = TextStyle(color = ColorProvider(TextSecondary), fontSize = Type.captionSize)
                )
            }
        }

        // The break between the payment and the two standing figures: the pair
        // below answer a different question from the one above them.
        Spacer(GlanceModifier.height(Space.lg))
        Text(
            summary.freeCash,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(TextPrimary), fontSize = Type.bodySize)
        )
        Spacer(GlanceModifier.height(Space.xs))
        Text(
            summary.parcels,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(TextSecondary), fontSize = Type.captionSize)
        )
    }
}

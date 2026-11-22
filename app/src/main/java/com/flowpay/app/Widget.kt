package com.flowpay.app

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

/**
 * FlowPay on the home screen: the next payment, what the month has left, and
 * whether anything is waiting to be collected.
 *
 * Those three are what the app gets opened to check, and checking them is the
 * part that does not need the app. Everything shown is read from the same
 * SharedPreferences the screens read, through [Store], so the widget cannot
 * drift from what the app would say.
 *
 * Beside the next payment, a round tick marks it paid without opening the app
 * (QuickActions.kt); for a day with several payments it opens Платежі instead.
 */
class FlowPayWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read inside the composition and keyed on the widget's own stamp. A
            // session lives about 45 seconds after each redraw and, while it does,
            // an update only recomposes when the widget's state changes — so a
            // tick tapped right after leaving the app would mark the payment and
            // leave it on the widget. [refreshWidget] moves the stamp.
            val stamp = currentState(WIDGET_STAMP) ?: 0L
            val shown = remember(stamp) { widgetShown(context) }
            WidgetBody(shown)
        }
    }
}

class FlowPayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FlowPayWidget()
}

/** The widget's own state: when it was last asked to read the store again. */
private val WIDGET_STAMP = longPreferencesKey("tc_stamp")

/**
 * Redraws every FlowPay widget from the store.
 *
 * The one way to update the widget after the store changed: an `updateAll` alone
 * is ignored by a session that is still running (see [FlowPayWidget]).
 */
suspend fun refreshWidget(context: Context) {
    try {
        GlanceAppWidgetManager(context).getGlanceIds(FlowPayWidget::class.java).forEach { id ->
            updateAppWidgetState(context, id) { it[WIDGET_STAMP] = System.currentTimeMillis() }
        }
        FlowPayWidget().updateAll(context)
    } catch (stopped: CancellationException) {
        throw stopped
    } catch (_: Exception) {
        // A launcher that refuses the update keeps the last picture until the next.
    }
}

/** Everything the widget draws, read at once. */
private class WidgetShown(
    val summary: WidgetSummary,
    val emoji: String,
    val picture: android.graphics.Bitmap?,
    val tick: WidgetTick,
    val undo: QuickMark?
)

private fun widgetShown(context: Context): WidgetShown {
    val store = Store(context)
    val today = LocalDate.now()
    val pays = store.pays()
    val marks = store.paidMarks(today)
    val income = store.income()
    // The widget never fetches: a rate it cannot refresh is one the app
    // already has, and totalLabel says so out loud when it is missing.
    val rate = store.fxRate().first.sell
    val prefs = TouchPrefs(context)
    // The same «Вільно» as Огляд: «На життя» and the funds in it (MoneyPlan.kt).
    val month = honestBudget(context, store, rate, today)
    val summary = widgetSummary(
        pays = pays,
        orders = store.orders(),
        income = income,
        usdSellRate = rate,
        today = today,
        marks = marks,
        month = month
    ).let {
        // «Ховати суми поза застосунком»: names and dates, no sums.
        if (prefs.hideOutside()) widgetWithoutSums(it, month) else it
    }
    // The next payment's emoji, from the imported pack when there is one —
    // decoded here because a widget draws a bitmap, not a composable glyph.
    val emoji = pays.firstOrNull { it.name == summary.paymentName }?.let { shownEmoji(it) } ?: "🗓️"
    val picture = runCatching {
        java.io.File(java.io.File(context.filesDir, "emoji"), "${emojiKey(emoji)}.png")
            .takeIf { it.isFile }
            ?.let { android.graphics.BitmapFactory.decodeFile(it.path) }
    }.getOrNull()
    // The same next payment the summary names, so the tick is beside what it marks.
    val next = nextPayment(stillOwing(pays, marks, today), today, rate)
    val undo = prefs.widgetUndo()?.let { (mark, day) -> widgetUndo(mark, day, marks, today) }
    return WidgetShown(summary, emoji, picture, widgetTick(next, today), undo)
}

private val PAY_NAME = ActionParameters.Key<String>(EXTRA_PAY_NAME)
private val PAY_MONTH = ActionParameters.Key<String>(EXTRA_PAY_MONTH)

private fun markParameters(mark: QuickMark) = actionParametersOf(PAY_NAME to mark.name, PAY_MONTH to mark.month)

private fun markOf(parameters: ActionParameters): QuickMark? {
    val name = parameters[PAY_NAME] ?: return null
    val month = parameters[PAY_MONTH] ?: return null
    return QuickMark(name, month)
}

/** The tick: the payment marked by the app's own rule, the undo line remembered for today. */
class WidgetMarkPaid : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val mark = markOf(parameters) ?: return
        if (quickMark(context, mark) == QuickOutcome.ADDED) {
            TouchPrefs(context).saveWidgetUndo(mark, LocalDate.now().toEpochDay())
        }
        afterQuickWrite(context)
    }
}

/** «Скасувати» on the widget: the mark it made taken back off. */
class WidgetUndoMark : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val mark = markOf(parameters) ?: return
        quickUnmark(context, mark)
        TouchPrefs(context).saveWidgetUndo(null, 0L)
        afterQuickWrite(context)
    }
}

@Composable
private fun WidgetBody(shown: WidgetShown) {
    val summary = shown.summary
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
        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            val undo = shown.undo
            if (undo != null) {
                // An accidental tap on the home screen has its way back for the
                // rest of the day, in the place the name of the app usually sits.
                Text(
                    widgetUndoLine(undo),
                    GlanceModifier
                        .defaultWeight()
                        .clickable(actionRunCallback<WidgetUndoMark>(markParameters(undo))),
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(AccentInk),
                        fontSize = Type.captionSize,
                        fontWeight = FontWeight.Bold
                    )
                )
            } else {
                Text(
                    "FLOWPAY",
                    GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = ColorProvider(AccentInk.copy(alpha = 0.65f)),
                        fontSize = Type.overlineSize,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
            if (shown.picture != null) {
                Image(ImageProvider(shown.picture), null, GlanceModifier.size(28.dp))
            } else {
                Text(shown.emoji, style = TextStyle(fontSize = Type.sectionSize))
            }
        }
        Spacer(GlanceModifier.height(Space.sm))

        Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(GlanceModifier.defaultWeight()) {
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
                        if (summary.paymentAmount.isNotBlank()) {
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
                        }
                        Text(
                            "${summary.paymentDate} · ${summary.paymentCountdown}",
                            maxLines = 1,
                            style = TextStyle(color = ColorProvider(AccentInk.copy(alpha = 0.7f)), fontSize = Type.captionSize)
                        )
                    }
                }
            }
            WidgetTickButton(shown.tick)
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

/** The round tick beside the next payment, or nothing when there is nothing to mark. */
@Composable
private fun WidgetTickButton(tick: WidgetTick) {
    val (action, said) = when (tick) {
        WidgetTick.None -> return
        is WidgetTick.Mark ->
            actionRunCallback<WidgetMarkPaid>(markParameters(tick.mark)) to "Позначити оплаченим: ${tick.mark.name}"
        // Several on one day: which of them is paid is a question for the app.
        WidgetTick.OpenPayments -> actionStartActivity(
            Intent(LocalContext.current, MainActivity::class.java)
                .setAction(ACTION_OPEN_TAB)
                .putExtra(EXTRA_TAB, TAB_PAYMENTS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        ) to "Відкрити платежі"
    }
    Spacer(GlanceModifier.width(Space.sm))
    Image(
        ImageProvider(R.drawable.widget_tick),
        said,
        GlanceModifier
            .size(40.dp)
            .clickable(action)
            .semantics { contentDescription = said }
    )
}

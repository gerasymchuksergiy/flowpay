package com.flowpay.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp

/**
 * The panel that says whether the app is still working when nobody is looking.
 *
 * It is deliberately styled as the status pill's twin rather than as a card inside
 * the overview list: both are one narrow bar above the screen saying one thing the
 * app wants you to know before you have asked anything, and making them look alike
 * is what stops the second one reading as a stray banner.
 *
 * Unlike the pill, this one is always there. A pill that only appears when
 * something is wrong is right for news; for the question "is the background work
 * alive" the answer "yes, at 10:05 today" is the whole point, and a strip that is
 * only ever seen in a crisis cannot build the habit of glancing at it.
 */
@Composable
fun WorkHealthStrip(line: HealthLine?, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    AnimatedVisibility(
        visible = line != null,
        modifier = modifier,
        // The same springs the status pill opens on, so the two bars that share
        // the top of the screen arrive the same way — and so this one goes still
        // when the phone is set to reduce motion, which Motion handles for both.
        enter = expandVertically(Motion.spatial()) + fadeIn(Motion.effects()),
        exit = shrinkVertically(Motion.spatial()) + fadeOut(Motion.effects())
    ) {
        // Null only while the strip is closing, when there is nothing left to draw.
        line?.let { shown ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Space.screen, vertical = Space.sm)
                    .clip(Radius.pill)
                    .clickable { onOpen() }
                    .background(SurfaceHigh.copy(alpha = 0.92f), Radius.pill)
                    .border(Dp.Hairline, HairLine, Radius.pill)
                    .padding(horizontal = Space.lg, vertical = Space.md),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (shown.alarm) Icons.Default.BatteryAlert else Icons.Default.Autorenew,
                    null,
                    Modifier.size(Space.lg),
                    tint = if (shown.alarm) Negative else Accent
                )
                Spacer(Modifier.width(Space.md))
                Column(Modifier.weight(1f)) {
                    Text(
                        shown.title,
                        color = TextPrimary,
                        fontSize = Type.captionSize,
                        fontWeight = Type.medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        shown.detail,
                        color = TextSecondary,
                        fontSize = Type.captionSize,
                        lineHeight = Type.captionLine,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.ChevronRight, null, Modifier.size(Space.lg), tint = TextDisabled)
            }
        }
    }
}

/**
 * Everything behind the strip: each pass, why the system is holding them, the way
 * out, and when the one daily message arrives.
 *
 * The digest hour lives here rather than on a settings screen because this sheet
 * is already the one place that answers "when does the app do things", and the
 * hour is the same question asked from the other side.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkHealthSheet(
    health: WorkHealth,
    digestHour: Int,
    onHour: (Int) -> Unit,
    onOpenSettings: () -> Unit,
    onClose: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = SurfaceLow,
        contentColor = TextPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = HairLine) }
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.screen)
                .padding(bottom = Space.xxl)
        ) {
            Text(
                "Фонова робота",
                fontSize = Type.sectionSize,
                lineHeight = Type.sectionLine,
                fontWeight = Type.medium
            )
            Text(
                "Ціни, посилки і зведення оновлюються, поки застосунок закритий. " +
                    "Якщо телефон це забороняє, усе виглядає так, ніби нічого не змінилось.",
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )

            Spacer(Modifier.height(Space.lg))
            health.runs.forEach { run ->
                LeaderRow(
                    workLabel(run.key),
                    lastRunLabel(run.atMillis, health.nowMillis),
                    alarm = workState(run, health.nowMillis) == WorkState.STALE
                )
            }

            Spacer(Modifier.height(Space.lg))
            Text(
                "Чому завдання чекає",
                color = TextDisabled,
                fontSize = Type.overlineSize,
                letterSpacing = Type.overlineTracking,
                fontWeight = Type.medium
            )
            Text(
                pendingReasonsLine(health.pendingReasons, health.apiLevel),
                color = TextSecondary,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.xs)
            )

            Spacer(Modifier.height(Space.lg))
            Button(
                onOpenSettings,
                Modifier.fillMaxWidth(),
                shape = Radius.pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Accent,
                    contentColor = AccentInk
                )
            ) { Text("Дозволити автозапуск і роботу в фоні", fontWeight = Type.medium) }

            Spacer(Modifier.height(Space.xl))
            Text(
                "Зведення о",
                color = TextDisabled,
                fontSize = Type.overlineSize,
                letterSpacing = Type.overlineTracking,
                fontWeight = Type.medium
            )
            Spacer(Modifier.height(Space.sm))
            // A scrolling row rather than a segmented control: seven hours do not
            // fit a phone's width as equal segments without the labels colliding.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Space.sm)
            ) {
                DIGEST_HOURS.forEach { hour ->
                    HourChip(hour, hour == digestHour) { onHour(hour) }
                }
            }
            Text(
                "Одне повідомлення на день. Негайно приходить лише те, що не чекає: " +
                    "досягнута цільова ціна і зберігання посилки, яке завтра стає платним.",
                color = TextDisabled,
                fontSize = Type.captionSize,
                lineHeight = Type.captionLine,
                modifier = Modifier.padding(top = Space.md)
            )
        }
    }
}

@Composable
private fun HourChip(hour: Int, selected: Boolean, onPick: () -> Unit) {
    Box(
        Modifier
            .clip(Radius.pill)
            .clickable { onPick() }
            .background(if (selected) Accent else SurfaceHigh, Radius.pill)
            .padding(horizontal = Space.lg, vertical = Space.md)
    ) {
        Text(
            "%02d:00".format(hour),
            color = if (selected) AccentInk else TextSecondary,
            fontSize = Type.captionSize,
            fontWeight = Type.medium,
            maxLines = 1
        )
    }
}

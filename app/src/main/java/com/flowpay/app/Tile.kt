package com.flowpay.app

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * One figure in the notification shade.
 *
 * Different from the home screen widget, and deliberately so: the widget is what
 * you see when you have gone to the home screen to look at it, and the tile is
 * what you reach without leaving whatever you were already doing — mid-chat,
 * mid-shop, about to agree to a price. That is the moment the dollar rate is
 * actually needed, and it is the one moment the home screen is nowhere near.
 *
 * So it carries exactly one number. Tapping swaps which one, because the two
 * questions — what is the dollar at, and what have I got left this month — belong
 * to the same instant and neither is worth a second tile.
 */

const val TILE_RATE = "rate"
const val TILE_FREE = "free"

/** Tapping cycles rather than opening the app: the answer is already on the tile. */
fun nextTileFace(current: String): String =
    if (current == TILE_RATE) TILE_FREE else TILE_RATE

/**
 * What the tile shows.
 *
 * [active] is whether there is a real figure behind the label. A tile drawn as
 * active with a dash on it claims to be working, and the whole value of this
 * surface is that a glance is enough — including a glance that has to tell you
 * the app has nothing yet.
 */
data class TileFace(val label: String, val subtitle: String, val active: Boolean)

fun tileFace(face: String, rate: FxRate, month: Budget): TileFace = when (face) {
    TILE_FREE -> TileFace(
        label = when {
            month.unknown -> "—"
            month.overspent -> money(-month.free)
            else -> money(month.free)
        },
        subtitle = when {
            month.unknown -> "Дохід не вказано"
            month.overspent -> "Бракує до кінця місяця"
            else -> "Вільно на місяць"
        },
        active = !month.unknown
    )
    else -> TileFace(
        label = if (rate.sell > 0) "${rateFigure(rate.sell)} ₴" else "—",
        subtitle = when {
            rate.sell <= 0 -> "Курс ще не завантажено"
            rate.source == SOURCE_NBU -> "Долар · НБУ"
            else -> "Долар · Monobank"
        },
        active = rate.sell > 0
    )
}

class FlowPayTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        draw()
    }

    /**
     * A tap changes the figure instead of opening the app.
     *
     * Opening the app is what the long press already does, and it is the thing
     * this surface exists to avoid.
     */
    override fun onClick() {
        super.onClick()
        val store = Store(applicationContext)
        store.saveTileFace(nextTileFace(store.tileFace()))
        draw()
    }

    private fun draw() {
        val tile = qsTile ?: return
        val store = Store(applicationContext)
        // The tile never fetches. It runs for a second at a time in the shade, and
        // a figure that arrives after the shade closes is no figure at all.
        val rate = store.fxRate().first
        val face = tileFace(
            store.tileFace(),
            rate,
            budget(store.income(), monthlyTotal(store.pays(), rate.sell))
        )
        tile.label = face.label
        tile.state = if (face.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.icon = Icon.createWithResource(applicationContext, R.drawable.ic_tile)
        // Subtitles arrived in Android 10; below that the label carries it alone.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = face.subtitle
        tile.contentDescription = "${face.subtitle}: ${face.label}"
        tile.updateTile()
    }

    companion object {
        /**
         * Asks the shade to redraw the tile after a background pass changed a figure.
         *
         * Only takes effect while the tile is on screen, which is the only time it
         * matters: the rest of the time [onStartListening] reads the same store.
         */
        fun refresh(context: Context) {
            runCatching {
                requestListeningState(
                    context,
                    ComponentName(context, FlowPayTileService::class.java)
                )
            }
        }
    }
}

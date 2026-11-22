package com.flowpay.app

import org.json.JSONObject

/**
 * A corridor for the dollar, and a word when it jumps — «Коридор і сплеск» from the
 * research's Revolut page (4 October 2026).
 *
 * What it replaces: one number, one direction, said once in the morning digest. The
 * owner now gives two optional edges on Monobank's sell rate — «нижче 41,10» and/or
 * «вище 42,00» — and, separately, «Сплеск»: a change of more than one per cent in a
 * day. A light background check ([RateWorker], every hour or so) tells them at once,
 * with «Стежити далі» and «Готово» on the notification.
 *
 * Three rules carried over from the threshold, because each was paid for:
 *
 * - **Only Monobank's sell rate.** The NBU's official figure, which the phone holds
 *   whenever Monobank does not answer, never arms, crosses or fills anything here —
 *   it usually sits below the bank's rate, and a day on one beside a day on the other
 *   is a jump the dollar never made (HANDOFF §11, `refreshUsdRate`).
 * - **A reading has to be current** to say anything about now ([RATE_WATCH_FRESH_MS]).
 * - **Once per crossing.** An edge that has spoken stays quiet until the rate has come
 *   back inside by [REARM_SHARE], so a rate hovering on the line does not ring every
 *   hour.
 *
 * And one thing deliberately not built: «найкращий був 41,20». It is the «могли б
 * зекономити» reproach the owner turned down (§12).
 */

/** A change in a day bigger than this is a «сплеск»: one per cent, the research's figure. */
const val SPIKE_SHARE = 0.01

/**
 * How far back inside an edge the rate has to come before the edge speaks again: a
 * quarter of a per cent, about ten kopecks at 41 ₴ — more than the bank's own
 * hour-to-hour flutter, less than any move worth a sentence.
 */
const val REARM_SHARE = 0.0025

/** A bank reading older than this says nothing about the rate now. */
const val RATE_WATCH_FRESH_MS = 3 * 60 * 60_000L

/** One edge of the corridor. */
data class RateBound(
    /** Hryvnia per dollar. Nought: this edge is not set. */
    val rate: Double = 0.0,
    /**
     * Whether crossing it will be said. False from the moment it has been said — or
     * when it was set already crossed, which the screen shows anyway — until the rate
     * comes back inside by [REARM_SHARE].
     */
    val armed: Boolean = true
) {
    val set: Boolean get() = rate > 0.0
}

data class RateCorridor(
    /** «нижче 41,10»: said when Monobank's sell rate falls below it. */
    val below: RateBound = RateBound(),
    /** «вище 42,00»: said when it rises above it. */
    val above: RateBound = RateBound(),
    /** «Сплеск»: said when the rate moved more than [SPIKE_SHARE] since yesterday. */
    val spike: Boolean = false,
    /** Epoch day the last «сплеск» was said, so a day says it once. */
    val spikeDay: Long = 0L
) {
    /** Anything to watch at all — the background check runs only then. */
    val watching: Boolean get() = below.set || above.set || spike
}

/** What a check found worth saying. */
sealed interface RateNews {
    /** Which notification this is, and which edge «Готово» takes away. */
    val edge: String

    data class Below(val rate: Double, val bound: Double) : RateNews {
        override val edge: String get() = EDGE_BELOW
    }

    data class Above(val rate: Double, val bound: Double) : RateNews {
        override val edge: String get() = EDGE_ABOVE
    }

    data class Spike(val from: Double, val to: Double) : RateNews {
        override val edge: String get() = EDGE_SPIKE
        val percent: Double get() = (to - from) / from * 100
    }
}

const val EDGE_BELOW = "below"
const val EDGE_ABOVE = "above"
const val EDGE_SPIKE = "spike"

/** The corridor after one check, and what to say. */
data class RateCheck(val corridor: RateCorridor, val news: List<RateNews>)

/**
 * One check of [rate] — the reading the phone holds, taken at [rateAt] — against the
 * corridor.
 *
 * [history] is the day-by-day record [appendRate] keeps, Monobank's sell rate only;
 * yesterday's point there is what a «сплеск» is measured from. Without one — the
 * phone was off yesterday — there is no day-over-day to speak of, and nothing is said.
 */
fun checkRate(
    corridor: RateCorridor,
    rate: FxRate,
    rateAt: Long,
    now: Long,
    history: List<PricePoint>,
    today: Long
): RateCheck {
    val quiet = RateCheck(corridor, emptyList())
    // The NBU's figure, no figure, or one too old to be about now: nothing moves.
    // A reading stamped ahead of the clock is a clock that moved back, and counts as
    // current, as it does for [rateIsFresh].
    if (rate.source != SOURCE_MONOBANK || rate.sell <= 0.0 || rateAt <= 0L) return quiet
    if (now - rateAt > RATE_WATCH_FRESH_MS) return quiet
    val sell = rate.sell
    val news = mutableListOf<RateNews>()

    val below = corridor.below.let { edge ->
        when {
            !edge.set -> edge
            edge.armed && sell < edge.rate -> {
                news += RateNews.Below(sell, edge.rate)
                edge.copy(armed = false)
            }
            !edge.armed && sell >= edge.rate * (1 + REARM_SHARE) -> edge.copy(armed = true)
            else -> edge
        }
    }
    val above = corridor.above.let { edge ->
        when {
            !edge.set -> edge
            edge.armed && sell > edge.rate -> {
                news += RateNews.Above(sell, edge.rate)
                edge.copy(armed = false)
            }
            !edge.armed && sell <= edge.rate * (1 - REARM_SHARE) -> edge.copy(armed = true)
            else -> edge
        }
    }
    var spikeDay = corridor.spikeDay
    if (corridor.spike && spikeDay != today) {
        val yesterday = history.lastOrNull { it.day == today - 1 && it.price > 0.0 }?.price
        if (yesterday != null && kotlin.math.abs(sell - yesterday) / yesterday > SPIKE_SHARE) {
            news += RateNews.Spike(yesterday, sell)
            spikeDay = today
        }
    }
    return RateCheck(corridor.copy(below = below, above = above, spikeDay = spikeDay), news)
}

/**
 * Why the corridor as typed cannot be saved, or null when it can. Said in the dialog
 * under the fields, beside a button that will not press.
 *
 * An edge needs Monobank's rate on the phone — the same refusal the threshold made,
 * for the same reason ([rateTargetBlocked]): an edge set against the NBU's figure
 * would be armed or not on the wrong side. The spike alone does not: it compares two
 * bank readings when they exist, and says nothing until then.
 */
fun corridorProblem(below: Double, above: Double, current: FxRate): String? {
    val edges = below > 0.0 || above > 0.0
    return when {
        edges && current.sell <= 0.0 ->
            "Спершу має завантажитись курс Monobank — без нього не видно, з якого боку межа"
        edges && current.source != SOURCE_MONOBANK ->
            "Межі стежать за курсом Monobank, а зараз є лише курс НБУ — оновіть курс трохи пізніше"
        below > 0.0 && above > 0.0 && below >= above -> "Нижня межа має бути меншою за верхню"
        else -> null
    }
}

/**
 * The corridor as typed, armed against the rate on the phone now.
 *
 * An edge set already crossed — «нижче 41,50» typed while the bank sells at 41,30 —
 * starts disarmed: the owner is looking at the rate on the very screen, and a
 * notification about it a minute later would be the app repeating them. It speaks
 * the next time the rate crosses, after coming back.
 */
fun corridorAsSet(below: Double, above: Double, spike: Boolean, current: FxRate, previous: RateCorridor): RateCorridor {
    val sell = current.sell.takeIf { current.source == SOURCE_MONOBANK && it > 0.0 }
    return RateCorridor(
        below = if (below > 0.0) RateBound(below, armed = sell == null || sell >= below) else RateBound(),
        above = if (above > 0.0) RateBound(above, armed = sell == null || sell <= above) else RateBound(),
        spike = spike,
        spikeDay = previous.spikeDay
    )
}

/** «Готово» on a notification: that edge, or the spike, is no longer watched. */
fun withoutEdge(corridor: RateCorridor, edge: String): RateCorridor = when (edge) {
    EDGE_BELOW -> corridor.copy(below = RateBound())
    EDGE_ABOVE -> corridor.copy(above = RateBound())
    EDGE_SPIKE -> corridor.copy(spike = false)
    else -> corridor
}

/**
 * The single threshold of before, as an edge of the corridor: a rise it waited for
 * becomes «вище», a fall «нижче». One already announced waits for the rate to come
 * back inside, the way an edge that has spoken does.
 */
fun corridorFrom(target: RateTarget?): RateCorridor {
    if (target == null || target.rate <= 0.0) return RateCorridor()
    val edge = RateBound(target.rate, armed = target.hitDay <= 0L)
    return if (target.above) RateCorridor(above = edge) else RateCorridor(below = edge)
}

/** What a notification says. The title is the same for all three: «Курс долара». */
fun rateNewsText(news: RateNews): String = when (news) {
    is RateNews.Below ->
        "Долар у Monobank — ${rateFigure(news.rate)} ₴, нижче вашої межі ${rateFigure(news.bound)}"
    is RateNews.Above ->
        "Долар у Monobank — ${rateFigure(news.rate)} ₴, вище вашої межі ${rateFigure(news.bound)}"
    is RateNews.Spike ->
        "Долар за добу ${signedPercent(news.percent)}: ${rateFigure(news.from)} → ${rateFigure(news.to)}"
}

/**
 * The line under the rate chart: what is watched, and what an edge that has already
 * spoken is waiting for.
 */
fun corridorNote(corridor: RateCorridor, rate: FxRate): String {
    if (!corridor.watching) {
        return "Скажу одразу, коли курс вийде за ваші межі або різко зміниться за добу"
    }
    val edges = listOfNotNull(
        corridor.below.takeIf { it.set }?.let { "нижче ${rateFigure(it.rate)}" },
        corridor.above.takeIf { it.set }?.let { "вище ${rateFigure(it.rate)}" }
    )
    val watched = if (rate.source == SOURCE_MONOBANK) "курс" else "курс Monobank"
    val head = when {
        edges.isNotEmpty() && corridor.spike ->
            "Скажу, коли $watched буде ${edges.joinToString(" або ")}, і про сплеск понад 1% за добу"
        edges.isNotEmpty() -> "Скажу, коли $watched буде ${edges.joinToString(" або ")}"
        else -> "Скажу про сплеск — якщо $watched за добу зміниться більше ніж на 1%"
    }
    val waiting = listOfNotNull(
        corridor.below.takeIf { it.set && !it.armed }
            ?.let { "Курс уже нижче ${rateFigure(it.rate)} — наступного разу скажу, коли повернеться вище" },
        corridor.above.takeIf { it.set && !it.armed }
            ?.let { "Курс уже вище ${rateFigure(it.rate)} — наступного разу скажу, коли повернеться нижче" }
    )
    return (listOf(head) + waiting).joinToString(". ")
}

fun corridorJson(corridor: RateCorridor): String = JSONObject()
    .put("b", corridor.below.rate).put("ba", corridor.below.armed)
    .put("a", corridor.above.rate).put("aa", corridor.above.armed)
    .put("s", corridor.spike).put("sd", corridor.spikeDay)
    .toString()

fun corridorOf(text: String): RateCorridor = runCatching {
    val o = JSONObject(text)
    fun rate(key: String) = o.optDouble(key, 0.0).takeIf { it.isFinite() && it > 0.0 } ?: 0.0
    RateCorridor(
        below = RateBound(rate("b"), o.optBoolean("ba", true)),
        above = RateBound(rate("a"), o.optBoolean("aa", true)),
        spike = o.optBoolean("s", false),
        spikeDay = o.optLong("sd", 0L)
    )
}.getOrDefault(RateCorridor())

package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

/**
 * «Сплачено» without opening the app: a round tick on the home-screen widget and
 * up to three buttons under the morning message. Research idea №2 (Copilot
 * Money); the owner said «додай все» on 4 October 2026. Pure logic here; the
 * widget is in Widget.kt, the buttons' receiver in QuickReceiver.kt.
 *
 * Two rules carry the whole feature, and the second has been paid for already:
 *
 * **The mark is the one the app would make.** The same record ([togglePaid]'s,
 * amount and currency copied in) for the month of the charge the widget or the
 * line is about — see [chargeMonth].
 *
 * **Nothing saves a list it read earlier.** The widget and the notification write
 * while the app may be open with its own copy of the marks in memory, which is
 * the class of bug HANDOFF §15 describes: the next tick in the app would save that
 * copy over the mark made from outside. So the outside writes re-read the store
 * under one lock, and the app's own writes are rebased onto the store
 * ([rebaseMarks]) rather than saved over it.
 */

// ------------------------------------------------------------ the mark

/** One payment to mark, and the month the mark is for, fixed when the button was drawn. */
data class QuickMark(val name: String, val month: String)

/**
 * The month a tick beside a charge is about: the month that charge falls in.
 *
 * It is the month [stillOwing] asks about, so a mark made here takes the payment
 * off the widget, the pill and the digest at once and the widget moves on to the
 * next one. Whenever the reminder is asking — the only time the digest offers a
 * button — it is also the month [tickMonth] marks on the payments screen (pinned
 * by a test). Outside the notice period the screen's tick answers for the charge
 * already behind this month while the widget shows the one ahead; the widget
 * marks what it shows, and never takes a mark off.
 */
fun chargeMonth(pay: Pay, today: LocalDate): String = monthKey(nextCharge(pay, today))

/**
 * Whether the store would keep a mark for [month] or prune it on the way in.
 *
 * Asked of [prunePaidMarks] itself, so the two cannot drift: a button that offered
 * to mark an annual fee five months out would write a mark the store drops, and
 * the widget would go on showing the payment it had just "paid".
 */
fun markKept(month: String, today: LocalDate): Boolean =
    prunePaidMarks(listOf(PaidMark("", month, 0.0)), today).isNotEmpty()

/**
 * Marks [pay] paid for [month] exactly as the tick in the app does, and leaves a
 * mark that is already there alone.
 *
 * Never a toggle: a tap from outside the app cannot see what the app did since
 * the button was drawn, and a second tap on a stale widget must not take off the
 * mark the first one made.
 */
fun markPaid(marks: List<PaidMark>, pay: Pay, month: String): List<PaidMark> =
    if (isPaid(marks, pay.name, month)) marks else togglePaid(marks, pay, month)

/** Takes one mark back off, and nothing else. */
fun unmarkPaid(marks: List<PaidMark>, mark: QuickMark): List<PaidMark> =
    marks.filterNot { it.name == mark.name && it.month == mark.month }

/** What one tap from outside the app did. */
enum class QuickOutcome {
    /** The mark was made; it can be taken back. */
    ADDED,

    /** It was already marked — in the app, or by an earlier tap. Nothing to undo. */
    ALREADY,

    /** No payment of that name any more: renamed or deleted since the button was drawn. */
    GONE
}

data class QuickResult(val marks: List<PaidMark>, val outcome: QuickOutcome)

/** One tap on «Сплачено», applied to the marks as the store has them now. */
fun quickMarked(marks: List<PaidMark>, pays: List<Pay>, mark: QuickMark): QuickResult {
    val pay = pays.firstOrNull { it.name == mark.name } ?: return QuickResult(marks, QuickOutcome.GONE)
    if (isPaid(marks, pay.name, mark.month)) return QuickResult(marks, QuickOutcome.ALREADY)
    return QuickResult(markPaid(marks, pay, mark.month), QuickOutcome.ADDED)
}

/**
 * What a screen did to its copy of the marks, applied to the marks as they are now.
 *
 * The screen holds the list it read when it came to the front; the widget and
 * the notification write behind its back. Saving the screen's list would put back
 * what was there before them — a mark made from the widget gone at the next tick
 * in the app. So the change is taken as a difference, [before] → [after], by
 * payment and month, and only that is applied to [current]: a mark the screen
 * added or re-priced is set, a mark it took off is taken off, and every other
 * mark stays as the store has it.
 */
fun rebaseMarks(current: List<PaidMark>, before: List<PaidMark>, after: List<PaidMark>): List<PaidMark> {
    fun key(mark: PaidMark) = mark.name to mark.month
    val was = before.associateBy(::key)
    val now = after.associateBy(::key)
    val removed = was.keys - now.keys
    val set = now.filter { (key, mark) -> was[key] != mark }
    val kept = current.filterNot { key(it) in removed }.map { set[key(it)] ?: it }
    val there = kept.map(::key).toSet()
    return kept + set.filterKeys { it !in there }.values
}

// ------------------------------------------------------------ the widget

/** What the round tick beside the widget's next payment does. */
sealed interface WidgetTick {
    /** One payment that day: the tick marks it. */
    data class Mark(val mark: QuickMark) : WidgetTick

    /** Several on that day («3 платежі»): the tick opens Платежі rather than marking them all. */
    data object OpenPayments : WidgetTick

    /** Nothing it could mark: nothing due, or a charge too far off for a mark to be kept. */
    data object None : WidgetTick
}

/** The tick for the payment the widget shows — the one [widgetSummary] names. */
fun widgetTick(next: NextPayment?, today: LocalDate): WidgetTick {
    val items = next?.items.orEmpty()
    if (items.size > 1) return WidgetTick.OpenPayments
    val pay = items.singleOrNull() ?: return WidgetTick.None
    val month = chargeMonth(pay, today)
    return if (markKept(month, today)) WidgetTick.Mark(QuickMark(pay.name, month)) else WidgetTick.None
}

/**
 * The mark the widget offers to take back, or null.
 *
 * The last one made from the widget, while it is still today and the mark is
 * still there — a mark the app has since taken off is not the widget's to undo,
 * and yesterday's accidental tap is no longer an accident worth a line.
 */
fun widgetUndo(last: QuickMark?, madeOn: Long, marks: List<PaidMark>, today: LocalDate): QuickMark? =
    last?.takeIf { madeOn == today.toEpochDay() && isPaid(marks, it.name, it.month) }

/** «✓ Інтернет · Скасувати» — the line the widget shows while an undo is possible. */
fun widgetUndoLine(mark: QuickMark): String = "✓ ${mark.name} · Скасувати"

// ------------------------------------------------------------ the morning message

/** Android shows three buttons under a notification, and three is what is offered. */
const val DIGEST_BUTTONS = 3

/**
 * The payments the morning message names, as buttons: soonest first, the order
 * the lines are in, at most three.
 *
 * The very reminders [digest] prints — same marks, same holidays — so there is a
 * button only where there is a payment line.
 */
fun digestOffers(
    pays: List<Pay>,
    today: LocalDate,
    holidays: Set<Long>,
    marks: List<PaidMark>
): List<QuickMark> =
    remindersDue(pays, today, holidays, marks)
        .map { QuickMark(it.pay.name, chargeMonth(it.pay, today)) }
        .distinct()
        .filter { markKept(it.month, today) }
        .take(DIGEST_BUTTONS)

/** One button pressed under the morning message. */
data class QuickDone(val mark: QuickMark, val added: Boolean)

/**
 * The morning message as the shade shows it, kept so a button can redraw it.
 *
 * The text is the morning's and stays the morning's: rebuilding it later would
 * not say the same thing (the price lines compare against what this message saw).
 * Only the title and the buttons follow what was pressed.
 */
data class DigestCard(
    val title: String,
    val body: String,
    val offers: List<QuickMark>,
    /** Pressed, oldest first. */
    val done: List<QuickDone> = emptyList()
)

/** A button under the morning message. */
sealed interface DigestButton {
    val mark: QuickMark

    data class Mark(override val mark: QuickMark) : DigestButton

    data class Undo(override val mark: QuickMark) : DigestButton
}

/** «Сплачено · Інтернет» or «Скасувати». */
fun digestButtonLabel(button: DigestButton): String = when (button) {
    is DigestButton.Mark -> "Сплачено · ${button.mark.name}"
    is DigestButton.Undo -> "Скасувати"
}

/** «Позначено: Інтернет» once something was pressed; the morning's own title before. */
fun digestTitle(card: DigestCard): String {
    val last = card.done.lastOrNull() ?: return card.title
    return if (last.added) "Позначено: ${last.mark.name}" else "Уже позначено: ${last.mark.name}"
}

/**
 * The buttons: «Скасувати» for the last mark made here, then the payments still
 * unmarked, three at most. A payment pressed is gone from the row whatever the
 * outcome — marked now, marked before, or no longer on the list.
 */
fun digestButtons(card: DigestCard): List<DigestButton> {
    val undo = card.done.lastOrNull { it.added }?.let { DigestButton.Undo(it.mark) }
    val pressed = card.done.map { it.mark }.toSet()
    val left = card.offers.filterNot { it in pressed }.map { DigestButton.Mark(it) }
    return (listOfNotNull(undo) + left).take(DIGEST_BUTTONS)
}

/**
 * The card after «Сплачено · …». A second press of the same button — a double
 * tap lands on the old button before the shade redraws — finds the mark already
 * there; it is still the one this message made, so «Скасувати» stays.
 */
fun digestPressed(card: DigestCard, mark: QuickMark, added: Boolean): DigestCard {
    val earlier = card.done.firstOrNull { it.mark == mark }
    return card.copy(done = card.done.filterNot { it.mark == mark } + QuickDone(mark, added || earlier?.added == true))
}

/** The card after «Скасувати»: as if that button had never been pressed. */
fun digestUndone(card: DigestCard, mark: QuickMark): DigestCard =
    card.copy(done = card.done.filterNot { it.mark == mark })

/** A payment that is no longer on the list: its button simply goes. */
fun digestGone(card: DigestCard, mark: QuickMark): DigestCard =
    card.copy(offers = card.offers - mark, done = card.done.filterNot { it.mark == mark })

// Stored under the SharedPreferences key `tc_digest`. A view of this morning, not
// app data: never in the backup.

fun quickMarkJson(mark: QuickMark): JSONObject = JSONObject().put("tcn", mark.name).put("tcm", mark.month)

fun quickMarkOf(o: JSONObject): QuickMark? {
    val name = o.optString("tcn")
    val month = o.optString("tcm")
    return if (name.isBlank() || monthKeyDate(month) == null) null else QuickMark(name, month)
}

fun digestCardJson(card: DigestCard): JSONObject = JSONObject()
    .put("tct", card.title)
    .put("tcb", card.body)
    .put("tco", JSONArray().apply { card.offers.forEach { put(quickMarkJson(it)) } })
    .put("tcd", JSONArray().apply { card.done.forEach { put(quickMarkJson(it.mark).put("tca", it.added)) } })

fun digestCardOf(o: JSONObject): DigestCard {
    fun marks(key: String): List<JSONObject> {
        val array = o.optJSONArray(key) ?: return emptyList()
        return (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }
    return DigestCard(
        title = o.optString("tct"),
        body = o.optString("tcb"),
        offers = marks("tco").mapNotNull(::quickMarkOf),
        done = marks("tcd").mapNotNull { item -> quickMarkOf(item)?.let { QuickDone(it, item.optBoolean("tca")) } }
    )
}

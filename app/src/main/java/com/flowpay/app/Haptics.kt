package com.flowpay.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/**
 * Everything this app is allowed to make the phone feel, named by what it means.
 *
 * The budget is five and the list below is the whole of it. That is a limit rather
 * than a starting point: Google's own guidance on this is unusually blunt — given
 * the choice between buzzy haptics and no haptics for touch feedback, choose no
 * haptics — and the failure mode is not a crash but a phone that is tiresome to
 * hold. A vibration on every tap stops being feedback within a day and becomes the
 * texture of the app, at which point the two taps that actually mattered are
 * indistinguishable from the two hundred that did not.
 *
 * So: nothing on an ordinary tap, nothing on opening the app, nothing on a scroll.
 * A haptic here marks a state change the person caused and cares about, or news the
 * app is reporting that landed while they were looking at it.
 *
 * The types are semantic, not waveforms, and that is the point. [HapticFeedbackType]
 * is routed by Compose through `HapticFeedbackConstantsCompat`, which substitutes a
 * constant the running platform actually has and returns without vibrating when it
 * has none — so at this app's floor of API 26 every call below resolves to a real
 * feedback constant from the days before the semantic ones existed:
 *
 *  - [HapticFeedbackType.SegmentTick], [HapticFeedbackType.ToggleOn] and
 *    [HapticFeedbackType.GestureThresholdActivate] fall to `CONTEXT_CLICK` (API 23),
 *  - [HapticFeedbackType.ToggleOff] falls to `CLOCK_TICK` (API 21),
 *  - [HapticFeedbackType.Confirm] falls to `VIRTUAL_KEY`,
 *  - [HapticFeedbackType.Reject] falls to `LONG_PRESS`.
 *
 * None of them degrade to silence on this phone, and none of them can throw. What
 * they must never become is `Vibrator.vibrate` with a duration or a pattern: that
 * is the buzzy path, it ignores the system's own haptic settings, and it is what
 * the guidance above is written against.
 */
@Immutable
class Touch internal constructor(private val feedback: HapticFeedback) {

    /**
     * Something the person asked for genuinely landed: a wish saved, an expense
     * corrected, a watched price actually reaching its target while they looked.
     *
     * Not for "the app received your tap" — the screen already says that.
     */
    fun landed() = feedback.performHapticFeedback(HapticFeedbackType.Confirm)

    /**
     * It did not land: a save that could not complete, a rate or a page the network
     * refused. Only when the person asked for the thing that failed — a fetch the
     * app started on its own is not news worth buzzing about.
     */
    fun refused() = feedback.performHapticFeedback(HapticFeedbackType.Reject)

    /**
     * A real two-state switch changed state. The two directions feel different on
     * purpose, which is the whole reason to use these two constants rather than one
     * tick: marking something paid and un-marking it are opposite acts, and a single
     * shared buzz would say only that something happened.
     */
    fun switched(on: Boolean) = feedback.performHapticFeedback(
        if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff
    )

    /**
     * A gesture has just passed the point where it becomes itself.
     *
     * At the threshold, not at the release. The decision is made under a finger
     * that is still down, and that is the moment worth reporting: felt there, it
     * tells you the thing is now yours to drag; felt on release, it tells you only
     * what you already saw happen.
     *
     * One site: the press-and-hold that turns a touch on the price chart into a
     * scrub rather than a scroll. Without it there is no way to know the chart has
     * taken the gesture except by moving and seeing whether anything follows.
     */
    fun committed() =
        feedback.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)

    /**
     * One data point crossed while dragging along the price history.
     *
     * This is the highest-value haptic available to an app like this one, and what
     * `SegmentTick` was added to the platform for: dragging across the chart, the
     * ticks turn a smooth slide into a series of discrete readings, so the hand
     * counts the days the eye is reading. It is also the one place where many
     * vibrations in a row is right rather than noise, because each one stands for a
     * separate fact under the finger.
     *
     * It must be driven by the index of the point, never by the pixel position: one
     * tick per reading is the effect, one per frame is the buzz. The single caller,
     * the scrub in `PriceChart`, holds the last index and only calls this when it
     * changes — a finger resting still on one point stays silent.
     */
    fun stepped() = feedback.performHapticFeedback(HapticFeedbackType.SegmentTick)
}

/** The phone, as this app is allowed to address it. */
@Composable
fun rememberTouch(): Touch {
    val feedback = LocalHapticFeedback.current
    return remember(feedback) { Touch(feedback) }
}

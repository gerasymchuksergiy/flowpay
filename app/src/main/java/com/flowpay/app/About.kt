package com.flowpay.app

/**
 * How much of «Про товар» shows at once.
 *
 * The block answers "what is this thing", which is why it sits first on the wish
 * page and why it is not itself allowed to be a closed heading — a page whose top
 * line is a fold you have to open to learn what you are looking at has moved the
 * wall rather than removed it. So the folding happens *inside* it, and only where
 * the shop actually published enough to need folding.
 *
 * Two different treatments, because the two long things inside are long in
 * different ways. A description is one run of prose and reads perfectly well cut
 * off mid-thought, so it gets a clamp: four lines and a «більше». Specifications
 * are a list of rows and a half-shown row is a lie about the table, so they get a
 * fold — but only once there are enough of them to be worth hiding.
 */

/**
 * Lines of the shop's description shown before it is cut.
 *
 * Four rather than two: the point of a clamp over a bare heading is that you can
 * tell from what is showing whether the rest is worth opening, and one sentence of
 * shop prose is rarely enough to tell. Parsing.kt already caps a stored
 * description at 600 characters, so the fully open state stays a paragraph.
 */
const val ABOUT_CLAMP_LINES = 4

/**
 * Specification rows shown without a fold.
 *
 * Folding two rows away costs a tap to see less than the fold itself occupies, and
 * a heading over them says nothing the rows do not. Five is where a block stops
 * being something the eye takes in whole and starts being a list to be read, and
 * that is the point it earns a heading of its own.
 */
const val SPEC_FOLD_LIMIT = 5

/** Whether this many specifications are worth putting behind a heading. */
fun foldsSpecs(count: Int): Boolean = count > SPEC_FOLD_LIMIT

/**
 * The word on the clamp.
 *
 * Lower case and bare rather than «Показати більше»: it sits directly under the
 * text it belongs to and is the only tappable thing there, so the verb is
 * already carried by the position.
 */
fun moreLabel(expanded: Boolean): String = if (expanded) "менше" else "більше"

/**
 * What the shut «Характеристики» heading is holding, as a bare count.
 *
 * Deliberately not [specsLabel], which would make the heading read
 * «Характеристики · 14 характеристик». The title has already said what they are;
 * the only thing left to say is how many.
 *
 * [Int.toString] rather than any formatter: this is a small count, and reaching
 * for a format string here is how a locale gets into a place that has no business
 * having one.
 */
fun specsCountLabel(count: Int): String = count.toString()

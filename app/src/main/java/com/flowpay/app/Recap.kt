package com.flowpay.app

import java.time.LocalDate

/**
 * The month, handed back as something worth reading.
 *
 * Monthly rather than yearly. A year-in-review needs a year and this phone has two
 * months of data, so the yearly version would be a deck of empty cards; the month
 * is also the cycle the whole app already thinks in — billing, budget, the record
 * of what was paid — and it produces an instance immediately while still being an
 * event rather than a screen.
 *
 * Three rules decide everything below.
 *
 * **It never interrupts.** It is built when it is asked for and waits until the
 * app is next opened. The cautionary tale is a photo app whose Memories could jab
 * you in the ribs with a painful one, and whose eventual fix was to separate the
 * artefact from the interruption. Everything non-urgent here already goes into one
 * morning digest, and a recap is not urgent at all.
 *
 * **Nothing volume-dependent is ported.** No percentiles, no "top N%", no
 * population-normalised archetype: those are computed across millions of people,
 * and at one person any percentile shown would be fabricated outright. What
 * survives at one person is a superlative from their own history, a comparison
 * against their own past months, an unexpected-but-true specific, an absurd but
 * accurate unit conversion, and a label chosen for being specific rather than for
 * being big.
 *
 * **Thin data is the main case, not the degraded one.** A deck that gates each
 * card on having enough history is technically honest and emotionally terrible —
 * it tells someone with a real account that they were not interesting enough. The
 * bank that handled this best took the third of its customers who fell into one
 * catch-all bucket and split them into five distinct outcomes rather than write
 * generic copy. So every slot here is a chain: if the first card cannot be filled,
 * a different card takes its place, and a card is never shown empty and never
 * shown as "не вистачає даних".
 *
 * And one constraint specific to money. A recap of spending is structurally closer
 * to the one people found humiliating — because it quantified behaviour they
 * regretted — than to the music one, where time spent listening is never felt as
 * wasted. So the deck leads with restraint rather than with spending: the drops
 * caught, the thing waited for, the raise noticed, what survives the standing
 * costs. [PurchaseReview.overpaid] is never shown here. "Ти переплатив" is a
 * reproach, and a reproach the person meets every month is one they stop opening.
 */

/** Which card this is. Named so a test can ask for one by name. */
enum class RecapKind {
    /** Always first: what was under watch, before any figure. */
    OPENING,

    /** Watched prices that fell during the month. */
    DROPS_CAUGHT,

    /** Watched, and nothing moved. Still an answer. */
    PRICES_HELD,

    /** The thing wanted longest and still not bought. */
    LONGEST_WAIT,

    /** The dearest thing on the list, for a list nothing dates. */
    DEAREST_WISH,

    /** Bought at the best price the tracker ever saw. */
    PATIENCE_PAID,

    /** The cheapest thing bought, stated without a verdict. */
    CHEAPEST_BOUGHT,

    /** This month's standing costs against last month's. */
    MONTH_ON_MONTH,

    /** What survives the standing costs. */
    COMMITTED_SHARE,

    /** The standing costs alone, when there is no income to measure them against. */
    STANDING_COSTS,

    /** A year of standing costs, in wishlist items. */
    YEAR_IN_WISHES,

    /** The same year, in dollars. */
    YEAR_IN_DOLLARS,

    /** A subscription that changed its price and did not get away with it. */
    SUB_PRICE_MOVED,

    /** A free period that ran out this month. */
    TRIAL_ENDED,

    /** Nothing moved, which for a subscription is the good outcome. */
    SUBS_STEADY,

    /** The reveal: the most specific label that fired. */
    LABEL
}

data class RecapCard(
    val kind: RecapKind,
    /** The small line above, naming what the card is about. */
    val overline: String,
    /** The phrase or figure the card exists to deliver. */
    val headline: String,
    /** The sentence under it. Empty where the headline says it all. */
    val detail: String = ""
)

data class Recap(
    /** "2026-09" */
    val month: String,
    /** "Вересень 2026" */
    val title: String,
    val cards: List<RecapCard>
) {
    /** Too little happened to be worth an event. The caller shows nothing at all. */
    val empty: Boolean get() = cards.size < MIN_RECAP_CARDS
}

/**
 * Below this the deck is not a recap, it is an opening and a label.
 *
 * Four, because the two that always fire say nothing on their own: an app with
 * nothing in it would otherwise produce a monthly ceremony about nothing, which is
 * the same failure as a daily "нічого нового" notification.
 */
const val MIN_RECAP_CARDS = 4

/**
 * The ceiling on the deck.
 *
 * The music one grew to about sixteen cards and had to add a speed control and a
 * jump-back, which is a tacit admission that it was too long. Eight is the ceiling
 * here, not the target.
 */
const val MAX_RECAP_CARDS = 8

/** The month a recap is for: the one that has ended. */
fun recapMonth(today: LocalDate): String =
    monthKey(today.withDayOfMonth(1).minusMonths(1))

/**
 * The month whose recap is waiting to be read, or null when none is.
 *
 * Compared as text, which for "2026-09" sorts as time. A recap already opened is
 * never offered again; one never opened stays waiting, because it is an artefact
 * rather than an announcement and an artefact does not expire at midnight.
 */
fun recapDue(lastSeen: String, today: LocalDate): String? =
    recapMonth(today).takeIf { it > lastSeen }

/** The one line that invites the deck, wherever it is offered from. */
fun recapInvite(recap: Recap): String = "${recap.title} — ${cardsLabel(recap.cards.size)}"

/** Ukrainian plural for how many price falls were caught. Neuter, so "5 падінь". */
fun dropsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "падінь"
        last in 1..4 -> "падіння"
        else -> "падінь"
    }
    return "$count $word ціни"
}

/** Ukrainian plural for how many cards the deck holds. */
fun cardsLabel(count: Int): String {
    val lastTwo = count % 100
    val last = count % 10
    val word = when {
        lastTwo in 11..14 -> "карток"
        last == 1 -> "картка"
        last in 2..4 -> "картки"
        else -> "карток"
    }
    return "$count $word"
}

/**
 * What a wish cost on [day], as far as its own history knows.
 *
 * Nought when nothing was recorded that early, and nought means "no basis" rather
 * than "free": a wish added halfway through the month has no price from before it,
 * and reporting its whole price as a fall would be the worst kind of invention.
 */
private fun priceOn(wish: Wish, day: Long): Double =
    wish.history.filter { it.price > 0.0 && it.day in 1..day }.lastOrNull()?.price ?: 0.0

/** A fall over the month, in hryvnia. Nought where there was none or none can be known. */
private fun fellBy(wish: Wish, firstDay: Long, lastDay: Long): Double {
    val before = priceOn(wish, firstDay - 1)
    val after = priceOn(wish, lastDay)
    if (before <= 0.0 || after <= 0.0) return 0.0
    return (before - after).coerceAtLeast(0.0)
}

/**
 * The whole deck, built from what is on the phone.
 *
 * Every slot is a chain of candidates and contributes at most one card. That is
 * the whole of the thin-data design: a card that cannot be filled is replaced by a
 * different card rather than gated out, so the deck stays a deck.
 */
fun monthlyRecap(
    wishes: List<Wish>,
    pays: List<Pay>,
    orders: List<Order>,
    marks: List<PaidMark>,
    month: String,
    today: LocalDate,
    income: Double,
    usdSellRate: Double
): Recap {
    val start = monthKeyDate(month) ?: today.withDayOfMonth(1)
    val firstDay = start.toEpochDay()
    val lastDay = start.plusMonths(1).minusDays(1).toEpochDay()

    val cards = listOfNotNull(
        opening(wishes, pays, month),
        whatTheWatchingCaught(wishes, firstDay, lastDay),
        superlative(wishes, lastDay),
        purchase(orders, firstDay, lastDay),
        theMonthItself(pays, marks, month, today, income, usdSellRate),
        conversion(wishes, pays, today, usdSellRate),
        subscriptions(pays, firstDay, lastDay),
        label(wishes, pays, orders, marks, month, firstDay, lastDay)
    ).take(MAX_RECAP_CARDS)

    return Recap(month = month, title = monthTitle(month), cards = cards)
}

/**
 * The build-up.
 *
 * It names what was being watched and no figure at all, because the card's job is
 * to say what the deck is about before the deck starts making claims. Null on an
 * app with nothing in it, which is what keeps [Recap.empty] from having to be a
 * special case further down.
 */
private fun opening(wishes: List<Wish>, pays: List<Pay>, month: String): RecapCard? {
    if (wishes.isEmpty() && pays.isEmpty()) return null
    val watched = listOfNotNull(
        positionsLabel(wishes.size).takeIf { wishes.isNotEmpty() },
        paymentsLabel(pays.size).takeIf { pays.isNotEmpty() }
    ).joinToString(" · ")
    return RecapCard(
        kind = RecapKind.OPENING,
        overline = monthTitle(month),
        // Attention paid, not money gone. Everything after this is evidence for
        // that sentence rather than an account of what was spent.
        headline = "Місяць уваги",
        detail = watched
    )
}

/** What watching the prices actually caught. */
private fun whatTheWatchingCaught(
    wishes: List<Wish>,
    firstDay: Long,
    lastDay: Long
): RecapCard? {
    val fell = wishes.map { it to fellBy(it, firstDay, lastDay) }.filter { it.second > 0.0 }
    if (fell.isNotEmpty()) {
        val total = fell.sumOf { it.second }
        return RecapCard(
            kind = RecapKind.DROPS_CAUGHT,
            overline = "Спіймано",
            // "Подешевшало:" and then the count, the same shape the digest uses.
            // A past-tense verb agreeing with a counted noun is where an app comes
            // out reading as machine-translated: "1 позиція подешевшало" is wrong
            // in gender and "2 позиції подешевшали" in one more place again.
            headline = "Подешевшало: ${positionsLabel(fell.size)}",
            detail = "Разом на ${money(total)}, поки ти на них не дивився"
        )
    }
    if (wishes.isEmpty()) return null
    // Not "не вистачає даних". A month in which nothing moved is a fact about the
    // month, and saying it plainly is the difference between a recap that reports
    // and one that tells you that you were not interesting enough.
    return RecapCard(
        kind = RecapKind.PRICES_HELD,
        overline = "Тиша",
        headline = "Ціни не зрушили",
        detail = "${positionsLabel(wishes.size)} під наглядом, і жодна не подешевшала. Теж відповідь."
    )
}

/** A superlative out of the person's own list, which needs no one else to exist. */
private fun superlative(wishes: List<Wish>, lastDay: Long): RecapCard? {
    val longest = wishes.mapNotNull { wish -> wantedDays(wish, lastDay)?.let { wish to it } }
        .maxByOrNull { it.second }
    if (longest != null && longest.second >= 1) {
        val (wish, days) = longest
        return RecapCard(
            kind = RecapKind.LONGEST_WAIT,
            overline = "Найдовше чекання",
            headline = wish.name,
            detail = "У списку ${daysLabel(days)} і досі не куплено"
        )
    }
    // Nothing on the list can be dated, so the superlative changes rather than the
    // slot going empty: a price needs no history to be the largest.
    val dearest = wishes.filter { wishGoal(it) > 0.0 }.maxByOrNull { wishGoal(it) }
        ?: return null
    return RecapCard(
        kind = RecapKind.DEAREST_WISH,
        overline = "Найдорожче бажання",
        headline = dearest.name,
        detail = money(wishGoal(dearest))
    )
}

/**
 * A purchase, and only ever the flattering half of the verdict.
 *
 * [PurchaseVerdict.HASTY] exists and has its own screen, which is where a person
 * goes looking for it. It is not put in front of them once a month.
 */
private fun purchase(orders: List<Order>, firstDay: Long, lastDay: Long): RecapCard? {
    val closed = orders.filter { it.archivedDay in firstDay..lastDay }
    val patient = closed.map { it to purchaseReview(it.paid, it.lowestSeen) }
        .filter { it.second.verdict == PurchaseVerdict.PATIENT }
        // The one that beat its own record by the most.
        .minByOrNull { it.first.paid - it.second.lowestSeen }
    if (patient != null) {
        val (order, review) = patient
        return RecapCard(
            kind = RecapKind.PATIENCE_PAID,
            overline = "Чекати було варто",
            headline = order.name,
            detail = "Заплачено ${money(review.paid)} — найнижче за весь час спостережень"
        )
    }
    val cheapest = closed.filter { it.paid > 0.0 }.minByOrNull { it.paid } ?: return null
    return RecapCard(
        kind = RecapKind.CHEAPEST_BOUGHT,
        overline = "Найдешевша покупка",
        headline = cheapest.name,
        detail = money(cheapest.paid)
    )
}

/**
 * The month against itself.
 *
 * The comparison instinct, aimed where it is honest at one person: their own last
 * month. It is stronger without an audience precisely because there is nobody to
 * be better than — the only thing it can mean is whether something changed.
 */
private fun theMonthItself(
    pays: List<Pay>,
    marks: List<PaidMark>,
    month: String,
    today: LocalDate,
    income: Double,
    usdSellRate: Double
): RecapCard? {
    val previous = monthKeyDate(month)?.minusMonths(1)?.let { monthKey(it) }
    val now = monthRecord(pays, marks, month, today, usdSellRate)
    val before = previous?.let { monthRecord(pays, marks, it, today, usdSellRate) }
    if (before != null && before.paid.total > 0.0 && now.paid.total > 0.0) {
        val difference = now.paid.total - before.paid.total
        return RecapCard(
            kind = RecapKind.MONTH_ON_MONTH,
            overline = "Проти минулого місяця",
            headline = when {
                kotlin.math.abs(difference) < 0.01 -> "Рівно стільки ж"
                difference > 0 -> "На ${money(difference)} більше"
                else -> "На ${money(-difference)} менше"
            },
            detail = "${before.title} ${totalLabel(before.paid)} → " +
                "${now.title} ${totalLabel(now.paid)}"
        )
    }
    // Nothing to compare against, so the same figures answer the other question:
    // not what changed, but what survives them.
    val bar = committedOf(budget(income, monthlyTotal(pays, usdSellRate, today)))
    if (bar.state != CommittedState.UNKNOWN && bar.committed > 0.0) {
        return RecapCard(
            kind = RecapKind.COMMITTED_SHARE,
            overline = "Що лишається",
            headline = committedHeadline(bar),
            detail = committedDetail(bar)
        )
    }
    if (pays.isEmpty()) return null
    // No income, so no share and no denominator invented for one. The standing
    // costs are a real figure on their own.
    val yearly = yearlyTotal(pays, usdSellRate, today)
    return RecapCard(
        kind = RecapKind.STANDING_COSTS,
        overline = "Постійні витрати",
        headline = paymentsLabel(pays.size),
        detail = "${totalLabel(yearly)} на рік"
    )
}

/**
 * The absurd but accurate conversion.
 *
 * This dataset is unusually good for it: a year of standing costs and a wishlist
 * are the same currency, so the multiple is arithmetic rather than analogy. It is
 * not a judgement — nothing here says the subscriptions should have been the
 * headphones — it is the same number in a unit the person picked themselves.
 */
private fun conversion(
    wishes: List<Wish>,
    pays: List<Pay>,
    today: LocalDate,
    usdSellRate: Double
): RecapCard? {
    val yearly = yearlyTotal(pays, usdSellRate, today)
    if (yearly.total <= 0.0) return null
    val dearest = wishes.filter { wishGoal(it) > 0.0 }.maxByOrNull { wishGoal(it) }
    if (dearest != null) {
        val goal = wishGoal(dearest)
        return RecapCard(
            kind = RecapKind.YEAR_IN_WISHES,
            overline = "Рік постійних витрат — це",
            headline = "${figure(yearly.total / goal, 1)} × «${dearest.name}»",
            detail = "${totalLabel(yearly)} на рік, ${money(goal)} за бажання"
        )
    }
    // No wishlist to convert into, so the other unit this app actually knows.
    if (usdSellRate <= 0.0) return null
    return RecapCard(
        kind = RecapKind.YEAR_IN_DOLLARS,
        overline = "Рік постійних витрат — це",
        headline = dollars(kotlin.math.round(yearly.total / usdSellRate)),
        detail = "за курсом ${rateFigure(usdSellRate)}"
    )
}

/** What the subscriptions themselves did, with the good outcome said out loud. */
private fun subscriptions(pays: List<Pay>, firstDay: Long, lastDay: Long): RecapCard? {
    val moved = pays.mapNotNull { pay -> lastAmountChange(pay)?.let { pay to it } }
        .filter { it.second.day in firstDay..lastDay }
        .maxByOrNull { kotlin.math.abs(it.second.percent) }
    if (moved != null) {
        val (pay, change) = moved
        return RecapCard(
            kind = RecapKind.SUB_PRICE_MOVED,
            overline = if (change.raised) "Подорожчала — і не тихо" else "Подешевшала",
            headline = pay.name,
            detail = amountMoveLine(pay).orEmpty()
        )
    }
    val ended = pays.filter { it.trialEnd in firstDay..lastDay }.minByOrNull { it.trialEnd }
    if (ended != null) {
        return RecapCard(
            kind = RecapKind.TRIAL_ENDED,
            overline = "Безкоштовне скінчилось",
            headline = ended.name,
            detail = "Пробний період закінчився · тепер ${amountLabel(ended.amount, ended.currency)}"
        )
    }
    if (pays.isEmpty()) return null
    return RecapCard(
        kind = RecapKind.SUBS_STEADY,
        overline = "Тримають ціну",
        headline = "Жодна не подорожчала",
        detail = "${paymentsLabel(pays.size)} за той самий рахунок, що й місяць тому"
    )
}

/**
 * The reveal: a label, chosen for being the most specific one that fired.
 *
 * The bank this is taken from assigned each customer whichever era the fewest
 * people qualified for, explicitly to maximise the feeling of being unusual — the
 * label was picked for being surprising, not for being the biggest number. At one
 * person there is nobody to count, so it is earned differently: the conditions are
 * written in order of how particular they are, and the first that fires wins.
 * "Патієнт" for the wish tracked ninety days and never bought is honest at one
 * person in a way no percentile could be.
 *
 * The last two are catch-alls and are still specific, because a generic answer is
 * exactly what a catch-all must not be.
 */
private fun label(
    wishes: List<Wish>,
    pays: List<Pay>,
    orders: List<Order>,
    marks: List<PaidMark>,
    month: String,
    firstDay: Long,
    lastDay: Long
): RecapCard {
    fun card(name: String, earned: String) =
        RecapCard(RecapKind.LABEL, "Звання місяця", name, earned)

    val patient = wishes.mapNotNull { wish -> wantedDays(wish, lastDay)?.let { wish to it } }
        .filter { it.second >= PATIENT_DAYS }
        .maxByOrNull { it.second }
    if (patient != null) {
        return card(
            "Патієнт",
            "«${patient.first.name}» у списку ${daysLabel(patient.second)} і досі не куплено"
        )
    }

    val sniped = orders.filter { it.archivedDay in firstDay..lastDay }
        .firstOrNull { purchaseReview(it.paid, it.lowestSeen).verdict == PurchaseVerdict.PATIENT }
    if (sniped != null) {
        return card("Снайпер", "«${sniped.name}» куплено за найнижчою ціною, яку бачив трекер")
    }

    val caught = pays.mapNotNull { pay -> lastAmountChange(pay)?.let { pay to it } }
        .firstOrNull { it.second.day in firstDay..lastDay }
    if (caught != null) {
        return card(
            "Ревізор",
            "${caught.first.name} змінила ціну, і це не пройшло непоміченим"
        )
    }

    val falls = wishes.count { fellBy(it, firstDay, lastDay) > 0.0 }
    if (falls >= HUNTER_DROPS) {
        return card("Мисливець", "За місяць спіймано ${dropsLabel(falls)}")
    }

    val due = monthKeyDate(month)?.let { start -> pays.filter { chargesIn(it, start) } }.orEmpty()
    if (due.isNotEmpty() && due.all { isPaid(marks, it.name, month) }) {
        return card("Бухгалтер", "Усі ${paymentsLabel(due.size)} цього місяця позначені")
    }

    if (wishes.size >= CARTOGRAPHER_POSITIONS) {
        return card("Картограф", "${positionsLabel(wishes.size)} під наглядом одночасно")
    }

    if (wishes.isNotEmpty() || pays.isNotEmpty()) {
        return card(
            "Спостерігач",
            "Місяць без різких рухів. Записано все одно."
        )
    }

    // The genuinely empty month still gets its own sentence rather than a shrug.
    return card("Початок", "Наступного місяця вже буде з чим порівняти")
}

/** Ninety days of wanting the same thing without buying it. */
const val PATIENT_DAYS = 90

/** Falls in one month that stop being luck. */
const val HUNTER_DROPS = 3

/** A wishlist this long is a map rather than a list. */
const val CARTOGRAPHER_POSITIONS = 10

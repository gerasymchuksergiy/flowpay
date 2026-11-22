package com.flowpay.app

import java.time.LocalDate

/**
 * «Поділись листом про підписку — FlowPay запам'ятає» (research, Rocket Money):
 * an e-mail, an SMS or a Viber message about a subscription, shared into the app
 * or pasted from the clipboard, fills «Новий платіж» — name, amount, date,
 * rhythm, the end of a free trial. Nothing is saved until the owner confirms.
 * No mail access, no model: the reading is these rules, on the phone.
 *
 * The risk the research named is misrouting in both directions, and the rules
 * are shaped by it. Such a letter almost always carries a link, so this is asked
 * before the link check, or the letter would become a wish. And a product page
 * «від 499 ₴/міс» (instalments) must not become a subscription — so a letter has
 * to use a subscription's own words (підписка, пробний, абонплата, trial,
 * renew…), outside its links, and has to say it recurs.
 */

/** What a letter about a subscription says, ready for «Новий платіж». */
data class SubscriptionDraft(
    val name: String,
    /** Nought when the letter names no price: the form leaves the box empty. */
    val amount: Double,
    val currency: String,
    /** The day of the month it is charged on, or null when the letter names no date. */
    val day: Int?,
    /** [Pay.billingMonth]: nought for monthly, the charge's month for a yearly fee. */
    val billingMonth: Int,
    /** Epoch day a free trial ends — the first charge — or nought. */
    val trialEnd: Long,
    /** The date the letter's price applies from, when it names one. */
    val from: LocalDate?
)

/** What a shared letter turned out to be about. */
sealed interface SharedLetter {
    val draft: SubscriptionDraft

    /** Nothing on the list is named: «Новий платіж», filled in. */
    data class NewPayment(override val draft: SubscriptionDraft) : SharedLetter

    /** A payment on the list at another price: «Оновити ціну з 1 лист.: 200 → 250 ₴». */
    data class PriceChange(val pay: Pay, override val draft: SubscriptionDraft) : SharedLetter

    /** A payment on the list at the same price: nothing to do but say so. */
    data class SamePrice(val pay: Pay, override val draft: SubscriptionDraft) : SharedLetter
}

/**
 * The letter against the list: a payment it names, or a new one.
 *
 * A price is compared only in the payment's own currency; a letter in dollars
 * about a hryvnia payment is drawn as a new payment for the owner to judge.
 */
fun subscriptionLetter(text: String?, pays: List<Pay>, today: LocalDate): SharedLetter? {
    val draft = parseSubscription(text, today) ?: return null
    val pay = paymentNamedIn(text.orEmpty(), pays) ?: return SharedLetter.NewPayment(draft)
    val named = draft.copy(name = pay.name)
    return when {
        draft.amount <= 0.0 || draft.currency != pay.currency.ifBlank { UAH } -> SharedLetter.NewPayment(draft)
        kotlin.math.abs(draft.amount - pay.amount) < 0.005 -> SharedLetter.SamePrice(pay, named)
        else -> SharedLetter.PriceChange(pay, named)
    }
}

/** The letter as a draft, or null when it is not about a subscription at all. */
fun parseSubscription(text: String?, today: LocalDate): SubscriptionDraft? {
    if (text.isNullOrBlank()) return null
    // Links and addresses say nothing about a subscription — «megogo.net/subscription»
    // is not a letter — but they stay in [text] for the name.
    val words = text.replace(LINK, " ").replace(MAIL, " ")
    val lower = words.lowercase()
    if (MARKERS.none { it in lower }) return null
    val trial = TRIAL.find(lower)
    val recurs = trial != null || RECURRING.any { it in lower }
    val amounts = amountsIn(words)
    val amount = bestAmount(words, amounts)
    val rhythmSaid = rhythmAfter(lower, amount) ?: rhythmIn(lower)
    // It has to say it comes back: a trial, a renewal, a subscription fee, or a
    // rhythm beside a price. «Підписка на новини» and a 999 ₴ gift card do not.
    if (!recurs && !(rhythmSaid != null && amount != null)) return null
    val dates = datesIn(words, today, amounts)
    val trialEnd = trial?.let { found -> dates.firstOrNull { it.at >= found.range.first && it.at - found.range.first < TRIAL_REACH } }
    val charge = trialEnd ?: chargeDate(lower, dates)
    val yearly = rhythmSaid == Rhythm.YEARLY
    return SubscriptionDraft(
        name = serviceName(text, words),
        amount = amount?.value ?: 0.0,
        currency = amount?.currency ?: UAH,
        day = charge?.date?.dayOfMonth,
        billingMonth = if (yearly) (charge?.date ?: today).monthValue else 0,
        trialEnd = trialEnd?.date?.toEpochDay() ?: 0L,
        from = charge?.date
    )
}

/** «Megogo · пробний до 12 лист., далі 199 ₴/міс» — the draft in one line, for the sheet. */
fun draftLine(draft: SubscriptionDraft): String = listOfNotNull(
    draft.name,
    draft.trialEnd.takeIf { it > 0L }?.let { "пробний до ${shortDate(LocalDate.ofEpochDay(it))}" },
    draft.amount.takeIf { it > 0.0 }?.let { amount ->
        val rhythm = if (draft.billingMonth > 0) "/рік" else "/міс"
        (if (draft.trialEnd > 0L) "далі " else "") + amountLabel(amount, draft.currency) + rhythm
    }
).joinToString(" · ").replace(" · далі", ", далі")

/** «Оновити ціну з 1 лист.: 200 → 250 ₴» — the one button of a price change. */
fun priceChangeLabel(change: SharedLetter.PriceChange): String {
    val since = change.draft.from?.let { " з ${shortDate(it)}" }.orEmpty()
    return "Оновити ціну$since: ${bareAmount(change.pay.amount)} → ${amountLabel(change.draft.amount, change.pay.currency)}"
}

/**
 * The charge still due at the old price before a new one starts, or null.
 *
 * The app holds one price per payment, and «Оновити» changes it at once — the
 * same as typing it in. When a charge falls between today and the new price's
 * date, the sheet says so, so the owner can mark that one at the old figure.
 */
fun oldPriceChargeBefore(pay: Pay, from: LocalDate?, today: LocalDate): LocalDate? {
    from ?: return null
    val next = nextCharge(pay, today)
    return next.takeIf { it.isBefore(from) }
}

/** «12 лист.» — a date the way a short Ukrainian line writes it. */
fun shortDate(date: LocalDate): String = "${date.dayOfMonth} ${MONTHS_ABBREVIATED[date.monthValue - 1]}"

private val MONTHS_ABBREVIATED = listOf(
    "січ.", "лют.", "бер.", "квіт.", "трав.", "черв.", "лип.", "серп.", "вер.", "жовт.", "лист.", "груд."
)

// ------------------------------------------------------------ the words

private val LINK = Regex("""(?i)\b(?:https?://|www\.)\S+""")
private val MAIL = Regex("""[\w.+-]+@[\w-]+(?:\.[\w-]+)+""")

/** A subscription's own words. Stems, so every case of «підписка» counts. */
private val MARKERS = listOf(
    "підписк", "передплат", "пробн", "автопродовж", "абонплат", "абонентськ",
    "trial", "subscri", "renew", "membership"
)

/** The words that say it comes back by itself. */
private val RECURRING = listOf(
    "абонплат", "абонентськ", "автопродовж", "продовжиться", "продовжено", "продовжена",
    "renew", "recurring", "auto-pay", "autopay"
)

/** A free period: «пробний період», «безкоштовно до», «free trial». */
private val TRIAL = Regex("""пробн|безкоштовн|free trial|trial|free until|free for""")

/** How far after the trial's own words its end date is looked for. */
private const val TRIAL_REACH = 90

private enum class Rhythm { MONTHLY, YEARLY }

private val MONTHLY = Regex("""щомісяц|щомісячн|на місяць|в місяць|за місяць|/\s?міс|місячн|monthly|per month|a month|each month|every month|/\s?mo\b|/\s?month""")
private val YEARLY = Regex("""щоріч|щороку|на рік|в рік|за рік|/\s?рік|річн|yearly|annual|per year|a year|each year|every year|/\s?yr\b|/\s?year""")

private fun rhythmIn(lower: String): Rhythm? = when {
    MONTHLY.containsMatchIn(lower) -> Rhythm.MONTHLY
    YEARLY.containsMatchIn(lower) -> Rhythm.YEARLY
    else -> null
}

/** The rhythm written right after the chosen price, «199 грн/міс», which beats one elsewhere. */
private fun rhythmAfter(lower: String, amount: Amount?): Rhythm? {
    amount ?: return null
    val after = lower.substring(amount.end.coerceAtMost(lower.length), (amount.end + 16).coerceAtMost(lower.length))
    return when {
        MONTHLY.find(after)?.range?.first?.let { it <= 4 } == true -> Rhythm.MONTHLY
        YEARLY.find(after)?.range?.first?.let { it <= 4 } == true -> Rhythm.YEARLY
        else -> null
    }
}

// ------------------------------------------------------------ the price

private class Amount(val value: Double, val currency: String, val start: Int, val end: Int)

private const val FIGURE = """\d{1,3}(?:[ \u00A0\u202F\u2009,.]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?"""

private val AMOUNT_AFTER = Regex(
    """(?<![\d.,])($FIGURE)\s?(грн\.?|гривень|гривні|гривня|₴|uah|\$|usd|дол(?:ар(?:ів|и)?)?\.?)""",
    RegexOption.IGNORE_CASE
)
private val AMOUNT_BEFORE = Regex("""(\$|usd|uah|₴)\s?($FIGURE)(?![\d])""", RegexOption.IGNORE_CASE)

private fun currencyOf(word: String): String =
    if (word.startsWith("$") || word.lowercase().startsWith("usd") || word.lowercase().startsWith("дол")) USD else UAH

private fun amountsIn(text: String): List<Amount> {
    val after = AMOUNT_AFTER.findAll(text).mapNotNull { match ->
        priceNumber(match.groupValues[1])?.let { Amount(it, currencyOf(match.groupValues[2]), match.range.first, match.range.last + 1) }
    }
    val before = AMOUNT_BEFORE.findAll(text).mapNotNull { match ->
        priceNumber(match.groupValues[2])?.let { Amount(it, currencyOf(match.groupValues[1]), match.range.first, match.range.last + 1) }
    }
    return (after + before)
        .sortedBy { it.start }
        .fold(emptyList()) { kept, next -> if (kept.any { next.start < it.end }) kept else kept + next }
}

/** Words that put an old price or a saving in front of a figure, not the price itself. */
private val NOT_THE_PRICE = Regex("""(?:замість|було|раніше|знижк\S*|економ\S*|instead of|was|previously|save|discount)\s*$""")

/** Words that lead into the price itself. */
private val THE_PRICE = Regex("""(?:далі|потім|після цього|then|after that|ціна|вартість|сума|становитиме|складатиме|складає|становить|буде списано|списуватимемо|спишемо|списання|price|charged|charge|will be|for|costs?)\s*:?\s*$""")

/**
 * The price among the figures a letter carries.
 *
 * «далі 199 грн/міс» beats «0 грн за перший місяць»; «з 200 на 250 грн» and
 * «179 грн замість 99 грн» are new prices with the old one beside them; a saving
 * is never the price. Ties go to the later figure, which in these letters is the
 * one that applies from now on.
 */
private fun bestAmount(text: String, amounts: List<Amount>): Amount? {
    val lower = text.lowercase()
    val scored = amounts.mapIndexed { index, amount ->
        val before = lower.substring((amount.start - 24).coerceAtLeast(0), amount.start)
        var score = 0
        if (rhythmAfter(lower, amount) != null) score += 2
        if (THE_PRICE.containsMatchIn(before)) score += 2
        if (NOT_THE_PRICE.containsMatchIn(before)) score -= 4
        // «з 200 на 250»: the first of the pair is what it was.
        if (Regex("""(?:^|\s)(?:з|from)\s*$""").containsMatchIn(before) &&
            amounts.getOrNull(index + 1)?.let { lower.substring(amount.end, it.start).trim() in setOf("на", "до", "to") } == true
        ) score -= 4
        amount to score
    }
    return scored.filter { it.first.value > 0.0 }.maxWithOrNull(compareBy({ it.second }, { it.first.start }))?.first
}

// ------------------------------------------------------------ the dates

private class FoundDate(val date: LocalDate, val at: Int)

private val UK_MONTHS = listOf("січ", "лют", "бер", "кві", "тра", "чер", "лип", "сер", "вер", "жов", "лис", "гру")
private val EN_MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")

private const val EN_MONTH = """(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sep(?:t(?:ember)?)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)\.?"""

private val DOTTED = Regex("""(?<![\d.])(\d{1,2})\.(\d{1,2})(?:\.(\d{4}|\d{2}))?(?![\d]|\.\d)""")
private val ISO = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
private val UK_WORDS = Regex("""(?<!\d)(\d{1,2})\s+([а-щьюяіїєґА-ЩЬЮЯІЇЄҐ]{3,})\.?(?:\s+(\d{4}))?""")
private val EN_MONTH_DAY = Regex("""\b$EN_MONTH\s+(\d{1,2})(?:st|nd|rd|th)?\b(?:,?\s+(\d{4}))?""", RegexOption.IGNORE_CASE)
private val EN_DAY_MONTH = Regex("""(?<!\d)(\d{1,2})(?:st|nd|rd|th)?\s+$EN_MONTH(?:,?\s+(\d{4}))?""", RegexOption.IGNORE_CASE)

/** Every date in the letter that is not part of a price, in reading order. */
private fun datesIn(text: String, today: LocalDate, amounts: List<Amount>): List<FoundDate> {
    fun inPrice(at: Int) = amounts.any { at in it.start until it.end }
    val found = mutableListOf<FoundDate>()
    fun add(at: Int, day: Int?, month: Int?, year: String?) {
        if (day == null || month == null || month !in 1..12 || day !in 1..31 || inPrice(at)) return
        dateOf(day, month, year, today)?.let { found += FoundDate(it, at) }
    }
    DOTTED.findAll(text).forEach { add(it.range.first, it.groupValues[1].toIntOrNull(), it.groupValues[2].toIntOrNull(), it.groupValues[3]) }
    ISO.findAll(text).forEach { add(it.range.first, it.groupValues[3].toIntOrNull(), it.groupValues[2].toIntOrNull(), it.groupValues[1]) }
    UK_WORDS.findAll(text).forEach { match ->
        val month = UK_MONTHS.indexOfFirst { match.groupValues[2].lowercase().startsWith(it) } + 1
        add(match.range.first, match.groupValues[1].toIntOrNull(), month.takeIf { it > 0 }, match.groupValues[3])
    }
    EN_MONTH_DAY.findAll(text).forEach { match ->
        val month = EN_MONTHS.indexOf(match.groupValues[1].lowercase().take(3)) + 1
        add(match.range.first, match.groupValues[2].toIntOrNull(), month.takeIf { it > 0 }, match.groupValues[3])
    }
    EN_DAY_MONTH.findAll(text).forEach { match ->
        val month = EN_MONTHS.indexOf(match.groupValues[2].lowercase().take(3)) + 1
        add(match.range.first, match.groupValues[1].toIntOrNull(), month.takeIf { it > 0 }, match.groupValues[3])
    }
    return found.sortedBy { it.at }.distinctBy { it.at }
}

/**
 * A date the letter names. Without a year, the coming one — unless it is within
 * a month behind, which is a charge just made («списано 3 жовт.» read on the 4th).
 */
private fun dateOf(day: Int, month: Int, year: String?, today: LocalDate): LocalDate? = runCatching {
    val y = year?.takeIf { it.isNotBlank() }?.toInt()?.let { if (it < 100) 2000 + it else it }
    if (y != null) return@runCatching LocalDate.of(y, month, day)
    val thisYear = LocalDate.of(today.year, month, day)
    if (thisYear.isBefore(today.minusDays(31))) thisYear.plusYears(1) else thisYear
}.getOrNull()

/** Words that lead into the date money is taken on. */
private val CHARGE_WORDS = Regex("""продовж|списан|спишемо|наступн|з |починаючи|від |renew|charge|next|billing|from|starting|on """)

/** The date beside a renewal or a charge, else the first date the letter names. */
private fun chargeDate(lower: String, dates: List<FoundDate>): FoundDate? =
    dates.firstOrNull { date -> CHARGE_WORDS.containsMatchIn(lower.substring((date.at - 30).coerceAtLeast(0), date.at)) }
        ?: dates.firstOrNull()

// ------------------------------------------------------------ the name

/** Services a letter is likely to come from, as the app should name them. Longest first. */
private val SERVICES = listOf(
    "youtube premium" to "YouTube Premium", "youtube music" to "YouTube Music", "youtube" to "YouTube Premium",
    "apple music" to "Apple Music", "apple tv" to "Apple TV+", "apple one" to "Apple One", "icloud" to "iCloud+",
    "google one" to "Google One", "google play" to "Google Play",
    "netflix" to "Netflix", "spotify" to "Spotify", "megogo" to "Megogo", "мегого" to "Megogo",
    "sweet.tv" to "Sweet.tv", "sweet tv" to "Sweet.tv", "київстар тб" to "Київстар ТБ", "kyivstar tv" to "Київстар ТБ",
    "kyivstar" to "Kyivstar", "київстар" to "Kyivstar", "vodafone" to "Vodafone", "водафон" to "Vodafone",
    "lifecell" to "lifecell", "лайфсел" to "lifecell", "volia" to "Воля", "воля" to "Воля",
    "ланет" to "Ланет", "lanet" to "Ланет", "тріолан" to "Тріолан", "triolan" to "Тріолан",
    "укртелеком" to "Укртелеком", "ukrtelecom" to "Укртелеком", "datagroup" to "Datagroup",
    "disney+" to "Disney+", "disney plus" to "Disney+", "hbo max" to "HBO Max", "max.com" to "HBO Max",
    "amazon prime" to "Amazon Prime", "prime video" to "Prime Video", "audible" to "Audible",
    "chatgpt" to "ChatGPT", "openai" to "ChatGPT", "claude" to "Claude", "anthropic" to "Claude",
    "microsoft 365" to "Microsoft 365", "office 365" to "Microsoft 365", "xbox game pass" to "Xbox Game Pass",
    "playstation plus" to "PlayStation Plus", "ps plus" to "PlayStation Plus", "nintendo" to "Nintendo Switch Online",
    "adobe" to "Adobe", "dropbox" to "Dropbox", "notion" to "Notion", "canva" to "Canva", "figma" to "Figma",
    "duolingo" to "Duolingo", "telegram premium" to "Telegram Premium", "discord nitro" to "Discord Nitro",
    "1password" to "1Password", "nordvpn" to "NordVPN", "expressvpn" to "ExpressVPN", "proton" to "Proton",
    "patreon" to "Patreon", "boosty" to "Boosty", "storytel" to "Storytel", "yakaboo" to "Yakaboo",
    "deezer" to "Deezer", "tidal" to "Tidal", "twitch" to "Twitch", "linkedin" to "LinkedIn Premium"
)

/** Words of a name that say nothing about which payment it is. */
private val NAME_NOISE_WORDS = setOf(
    "підписка", "оплата", "платіж", "плата", "мій", "моя", "моє", "мої", "за", "на", "для", "та",
    "місяць", "тариф", "рахунок", "послуги", "premium", "plus", "pro", "the", "app", "сервіс", "інше"
)

private fun startsWord(text: String, at: Int): Boolean = at == 0 || !text[at - 1].isLetterOrDigit()

/** Where [word] stands in [text] as a word or the start of one; three letters must stand alone. */
private fun wordAt(text: String, word: String): Int? {
    var from = 0
    while (true) {
        val at = text.indexOf(word, from)
        if (at < 0) return null
        val end = at + word.length
        val whole = end >= text.length || !text[end].isLetterOrDigit()
        if (startsWord(text, at) && (whole || word.length >= 4)) return at
        from = at + 1
    }
}

/** A brand written in Cyrillic, in the Latin the letters use. */
private val CYRILLIC_BRANDS = mapOf(
    "нетфлікс" to "netflix", "спотіфай" to "spotify", "мегого" to "megogo", "ютуб" to "youtube",
    "айклауд" to "icloud", "київстар" to "kyivstar", "водафон" to "vodafone", "лайфсел" to "lifecell"
)

/** The words of a payment's name to look for in a letter. */
private fun letterTokens(name: String): List<String> =
    name.lowercase().split(Regex("""[^\p{L}\p{N}+.]+"""))
        .map { it.trim('.') }
        .filter { it.length >= 3 && it !in NAME_NOISE_WORDS }
        .flatMap { listOf(it, transliterate(it)) + listOfNotNull(CYRILLIC_BRANDS[it]) }
        .filter { it.length >= 3 }
        .distinct()

/**
 * The payment on the list a letter is about, or null: the one more of whose name
 * the letter names — «YouTube Premium Family» over «YouTube» in a letter that says
 * all three words.
 */
fun paymentNamedIn(text: String, pays: List<Pay>): Pay? {
    val lower = text.lowercase()
    return pays.mapNotNull { pay ->
        letterTokens(pay.name).filter { wordAt(lower, it) != null }.sumOf { it.length }.takeIf { it > 0 }?.let { pay to it }
    }.maxByOrNull { it.second }?.first
}

/**
 * What the new payment is called: a service the app knows, the sender of an
 * e-mail, a quoted name after «підписка», a capitalised name before
 * «subscription». «Підписка» when none of them is there.
 */
private fun serviceName(text: String, words: String): String {
    val lower = text.lowercase()
    SERVICES.mapNotNull { (key, name) -> wordAt(lower, key)?.let { at -> Triple(at, key, name) } }
        .minWithOrNull(compareBy({ it.first }, { -it.second.length }))
        ?.let { return it.third }
    Regex("""(?im)^(?:from|від|відправник)\s*:\s*"?([^"<\n]+?)"?\s*<""").find(text)?.groupValues?.get(1)?.trim()
        ?.takeIf { it.isNotBlank() && it.length <= 40 }?.let { return it }
    Regex("""підписк\S*\s+(?:на\s+)?«([^»]{2,40})»""", RegexOption.IGNORE_CASE).find(words)?.groupValues?.get(1)?.trim()
        ?.let { return it }
    Regex("""[Пп]ідписк\S*\s+(?:на\s+)?([A-Z][\w.+]*(?:\s+[A-Z][\w.+]*){0,2})""").find(words)?.groupValues?.get(1)?.trim()
        ?.let { return it }
    Regex("""([A-Z][\w.+]*(?:\s+[A-Z][\w.+]*){0,2})\s+(?:subscription|membership|plan|free trial)""").find(words)
        ?.groupValues?.get(1)
        ?.split(Regex("\\s+"))?.dropWhile { it.lowercase() in setOf("your", "the", "this", "my") }
        ?.joinToString(" ")?.takeIf { it.isNotBlank() }
        ?.let { return it }
    return "Підписка"
}

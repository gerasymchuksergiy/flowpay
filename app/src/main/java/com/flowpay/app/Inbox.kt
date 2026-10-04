package com.flowpay.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * «Telegram-скринька». The owner works at a PC a lot, and reaching for the phone
 * every time something turns up was the annoyance («сіпати телефон постійно … бісить»).
 * So they paste a link, a waybill or a letter into a chat with their OWN bot, on the
 * PC; FlowPay on the phone picks it up, adds it, and replies in that chat with what
 * it did — «✅ Бажання: Навушники JBL — 1 599 ₴ (rozetka.com.ua)».
 *
 * This file is the pure half, tested in InboxTest: Telegram's answers, binding the
 * owner's chat, what a message means and every reply. It also holds the readings the
 * share sheet and the inbox have in common — [shareRoute], [filledWish], [letterPay],
 * [parcelOrder] — so a message from the PC is decided exactly as the same text shared
 * on the phone is, by the same functions rather than a copy of them. The Android half
 * (the token, the preferences, the calls, the worker) is InboxSync.kt; the screens
 * are InboxUi.kt. Every preference key the inbox adds starts with `tg_`.
 */

// ------------------------------------------------------------ one reading for every way in

/** What a shared or sent text is, in the order the share router has always asked. */
sealed interface ShareRoute {
    /** A letter about a subscription: a new payment, a new price, or the same price. */
    data class Letter(val letter: SharedLetter) : ShareRoute

    /** A parcel: a Nova Poshta waybill anywhere in the words, or a tracking number in a text with no link. */
    data class Parcel(val number: String) : ShareRoute

    /** A link to a thing already watched. */
    data class Known(val link: SharedLink.Known) : ShareRoute

    /** A Hotline product page — a market for a wish, not a wish ([hotlineProductUrl]). */
    data class Market(val url: String) : ShareRoute

    /** A link to a thing not on the list yet. */
    data class NewLink(val url: String) : ShareRoute

    /** No letter, no parcel number, no link. */
    data object Unclear : ShareRoute
}

/**
 * The share router's decision, written once: the app's own router (AppCommand.AddShared)
 * and the Telegram inbox both ask this, so the two cannot drift apart.
 *
 * The order is the point, and each step is there for a reason found the hard way. A
 * letter about a subscription first: it nearly always carries a link, and an order
 * number in it can look like a waybill (§22). Then a Nova Poshta waybill anywhere in
 * the words, link or no link: a shop's SMS carries the order's link beside the number,
 * and the link used to win (§20). Then the link — already watched, a Hotline page, or
 * new — and with no link at all, any tracking number in the text.
 */
fun shareRoute(text: String?, wishes: List<Wish>, pays: List<Pay>, today: LocalDate): ShareRoute {
    subscriptionLetter(text, pays, today)?.let { return ShareRoute.Letter(it) }
    sharedParcelNumber(text)?.let { return ShareRoute.Parcel(it) }
    return when (val link = sharedLink(text, wishes)) {
        SharedLink.Missing -> trackingNumberIn(text)?.let { ShareRoute.Parcel(it) } ?: ShareRoute.Unclear
        is SharedLink.Known -> ShareRoute.Known(link)
        is SharedLink.New -> hotlineProductUrl(link.url)?.let { ShareRoute.Market(it) } ?: ShareRoute.NewLink(link.url)
    }
}

/**
 * A placeholder once its page has been read. The shop's own title, photograph and
 * words replace the placeholder's, but the row keeps its id so nothing else has to be
 * told it changed. The share router's and the inbox's one way to fill one in.
 */
fun filledWish(placeholder: Wish, fetched: Wish, day: Long, rate: FxRate): Wish =
    refreshedWish(placeholder, fetched, day, rate).copy(name = fetched.name)

/** A free trial or a yearly fee is a decision as well as a charge: it gets the longer notice. */
fun longNotice(trialEnd: Long, billingMonth: Int): Boolean = trialEnd > 0L || billingMonth > 0

/**
 * The payment a letter about a subscription makes: what «Новий платіж», filled in
 * from the letter and saved untouched, saves. The form takes its fields from this;
 * the Telegram inbox, which has no form to show, saves it as it is.
 *
 * A letter's trial is a free one — [Pay.promoPrice] stays nought, so [priceOn] reads
 * nothing before [Pay.trialEnd] and the price after it, and [nextCharge] looks past
 * the free period (§21). A promo's own price is only ever typed. A letter that names
 * no day leaves the form's own default, the 1st.
 */
fun letterPay(draft: SubscriptionDraft, today: LocalDate): Pay = Pay(
    name = draft.name.trim().ifBlank { "Інше" },
    amount = draft.amount,
    day = (draft.day ?: 1).coerceIn(1, 31),
    currency = draft.currency,
    warnDays = if (longNotice(draft.trialEnd, draft.billingMonth)) LONG_NOTICE_DAYS else DEFAULT_WARN_DAYS,
    // The opening figure, dated — as the form writes it.
    amounts = listOf(PricePoint(draft.amount, today.toEpochDay())),
    trialEnd = draft.trialEnd,
    billingMonth = draft.billingMonth
)

/**
 * A purchase known only by its number: what «Додати покупку» saves from a tracking
 * number with no shop link, and what the inbox saves from a waybill sent to it.
 */
fun parcelOrder(number: String, id: String): Order {
    val clean = number.filter { !it.isWhitespace() }
    return Order(id, parcelNameFor(clean), "", ORDERED, tracking = clean)
}

/**
 * An id for a new row: the clock, as the app has always used, moved past any id
 * already taken. Two links sent in one message batch are added within the same
 * millisecond, and [withoutRepeatedIds] would drop the second.
 */
fun freshId(now: Long, taken: Collection<String>): String {
    var id = now
    while (id.toString() in taken) id++
    return id.toString()
}

// ------------------------------------------------------------ Telegram's answers

/** Every answer of the Bot API: `ok`, and either `result` or an error code with a description. */
data class TgAnswer(
    val ok: Boolean,
    /** A JSONObject, a JSONArray, a Boolean — whatever the method returns. */
    val result: Any?,
    val errorCode: Int = 0,
    val description: String = "",
    /** `parameters.retry_after` on a 429: seconds to wait. Nought otherwise. */
    val retryAfter: Int = 0
)

/** An answer's JSON, or null when the body is not Telegram's JSON at all. */
fun tgAnswer(body: String): TgAnswer? = runCatching {
    val root = JSONObject(body)
    TgAnswer(
        ok = root.optBoolean("ok", false),
        result = root.opt("result"),
        errorCode = root.optInt("error_code", 0),
        description = root.optString("description", ""),
        retryAfter = root.optJSONObject("parameters")?.optInt("retry_after", 0) ?: 0
    )
}.getOrNull()

/** One message to the bot, as far as the inbox cares. */
data class TgMessage(
    val chatId: Long,
    /** "private", "group", "supergroup" or "channel". Only a private chat can be bound. */
    val chatType: String,
    val messageId: Long,
    /**
     * The words: the text, else a photo's or a file's caption, with the addresses of
     * links hidden under words added on their own lines. Null when there are none.
     */
    val text: String?,
    /** A photo, a file, a voice message and the like: something that is not words. */
    val media: Boolean,
    val fromBot: Boolean
)

/** One update. [message] is null for anything that is not a new message — an edit, a channel post. */
data class TgUpdate(val id: Long, val message: TgMessage?)

/** What a message can carry besides words. A service message («pinned», «joined») carries none of these. */
private val MEDIA_FIELDS = listOf(
    "photo", "document", "video", "voice", "audio", "animation", "sticker", "video_note",
    "contact", "location", "venue", "poll", "dice", "story", "paid_media"
)

/** The updates in a getUpdates `result`, every one of them — the ones the inbox ignores still move the offset. */
fun updatesIn(result: Any?): List<TgUpdate> {
    val array = result as? JSONArray ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val item = array.optJSONObject(index) ?: return@mapNotNull null
        if (!item.has("update_id")) return@mapNotNull null
        // Only `message`: an edited message is not a new errand, and acting on the
        // edit of a link already added would add it twice.
        TgUpdate(item.optLong("update_id"), item.optJSONObject("message")?.let(::tgMessageOf))
    }
}

/** A Message object, or null when it has no chat to answer in. */
fun tgMessageOf(json: JSONObject): TgMessage? {
    val chat = json.optJSONObject("chat") ?: return null
    if (!chat.has("id")) return null
    val captioned = !json.has("text")
    val words = if (captioned) json.optString("caption", "") else json.optString("text", "")
    val hidden = hiddenLinks(json.optJSONArray(if (captioned) "caption_entities" else "entities"), words)
    val text = (listOf(words) + hidden).filter { it.isNotBlank() }.joinToString("\n").ifBlank { null }
    return TgMessage(
        chatId = chat.optLong("id"),
        chatType = chat.optString("type", ""),
        messageId = json.optLong("message_id"),
        text = text,
        media = MEDIA_FIELDS.any { json.has(it) },
        fromBot = json.optJSONObject("from")?.optBoolean("is_bot", false) == true
    )
}

/**
 * The addresses behind words — a `text_link` entity — that the words themselves do not
 * show. A shop's post forwarded from a channel often hides its link under the product's
 * name; on the phone the share carries the address, so here it is added the same way.
 */
fun hiddenLinks(entities: JSONArray?, words: String): List<String> {
    entities ?: return emptyList()
    return (0 until entities.length()).mapNotNull { entities.optJSONObject(it) }
        .filter { it.optString("type", "") == "text_link" }
        .map { it.optString("url", "").trim() }
        .filter { it.isNotBlank() && it !in words }
        .distinct()
}

/** The bot's own username from getMe, or null. */
fun botUsername(result: Any?): String? =
    (result as? JSONObject)?.optString("username", "")?.trim()?.takeIf { it.isNotBlank() }

// ------------------------------------------------------------ asking

/** The getUpdates query: what comes after [offset] (all unconfirmed when null), no waiting, messages only. */
fun updatesQuery(offset: Long?): String = buildList {
    if (offset != null && offset > 0L) add("offset=$offset")
    add("timeout=0")
    add("allowed_updates=" + URLEncoder.encode("[\"message\"]", "UTF-8"))
}.joinToString("&")

/**
 * How long a stored offset is worth sending. Telegram keeps an update 24 hours, so an
 * offset older than that confirms nothing still waiting — and after a week with no
 * updates Telegram picks the next update's number at random, possibly below it. Past
 * this the pass asks for everything unconfirmed instead.
 */
const val OFFSET_KEEP_MS = 24L * 60 * 60 * 1000

/** The offset to send, or null to ask for every update still unconfirmed. */
fun offsetToSend(stored: Long, storedAt: Long, now: Long): Long? =
    stored.takeIf { it > 0L && now - storedAt in 0 until OFFSET_KEEP_MS }

/** The offset after [update]: one past it, and never backwards. */
fun offsetAfter(current: Long?, update: TgUpdate): Long = maxOf(current ?: 0L, update.id + 1)

/** Telegram's own limit is 4 096 characters; a reply is cut well before it. */
const val TG_TEXT_LIMIT = 4000

/**
 * The sendMessage body: plain text (no parse_mode, so nothing in a shop's title is
 * read as markup), no link preview, and an answer to the message it is about — sent
 * all the same if that message was deleted meanwhile.
 */
fun sendMessageBody(chatId: Long, text: String, replyTo: Long): JSONObject = JSONObject()
    .put("chat_id", chatId)
    .put("text", text.take(TG_TEXT_LIMIT))
    .put("link_preview_options", JSONObject().put("is_disabled", true))
    .apply {
        if (replyTo > 0L) {
            put("reply_parameters", JSONObject().put("message_id", replyTo).put("allow_sending_without_reply", true))
        }
    }

/**
 * The bot token out of whatever was pasted: the token alone, or @BotFather's whole
 * message around it («Use this token to access the HTTP API: 123456789:AA…»). Null
 * when there is none.
 */
fun botTokenIn(text: String?): String? = BOT_TOKEN.find(text.orEmpty())?.value

private val BOT_TOKEN = Regex("""(?<!\d)\d{5,15}:[A-Za-z0-9_-]{30,}""")

/** The link that opens the bot with Start and the code already in it. */
fun startLink(username: String, code: String): String = "https://t.me/$username?start=$code"

/** @BotFather, where a bot is made. */
const val BOT_FATHER_LINK = "https://t.me/BotFather"

// ------------------------------------------------------------ binding the owner's chat

/** A fresh six-digit code, drawn from [random] — SecureRandom on the phone. */
fun newBindingCode(random: java.util.Random): String = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000))

private val START_WITH_CODE = Regex("""/start(?:@\w+)?\s+(\d{6})""")
private val BARE_CODE = Regex("""\d{6}""")

/** The code in «/start 123456» (what the link sends) or in the six digits typed alone; null otherwise. */
fun bindingCode(text: String?): String? {
    val words = text?.trim() ?: return null
    START_WITH_CODE.matchEntire(words)?.let { return it.groupValues[1] }
    return words.takeIf { BARE_CODE.matches(it) }
}

private val GREETING = Regex("""/(?:start|help)(?:@\w+)?(?:\s.*)?""", RegexOption.DOT_MATCHES_ALL)

/** «/start» or «/help» in the owner's chat: asking what this chat is for. */
fun isGreeting(text: String?): Boolean = text?.trim()?.let { GREETING.matches(it) } == true

/** What one update asks of the inbox. */
sealed interface Triage {
    /** Not a new message, another chat, a bot, or anything before binding that is not the code. */
    data object Ignore : Triage

    /** The code, in a private chat, while one is waiting: this chat becomes the owner's. */
    data class Bind(val message: TgMessage) : Triage

    /** «/start» or «/help» from the owner. */
    data class Hello(val message: TgMessage) : Triage

    /** A photo or a file from the owner with no words to read. */
    data class NotText(val message: TgMessage) : Triage

    /** Words from the owner: the share router's to decide. */
    data class Handle(val message: TgMessage, val text: String) : Triage
}

/**
 * What to do with one update. [owner] is the bound chat, nought before binding;
 * [code] the binding code waiting, empty when none.
 *
 * Until a chat is bound only the code counts, and nothing else gets an answer — a
 * stranger who found the bot learns nothing, not even that it is alive. After it,
 * every other chat is ignored just as silently.
 */
fun triage(update: TgUpdate, owner: Long, code: String): Triage {
    val message = update.message ?: return Triage.Ignore
    if (message.fromBot) return Triage.Ignore
    if (owner == 0L) {
        val binds = code.isNotEmpty() && message.chatType == "private" && bindingCode(message.text) == code
        return if (binds) Triage.Bind(message) else Triage.Ignore
    }
    if (message.chatId != owner) return Triage.Ignore
    val text = message.text
    return when {
        text == null -> if (message.media) Triage.NotText(message) else Triage.Ignore
        isGreeting(text) -> Triage.Hello(message)
        else -> Triage.Handle(message, text)
    }
}

// ------------------------------------------------------------ what a message from the owner does

/** What one message from the owner comes to. */
sealed interface InboxStep {
    /** Nothing to change; only the reply. */
    data class Say(val text: String) : InboxStep

    /** A new payment from a letter, with its reply. */
    data class AddPay(val pay: Pay, val reply: String) : InboxStep

    /** A payment on the list at a new price — [withAmount], as «Оновити ціну» does. */
    data class NewPrice(val pay: Pay, val amount: Double, val reply: String) : InboxStep

    /** A parcel not on the list yet: added, then asked about once. */
    data class AddParcel(val number: String) : InboxStep

    /** A shop's page not on the list yet: the placeholder first, then the page. */
    data class AddWish(val url: String) : InboxStep
}

/**
 * The share router's decision for a message from the owner, as a step the inbox can
 * take without a screen. [hideSums] is «Ховати суми поза застосунком»: a chat is
 * outside the app, so payment sums are masked there as on the widget. Shop prices are
 * not personal and stay.
 */
fun inboxStep(
    text: String,
    wishes: List<Wish>,
    pays: List<Pay>,
    orders: List<Order>,
    today: LocalDate,
    hideSums: Boolean
): InboxStep {
    fun outside(line: String) = if (hideSums) maskSums(line) else line
    return when (val route = shareRoute(text, wishes, pays, today)) {
        is ShareRoute.Letter -> when (val letter = route.letter) {
            is SharedLetter.SamePrice -> InboxStep.Say(samePriceReply(letter.pay))
            is SharedLetter.PriceChange ->
                InboxStep.NewPrice(letter.pay, letter.draft.amount, outside(priceChangeReply(letter, today)))
            is SharedLetter.NewPayment -> {
                val draft = letter.draft
                // On the phone the form opens and the owner judges these two; here
                // there is no form, so nothing is added that the owner did not see.
                val named = paymentNamedIn(text, pays)
                when {
                    draft.amount <= 0.0 -> InboxStep.Say(noPriceReply(draft))
                    named != null -> InboxStep.Say(outside(otherCurrencyReply(named, draft)))
                    else -> letterPay(draft, today).let { pay ->
                        InboxStep.AddPay(pay, outside(newPayReply(pay, draft.day != null, today)))
                    }
                }
            }
        }
        is ShareRoute.Parcel ->
            knownParcel(orders, route.number)?.let { InboxStep.Say(knownParcelReply(it, route.number, today.toEpochDay())) }
                ?: InboxStep.AddParcel(route.number)
        is ShareRoute.Known -> InboxStep.Say(knownWishReply(route.link.wish))
        is ShareRoute.Market -> InboxStep.Say(INBOX_HOTLINE)
        is ShareRoute.NewLink -> InboxStep.AddWish(route.url)
        ShareRoute.Unclear -> InboxStep.Say(INBOX_UNCLEAR)
    }
}

// ------------------------------------------------------------ the replies

/** Said once the chat is bound, and again to «/start» and «/help». */
const val INBOX_READY = "Готово: це ваша скринька FlowPay. Надсилайте посилання, номери посилок і листи про підписки"

const val INBOX_NOT_TEXT = "Поки що розумію лише текст і посилання"

/** Present tense on purpose: «не зрозумів» would give the app a gender (HANDOFF §13). */
const val INBOX_UNCLEAR = "🤔 Не розумію: тут немає ні посилання на товар, ні номера посилки, ні листа про підписку"

const val INBOX_HOTLINE = "Сторінку Hotline прив'язати до бажання поки можна лише з телефону: Поділитися → FlowPay"

/** Said when handling a message failed in a way nobody foresaw, so it is not retried for ever. */
const val INBOX_FAILED = "Не вдалося обробити це повідомлення — додайте вручну у FlowPay"

/** «12.11», or «15.01.2027» outside this year. */
fun dottedDate(date: LocalDate, today: LocalDate): String =
    if (date.year == today.year) {
        String.format(Locale.ROOT, "%02d.%02d", date.dayOfMonth, date.monthValue)
    } else {
        String.format(Locale.ROOT, "%02d.%02d.%d", date.dayOfMonth, date.monthValue, date.year)
    }

/** «🧾 Платіж: Megogo — 199 ₴ щомісяця, наступне 12.11 (пробний до 12.11). Якщо щось не так — виправте у FlowPay» */
fun newPayReply(pay: Pay, dated: Boolean, today: LocalDate): String {
    val rhythm = if (pay.billingMonth > 0) "щороку" else "щомісяця"
    val next = dottedDate(nextCharge(pay, today), today)
    val trial = pay.trialEnd.takeIf { it > 0L }
        ?.let { " (пробний до ${dottedDate(LocalDate.ofEpochDay(it), today)})" }.orEmpty()
    val undated = if (dated) "" else " — дати в листі немає, тож узято ${pay.day} число"
    return "🧾 Платіж: ${pay.name} — ${amountLabel(pay.amount, pay.currency)} $rhythm, наступне $next$trial$undated. " +
        "Якщо щось не так — виправте у FlowPay"
}

/**
 * «✏️ «Netflix»: 200 → 250 ₴ з 1 листопада», and — as the dialog on the phone says —
 * a charge still due at the old price before the new one starts.
 */
fun priceChangeReply(change: SharedLetter.PriceChange, today: LocalDate): String {
    val pay = change.pay
    val since = change.draft.from?.let { " з ${dayMonth(it)}" }.orEmpty()
    val head = "✏️ «${pay.name}»: ${bareAmount(pay.amount)} → ${amountLabel(change.draft.amount, pay.currency)}$since"
    val stillOld = oldPriceChargeBefore(pay, change.draft.from, today)
        ?.let { ". Списання ${dayMonth(it)} ще за старою ціною, ${amountLabel(pay.amount, pay.currency)}" }.orEmpty()
    return head + stillOld
}

/** «ℹ️ «Netflix» уже є, ціна та сама» */
fun samePriceReply(pay: Pay): String = "ℹ️ «${pay.name}» уже є, ціна та сама"

/** A letter about a subscription with no figure in it: nothing is added on a guess. */
fun noPriceReply(draft: SubscriptionDraft): String =
    "🧾 Лист про «${draft.name}», але ціни в ньому немає — платіж не додано. " +
        "Надішліть лист із сумою або додайте платіж у FlowPay"

/** A letter naming a payment on the list, in another currency: the phone would ask, so nothing changes. */
fun otherCurrencyReply(pay: Pay, draft: SubscriptionDraft): String =
    "🧾 «${pay.name}» уже є у FlowPay, але в іншій валюті: у листі ${amountLabel(draft.amount, draft.currency)}, " +
        "у FlowPay ${amountLabel(pay.amount, pay.currency)}. Нічого не змінено — перевірте у FlowPay"

/** «👀 Уже стежу: Навушники JBL — зараз 1 599 ₴». A shop's price, not a personal sum. */
fun knownWishReply(wish: Wish): String {
    val price = when {
        wish.price <= 0.0 -> "ціни поки немає"
        isStale(wish.freshness) -> "востаннє ${money(wish.price)}"
        else -> "зараз ${money(wish.price)}"
    }
    return "👀 Уже стежу: ${wish.name} — $price"
}

/**
 * A new wish, as filled from its page — or null when the page gave nothing.
 *
 * «✅ Бажання: Навушники JBL — 1 599 ₴ (rozetka.com.ua)»; without a price, what was
 * read and the promise the twice-daily check keeps: a placeholder is
 * [Freshness.UNREADABLE] and is read again with the others.
 */
fun newWishReply(wish: Wish?, url: String): String {
    val shop = sourceName(url)
    val named = wish?.name?.takeIf { it.isNotBlank() && it != placeholderName(url) }
    if (wish == null || wish.price <= 0.0) {
        return if (named != null) {
            "✅ Бажання: $named ($shop) — ціну прочитати не вдалося, спробую під час перевірки цін"
        } else {
            "✅ Бажання додано ($shop), але ціну прочитати не вдалося — спробую під час перевірки цін"
        }
    }
    // A price the page showed while saying the thing cannot be bought is said so.
    val state = freshnessLabel(wish.freshness)?.let { " · ${it.lowercase()}" }.orEmpty()
    return "✅ Бажання: ${named ?: wish.name} — ${money(wish.price)} ($shop)$state"
}

/** «Посилка …0001» for a parcel known by its number only, «Навушники JBL (…0001)» for a named one. */
fun parcelLabel(order: Order, number: String = order.tracking): String {
    val clean = number.filter { !it.isWhitespace() }
    val tail = "…" + clean.takeLast(4)
    return if (order.name == parcelNameFor(order.tracking.filter { !it.isWhitespace() }) || order.name.isBlank()) {
        "Посилка $tail"
    } else {
        "${order.name} ($tail)"
    }
}

/** Where a parcel on the list stands, from what the app last learned about it. */
fun parcelStateLine(order: Order, today: Long): String {
    val refund = order.refund
    return when {
        refund != null -> "повернення — ${refundStatusLine(refund, today).replaceFirstChar { it.lowercase() }}"
        order.archivedDay > 0L -> "покупку завершено ${formatDate(LocalDate.ofEpochDay(order.archivedDay))}"
        order.digital -> DIGITAL_LABEL
        order.problem -> problemNote(order.statusCode)
        order.statusDetail.isNotBlank() -> order.statusDetail
        !isAutoTracked(order) -> "${order.status} · це не номер Нової пошти, тож FlowPay сам його не перевіряє"
        else -> "${order.status} · статус ще не перевірено"
    }
}

/** «📦 Посилка …0001: Прибув у відділення · Київ, відділення №5» — a parcel already on the list. */
fun knownParcelReply(order: Order, number: String, today: Long): String =
    "📦 ${parcelLabel(order, number)}: ${parcelStateLine(order, today)}"

/**
 * A parcel just added. [checked] says whether Nova Poshta answered the one question
 * asked; the status itself is then on [order].
 */
fun newParcelReply(order: Order, checked: Boolean, today: Long): String {
    val label = parcelLabel(order).replaceFirst("Посилка", "посилку")
    return when {
        !isAutoTracked(order) -> "📦 Додано $label. Це не номер Нової пошти — FlowPay сам її не перевіряє"
        !checked -> "📦 Додано $label. Статус дізнаюся під час наступної перевірки"
        else -> "📦 Додано $label: ${parcelStateLine(order, today)}"
    }
}

// ------------------------------------------------------------ what waits to be said

/** One reply still to send: kept until Telegram has it, so a dropped connection loses no answer. */
data class TgReply(val chatId: Long, val replyTo: Long, val text: String)

/** How many unsent replies are kept; the oldest go first. */
const val OUTBOX_LIMIT = 30

fun repliesJson(items: List<TgReply>): String = JSONArray().apply {
    items.takeLast(OUTBOX_LIMIT).forEach { put(JSONObject().put("c", it.chatId).put("m", it.replyTo).put("t", it.text)) }
}.toString()

fun repliesOf(json: String?): List<TgReply> = runCatching {
    val array = JSONArray(json ?: "[]")
    (0 until array.length()).mapNotNull { array.optJSONObject(it) }
        .map { TgReply(it.optLong("c"), it.optLong("m"), it.optString("t", "")) }
        .filter { it.chatId != 0L && it.text.isNotBlank() }
}.getOrDefault(emptyList())

// ------------------------------------------------------------ the settings row

/** «перевірено о 14:05», «перевірено вчора о 09:30», «перевірено 3 жовтня о 18:00». */
fun lastCheckLine(at: Long, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (at <= 0L) return "ще не перевірено"
    val then = Instant.ofEpochMilli(at).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val time = String.format(Locale.ROOT, "%02d:%02d", then.hour, then.minute)
    return when (then.toLocalDate()) {
        today -> "перевірено о $time"
        today.minusDays(1) -> "перевірено вчора о $time"
        else -> "перевірено ${dayMonth(then.toLocalDate())} о $time"
    }
}

/** Telegram refused the token: revoked in @BotFather, or never right. */
const val INBOX_TOKEN_REFUSED = "Токен не підходить — його могли відкликати у @BotFather"

/** getUpdates kept answering 409 after the webhook was removed: something else reads this bot. */
const val INBOX_OTHER_READER = "Цього бота вже читає інша програма. Створіть для FlowPay окремого бота в @BotFather"

/** The keystore no longer opens the stored token — a restored phone, a lock screen removed. */
const val INBOX_TOKEN_LOST = "Збережений токен більше не відкривається — відключіть скриньку й підключіть бота знову"

/** The real faults, in words for the owner; null for a status that only means «try again later». */
fun inboxFault(code: Int): String? = when (code) {
    401, 404 -> INBOX_TOKEN_REFUSED
    409 -> INBOX_OTHER_READER
    else -> null
}

/** What «Підключити» says when getMe refused the token. */
fun connectProblem(code: Int): String = when (code) {
    401, 404 -> "Telegram не прийняв цей токен. Скопіюйте його в @BotFather ще раз — увесь рядок «123456789:AA…»"
    else -> "Telegram відповів помилкою $code. Спробуйте ще раз за хвилину"
}

/** What «Підключити» says when Telegram did not answer at all. */
const val INBOX_CONNECT_OFFLINE = "Немає зв'язку з Telegram. Перевірте інтернет і спробуйте ще раз"

/** The way to a bot, for someone who has never made one. */
const val INBOX_STEPS = "1. Відкрийте в Telegram @BotFather → /newbot.\n" +
    "2. Дайте будь-яке ім'я.\n" +
    "3. Придумайте username, що закінчується на «bot».\n" +
    "4. Скопіюйте токен (рядок «123456789:AA…»).\n" +
    "5. Вставте його нижче."

/** HyperOS stops background work of an app left on the default battery setting. */
const val INBOX_BATTERY_LINE =
    "Щоб скринька працювала, коли FlowPay закритий, у налаштуваннях батареї для FlowPay оберіть «Без обмежень»"

/** The row under Налаштування: connected or not, and when Telegram last answered. */
data class InboxRow(val connected: Boolean, val line: String, val alarm: Boolean)

fun inboxRow(connected: Boolean, bound: Boolean, username: String, error: String, lastPass: Long, now: Long): InboxRow =
    when {
        !connected -> InboxRow(
            false,
            "Надсилайте з комп'ютера посилання, номери посилок і листи про підписки — FlowPay додасть сам",
            false
        )
        error.isNotBlank() -> InboxRow(true, error, true)
        !bound -> InboxRow(true, "Бот @$username чекає, коли ви натиснете Start", false)
        else -> InboxRow(true, "@$username · ${lastCheckLine(lastPass, now)}", false)
    }

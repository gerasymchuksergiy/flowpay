package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * «Telegram-скринька» — everything the inbox decides without a phone or a bot:
 * reading Telegram's answers, binding the owner's chat, what each message comes to,
 * every reply with sums shown and hidden, and the functions the inbox shares with the
 * share router, «Новий платіж» and «Додати покупку».
 *
 * Every chat id, token and message here is made up for these tests. The token is the
 * Bot API documentation's own example. Nothing was sent to Telegram: the request and
 * answer shapes are checked against core.telegram.org/bots/api, not a live bot.
 */
class InboxTest {

    // A Tuesday, the same day SubscriptionTextTest reads its letters on.
    private val today = LocalDate.of(2026, 10, 20)
    private val day = today.toEpochDay()

    private val owner = 5_550_001L
    private val code = "123456"

    private val rozetkaUrl = "https://rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649/"
    private val hotlineUrl = "https://hotline.ua/ua/av-naushniki-garnitury/jbl-tune-520bt-black-jblt520btblkeu/"

    private val megogoLetter =
        "MEGOGO: Ви оформили пробну підписку «Максимальна». Безкоштовно до 12 листопада, " +
            "далі 199 грн/міс. Скасувати можна будь-коли: https://megogo.net/ua/profile/subscriptions"
    private val internetLetter =
        "Шановний абоненте! З 01.11.2026 абонплата за тарифом «Домашній інтернет 500» становитиме " +
            "250 грн на місяць. Деталі на https://volia.com/ukr/tariffs"
    private val netflixLetter =
        "Your Netflix membership will renew on November 12, 2026. You'll be charged \$15.49 per month."
    private val waybillSms =
        "Rozetka: замовлення 123456789 передано в доставку. ТТН 2045 0000 0000 01. " +
            "Відстежити: https://novaposhta.ua/tracking/?cargo_number=20450000000001"

    private val internet = Pay("Інтернет", 200.0, day = 1)
    private val netflix = Pay("Netflix", 15.49, day = 12, currency = USD)

    private fun wish(id: String, name: String, url: String, price: Double, freshness: Freshness = Freshness.OK) =
        Wish(id, name, url, "", price, history = listOf(PricePoint(price, day - 30)), freshness = freshness)

    private fun step(
        text: String,
        wishes: List<Wish> = emptyList(),
        pays: List<Pay> = emptyList(),
        orders: List<Order> = emptyList(),
        hide: Boolean = false
    ) = inboxStep(text, wishes, pays, orders, today, hide)

    private fun said(step: InboxStep): String = (step as InboxStep.Say).text

    // ------------------------------------------------------------ Telegram's answers

    private val updatesBody = """
        {"ok":true,"result":[
         {"update_id":900000001,"message":{"message_id":11,"from":{"id":5550001,"is_bot":false,"first_name":"Test"},
          "chat":{"id":5550001,"first_name":"Test","type":"private"},"date":1792000000,"text":"/start 123456",
          "entities":[{"offset":0,"length":6,"type":"bot_command"}]}},
         {"update_id":900000002,"edited_message":{"message_id":9,"from":{"id":5550001,"is_bot":false,"first_name":"Test"},
          "chat":{"id":5550001,"type":"private"},"date":1792000000,"edit_date":1792000100,
          "text":"https://rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649/"}},
         {"update_id":900000003,"message":{"message_id":12,"from":{"id":5550001,"is_bot":false,"first_name":"Test"},
          "chat":{"id":5550001,"type":"private"},"date":1792000200,
          "photo":[{"file_id":"test-photo","file_unique_id":"test-unique","width":90,"height":90}],
          "caption":"Навушники https://rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649/"}},
         {"update_id":900000004,"message":{"message_id":3,"from":{"id":7770002,"is_bot":false,"first_name":"Stranger"},
          "chat":{"id":7770002,"type":"private"},"date":1792000300,"text":"https://prom.ua/ua/p2928860537-komplekt.html"}},
         {"update_id":900000005,"message":{"message_id":13,"from":{"id":5550001,"is_bot":false,"first_name":"Test"},
          "chat":{"id":5550001,"type":"private"},"date":1792000400,
          "photo":[{"file_id":"test-photo-2","file_unique_id":"test-unique-2","width":90,"height":90}]}}
        ]}
    """.trimIndent()

    private fun parsed(): List<TgUpdate> {
        val answer = tgAnswer(updatesBody)!!
        assertTrue(answer.ok)
        return updatesIn(answer.result)
    }

    @Test
    fun `every update is read, the ones the inbox ignores included`() {
        assertEquals(listOf(900000001L, 900000002L, 900000003L, 900000004L, 900000005L), parsed().map { it.id })
    }

    @Test
    fun `a text message is read with its chat, its id and its words`() {
        val message = parsed()[0].message!!

        assertEquals(owner, message.chatId)
        assertEquals("private", message.chatType)
        assertEquals(11L, message.messageId)
        assertEquals("/start 123456", message.text)
        assertFalse(message.media)
        assertFalse(message.fromBot)
    }

    @Test
    fun `an edited message is not a new message`() {
        assertNull(parsed()[1].message)
    }

    @Test
    fun `a photo's caption stands in for the text`() {
        val message = parsed()[2].message!!

        assertEquals("Навушники $rozetkaUrl", message.text)
        assertTrue(message.media)
    }

    @Test
    fun `a photo with no caption is media with no words`() {
        val message = parsed()[4].message!!

        assertNull(message.text)
        assertTrue(message.media)
    }

    @Test
    fun `a link hidden under words is added to the text`() {
        val message = tgMessageOf(
            org.json.JSONObject(
                """{"message_id":14,"chat":{"id":5550001,"type":"private"},"text":"Ось ці навушники",
                "entities":[{"type":"text_link","offset":4,"length":12,"url":"$rozetkaUrl"},
                            {"type":"bold","offset":0,"length":3}]}"""
            )
        )!!

        assertEquals("Ось ці навушники\n$rozetkaUrl", message.text)
        assertTrue(step(message.text!!) is InboxStep.AddWish)
    }

    @Test
    fun `a link already in the words is not added twice`() {
        val message = tgMessageOf(
            org.json.JSONObject(
                """{"message_id":15,"chat":{"id":5550001,"type":"private"},"text":"$rozetkaUrl",
                "entities":[{"type":"text_link","offset":0,"length":10,"url":"$rozetkaUrl"}]}"""
            )
        )!!

        assertEquals(rozetkaUrl, message.text)
    }

    @Test
    fun `an error answer keeps its code and the wait it asks for`() {
        val refused = tgAnswer("""{"ok":false,"error_code":401,"description":"Unauthorized"}""")!!
        assertFalse(refused.ok)
        assertEquals(401, refused.errorCode)
        assertEquals("Unauthorized", refused.description)

        val busy = tgAnswer("""{"ok":false,"error_code":429,"description":"Too Many Requests: retry after 3","parameters":{"retry_after":3}}""")!!
        assertEquals(429, busy.errorCode)
        assertEquals(3, busy.retryAfter)
    }

    @Test
    fun `a body that is not Telegram's JSON is no answer`() {
        assertNull(tgAnswer("<html><body>502 Bad Gateway</body></html>"))
        assertNull(tgAnswer(""))
        assertTrue(updatesIn(null).isEmpty())
        assertTrue(updatesIn(org.json.JSONObject()).isEmpty())
    }

    @Test
    fun `getMe gives the bot's username`() {
        val me = tgAnswer(
            """{"ok":true,"result":{"id":123456,"is_bot":true,"first_name":"FlowPay","username":"flow_inbox_test_bot","can_join_groups":true}}"""
        )!!

        assertEquals("flow_inbox_test_bot", botUsername(me.result))
        assertNull(botUsername(org.json.JSONObject().put("id", 1)))
        assertNull(botUsername(true))
    }

    // ------------------------------------------------------------ the offset

    @Test
    fun `the offset moves one past every update, ignored ones included, and never back`() {
        val offset = parsed().fold<TgUpdate, Long?>(null) { at, update -> offsetAfter(at, update) }

        assertEquals(900000006L, offset)
        assertEquals(900000006L, offsetAfter(900000006L, TgUpdate(900000001L, null)))
    }

    @Test
    fun `getUpdates asks for messages only, without waiting, after the offset`() {
        assertEquals("offset=900000006&timeout=0&allowed_updates=%5B%22message%22%5D", updatesQuery(900000006L))
        // No offset: every update Telegram still holds unconfirmed.
        assertEquals("timeout=0&allowed_updates=%5B%22message%22%5D", updatesQuery(null))
    }

    @Test
    fun `a stored offset is sent only while Telegram could still hold what it confirms`() {
        val now = 1_792_000_000_000L
        assertEquals(42L, offsetToSend(42L, now - 60_000L, now))
        // Older than a day: Telegram keeps nothing that long, and after a quiet week it
        // numbers the next update at random — ask for everything unconfirmed instead.
        assertNull(offsetToSend(42L, now - OFFSET_KEEP_MS - 1, now))
        assertNull(offsetToSend(0L, now, now))
        // A clock that went backwards proves nothing either way.
        assertNull(offsetToSend(42L, now + 60_000L, now))
    }

    // ------------------------------------------------------------ binding

    @Test
    fun `the code binds as the link sends it and as typed alone`() {
        assertEquals(code, bindingCode("/start 123456"))
        assertEquals(code, bindingCode("/start@flow_inbox_test_bot 123456"))
        assertEquals(code, bindingCode(" 123456 "))
        assertNull(bindingCode("/start"))
        assertNull(bindingCode("1234567"))
        assertNull(bindingCode("код 123456"))
        assertNull(bindingCode(null))
    }

    @Test
    fun `a code is six digits, leading zeros kept`() {
        repeat(50) { seed ->
            val drawn = newBindingCode(java.util.Random(seed.toLong()))
            assertEquals(6, drawn.length)
            assertTrue(drawn.all { it.isDigit() })
        }
    }

    @Test
    fun `before binding only the right code in a private chat counts`() {
        val updates = parsed()

        assertTrue(triage(updates[0], 0L, code) is Triage.Bind)
        // Everything else is ignored, the owner's own link included, and gets no answer.
        updates.drop(1).forEach { assertEquals(Triage.Ignore, triage(it, 0L, code)) }
        // A wrong code, or no code waiting, binds nothing.
        assertEquals(Triage.Ignore, triage(updates[0], 0L, "654321"))
        assertEquals(Triage.Ignore, triage(updates[0], 0L, ""))
    }

    @Test
    fun `the bare code binds too`() {
        val typed = TgUpdate(1L, TgMessage(owner, "private", 20L, "123456", media = false, fromBot = false))

        assertEquals(Triage.Bind(typed.message!!), triage(typed, 0L, code))
    }

    @Test
    fun `a group cannot be bound, nor a bot`() {
        val group = TgUpdate(1L, TgMessage(-100_200L, "group", 5L, "/start 123456", media = false, fromBot = false))
        val bot = TgUpdate(2L, TgMessage(owner, "private", 6L, "/start 123456", media = false, fromBot = true))

        assertEquals(Triage.Ignore, triage(group, 0L, code))
        assertEquals(Triage.Ignore, triage(bot, 0L, code))
    }

    @Test
    fun `once bound, only the owner's chat is read`() {
        val updates = parsed()

        assertTrue(triage(updates[0], owner, "") is Triage.Hello)
        assertEquals(Triage.Ignore, triage(updates[1], owner, ""))
        assertEquals(Triage.Handle(updates[2].message!!, "Навушники $rozetkaUrl"), triage(updates[2], owner, ""))
        // Another chat is ignored silently.
        assertEquals(Triage.Ignore, triage(updates[3], owner, ""))
        assertTrue(triage(updates[4], owner, "") is Triage.NotText)
    }

    @Test
    fun `a service message with neither words nor media is passed over`() {
        val pinned = TgUpdate(1L, TgMessage(owner, "private", 30L, null, media = false, fromBot = false))

        assertEquals(Triage.Ignore, triage(pinned, owner, ""))
    }

    @Test
    fun `start and help are greetings, a link is not`() {
        assertTrue(isGreeting("/start"))
        assertTrue(isGreeting("/help"))
        assertTrue(isGreeting("/start 123456"))
        assertFalse(isGreeting(rozetkaUrl))
        assertFalse(isGreeting("стартуємо"))
    }

    // ------------------------------------------------------------ every route

    @Test
    fun `a shop link is added as a wish`() {
        val step = step("Навушники JBL Tune 520BT — $rozetkaUrl")

        assertEquals(InboxStep.AddWish(rozetkaUrl), step)
    }

    @Test
    fun `a link already watched is answered with its price, never added twice`() {
        val wishes = listOf(wish("w1", "Навушники JBL Tune 520BT", rozetkaUrl, 1_599.0))

        val step = step("$rozetkaUrl?utm_source=telegram&utm_medium=share", wishes)

        assertEquals("👀 Уже стежу: Навушники JBL Tune 520BT — зараз ${money(1_599.0)}", said(step))
    }

    @Test
    fun `several links in one message add the first and say the rest were not`() {
        val text = "Глянь https://rozetka.com.ua/ua/a/p1/ і ще https://comfy.ua/b.html та https://allo.ua/c/"

        val step = step(text) as InboxStep.AddWish

        assertEquals("https://rozetka.com.ua/ua/a/p1/", step.url)
        assertEquals("Інших посилань із цього повідомлення не додано — надсилайте по одному", step.note)
        assertEquals(listOf("https://comfy.ua/b.html", "https://allo.ua/c/"), otherLinks(text))
        assertEquals("✅ Бажання: X\n${step.note}", withNote("✅ Бажання: X", step.note))
    }

    @Test
    fun `the same page twice is one link, and one link has no note`() {
        val twice = "$rozetkaUrl і ще раз $rozetkaUrl?utm_source=telegram"

        assertTrue(otherLinks(twice).isEmpty())
        assertNull((step(twice) as InboxStep.AddWish).note)
        assertNull((step(rozetkaUrl) as InboxStep.AddWish).note)
        assertEquals("✅ Бажання: X", withNote("✅ Бажання: X", null))
    }

    @Test
    fun `a watched link with others beside it says both`() {
        val wishes = listOf(wish("w1", "Навушники JBL Tune 520BT", rozetkaUrl, 1_599.0))

        assertEquals(
            "👀 Уже стежу: Навушники JBL Tune 520BT — зараз ${money(1_599.0)}\n" +
                "Інших посилань із цього повідомлення не додано — надсилайте по одному",
            said(step("$rozetkaUrl https://comfy.ua/b.html", wishes))
        )
    }

    @Test
    fun `a watched thing with no fresh price says so`() {
        assertEquals("👀 Уже стежу: Кросівки — ціни поки немає", knownWishReply(wish("w", "Кросівки", rozetkaUrl, 0.0)))
        assertEquals(
            "👀 Уже стежу: Кросівки — востаннє ${money(2_100.0)}",
            knownWishReply(wish("w", "Кросівки", rozetkaUrl, 2_100.0, Freshness.OUT_OF_STOCK))
        )
    }

    @Test
    fun `a waybill SMS with a link is a parcel, not a wish`() {
        assertEquals(InboxStep.AddParcel("20450000000001"), step(waybillSms))
    }

    @Test
    fun `a waybill already on the list is answered with its status`() {
        val parcel = Order(
            "o1", "Посилка …0001", "", IN_TRANSIT, tracking = "20450000000001",
            statusDetail = "Відправлення у м. Київ · Київ, відділення №5", checkedAt = 1L
        )

        assertEquals("📦 Посилка …0001: Відправлення у м. Київ · Київ, відділення №5", said(step(waybillSms, orders = listOf(parcel))))
        assertEquals(
            "📦 Навушники JBL (…0001): Відправлення у м. Київ · Київ, відділення №5",
            said(step(waybillSms, orders = listOf(parcel.copy(name = "Навушники JBL"))))
        )
    }

    @Test
    fun `a postal number with no link is a parcel too`() {
        assertEquals(InboxStep.AddParcel("RL778364634EE"), step("Ваша посилка RL778364634EE вже в Україні"))
    }

    @Test
    fun `a Megogo trial letter adds the payment, free until the trial ends`() {
        val step = step(megogoLetter, pays = listOf(internet)) as InboxStep.AddPay

        assertEquals("Megogo", step.pay.name)
        assertEquals(199.0, step.pay.amount, 0.0)
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay(), step.pay.trialEnd)
        assertEquals(0.0, step.pay.promoPrice, 0.0)
        assertEquals(
            "🧾 Платіж: Megogo — 199 ₴ щомісяця, наступне 12.11 (пробний до 12.11). Якщо щось не так — виправте у FlowPay",
            step.reply
        )
    }

    @Test
    fun `the trial letter's reply hides its sum when sums are hidden outside the app`() {
        val step = step(megogoLetter, hide = true) as InboxStep.AddPay

        assertEquals(
            "🧾 Платіж: Megogo — ••• ₴ щомісяця, наступне 12.11 (пробний до 12.11). Якщо щось не так — виправте у FlowPay",
            step.reply
        )
        // Only the reply: the payment itself keeps its figure.
        assertEquals(199.0, step.pay.amount, 0.0)
    }

    @Test
    fun `a price-change letter changes the price, as «Оновити ціну» does`() {
        val step = step(internetLetter, pays = listOf(Pay("Оренда", 8_000.0), internet)) as InboxStep.NewPrice

        assertEquals(internet, step.pay)
        assertEquals(250.0, step.amount, 0.0)
        assertEquals("✏️ «Інтернет»: 200 → 250 ₴ з 1 листопада", step.reply)
        // What the worker then writes: the dialog's own confirm, through the history.
        val changed = withAmount(step.pay, step.amount, day)
        assertEquals(250.0, changed.amount, 0.0)
        assertEquals(listOf(200.0, 250.0), changed.amounts.map { it.price })
    }

    @Test
    fun `a price change says a charge still due at the old price`() {
        val late = Pay("Інтернет", 200.0, day = 25)

        val step = step(internetLetter, pays = listOf(late)) as InboxStep.NewPrice

        assertEquals("✏️ «Інтернет»: 200 → 250 ₴ з 1 листопада. Списання 25 жовтня ще за старою ціною, 200 ₴", step.reply)
    }

    @Test
    fun `a price change hides both sums when asked`() {
        val step = step(internetLetter, pays = listOf(internet), hide = true) as InboxStep.NewPrice

        assertEquals("✏️ «Інтернет»: ••• → ••• ₴ з 1 листопада", step.reply)
        val late = step(internetLetter, pays = listOf(Pay("Інтернет", 200.0, day = 25)), hide = true) as InboxStep.NewPrice
        assertEquals("✏️ «Інтернет»: ••• → ••• ₴ з 1 листопада. Списання 25 жовтня ще за старою ціною, ••• ₴", late.reply)
    }

    @Test
    fun `replies with no sum in them read the same with sums hidden`() {
        val parcel = parcelOrder("20450000000001", "o1")
        listOf<Triple<String, List<Pay>, List<Order>>>(
            Triple(netflixLetter, listOf(netflix), emptyList()),
            Triple("Ваш пробний період Netflix закінчується 12 листопада.", emptyList(), emptyList()),
            Triple(waybillSms, emptyList(), listOf(parcel)),
            Triple(hotlineUrl, emptyList(), emptyList()),
            Triple("Привіт!", emptyList(), emptyList())
        ).forEach { (text, pays, orders) ->
            assertEquals(said(step(text, pays = pays, orders = orders)), said(step(text, pays = pays, orders = orders, hide = true)))
        }
    }

    @Test
    fun `the same price is just said`() {
        assertEquals("ℹ️ «Netflix» уже є, ціна та сама", said(step(netflixLetter, pays = listOf(netflix))))
    }

    @Test
    fun `a letter with no price adds nothing on a guess`() {
        val reply = said(step("Ваш пробний період Netflix закінчується 12 листопада."))

        assertEquals(
            "🧾 Лист про «Netflix», але ціни в ньому немає — платіж не додано. Надішліть лист із сумою або додайте платіж у FlowPay",
            reply
        )
    }

    @Test
    fun `a letter about a payment kept in another currency changes nothing`() {
        val inHryvnia = Pay("Netflix", 549.0, day = 12)

        assertEquals(
            "🧾 «Netflix» уже є у FlowPay, але в іншій валюті: у листі ${amountLabel(15.49, USD)}, у FlowPay ${money(549.0)}. " +
                "Нічого не змінено — перевірте у FlowPay",
            said(step(netflixLetter, pays = listOf(inHryvnia)))
        )
        assertEquals(
            "🧾 «Netflix» уже є у FlowPay, але в іншій валюті: у листі ••• $, у FlowPay ••• ₴. Нічого не змінено — перевірте у FlowPay",
            said(step(netflixLetter, pays = listOf(inHryvnia), hide = true))
        )
    }

    @Test
    fun `a Hotline product page is left for the phone`() {
        assertEquals(INBOX_HOTLINE, said(step(hotlineUrl)))
        assertEquals("Сторінку Hotline прив'язати до бажання поки можна лише з телефону: Поділитися → FlowPay", INBOX_HOTLINE)
    }

    @Test
    fun `plain chatter is not understood, and says why`() {
        assertEquals(INBOX_UNCLEAR, said(step("Привіт! Ввечері купимо хліб і молоко")))
        assertEquals("🤔 Не розумію: тут немає ні посилання на товар, ні номера посилки, ні листа про підписку", INBOX_UNCLEAR)
    }

    @Test
    fun `a shop's price is never hidden, whatever the switch says`() {
        val wishes = listOf(wish("w1", "Навушники JBL Tune 520BT", rozetkaUrl, 1_599.0))

        assertEquals(
            "👀 Уже стежу: Навушники JBL Tune 520BT — зараз ${money(1_599.0)}",
            said(step(rozetkaUrl, wishes, hide = true))
        )
    }

    // ------------------------------------------------------------ the other replies

    @Test
    fun `the fixed replies are the agreed words`() {
        assertEquals("Готово: це ваша скринька FlowPay. Надсилайте посилання, номери посилок і листи про підписки", INBOX_READY)
        assertEquals("Поки що розумію лише текст і посилання", INBOX_NOT_TEXT)
    }

    @Test
    fun `a new wish says its name, price and shop`() {
        val read = wish("w1", "Навушники JBL Tune 520BT", rozetkaUrl, 1_599.0)

        assertEquals("✅ Бажання: Навушники JBL Tune 520BT — ${money(1_599.0)} (rozetka.com.ua)", newWishReply(read, rozetkaUrl))
        assertEquals(
            "✅ Бажання: Навушники JBL Tune 520BT — ${money(1_599.0)} (rozetka.com.ua) · немає в наявності",
            newWishReply(read.copy(freshness = Freshness.OUT_OF_STOCK), rozetkaUrl)
        )
    }

    @Test
    fun `a new wish without a price promises the next check`() {
        assertEquals(
            "✅ Бажання додано (rozetka.com.ua), але ціну прочитати не вдалося — спробую під час перевірки цін",
            newWishReply(null, rozetkaUrl)
        )
        // The page named the thing and kept its price back.
        assertEquals(
            "✅ Бажання: Навушники JBL Tune 520BT (rozetka.com.ua) — ціну прочитати не вдалося, спробую під час перевірки цін",
            newWishReply(wish("w1", "Навушники JBL Tune 520BT", rozetkaUrl, 0.0, Freshness.UNREADABLE), rozetkaUrl)
        )
        // A placeholder's own name is not a name read from the page.
        assertEquals(newWishReply(null, rozetkaUrl), newWishReply(placeholderWish(rozetkaUrl, "w1", day), rozetkaUrl))
    }

    @Test
    fun `a new parcel says what Nova Poshta answered, or that it will be asked`() {
        val added = parcelOrder("20450000000001", "o1")
        val checked = added.copy(statusDetail = "Прибув у відділення · Київ, відділення №5", checkedAt = 1L)

        assertEquals("📦 Додано посилку …0001: Прибув у відділення · Київ, відділення №5", newParcelReply(checked, true, day))
        assertEquals("📦 Додано посилку …0001. Статус дізнаюся під час наступної перевірки", newParcelReply(added, false, day))
        assertEquals(
            "📦 Додано посилку …34EE. Це не номер Нової пошти — FlowPay сам її не перевіряє",
            newParcelReply(parcelOrder("RL778364634EE", "o2"), false, day)
        )
    }

    @Test
    fun `a known parcel in trouble says which trouble`() {
        val refused = parcelOrder("20450000000001", "o1").copy(problem = true, statusCode = 103, checkedAt = 1L)

        assertEquals("📦 Посилка …0001: ${problemNote(103)}", knownParcelReply(refused, "20450000000001", day))
    }

    @Test
    fun `a known parcel never asked about says so`() {
        assertEquals(
            "📦 Посилка …0001: Замовлено · статус ще не перевірено",
            knownParcelReply(parcelOrder("20450000000001", "o1"), "20450000000001", day)
        )
    }

    @Test
    fun `a return waybill is answered as the return`() {
        val going = parcelOrder("20450000000001", "o1").copy(
            name = "Навушники JBL",
            archivedDay = day - 10,
            refund = Refund(startedDay = day - 3, amount = 1_599.0, tracking = "20450000000099")
        )

        assertEquals(
            "📦 Навушники JBL (…0099): повернення — відправлено · стежу за зворотною накладною",
            knownParcelReply(going, "20450000000099", day)
        )
    }

    @Test
    fun `dates in replies are short this year and carry the year beyond it`() {
        assertEquals("12.11", dottedDate(LocalDate.of(2026, 11, 12), today))
        assertEquals("15.01.2027", dottedDate(LocalDate.of(2027, 1, 15), today))
    }

    @Test
    fun `a yearly fee says so, with its date`() {
        val step = step("Нагадуємо: річна підписка Notion продовжиться 15 січня 2027, сума 4 200 грн на рік.") as InboxStep.AddPay

        assertEquals(1, step.pay.billingMonth)
        assertEquals(
            "🧾 Платіж: Notion — ${money(4_200.0)} щороку, наступне 15.01.2027. Якщо щось не так — виправте у FlowPay",
            step.reply
        )
    }

    @Test
    fun `a letter with no date says the day was assumed`() {
        val pay = letterPay(SubscriptionDraft("Spotify", 169.0, UAH, null, 0, 0L, null), today)

        assertEquals(
            "🧾 Платіж: Spotify — 169 ₴ щомісяця, наступне 01.11 — дати в листі немає, тож узято 1 число. Якщо щось не так — виправте у FlowPay",
            newPayReply(pay, dated = false, today = today)
        )
    }

    // ------------------------------------------------------------ the shared functions

    @Test
    fun `a letter's payment is what the form would save untouched`() {
        val draft = parseSubscription(megogoLetter, today)!!

        val pay = letterPay(draft, today)

        assertEquals(
            Pay(
                name = "Megogo",
                amount = 199.0,
                day = 12,
                currency = UAH,
                warnDays = LONG_NOTICE_DAYS,
                amounts = listOf(PricePoint(199.0, day)),
                trialEnd = LocalDate.of(2026, 11, 12).toEpochDay(),
                billingMonth = 0
            ),
            pay
        )
        // §21: free until the trial ends, the price after, and the first real charge then.
        assertEquals(0.0, priceOn(pay, day), 0.0)
        assertEquals(199.0, priceOn(pay, LocalDate.of(2026, 11, 12).toEpochDay()), 0.0)
        assertEquals(LocalDate.of(2026, 11, 12), nextCharge(pay, today))
    }

    @Test
    fun `a plain monthly letter keeps the default notice, a trial or a yearly fee the long one`() {
        val plain = letterPay(SubscriptionDraft("Kyivstar", 175.0, UAH, 5, 0, 0L, null), today)
        val yearly = letterPay(SubscriptionDraft("Notion", 4_200.0, UAH, 15, 1, 0L, null), today)

        assertEquals(DEFAULT_WARN_DAYS, plain.warnDays)
        assertEquals(LONG_NOTICE_DAYS, yearly.warnDays)
        assertTrue(longNotice(1L, 0))
        assertTrue(longNotice(0L, 3))
        assertFalse(longNotice(0L, 0))
    }

    @Test
    fun `no day in the letter is the form's default first`() {
        assertEquals(1, letterPay(SubscriptionDraft("Spotify", 169.0, UAH, null, 0, 0L, null), today).day)
        assertEquals("Інше", letterPay(SubscriptionDraft("  ", 99.0, UAH, 3, 0, 0L, null), today).name)
    }

    @Test
    fun `a waybill makes the same purchase the add form makes from a number`() {
        assertEquals(
            Order("o1", "Посилка …0001", "", ORDERED, tracking = "20450000000001"),
            parcelOrder("2045 0000 0000 01", "o1")
        )
    }

    @Test
    fun `filling a placeholder keeps its id and takes the page's name and price`() {
        val kept = placeholderWish(rozetkaUrl, "id-1", day)
        val read = Wish("scraped", "Навушники JBL Tune 520BT", rozetkaUrl, "https://example.test/p.jpg", 1_599.0, history = emptyList())

        val filled = filledWish(kept, read, day, FxRate())

        assertEquals("id-1", filled.id)
        assertEquals("Навушники JBL Tune 520BT", filled.name)
        assertEquals(1_599.0, filled.price, 0.0)
        assertEquals(1, filled.history.size)
        assertEquals(refreshedWish(kept, read, day, FxRate()).copy(name = read.name), filled)
    }

    @Test
    fun `a new id never repeats one already taken`() {
        assertEquals("1000", freshId(1000L, emptyList()))
        assertEquals("1002", freshId(1000L, listOf("1000", "1001")))
    }

    // ------------------------------------------------------------ the router, as before

    @Test
    fun `the router asks the letter first, even with a link and an order number in it`() {
        val route = shareRoute(megogoLetter, emptyList(), emptyList(), today)

        assertTrue(route is ShareRoute.Letter && route.letter is SharedLetter.NewPayment)
        assertTrue(shareRoute(internetLetter, emptyList(), listOf(internet), today).let { it is ShareRoute.Letter && it.letter is SharedLetter.PriceChange })
        assertTrue(shareRoute(netflixLetter, emptyList(), listOf(netflix), today).let { it is ShareRoute.Letter && it.letter is SharedLetter.SamePrice })
    }

    @Test
    fun `the router sends a waybill to purchases, link or no link`() {
        assertEquals(ShareRoute.Parcel("20450000000001"), shareRoute(waybillSms, emptyList(), emptyList(), today))
        assertEquals(
            ShareRoute.Parcel("20450000000001"),
            shareRoute("Відстежуйте: https://novaposhta.ua/tracking/?cargo_number=20450000000001", emptyList(), emptyList(), today)
        )
        assertEquals(ShareRoute.Parcel("20450000000001"), shareRoute("Ваша посилка 20450000000001 у відділенні №12", emptyList(), emptyList(), today))
    }

    @Test
    fun `the router keeps digits inside a product link a link`() {
        assertEquals(
            ShareRoute.NewLink("https://shop.example/p/12345678901234/"),
            shareRoute("https://shop.example/p/12345678901234/ класні", emptyList(), emptyList(), today)
        )
    }

    @Test
    fun `the router follows a postal number only when there is no link`() {
        assertEquals(ShareRoute.Parcel("RL778364634EE"), shareRoute("Посилка RL778364634EE", emptyList(), emptyList(), today))
        assertEquals(
            ShareRoute.NewLink("https://temu.com/order/1"),
            shareRoute("Tracking RL778364634EE https://temu.com/order/1", emptyList(), emptyList(), today)
        )
    }

    @Test
    fun `the router opens a watched link, binds a Hotline page and adds anything else`() {
        val watched = wish("w1", "Навушники JBL", rozetkaUrl, 1_599.0)

        val known = shareRoute("Навушники JBL $rozetkaUrl", listOf(watched), emptyList(), today)
        assertEquals("w1", wishToOpen((known as ShareRoute.Known).link))
        assertEquals(ShareRoute.Market(hotlineUrl), shareRoute("$hotlineUrl?tab=prices#offers", emptyList(), emptyList(), today))
        assertEquals(ShareRoute.NewLink(rozetkaUrl), shareRoute(rozetkaUrl, emptyList(), emptyList(), today))
        assertEquals(ShareRoute.Unclear, shareRoute("просто текст", emptyList(), emptyList(), today))
        assertEquals(ShareRoute.Unclear, shareRoute(null, emptyList(), emptyList(), today))
    }

    // ------------------------------------------------------------ the requests

    @Test
    fun `a reply is plain text with no preview, answering its message`() {
        val body = sendMessageBody(owner, "✅ Бажання: Навушники", 12L)

        assertEquals(owner, body.getLong("chat_id"))
        assertEquals("✅ Бажання: Навушники", body.getString("text"))
        assertTrue(body.getJSONObject("link_preview_options").getBoolean("is_disabled"))
        assertEquals(12L, body.getJSONObject("reply_parameters").getLong("message_id"))
        assertTrue(body.getJSONObject("reply_parameters").getBoolean("allow_sending_without_reply"))
        assertFalse(body.has("parse_mode"))
    }

    @Test
    fun `a reply to nothing carries no reply parameters, and a long one is cut`() {
        val body = sendMessageBody(owner, "а".repeat(5_000), 0L)

        assertFalse(body.has("reply_parameters"))
        assertEquals(TG_TEXT_LIMIT, body.getString("text").length)
    }

    @Test
    fun `the token is found alone or inside BotFather's whole message`() {
        val docsExample = "123456:ABC-DEF1234ghIkl-zyx57W2v1u123ew11"

        assertEquals(docsExample, botTokenIn(docsExample))
        assertEquals(docsExample, botTokenIn("  $docsExample\n"))
        assertEquals(
            docsExample,
            botTokenIn(
                "Done! Congratulations on your new bot. You will find it at t.me/flow_inbox_test_bot.\n\n" +
                    "Use this token to access the HTTP API:\n$docsExample\nKeep your token secure and store it safely."
            )
        )
        assertEquals(docsExample, botTokenIn("bot$docsExample"))
        assertNull(botTokenIn("привіт"))
        assertNull(botTokenIn("123456:short"))
        assertNull(botTokenIn(null))
    }

    @Test
    fun `the start link carries the code`() {
        assertEquals("https://t.me/flow_inbox_test_bot?start=123456", startLink("flow_inbox_test_bot", code))
    }

    // ------------------------------------------------------------ what waits to be sent

    @Test
    fun `unsent replies survive the round trip, the newest thirty kept`() {
        val replies = (1..35).map { TgReply(owner, it.toLong(), "відповідь $it") }

        val back = repliesOf(repliesJson(replies))

        assertEquals(replies.takeLast(OUTBOX_LIMIT), back)
        assertTrue(repliesOf(null).isEmpty())
        assertTrue(repliesOf("не json").isEmpty())
    }

    // ------------------------------------------------------------ the settings row

    private fun at(time: LocalDateTime) = time.toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `the last check reads as a time, yesterday, or a date`() {
        val utc = ZoneId.of("UTC")
        val now = at(LocalDateTime.of(2026, 10, 20, 18, 0))

        assertEquals("перевірено о 14:05", lastCheckLine(at(LocalDateTime.of(2026, 10, 20, 14, 5)), now, utc))
        assertEquals("перевірено вчора о 09:30", lastCheckLine(at(LocalDateTime.of(2026, 10, 19, 9, 30)), now, utc))
        assertEquals("перевірено 3 жовтня о 18:00", lastCheckLine(at(LocalDateTime.of(2026, 10, 3, 18, 0)), now, utc))
        assertEquals("ще не перевірено", lastCheckLine(0L, now, utc))
    }

    @Test
    fun `the row says what state the inbox is in`() {
        val now = System.currentTimeMillis()

        assertFalse(inboxRow(false, false, "", "", 0L, now).connected)
        assertEquals("Бот @flow_inbox_test_bot чекає, коли ви натиснете Start", inboxRow(true, false, "flow_inbox_test_bot", "", 0L, now).line)
        assertTrue(inboxRow(true, true, "flow_inbox_test_bot", INBOX_TOKEN_REFUSED, now, now).alarm)
        assertTrue(inboxRow(true, true, "flow_inbox_test_bot", "", now, now).line.startsWith("@flow_inbox_test_bot · перевірено о "))
    }

    @Test
    fun `only real faults are shown`() {
        assertEquals("Токен не підходить — його могли відкликати у @BotFather", inboxFault(401))
        assertEquals(INBOX_TOKEN_REFUSED, inboxFault(404))
        assertEquals(INBOX_OTHER_READER, inboxFault(409))
        // Telegram's own trouble and flood control are only «try again».
        assertNull(inboxFault(429))
        assertNull(inboxFault(500))
        assertNull(inboxFault(502))
    }

    @Test
    fun `a refused token on connecting says what to copy`() {
        assertTrue(connectProblem(401).contains("@BotFather"))
        assertTrue(connectProblem(500).contains("500"))
    }
}

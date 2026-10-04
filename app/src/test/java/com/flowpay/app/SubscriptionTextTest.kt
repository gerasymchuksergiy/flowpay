package com.flowpay.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * «Поділись листом про підписку». Every sample is the shape a real letter, SMS or
 * Viber message takes — written for these tests, no one's real mail. The two
 * failures the research warned about are pinned at the bottom: a product page
 * «від 499 ₴/міс» taken for a subscription, and a letter that becomes a wish.
 */
class SubscriptionTextTest {

    // A Tuesday; the letters below are read on it.
    private val today = LocalDate.of(2026, 10, 20)

    private fun parsed(text: String): SubscriptionDraft {
        val draft = parseSubscription(text, today)
        assertNotNull("not read as a subscription: $text", draft)
        return draft!!
    }

    // ------------------------------------------------------------ Ukrainian

    @Test
    fun `a Megogo trial letter fills the name, the price after the trial and the trial's end`() {
        val draft = parsed(
            "MEGOGO: Ви оформили пробну підписку «Максимальна». Безкоштовно до 12 листопада, " +
                "далі 199 грн/міс. Скасувати можна будь-коли: https://megogo.net/ua/profile/subscriptions"
        )

        assertEquals("Megogo", draft.name)
        assertEquals(199.0, draft.amount, 0.0)
        assertEquals(UAH, draft.currency)
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay(), draft.trialEnd)
        assertEquals(12, draft.day)
        assertEquals(0, draft.billingMonth)
        assertEquals("Megogo · пробний до 12 лист., далі 199 ₴/міс", draftLine(draft))
    }

    @Test
    fun `the research's own one-liner reads the same way`() {
        val draft = parsed("Megogo · пробний до 12 лист., далі 199 ₴/міс")

        assertEquals("Megogo · пробний до 12 лист., далі 199 ₴/міс", draftLine(draft))
    }

    @Test
    fun `an internet provider's new fee is read with the date it starts`() {
        val draft = parsed(
            "Шановний абоненте! З 01.11.2026 абонплата за тарифом «Домашній інтернет 500» становитиме " +
                "250 грн на місяць. Деталі на https://volia.com/ukr/tariffs"
        )

        assertEquals(250.0, draft.amount, 0.0)
        assertEquals(LocalDate.of(2026, 11, 1), draft.from)
        assertEquals(1, draft.day)
        assertEquals("Воля", draft.name)
    }

    @Test
    fun `a mobile operator's SMS is read`() {
        val draft = parsed("Kyivstar: 05.11 буде списано 175 грн абонплати за тариф «Безлім». Поповніть рахунок заздалегідь.")

        assertEquals("Kyivstar", draft.name)
        assertEquals(175.0, draft.amount, 0.0)
        assertEquals(5, draft.day)
    }

    @Test
    fun `gigabytes are not a price`() {
        val draft = parsed(
            "Ваша підписка на iCloud+ (50 ГБ) автоматично продовжиться 15.11.2026. Ціна: 49 грн на місяць. " +
                "Керувати: Параметри → Apple ID."
        )

        assertEquals("iCloud+", draft.name)
        assertEquals(49.0, draft.amount, 0.0)
        assertEquals(15, draft.day)
    }

    @Test
    fun `a raise names the new price, not the old one beside it`() {
        val draft = parsed(
            "Ціна вашої підписки YouTube Premium змінюється: з 12 грудня 2026 р. вона становитиме " +
                "179 грн/міс замість 99 грн/міс."
        )

        assertEquals("YouTube Premium", draft.name)
        assertEquals(179.0, draft.amount, 0.0)
        assertEquals(LocalDate.of(2026, 12, 12), draft.from)
    }

    @Test
    fun `from one figure to another, the second is the price`() {
        assertEquals(250.0, parsed("Абонплата за інтернет зростає з 200 грн на 250 грн з 1 листопада.").amount, 0.0)
    }

    @Test
    fun `a yearly fee is yearly, in the month it is charged`() {
        val draft = parsed("Нагадуємо: річна підписка Notion продовжиться 15 січня 2027, сума 4 200 грн на рік.")

        assertEquals(4_200.0, draft.amount, 0.0)
        assertEquals(1, draft.billingMonth)
        assertEquals(15, draft.day)
    }

    @Test
    fun `month names are read whatever their case`() {
        assertEquals(12, parsed("Пробний період Sweet.tv діє до 12 Листопада, потім 99 грн щомісяця").day)
    }

    // ------------------------------------------------------------ English

    @Test
    fun `a Netflix renewal in dollars`() {
        val draft = parsed(
            "Your Netflix membership will renew on November 12, 2026. You'll be charged $15.49 per month. " +
                "Manage your subscription at netflix.com/account"
        )

        assertEquals("Netflix", draft.name)
        assertEquals(15.49, draft.amount, 0.0)
        assertEquals(USD, draft.currency)
        assertEquals(12, draft.day)
        assertEquals(0, draft.billingMonth)
    }

    @Test
    fun `a Spotify free trial ending`() {
        val draft = parsed("Your Spotify Premium free trial ends on 3 Nov 2026. After that, you'll pay 169 UAH/month. Cancel anytime.")

        assertEquals("Spotify", draft.name)
        assertEquals(169.0, draft.amount, 0.0)
        assertEquals(UAH, draft.currency)
        assertEquals(LocalDate.of(2026, 11, 3).toEpochDay(), draft.trialEnd)
    }

    @Test
    fun `an annual plan with the currency written first`() {
        val draft = parsed("Thanks for subscribing to 1Password Families. Your annual subscription of $59.88 renews on 2027-01-15.")

        assertEquals("1Password", draft.name)
        assertEquals(59.88, draft.amount, 0.0)
        assertEquals(USD, draft.currency)
        assertEquals(1, draft.billingMonth)
        assertEquals(15, draft.day)
    }

    @Test
    fun `hryvnias written as a code before the figure`() {
        val draft = parsed("Google One: your plan (100 GB) will renew on Oct 28 for UAH 59.00/month.")

        assertEquals("Google One", draft.name)
        assertEquals(59.0, draft.amount, 0.0)
        assertEquals(UAH, draft.currency)
        assertEquals(28, draft.day)
    }

    @Test
    fun `an unknown service is named from the letter`() {
        assertEquals("Readymag", parsed("Your Readymag subscription renews on Nov 2 for $16 per month.").name)
        assertEquals("Acme Cloud", parsed("From: Acme Cloud <billing@acme.example>\nYour subscription renews on 4 Dec, $5/month.").name)
        assertEquals("Підписка", parsed("Ваша підписка автоматично продовжиться 9.11, 120 грн").name)
    }

    @Test
    fun `a Latin name right after «підписка» is the name`() {
        val draft = parsed("Підписка Rozetka Premium продовжиться 05.11 — 199 грн на місяць")

        assertEquals("Rozetka Premium", draft.name)
        assertEquals(199.0, draft.amount, 0.0)
        assertEquals(5, draft.day)
    }

    @Test
    fun `an Apple receipt with the hryvnia sign in front`() {
        val draft = parsed(
            "Apple Music Individual (Monthly)\nRenews November 12, 2026\nTotal ₴169.00\n" +
                "You can manage your subscriptions in Settings."
        )

        assertEquals("Apple Music", draft.name)
        assertEquals(169.0, draft.amount, 0.0)
        assertEquals(UAH, draft.currency)
        assertEquals(12, draft.day)
        assertEquals(0, draft.billingMonth)
    }

    @Test
    fun `a letter with no price still opens the form, with the box left empty`() {
        val draft = parsed("Ваш пробний період Netflix закінчується 12 листопада.")

        assertEquals(0.0, draft.amount, 0.0)
        assertEquals("Netflix", draft.name)
        assertEquals(LocalDate.of(2026, 11, 12).toEpochDay(), draft.trialEnd)
    }

    // ------------------------------------------------------------ what is not a subscription

    @Test
    fun `a product page with instalments is not a subscription`() {
        assertNull(parseSubscription("Ноутбук Lenovo IdeaPad 5 — від 499 ₴/міс у розстрочку https://rozetka.com.ua/ua/lenovo/p123/", today))
        assertNull(parseSubscription("Купуй частинами: 6 платежів по 1 000 грн на місяць без переплат", today))
    }

    @Test
    fun `a parcel SMS is not a subscription`() {
        assertNull(parseSubscription("Ваше відправлення 20450123456789 прибуло у відділення №12. Сума до сплати 1 249 грн.", today))
    }

    @Test
    fun `a gift card or a newsletter is not a subscription, however it is worded`() {
        assertNull(parseSubscription("Подарункова підписка Netflix на 3 місяці — 999 грн https://shop.example/netflix-gift", today))
        assertNull(parseSubscription("Оформіть підписку на новини і отримайте знижку 100 грн на перше замовлення", today))
    }

    @Test
    fun `a subscription word only inside a link does not count`() {
        assertNull(parseSubscription("Дивись які навушники https://shop.example/subscription-deals/p1 всього 999 грн на місяць", today))
    }

    @Test
    fun `nothing at all is nothing`() {
        assertNull(parseSubscription(null, today))
        assertNull(parseSubscription("   ", today))
    }

    // ------------------------------------------------------------ against the list

    private val internet = Pay("Інтернет", 200.0, day = 1)
    private val netflix = Pay("Netflix", 15.49, day = 12, currency = USD)

    @Test
    fun `a new price for a payment on the list offers to update it`() {
        val letter = subscriptionLetter(
            "З 01.11.2026 абонплата за тарифом «Домашній інтернет 500» становитиме 250 грн на місяць.",
            listOf(Pay("Оренда", 8_000.0), internet),
            today
        )

        letter as SharedLetter.PriceChange
        assertEquals(internet, letter.pay)
        assertEquals("Інтернет", letter.draft.name)
        assertEquals("Оновити ціну з 1 лист.: 200 → 250 ₴", priceChangeLabel(letter))
    }

    @Test
    fun `the same price for a payment on the list is just that`() {
        val letter = subscriptionLetter(
            "Your Netflix membership will renew on November 12, 2026. You'll be charged $15.49 per month.",
            listOf(netflix),
            today
        )

        assertTrue(letter is SharedLetter.SamePrice)
    }

    @Test
    fun `nothing on the list named means a new payment`() {
        val letter = subscriptionLetter("Megogo · пробний до 12 лист., далі 199 ₴/міс", listOf(internet, netflix), today)

        letter as SharedLetter.NewPayment
        assertEquals("Megogo", letter.draft.name)
    }

    @Test
    fun `a payment called just «Підписка» is not every subscription letter`() {
        val letter = subscriptionLetter("Megogo · пробний до 12 лист., далі 199 ₴/міс", listOf(Pay("Підписка", 99.0)), today)

        assertTrue(letter is SharedLetter.NewPayment)
    }

    @Test
    fun `a name is matched as a word, not inside another one`() {
        assertNull(paymentNamedIn("Підписка на газету «День»: 120 грн щомісяця", listOf(Pay("Газ", 400.0))))
        assertEquals(internet, paymentNamedIn("Інтернету буде підвищено тариф", listOf(internet)))
        assertEquals(netflix, paymentNamedIn("Нетфлікс дорожчає", listOf(netflix.copy(name = "Нетфлікс")))?.copy(name = "Netflix"))
    }

    @Test
    fun `the longest name wins when two are named`() {
        val pays = listOf(Pay("YouTube", 99.0), Pay("YouTube Premium Family", 199.0))

        assertEquals("YouTube Premium Family", paymentNamedIn("YouTube Premium Family: ціна змінюється", pays)?.name)
    }

    @Test
    fun `a charge still due at the old price before the new one is found`() {
        // The 25th of October is still at 200; the new price starts on the 1st.
        val pay = Pay("Інтернет", 200.0, day = 25)

        assertEquals(LocalDate.of(2026, 10, 25), oldPriceChargeBefore(pay, LocalDate.of(2026, 11, 1), today))
        assertNull(oldPriceChargeBefore(internet, LocalDate.of(2026, 11, 1), today))
        assertNull(oldPriceChargeBefore(pay, null, today))
    }

    @Test
    fun `dates read as a short Ukrainian line writes them`() {
        assertEquals("1 лист.", shortDate(LocalDate.of(2026, 11, 1)))
        assertEquals("12 груд.", shortDate(LocalDate.of(2026, 12, 12)))
    }
}

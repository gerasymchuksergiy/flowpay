package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.flowpay.app.AT_BRANCH
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FxRate
import com.flowpay.app.IN_TRANSIT
import com.flowpay.app.Order
import com.flowpay.app.PaidMark
import com.flowpay.app.Pay
import com.flowpay.app.PricePoint
import com.flowpay.app.RECEIVED
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.Store
import com.flowpay.app.SumsMask
import com.flowpay.app.TAB_ORDERS
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.TAB_PAYMENTS
import com.flowpay.app.TAB_RATE
import com.flowpay.app.TAB_WISHES
import com.flowpay.app.USD
import com.flowpay.app.Wish
import com.flowpay.app.monthKey
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

/**
 * The screens this work touches, drawn on the JVM like [ScreenShots]: every tab
 * with the eye on Огляд shut (one sum left showing undoes the mode, so each is
 * looked at), the recap deck «Без сум», and a letter about a subscription arriving
 * through the share sheet. PNGs in build/outputs/roborazzi/touch/.
 *
 * Run with `./gradlew :app:testDebugUnitTest -Pshots --tests '*TouchShots*'`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h873dp-440dpi")
class TouchShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private val day = today.toEpochDay()

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        app.getSharedPreferences("flowpay-mono", 0).edit()
            .putString("tok", "screenshot")
            .putString("client", com.flowpay.app.monoClientJson(monoClient()).toString())
            .putString("tx", org.json.JSONArray(statement().map { com.flowpay.app.monoTxJson(it) }).toString())
            .putLong("sync", System.currentTimeMillis())
            .commit()
        Store(app).run {
            saveWishes(sampleWishes())
            savePays(samplePays())
            saveOrders(sampleOrders())
            val last = today.minusMonths(1)
            savePaidMarks(
                listOf(
                    PaidMark("Мобільний", monthKey(today), 231.0),
                    PaidMark("Оренда квартири", monthKey(last), 11_000.0),
                    PaidMark("Інтернет", monthKey(last), 300.0),
                    PaidMark("Мобільний", monthKey(last), 231.0),
                    PaidMark("Netflix", monthKey(last), 299.0)
                )
            )
            saveIncome(40_000.0)
            saveFxRate(FxRate(41.2, 41.6, SOURCE_MONOBANK), System.currentTimeMillis())
        }
    }

    @After
    fun reset() = SumsMask.set(false)

    /** The app, with its command cleared once handled, as MainActivity does. */
    private fun app(command: AppCommand) {
        val held = mutableStateOf<AppCommand?>(command)
        rule.setContent { FlowPayApp(rule.activity, held.value) { held.value = null } }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onAllNodesWithText(text)[0].performClick()
        rule.waitForIdle()
    }

    private fun save(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/touch/$name.png")

    /** Every window, so a sheet or a dialog over the screen is in the picture too. */
    private fun saveScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/touch/$name.png")

    private fun hidden(tab: Int) {
        SumsMask.set(true)
        app(AppCommand.OpenTab(tab))
    }

    // ------------------------------------------------------------ the eye shut

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun hiddenOverview() = hidden(TAB_OVERVIEW).also { save("h5-overview-whole") }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2400dp-440dpi")
    fun hiddenPayments() = hidden(TAB_PAYMENTS).also { save("h3-payments-whole") }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h1900dp-440dpi")
    fun hiddenByMonths() {
        hidden(TAB_PAYMENTS)
        tap("По місяцях")
        // The month just ended, opened, so its lines and their amounts are drawn.
        tap(com.flowpay.app.monthTitle(monthKey(today.minusMonths(1))))
        save("h6-by-months")
    }

    @Test fun hiddenWishes() = hidden(TAB_WISHES).also { save("h1-wishes") }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun hiddenWishPage() {
        hidden(TAB_WISHES)
        tap("Навушники Sony WH-1000XM5")
        tap("План накопичення")
        save("h7-wish-page")
    }

    @Test fun hiddenRate() = hidden(TAB_RATE).also { save("h2-rate") }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h1900dp-440dpi")
    fun hiddenOrders() {
        hidden(TAB_ORDERS)
        tap("Архів покупок")
        save("h4-orders")
    }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2400dp-440dpi")
    fun hiddenParcelPage() {
        hidden(TAB_ORDERS)
        tap("Навушники JBL Tune 520")
        tap("Оплата")
        save("h8-parcel-page")
    }

    @Test fun hiddenRecap() {
        hidden(TAB_OVERVIEW)
        tap("МІСЯЦЬ ГОТОВИЙ")
        save("h9-recap")
    }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun hiddenMono() {
        hidden(TAB_OVERVIEW)
        tap("Налаштувати")
        saveScreen("h11-mono")
    }

    // ------------------------------------------------------------ with the eye open

    @Test fun shownOverview() = app(AppCommand.OpenTab(TAB_OVERVIEW)).also { save("s5-overview") }

    @Test fun recapWithSums() {
        app(AppCommand.OpenTab(TAB_OVERVIEW))
        tap("МІСЯЦЬ ГОТОВИЙ")
        save("s9-recap")
    }

    @Test fun recapWithoutSums() {
        app(AppCommand.OpenTab(TAB_OVERVIEW))
        tap("МІСЯЦЬ ГОТОВИЙ")
        tap("Без сум")
        save("s9-recap-nosums")
    }

    /** Every card of the deck, one picture each. Closing the deck marks it read, so one walk per test. */
    private fun walkDeck(prefix: String, withoutSums: Boolean) {
        app(AppCommand.OpenTab(TAB_OVERVIEW))
        tap("МІСЯЦЬ ГОТОВИЙ")
        if (withoutSums) tap("Без сум")
        repeat(8) { at ->
            save("$prefix-$at")
            rule.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.85f, height * 0.5f)) }
            rule.waitForIdle()
        }
    }

    @Test fun recapCardsWithSums() = walkDeck("r-sums", withoutSums = false)

    @Test fun recapCardsWithoutSums() = walkDeck("r-nosums", withoutSums = true)

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun settingsSwitch() {
        app(AppCommand.OpenTab(TAB_OVERVIEW))
        save("s5-overview-whole")
    }

    // ------------------------------------------------------------ a letter shared in

    @Test fun letterNewPayment() {
        app(AppCommand.AddShared(MEGOGO))
        saveScreen("l1-letter-new")
    }

    @Test fun letterPriceChange() {
        app(AppCommand.AddShared(VOLIA))
        saveScreen("l2-letter-price")
    }

    @Test fun letterEnglish() {
        app(AppCommand.AddShared(NETFLIX_TRIAL))
        saveScreen("l3-letter-english")
    }

    // ------------------------------------------------------------ sample data

    private fun sampleWishes() = listOf(
        Wish(
            id = "w1", name = "Навушники Sony WH-1000XM5", url = "https://rozetka.com.ua/x",
            image = "", price = 11_499.0, targetPrice = 10_500.0, category = "Техніка",
            history = listOf(PricePoint(12_999.0, day - 40), PricePoint(11_999.0, day - 20), PricePoint(11_499.0, day - 3)),
            checkedDay = day, saved = 4_000.0, monthlyPlan = 2_000.0
        ),
        Wish(
            id = "w2", name = "Кросівки ASICS Gel-1130", url = "https://prom.ua/x",
            image = "", price = 3_999.0, category = "Одяг",
            history = listOf(PricePoint(4_299.0, day - 25), PricePoint(3_999.0, day - 5)),
            checkedDay = day, saved = 1_500.0
        ),
        Wish(
            id = "w3", name = "Модуль пам'яті Kingston 32GB", url = "https://rozetka.com.ua/y",
            image = "", price = 2_799.0, category = "Техніка",
            history = listOf(PricePoint(2_799.0, day - 10)), checkedDay = day
        )
    )

    private fun samplePays() = listOf(
        Pay("Підписка Adobe", 9.59, day = 15, currency = USD),
        Pay("Мобільний", 231.0, day = 23),
        // Due tomorrow, so the week's weather has something to say and the pill
        // above every screen announces it.
        Pay("Спортзал", 900.0, day = today.plusDays(1).dayOfMonth),
        Pay("Інтернет", 300.0, day = 1),
        Pay("Оренда квартири", 11_000.0, day = 1),
        Pay(
            "YouTube Premium", 179.0, day = 20,
            amounts = listOf(PricePoint(99.0, 0L), PricePoint(179.0, day - 12))
        ),
        Pay("Netflix", 299.0, day = 28),
        Pay("Домен", 600.0, day = 14, billingMonth = today.plusMonths(3).monthValue),
        Pay("Megogo", 199.0, day = today.plusDays(9).dayOfMonth, trialEnd = day + 9),
        Pay(
            "Телефон частинами", 2_000.0, day = 12, instalments = 6,
            instalmentStart = today.minusMonths(2).withDayOfMonth(12).toEpochDay()
        )
    )

    private fun sampleOrders() = listOf(
        Order(
            "o1", "Навушники JBL Tune 520", "https://rozetka.com.ua/jbl",
            AT_BRANCH, tracking = "20450000000002", price = 1_599.0, amountToPay = 1_599.0,
            paidStorageFrom = day + 4
        ),
        Order(
            "o3", "Чохол для телефона", "https://rozetka.com.ua/z",
            IN_TRANSIT, tracking = "20450000000001", price = 349.0,
            statusDetail = "Прямує до міста одержувача · Чернівці"
        ),
        Order(
            "o4", "Модуль пам'яті для ноутбука", "https://rozetka.com.ua/w",
            RECEIVED, price = 10_739.0, paid = 10_499.0, lowestSeen = 10_499.0, uses = 12,
            archivedDay = day - 6, returnBy = day + 8
        )
    )

    private fun monoClient() = com.flowpay.app.MonoClient(
        "Власник",
        listOf(com.flowpay.app.MonoAccount("acc", com.flowpay.app.UAH_CODE, 1_234_000, 0, "black", listOf("537541******1234"), "")),
        listOf(com.flowpay.app.MonoJar("jar1", "Навушники", com.flowpay.app.UAH_CODE, 400_000, 1_050_000))
    )

    private fun noon(date: LocalDate): Long = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()

    private fun statement(): List<com.flowpay.app.MonoTx> {
        val lastMonth = today.minusMonths(1)
        fun tx(id: String, date: LocalDate, description: String, amount: Long, mcc: Int = 4899) =
            com.flowpay.app.MonoTx(id, noon(date), description, mcc, amount, amount, com.flowpay.app.UAH_CODE, false, "acc")
        val spotify = today.minusDays(3)
        return listOf(
            tx("n", lastMonth.withDayOfMonth(28), "NETFLIX.COM", -32900),
            tx("y", lastMonth.withDayOfMonth(20), "Google *YouTube", -17900),
            tx("v", today.withDayOfMonth(1), "VOLIA", -30000),
            tx("s1", spotify, "SPOTIFY", -16900),
            tx("s2", spotify.minusDays(30), "SPOTIFY", -16900),
            tx("s3", spotify.minusDays(60), "SPOTIFY", -16900),
            tx("g", today.minusDays(1), "SILPO", -84530, mcc = 5411)
        )
    }

    private companion object {
        const val MEGOGO = "MEGOGO: Ви оформили пробну підписку «Максимальна». Безкоштовно до 12 листопада, " +
            "далі 199 грн/міс. Скасувати можна будь-коли: https://megogo.net/ua/profile/subscriptions"

        const val VOLIA = "Шановний абоненте! З 01.11.2026 абонплата за тарифом «Домашній інтернет 500» " +
            "становитиме 360 грн на місяць. Деталі: https://volia.com/ukr/tariffs"

        const val NETFLIX_TRIAL = "Your Spotify Premium free trial ends on 3 Nov 2026. After that, you'll pay " +
            "169 UAH/month. Manage your subscription at https://www.spotify.com/account"
    }
}

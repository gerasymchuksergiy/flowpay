package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FxRate
import com.flowpay.app.ORDERED
import com.flowpay.app.IN_TRANSIT
import com.flowpay.app.Order
import com.flowpay.app.PaidMark
import com.flowpay.app.Pay
import com.flowpay.app.PricePoint
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.Store
import com.flowpay.app.TAB_ORDERS
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.TAB_PAYMENTS
import com.flowpay.app.TAB_RATE
import com.flowpay.app.TAB_WISHES
import com.flowpay.app.USD
import com.flowpay.app.Wish
import com.flowpay.app.monthKey
import com.github.takahirom.roborazzi.captureRoboImage
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
 * Every tab, drawn by Android's own renderer on this machine, as PNG.
 *
 * Nothing in this app had ever been seen on a screen by whoever was changing it
 * (HANDOFF §14). These are not a phone — no status bar, no HyperOS, and blur or
 * shaders are not proven here — but they are the real composables with real
 * fonts and real data, which is the difference between designing and guessing.
 *
 * Run with `./gradlew :app:testDebugUnitTest -Pshots` (environment as in
 * HANDOFF §5). Excluded from every other run, so CI never renders anything.
 * Sample data is relative to today, because the screens read the clock.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// Close to the Redmi Note 14: 1080×2400. sdk 34, because 36 needs Java 21 here.
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h873dp-440dpi")
class ScreenShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private val day = today.toEpochDay()

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        // The app's own motion snaps to its end state when this is nought, which
        // is what a still picture needs.
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        // The owner's Apple emoji, if this machine has them unpacked (they are
        // not in the repository — see EmojiPack). Without them the renders fall
        // back to the emoji font, which is what any other phone shows.
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        // monobank as if connected: no token (the keystore does not exist on the
        // JVM), only what a pass would have stored — a card, a jar, a statement.
        app.getSharedPreferences("flowpay-mono", 0).edit()
            .putString("tok", "screenshot")
            .putString("client", com.flowpay.app.monoClientJson(sampleMonoClient()).toString())
            .putString("tx", org.json.JSONArray(sampleStatement().map { com.flowpay.app.monoTxJson(it) }).toString())
            .putLong("sync", System.currentTimeMillis())
            .commit()
        Store(app).run {
            saveWishes(sampleWishes())
            savePays(samplePays())
            saveOrders(sampleOrders())
            savePaidMarks(listOf(PaidMark("Мобільний", monthKey(today), 231.0)))
            saveIncome(40_000.0)
            // Fresh, so the rate screen does not go to the network.
            saveFxRate(FxRate(41.2, 41.6, SOURCE_MONOBANK), System.currentTimeMillis())
        }
    }

    private fun shot(tab: Int, name: String) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(tab)) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun wishes() = shot(TAB_WISHES, "1-wishes")
    @Test fun rate() = shot(TAB_RATE, "2-rate")
    @Test fun payments() = shot(TAB_PAYMENTS, "3-payments")
    @Test fun orders() = shot(TAB_ORDERS, "4-orders")
    @Test fun overview() = shot(TAB_OVERVIEW, "5-overview")

    // The same screens on a very tall window, so a whole list fits in one picture.
    @Test @Config(qualifiers = "uk-rUA-w393dp-h1900dp-440dpi")
    fun paymentsWhole() = shot(TAB_PAYMENTS, "3b-payments-whole")

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun overviewWhole() = shot(TAB_OVERVIEW, "5b-overview-whole")

    @Test @Config(qualifiers = "uk-rUA-w393dp-h1700dp-440dpi")
    fun rateWhole() = shot(TAB_RATE, "2b-rate-whole")

    /** One of the pages a tap leads to, drawn after that tap. */
    private fun page(tab: Int, name: String, tap: String) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(tab)) }
        rule.waitForIdle()
        rule.onAllNodesWithText(tap)[0].performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun byMonths() = page(TAB_PAYMENTS, "6-by-months", "По місяцях")
    @Test fun wishPage() = page(TAB_WISHES, "7-wish-page", "Кросівки ASICS Gel-1130")
    @Test fun parcelPage() = page(TAB_ORDERS, "8-parcel-page", "Чохол для телефона")
    @Test fun recap() = page(TAB_OVERVIEW, "9-recap", "МІСЯЦЬ ГОТОВИЙ")
    @Test fun duel() = page(TAB_WISHES, "10-duel", "Дуель бажань")

    // Tall, so the settings row is on screen to be tapped.
    @Test @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun monoSheet() = page(TAB_OVERVIEW, "11-mono", "Налаштувати")

    // ------------------------------------------------------------ motion, as frames

    /**
     * A run of frames with the app's motion switched on, for stitching into a GIF.
     * The clock is driven by hand so every frame is a known moment.
     */
    private fun frames(tab: Int, name: String, count: Int, stepMs: Long, settleMs: Long, act: () -> Unit) {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        rule.mainClock.autoAdvance = false
        // Cleared once handled, as MainActivity does: a command left standing is
        // re-applied on every tab change and would snap the app back to [tab].
        val command = androidx.compose.runtime.mutableStateOf<AppCommand?>(AppCommand.OpenTab(tab))
        rule.setContent { FlowPayApp(rule.activity, command.value) { command.value = null } }
        rule.mainClock.advanceTimeBy(settleMs)
        act()
        repeat(count) { at ->
            rule.onRoot().captureRoboImage("build/outputs/roborazzi/clips/$name-%03d.png".format(at))
            rule.mainClock.advanceTimeBy(stepMs)
        }
    }

    @Test fun clipPaid() {
        // Without the monobank tiles, which push the payment tiles below the fold.
        RuntimeEnvironment.getApplication().getSharedPreferences("flowpay-mono", 0).edit().clear().commit()
        frames(TAB_PAYMENTS, "paid", count = 40, stepMs = 33, settleMs = 3_000) {
            rule.onAllNodesWithContentDescription("Позначити оплаченим")[0].performClick()
        }
    }

    @Test fun clipOpen() = frames(TAB_OVERVIEW, "open", count = 36, stepMs = 33, settleMs = 0) {}

    @Test fun clipTab() = frames(TAB_PAYMENTS, "tab", count = 40, stepMs = 33, settleMs = 3_000) {
        // A tap is delivered on a frame, so the clock runs for the tap itself and
        // is taken back by hand for the frames that follow it.
        rule.mainClock.autoAdvance = true
        rule.onAllNodesWithText("Покупки").onLast().performClick()
        rule.mainClock.autoAdvance = false
    }

    // ------------------------------------------------------------ sample data

    private fun sampleMonoClient() = com.flowpay.app.MonoClient(
        "Власник",
        listOf(com.flowpay.app.MonoAccount("acc", com.flowpay.app.UAH_CODE, 1_234_000, 0, "black", listOf("537541******1234"), "")),
        listOf(com.flowpay.app.MonoJar("jar1", "Навушники", com.flowpay.app.UAH_CODE, 400_000, 1_050_000))
    )

    private fun noon(date: LocalDate): Long = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()

    private fun sampleStatement(): List<com.flowpay.app.MonoTx> {
        val lastMonth = today.minusMonths(1)
        fun tx(id: String, date: LocalDate, description: String, amount: Long, mcc: Int = 4899) =
            com.flowpay.app.MonoTx(id, noon(date), description, mcc, amount, amount, com.flowpay.app.UAH_CODE, false, "acc")
        val spotify = today.minusDays(3)
        return listOf(
            tx("n", lastMonth.withDayOfMonth(28), "NETFLIX.COM", -29900),
            tx("y", lastMonth.withDayOfMonth(20), "Google *YouTube", -17900),
            tx("v", today.withDayOfMonth(1), "VOLIA", -30000),
            tx("s1", spotify, "SPOTIFY", -16900),
            tx("s2", spotify.minusDays(30), "SPOTIFY", -16900),
            tx("s3", spotify.minusDays(60), "SPOTIFY", -16900),
            tx("g", today.minusDays(1), "SILPO", -84530, mcc = 5411)
        )
    }

    private fun sampleWishes() = listOf(
        Wish(
            id = "w1", name = "Навушники Sony WH-1000XM5", url = "https://rozetka.com.ua/x",
            image = "", price = 11_499.0, targetPrice = 10_500.0, category = "Техніка",
            history = listOf(
                PricePoint(12_999.0, day - 40), PricePoint(11_999.0, day - 20), PricePoint(11_499.0, day - 3)
            ),
            checkedDay = day, saved = 4_000.0, monthlyPlan = 2_000.0
        ),
        Wish(
            id = "w2", name = "Кросівки ASICS Gel-1130", url = "https://prom.ua/x",
            image = "", price = 3_999.0, category = "Одяг",
            history = listOf(PricePoint(4_299.0, day - 25), PricePoint(3_999.0, day - 5)),
            checkedDay = day
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
        Pay("Інтернет", 300.0, day = 1),
        Pay("Оренда квартири", 11_000.0, day = 1),
        Pay("YouTube Premium", 179.0, day = 20),
        Pay("Netflix", 299.0, day = 28),
        Pay("iCloud+", 99.0, day = 30)
    )

    private fun sampleOrders() = listOf(
        Order(
            "o1", "nova 2 lite бездротовий ігровий для пк", "https://www.temu.com/ua/x.html",
            ORDERED, tracking = "RL778364634EE", price = 1_117.0
        ),
        Order(
            "o2", "EA SPORTS FC 27 у Steam", "https://store.steampowered.com/app/1",
            ORDERED, price = 2_199.0, digital = true
        ),
        Order(
            "o3", "Чохол для телефона", "https://rozetka.com.ua/z",
            IN_TRANSIT, tracking = "20450000000001", price = 349.0,
            statusDetail = "Прямує до міста одержувача · Чернівці"
        ),
        Order(
            "o4", "Модуль пам'яті для ноутбука", "https://rozetka.com.ua/w",
            com.flowpay.app.RECEIVED, price = 10_739.0, paid = 10_739.0, lowestSeen = 10_739.0,
            archivedDay = day - 6, returnBy = day + 8
        )
    )
}

package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FxRate
import com.flowpay.app.ORDERED
import com.flowpay.app.IN_TRANSIT
import com.flowpay.app.Order
import com.flowpay.app.PaidMark
import com.flowpay.app.Pay
import com.flowpay.app.PricePoint
import com.flowpay.app.SOURCE_NBU
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
        Store(app).run {
            saveWishes(sampleWishes())
            savePays(samplePays())
            saveOrders(sampleOrders())
            savePaidMarks(listOf(PaidMark("Мобільний", monthKey(today), 231.0)))
            saveIncome(40_000.0)
            // Fresh, so the rate screen does not go to the network.
            saveFxRate(FxRate(41.2, 41.6, SOURCE_NBU), System.currentTimeMillis())
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

    // ------------------------------------------------------------ sample data

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
        Pay("YouTube Premium", 179.0, day = 20)
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

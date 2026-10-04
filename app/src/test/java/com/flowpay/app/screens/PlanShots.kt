package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.Fund
import com.flowpay.app.FxRate
import com.flowpay.app.LifeCost
import com.flowpay.app.PaidMark
import com.flowpay.app.Pay
import com.flowpay.app.Payday
import com.flowpay.app.PlanStore
import com.flowpay.app.PricePoint
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.Store
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.TAB_PAYMENTS
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
 * The plan's tiles and sheets (MoneyPlan.kt), drawn the way [ScreenShots] draws
 * every tab: «На життя» on, a payday today, a cushion and two annual payments
 * with funds, monobank connected. Run with -Pshots; never in CI.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h873dp-440dpi")
class PlanShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private val day = today.toEpochDay()

    /** Charged within the timeline's month, so its tile wears the fund's ring. */
    private val soon = today.plusDays(12)

    /** Two months out: on «Раз на рік», with a fund saving for it. */
    private val later = today.plusMonths(2)

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        app.getSharedPreferences("flowpay-mono", 0).edit()
            .putString("tok", "screenshot")
            .putString(
                "client",
                com.flowpay.app.monoClientJson(
                    com.flowpay.app.MonoClient(
                        "Власник",
                        listOf(com.flowpay.app.MonoAccount("acc", com.flowpay.app.UAH_CODE, 1_840_000, 0, "black", listOf("537541******1234"), "")),
                        emptyList()
                    )
                ).toString()
            )
            .putLong("cat", System.currentTimeMillis())
            .putLong("sync", System.currentTimeMillis())
            .commit()
        Store(app).run {
            saveWishes(
                listOf(
                    Wish(
                        id = "w1", name = "Навушники Sony WH-1000XM5", url = "https://rozetka.com.ua/x", image = "",
                        price = 11_499.0, targetPrice = 10_500.0, category = "Техніка",
                        history = listOf(PricePoint(12_999.0, day - 40), PricePoint(11_499.0, day - 3)),
                        checkedDay = day, saved = 4_000.0, monthlyPlan = 3_000.0, duelWins = 1, duelsPlayed = 4
                    ),
                    Wish(
                        id = "w2", name = "Ноутбук ASUS Vivobook 16", url = "https://rozetka.com.ua/n", image = "",
                        price = 32_999.0, category = "Техніка", history = listOf(PricePoint(32_999.0, day - 20)),
                        checkedDay = day, saved = 6_000.0, deadline = today.plusMonths(6).toEpochDay(), duelWins = 3, duelsPlayed = 4
                    ),
                    Wish(
                        id = "w3", name = "Кросівки ASICS Gel-1130", url = "https://prom.ua/x", image = "",
                        price = 3_999.0, category = "Одяг", history = listOf(PricePoint(4_299.0, day - 25), PricePoint(3_999.0, day - 5)),
                        checkedDay = day
                    )
                )
            )
            savePays(
                listOf(
                    Pay("Підписка Adobe", 9.59, day = 15, currency = USD),
                    Pay("Мобільний", 231.0, day = 23),
                    Pay("Інтернет", 300.0, day = 1),
                    Pay("Оренда квартири", 11_000.0, day = 1),
                    Pay("YouTube Premium", 179.0, day = 20),
                    Pay("Антивірус", 1_199.0, day = soon.dayOfMonth, billingMonth = soon.monthValue),
                    Pay("Автоцивілка", 6_400.0, day = 15, billingMonth = later.monthValue),
                    Pay("Домен", 20.0, day = 14, currency = USD, billingMonth = today.plusMonths(5).monthValue)
                )
            )
            savePaidMarks(listOf(PaidMark("Мобільний", monthKey(today), 231.0)))
            saveIncome(40_000.0)
            saveFxRate(FxRate(41.2, 41.6, SOURCE_MONOBANK), System.currentTimeMillis())
        }
        PlanStore(app).run {
            saveLife(LifeCost(true, 12_000.0))
            savePayday(Payday(today.dayOfMonth))
            saveFunds(
                listOf(
                    Fund("f1", "Антивірус", payName = "Антивірус", saved = 800.0, dueMonth = monthKey(soon)),
                    Fund("f2", "Автоцивілка", payName = "Автоцивілка", saved = 2_140.0, dueMonth = monthKey(later)),
                    Fund("f3", "Подушка", emoji = "🛟", saved = 8_000.0, monthly = 2_000.0, cushion = true)
                )
            )
        }
    }

    private fun open(tab: Int) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(tab)) }
        rule.waitForIdle()
    }

    private fun shoot(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")

    @Test @Config(qualifiers = "uk-rUA-w393dp-h3400dp-440dpi")
    fun overviewWhole() {
        open(TAB_OVERVIEW)
        shoot("p1-overview-whole")
    }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun paymentsWhole() {
        open(TAB_PAYMENTS)
        shoot("p2-payments-whole")
    }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h1400dp-440dpi")
    fun afford() {
        open(TAB_OVERVIEW)
        rule.onAllNodesWithText("Чи потягну?")[0].performScrollTo().performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("Ціна, ₴")[0].performTextInput("16500")
        rule.waitForIdle()
        shoot("p3-afford")
    }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h2200dp-440dpi")
    fun wishPlan() {
        open(TAB_WISHES)
        rule.onAllNodesWithText("Навушники Sony WH-1000XM5")[0].performClick()
        rule.waitForIdle()
        rule.onAllNodesWithText("План накопичення")[0].performScrollTo().performClick()
        rule.waitForIdle()
        shoot("p4-wish-plan")
    }

    @Test
    fun incomeDialog() {
        open(TAB_PAYMENTS)
        rule.onAllNodesWithText("Лишається")[0].performClick()
        rule.waitForIdle()
        shoot("p5-income")
    }

    @Test @Config(qualifiers = "uk-rUA-w393dp-h1400dp-440dpi")
    fun fundSheet() {
        open(TAB_PAYMENTS)
        rule.onAllNodesWithText("+ Фонд")[0].performScrollTo().performClick()
        rule.waitForIdle()
        shoot("p6-fund-sheet")
    }
}

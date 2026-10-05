package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.Fund
import com.flowpay.app.FxRate
import com.flowpay.app.LifeCost
import com.flowpay.app.MonoAccount
import com.flowpay.app.MonoClient
import com.flowpay.app.PaidMark
import com.flowpay.app.Pay
import com.flowpay.app.Payday
import com.flowpay.app.PlanStore
import com.flowpay.app.PricePoint
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.Store
import com.flowpay.app.SumsMask
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.TAB_PAYMENTS
import com.flowpay.app.TAB_WISHES
import com.flowpay.app.UAH_CODE
import com.flowpay.app.USD
import com.flowpay.app.Wish
import com.flowpay.app.monoClientJson
import com.flowpay.app.monthKey
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
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
 * The plan's tiles and sheets (HANDOFF §24) with the eye on Огляд shut: the
 * hero, «Розкласти зарплату», «Скільки можна сьогодні», «Місяць наперед»,
 * «Чи потягну?», the funds and their sheet, the settings rows. [PlanShots]' own
 * data, so each picture can be held against its open-eyed twin. PNGs in
 * build/outputs/roborazzi/touch-mask/. `-Pshots` only.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h3400dp-440dpi")
class TouchPlanMaskShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private val day = today.toEpochDay()
    private val soon = today.plusDays(12)
    private val later = today.plusMonths(2)

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        val client = MonoClient(
            "Власник",
            listOf(MonoAccount("acc", UAH_CODE, 3_200_000, 0, "black", listOf("537541******1234"), "")),
            emptyList()
        )
        app.getSharedPreferences("flowpay-mono", 0).edit()
            .putString("tok", "screenshot")
            .putString("client", monoClientJson(client).toString())
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
                    Fund("f3", "Подушка", saved = 8_000.0, monthly = 2_000.0, cushion = true)
                )
            )
        }
        SumsMask.set(true)
    }

    @After
    fun reset() = SumsMask.set(false)

    private fun open(tab: Int) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(tab)) }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onAllNodesWithText(text)[0].performScrollTo().performClick()
        rule.waitForIdle()
    }

    // Every window, so a sheet or a snackbar is in the picture too.
    private fun save(name: String) = captureScreenRoboImage("build/outputs/roborazzi/touch-mask/$name.png")

    @Test fun overview() = open(TAB_OVERVIEW).also { save("p1-overview") }

    /** «На життя» high enough that the plans no longer fit: «Плани не сходяться». */
    @Test
    fun conflict() {
        PlanStore(RuntimeEnvironment.getApplication()).saveLife(LifeCost(true, 20_000.0))
        open(TAB_OVERVIEW)
        save("p2-conflict")
    }

    /** «Я відклав» pressed: the tile's line and the snackbar. */
    @Config(qualifiers = "uk-rUA-w393dp-h1600dp-440dpi")
    @Test
    fun ritualDone() {
        open(TAB_OVERVIEW)
        tap("Я відклав")
        save("p3-ritual-done")
    }

    @Test fun payments() = open(TAB_PAYMENTS).also { save("p4-payments") }

    @Test
    fun afford() {
        open(TAB_OVERVIEW)
        tap("Чи потягну?")
        rule.onAllNodesWithText("Ціна, ₴")[0].performTextInput("36500")
        rule.waitForIdle()
        save("p5-afford")
        // Inside the sheet, which is tall enough here not to need a scroll.
        rule.onAllNodesWithText("Купив частинами")[0].performClick()
        rule.waitForIdle()
        save("p5-afford-parts")
    }

    @Config(qualifiers = "uk-rUA-w393dp-h2200dp-440dpi")
    @Test
    fun wishPlan() {
        open(TAB_WISHES)
        tap("Навушники Sony WH-1000XM5")
        tap("План накопичення")
        save("p6-wish-plan")
    }

    @Test
    fun fundSheet() {
        open(TAB_PAYMENTS)
        val fundRow = SemanticsMatcher("«Змінити фонд»") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Змінити фонд" }
        rule.onAllNodes(fundRow and hasText("Автоцивілка", substring = true))[0].performScrollTo().performClick()
        rule.waitForIdle()
        save("p7-fund-sheet")
    }
}

package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FxRate
import com.flowpay.app.MonoAccount
import com.flowpay.app.MonoClient
import com.flowpay.app.MonoTx
import com.flowpay.app.Order
import com.flowpay.app.Pay
import com.flowpay.app.RECEIVED
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.Store
import com.flowpay.app.TAB_PAYMENTS
import com.flowpay.app.UAH_CODE
import com.flowpay.app.cancelled
import com.flowpay.app.instalmentStartFor
import com.flowpay.app.monoClientJson
import com.flowpay.app.monoTxJson
import com.flowpay.app.paidOff
import com.flowpay.app.paused
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
 * Платежі with every state of a payment's life on it (PaymentsLife.kt): a
 * cancellation still running, one whose question is due, a pause, a promo price,
 * a plan paid off early — and the monobank cards. Sample data only, relative to
 * today. Run with `-Pshots` like ScreenShots.kt; not a phone.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
class PaymentsLifeShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()

    private fun noon(date: LocalDate): Long = date.atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toEpochSecond()

    private fun tx(id: String, date: LocalDate, description: String, amount: Long) =
        MonoTx(id, noon(date), description, 4899, amount, amount, UAH_CODE, false, "acc")

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        val client = MonoClient(
            "Власник",
            listOf(MonoAccount("acc", UAH_CODE, 21_000, 0, "black", listOf("537541******1234"), "")),
            emptyList()
        )
        val statement = listOf(
            // Spotify was cancelled and charged anyway.
            tx("s1", today.minusDays(40), "SPOTIFY", -16_900),
            tx("s2", today, "SPOTIFY", -16_900),
            // YouTube twice in two days.
            tx("y1", today.minusDays(3), "Google *YouTube Premium", -17_900),
            tx("y2", today.minusDays(2), "Google *YouTube Premium", -17_900),
            // Sweet.tv quiet for fifty days.
            tx("t1", today.minusDays(50), "SWEET.TV", -9_900)
        )
        app.getSharedPreferences("flowpay-mono", 0).edit()
            .putString("tok", "screenshot")
            .putString("client", monoClientJson(client).toString())
            .putString("tx", org.json.JSONArray(statement.map { monoTxJson(it) }).toString())
            .putLong("sync", System.currentTimeMillis())
            .commit()
        val netflix = Pay("Netflix", 299.0, day = today.plusDays(11).dayOfMonth)
        val spotify = Pay("Spotify", 169.0, day = today.dayOfMonth, monoMerchant = "spotify")
        val phone = Pay(
            "iPhone частинами", 2_500.0, day = today.plusDays(5).dayOfMonth,
            instalments = 6, instalmentStart = instalmentStartFor(today.plusDays(5).dayOfMonth, 2, today), order = "o1"
        )
        Store(app).run {
            savePays(
                listOf(
                    Pay("Оренда квартири", 11_000.0, day = 1),
                    Pay(
                        "Інтернет", 300.0, day = today.plusDays(4).dayOfMonth,
                        trialEnd = today.plusDays(60).toEpochDay(), promoPrice = 150.0, warnDays = 3
                    ),
                    Pay("YouTube Premium", 179.0, day = today.minusDays(2).dayOfMonth, monoMerchant = "google youtube premium"),
                    Pay("Sweet.tv", 99.0, day = today.minusDays(20).dayOfMonth, monoMerchant = "sweet tv"),
                    cancelled(netflix, today.plusDays(10), today),
                    cancelled(spotify, today.minusDays(1), today),
                    paused(Pay("Megogo", 199.0, day = 5), today.minusDays(3)),
                    paidOff(phone, today)
                )
            )
            saveOrders(
                listOf(
                    Order(
                        "o1", "iPhone 16", "https://rozetka.com.ua/p", RECEIVED, price = 15_000.0, paid = 15_000.0,
                        archivedDay = today.toEpochDay() - 20, planReturned = today.toEpochDay() - 1
                    )
                )
            )
            // The purchases archive open, so the returned line is on screen.
            saveSectionOpen(com.flowpay.app.SECTION_ORDER_ARCHIVE, true)
            saveIncome(40_000.0)
            saveFxRate(FxRate(41.2, 41.6, SOURCE_MONOBANK), System.currentTimeMillis())
        }
    }

    private fun open() {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(TAB_PAYMENTS)) }
        rule.waitForIdle()
    }

    @Test
    fun payments() {
        open()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/life-1-payments.png")
    }

    @Test
    fun subscriptionSheet() {
        open()
        rule.onAllNodesWithText("Інтернет")[0].performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/life-2-sheet.png")
    }

    @Test
    fun purchases() {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(com.flowpay.app.TAB_ORDERS)) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/life-4-purchases.png")
    }

    @Test
    fun planSheet() {
        open()
        rule.onAllNodesWithText("iPhone частинами")[0].performScrollTo().performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/life-3-plan.png")
    }
}

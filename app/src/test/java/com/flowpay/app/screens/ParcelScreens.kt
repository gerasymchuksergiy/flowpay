package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.flowpay.app.AT_BRANCH
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FxRate
import com.flowpay.app.IN_TRANSIT
import com.flowpay.app.Order
import com.flowpay.app.ParcelDetails
import com.flowpay.app.ParcelPrefs
import com.flowpay.app.Pay
import com.flowpay.app.RECEIVED
import com.flowpay.app.Refund
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.Store
import com.flowpay.app.TAB_ORDERS
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.parsePickupPoint
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
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
 * The second round of parcels and purchases, drawn: a parcel at a branch with
 * money to pay and the branch's hours, a return under way, the archive with a
 * warranty and «Як тобі …?», and Огляд with the parcels' money and «Мені винні».
 * Same rules as ScreenShots.kt: `-Pshots` only, sample data relative to today,
 * nothing fetched (the branch's directory answer is seeded into its cache).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h2400dp-440dpi")
class ParcelScreens {

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
        app.getSharedPreferences("flowpay-mono", 0).edit().clear().commit()
        Store(app).run {
            saveWishes(emptyList())
            savePays(listOf(Pay("Інтернет", 300.0, day = today.plusDays(2).dayOfMonth), Pay("Оренда квартири", 11_000.0, day = 1)))
            saveOrders(sampleOrders())
            saveIncome(40_000.0)
            saveFxRate(FxRate(41.2, 41.6, SOURCE_MONOBANK), System.currentTimeMillis())
        }
        ParcelPrefs(app).savePoint(parsePickupPoint(BRANCH, System.currentTimeMillis())!!)
    }

    private fun shot(tab: Int, name: String) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(tab)) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    private fun page(tab: Int, name: String, tap: String) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(tab)) }
        rule.waitForIdle()
        rule.onAllNodesWithText(tap)[0].performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun orders() {
        Store(RuntimeEnvironment.getApplication()).saveSectionOpen("orderarchive", true)
        shot(TAB_ORDERS, "p1-orders")
    }

    @Test fun overview() = shot(TAB_OVERVIEW, "p2-overview")

    @Test fun parcelPage() = page(TAB_ORDERS, "p3-parcel-at-branch", "Навушники JBL Tune 520BT")

    @Test fun returnPage() = page(TAB_ORDERS, "p4-return-page", "Кросівки ASICS")

    /** A form sheet is a window of its own, so these capture the whole screen. */
    private fun sheet(tab: Int, name: String, vararg taps: String) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(tab)) }
        rule.waitForIdle()
        taps.forEach { tap ->
            rule.onAllNodesWithText(tap, substring = true)[0].performClick()
            rule.waitForIdle()
        }
        captureScreenRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun closeSheet() = sheet(TAB_ORDERS, "p5-close-sheet", "Навушники JBL Tune 520BT", "Отримав — завершити")

    @Test fun returnSheet() {
        Store(RuntimeEnvironment.getApplication()).saveSectionOpen("orderarchive", true)
        sheet(TAB_ORDERS, "p6-return-sheet", "Повертаю")
    }

    // The phone number's AlertDialog is not drawn here: under Robolectric it never
    // went idle (AppNotIdleException), so it was left for the phone.

    private fun sampleOrders() = listOf(
        Order(
            "o1", "Навушники JBL Tune 520BT", "https://rozetka.com.ua/x", AT_BRANCH,
            tracking = "20450000000001", price = 1_249.0, amountToPay = 1_249.0,
            paidStorageFrom = day + 4, statusCode = 7,
            statusDetail = "Прибув у відділення · Київ, відділення №12",
            details = ParcelDetails(
                cityRecipient = "Київ", warehouseRecipient = "Відділення №12: вул. Прикладна, 8",
                warehouseNumber = "12", warehouseCategory = "Branch",
                warehouseRef = "731a002c-3ed2-11e6-a9f2-005056887b8d",
                deliveryCost = 90.0, goodsToPay = 1_159.0, sender = "Магазин Приклад"
            )
        ),
        Order(
            "o2", "Чохол для телефона", "https://prom.ua/x", IN_TRANSIT,
            tracking = "20450000000002", price = 249.0, amountToPay = 249.0, scheduledDelivery = day + 2
        ),
        Order(
            "o3", "Кросівки ASICS", "https://rozetka.com.ua/y", RECEIVED,
            price = 3_999.0, paid = 3_899.0, archivedDay = day - 12, returnBy = day + 2,
            refund = Refund(startedDay = day - 9, amount = 3_899.0, tracking = "20450000000099", reason = "не той розмір", shopGotDay = day - 6)
        ),
        Order(
            "o4", "Павербанк Xiaomi 20000", "https://rozetka.com.ua/z", RECEIVED,
            price = 1_100.0, paid = 999.0, lowestSeen = 999.0, archivedDay = day - 22, returnBy = day + 5,
            warrantyUntil = day + 340, why = "щоб не сідав телефон у дорозі", category = "Гаджети"
        ),
        Order(
            "o5", "Кавоварка", "https://rozetka.com.ua/k", RECEIVED,
            price = 4_000.0, paid = 3_800.0, archivedDay = day - 40, delight = 4, delightDay = day - 18,
            again = 1, why = "щоранку справжня кава"
        )
    )

    private companion object {
        // Kyiv branch №12 as the public directory answered on 4 October 2026, trimmed.
        const val BRANCH = """{"success":true,"data":[{"Ref":"731a002c-3ed2-11e6-a9f2-005056887b8d","CategoryOfWarehouse":"Branch","POSTerminal":"1","GeneratorEnabled":"1","HasFittingRoom":"1","WarehouseStatus":"Working","Schedule":{"Monday":"08:00-21:00","Tuesday":"08:00-21:00","Wednesday":"08:00-21:00","Thursday":"08:00-21:00","Friday":"08:00-21:00","Saturday":"08:00-19:00","Sunday":"08:00-19:00"}}]}"""
    }
}

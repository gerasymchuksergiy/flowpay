package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import com.flowpay.app.AT_BRANCH
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FxRate
import com.flowpay.app.IN_TRANSIT
import com.flowpay.app.MonoAccount
import com.flowpay.app.MonoClient
import com.flowpay.app.MonoTx
import com.flowpay.app.Order
import com.flowpay.app.ParcelDetails
import com.flowpay.app.ParcelPrefs
import com.flowpay.app.Pay
import com.flowpay.app.RECEIVED
import com.flowpay.app.Refund
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.Store
import com.flowpay.app.SumsMask
import com.flowpay.app.TAB_ORDERS
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.TAB_PAYMENTS
import com.flowpay.app.UAH_CODE
import com.flowpay.app.cancelled
import com.flowpay.app.instalmentStartFor
import com.flowpay.app.monoClientJson
import com.flowpay.app.monoTxJson
import com.flowpay.app.paidOff
import com.flowpay.app.parsePickupPoint
import com.flowpay.app.paused
import com.flowpay.app.withPromoEnded
import com.github.takahirom.roborazzi.captureRoboImage
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
 * The parcels' and the payments' new screens (HANDOFF §20, §21) with the eye on
 * Огляд shut: cash on delivery, returns, «Мені винні», a promo, cancelled, paused
 * and ended payments, monobank's new cards, and the recap's new cards. One sum
 * left showing undoes the mode, so each picture is looked at. PNGs in
 * build/outputs/roborazzi/touch-mask/. `-Pshots` only, like [TouchShots].
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
class TouchMaskShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private val day = today.toEpochDay()

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
            listOf(MonoAccount("acc", UAH_CODE, 2_100_000, 0, "black", listOf("537541******1234"), "")),
            emptyList()
        )
        val statement = listOf(
            // Spotify was cancelled and charged anyway; YouTube twice in two days;
            // Sweet.tv quiet for fifty days.
            tx("s1", today.minusDays(40), "SPOTIFY", -16_900),
            tx("s2", today, "SPOTIFY", -16_900),
            tx("y1", today.minusDays(3), "Google *YouTube Premium", -17_900),
            tx("y2", today.minusDays(2), "Google *YouTube Premium", -17_900),
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
            instalments = 6, instalmentStart = instalmentStartFor(today.plusDays(5).dayOfMonth, 2, today), order = "o6"
        )
        // A promo that ran out in the middle of last month, for the recap's card.
        val promoEnd = today.minusMonths(1).withDayOfMonth(15)
        val kyivstar = withPromoEnded(
            Pay("Київстар", 250.0, day = 15, trialEnd = promoEnd.toEpochDay(), promoPrice = 150.0),
            today
        )
        Store(app).run {
            saveWishes(emptyList())
            savePays(
                listOf(
                    Pay("Оренда квартири", 11_000.0, day = 1),
                    Pay(
                        "Інтернет", 300.0, day = today.plusDays(4).dayOfMonth,
                        trialEnd = today.plusDays(60).toEpochDay(), promoPrice = 150.0, warnDays = 3
                    ),
                    Pay("YouTube Premium", 179.0, day = today.minusDays(2).dayOfMonth, monoMerchant = "google youtube premium"),
                    Pay("Sweet.tv", 99.0, day = today.minusDays(20).dayOfMonth, monoMerchant = "sweet tv"),
                    kyivstar,
                    cancelled(netflix, today.plusDays(10), today),
                    cancelled(spotify, today.minusDays(1), today),
                    paused(Pay("Megogo", 199.0, day = 5), today.minusDays(3)),
                    paidOff(phone, today)
                )
            )
            saveOrders(sampleOrders(promoEnd))
            saveSectionOpen(com.flowpay.app.SECTION_ORDER_ARCHIVE, true)
            saveIncome(40_000.0)
            saveFxRate(FxRate(41.2, 41.6, SOURCE_MONOBANK), System.currentTimeMillis())
        }
        ParcelPrefs(app).savePoint(parsePickupPoint(BRANCH, System.currentTimeMillis())!!)
        SumsMask.set(true)
    }

    @After
    fun reset() = SumsMask.set(false)

    private fun app(tab: Int) {
        val held = mutableStateOf<AppCommand?>(AppCommand.OpenTab(tab))
        rule.setContent { FlowPayApp(rule.activity, held.value) { held.value = null } }
        rule.waitForIdle()
    }

    private fun tap(text: String) {
        rule.onAllNodesWithText(text, substring = true)[0].performScrollTo().performClick()
        rule.waitForIdle()
    }

    private fun save(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/touch-mask/$name.png")

    @Test fun overview() = app(TAB_OVERVIEW).also { save("m1-overview") }

    @Test fun orders() = app(TAB_ORDERS).also { save("m2-orders") }

    @Test fun parcelPage() {
        app(TAB_ORDERS)
        tap("Навушники JBL Tune 520BT")
        tap("Оплата")
        save("m3-parcel-page")
    }

    @Test fun returnPage() {
        app(TAB_ORDERS)
        tap("Кросівки ASICS")
        save("m4-return-page")
    }

    @Test fun payments() = app(TAB_PAYMENTS).also { save("m5-payments") }

    @Config(qualifiers = "uk-rUA-w393dp-h873dp-440dpi")
    @Test fun recapCards() {
        app(TAB_OVERVIEW)
        tap("МІСЯЦЬ ГОТОВИЙ")
        repeat(8) { at ->
            save("m6-recap-$at")
            rule.onRoot().performTouchInput { click(Offset(width * 0.85f, height * 0.5f)) }
            rule.waitForIdle()
        }
    }

    private fun sampleOrders(promoEnd: LocalDate) = listOf(
        Order(
            "o1", "Навушники JBL Tune 520BT", "https://rozetka.com.ua/x", AT_BRANCH,
            tracking = "20450000000001", price = 1_249.0, amountToPay = 1_249.0,
            paidStorageFrom = day + 4, statusCode = 7,
            statusDetail = "Прибув у відділення · Київ, відділення №12",
            details = ParcelDetails(
                cityRecipient = "Київ", warehouseRecipient = "Відділення №12: вул. Прикладна, 8",
                warehouseNumber = "12", warehouseCategory = "Branch",
                warehouseRef = "731a002c-3ed2-11e6-a9f2-005056887b8d",
                deliveryCost = 90.0, goodsToPay = 1_159.0, storageCharged = 40.0, sender = "Магазин Приклад"
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
            "o5", "Кавоварка", "https://rozetka.com.ua/k", RECEIVED,
            price = 4_000.0, paid = 3_800.0, archivedDay = promoEnd.toEpochDay() - 25,
            delight = 4, delightDay = promoEnd.toEpochDay() + 1, again = 1, why = "щоранку справжня кава"
        ),
        Order(
            "o6", "iPhone 16", "https://rozetka.com.ua/p", RECEIVED, price = 15_000.0, paid = 15_000.0,
            archivedDay = day - 20
        )
    )

    private companion object {
        // Kyiv branch №12 as the public directory answered on 4 October 2026, trimmed (as in ParcelScreens).
        const val BRANCH = """{"success":true,"data":[{"Ref":"731a002c-3ed2-11e6-a9f2-005056887b8d","CategoryOfWarehouse":"Branch","POSTerminal":"1","GeneratorEnabled":"1","HasFittingRoom":"1","WarehouseStatus":"Working","Schedule":{"Monday":"08:00-21:00","Tuesday":"08:00-21:00","Wednesday":"08:00-21:00","Thursday":"08:00-21:00","Friday":"08:00-21:00","Saturday":"08:00-19:00","Sunday":"08:00-19:00"}}]}"""
    }
}

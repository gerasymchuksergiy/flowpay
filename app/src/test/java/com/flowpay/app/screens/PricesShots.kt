package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.Freshness
import com.flowpay.app.FxRate
import com.flowpay.app.PricePoint
import com.flowpay.app.PriceStore
import com.flowpay.app.SECTION_HISTORY
import com.flowpay.app.SECTION_SHOPS
import com.flowpay.app.SOURCE_MONOBANK
import com.flowpay.app.SetAside
import com.flowpay.app.StockGap
import com.flowpay.app.Store
import com.flowpay.app.TAB_WISHES
import com.flowpay.app.UAH
import com.flowpay.app.Wish
import com.flowpay.app.WishSource
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
 * The screens the second pass over prices changed, drawn on the JVM like
 * [ScreenShots] — on the narrowest phone width the app has to fit (360 dp), and
 * tall, so the whole wish page is one picture. Run with `-Pshots`; the PNGs land in
 * app/build/outputs/roborazzi/ as `p*.png`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w360dp-h2600dp-440dpi")
class PricesShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val today = LocalDate.now()
    private val day = today.toEpochDay()

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        Store(app).run {
            saveWishes(listOf(rozetkaWish()))
            saveFxRate(FxRate(44.8, 45.2, SOURCE_MONOBANK), System.currentTimeMillis())
            saveSectionOpen(SECTION_HISTORY, true)
            saveSectionOpen(SECTION_SHOPS, true)
        }
        PriceStore(app).saveRozetkaCard(true)
    }

    private fun page(name: String, tap: String) {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(TAB_WISHES)) }
        rule.waitForIdle()
        rule.onAllNodesWithText(tap)[0].performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/$name.png")
    }

    @Test fun wishPage() = page("p1-wish-page", "Навушники JBL Tune 520BT Black")

    private fun rozetkaWish(): Wish {
        val url = "https://rozetka.com.ua/ua/jbl_jblt520btblkeu/p369896649/"
        return Wish(
            id = "w1",
            name = "Навушники JBL Tune 520BT Black",
            url = url,
            image = "",
            price = 1599.0,
            targetPrice = 1550.0,
            category = "Техніка",
            history = listOf(
                PricePoint(1799.0, day - 60),
                PricePoint(1699.0, day - 45),
                // A shop's glitch, one check long — the hint asks about it.
                PricePoint(159.0, day - 33),
                PricePoint(1699.0, day - 33),
                PricePoint(1599.0, day - 12)
            ),
            checkedDay = day,
            addedDay = day - 60,
            freshness = Freshness.OK,
            // Set aside earlier by hand.
            excluded = listOf(SetAside(PricePoint(2999.0, day - 50), PricePoint(1699.0, day - 45))),
            // Sold out for ten days.
            stockGaps = listOf(StockGap(day - 28, day - 18)),
            sources = listOf(
                WishSource(
                    url = url, price = 1599.0, freshness = Freshness.OK, checkedDay = day,
                    amount = 1599.0, currency = UAH, rate = 1.0, listPrice = 1799.0,
                    memberPrice = 1519.0, memberTier = "https://rozetka.com.ua/#rozetka-card"
                )
            )
        )
    }
}

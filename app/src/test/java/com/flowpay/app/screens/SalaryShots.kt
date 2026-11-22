package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FlowPayTheme
import com.flowpay.app.Payday
import com.flowpay.app.SalaryEntry
import com.flowpay.app.SalaryKind
import com.flowpay.app.SalaryOverviewTile
import com.flowpay.app.SalarySheet
import com.flowpay.app.SalaryStore
import com.flowpay.app.Space
import com.flowpay.app.SurfaceLow
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.TextPrimary
import com.flowpay.app.monthKey
import com.flowpay.app.salaryJson
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
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
 * «Зарплата» on the JVM: the sheet empty and with half a year in it, the form, and
 * the tile on Огляд. PNGs in build/outputs/roborazzi/salary/.
 *
 * Run with `./gradlew :app:testDebugUnitTest -Pshots --tests '*SalaryShots*'`.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h873dp-440dpi")
class SalaryShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val payday = Payday(salary = 25, advance = 10)

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        app.getSharedPreferences("flowpay", 0).edit().clear().commit()
    }

    /** Half a year as an owner would have it: advance and salary most months, a bonus once. */
    private fun halfYear() {
        val now = LocalDate.now().withDayOfMonth(1)
        val log = (5 downTo 0).flatMap { back ->
            val month = now.minusMonths(back.toLong())
            val key = monthKey(month)
            val list = mutableListOf(
                SalaryEntry(month = key, kind = SalaryKind.ADVANCE, amount = 600.0 + back * 10, day = month.withDayOfMonth(10).toEpochDay(), rate = 41.2 + back * 0.1)
            )
            // This month: only the advance is in so far.
            if (back > 0) list += SalaryEntry(month = key, kind = SalaryKind.SALARY, amount = 950.0 - back * 15, day = month.withDayOfMonth(25).toEpochDay(), rate = 41.3 + back * 0.1)
            if (back == 2) list += SalaryEntry(month = key, kind = SalaryKind.OTHER, amount = 200.0, day = month.withDayOfMonth(15).toEpochDay(), note = "премія", rate = 41.0)
            list
        }
        RuntimeEnvironment.getApplication().getSharedPreferences("flowpay", 0).edit()
            .putString(SalaryStore.KEY, salaryJson(log)).commit()
        SalaryStore.version++
    }

    private fun saveScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/salary/$name.png")

    @Test
    fun empty() {
        rule.setContent { FlowPayTheme { SalarySheet(payday, 41.5) {} } }
        rule.waitForIdle()
        saveScreen("s1-empty")
    }

    @Test
    @Config(qualifiers = "uk-rUA-w393dp-h2400dp-440dpi")
    fun filled() {
        halfYear()
        rule.setContent { FlowPayTheme { SalarySheet(payday, 41.5) {} } }
        rule.waitForIdle()
        saveScreen("s2-filled")
    }

    @Test
    fun form() {
        halfYear()
        rule.setContent { FlowPayTheme { SalarySheet(payday, 41.5) {} } }
        rule.waitForIdle()
        rule.onAllNodesWithText("Записати виплату")[0].performClick()
        rule.waitForIdle()
        saveScreen("s3-form")
    }

    @Test
    fun tile() {
        halfYear()
        rule.setContent {
            FlowPayTheme {
                Surface(Modifier.fillMaxSize(), color = SurfaceLow, contentColor = TextPrimary) {
                    Column(Modifier.padding(Space.screen)) {
                        SalaryOverviewTile(LocalDate.now(), Modifier.fillMaxWidth()) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/salary/s4-tile.png")
    }

    @Test
    @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun overview() {
        halfYear()
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(TAB_OVERVIEW)) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/salary/s5-overview.png")
    }
}

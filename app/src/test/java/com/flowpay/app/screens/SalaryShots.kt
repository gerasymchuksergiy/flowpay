package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.SalaryEntry
import com.flowpay.app.SalaryKind
import com.flowpay.app.SalaryStore
import com.flowpay.app.TAB_PAYMENTS
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
 * «Зарплата» on the JVM, where it lives — the third view of Платежі: empty, with
 * half a year in it, the form behind the header's «+», and the paste form.
 * PNGs in build/outputs/roborazzi/salary/. The figures are made up.
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

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        app.getSharedPreferences("flowpay", 0).edit().clear().commit()
        SalaryStore.version++
    }

    /** Half a year: an advance mid-month, the salary early the next month, a bonus once. */
    private fun halfYear() {
        val now = LocalDate.now().withDayOfMonth(1)
        val log = (5 downTo 0).flatMap { back ->
            val month = now.minusMonths(back.toLong())
            val key = monthKey(month)
            val list = mutableListOf(
                SalaryEntry(month = key, kind = SalaryKind.ADVANCE, amount = 600.0 + back * 10, day = month.withDayOfMonth(16).toEpochDay(), rate = 41.2 + back * 0.1)
            )
            if (back > 0) list += SalaryEntry(month = key, kind = SalaryKind.SALARY, amount = 950.0 - back * 15, day = month.plusMonths(1).withDayOfMonth(2).toEpochDay(), rate = 41.3 + back * 0.1)
            if (back == 2) list += SalaryEntry(month = key, kind = SalaryKind.OTHER, amount = 200.0, day = month.withDayOfMonth(20).toEpochDay(), note = "премія", rate = 41.0)
            list
        }
        RuntimeEnvironment.getApplication().getSharedPreferences("flowpay", 0).edit()
            .putString(SalaryStore.KEY, salaryJson(log)).commit()
        SalaryStore.version++
    }

    private fun openSalary() {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(TAB_PAYMENTS)) }
        rule.waitForIdle()
        rule.onNodeWithText("Зарплата").performClick()
        rule.waitForIdle()
    }

    @Test
    fun empty() {
        openSalary()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/salary/s1-empty.png")
    }

    @Test
    @Config(qualifiers = "uk-rUA-w393dp-h2200dp-440dpi")
    fun filled() {
        halfYear()
        openSalary()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/salary/s2-filled.png")
    }

    @Test
    fun formFromHeader() {
        halfYear()
        openSalary()
        rule.onNodeWithContentDescription("Записати виплату").performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/salary/s3-form.png")
    }

    @Test
    @Config(qualifiers = "uk-rUA-w393dp-h1400dp-440dpi")
    fun paste() {
        openSalary()
        rule.onNodeWithText("Списком").performClick()
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextInput(
            "16.08.2026 аванс 500$ курс 41,2\n" +
                "02.09.2026 зарплата 520$ курс 41,3 за 08.2026\n" +
                "не виплата\n" +
                "16.09.2026 аванс 510$ курс 41,4"
        )
        rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/salary/s6-paste.png")
        rule.onNodeWithText("Додати 3").performClick()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/salary/s7-after-paste.png")
    }
}

package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.flowpay.app.FlowPayTheme
import com.flowpay.app.Payday
import com.flowpay.app.SalarySheet
import com.flowpay.app.SalaryStore
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * «Вставити списком» on the JVM: a pasted history with one line not understood, read
 * back before anything is added; then the log after «Додати».
 * PNGs in build/outputs/roborazzi/salary/. The figures are made up.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h1400dp-440dpi")
class SalaryPasteShots {

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

    @Test
    fun paste() {
        rule.setContent { FlowPayTheme { SalarySheet(Payday(salary = 2, advance = 16), 41.5) {} } }
        rule.waitForIdle()
        rule.onNodeWithText("Вставити кілька виплат списком").performClick()
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput(
            "16.08.2026 аванс 500$ курс 41,2\n" +
                "02.09.2026 зарплата 520$ курс 41,3 за 08.2026\n" +
                "не виплата\n" +
                "16.09.2026 аванс 510$ курс 41,4"
        )
        rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/salary/s6-paste.png")
        rule.onNodeWithText("Додати 3").performClick()
        rule.waitForIdle()
        captureScreenRoboImage("build/outputs/roborazzi/salary/s7-after-paste.png")
    }
}

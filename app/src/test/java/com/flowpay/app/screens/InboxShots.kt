package com.flowpay.app.screens

import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.flowpay.app.AppCommand
import com.flowpay.app.FlowPayApp
import com.flowpay.app.FlowPayTheme
import com.flowpay.app.InboxConnected
import com.flowpay.app.InboxSettingsItem
import com.flowpay.app.InboxSheet
import com.flowpay.app.InboxStore
import com.flowpay.app.Space
import com.flowpay.app.SurfaceLow
import com.flowpay.app.TAB_OVERVIEW
import com.flowpay.app.TextPrimary
import com.flowpay.app.Type
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

/**
 * «Telegram-скринька» on the JVM: the sheet before connecting, waiting for Start and
 * bound, the row on its own and the row in Огляд. PNGs in build/outputs/roborazzi/inbox/.
 *
 * Run with `./gradlew :app:testDebugUnitTest -Pshots --tests '*InboxShots*'`.
 *
 * The keystore does not exist on the JVM, so a connected bot is seeded as preferences
 * only (a stand-in token string the sheet never opens). The waiting state is drawn
 * from its own composable, because the sheet starts asking Telegram the moment it
 * shows that state — and here that pass would find the stand-in token unreadable.
 */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "uk-rUA-w393dp-h873dp-440dpi")
class InboxShots {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun seed() {
        val app = RuntimeEnvironment.getApplication()
        Settings.Global.putFloat(app.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val pack = java.io.File(System.getProperty("flowpay.emojiPack") ?: "C:/Temp/flowpay-emoji")
        if (pack.isDirectory) pack.copyRecursively(java.io.File(app.filesDir, "emoji"), overwrite = true)
        app.getSharedPreferences(InboxStore.FILE, 0).edit().clear().commit()
    }

    /** A bot as connecting leaves it; bound or still waiting for Start. */
    private fun connected(bound: Boolean) {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences(InboxStore.FILE, 0).edit()
            .putString("tg_tok", "screenshot")
            .putString("tg_user", "flowpay_inbox_bot")
        if (bound) {
            prefs.putLong("tg_chat", 5_550_001L).putLong("tg_last", System.currentTimeMillis() - 4 * 60_000L)
        } else {
            prefs.putString("tg_code", "482913")
        }
        prefs.commit()
    }

    private fun saveScreen(name: String) = captureScreenRoboImage("build/outputs/roborazzi/inbox/$name.png")

    private fun save(name: String) = rule.onRoot().captureRoboImage("build/outputs/roborazzi/inbox/$name.png")

    @Test
    fun setup() {
        rule.setContent { FlowPayTheme { InboxSheet {} } }
        rule.waitForIdle()
        saveScreen("i1-setup")
    }

    @Test
    fun waiting() {
        connected(bound = false)
        rule.setContent {
            FlowPayTheme {
                // The sheet's own surface and ink, which FormSheet gives its content.
                Surface(Modifier.fillMaxSize(), color = SurfaceLow, contentColor = TextPrimary) {
                    Column(Modifier.padding(horizontal = Space.screen, vertical = Space.xl)) {
                        Text("Telegram-скринька", fontSize = Type.sectionSize, lineHeight = Type.sectionLine, fontWeight = Type.medium)
                        Column(Modifier.fillMaxWidth().padding(top = Space.md)) {
                            InboxConnected(InboxStore(rule.activity), 0, bound = false, waitedOut = false) {}
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        save("i2-waiting")
    }

    @Test
    fun waitedOut() {
        connected(bound = false)
        rule.setContent {
            FlowPayTheme {
                Surface(Modifier.fillMaxSize(), color = SurfaceLow, contentColor = TextPrimary) {
                    Column(Modifier.padding(horizontal = Space.screen, vertical = Space.xl)) {
                        InboxConnected(InboxStore(rule.activity), 0, bound = false, waitedOut = true) {}
                    }
                }
            }
        }
        rule.waitForIdle()
        save("i2b-waited-out")
    }

    @Test
    fun bound() {
        connected(bound = true)
        rule.setContent { FlowPayTheme { InboxSheet {} } }
        rule.waitForIdle()
        saveScreen("i3-connected")
    }

    @Test
    fun rows() {
        connected(bound = true)
        rule.setContent {
            FlowPayTheme {
                Surface(Modifier.fillMaxSize(), color = SurfaceLow, contentColor = TextPrimary) {
                    Column(Modifier.padding(vertical = Space.xl)) {
                        InboxSettingsItem {}
                    }
                }
            }
        }
        rule.waitForIdle()
        save("i4-row-bound")
    }

    @Test
    @Config(qualifiers = "uk-rUA-w393dp-h2600dp-440dpi")
    fun overview() {
        rule.setContent { FlowPayApp(rule.activity, AppCommand.OpenTab(TAB_OVERVIEW)) }
        rule.waitForIdle()
        save("i5-overview")
    }
}

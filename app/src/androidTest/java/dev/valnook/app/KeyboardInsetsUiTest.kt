package dev.valnook.app

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.repository.SaveFinancialSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import javax.inject.Inject

/** Edge-to-edge windows are not resized for the IME, so focused inputs must be kept above it by the app. */
@HiltAndroidTest
class KeyboardInsetsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    private val graph get() = sessions.session.value.graph
    private val automation get() = InstrumentationRegistry.getInstrumentation().uiAutomation
    private var keyboardSetting: String? = null
    private var accessibilityFlags: Int? = null

    @Before fun prepare() {
        val original = shell("settings get secure show_ime_with_hard_keyboard").trim()
        require(original in listOf("0", "1", "null"))
        keyboardSetting = original
        shell("settings put secure show_ime_with_hard_keyboard 1")
        hilt.inject()
    }

    @After fun restore() {
        accessibilityFlags?.let { flags -> automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
        keyboardSetting?.let {
            shell(if (it == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $it")
        }
    }

    @Test fun exchange_rate_fields_and_save_stay_above_the_keyboard() {
        val sources = Currency.supported.filter { it.code != "CNY" }.take(10)
        runBlocking {
            val current = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(SaveFinancialSettings(current.revision, Currency.of("CNY"),
                sources.map { FxRate(it, Currency.of("CNY"), BigDecimal("1.5")) }))
        }
        openSettings()
        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_exchange_rates)).performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("fx-rate-0").fetchSemanticsNodes().isNotEmpty() }
        val last = rule.onNodeWithTag("fx-rate-${sources.lastIndex}")
        last.performScrollTo().performClick()
        showScreenKeyboard()
        assertAboveKeyboard("fx-last-rate", last)
        // Scroll by finger like a user would; the keyboard stays open on the focused rate.
        val saveLabel = text(dev.valnook.feature.settings.R.string.settings_save)
        repeat(4) {
            if (rule.onAllNodesWithText(saveLabel).fetchSemanticsNodes().isEmpty())
                rule.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp(startY = centerY, endY = top) }
        }
        rule.onNode(hasScrollToNodeAction()).performTouchInput { swipeUp(startY = centerY, endY = top) }
        assertAboveKeyboard("fx-save", rule.onNodeWithText(saveLabel))
    }

    @Test fun clear_data_input_and_confirm_stay_above_the_keyboard() {
        openSettings()
        rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_clear_data)).performScrollTo().performClick()
        rule.waitUntil(10_000) { rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() }
        val input = rule.onNode(hasSetTextAction())
        input.performClick()
        showScreenKeyboard()
        assertAboveKeyboard("clear-input", input)
        val confirm = rule.onNodeWithText(text(dev.valnook.feature.settings.R.string.settings_confirm_clear))
        confirm.performScrollTo()
        assertAboveKeyboard("clear-confirm", confirm)
    }

    private fun openSettings() {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-backup-export").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun text(id: Int) = rule.activity.getString(id)

    private fun imeBottom(): Int = rule.runOnUiThread {
        ViewCompat.getRootWindowInsets(rule.activity.window.decorView)!!.getInsets(WindowInsetsCompat.Type.ime()).bottom
    }

    private fun assertAboveKeyboard(name: String, node: SemanticsNodeInteraction) {
        // The IME and its inset animation run on the platform clock, not the Compose test clock.
        SystemClock.sleep(500)
        rule.waitForIdle()
        PlatformTestStorageRegistry.getInstance().openOutputFile("keyboard-$name.png").use {
            requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Measure after the screenshot, so a late scroll back would still be caught.
        SystemClock.sleep(300)
        rule.waitForIdle()
        val keyboardTop = rule.runOnUiThread {
            val view = rule.activity.window.decorView
            val location = IntArray(2).also(view::getLocationOnScreen)
            location[1] + view.height - imeBottom()
        }
        val semantics = node.fetchSemanticsNode()
        val top = semantics.positionOnScreen.y
        val bottom = top + semantics.size.height
        assertTrue("$name top=$top bottom=$bottom keyboardTop=$keyboardTop", top >= 0f && bottom <= keyboardTop + 1f)
    }

    private fun showScreenKeyboard() {
        // The emulator's hardware-keyboard toolbar reports IME visibility without an on-screen keyboard.
        val info = automation.serviceInfo
        if (accessibilityFlags == null) accessibilityFlags = info.flags
        info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        fun findAction(node: android.view.accessibility.AccessibilityNodeInfo?, label: String):
            android.view.accessibility.AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (index in 0 until node.childCount) findAction(node.getChild(index), label)?.let { return it }
            return null
        }
        rule.waitUntil(5_000) { imeBottom() > 0 }
        SystemClock.sleep(400)
        val toolbar = automation.windows.firstNotNullOfOrNull { findAction(it.root, "Open more keyboard options") }
        if (toolbar != null) {
            assertTrue(toolbar.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(300)
            val show = automation.windows.firstNotNullOfOrNull { findAction(it.root, "Show on-screen keyboard") }
            assertNotNull("Expected keyboard expansion action", show)
            assertTrue(show!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
        }
        val minimum = 150 * rule.activity.resources.displayMetrics.density
        rule.waitUntil(5_000) { imeBottom() > minimum }
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
}

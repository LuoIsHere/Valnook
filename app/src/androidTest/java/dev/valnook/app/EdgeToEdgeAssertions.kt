package dev.valnook.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import kotlin.math.abs

/**
 * Asserts that the scrolling area [matcher] finds runs down to the screen's bottom edge, behind the navigation bar,
 * instead of stopping above it. The bar must have a height, or the check would say nothing.
 */
fun AndroidComposeTestRule<*, out ComponentActivity>.assertReachesScreenBottom(name: String, matcher: SemanticsMatcher) {
    waitForIdle()
    val (screenBottom, navigationBar) = runOnUiThread {
        val view = activity.window.decorView
        val location = IntArray(2).also(view::getLocationOnScreen)
        val bar = ViewCompat.getRootWindowInsets(view)!!.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
        location[1] + view.height to bar
    }
    assertTrue("$name: the navigation bar has no height", navigationBar > 0)
    val node = onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
    val bottom = node.positionOnScreen.y + node.size.height
    assertTrue("$name bottom=$bottom screenBottom=$screenBottom navigationBar=$navigationBar",
        abs(bottom - screenBottom) <= 1f)
}

/**
 * The emulator's on-screen keyboard, measured against the window [view] gives. Edge-to-edge windows are not resized
 * for it, so the app must keep things above it. Call [prepare] before the test and [restore] after it.
 */
class ScreenKeyboard(private val view: () -> View) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val automation get() = instrumentation.uiAutomation
    private var keyboardSetting: String? = null
    private var accessibilityFlags: Int? = null

    /** Shows the on-screen keyboard beside the emulator's hardware one. */
    fun prepare() {
        val original = shell("settings get secure show_ime_with_hard_keyboard").trim()
        require(original in listOf("0", "1", "null"))
        keyboardSetting = original
        shell("settings put secure show_ime_with_hard_keyboard 1")
    }

    fun restore() {
        accessibilityFlags?.let { flags -> automation.serviceInfo = automation.serviceInfo.apply { this.flags = flags } }
        keyboardSetting?.let {
            shell(if (it == "null") "settings delete secure show_ime_with_hard_keyboard"
                else "settings put secure show_ime_with_hard_keyboard $it")
        }
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun insets(type: Int): Int = onMain { ViewCompat.getRootWindowInsets(view())!!.getInsets(type).bottom }

    fun imeBottom(): Int = insets(WindowInsetsCompat.Type.ime())

    fun navigationBar(): Int = insets(WindowInsetsCompat.Type.navigationBars())

    /** The window's bottom edge on the screen: the screen's own, for an edge-to-edge window. */
    fun windowBottom(): Int = onMain {
        val view = view()
        val location = IntArray(2).also(view::getLocationOnScreen)
        location[1] + view.height
    }

    /** The keyboard's top edge on the screen; the window's bottom while it is down. */
    fun top(): Int = windowBottom() - imeBottom()

    fun show(rule: ComposeTestRule) {
        // The emulator's hardware-keyboard toolbar reports IME visibility without an on-screen keyboard.
        val info = automation.serviceInfo
        if (accessibilityFlags == null) accessibilityFlags = info.flags
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        automation.serviceInfo = info
        fun findAction(node: AccessibilityNodeInfo?, label: String): AccessibilityNodeInfo? {
            if (node == null) return null
            if (node.contentDescription?.toString() == label || node.text?.toString() == label) return node
            for (index in 0 until node.childCount) findAction(node.getChild(index), label)?.let { return it }
            return null
        }
        rule.waitUntil(5_000) { imeBottom() > 0 }
        SystemClock.sleep(400)
        val toolbar = automation.windows.firstNotNullOfOrNull { findAction(it.root, "Open more keyboard options") }
        if (toolbar != null) {
            assertTrue(toolbar.performAction(AccessibilityNodeInfo.ACTION_CLICK))
            SystemClock.sleep(300)
            val show = automation.windows.firstNotNullOfOrNull { findAction(it.root, "Show on-screen keyboard") }
            assertNotNull("Expected keyboard expansion action", show)
            assertTrue(show!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        }
        val minimum = 150 * onMain { view().resources.displayMetrics.density }
        rule.waitUntil(5_000) { imeBottom() > minimum }
    }

    /** Waits for the keyboard's animation, saves a screenshot as "keyboard-[name]", and measures [node] against it. */
    fun assertAbove(rule: ComposeTestRule, name: String, node: SemanticsNodeInteraction) {
        settle(rule, name)
        val semantics = node.fetchSemanticsNode()
        val top = semantics.positionOnScreen.y
        val bottom = top + semantics.size.height
        val keyboardTop = top()
        assertTrue("$name top=$top bottom=$bottom keyboardTop=$keyboardTop", top >= 0f && bottom <= keyboardTop + 1f)
    }

    /** As [assertAbove], and also not floating over it: [node]'s bottom edge is the keyboard's top. */
    fun assertOn(rule: ComposeTestRule, name: String, node: SemanticsNodeInteraction) {
        settle(rule, name)
        val semantics = node.fetchSemanticsNode()
        val bottom = semantics.positionOnScreen.y + semantics.size.height
        val keyboardTop = top()
        assertTrue("$name bottom=$bottom keyboardTop=$keyboardTop", abs(bottom - keyboardTop) <= 1f)
    }

    private fun settle(rule: ComposeTestRule, name: String) {
        // The IME and its inset animation run on the platform clock, not the Compose test clock.
        SystemClock.sleep(500)
        rule.waitForIdle()
        if (TestShots.enabled) PlatformTestStorageRegistry.getInstance().openOutputFile("keyboard-$name.png").use {
            requireNotNull(automation.takeScreenshot()).compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        // Measure a moment later, so a late scroll back would still be caught.
        SystemClock.sleep(300)
        rule.waitForIdle()
    }

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command))
        .bufferedReader().use { it.readText() }
}

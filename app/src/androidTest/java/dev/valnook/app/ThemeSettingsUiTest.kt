package dev.valnook.app

import android.app.DatePickerDialog
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.appearance.ThemePreferences
import dev.valnook.feature.settings.AppThemeMode
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File

@HiltAndroidTest
class ThemeSettingsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private lateinit var context: Context
    private var originalTheme: String? = null
    private var originalSystemMode = UiModeManager.MODE_NIGHT_NO

    @Before fun prepare() {
        hilt.inject()
        context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        originalTheme = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).getString("theme", null)
        originalSystemMode = context.getSystemService(UiModeManager::class.java).nightMode
        assertTrue(runBlocking { ThemePreferences(context).save(AppThemeMode.SYSTEM) })
        rule.activityRule.scenario.recreate()
        waitForRoot()
    }

    @After fun restore() {
        if (!::context.isInitialized) return
        val editor = context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
        if (originalTheme == null) editor.remove("theme") else editor.putString("theme", originalTheme)
        assertTrue(editor.commit())
        systemMode(when (originalSystemMode) {
            UiModeManager.MODE_NIGHT_YES -> "yes"
            UiModeManager.MODE_NIGHT_AUTO -> "auto"
            UiModeManager.MODE_NIGHT_CUSTOM -> "custom"
            else -> "no"
        })
        rule.activityRule.scenario.recreate()
        waitForRoot()
    }

    private fun systemMode(value: String) {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("cmd uimode night $value")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
    }

    private fun waitForRoot() = rule.waitUntil(10_000) {
        rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty()
    }

    private fun openPicker() {
        waitForRoot()
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-theme").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-theme").performScrollTo().performClick()
        rule.onNodeWithTag("theme-picker").assertIsDisplayed()
    }

    private fun choose(mode: AppThemeMode) {
        openPicker()
        rule.onNodeWithTag("theme-option-${mode.name}").performClick()
        try {
            rule.waitUntil(10_000) {
                ThemePreferences(context).read() == mode &&
                    rule.onAllNodesWithTag("theme-picker").fetchSemanticsNodes().isEmpty() &&
                    rule.onAllNodesWithTag("settings-theme").fetchSemanticsNodes().isNotEmpty()
            }
        } catch (failure: Throwable) {
            throw AssertionError("Saved=${ThemePreferences(context).read()}, " +
                "night=${rule.activity.resources.configuration.uiMode}; " +
                rule.onAllNodes(isRoot()).printToString(maxDepth = 8), failure)
        }
        rule.onNodeWithTag("nav-settings").assertIsSelected()
    }

    private fun assertAppearance(dark: Boolean) {
        val expected = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        rule.waitUntil(10_000) {
            rule.activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == expected
        }
        rule.waitForIdle()
        rule.runOnUiThread {
            val activity = rule.activity
            val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertEquals(!dark, controller.isAppearanceLightStatusBars)
            assertEquals(!dark, controller.isAppearanceLightNavigationBars)
            val dialog = DatePickerDialog(activity, null, 2026, 9, 5)
            assertEquals(expected, dialog.context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        }
        val image = rule.onRoot().captureToImage().asAndroidBitmap()
        try {
            // Page edge is outside the glass cards and must use the selected theme's background.
            val pixel = image.getPixel(1, image.height / 2)
            val brightness = (android.graphics.Color.red(pixel) + android.graphics.Color.green(pixel) +
                android.graphics.Color.blue(pixel)) / 3
            assertTrue("Rendered theme must match resource configuration: $brightness", if (dark) brightness < 70 else brightness > 220)
        } finally { image.recycle() }
    }

    private fun screenshot(name: String) {
        val node = if (name.startsWith("theme-picker")) rule.onNodeWithTag("theme-picker") else rule.onRoot()
        val bitmap = node.captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun choices_persist_restore_and_follow_system_without_changing_ledger_settings() {
        systemMode("no")
        assertAppearance(false)
        openPicker()
        rule.onNodeWithTag("theme-option-SYSTEM").assertIsSelected()
        rule.onNodeWithTag("theme-option-DARK").assertIsNotSelected()
        rule.onNodeWithTag("theme-option-LIGHT").assertIsNotSelected()
        rule.onNodeWithText(rule.activity.getString(dev.valnook.feature.settings.R.string.settings_cancel)).performClick()
        assertEquals(AppThemeMode.SYSTEM, ThemePreferences(context).read())

        choose(AppThemeMode.DARK)
        assertAppearance(true)
        screenshot("theme-settings-dark")
        assertTrue(File(context.applicationInfo.dataDir, "shared_prefs/appearance.xml").readText().contains("DARK"))
        rule.activityRule.scenario.recreate()
        assertAppearance(true)
        openPicker()
        rule.onNodeWithTag("theme-option-DARK").assertIsSelected()
        screenshot("theme-picker-dark")
        rule.onNodeWithTag("theme-option-DARK").performClick()
        systemMode("yes")
        assertAppearance(true)

        choose(AppThemeMode.LIGHT)
        assertAppearance(false)
        screenshot("theme-settings-light")
        rule.activityRule.scenario.recreate()
        assertAppearance(false)
        systemMode("no")
        assertAppearance(false)
        systemMode("yes")
        assertAppearance(false)

        choose(AppThemeMode.SYSTEM)
        assertAppearance(true)
        systemMode("no")
        assertAppearance(false)
        systemMode("yes")
        assertAppearance(true)
    }

    @Test fun unknown_saved_theme_falls_back_to_system() {
        assertTrue(context.getSharedPreferences("appearance", Context.MODE_PRIVATE).edit()
            .putString("theme", "FUTURE_THEME").commit())
        assertEquals(AppThemeMode.SYSTEM, ThemePreferences(context).read())
        rule.activityRule.scenario.recreate()
        val systemNight = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        assertAppearance(systemNight == Configuration.UI_MODE_NIGHT_YES)
        openPicker()
        rule.onNodeWithTag("theme-option-SYSTEM").assertIsSelected()
    }
}

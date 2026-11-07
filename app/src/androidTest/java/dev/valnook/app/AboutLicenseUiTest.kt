package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.navigation.ValnookRoot
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Settings and About name the MIT License, and About opens the repository's LICENSE in full. */
@HiltAndroidTest
class AboutLicenseUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("about-license-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun settings_and_about_name_the_license_and_open_its_full_text() {
        rule.runOnUiThread { rule.activity.setContent { ValnookRoot(rule.activity.sessions, rule.activity.webAdmin) } }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-footer-license"))
        rule.onNodeWithTag("settings-footer-license").assertTextEquals("MIT License")
        save("settings")

        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-about"))
        rule.onNodeWithTag("settings-about").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("about-page").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("about-license-name").assertTextEquals("MIT License")
        // The entry is called License in every language.
        rule.onNodeWithTag("about-license").assertTextContains("License", substring = true)
        save("about")

        rule.onNodeWithTag("about-license").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("license-text").fetchSemanticsNodes().isNotEmpty() }
        val text = rule.onNodeWithTag("license-text").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertTrue(text, text.startsWith("MIT License"))
        assertTrue("The whole text, through its last clause", text.contains("Permission is hereby granted, free of charge") &&
            text.endsWith("SOFTWARE."))
        // Paragraphs wrap at the screen's width rather than at the file's 80 columns.
        assertTrue(text, text.contains("any person obtaining a copy of this software"))
        val paragraphs = text.split("\n\n")
        assertTrue(text, paragraphs.size == 5 && paragraphs.none { "\n" in it })
        // The title bar names the page.
        assertTrue(rule.onAllNodesWithText("License", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        save("text")
    }
}

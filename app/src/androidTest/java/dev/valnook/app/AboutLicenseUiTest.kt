package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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

    @Test fun about_opens_the_third_party_notices_from_top_to_bottom() {
        rule.runOnUiThread { rule.activity.setContent { ValnookRoot(rule.activity.sessions, rule.activity.webAdmin) } }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-about"))
        rule.onNodeWithTag("settings-about").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("about-page").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("about-page").performScrollToNode(hasTestTag("about-third-party"))
        rule.onNodeWithTag("about-third-party").assertHasClickAction().performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("third-party-page").fetchSemanticsNodes().isNotEmpty() }
        // The file as the repository keeps it: its title first, then the icon attribution.
        rule.onNodeWithText("THIRD-PARTY NOTICES").assertIsDisplayed()
        rule.onNodeWithTag("third-party-page").performScrollToNode(hasText("Font Awesome Free 7.3.1", substring = true))
        rule.onNodeWithText("CC BY 4.0", substring = true).assertExists()
        save("third-party-top")
        // The Apache License closes the file.
        rule.onNodeWithTag("third-party-page").performScrollToNode(hasText("END OF TERMS AND CONDITIONS"))
        rule.onNodeWithText("END OF TERMS AND CONDITIONS").assertIsDisplayed()
        save("third-party-end")
    }

    @Test fun settings_and_about_name_the_license_and_open_its_full_text() {
        rule.runOnUiThread { rule.activity.setContent { ValnookRoot(rule.activity.sessions, rule.activity.webAdmin) } }
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-list").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-footer-license"))
        rule.onNodeWithTag("settings-footer-license").assertTextEquals("MIT License")
        // Clear data sits in a card like the other entries, and the footer follows it without a wide gap.
        val clear = rule.onNodeWithTag("settings-clear-data").assertHasClickAction().fetchSemanticsNode().boundsInRoot
        val footer = rule.onNodeWithTag("settings-footer").fetchSemanticsNode().boundsInRoot
        val gap = with(rule.density) { (footer.top - clear.bottom).toDp() }
        assertTrue("gap $gap", gap > 0.dp && gap <= 24.dp)
        // The version code follows the version name, as Play and backups know the build.
        val version = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        rule.onNodeWithTag("settings-footer-version").assertTextEquals(version)
        save("settings")

        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-about"))
        rule.onNodeWithTag("settings-about").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("about-page").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("about-license-name").assertTextEquals("MIT License")
        // The version and the internal build, as at the foot of Settings.
        rule.onNodeWithTag("about-version").assertTextEquals(version)
        rule.onNodeWithTag("about-internal-build").assertTextEquals("Internal build: " + BuildConfig.INTERNAL_BUILD_ID)
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

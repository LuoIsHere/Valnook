package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookDialog
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.feature.webadmin.WebAdminLockScreen
import org.junit.Rule
import org.junit.Test

/** What fits a tall portrait screen must still work in a short window: landscape, large text, the keyboard up. */
@HiltAndroidTest
class ShortScreenUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Test fun a_dialog_body_taller_than_the_screen_scrolls_and_leaves_the_buttons() {
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme {
            ValnookDialog({}, { TextButton({}, Modifier.testTag("short-confirm")) { Text("OK") } },
                dismissButton = { TextButton({}, Modifier.testTag("short-cancel")) { Text("Cancel") } },
                title = { Text("Synthetic notice") },
                text = { Column(Modifier.verticalScroll(rememberScrollState()).testTag("short-body")) {
                    repeat(80) { Text("Synthetic line $it") }
                } })
        } } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("short-confirm").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("short-confirm").assertIsDisplayed()
        rule.onNodeWithTag("short-cancel").assertIsDisplayed()
        rule.onNodeWithTag("short-body").performScrollToNode(hasText("Synthetic line 79"))
        rule.onNodeWithText("Synthetic line 79").assertIsDisplayed()
        rule.onNodeWithTag("short-confirm").assertIsDisplayed()
    }

    /** Its end button is the only way out of the lock, so it must be reachable however little room there is. */
    @Test fun the_web_admin_lock_scrolls_to_its_end_button_in_a_short_window() {
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme {
            Box(Modifier.fillMaxWidth().height(240.dp)) { WebAdminLockScreen(null) {} }
        } } }
        rule.waitForIdle()
        rule.onNodeWithTag("webadmin-lock-end").performScrollTo().assertIsDisplayed()
    }
}

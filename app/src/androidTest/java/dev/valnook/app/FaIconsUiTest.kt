package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.valnook.core.designsystem.R
import dev.valnook.designsystem.AddIcon
import dev.valnook.designsystem.SelectedIcon
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.designsystem.accountSymbolResource
import dev.valnook.domain.model.AccountSymbols
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Saved account icon keys keep their meaning, on Android and on the web page, after moving to Font Awesome. */
class FaIconsUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun every_account_key_has_its_own_icon() {
        val icons = AccountSymbols.keys.associateWith(::accountSymbolResource)
        assertEquals("Two keys share an icon: $icons", AccountSymbols.keys.size, icons.values.toSet().size)
        assertEquals(accountSymbolResource("account_balance"), accountSymbolResource("not_a_key"))
        icons.values.forEach { id ->
            assertTrue(rule.activity.resources.getResourceEntryName(id).startsWith("fa_"))
            assertTrue(rule.activity.getDrawable(id) != null)
        }
    }

    @Test fun the_web_picker_lists_exactly_the_account_keys() {
        val script = rule.activity.assets.open("webadmin/symbols.js").bufferedReader().use { it.readText() }
        fun keys(name: String) = Regex("""export const $name = (\{.*?\});\n""").find(script)!!.groupValues[1]
            .let { Regex(""""(\w+)": \{"viewBox"""").findAll(it).map { match -> match.groupValues[1] }.toSet() }
        assertEquals(AccountSymbols.keys, keys("symbols"))
        assertEquals(setOf("ui_refresh", "ui_close", "ui_up", "ui_down", "ui_delete"), keys("uiSymbols"))
    }

    @Test fun add_icon_is_large_inside_a_full_size_touch_target() {
        // The theme's root passes its size down as a minimum; the outer Box lets the button take its own size.
        rule.setContent { ValnookTheme { Box { IconButton({}, Modifier.testTag("button")) { Box(Modifier.testTag("icon")) { AddIcon() } } } } }
        rule.onNodeWithTag("button", useUnmergedTree = true).assertTouchWidthIsEqualTo(48.dp).assertTouchHeightIsEqualTo(48.dp)
        val icon = rule.onNodeWithTag("icon", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(with(rule.density) { 28.dp.toPx() }, icon.width, 1f)
    }

    @Test fun selected_icon_says_so_to_screen_readers() {
        rule.setContent { ValnookTheme { SelectedIcon() } }
        rule.onNodeWithContentDescription(rule.activity.getString(R.string.icon_selected)).assertIsDisplayed()
    }
}

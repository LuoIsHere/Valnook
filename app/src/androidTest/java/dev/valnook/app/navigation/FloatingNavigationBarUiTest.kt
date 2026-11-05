package dev.valnook.app.navigation

import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.NavigationItemId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test

/** Every tab name shows whole, however narrow the screen or large the text. */
class FloatingNavigationBarUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun showsWhole(locale: Locale, fontScale: Float, labels: List<String>) {
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocale(locale) }
            val context = LocalContext.current.createConfigurationContext(configuration)
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides configuration,
                LocalResources provides context.resources, LocalDensity provides Density(density, fontScale)) {
                ValnookTheme {
                    Box { FloatingNavigationBar(AccountsKey, NavigationItemId.entries.toList(), {}, Modifier.width(360.dp)) }
                }
            }
        }
        rule.waitForIdle()
        labels.forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).fetchSemanticsNode()
                .config[SemanticsActions.GetTextLayoutResult].action!!(results)
            // Not hasVisualOverflow: it also reports text that is narrower than the space it was given.
            val layout = results.single()
            assertFalse("$label is cut off at font scale $fontScale", layout.multiParagraph.didExceedMaxLines)
            assertEquals(label, label.length, layout.getLineEnd(0, visibleEnd = true))
        }
    }

    private val english = listOf("Accounts", "Wallet", "Investments", "Statistics", "Settings")

    @Test fun english_tabs_are_whole_at_normal_size() = showsWhole(Locale.ENGLISH, 1f, english)

    @Test fun english_tabs_are_whole_at_large_size() = showsWhole(Locale.ENGLISH, 1.5f, english)

    @Test fun chinese_tabs_are_whole_at_large_size() =
        showsWhole(Locale.SIMPLIFIED_CHINESE, 1.5f, listOf("账户", "卡包", "投资", "统计", "设置"))
}

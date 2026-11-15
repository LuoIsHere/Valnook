package dev.valnook.app

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.feature.ai.AiGuideScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Settings → AI → Quick start on its own: links are caught here, so nothing reaches another site. */
class AiGuideUiTest {
    @get:Rule val rule = createComposeRule()
    private val opened = mutableListOf<String>()
    private var fills = 0

    private fun show() = rule.setContent {
        ValnookTheme(dark_theme = false) {
            CompositionLocalProvider(LocalUriHandler provides object : UriHandler {
                override fun openUri(uri: String) { opened += uri }
            }) { AiGuideScreen(onFill = { fills++ }) }
        }
    }

    /** An example value shown under its label; it sits in a selectable box, so it is looked for inside. */
    private fun value(tag: String, text: String) =
        rule.onNode(hasAnyAncestor(hasTestTag(tag)) and hasText(text), useUnmergedTree = true).assertExists()

    private fun next() {
        rule.onNodeWithTag("ai-guide-next").performClick()
        rule.waitForIdle()
    }

    @Test fun the_notice_comes_first_and_the_quick_setup_last() {
        show()
        rule.onNodeWithTag("ai-guide-step").assertTextEquals("1 / 8")
        rule.onNodeWithTag("ai-guide-previous").assertIsNotEnabled()
        rule.onNodeWithText("Valnook 与 DeepSeek 及其关联方不存在任何合作或隶属关系", substring = true).assertExists()
        rule.onNodeWithText("核对日期为 2026年11月15日", substring = true).assertExists()
        rule.onNodeWithText("建议为 Valnook 单独创建一个 API Key", substring = true).assertExists()
        rule.onNodeWithText("因 Key 泄露或被盗用造成的损失，Valnook 不承担责任", substring = true).assertExists()
        repeat(7) { next() }
        rule.onNodeWithTag("ai-guide-step").assertTextEquals("8 / 8")
        rule.onNodeWithTag("ai-guide-next").assertIsNotEnabled()
        value("ai-guide-fill-url", "https://api.deepseek.com")
        value("ai-guide-fill-model", "deepseek-flash")
        value("ai-guide-fill-extra", "（留空）")
        value("ai-guide-fill-thinking-off", """{"thinking": {"type": "disabled"}}""")
        rule.onNodeWithTag("ai-guide-fill").performScrollTo().performClick()
        assertEquals(1, fills)
        // Back a step.
        rule.onNodeWithTag("ai-guide-previous").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("ai-guide-step").assertTextEquals("7 / 8")
    }

    @Test fun links_show_their_address_and_open_in_the_browser() {
        show()
        next(); next()
        rule.onNodeWithTag("ai-guide-link-api-keys").assertTextContains("https://platform.deepseek.com/api_keys", substring = true)
            .performScrollTo().performClick()
        assertEquals(listOf("https://platform.deepseek.com/api_keys"), opened)
        // The documentation in the app's language.
        next()
        rule.onNodeWithTag("ai-guide-link-docs").performScrollTo().performClick()
        assertEquals("https://api-docs.deepseek.com/zh-cn/", opened.last())
    }
}

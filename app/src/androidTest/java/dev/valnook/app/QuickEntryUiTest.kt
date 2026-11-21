package dev.valnook.app

import android.content.Intent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.quick.QuickEntryPreferences
import dev.valnook.app.quick.QuickShortcuts
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.repository.SaveNavigationConfiguration
import dev.valnook.feature.settings.QuickLaunchTarget
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Settings → Quick bookkeeping: opening straight to a bill on a fresh start, the icon's long-press shortcuts, and the
 * share sheet entry.
 */
@HiltAndroidTest
class QuickEntryUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createEmptyComposeRule()
    @Inject lateinit var sessions: AppSessionManager
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val quick = QuickEntryPreferences.of(context)
    private val graph get() = sessions.session.value.graph
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun seed() {
        hilt.inject()
        reset()
        runBlocking {
            val settings = graph.settings.observeSettings().first()
            graph.settingsWriter.applyChange(SaveNavigationConfiguration(settings.revision, NavigationConfiguration(
                NavigationItemId.entries, setOf(NavigationItemId.BILLS, NavigationItemId.ACCOUNTS, NavigationItemId.SETTINGS))))
        }
    }

    @After fun clean() {
        runBlocking { sessions.exitDemo() }
        scenario?.close()
        reset()
    }

    private fun reset() {
        quick.setLaunch(false)
        quick.setTarget(QuickLaunchTarget.MANUAL)
        quick.setShare(true)
        shortcuts.removeAllDynamicShortcuts()
    }

    private val shortcuts = context.getSystemService(android.content.pm.ShortcutManager::class.java)
    private fun shortcutIds() = shortcuts.dynamicShortcuts.sortedBy { it.rank }.map { it.id }

    /** The intent a published shortcut starts, as the launcher would. */
    private fun shortcut(id: String): Intent {
        waitUntil { id in shortcutIds() }
        return shortcuts.dynamicShortcuts.single { it.id == id }.intent!!
    }

    private fun waitUntil(condition: () -> Boolean) = rule.waitUntil(10_000, condition)

    private fun launch(fromIcon: Boolean = true, intent: Intent? = null) {
        scenario?.close()
        // A copy: the scenario adds its own flags to the intent it is given.
        scenario = ActivityScenario.launch(intent?.let(::Intent) ?: Intent(context, MainActivity::class.java).apply {
            if (fromIcon) { action = Intent.ACTION_MAIN; addCategory(Intent.CATEGORY_LAUNCHER) }
        })
    }

    /** A shortcut used while the app runs. */
    private fun use(intent: Intent) = scenario!!.onActivity { it.startActivity(intent) }

    private fun waitTag(tag: String, timeout: Long = 10_000) =
        rule.waitUntil(timeout) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun enableAi() = runBlocking {
        val ai = graph.ai!!
        ai.saveConnection(AiConnection(null, "https://ai.example.test", "synthetic-model"), "synthetic-key")
        val fingerprint = ai.observeSettings().first().connection.fingerprint
        TestGraphs.real.aiChecks.save(AiCheckReport(fingerprint, 0, mapOf(AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED),
            AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED))))
        ai.setEnabled(true)
    }

    private fun shareTargets() = context.packageManager.queryIntentActivities(
        Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName), 0)

    @Test fun a_fresh_start_from_the_icon_opens_a_new_bill_once() {
        quick.setLaunch(true)
        launch()
        waitTag("bill-editor")
        // Back leaves it for the app as usual.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("floating-navigation-overlay")
        rule.onNodeWithTag("bill-editor").assertDoesNotExist()
        // Coming back with saved state (rotation, the system restoring it) opens nothing again.
        scenario!!.recreate()
        waitTag("floating-navigation-overlay")
        rule.waitForIdle()
        rule.onNodeWithTag("bill-editor").assertDoesNotExist()
    }

    @Test fun the_ai_chat_opens_when_it_can_be_used_otherwise_the_bill_page() {
        quick.setLaunch(true)
        quick.setTarget(QuickLaunchTarget.AI)
        launch()
        waitTag("bill-editor")
        // The setting's own summary already says it falls back, so starting up does not ask.
        rule.waitForIdle()
        rule.onNodeWithTag("ai-shortcut-notice").assertDoesNotExist()
        enableAi()
        launch()
        waitTag("ai-chat")
    }

    @Test fun nothing_opens_with_the_switch_off_or_from_elsewhere() {
        launch()
        waitTag("floating-navigation-overlay")
        rule.waitForIdle()
        rule.onNodeWithTag("bill-editor").assertDoesNotExist()
        quick.setLaunch(true)
        launch(fromIcon = false)
        waitTag("floating-navigation-overlay")
        rule.waitForIdle()
        rule.onNodeWithTag("bill-editor").assertDoesNotExist()
    }

    @Test fun shortcuts_open_their_page_on_a_fresh_start_and_above_the_current_page() {
        launch()
        waitTag("floating-navigation-overlay")
        // Without AI only "New bill" is offered.
        waitUntil { shortcutIds() == listOf(QuickShortcuts.NEW_BILL) }
        val newBill = shortcut(QuickShortcuts.NEW_BILL)
        assertEquals("记一笔", shortcuts.dynamicShortcuts.single().shortLabel.toString())
        // A fresh start from it, with the switch off.
        launch(intent = newBill)
        waitTag("bill-editor")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("floating-navigation-overlay")
        // While the app runs, the page opens above where it was, and back returns there.
        rule.onNodeWithTag("nav-settings").performClick()
        waitTag("settings-list")
        use(newBill)
        waitTag("bill-editor")
        // Used again, it does not stack a second bill page.
        use(newBill)
        rule.waitForIdle()
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("settings-list")
        rule.onNodeWithTag("bill-editor").assertDoesNotExist()
    }

    @Test fun the_ai_shortcut_is_offered_only_while_the_chat_can_be_used() {
        launch()
        waitTag("floating-navigation-overlay")
        waitUntil { shortcutIds() == listOf(QuickShortcuts.NEW_BILL) }
        enableAi()
        waitUntil { shortcutIds() == listOf(QuickShortcuts.NEW_BILL, QuickShortcuts.AI_CHAT) }
        val chat = shortcut(QuickShortcuts.AI_CHAT)
        launch(intent = chat)
        waitTag("ai-chat")
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("floating-navigation-overlay")
        use(chat)
        waitTag("ai-chat")
        runBlocking { graph.ai!!.setEnabled(false) }
        waitUntil { shortcutIds() == listOf(QuickShortcuts.NEW_BILL) }
        // A copy pinned to the home screen stays: it opens the bill page and says why.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitTag("floating-navigation-overlay")
        use(chat)
        waitTag("bill-editor")
        waitTag("ai-shortcut-notice")
        rule.onNodeWithTag("ai-chat").assertDoesNotExist()
        rule.onNodeWithTag("ai-shortcut-notice-ok").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ai-shortcut-notice").fetchSemanticsNodes().isEmpty() }
        rule.onNodeWithTag("bill-editor").assertExists()
    }

    @Test fun demo_data_stays_where_it_is() {
        launch()
        waitTag("floating-navigation-overlay")
        val newBill = shortcut(QuickShortcuts.NEW_BILL)
        runBlocking { sessions.enterDemo() }
        rule.waitForIdle()
        waitTag("floating-navigation-overlay")
        use(newBill)
        rule.waitForIdle()
        rule.onNodeWithTag("bill-editor").assertDoesNotExist()
        // The menu stays as real data set it.
        assertEquals(listOf(QuickShortcuts.NEW_BILL), shortcutIds())
    }

    @Test fun the_settings_page_sets_the_start_and_the_share_entry() {
        launch()
        waitTag("nav-settings")
        rule.onNodeWithTag("nav-settings").performClick()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-quick-entry"))
        rule.onNodeWithTag("settings-quick-entry").assertTextContains("打开时不跳转 · 分享记账已开启").performClick()
        waitTag("quick-entry")
        rule.onNodeWithTag("quick-target-manual").assertDoesNotExist()
        rule.onNodeWithText("从桌面图标打开、且 App 不在后台时，直接进入记账页面").assertExists()
        rule.onNodeWithTag("quick-launch-shortcuts").assertTextEquals("也可以长按桌面图标，选择“记一笔”或“AI 记账”。")
        rule.onNodeWithTag("quick-launch").assertIsOff().performClick()
        rule.waitUntil(5_000) { quick.settings.value.launch }
        // The AI chat cannot be chosen while AI is off.
        rule.onNodeWithTag("quick-target-manual").assertIsSelected()
        rule.onNodeWithTag("quick-target-ai").assertIsNotEnabled()
        rule.onNodeWithText("需先开启 AI 记账并通过检测；不可用时进入手动记账").assertExists()
        // The share entry leaves the system share sheet and comes back.
        assertFalse(shareTargets().isEmpty())
        rule.onNodeWithTag("quick-share").assertIsOn().performClick()
        rule.waitUntil(5_000) { !quick.settings.value.share }
        assertTrue(shareTargets().isEmpty())
        rule.onNodeWithTag("quick-share").assertIsOff().performClick()
        rule.waitUntil(5_000) { quick.settings.value.share }
        assertFalse(shareTargets().isEmpty())
        rule.onNodeWithTag("quick-share-note").assertExists()
        // The summary in Settings follows.
        scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("settings-quick-entry"))
        rule.onNodeWithTag("settings-quick-entry").assertTextContains("打开时进入手动记账 · 分享记账已开启")
    }
}

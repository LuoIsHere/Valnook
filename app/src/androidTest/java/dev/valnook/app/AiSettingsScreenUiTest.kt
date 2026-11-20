package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.ai.*
import dev.valnook.domain.model.BillCategory
import dev.valnook.domain.model.BillCategoryKind
import dev.valnook.feature.ai.AiAvatars
import dev.valnook.feature.ai.AiSettingsScreen
import dev.valnook.feature.ai.AiSettingsViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Settings → AI against a scripted service: nothing here reaches the network. */
class AiSettingsScreenUiTest {
    @get:Rule val rule = createComposeRule()

    /** A connection already filled in, as after the user entered one; nothing is filled in for a new user. */
    private val settings = MutableStateFlow(AiSettings(connection = AiConnection(null, "https://api.example.test/v1", "synthetic-small")))
    private val keySaved = MutableStateFlow(false)
    private val report = MutableStateFlow<AiCheckReport?>(null)
    private val savedKeys = mutableListOf<String?>()
    private var checks = 0
    private var dirty = false
    private var prompts = 0
    private var guides = 0
    /** The quick start's request to enter its example, as the root passes it. */
    private val fill = androidx.compose.runtime.mutableStateOf(false)

    private val service = object : AiService {
        override fun observeSettings() = settings
        override fun observeChecks() = combine(settings, report) { s, r -> r?.takeIf { it.fingerprint == s.connection.fingerprint } }
        override fun observeKeySaved() = keySaved
        override fun observeChoices() = MutableStateFlow(AiChoices(
            listOf(AiAccountChoice(4, "Synthetic Bank · Savings", "CNY", true)),
            listOf(category(1, "other", "其他"), category(2, null, "合成餐饮"),
                category(3, "income.other", "其他", BillCategoryKind.INCOME))))
        override suspend fun saveConnection(connection: AiConnection, apiKey: String?) {
            savedKeys += apiKey
            if (apiKey != null) keySaved.value = apiKey.isNotEmpty()
            settings.value = settings.value.copy(connection = connection)
        }
        override suspend fun saveDefaults(timeMinutes: Int, cashAccountId: Long?, expenseCategoryId: Long?, incomeCategoryId: Long?) {
            settings.value = settings.value.copy(defaultTimeMinutes = timeMinutes, defaultCashAccountId = cashAccountId,
                defaultExpenseCategoryId = expenseCategoryId, defaultIncomeCategoryId = incomeCategoryId)
        }
        override suspend fun savePersonal(myRules: String, userNickname: String, aiNickname: String) {
            settings.value = settings.value.copy(myRules = myRules, userNickname = userNickname, aiNickname = aiNickname)
        }
        override suspend fun fetchModels(connection: AiConnection, apiKey: String?) = listOf("synthetic-small", "synthetic-large")
        override suspend fun check(progress: (AiCheckItem) -> Unit): AiCheckReport {
            checks++
            AiCheckItem.entries.forEach(progress)
            return AiCheckReport(settings.value.connection.fingerprint, 0, mapOf(
                AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED), AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED),
                AiCheckItem.IMAGE to AiCheck(AiCheckState.FAILED, AiProblem.WRONG_ANSWER),
                AiCheckItem.THINKING to AiCheck(AiCheckState.UNKNOWN, AiProblem.TIMEOUT))).also { report.value = it }
        }
        override suspend fun promptPreview() = ""
        override fun observeFeatures() = combine(settings, observeChecks()) { s, r -> AiFeatures.of(s, r) }
        override suspend fun setEnabled(enabled: Boolean) { settings.value = settings.value.copy(enabled = enabled) }
        override suspend fun saveFeatures(billChat: Boolean, shareText: Boolean, shareImage: Boolean, thinking: Boolean,
            chat: Boolean, sendHabits: Boolean) {
            settings.value = settings.value.copy(billChat = billChat, shareText = shareText, shareImage = shareImage,
                thinking = thinking, chat = chat, sendHabits = sendHabits)
        }
        override suspend fun saveKeepImages(keep: Boolean) { settings.value = settings.value.copy(defaultKeepImages = keep) }
        override suspend fun saveQuery(query: Boolean) { settings.value = settings.value.copy(query = query) }
    }

    private fun category(id: Long, key: String?, name: String, kind: BillCategoryKind = BillCategoryKind.EXPENSE) =
        BillCategory(id, kind, null, key, name, "circle", "grey", id, false, null, 1)

    private fun show() {
        dev.valnook.feature.ai.AiNotices.clear(InstrumentationRegistry.getInstrumentation().targetContext)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ai-avatars-test").apply { deleteRecursively() }
        val vm = AiSettingsViewModel(service, AiAvatars(directory))
        rule.setContent { ValnookTheme(dark_theme = false) { AiSettingsScreen(vm, onPrompt = { prompts++ }, onDirtyChange = { dirty = it },
            onGuide = { guides++ }, fillExample = fill.value, onFillHandled = { fill.value = false }) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ai-settings").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun a_new_setup_has_one_empty_connection_and_no_vendor() {
        settings.value = AiSettings()
        show()
        rule.onNodeWithTag("ai-preset-custom").assertDoesNotExist()
        rule.onNodeWithText("DeepSeek", substring = true).assertDoesNotExist()
        // The hint shows once the empty field is in use (the label holds the place before).
        rule.onNodeWithTag("ai-base-url").performClick()
        rule.onNodeWithText("例如 https://api.example.com/v1", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("ai-check").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("ai-api-key-remove").assertDoesNotExist()
    }

    @Test fun an_empty_connection_points_to_the_quick_start_and_takes_its_example_unsaved() {
        settings.value = AiSettings()
        show()
        rule.onNodeWithTag("ai-guide-hint").assertTextEquals("不清楚如何填写？查看快速入门").performClick()
        assertEquals(1, guides)
        fill.value = true
        rule.waitUntil(5_000) { !fill.value }
        // Nothing typed yet, so nothing to ask about.
        rule.onNodeWithTag("ai-guide-replace").assertDoesNotExist()
        rule.onNodeWithTag("ai-base-url").assertTextContains("https://api.deepseek.com")
        rule.onNodeWithTag("ai-model").assertTextContains("deepseek-flash")
        rule.onNodeWithTag("ai-api-key").assertTextContains("已填入示例参数。请填写 API Key，然后点击“保存并检测”。")
        rule.onNodeWithTag("ai-guide-hint").assertDoesNotExist()
        rule.onNodeWithTag("ai-advanced").performClick()
        rule.onNodeWithTag("ai-thinking-off-params").assertTextContains("""{"thinking": {"type": "disabled"}}""")
        rule.onNodeWithTag("ai-thinking-on-params").assertTextContains("""{"thinking": {"type": "enabled"}}""")
        // Entered, not saved: the user adds the key and saves.
        assertTrue(savedKeys.isEmpty())
        assertEquals("", settings.value.connection.baseUrl)
        rule.onNodeWithTag("ai-api-key").performTextInput("synthetic-test-key")
        rule.onNodeWithTag("ai-connection-save").performScrollTo().performClick()
        rule.waitUntil(5_000) { savedKeys.isNotEmpty() }
        assertEquals(AiConnection(null, "https://api.deepseek.com", "deepseek-flash", "",
            """{"thinking": {"type": "disabled"}}""", """{"thinking": {"type": "enabled"}}"""), settings.value.connection)
        rule.waitUntil(5_000) { !dirty }
        rule.onNodeWithText("已填入示例参数", substring = true).assertDoesNotExist()
    }

    @Test fun the_example_asks_before_replacing_a_connection_and_leaves_the_key() {
        keySaved.value = true
        show()
        rule.onNodeWithTag("ai-guide-hint").assertDoesNotExist()
        fill.value = true
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ai-guide-replace").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ai-guide-replace-cancel").performClick()
        rule.onNodeWithTag("ai-base-url").assertTextContains("https://api.example.test/v1")
        fill.value = true
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ai-guide-replace").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("当前填写的接口地址、模型和高级参数将替换为示例参数，API Key 保持不变。").assertExists()
        rule.onNodeWithTag("ai-guide-replace-confirm").performClick()
        rule.onNodeWithTag("ai-base-url").assertTextContains("https://api.deepseek.com")
        rule.onNodeWithTag("ai-model").assertTextContains("deepseek-flash")
        // The saved key may be another service's: the field says so.
        rule.onNodeWithTag("ai-api-key").assertTextContains("已保存的 Key 如不属于该服务", substring = true)
        assertTrue(savedKeys.isEmpty())
        assertEquals("https://api.example.test/v1", settings.value.connection.baseUrl)
    }

    @Test fun a_key_saves_without_staying_in_the_form() {
        show()
        rule.onNodeWithTag("ai-base-url").assertTextContains("https://api.example.test/v1")
        rule.onNodeWithTag("ai-model").assertTextContains("synthetic-small")
        rule.onNodeWithTag("ai-check-state-CONNECT").assertTextEquals("未检测")
        rule.onNodeWithTag("ai-api-key").performTextInput("synthetic-test-key")
        rule.waitUntil(5_000) { dirty }
        rule.onNodeWithTag("ai-connection-save").performClick()
        rule.waitUntil(5_000) { savedKeys.isNotEmpty() }
        assertEquals("synthetic-test-key", savedKeys.single())
        // The typed key is gone from the screen; the field now says one is saved.
        rule.waitUntil(5_000) { !dirty }
        rule.onNodeWithTag("ai-api-key").assertTextContains("已保存", substring = true)
        rule.onNodeWithText("synthetic-test-key", substring = true).assertDoesNotExist()
        rule.onNodeWithTag("ai-connection-save").assertDoesNotExist()
        // With a key saved, deleting it sits beside the model list.
        rule.onNodeWithTag("ai-api-key-remove").assertExists()
        rule.onNodeWithTag("ai-models-fetch").assertExists()
    }

    @Test fun a_wrong_address_or_parameters_cannot_be_saved() {
        show()
        rule.onNodeWithTag("ai-base-url").performTextReplacement("api.example.test")
        rule.onNodeWithText("请填写以 http:// 或 https:// 开头的地址").assertExists()
        rule.onNodeWithTag("ai-connection-save").assertIsNotEnabled()
        rule.onNodeWithTag("ai-base-url").performTextReplacement("http://192.0.2.10:11434/v1/chat/completions")
        rule.onNodeWithText("该地址未加密，请仅在可信网络中使用").assertExists()
        rule.onNodeWithTag("ai-advanced").performScrollTo().performClick()
        rule.onNodeWithTag("ai-extra-params").performScrollTo().performTextReplacement("[1]")
        rule.onNodeWithText("须为一个 JSON 对象或留空").assertExists()
        rule.onNodeWithTag("ai-connection-save").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("ai-extra-params").performTextReplacement("")
        rule.onNodeWithTag("ai-connection-save").performScrollTo().assertIsEnabled().performClick()
        // Saved tidy: the pasted endpoint is cut back to the base, and it is no longer the preset.
        rule.waitUntil(5_000) { settings.value.connection.baseUrl == "http://192.0.2.10:11434/v1" }
        assertNull(settings.value.connection.presetId)
    }

    @Test fun the_model_list_fills_the_model() {
        show()
        rule.onNodeWithTag("ai-models-fetch").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ai-model-option-synthetic-large").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("ai-model-option-synthetic-large").performClick()
        rule.onNodeWithTag("ai-model").assertTextContains("synthetic-large")
    }

    @Test fun checks_save_first_and_show_each_result_with_its_reason() {
        show()
        rule.onNodeWithTag("ai-model").performTextReplacement("synthetic-medium")
        rule.onNodeWithTag("ai-check").performScrollTo().assertTextEquals("保存并检测").performClick()
        rule.waitUntil(5_000) { checks == 1 }
        assertEquals("synthetic-medium", settings.value.connection.model)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("通过").fetchSemanticsNodes().size == 2 }
        rule.onNodeWithTag("ai-check-state-IMAGE").assertTextEquals("不支持")
        rule.onNodeWithText("回答有误").assertExists()
        rule.onNodeWithTag("ai-check-state-THINKING").assertTextEquals("无法判断")
        rule.onNodeWithText("请求超时，请重试").assertExists()
        rule.onNodeWithTag("ai-check").assertTextEquals("重新检测")
        // Changing the connection voids the report.
        rule.onNodeWithTag("ai-model").performScrollTo().performTextReplacement("synthetic-large")
        rule.onNodeWithTag("ai-connection-save").performScrollTo().performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithText("未检测").fetchSemanticsNodes().size == 4 }
    }

    @Test fun defaults_save_at_once_and_personal_details_with_their_button() {
        show()
        rule.onNodeWithTag("input-默认账户").performScrollTo().performClick()
        rule.onNode(hasText("Synthetic Bank · Savings · CNY") and hasAnyAncestor(isPopup())).performClick()
        rule.waitUntil(5_000) { settings.value.defaultCashAccountId == 4L }
        rule.onNodeWithTag("input-默认支出分类").performScrollTo().performClick()
        rule.onNode(hasText("合成餐饮") and hasAnyAncestor(isPopup())).performClick()
        rule.waitUntil(5_000) { settings.value.defaultExpenseCategoryId == 2L }
        // Back to the catch-all is stored as no choice.
        rule.onNodeWithTag("input-默认支出分类").performScrollTo().performClick()
        rule.onNode(hasText("其他") and hasAnyAncestor(isPopup())).performClick()
        rule.waitUntil(5_000) { settings.value.defaultExpenseCategoryId == null }

        rule.onNodeWithTag("ai-my-rules").performScrollTo().performTextInput("合成规则")
        rule.onNodeWithTag("ai-user-nickname").performScrollTo().performTextInput("小合")
        rule.onNodeWithText("4/2000").assertExists()
        rule.onNodeWithTag("ai-personal-save").performScrollTo().performClick()
        rule.waitUntil(5_000) { settings.value.myRules == "合成规则" && settings.value.userNickname == "小合" }
        rule.onNodeWithTag("ai-prompt-entry").performScrollTo().performClick()
        assertEquals(1, prompts)
        rule.onNodeWithTag("ai-disclaimer").performScrollTo().assertExists()
    }

    @Test fun keeping_pictures_is_on_by_default_and_saves_at_once() {
        settings.value = settings.value.copy(enabled = true)
        report.value = AiCheckReport(settings.value.connection.fingerprint, 0, mapOf(
            AiCheckItem.CONNECT to AiCheck(AiCheckState.PASSED), AiCheckItem.TOOLS to AiCheck(AiCheckState.PASSED)))
        show()
        rule.onNodeWithTag("ai-feature-keep-images").performScrollTo().assertIsOn()
        rule.onNodeWithText("图片默认保存到账单").assertExists()
        rule.onNodeWithTag("ai-feature-keep-images").performClick()
        rule.waitUntil(5_000) { !settings.value.defaultKeepImages }
        rule.onNodeWithTag("ai-feature-keep-images").assertIsOff()
    }

    @Test fun the_master_switch_waits_for_the_checks_and_explains_itself_until_told_not_to() {
        show()
        rule.onNodeWithTag("ai-enable").assertIsNotEnabled().assertIsOff()
        rule.onNodeWithText("连通与工具调用检测通过后方可开启").assertExists()
        rule.onNodeWithTag("ai-check").performScrollTo().performClick()
        rule.waitUntil(5_000) { checks == 1 }
        rule.onNodeWithTag("ai-enable").performScrollTo().assertIsEnabled().performClick()
        // It says where the data goes, as the privacy policy does; cancelling leaves it off.
        rule.onNodeWithTag("ai-enable-notice").assertExists()
        rule.onNodeWithTag("ai-enable-notice-text").assertExists()
        rule.onNodeWithTag("ai-enable-notice-cancel").performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ai-enable-notice").fetchSemanticsNodes().isEmpty() }
        assertTrue(!settings.value.enabled)
        rule.onNodeWithTag("ai-enable").performClick()
        rule.onNodeWithTag("ai-enable-notice-ok").performClick()
        rule.waitUntil(5_000) { settings.value.enabled }
        // Every time it is switched on again, until "Don't show again".
        rule.onNodeWithTag("ai-enable").performClick()
        rule.waitUntil(5_000) { !settings.value.enabled }
        rule.onNodeWithTag("ai-enable").performClick()
        rule.onNodeWithTag("ai-enable-notice-skip").performClick()
        rule.onNodeWithTag("ai-enable-notice-ok").performClick()
        rule.waitUntil(5_000) { settings.value.enabled }
        rule.onNodeWithTag("ai-enable").performClick()
        rule.waitUntil(5_000) { !settings.value.enabled }
        rule.onNodeWithTag("ai-enable").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("ai-enable-notice").assertDoesNotExist()
        rule.waitUntil(5_000) { settings.value.enabled }
        // Features follow; those whose check did not pass stay off and say why.
        // Sharing is set in Settings → Quick bookkeeping, not here.
        rule.onNodeWithTag("ai-feature-share-image").assertDoesNotExist()
        rule.onNodeWithTag("ai-feature-share-text").assertDoesNotExist()
        rule.onNodeWithTag("ai-feature-thinking").performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("ai-feature-chat").performScrollTo().assertIsOff().performClick()
        rule.waitUntil(5_000) { settings.value.chat }
        rule.onNodeWithTag("ai-feature-bill-chat").performScrollTo().assertIsOn().performClick()
        rule.waitUntil(5_000) { !settings.value.billChat }
    }
}

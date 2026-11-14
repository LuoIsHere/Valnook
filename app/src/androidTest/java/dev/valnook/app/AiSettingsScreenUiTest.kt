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

    private val settings = MutableStateFlow(AiSettings())
    private val keySaved = MutableStateFlow(false)
    private val report = MutableStateFlow<AiCheckReport?>(null)
    private val savedKeys = mutableListOf<String?>()
    private var checks = 0
    private var dirty = false
    private var prompts = 0

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
    }

    private fun category(id: Long, key: String?, name: String, kind: BillCategoryKind = BillCategoryKind.EXPENSE) =
        BillCategory(id, kind, null, key, name, "circle", "grey", id, false, null, 1)

    private fun show() {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "ai-avatars-test").apply { deleteRecursively() }
        val vm = AiSettingsViewModel(service, AiAvatars(directory))
        rule.setContent { ValnookTheme(dark_theme = false) { AiSettingsScreen(vm, onPrompt = { prompts++ }, onDirtyChange = { dirty = it }) } }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("ai-settings").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun the_preset_is_filled_in_and_a_key_saves_without_staying_in_the_form() {
        show()
        rule.onNodeWithTag("ai-base-url").assertTextContains("https://api.deepseek.com")
        rule.onNodeWithTag("ai-model").assertTextContains("deepseek-flash")
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
    }

    @Test fun a_wrong_address_or_parameters_cannot_be_saved() {
        show()
        rule.onNodeWithTag("ai-base-url").performTextReplacement("api.example.test")
        rule.onNodeWithText("请填写以 http:// 或 https:// 开头的地址").assertExists()
        rule.onNodeWithTag("ai-connection-save").assertIsNotEnabled()
        rule.onNodeWithTag("ai-base-url").performTextReplacement("http://192.0.2.10:11434/v1/chat/completions")
        rule.onNodeWithText("该地址使用未加密连接，仅在可信网络中使用").assertExists()
        rule.onNodeWithTag("ai-advanced").performScrollTo().performClick()
        rule.onNodeWithTag("ai-extra-params").performScrollTo().performTextReplacement("[1]")
        rule.onNodeWithText("需要是一个 JSON 对象，或者留空").assertExists()
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
        rule.onNodeWithTag("ai-model").performTextReplacement("synthetic-small")
        rule.onNodeWithTag("ai-check").performScrollTo().assertTextEquals("保存并检测").performClick()
        rule.waitUntil(5_000) { checks == 1 }
        assertEquals("synthetic-small", settings.value.connection.model)
        rule.waitUntil(5_000) { rule.onAllNodesWithText("通过").fetchSemanticsNodes().size == 2 }
        rule.onNodeWithTag("ai-check-state-IMAGE").assertTextEquals("不支持")
        rule.onNodeWithText("回答不正确").assertExists()
        rule.onNodeWithTag("ai-check-state-THINKING").assertTextEquals("无法判断")
        rule.onNodeWithText("等待超时，可以重试").assertExists()
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
}

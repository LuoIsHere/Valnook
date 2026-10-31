package dev.valnook.app

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.fx.FxRateSync
import dev.valnook.domain.fx.FxSyncError
import dev.valnook.domain.fx.FxSyncNotice
import dev.valnook.domain.fx.FxSyncStatus
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.settings.FxSettingsScreen
import dev.valnook.feature.settings.SettingsViewModel
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The automatic exchange rate switch on the rates page, against a recording sync. */
class FxSettingsUiTest {
    @get:Rule val rule = createComposeRule()
    private val cny = Currency.of("CNY")
    private val usd = Currency.of("USD")
    private val eur = Currency.of("EUR")
    private val models = mutableListOf<SettingsViewModel>()

    private class Repository(initial: AppSettings) : SettingsRepository, SettingsWriter {
        val values = MutableStateFlow(initial)
        override fun observeSettings() = values
        override suspend fun applyChange(change: SettingsChange): AppSettings {
            if (values.value.revision != change.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
            val next = when (change) {
                is SaveFinancialSettings -> values.value.copy(baseCurrency = change.baseCurrency, rates = change.rates)
                else -> values.value
            }.copy(revision = values.value.revision + 1)
            values.value = next
            return next
        }
    }

    private class Sync(initial: FxSyncStatus) : FxRateSync {
        val status = MutableStateFlow(initial)
        val calls = mutableListOf<String>()
        override fun observeStatus() = status
        override fun observeNotice() = flowOf<FxSyncNotice?>(null)
        override suspend fun consumeNotice(id: String) = Unit
        override suspend fun refreshNow() { calls += "refresh" }
        override suspend fun setAutoEnabled(on: Boolean) { calls += "auto:$on"; status.value = status.value.copy(autoEnabled = on) }
        override suspend fun dismissNotice() { calls += "dismiss"; status.value = status.value.copy(noticeDismissed = true) }
    }

    private val settings = AppSettings(cny, listOf(FxRate(usd, cny, BigDecimal("6.66")), FxRate(eur, cny, BigDecimal("7.5"))), 2,
        language = AppLanguage.ZH_HANS)

    private fun show(repository: Repository, sync: Sync) {
        rule.runOnIdle { models += SettingsViewModel(repository, repository, SavedStateHandle(), sync) }
        rule.setContent { ValnookTheme { FxSettingsScreen(models.last()) } }
        rule.waitUntil(5_000) { models.last().state.value.loaded }
    }

    @After fun close() { rule.runOnIdle { models.forEach { it.viewModelScope.cancel() } } }

    private fun rate(index: Int) = rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("fx-rate-$index")))
    /** A locked field offers no text input but still shows its value. */
    private fun lockedRate(index: Int, value: String) {
        rule.onAllNodes(hasSetTextAction() and hasAnyAncestor(hasTestTag("fx-rate-$index"))).assertCountEquals(0)
        rule.onNode(hasAnyAncestor(hasTestTag("fx-rate-$index")) and hasText(value, substring = true), useUnmergedTree = true)
            .assertExists()
    }

    @Test fun automatic_mode_locks_the_rates_and_shows_status_missing_and_dates() {
        val sync = Sync(FxSyncStatus(available = true, autoEnabled = true, noticeDismissed = true,
            lastSuccessAtMs = 1_700_000_000_000, dataDate = LocalDate.of(2026, 10, 30), missingCurrencies = listOf("KWD"),
            sourceDates = mapOf("USD" to LocalDate.of(2026, 10, 30), "EUR" to LocalDate.of(2026, 10, 29),
                "GBP" to LocalDate.of(2026, 10, 30))))
        show(Repository(settings), sync)
        rule.onNodeWithTag("fx-auto-switch").assertIsOn().assertIsEnabled()
        lockedRate(0, "6.66")
        rule.onAllNodesWithTag("fx-add").assertCountEquals(0)
        rule.onNodeWithTag("fx-auto-status").assertTextContains("汇率日期", substring = true)
        rule.onNodeWithTag("fx-missing").assertTextContains("KWD", substring = true)
        rule.onNodeWithTag("fx-note-EUR").assertExists()   // older than the headline data date
        rule.onAllNodesWithTag("fx-note-USD").assertCountEquals(0)
        rule.onNodeWithTag("fx-update-now").performClick()
        rule.waitUntil(5_000) { sync.calls == listOf("refresh") }

        sync.status.value = sync.status.value.copy(lastError = FxSyncError.NETWORK, lastAttemptAtMs = 1_700_000_100_000)
        rule.onNodeWithTag("fx-auto-status").assertTextContains("更新失败", substring = true)
        sync.status.value = sync.status.value.copy(running = true)
        rule.onNodeWithTag("fx-update-now").assertIsNotEnabled()

        // Once today's rates are stored the button is grey and says so; yesterday's success leaves it usable.
        sync.status.value = sync.status.value.copy(running = false, lastError = null, lastSuccessDay = LocalDate.now())
        rule.onNodeWithTag("fx-update-now").assertIsNotEnabled().assertTextEquals("今日已更新")
        sync.status.value = sync.status.value.copy(lastSuccessDay = LocalDate.now().minusDays(1))
        rule.onNodeWithTag("fx-update-now").assertIsEnabled().assertTextEquals("立即更新")
    }

    @Test fun switching_off_keeps_the_last_automatic_values_to_edit() {
        val sync = Sync(FxSyncStatus(available = true, autoEnabled = true, noticeDismissed = true))
        show(Repository(settings), sync)
        rule.onNodeWithTag("fx-auto-switch").performClick()
        rule.waitUntil(5_000) { sync.calls == listOf("auto:false") }
        rate(0).assertIsEnabled().assertTextContains("6.66")
        rule.onNodeWithTag("fx-add").assertExists()
        rule.onNodeWithText("未设置汇率的币种按 1:1 折算").assertExists()
    }

    @Test fun unsaved_edits_then_notice_with_dont_show_again() {
        val sync = Sync(FxSyncStatus(available = true))
        show(Repository(settings), sync)
        rate(0).performTextReplacement("9")
        rule.onNodeWithTag("fx-auto-switch").performClick()
        rule.onNodeWithText("放弃未保存的修改？").assertExists()
        rule.onNodeWithTag("fx-discard-confirm").performClick()
        rule.onNodeWithTag("fx-notice").assertExists()
        rule.onNodeWithText("不构成投资、理财或其他财务建议", substring = true).assertExists()
        rule.onNodeWithTag("fx-notice-skip").performClick()
        rule.onNodeWithTag("fx-notice-accept").performClick()
        rule.waitUntil(5_000) { sync.calls == listOf("dismiss", "auto:true") }
        rule.onNodeWithTag("fx-auto-switch").assertIsOn()
        lockedRate(0, "6.66")
    }

    @Test fun cancelling_the_notice_leaves_the_switch_off() {
        val sync = Sync(FxSyncStatus(available = true))
        show(Repository(settings), sync)
        rule.onNodeWithTag("fx-auto-switch").performClick()
        rule.onNodeWithTag("fx-notice-cancel").performClick()
        rule.onAllNodesWithTag("fx-notice").assertCountEquals(0)
        rule.onNodeWithTag("fx-auto-switch").assertIsOff()
        assertEquals(emptyList<String>(), sync.calls)
        rate(0).assertIsEnabled()
    }

    @Test fun demo_shows_the_switch_disabled() {
        val sync = Sync(FxSyncStatus(available = false))
        show(Repository(settings), sync)
        rule.onNodeWithTag("fx-auto-switch").assertIsOff().assertIsNotEnabled()
        rule.onNodeWithText("演示模式下不联网").assertExists()
        rate(0).assertIsEnabled()
    }
}

package dev.valnook.app.onboarding

import android.content.Context
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomSettings
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.fx.FxRateSync
import dev.valnook.domain.fx.FxSyncNotice
import dev.valnook.domain.fx.FxSyncStatus
import dev.valnook.domain.model.Currency
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Choosing automatic exchange rates during onboarding: notice first, no rate rows, first sync on finishing. */
class OnboardingFxTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences = context.getSharedPreferences("onboarding-fx-${UUID.randomUUID()}", Context.MODE_PRIVATE)
    private val store = OnboardingPreferences(preferences)
    private lateinit var db: ValnookDatabase
    private lateinit var settings: RoomSettings
    private lateinit var vm: OnboardingViewModel

    private class Sync : FxRateSync {
        val status = MutableStateFlow(FxSyncStatus(available = true))
        val calls = mutableListOf<String>()
        override fun observeStatus() = status
        override fun observeNotice() = flowOf<FxSyncNotice?>(null)
        override suspend fun consumeNotice(id: String) = Unit
        override suspend fun refreshNow() = Unit
        override suspend fun setAutoEnabled(on: Boolean) { calls += "auto:$on" }
        override suspend fun dismissNotice() { calls += "dismiss"; status.value = status.value.copy(noticeDismissed = true) }
    }
    private val sync = Sync()

    @Before fun prepare() {
        db = ValnookDatabase.inMemory(context)
        settings = RoomSettings(db, Clock.systemUTC())
        store.save(OnboardingDraft(step = OnboardingStep.FINANCE, accepted = true, introSeen = true))
    }

    @After fun close() {
        rule.runOnIdle { vm.viewModelScope.cancel() }
        db.close()
        preferences.edit().clear().commit()
    }

    @Test fun automatic_rates_skip_the_rate_rows_and_switch_on_after_finishing() {
        rule.runOnIdle { vm = OnboardingViewModel(store, settings, settings, sync) }
        rule.setContent { ValnookTheme { OnboardingFlow(vm) { androidx.compose.material3.Text("Home") } } }
        rule.waitUntil(5_000) { vm.state.value.loaded }
        rule.runOnIdle { vm.base(Currency.of("CNY")) }
        rule.waitUntil(5_000) { vm.state.value.draft.base != null }
        rule.onNodeWithTag("fx-add").assertExists()
        val hint = rule.onNodeWithTag("onboarding-fx-auto-hint")
        hint.assertTextEquals(context.getString(dev.valnook.app.R.string.onboarding_fx_auto_later_hint))

        rule.onNodeWithTag("onboarding-fx-auto").performClick()
        rule.onNodeWithTag("fx-notice").assertExists()
        // Only the text scrolls, so the buttons are on screen however tall the notice is.
        listOf("fx-notice-cancel", "fx-notice-skip", "fx-notice-accept").forEach { rule.onNodeWithTag(it).assertIsDisplayed() }
        rule.onNodeWithTag("fx-notice-cancel").performClick()
        rule.waitUntil(5_000) { !vm.state.value.fxNotice }
        assertTrue(!vm.state.value.draft.autoFx)
        rule.onNodeWithTag("onboarding-fx-auto").assertIsOff()
        hint.assertTextEquals(context.getString(dev.valnook.app.R.string.onboarding_fx_auto_later_hint))

        rule.onNodeWithTag("onboarding-fx-auto").performClick()
        rule.onNodeWithTag("fx-notice-skip").performClick()
        rule.onNodeWithTag("fx-notice-accept").performClick()
        rule.waitUntil(5_000) { vm.state.value.draft.autoFx }
        assertTrue(store.read().autoFx)
        rule.onAllNodesWithTag("fx-add").assertCountEquals(0)
        hint.assertTextEquals(context.getString(dev.valnook.app.R.string.onboarding_fx_auto_hint))
        // Nothing is fetched before setup finishes, so the base picker leaves rates out.
        rule.onNodeWithTag("fx-base").performTouchInput { click(center) }
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("currency-list").fetchSemanticsNodes().isNotEmpty() }
        rule.onAllNodesWithTag("currency-rate-CNY", useUnmergedTree = true).assertCountEquals(0)
        rule.onAllNodesWithTag("currency-rate-USD", useUnmergedTree = true).assertCountEquals(0)
        rule.onNode(hasText("CNY") and hasClickAction() and hasAnyAncestor(hasTestTag("currency-list"))).performClick()
        rule.waitUntil(5_000) { rule.onAllNodesWithTag("currency-list").fetchSemanticsNodes().isEmpty() }

        rule.onNodeWithTag("onboarding-finish").performClick()
        rule.waitUntil(5_000) { vm.state.value.draft.step == OnboardingStep.READY }
        val saved = runBlocking { settings.observeSettings().first() }
        assertEquals("CNY", saved.baseCurrency!!.code)
        assertTrue(saved.rates.isEmpty())
        assertEquals(listOf("dismiss", "auto:true"), sync.calls)
    }
}

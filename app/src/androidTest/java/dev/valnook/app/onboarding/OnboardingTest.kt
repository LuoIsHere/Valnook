package dev.valnook.app.onboarding

import android.content.Context
import android.graphics.Bitmap
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomSettings
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.feature.settings.FxRateDraft
import java.io.File
import java.time.Clock
import java.util.UUID
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Uses its own preference file and an in-memory ledger; never resets the installed app. */
class OnboardingTest {
    @get:Rule val rule = createComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceName = "onboarding-test-${UUID.randomUUID()}"
    private val preferences by lazy { context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE) }
    private val store by lazy { OnboardingPreferences(preferences) }
    private lateinit var db: ValnookDatabase
    private lateinit var settings: RoomSettings
    private val models = mutableListOf<OnboardingViewModel>()
    private lateinit var vm: OnboardingViewModel
    private var failFinancialWrite = false
    private var failReadyMarker = false
    private var language = mutableStateOf("zh-Hans")

    @Before fun prepare() {
        db = ValnookDatabase.inMemory(context)
        settings = RoomSettings(db, Clock.systemUTC())
        store.initialize(false)
    }
    @After fun close() {
        rule.runOnIdle { models.forEach { it.viewModelScope.cancel() } }
        db.close()
        context.deleteSharedPreferences(preferenceName)
    }

    private fun model(): OnboardingViewModel {
        val storage = object : OnboardingStorage {
            override fun read() = store.read()
            override fun save(draft: OnboardingDraft) {
                if (failReadyMarker && draft.step == OnboardingStep.READY) error("synthetic local save failure")
                store.save(draft)
            }
        }
        val writer = object : SettingsWriter {
            override suspend fun applyChange(change: SettingsChange): AppSettings {
                if (failFinancialWrite && change is SaveFinancialSettings) throw DomainException(ErrorCode.STALE_RECORD)
                return settings.applyChange(change)
            }
        }
        return OnboardingViewModel(storage, settings, writer).also { models += it }
    }

    private fun show(dark: Boolean = false, compact: Boolean = false) {
        rule.runOnIdle { vm = model() }
        rule.setContent {
            val configuration = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList.forLanguageTags(language.value)) }
            val localized = context.createConfigurationContext(configuration)
            val density = LocalDensity.current
            CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                LocalDensity provides Density(density.density, if (compact) 1.5f else 1f)) {
                Box(if (compact) Modifier.size(320.dp, 520.dp) else Modifier.fillMaxSize()) {
                    ValnookTheme(dark_theme = dark) {
                        Box(Modifier.fillMaxSize().testTag("onboarding-test-root")) {
                            OnboardingFlow(vm) { Text("Home", Modifier.testTag("onboarding-home")) }
                        }
                    }
                }
            }
        }
        waitFor { vm.state.value.loaded }
    }

    private fun waitFor(condition: () -> Boolean) { rule.waitUntil(8_000, condition) }
    private fun capture(name: String) {
        val output = File(context.getExternalFilesDir(null), "onboarding-test/$name.png")
        output.parentFile!!.mkdirs()
        val image = rule.onNodeWithTag("onboarding-test-root").captureToImage().asAndroidBitmap()
        output.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    private fun goFinance() {
        rule.onNodeWithTag("onboarding-consent").performScrollTo().performClick()
        waitFor { vm.state.value.draft.accepted }
        rule.onNodeWithTag("onboarding-start").performScrollTo().performClick()
        waitFor { vm.state.value.draft.step == OnboardingStep.FINANCE }
    }

    @Test fun consent_currency_optional_rates_success_and_restart() {
        show(dark = true)
        rule.onNodeWithTag("onboarding-start").assertIsNotEnabled()
        capture("welcome-dark")
        // The link is separate from the checkbox: viewing must never imply consent.
        val layouts = mutableListOf<TextLayoutResult>()
        val policy = rule.onNodeWithTag("onboarding-privacy").performScrollTo()
        policy.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val linkBounds = layouts.single().getBoundingBox(layouts.single().layoutInput.text.lastIndex)
        policy.performTouchInput { click(linkBounds.center) }
        rule.waitForIdle()
        rule.onNodeWithTag("privacy-policy-page").assertExists()
        assertFalse(vm.state.value.draft.accepted)
        rule.onNodeWithContentDescription("返回").performClick()
        goFinance()
        rule.onNodeWithTag("onboarding-finish").assertIsNotEnabled()
        rule.onNodeWithTag("fx-base").performTouchInput { click(center) }
        rule.onNode(hasText("CNY", substring = true) and hasClickAction()).performClick()
        waitFor { vm.state.value.draft.base?.code == "CNY" }
        capture("finance-dark")
        rule.onNodeWithTag("onboarding-finish").performClick()
        waitFor { vm.state.value.draft.step == OnboardingStep.READY }
        capture("ready-dark")
        assertEquals(OnboardingStep.READY, store.read().step)
        val financial = runBlocking { settings.observeSettings().first() }
        assertEquals("CNY", financial.baseCurrency!!.code)
        assertTrue(financial.rates.isEmpty())
        rule.onNodeWithTag("onboarding-enter").performClick()
        waitFor { vm.state.value.draft.step == OnboardingStep.COMPLETE }
        rule.onNodeWithTag("onboarding-home").assertExists()
        // Recreating the reader and initialization with an existing DB must preserve completion.
        OnboardingPreferences(preferences).initialize(true)
        assertEquals(OnboardingStep.COMPLETE, OnboardingPreferences(preferences).read().step)
    }

    @Test fun invalid_rates_write_failures_and_marker_retry_do_not_finish_early() {
        show()
        goFinance()
        rule.runOnIdle { vm.base(Currency.of("CNY")) }
        waitFor { vm.state.value.draft.base != null }
        rule.onNodeWithTag("fx-add").performScrollTo().performClick()
        waitFor { vm.state.value.draft.rates.size == 1 }
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("fx-rate-0"))).performScrollTo().performTextReplacement("0")
        waitFor { vm.state.value.draft.rates[0].rateInput == "0" }
        rule.onNodeWithTag("onboarding-finish").performClick()
        waitFor { vm.state.value.error == ErrorCode.POSITIVE }
        assertNull(runBlocking { settings.observeSettings().first() }.baseCurrency)
        rule.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("fx-rate-0"))).performScrollTo().performTextReplacement("7.25")
        waitFor { vm.state.value.draft.rates[0].rateInput == "7.25" }
        failFinancialWrite = true
        rule.onNodeWithTag("onboarding-finish").performClick()
        waitFor { vm.state.value.error != null }
        assertEquals(OnboardingStep.FINANCE, store.read().step)
        assertNull(runBlocking { settings.observeSettings().first() }.baseCurrency)
        failFinancialWrite = false
        failReadyMarker = true
        rule.onNodeWithTag("onboarding-finish").performClick()
        waitFor { !vm.state.value.busy && runBlocking { settings.observeSettings().first() }.baseCurrency != null }
        assertEquals(OnboardingStep.FINANCE, store.read().step)
        val committedRevision = runBlocking { settings.observeSettings().first() }.revision
        failReadyMarker = false
        rule.onNodeWithTag("onboarding-finish").performClick()
        waitFor { vm.state.value.draft.step == OnboardingStep.READY }
        assertEquals(committedRevision, runBlocking { settings.observeSettings().first() }.revision)
    }

    @Test fun small_screen_large_font_language_and_draft_survive_recreation() {
        show(compact = true)
        goFinance()
        rule.runOnIdle { vm.base(Currency.of("USD")) }
        waitFor { vm.state.value.draft.base != null }
        rule.runOnIdle { vm.addRate() }
        waitFor { vm.state.value.draft.rates.isNotEmpty() }
        rule.runOnIdle { vm.updateRate(0, Currency.of("HKD"), "0.128"); vm.back() }
        waitFor { vm.state.value.draft.step == OnboardingStep.WELCOME }
        rule.runOnIdle { vm.language(AppLanguage.ENGLISH) }
        waitFor { vm.state.value.language == AppLanguage.ENGLISH && !vm.state.value.busy }
        rule.runOnIdle { language.value = "en" }
        rule.onNodeWithTag("onboarding-start").performScrollTo().assertIsEnabled()
        capture("welcome-small-english")
        // A new ViewModel reads the same durable draft after process loss.
        lateinit var restored: OnboardingViewModel
        rule.runOnIdle { restored = model() }
        waitFor { restored.state.value.loaded }
        assertTrue(restored.state.value.draft.accepted)
        assertTrue(restored.state.value.draft.introSeen)
        assertEquals("0.128", restored.state.value.draft.rates.single().rateInput)
        assertEquals(AppLanguage.ENGLISH, restored.state.value.language)
        rule.onNodeWithTag("onboarding-start").performClick()
        waitFor { vm.state.value.draft.step == OnboardingStep.FINANCE }
        rule.onNodeWithTag("fx-rate-0").performScrollTo().assertExists()
        capture("finance-small-english")
        rule.onNodeWithTag("onboarding-finish").performClick()
        waitFor { vm.state.value.draft.step == OnboardingStep.READY }
        capture("ready-small-english")
    }

    @Test fun legacy_install_skips_without_claiming_consent() {
        preferences.edit().clear().commit()
        store.initialize(true)
        show()
        rule.onNodeWithTag("onboarding-home").assertExists()
        assertFalse(store.read().accepted)
        assertEquals(OnboardingStep.LEGACY, store.read().step)
        assertNull(runBlocking { settings.observeSettings().first() }.baseCurrency)
    }

    @Test fun logo_rises_once_and_does_not_replay_after_returning() {
        rule.mainClock.autoAdvance = false
        try {
            show()
            rule.mainClock.advanceTimeBy(32)
            val start = rule.onNodeWithTag("onboarding-logo").getUnclippedBoundsInRoot().top
            rule.mainClock.advanceTimeBy(400)
            val middle = rule.onNodeWithTag("onboarding-logo").getUnclippedBoundsInRoot().top
            rule.mainClock.advanceTimeBy(700)
            val end = rule.onNodeWithTag("onboarding-logo").getUnclippedBoundsInRoot().top
            assertTrue("Logo should rise smoothly through the welcome animation", start > middle && middle > end)
            rule.mainClock.autoAdvance = true
            goFinance()
            rule.runOnIdle { vm.back() }
            waitFor { vm.state.value.draft.step == OnboardingStep.WELCOME }
            rule.waitForIdle()
            assertEquals(end, rule.onNodeWithTag("onboarding-logo").getUnclippedBoundsInRoot().top)
        } finally { rule.mainClock.autoAdvance = true }
    }

    @Test fun changing_main_currency_requires_confirmation_before_clearing_rates() {
        store.save(OnboardingDraft(step = OnboardingStep.FINANCE, accepted = true, introSeen = true,
            base = Currency.of("CNY"), rates = listOf(FxRateDraft(Currency.of("USD"), "7.2"))))
        show()
        fun chooseUsd() {
            rule.onNodeWithTag("fx-base").performTouchInput { click(center) }
            rule.onNode(hasText("USD", substring = true) and hasClickAction() and
                hasAnyAncestor(hasTestTag("currency-list"))).performClick()
        }
        chooseUsd()
        rule.onNodeWithText("取消").performClick()
        assertEquals("CNY", vm.state.value.draft.base!!.code)
        assertEquals("7.2", vm.state.value.draft.rates.single().rateInput)
        chooseUsd()
        rule.onNodeWithText("更换币种").performClick()
        waitFor { vm.state.value.draft.base?.code == "USD" }
        assertTrue(vm.state.value.draft.rates.isEmpty())
        rule.onNodeWithTag("onboarding-finish").performClick()
        waitFor { vm.state.value.draft.step == OnboardingStep.READY }
        lateinit var restored: OnboardingViewModel
        rule.runOnIdle { restored = model() }
        waitFor { restored.state.value.loaded }
        assertEquals(OnboardingStep.READY, restored.state.value.draft.step)
    }

    @Test fun successful_clear_reopens_setup_but_cancel_and_failure_preserve_completion() {
        val clock = Clock.systemUTC()
        val graph = dev.valnook.app.di.createDatabaseGraph(context, db, clock, dev.valnook.app.di.currentBuildInfo(), persistentPrivateCards = false)
        var failClear = false
        val maintenance = object : DataMaintenance {
            override suspend fun clearBusinessData() {
                if (failClear) error("synthetic maintenance failure")
                graph.maintenance.clearBusinessData()
            }
        }
        val manager = dev.valnook.app.di.AppSessionManager(context, graph.copy(maintenance = maintenance), db, clock,
            onboarding = store)
        store.save(OnboardingDraft(step = OnboardingStep.COMPLETE, accepted = true, introSeen = true))
        runBlocking { settings.applyChange(SaveFinancialSettings(0, Currency.of("CNY"), emptyList())) }
        rule.setContent {
            val active by manager.session.collectAsState()
            key(active.id) {
                ValnookTheme {
                    OnboardingGate(active.graph.settings, active.graph.settingsWriter, store) {
                        Text("Home", Modifier.testTag("onboarding-home"))
                    }
                }
            }
        }
        waitFor { rule.onAllNodesWithTag("onboarding-home").fetchSemanticsNodes().isNotEmpty() }
        runBlocking {
            val cancelled = manager.issueClearChallenge()
            manager.cancelClearChallenge(cancelled)
            assertTrue(runCatching { manager.clearRealData(cancelled, cancelled) }.isFailure)
        }
        assertEquals(OnboardingStep.COMPLETE, store.read().step)
        failClear = true
        runBlocking {
            val challenge = manager.issueClearChallenge()
            assertTrue(runCatching { manager.clearRealData(challenge, challenge) }.isFailure)
        }
        assertEquals(OnboardingStep.COMPLETE, store.read().step)
        assertEquals("CNY", runBlocking { settings.observeSettings().first() }.baseCurrency!!.code)
        failClear = false
        runBlocking {
            val challenge = manager.issueClearChallenge()
            manager.clearRealData(challenge, challenge)
        }
        waitFor { rule.onAllNodesWithTag("onboarding-start").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("onboarding-start").assertIsNotEnabled()
        assertEquals(OnboardingStep.WELCOME, store.read().step)
        assertFalse(store.read().accepted)
        assertNull(runBlocking { settings.observeSettings().first() }.baseCurrency)
        OnboardingPreferences(preferences).initialize(true)
        assertEquals(OnboardingStep.WELCOME, OnboardingPreferences(preferences).read().step)
    }
}

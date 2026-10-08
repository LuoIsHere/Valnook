package dev.valnook.app.onboarding

import android.app.Activity
import android.app.Application
import android.app.LocaleManager
import android.os.Bundle
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.MainActivity
import dev.valnook.app.di.AppSessionManager
import dev.valnook.domain.model.AppLanguage
import kotlinx.coroutines.cancel
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/** Real LocaleManager/recreation path, backed by TestModule's in-memory database. */
@HiltAndroidTest
class OnboardingLanguageTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val locales = context.getSystemService(LocaleManager::class.java)
    private val originalLocales = locales.applicationLocales
    private val name = "onboarding-language-test-${UUID.randomUUID()}"
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Inject lateinit var sessions: AppSessionManager
    private lateinit var model: OnboardingViewModel
    private val creations = AtomicInteger()
    private val guard = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
            if (activity is MainActivity && creations.incrementAndGet() > 4) activity.finish()
        }
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
    @Before fun prepare() {
        hilt.inject()
        (context.applicationContext as Application).registerActivityLifecycleCallbacks(guard)
        val store = OnboardingPreferences(context.getSharedPreferences(name, 0))
        store.initialize(false)
        store.save(OnboardingDraft(accepted = true, introSeen = true))
        rule.runOnIdle {
            val graph = sessions.session.value.graph
            model = OnboardingViewModel(store, graph.settings, graph.settingsWriter)
        }
        rule.waitUntil(8_000) { model.state.value.loaded }
    }
    @After fun cleanup() {
        (context.applicationContext as Application).unregisterActivityLifecycleCallbacks(guard)
        if (::model.isInitialized) model.viewModelScope.cancel()
        // Close before restoring the real user's locale, so the synthetic DB cannot overwrite it.
        rule.activityRule.scenario.close()
        locales.applicationLocales = originalLocales
        context.deleteSharedPreferences(name)
    }
    @Test fun language_switch_preserves_draft_and_does_not_restart_repeatedly() {
        for ((language, tags) in listOf(AppLanguage.ENGLISH to "en", AppLanguage.ZH_HANS to "zh-Hans",
            AppLanguage.SYSTEM to "")) {
            creations.set(0)
            rule.runOnIdle { model.language(language) }
            rule.waitUntil(10_000) {
                creations.get() > 4 || (!model.state.value.busy && model.state.value.language == language &&
                    locales.applicationLocales.toLanguageTags() == tags)
            }
            // LocaleManager accepts the request before Android dispatches the configuration change.
            val resolved = if (language == AppLanguage.SYSTEM)
                android.content.res.Resources.getSystem().configuration.locales[0].language else tags.substringBefore('-')
            rule.waitUntil(8_000) { rule.activity.resources.configuration.locales[0].language == resolved }
            Thread.sleep(1_000)
            assertTrue("Repeated Activity recreation; guard stopped the test Activity", creations.get() <= 1)
            val count = creations.get()
            Thread.sleep(800)
            rule.waitForIdle()
            assertEquals("Language must stabilize after one configuration change", count, creations.get())
            assertTrue(model.state.value.draft.accepted)
            assertTrue(model.state.value.draft.introSeen)
            assertEquals(OnboardingStep.WELCOME, model.state.value.draft.step)
        }
    }
}

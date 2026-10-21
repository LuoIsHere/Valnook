package dev.valnook.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.repository.SaveLanguage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * Walks the bundled demo in both languages and both themes: every root page, an account, a ledger with notes and a
 * wallet card. Checks that each demo text appears in the chosen language and saves a screenshot of every stop.
 */
@HiltAndroidTest
class DemoWalkthroughUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun inject() = hilt.inject()

    private data class Words(val group: String, val bank: String, val salaryCard: String, val salary: String,
        val card: String, val instrument: String)

    private val chinese = Words("银行卡", "招商银行", "招行储蓄卡", "工资", "招行储蓄卡", "沪深300ETF")
    private val english = Words("Bank cards", "China Merchants Bank", "CMB debit card", "Salary", "CMB debit card",
        "CSI 300 ETF")

    @Test fun demo_reads_in_chinese_light() = walk(english = false, dark = false)
    @Test fun demo_reads_in_chinese_dark() = walk(english = false, dark = true)
    @Test fun demo_reads_in_english_light() = walk(english = true, dark = false)
    @Test fun demo_reads_in_english_dark() = walk(english = true, dark = true)

    private fun walk(english: Boolean, dark: Boolean) {
        val words = if (english) this.english else chinese
        val name = (if (english) "en" else "zh") + "-" + (if (dark) "dark" else "light")
        val activity = rule.activity
        val sessions = activity.sessions
        // The demo follows the real session's language, as when switching demo on in Settings.
        runBlocking {
            val real = sessions.session.value.graph
            val settings = real.settings.observeSettings().first()
            real.settingsWriter.applyChange(SaveLanguage(settings.revision,
                if (english) AppLanguage.ENGLISH else AppLanguage.ZH_HANS))
            sessions.enterDemo()
        }
        try {
            val config = Configuration(activity.resources.configuration).apply {
                setLocales(LocaleList(if (english) Locale.US else Locale.SIMPLIFIED_CHINESE))
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            val context = activity.createConfigurationContext(config)
            rule.runOnUiThread { activity.setContent {
                CompositionLocalProvider(LocalConfiguration provides config, LocalContext provides context) {
                    ValnookRoot(activity.sessions, activity.webAdmin)
                }
            } }
            val graph = sessions.session.value.graph
            val snapshot = runBlocking { graph.overview.snapshot() }
            val bank = snapshot.accounts.single { it.name == words.bank }
            val salary = snapshot.cash.single { it.name == words.salaryCard }

            waitTag("accounts-list")
            waitText(words.group)
            capture("$name-accounts")

            // Expanding an account lists its balance accounts; a balance account opens its ledger.
            rule.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("account-toggle-${bank.id}"))
            if (rule.onAllNodesWithTag("account-cash-${salary.id}").fetchSemanticsNodes().isEmpty())
                rule.onNodeWithTag("account-toggle-${bank.id}").performClick()
            waitTag("account-cash-${salary.id}")
            capture("$name-accounts-expanded")
            rule.onNodeWithTag("account-cash-${salary.id}").performScrollTo().performClick()
            waitText(words.salary)
            capture("$name-ledger")
            back(context)
            waitTag("accounts-list")
            rule.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("account-total-${bank.id}"))
            rule.onNodeWithTag("account-total-${bank.id}").performClick()
            waitTag("account-detail-tabs")
            capture("$name-account")
            back(context)
            waitTag("accounts-list")

            rule.onNodeWithTag("nav-wallet").performClick()
            waitTag("wallet-stack")
            capture("$name-wallet")
            val cards = runBlocking { requireNotNull(graph.wallet).observeCards().first() }
            val first = cards.first()
            check(first.name == words.card) { "Card name ${first.name}" }
            // The first card is the front of the stack, at the bottom of the list.
            rule.onNodeWithTag("wallet-stack").performScrollToIndex(cards.lastIndex)
            rule.waitUntil(15_000) {
                rule.onAllNodesWithTag("wallet-card-${first.id}", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("wallet-card-${first.id}", useUnmergedTree = true).performClick()
            waitTag("wallet-detail")
            rule.mainClock.advanceTimeBy(1_000)
            capture("$name-wallet-detail")
            back(context)
            waitTag("wallet-stack")

            rule.onNodeWithTag("nav-investments").performClick()
            waitTag("investment-home")
            capture("$name-investments")

            rule.onNodeWithTag("nav-statistics").performClick()
            rule.waitForIdle()
            rule.mainClock.advanceTimeBy(1_000)
            capture("$name-statistics")

            rule.onNodeWithTag("nav-settings").performClick()
            rule.waitForIdle()
            capture("$name-settings")

            check(snapshot.instruments.any { it.name == words.instrument }) { "Instrument names not localised" }
        } finally {
            runBlocking { sessions.exitDemo() }
        }
    }

    private fun back(context: android.content.Context) {
        rule.onNodeWithContentDescription(context.getString(dev.valnook.app.R.string.nav_back)).performClick()
        rule.waitForIdle()
    }

    private fun waitTag(tag: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun waitText(text: String) {
        rule.waitUntil(15_000) { rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("demo-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
}

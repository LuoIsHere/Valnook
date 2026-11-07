package dev.valnook.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.LocalPageTopBarHeight
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.feature.cash.CashDetail
import dev.valnook.feature.cash.CashViewModel
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** The sub-account page's header: what it says above the balance, its edit button, and how it collapses. */
@HiltAndroidTest
class CashDetailUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private val clock = Clock.fixed(Instant.parse("2026-10-11T12:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private val graph get() = rule.activity.sessions.session.value.graph
    private var edits = 0
    private val bar = 56.dp
    private val width = 360.dp

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }
    @After fun close() { rule.runOnUiThread { store.clear() } }

    private fun show(credit: Boolean, dark: Boolean = false, fontScale: Float = 1f) {
        val change = if (credit) CashBalanceChange("CNY", -775_449, null, name = "Visa card", type = BalanceAccountType.CREDIT,
            credit = CreditAccountInput(5_000_000, 7, CreditDueRule.AfterStatementDays(20), null))
            else CashBalanceChange("CNY", 22_558_498, null, name = "Everyday savings")
        val parent = runBlocking { graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
            "Example bank", "", listOf(change))).id }
        val cash = runBlocking { graph.cash.observe_cash(parent).first().single() }
        rule.runOnUiThread {
            val vm = CashViewModel(parent, graph.cash, graph.cashPages, SavedStateHandle(), clock = clock)
            store.put("cash", vm)
            rule.activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
                val context = LocalContext.current.createConfigurationContext(config)
                val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides context, LocalConfiguration provides config,
                    LocalDensity provides Density(density.density, fontScale), LocalPageTopBarHeight provides bar) {
                    ValnookTheme(dark_theme = dark) { Surface(Modifier.fillMaxSize()) { Box {
                        // Room above the page stands in for the title bar, so the balance's final place stays on screen.
                        Box(Modifier.padding(top = bar).width(width).height(640.dp).testTag("cash-detail-shot")) {
                            CashDetail(vm, cash.id, { edits++ }, {})
                        }
                    } } }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("cash-trend-value").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("cash-detail-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun px(value: androidx.compose.ui.unit.Dp) = with(rule.density) { value.toPx() }
    private fun label() = rule.onNodeWithTag("cash-trend-date")
    private fun editButtons() = rule.onAllNodesWithContentDescription("编辑余额")

    private fun collapse() {
        val height = bounds("cash-detail-header").height
        rule.onNodeWithTag("cash-detail-header").performTouchInput {
            down(center)
            // Slow enough that releasing counts as stopping, not as a fling.
            repeat(10) { moveBy(Offset(0f, -height * .09f), delayMillis = 60) }
            advanceEventTime(500)
            moveBy(Offset.Zero)
            up()
        }
        rule.waitForIdle()
    }

    @Test fun credit_header_shows_the_debt_and_bill_then_collapses_into_the_bar() {
        show(credit = true)
        label().assertTextContains("欠款", substring = true)
        rule.onNodeWithTag("cash-billing-tag", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("cash-trend-value").assertTextEquals("-7 754.49 CNY")
        save("credit-open")

        editButtons().assertCountEquals(1)
        editButtons()[0].performClick()
        rule.runOnIdle { assertEquals(1, edits) }

        val open = bounds("cash-trend-value")
        collapse()
        val closed = bounds("cash-trend-value")
        assertTrue("Smaller: ${open.width} -> ${closed.width}", closed.width < open.width * .7f)
        assertEquals("Centered in the title bar", px(bar) / 2f, closed.center.y, 3f)
        assertEquals("Ends before the delete button", px(width) - px(60.dp), closed.right, 3f)
        // Folded away with the chart: nothing to reach until the header opens again.
        editButtons().assertCountEquals(0)
        save("credit-closed")
    }

    @Test fun savings_header_says_current_balance_and_a_touch_shows_the_day() {
        show(credit = false)
        label().assertTextEquals("当前余额")
        rule.onAllNodesWithTag("cash-billing-tag", useUnmergedTree = true).assertCountEquals(0)
        val chart = rule.onNodeWithTag("cash-trend-chart")
        chart.performTouchInput { down(Offset(width * .3f, height * .5f)) }
        rule.waitForIdle()
        assertTrue("A date replaces the label",
            rule.onNodeWithTag("cash-trend-date").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                .joinToString().contains("年"))
        chart.performTouchInput { up() }
        rule.waitForIdle()
        label().assertTextEquals("当前余额")
        save("savings-light")
    }

    @Test fun dark_and_large_font_shots() {
        show(credit = true, dark = true, fontScale = 1.3f)
        rule.onNodeWithTag("cash-trend-value").assertIsDisplayed()
        editButtons().assertCountEquals(1)
        save("credit-dark-large-font")
    }
}

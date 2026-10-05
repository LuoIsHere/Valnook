package dev.valnook.app

import android.graphics.Bitmap
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.*
import dev.valnook.app.navigation.*
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.model.AppSettings
import dev.valnook.app.di.DemoDataSeeder
import kotlinx.coroutines.runBlocking
import androidx.navigation3.runtime.NavKey
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@HiltAndroidTest
class NeutralMotionUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject() }

    private fun scene(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent(content = content) }
        rule.waitForIdle()
    }
    private fun bounds(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    private fun aligned(indicator: Rect, item: Rect) {
        assertEquals(item.center.x, indicator.center.x, 1.5f)
        assertEquals(item.center.y, indicator.center.y, 1.5f)
        assertTrue(indicator.left >= item.left - 1 && indicator.right <= item.right + 1)
    }
    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    @Test fun navigation_indicator_moves_and_retargets_without_moving_hit_areas() {
        val selected = mutableStateOf<NavKey>(AccountsKey)
        val items = mutableStateOf(NavigationItemId.entries.toList())
        scene { ValnookTheme(dark_theme = true) {
            FloatingNavigationBar(selected.value, items.value, { selected.value = it }, Modifier.fillMaxWidth())
        } }
        val first = bounds("nav-accounts")
        val last = bounds("nav-settings")
        aligned(bounds("capsule-indicator"), first)
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag("nav-settings").performClick()
        rule.mainClock.advanceTimeBy(80)
        val middle = bounds("capsule-indicator")
        assertTrue("Must show an intermediate frame, not jump", middle.left > first.left && middle.left < last.left)
        assertEquals(first, bounds("nav-accounts"))
        rule.onNodeWithTag("nav-settings").assertIsSelected()
        save("neutral-navigation-midflight")
        rule.onNodeWithTag("nav-investments").performClick()
        rule.mainClock.advanceTimeBy(32)
        val redirected = bounds("capsule-indicator")
        assertTrue("Retarget from current position, not the old endpoint", redirected.left < last.left)
        rule.mainClock.advanceTimeBy(300)
        aligned(bounds("capsule-indicator"), bounds("nav-investments"))
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { items.value = listOf(NavigationItemId.SETTINGS, NavigationItemId.INVESTMENTS) }
        aligned(bounds("capsule-indicator"), bounds("nav-investments"))
        rule.onNodeWithTag("nav-accounts").assertDoesNotExist()
        save("neutral-navigation-reordered")
    }

    @Test fun unequal_segments_follow_rtl_large_text_and_resize() {
        val selected = mutableStateOf("B")
        val direction = mutableStateOf(LayoutDirection.Rtl)
        val width = mutableStateOf(320.dp)
        val options = mutableStateOf(listOf("A", "B", "C"))
        scene {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density, 2f),
                LocalLayoutDirection provides direction.value) {
                ValnookTheme(dark_theme = false) {
                    Box(Modifier.fillMaxSize().padding(top = 80.dp)) {
                        CapsuleChoiceRow(options.value, selected.value, { selected.value = it }, Modifier.width(width.value),
                            optionModifier = { Modifier.testTag("option-$it") },
                            optionWeight = { if (it == "B") 2f else 1f }) { Text(it) }
                    }
                }
            }
        }
        assertTrue(bounds("option-B").width > bounds("option-A").width * 1.9f)
        aligned(bounds("capsule-indicator"), bounds("option-B"))
        rule.onNodeWithTag("option-A").performClick()
        aligned(bounds("capsule-indicator"), bounds("option-A"))
        rule.runOnIdle { width.value = 260.dp; direction.value = LayoutDirection.Ltr; options.value = listOf("C", "A") }
        aligned(bounds("capsule-indicator"), bounds("option-A"))
        rule.onNodeWithTag("option-A").assertIsSelected()
        save("neutral-segments-large-text")
    }

    @Test fun popup_retains_window_during_exit_then_removes_it_and_can_reopen() {
        val visible = mutableStateOf(false)
        scene { ValnookTheme(dark_theme = true) {
            AnimatedGlassDialog(visible.value, { visible.value = false }) {
                GlassCard(Modifier.testTag("animated-popup")) { Text("Currency", Modifier.padding(24.dp)) }
            }
        } }
        fun windowCount(): Int {
            rule.waitForIdle()
            return rule.runOnUiThread { WindowInspector.getGlobalWindowViews().size }
        }
        val initialCount = windowCount()
        rule.runOnIdle { visible.value = true }
        rule.onNodeWithTag("animated-popup").assertIsDisplayed()
        assertTrue(windowCount() > initialCount)
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { visible.value = false }
        rule.mainClock.advanceTimeBy(48)
        assertTrue("Window must survive the exit frames", windowCount() > initialCount)
        rule.mainClock.advanceTimeBy(240)
        rule.waitForIdle()
        assertEquals(initialCount, windowCount())
        rule.mainClock.autoAdvance = true
        rule.runOnIdle { visible.value = true }
        rule.onNodeWithTag("animated-popup").assertIsDisplayed()
        rule.runOnIdle { visible.value = false }
        assertEquals(initialCount, windowCount())
    }

    @Test fun investment_expansion_can_reverse_without_opening_account() {
        val activity = rule.activity
        val graph = activity.sessions.session.value.graph
        runBlocking { DemoDataSeeder(graph, graph.clock).seed(AppSettings()) }
        val position = runBlocking { graph.overview.snapshot() }.positions.first { it.holding_quantity_e8 > 0 }
        scene { ValnookRoot(activity.sessions, activity.webAdmin) }
        rule.onNodeWithTag("nav-investments").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("investment-home").fetchSemanticsNodes().isNotEmpty() }
        val toggle = rule.onNodeWithTag("investment-account-toggle-${position.account_id}")
        rule.onNodeWithTag("investment-home").performScrollToNode(hasTestTag("investment-account-toggle-${position.account_id}"))
        val holding = rule.onNodeWithTag("holding-${position.id}")
        holding.assertDoesNotExist()
        rule.mainClock.autoAdvance = false
        toggle.performClick()
        rule.mainClock.advanceTimeBy(64)
        holding.assertExists()
        toggle.performClick()
        rule.mainClock.advanceTimeBy(250)
        holding.assertDoesNotExist()
        rule.onNodeWithTag("nav-investments").assertIsSelected()
        rule.mainClock.autoAdvance = true
        toggle.performClick()
        holding.assertExists()
        save("neutral-investments-expanded")
    }
}

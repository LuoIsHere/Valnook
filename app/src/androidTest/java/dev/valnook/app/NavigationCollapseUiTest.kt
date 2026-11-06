package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.io.PlatformTestStorageRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.navigation.ValnookRoot
import dev.valnook.feature.wallet.WalletImageCodec
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/**
 * The bottom navigation folds to one tab while a root page scrolls down, and opens again only at the top of the page
 * or when the folded capsule is tapped. A card's details leave it as it was.
 */
@HiltAndroidTest
class NavigationCollapseUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private val graph get() = rule.activity.sessions.session.value.graph

    private fun root() {
        rule.runOnUiThread { rule.activity.setContent { ValnookRoot(rule.activity.sessions, rule.activity.webAdmin) } }
        rule.waitForIdle()
        rule.waitUntil(15_000) { rule.onAllNodesWithTag("nav-settings").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun folded() = rule.onAllNodesWithTag("navigation-expand").fetchSemanticsNodes().isNotEmpty()
    private fun waitFolded(expected: Boolean) = rule.waitUntil(5_000) { folded() == expected }
    private fun page() = rule.onAllNodes(hasScrollAction()).onFirst()
    private fun scrollDown(distance: Float = 600f, millis: Long = 300) =
        page().performTouchInput { swipe(center, center.copy(y = center.y - distance), millis) }
    private fun scrollUp(distance: Float = 600f) = page().performTouchInput { swipe(center, center.copy(y = center.y + distance), 300) }
    private fun capsuleWidth() = rule.onNodeWithTag("navigation-capsule", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width

    private fun save(name: String) {
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun openSettings() {
        rule.onNodeWithTag("nav-settings").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("settings-backup-export").fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
    }

    @Test fun scrolling_down_folds_and_only_the_top_or_a_tap_opens_it() {
        root()
        openSettings()
        assertFalse(folded())
        scrollDown()
        waitFolded(true)
        val label = rule.activity.getString(R.string.nav_expand_bar,
            rule.activity.getString(dev.valnook.feature.settings.R.string.settings_nav_settings))
        rule.onNodeWithContentDescription(label).assertExists()
        // Back up part of the way: still folded.
        scrollUp(120f)
        rule.waitForIdle()
        assertTrue(folded())
        repeat(4) { scrollUp(1200f) }
        waitFolded(false)
        scrollDown()
        waitFolded(true)
        rule.onNodeWithTag("navigation-expand").performClick()
        waitFolded(false)
        rule.onNodeWithTag("nav-accounts").assertExists()
    }

    @Test fun a_small_move_does_not_fold() {
        root()
        openSettings()
        scrollDown(40f)
        rule.waitForIdle()
        assertFalse(folded())
    }

    @Test fun another_root_page_opens_with_the_whole_bar() {
        root()
        openSettings()
        scrollDown()
        waitFolded(true)
        rule.activity.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        waitFolded(false)
        rule.onNodeWithTag("nav-settings").assertExists()
    }

    @Test fun reversing_half_way_continues_from_where_it_is() {
        root()
        openSettings()
        val full = capsuleWidth()
        save("navigation-fold-0-open")
        rule.mainClock.autoAdvance = false
        try {
            // A quick flick, so the fold is still under way when it is measured. The clock is held, so nothing here
            // may need a fling to settle: the reversal is a tap on the capsule, not another scroll.
            scrollDown(millis = 60)
            rule.mainClock.advanceTimeBy(120)
            val middle = capsuleWidth()
            save("navigation-fold-1-half")
            assertTrue("Half way through folding: $middle of $full", middle < full - 1 && middle > full / 4)
            rule.mainClock.advanceTimeBy(80)
            val beforeTap = capsuleWidth()
            save("navigation-fold-2-folding")
            rule.onNodeWithTag("navigation-expand").performClick()
            rule.mainClock.advanceTimeByFrame()
            val afterTap = capsuleWidth()
            rule.mainClock.advanceTimeBy(100)
            save("navigation-fold-3-reopening")
            assertTrue("No jump to the whole bar: $beforeTap then $afterTap of $full",
                afterTap < full - 1 && afterTap - beforeTap < full * .15f)
            rule.mainClock.advanceTimeBy(600)
            assertEquals(full, capsuleWidth(), 1f)
        } finally { rule.mainClock.autoAdvance = true }
    }

    @Test fun a_card_s_details_bring_the_bar_back_as_it_was() {
        val wallet = requireNotNull(graph.wallet)
        val ids = runBlocking {
            (1..10).map { index ->
                val bitmap = Bitmap.createBitmap(1000, 631, Bitmap.Config.ARGB_8888); bitmap.eraseColor(0xff566775.toInt())
                val image = WalletImageCodec.encode(bitmap, 1f, 0f, 0f); bitmap.recycle()
                wallet.save(null, null, "Card $index", null, image.key, image)
            }
        }
        root()
        rule.onNodeWithTag("nav-wallet").performClick()
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("wallet-stack").fetchSemanticsNodes().isNotEmpty() }
        fun openAndClose() {
            rule.onNodeWithTag("wallet-card-${ids.first()}", useUnmergedTree = true).performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("wallet-detail").fetchSemanticsNodes().isNotEmpty() }
            rule.onNodeWithTag("root-capsule").assertDoesNotExist()
            rule.onNodeWithContentDescription(rule.activity.getString(R.string.nav_back)).performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("wallet-detail").fetchSemanticsNodes().isEmpty() }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("root-capsule").fetchSemanticsNodes().isNotEmpty() }
            rule.waitForIdle()
        }
        // Folded before the card: folded after it.
        rule.onNodeWithTag("wallet-stack").performTouchInput { swipe(center, center.copy(y = center.y - 500f), 300) }
        waitFolded(true)
        openAndClose()
        assertTrue(folded())
        // Open before the card: open after it.
        rule.onNodeWithTag("navigation-expand").performClick()
        waitFolded(false)
        openAndClose()
        assertFalse(folded())
    }
}

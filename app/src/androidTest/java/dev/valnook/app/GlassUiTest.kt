package dev.valnook.app

import android.graphics.Bitmap
import android.view.WindowManager
import android.view.inspector.WindowInspector
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.*
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.*
import dev.valnook.feature.accounts.AccountsContent
import dev.valnook.app.di.DemoDataSeeder
import dev.valnook.app.navigation.ValnookRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@HiltAndroidTest
class GlassUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    @Before fun prepare() { hilt.inject() }

    private fun scene(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent(content = content) }
        rule.waitForIdle()
    }

    private fun save(name: String, bitmap: Bitmap) {
        PlatformTestStorageRegistry.getInstance().openOutputFile("$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun root_glass_gallery_preserves_readable_overlays_in_both_themes() {
        val activity = rule.activity
        val graph = activity.sessions.session.value.graph
        runBlocking { DemoDataSeeder(graph, graph.clock).seed(AppSettings()) }
        for (dark in listOf(false, true)) {
            val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (dark) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
            }
            scene {
                CompositionLocalProvider(LocalConfiguration provides configuration) {
                    key(dark) { ValnookRoot(activity.sessions, activity.webAdmin) }
                }
            }
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("accounts-summary").fetchSemanticsNodes().isNotEmpty() }
            val theme = if (dark) "dark" else "light"
            save("root-$theme-accounts", rule.onRoot().captureToImage().asAndroidBitmap())
            val page = rule.onNodeWithTag("accounts-list").fetchSemanticsNode().boundsInWindow
            val toolbar = rule.onNodeWithTag("root-toolbar").fetchSemanticsNode().boundsInWindow
            val capsule = rule.onNodeWithTag("root-capsule").fetchSemanticsNode().boundsInWindow
            assertTrue("Viewport must extend behind both overlays", page.top < toolbar.bottom && page.bottom > capsule.bottom)
            rule.onNodeWithTag("accounts-list").performTouchInput { swipeUp(durationMillis = 450) }
            rule.mainClock.advanceTimeBy(500)
            save("root-$theme-scrolled", rule.onRoot().captureToImage().asAndroidBitmap())
            rule.onNodeWithTag("nav-investments").performClick()
            rule.waitUntil(10_000) { rule.onAllNodesWithTag("investment-summary").fetchSemanticsNodes().isNotEmpty() }
            save("root-$theme-investments", rule.onRoot().captureToImage().asAndroidBitmap())
            rule.onNodeWithTag("investment-fx-info").performClick()
            rule.onNodeWithText(activity.getString(dev.valnook.feature.investments.R.string.investment_current_fx_hint)).assertIsDisplayed()
            rule.onNodeWithTag("nav-settings").performClick()
            save("root-$theme-settings", rule.onRoot().captureToImage().asAndroidBitmap())
        }
    }

    @Test fun glass_blurs_background_tracks_updates_and_keeps_foreground_sharp() {
        val alternate = mutableStateOf(false)
        val sourceDraws = AtomicInteger()
        scene {
            ValnookTheme(dark_theme = true) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier.fillMaxSize()) {
                    Canvas(Modifier.fillMaxSize().glassSource(backdrop)) {
                        sourceDraws.incrementAndGet()
                        val stripe = 6.dp.toPx()
                        var x = 0f
                        var index = 0
                        while (x < size.width) {
                            val color = if (alternate.value) {
                                if (index % 2 == 0) Color.Green else Color.Yellow
                            } else if (index % 2 == 0) Color.Red else Color.Blue
                            drawRect(color, Offset(x, 0f), Size(stripe, size.height))
                            index++; x += stripe
                        }
                    }
                    GlassSurface(Modifier.align(Alignment.Center).size(240.dp, 160.dp).testTag("glass-sample"),
                        backdrop = backdrop) {
                        Text("128,640.28", Modifier.align(Alignment.BottomCenter).padding(20.dp),
                            color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
        }
        val first = rule.onNodeWithTag("glass-sample").captureToImage().asAndroidBitmap()
        save("glass-blur-before", first)
        fun samples(bitmap: Bitmap): List<Int> {
            val y = bitmap.height / 3
            return (bitmap.width / 4 until bitmap.width * 3 / 4).map { bitmap.getPixel(it, y) }
        }
        val reds = samples(first).map { android.graphics.Color.red(it) }
        assertTrue("Blur must suppress stripe edges, spread=${reds.max()-reds.min()}", reds.max() - reds.min() < 10)
        val lowerPixels = (first.height / 2 until first.height * 9 / 10).flatMap { y ->
            (first.width / 5 until first.width * 4 / 5).map { x -> android.graphics.Color.red(first.getPixel(x, y)) }
        }
        assertTrue("Foreground text must stay bright and sharp", lowerPixels.max() > 235)
        rule.runOnIdle { alternate.value = true }
        val second = rule.onNodeWithTag("glass-sample").captureToImage().asAndroidBitmap()
        save("glass-blur-after", second)
        val greenBefore = samples(first).map { android.graphics.Color.green(it) }.average()
        val greenAfter = samples(second).map { android.graphics.Color.green(it) }.average()
        assertTrue("Glass must sample updated content, before=$greenBefore after=$greenAfter", greenAfter - greenBefore > 20)
        val settled = sourceDraws.get()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        assertTrue("Idle blur must not continuously redraw the source", sourceDraws.get() - settled < 5)
        first.recycle(); second.recycle()
    }

    @Test fun currency_popup_blur_is_scoped_to_popup_and_selection_is_single() {
        var selections = 0
        val selected = mutableStateOf("CNY")
        scene {
            ValnookTheme(dark_theme = true) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    Text("128,640.28", style = MaterialTheme.typography.headlineMedium)
                    CurrencyChoice(selected.value, { selected.value = it; selections++ },
                        options = listOf("CNY" to "人民币", "USD" to "美元", "EUR" to "欧元"))
                }
            }
        }
        val currencyLabel = rule.activity.getString(dev.valnook.core.designsystem.R.string.currency)
        rule.onNodeWithContentDescription("$currencyLabel: CNY").performClick()
        rule.onNodeWithTag("currency-list").assertIsDisplayed()
        rule.waitForIdle()
        val blurEnabled = rule.runOnUiThread {
            rule.activity.getSystemService(WindowManager::class.java).isCrossWindowBlurEnabled
        }
        val radii = rule.runOnUiThread { WindowInspector.getGlobalWindowViews().mapNotNull {
            (it.layoutParams as? WindowManager.LayoutParams)?.blurBehindRadius
        } }
        if (blurEnabled) assertTrue("Dialog must request window blur: $radii", radii.any { it > 0 })
        save("glass-currency-popup", requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()))
        rule.onNode(hasText("USD") and hasAnyAncestor(hasTestTag("currency-list"))).performClick()
        rule.onNodeWithTag("currency-list").assertDoesNotExist()
        assertEquals("USD", selected.value)
        assertEquals(1, selections)
        rule.onNodeWithContentDescription("$currencyLabel: USD").performClick()
        rule.onNodeWithText(rule.activity.getString(dev.valnook.core.designsystem.R.string.cancel)).performClick()
        assertEquals(1, selections)
        rule.waitForIdle()
        val remaining = rule.runOnUiThread { WindowInspector.getGlobalWindowViews().mapNotNull {
            (it.layoutParams as? WindowManager.LayoutParams)?.blurBehindRadius
        } }
        assertTrue("Dismissed popup must not leave blur on the Activity: $remaining", remaining.all { it == 0 })
        PlatformTestStorageRegistry.getInstance().openOutputFile("popup-blur-capability.txt").use {
            it.write("crossWindowBlurEnabled=$blurEnabled; openRadii=$radii; closedRadii=$remaining".toByteArray())
        }
    }

    @Test fun account_expand_and_details_are_separate_and_cancelled_press_does_not_open() {
        val snapshot = AssetSnapshot(listOf(SavingsAccount(7, "示例账户", "展示数据", 1)),
            listOf(CashAccount(7, Currency.of("CNY"), 125000, 1, id = 11, name = "人民币现金")),
            emptyList(), emptyList(), emptyList(), AppSettings(baseCurrency = Currency.of("CNY")))
        val overview = AssetValuation.calculate(snapshot)
        var opens = 0
        scene { ValnookTheme { AccountsContent(overview, { opens++ }, snapshot) } }
        rule.onNodeWithTag("account-total-7").performClick()
        assertEquals(1, opens)
        rule.onNodeWithTag("account-cash-11").assertDoesNotExist()
        rule.onNodeWithTag("account-toggle-7").performClick()
        rule.onNodeWithTag("account-cash-11").assertIsDisplayed()
        assertEquals(1, opens)
        rule.onNodeWithTag("account-total-7").performTouchInput {
            down(center)
            moveTo(Offset(center.x, -100f), delayMillis = 200)
            up()
        }
        assertEquals(1, opens)
        rule.mainClock.advanceTimeBy(600)
        rule.waitForIdle()
        save("glass-accounts-expanded", rule.onRoot().captureToImage().asAndroidBitmap())
    }
}

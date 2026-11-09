package dev.valnook.app

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.LocalPageTopBarHeight
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.command.SubmissionPhase
import dev.valnook.domain.model.Direction
import dev.valnook.domain.repository.*
import dev.valnook.feature.investments.TradeForm
import dev.valnook.feature.investments.TradeFormMode
import dev.valnook.feature.investments.TradeFormViewModel
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** A new buy or sell can set its price as the instrument's current price; an older one cannot, and says why. */
@HiltAndroidTest
class TradeFormUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()
    private val store = ViewModelStore()
    private val graph get() = rule.activity.sessions.session.value.graph
    private var account = 0L
    private var instrument = 0L
    private var position = 0L

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }
    @After fun close() { rule.runOnUiThread { store.clear() } }

    private fun id() = UUID.randomUUID().toString()

    private fun seed() = runBlocking {
        val commands = graph.commands
        val type = commands.execute(SaveAssetType(id(), null, "基金")).id
        account = commands.execute(SaveAccount(id(), null, null, "Synthetic broker", "", emptyList())).id
        instrument = commands.execute(SaveInstrument(id(), null, null, "Synthetic fund", "SYN1", type, "CNY", 12_000_000)).id
        position = commands.execute(CreateInvestmentPosition(id(), account, instrument)).id
    }

    private fun show(direction: Direction, dark: Boolean = false): TradeFormViewModel {
        lateinit var vm: TradeFormViewModel
        rule.runOnUiThread {
            vm = TradeFormViewModel(account, TradeFormMode.CREATE, instrument, position, null, direction, graph.investments,
                graph.instruments, graph.cash, graph.commands, graph.clock, SavedStateHandle())
            store.put("trade-" + UUID.randomUUID(), vm)
            rule.activity.setContent {
                val config = Configuration(LocalConfiguration.current).apply { setLocales(LocaleList(Locale.SIMPLIFIED_CHINESE)) }
                CompositionLocalProvider(LocalContext provides LocalContext.current.createConfigurationContext(config),
                    LocalConfiguration provides config, LocalPageTopBarHeight provides 56.dp) {
                    ValnookTheme(dark_theme = dark) { Surface(Modifier.fillMaxSize()) {
                        Box(Modifier.padding(top = 56.dp).width(360.dp).height(720.dp)) { TradeForm(vm, {}) }
                    } }
                }
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodesWithTag("trade-set-current-price").fetchSemanticsNodes().isNotEmpty() }
        return vm
    }

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = requireNotNull(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        PlatformTestStorageRegistry.getInstance().openOutputFile("trade-form-$name.png").use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun a_new_trade_can_set_its_price_as_current_unless_it_is_older() = runBlocking<Unit> {
        seed()
        val vm = show(Direction.BUY)
        val option = rule.onNodeWithTag("trade-set-current-price")
        option.assertIsOff().assertIsEnabled()
        rule.onAllNodesWithTag("trade-set-current-price-older").assertCountEquals(0)
        rule.runOnUiThread { vm.update { it.copy(quantityInput = "1", executionPriceInput = "118.5") } }
        option.performClick()
        option.assertIsOn()
        save("buy-on")
        rule.runOnUiThread { vm.submit() }
        rule.waitUntil(10_000) { vm.submission.value.phase == SubmissionPhase.SUCCEEDED }
        assertEquals(11_850_000L, graph.instruments.observeInstrument(instrument).first()!!.currentPriceE5)

        // A sell dated a day earlier than the price just set: greyed out, with the reason.
        val older = show(Direction.SELL)
        rule.runOnUiThread { older.update { it.copy(occurredAt = it.occurredAt.minusDays(1)) } }
        rule.waitForIdle()
        rule.onNodeWithTag("trade-set-current-price").assertIsOff().assertIsNotEnabled()
        rule.onNodeWithTag("trade-set-current-price-older").assertTextContains("不能设为现价", substring = true)
        save("sell-older")
        val dark = show(Direction.SELL, dark = true)
        rule.runOnUiThread { dark.update { it.copy(occurredAt = it.occurredAt.minusDays(1)) } }
        save("sell-older-dark")
    }

    @Test fun a_new_trade_moves_to_another_account_keeping_what_was_typed() = runBlocking<Unit> {
        seed()
        val other = graph.commands.execute(SaveAccount(id(), null, null, "Synthetic bank", "", emptyList())).id
        graph.commands.execute(SetCashBalance(id(), account, "CNY", 100_000, null))
        val saved = SavedStateHandle()
        lateinit var vm: TradeFormViewModel
        rule.runOnUiThread {
            vm = TradeFormViewModel(account, TradeFormMode.CREATE, instrument, position, null, Direction.BUY, graph.investments,
                graph.instruments, graph.cash, graph.commands, graph.clock, saved)
            store.put("move", vm)
            // Shown, so its cash accounts are read.
            rule.activity.setContent { ValnookTheme { Surface(Modifier.fillMaxSize()) { TradeForm(vm, {}) } } }
        }
        rule.waitUntil(10_000) { vm.state.value.loaded && vm.cashAccounts.value.isNotEmpty() }
        rule.runOnUiThread {
            vm.update { it.copy(quantityInput = "3", executionPriceInput = "101", feeInput = "0.5") }
            vm.setCashLinked(true)
        }
        rule.waitForIdle()
        assertEquals(true, vm.state.value.cashAccountId != null)
        // The other account has no position yet and no cash: what was typed stays, the cash choice is cleared.
        rule.runOnUiThread { vm.selectAccount(other, null) }
        rule.waitForIdle()
        assertEquals(other, vm.accountId.value)
        val moved = vm.state.value
        assertEquals(listOf("3", "101", "0.5"), listOf(moved.quantityInput, moved.executionPriceInput, moved.feeInput))
        assertEquals(null, moved.positionId)
        assertEquals(false, moved.cashLinked)
        assertEquals(null, moved.cashAccountId)
        rule.waitUntil(5_000) { vm.cashAccounts.value.isEmpty() }
        // Recreated from its saved state, it is still in the account chosen.
        lateinit var again: TradeFormViewModel
        rule.runOnUiThread {
            again = TradeFormViewModel(account, TradeFormMode.CREATE, instrument, position, null, Direction.BUY, graph.investments,
                graph.instruments, graph.cash, graph.commands, graph.clock, saved)
            store.put("again", again)
        }
        assertEquals(other, again.accountId.value)
        // Saved, the buy opens a position in the account it moved to.
        rule.runOnUiThread { vm.submit() }
        rule.waitUntil(10_000) { vm.submission.value.phase == SubmissionPhase.SUCCEEDED }
        val positions = graph.overview.observeSnapshot().first().positions.filter { it.instrumentId == instrument }
        assertEquals(dev.valnook.domain.money.DecimalRules.parse_e8("3"),
            positions.single { it.account_id == other }.holding_quantity_e8)
    }
}

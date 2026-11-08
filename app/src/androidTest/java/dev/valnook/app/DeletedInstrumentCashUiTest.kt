package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.model.Currency
import dev.valnook.feature.cash.CashEntryDetailContent
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Cash a deleted instrument's trade moved reads as its buy or sale, with no trade left to open. */
@HiltAndroidTest
class DeletedInstrumentCashUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private fun entry(delta: Long, source: CashSource) =
        CashEntry(1, 1, Currency.of("CNY"), delta, 0, source, null, null, "", 1)

    private fun show(entry: CashEntry) {
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme { Surface { CashEntryDetailContent(entry, "合成账户") {} } } } }
        rule.waitForIdle()
    }

    @Test fun a_deleted_instruments_cash_names_its_side_and_offers_nothing_to_open() {
        show(entry(-2000, CashSource.TRADE_REMOVED))
        rule.onNodeWithText("已删除标的的买入").assertExists()
        rule.onNodeWithText("编辑来源交易").assertDoesNotExist()
        rule.onNodeWithText("编辑余额变化").assertDoesNotExist()
        show(entry(1200, CashSource.TRADE_REMOVED))
        rule.onNodeWithText("已删除标的的卖出").assertExists()
        // A live trade still opens its record.
        show(entry(-2000, CashSource.TRADE))
        rule.onNodeWithText("编辑来源交易").assertExists()
    }
}

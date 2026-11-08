package dev.valnook.app

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.feature.deposits.DepositsContent
import dev.valnook.feature.investments.InstrumentLibraryContent
import dev.valnook.feature.investments.InstrumentLibraryState
import dev.valnook.feature.investments.RealizedProfitContent
import dev.valnook.feature.investments.RealizedRow
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Long cards compose only the rows on screen: each row is a lazy item of its own, reached by scrolling. */
@HiltAndroidTest
class LazyCardRowsUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private val count = 200L
    private val funds = AssetType(2, "合成基金")
    private val cny = Currency.of("CNY")
    private fun instrument(id: Long) = Instrument(id, "合成基金 $id", "SYN$id", funds.id, funds.name, cny, 100_000, true, 1, 0)

    private fun show(content: @Composable () -> Unit) {
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme { Surface(Modifier.safeDrawingPadding()) { content() } } } }
        rule.waitForIdle()
    }

    /** The first row is there, the last is not composed until the list scrolls to it. */
    private fun assertOnlyRowsOnScreen(list: SemanticsMatcher, first: String, last: String, lastKey: String) {
        rule.onNodeWithTag(first, useUnmergedTree = true).assertExists()
        rule.onNodeWithTag(last, useUnmergedTree = true).assertDoesNotExist()
        rule.onNode(list).performScrollToKey(lastKey)
        rule.waitForIdle()
        rule.onNodeWithTag(last, useUnmergedTree = true).assertIsDisplayed()
        rule.onNodeWithTag(first, useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun the_instrument_library_composes_only_the_rows_on_screen() {
        val all = (1..count).map { InstrumentAssets(instrument(it), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO) }
        show { InstrumentLibraryContent(InstrumentLibraryState.Ready(all, listOf(funds), emptySet())) {} }
        assertOnlyRowsOnScreen(hasTestTag("instrument-library"), "library-row-1", "library-row-$count", "instrument:$count")
    }

    @Test fun realized_profit_composes_only_the_rows_on_screen() {
        val rows = (1..count).map { RealizedRow(InstrumentAssets(instrument(it), BigDecimal.ZERO, null, BigDecimal("1.5")), false, null) }
        show { RealizedProfitContent(ConvertedTotal(BigDecimal("300"), cny, emptySet()), cny, rows) {} }
        assertOnlyRowsOnScreen(hasTestTag("realized-page"), "realized-row-1", "realized-row-$count", "realized:$count")
    }

    @Test fun deposits_compose_only_the_rows_on_screen_and_end_with_the_open_row() {
        val start = LocalDate.parse("2026-09-01").toEpochDay()
        val end = LocalDate.parse("2027-09-01").toEpochDay()
        val deposits = (1..count).map { TermDeposit(it, 1, cny, 100_000, DecimalRules.parse_e8("2"), start, end,
            DecimalRules.interest(100_000, DecimalRules.parse_e8("2"), start, end), false, false, null) }
        var opened = 0
        show { DepositsContent(deposits, start + 30, { opened++ }, {}, on_archive = {}, hasMore = false) }
        // The row to open another is the card's last piece, so it is composed only at the end of the list.
        rule.onNodeWithTag("deposit-open").assertDoesNotExist()
        assertOnlyRowsOnScreen(hasScrollToKeyAction(), "deposit-record-1", "deposit-record-$count", "deposit:$count")
        rule.onNode(hasScrollToKeyAction()).performScrollToKey("deposit:footer")
        rule.onNodeWithTag("deposit-open").assertIsDisplayed().performClick()
        assertEquals(1, opened)
    }
}

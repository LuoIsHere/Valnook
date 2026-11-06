package dev.valnook.app

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.valnook.designsystem.LocalPageTopBarHeight
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.*
import dev.valnook.feature.accounts.AccountDetailContent
import dev.valnook.feature.accounts.AccountDetailSection
import dev.valnook.feature.accounts.AccountSectionSummary
import dev.valnook.feature.cash.CashContent
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The header closes before the list scrolls, moves the balance up, and never rests halfway. */
class AccountDetailCollapseUiTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val cny = Currency.of("CNY")
    private val expansion = mutableFloatStateOf(-1f)

    private fun total(value: String) = ConvertedTotal(BigDecimal(value), cny, emptySet())

    private fun show(rows: Int, depositTotal: String = "0") = rule.setContent {
        // Room above the page stands in for the title bar, so the balance's final position stays on screen.
        // The theme's root passes its size down as a minimum; the outer Box lets the page take its own size.
        ValnookTheme { CompositionLocalProvider(LocalPageTopBarHeight provides 56.dp) { Box {
            Box(Modifier.padding(top = 56.dp).width(360.dp).height(640.dp)) {
                AccountDetailContent(1, "Bank", "", total("1234.50"),
                    mapOf(AccountDetailSection.ACCOUNTS to AccountSectionSummary(rows, total("1234.50")),
                        AccountDetailSection.DEPOSITS to AccountSectionSummary(0, total(depositTotal)),
                        AccountDetailSection.INVESTMENTS to AccountSectionSummary(0, total("0"))),
                    AccountDetailSection.ACCOUNTS,
                    List(30) { StatisticsPoint(LocalDate.of(2026, 10, 1).plusDays(it.toLong()), BigDecimal(it), reliable = true) },
                    cashContent = { slot ->
                        expansion.floatValue = slot.expansion()
                        LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                            items(rows) { index -> Text("row $index", Modifier.height(80.dp).testTag("row-$index")) }
                        }
                    }, depositContent = { Text("deposits") }, investmentContent = { Text("investments") })
            }
        } } }
    }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun drag(pixels: Float, tag: String = "list") {
        rule.onNodeWithTag(tag).performTouchInput {
            down(center)
            // Slow enough that releasing counts as stopping, not as a fling.
            repeat(10) { moveBy(androidx.compose.ui.geometry.Offset(0f, pixels / 10f), delayMillis = 60) }
            advanceEventTime(500)
            moveBy(androidx.compose.ui.geometry.Offset.Zero)
            up()
        }
        rule.waitForIdle()
    }

    private val openTabsTop get() = with(rule.density) { (56.dp).toPx() } + headerHeight
    private var headerHeight = 0f

    private fun measureOpen() { headerHeight = bounds("account-section-ACCOUNTS").top - with(rule.density) { 56.dp.toPx() } }

    @Test fun the_header_closes_before_the_list_moves() {
        show(30)
        rule.waitForIdle()
        measureOpen()
        val firstRow = bounds("row-0").top - bounds("account-section-ACCOUNTS").top
        val thirdRow = bounds("row-2").top - bounds("account-section-ACCOUNTS").top
        drag(-headerHeight * .9f)
        // Past the middle: the release finishes closing. The list itself has not scrolled.
        assertEquals(with(rule.density) { 56.dp.toPx() }, bounds("account-section-ACCOUNTS").top, 2f)
        assertEquals(firstRow, bounds("row-0").top - bounds("account-section-ACCOUNTS").top, 2f)
        drag(-300f)
        assertTrue("The list scrolls once the header is closed",
            bounds("row-2").top - bounds("account-section-ACCOUNTS").top < thirdRow - 100f)
    }

    @Test fun a_short_release_goes_back_open() {
        show(30)
        rule.waitForIdle()
        measureOpen()
        drag(-headerHeight * .2f)
        assertEquals(openTabsTop, bounds("account-section-ACCOUNTS").top, 2f)
        assertEquals(0f, expansion.floatValue, 0f)
    }

    @Test fun the_header_reopens_only_when_the_list_is_back_at_its_top() {
        show(30)
        rule.waitForIdle()
        measureOpen()
        drag(-headerHeight * .9f)
        drag(-400f)
        drag(100f)
        assertEquals(with(rule.density) { 56.dp.toPx() }, bounds("account-section-ACCOUNTS").top, 2f)
        drag(2000f)
        assertEquals(openTabsTop, bounds("account-section-ACCOUNTS").top, 2f)
    }

    @Test fun a_list_too_short_to_scroll_still_collapses_and_opens_the_details() {
        show(1)
        rule.waitForIdle()
        measureOpen()
        assertEquals(0f, expansion.floatValue, 0f)
        drag(-headerHeight * .9f)
        assertEquals(1f, expansion.floatValue, 0f)
        drag(headerHeight * .9f)
        assertEquals(0f, expansion.floatValue, 0f)
    }

    @Test fun dragging_the_header_itself_collapses_it() {
        show(1)
        rule.waitForIdle()
        measureOpen()
        drag(-headerHeight * .9f, "account-detail-header")
        assertEquals(1f, expansion.floatValue, 0f)
    }

    @Test fun the_balance_moves_up_and_right_and_shrinks() {
        show(30)
        rule.waitForIdle()
        measureOpen()
        val open = bounds("account-detail-total")
        drag(-headerHeight * .9f)
        val closed = bounds("account-detail-total")
        assertTrue("Smaller: ${open.width} -> ${closed.width}", closed.width < open.width * .6f)
        assertTrue("Further right", closed.left > open.left)
        val bar = with(rule.density) { 56.dp.toPx() }
        assertEquals("Centered in the title bar", bar / 2f, closed.center.y, 3f)
        val pageRight = with(rule.density) { 360.dp.toPx() }
        assertEquals("Ends before the edit button", pageRight - with(rule.density) { 44.dp.toPx() }, closed.right, 3f)
    }

    @Test fun a_finger_on_the_chart_shows_that_days_value_then_restores_the_balance() {
        show(30)
        rule.waitForIdle()
        rule.onNodeWithTag("account-detail-total", useUnmergedTree = true).assertTextEquals("1 234.50 CNY")
        rule.onNodeWithTag("account-trend-chart").performTouchInput { down(centerRight) }
        rule.waitForIdle()
        rule.onNodeWithTag("account-detail-total", useUnmergedTree = true).assertTextEquals("29.00 CNY")
        rule.onNodeWithTag("account-detail-total-label", useUnmergedTree = true).assertTextContains("2026", substring = true)
        rule.onNodeWithTag("account-trend-chart").performTouchInput { up() }
        rule.waitForIdle()
        rule.onNodeWithTag("account-detail-total", useUnmergedTree = true).assertTextEquals("1 234.50 CNY")
    }

    /** Resizing would rebuild and squash the curve, so collapsing must leave the chart's size alone. */
    @Test fun the_chart_keeps_its_size_while_closing() {
        show(30)
        rule.waitForIdle()
        measureOpen()
        val before = rule.onNodeWithTag("account-trend-chart").fetchSemanticsNode().size
        drag(-headerHeight * .9f)
        assertEquals(before, rule.onNodeWithTag("account-trend-chart").fetchSemanticsNode().size)
    }

    @Test fun section_counts_are_in_the_tabs_and_a_lone_total_is_hidden() {
        show(4)
        rule.onNodeWithTag("account-section-ACCOUNTS").assertTextContains("4", substring = true)
        rule.onNodeWithTag("account-section-total").assertDoesNotExist()
    }

    @Test fun a_section_total_shows_when_two_sections_hold_assets() {
        show(4, depositTotal = "10")
        rule.onNodeWithTag("account-section-total").assertExists()
    }

    private fun credit(id: Long, name: String, balance: Long) = CashAccount(id, cny, balance, 1, id, name,
        creditProfile = CreditAccountProfile(500000, 7, CreditDueRule.AfterStatementDays(20), null))

    private fun showCash(expanded: Float) = rule.setContent {
        ValnookTheme { Box { Box(Modifier.width(360.dp).height(640.dp)) {
            CashContent(listOf(CashAccount(2, cny, 1000, 1, 1, "Savings"), credit(3, "Card", -2000)),
                creditExpansion = { expanded }, accountNote = "Salary", on_open = {})
        } } }
    }

    @Test fun credit_cards_come_first() {
        showCash(0f)
        assertTrue(bounds("cash-section-credit").bottom <= bounds("cash-section-savings").top)
        rule.onNodeWithTag("cash-section-info").assertExists()
    }

    @Test fun closed_details_take_no_room_but_stay_readable_to_screen_readers() {
        showCash(0f)
        assertEquals(0f, bounds("cash-credit-details-3").height, 0.5f)
        rule.onNodeWithTag("cash-row-3").assertTextContains(rule.activity.getString(dev.valnook.feature.cash.R.string.cash_limit_used))
    }

    @Test fun open_details_are_visible() {
        showCash(1f)
        assertTrue(bounds("cash-credit-details-3").height > 0f)
    }
}

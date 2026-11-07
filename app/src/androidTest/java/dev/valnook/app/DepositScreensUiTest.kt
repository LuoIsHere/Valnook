package dev.valnook.app

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.io.PlatformTestStorageRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.TermDeposit
import dev.valnook.domain.money.DecimalRules
import dev.valnook.feature.deposits.DepositDetailContent
import dev.valnook.feature.deposits.DepositsContent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import dev.valnook.core.designsystem.R as CoreR
import dev.valnook.feature.deposits.R as DepositsR

/** Each deposit row and the detail page read the term, its state and what is left of it from the dates. */
@HiltAndroidTest
class DepositScreensUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    private val today = LocalDate.parse("2026-10-03").toEpochDay()
    private fun day(text: String) = LocalDate.parse(text).toEpochDay()
    private fun string(id: Int, vararg args: Any) = rule.activity.getString(id, *args)
    private fun plural(id: Int, count: Int) = rule.activity.resources.getQuantityString(id, count, count)
    private fun term(months: Int? = null, years: Int? = null, days: Int? = null) = string(DepositsR.string.deposit_term_title,
        when { years != null -> plural(DepositsR.plurals.deposit_term_years, years)
            months != null -> plural(DepositsR.plurals.deposit_term_months, months)
            else -> plural(DepositsR.plurals.deposit_term_days, days!!) })
    private fun deposit(id: Long, code: String, principal: Long, rate: String, start: String, end: String,
        closed: Boolean = false, closedAt: Long? = null) = TermDeposit(id, 1, Currency.of(code), principal,
        DecimalRules.parse_e8(rate), day(start), day(end),
        DecimalRules.interest(principal, DecimalRules.parse_e8(rate), day(start), day(end)), closed, false,
        if (closed) false else null, closedAtMs = closedAt)

    private fun save(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        PlatformTestStorageRegistry.getInstance().openOutputFile("deposit-$name.png").use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private fun row(id: Long) = rule.onNodeWithTag("deposit-record-$id")

    @Test fun rows_name_the_term_and_say_how_much_of_it_is_left() {
        val active = deposit(1, "HKD", 400000, "2.6", "2026-09-24", "2026-12-24")
        val matured = deposit(2, "CNY", 1000000, "1.8", "2026-04-01", "2026-10-01")
        val upcoming = deposit(3, "USD", 500000, "4", "2026-10-10", "2027-10-10")
        val custom = deposit(4, "CNY", 200000, "1.5", "2026-10-01", "2026-11-15")
        val opened = mutableListOf<Long>()
        for (dark in listOf(false, true)) {
            rule.runOnUiThread { rule.activity.setContent { ValnookTheme(dark_theme = dark) { Surface(Modifier.safeDrawingPadding()) {
                DepositsContent(listOf(active, matured, upcoming, custom), today, {}, {}, { opened += it }, on_archive = {})
            } } } }
            rule.waitForIdle()
            save(if (dark) "rows-dark" else "rows-light")
        }
        row(1).assert(hasText(term(months = 3), substring = true))
            .assert(hasText(string(CoreR.string.active), substring = true))
            .assert(hasText(string(DepositsR.string.deposit_rate_value, "2.6"), substring = true))
            .assert(hasText("+" + DecimalRules.format_display(active.expected_interest_minor, 2), substring = true))
            .assert(hasText(plural(DepositsR.plurals.deposit_days_left, 82) + " · 9%", substring = true))
            .assert(hasText("2026-09-24 → 2026-12-24", substring = true))
        row(2).assert(hasText(term(months = 6), substring = true))
            .assert(hasText(string(CoreR.string.matured), substring = true))
            .assert(hasText(plural(DepositsR.plurals.deposit_matured_days, 2) + " · 100%", substring = true))
        row(3).assert(hasText(term(years = 1), substring = true))
            .assert(hasText(string(CoreR.string.upcoming), substring = true))
            .assert(hasText(plural(DepositsR.plurals.deposit_starts_in, 7) + " · 0%", substring = true))
        row(4).assert(hasText(term(days = 45), substring = true))
        row(2).performClick()
        assertEquals(listOf(2L), opened)
    }

    @Test fun settled_rows_say_when_they_were_settled() {
        val settledAt = java.time.LocalDate.parse("2026-04-02").atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val settled = deposit(5, "CNY", 1000000, "3", "2026-01-01", "2026-04-01", closed = true, closedAt = settledAt)
        rule.runOnUiThread { rule.activity.setContent { ValnookTheme { Surface(Modifier.safeDrawingPadding()) {
            DepositsContent(listOf(settled), today, {}, {}, closed = true, hasMore = false)
        } } } }
        row(5).assert(hasText(string(CoreR.string.closed), substring = true))
            .assert(hasText(string(DepositsR.string.deposit_settled_on, "2026-04-02") + " · 100%", substring = true))
        // Settled ones are not opened here, and their list has no settled link of its own.
        rule.onNodeWithTag("deposit-open").assertDoesNotExist()
        rule.onNodeWithTag("deposit-settled-open").assertDoesNotExist()
        save("settled")
    }

    @Test fun detail_shows_the_term_over_its_facts_and_offers_settling_once_matured() {
        val matured = deposit(2, "CNY", 1000000, "1.8", "2026-04-01", "2026-10-01")
        var shown by androidx.compose.runtime.mutableStateOf(matured)
        var closes = 0
        for (dark in listOf(false, true)) {
            rule.runOnUiThread { rule.activity.setContent { ValnookTheme(dark_theme = dark) { Surface(Modifier.safeDrawingPadding()) {
                DepositDetailContent(shown, today, "Example bank", {}, { closes++ })
            } } } }
            rule.waitForIdle()
            save(if (dark) "detail-dark" else "detail-light")
        }
        rule.onNodeWithTag("deposit-detail-summary")
            .assert(hasAnyDescendant(hasText(term(months = 6))))
            .assert(hasAnyDescendant(hasText(string(CoreR.string.matured))))
        rule.onNodeWithTag("deposit-detail-principal").assertTextEquals(DecimalRules.format_display(1000000, 2) + " CNY")
        val total = DecimalRules.format_display(matured.principal_minor + matured.expected_interest_minor, 2) + " CNY"
        rule.onNodeWithTag("detail-" + string(DepositsR.string.deposit_summary_total)).assert(hasText(total))
        rule.onNodeWithTag("detail-" + string(DepositsR.string.deposit_term_section))
            .assert(hasText(plural(DepositsR.plurals.deposit_term_days, 183)))
        rule.onNodeWithTag("detail-" + string(DepositsR.string.deposit_rate_label)).assert(hasText("1.8%"))
        rule.onNodeWithTag("deposit-detail-list").performScrollToNode(hasText(string(CoreR.string.close_deposit)))
        rule.onNodeWithText(string(CoreR.string.close_deposit)).performClick()
        assertEquals(1, closes)
        rule.onNodeWithText(string(CoreR.string.edit_deposit)).assertExists()
        // Before maturity, and once settled, there is nothing to settle.
        rule.runOnUiThread { shown = deposit(1, "HKD", 400000, "2.6", "2026-09-24", "2026-12-24") }
        rule.waitForIdle()
        rule.onNodeWithText(string(CoreR.string.close_deposit)).assertDoesNotExist()
        rule.runOnUiThread { shown = matured.copy(closed = true, close_cash_linked = true) }
        rule.waitForIdle()
        rule.onNodeWithText(string(CoreR.string.close_deposit)).assertDoesNotExist()
        rule.onNodeWithText(string(CoreR.string.edit_deposit)).assertExists()
    }
}

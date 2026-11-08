package dev.valnook.app

import android.os.Bundle
import android.text.format.DateFormat
import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.designsystem.ValnookTheme
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashSource
import dev.valnook.domain.model.Currency
import dev.valnook.feature.cash.CashDayGroup
import dev.valnook.feature.cash.CashEntryDetailContent
import dev.valnook.feature.investments.TradeDetailContent
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.Investment
import dev.valnook.domain.model.Trade
import dev.valnook.designsystem.clockFormatter
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Sub-account ledger and trade times follow the system's 12- or 24-hour setting. Run once under each setting to see both. */
@HiltAndroidTest
class CashClockUiTest {
    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val rule = createAndroidComposeRule<MainActivity>()

    @Before fun prepare() { hilt.inject(); rule.waitForIdle() }

    @Test fun the_clock_reads_as_twelve_or_twenty_four_hours_in_either_language() {
        val afternoon = LocalTime.of(15, 5)
        assertEquals("15:05", afternoon.format(clockFormatter(Locale.SIMPLIFIED_CHINESE, true)))
        assertEquals("15:05", afternoon.format(clockFormatter(Locale.US, true)))
        val chinese = afternoon.format(clockFormatter(Locale.SIMPLIFIED_CHINESE, false))
        assertTrue(chinese, "下午" in chinese && "3:05" in chinese && "15" !in chinese)
        val english = afternoon.format(clockFormatter(Locale.US, false))
        assertTrue(english, "3:05" in english && "PM" in english && "15" !in english)
    }

    @Test fun ledger_rows_and_the_entry_detail_use_the_devices_setting() {
        val activity = rule.activity
        val is24Hour = DateFormat.is24HourFormat(activity)
        InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply { putString("is24Hour", is24Hour.toString()) })
        val day = LocalDate.of(2026, 3, 9)
        val at = day.atTime(15, 5).atZone(ZoneId.systemDefault())
        val entry = CashEntry(1, 1, Currency.of("CNY"), 1200, at.toInstant().toEpochMilli(), CashSource.CASH_SET, null, null, "合成备注", 1)
        val expected = at.format(clockFormatter(activity.resources.configuration.locales[0], is24Hour))
        assertEquals(is24Hour, expected == "15:05")

        rule.runOnUiThread { activity.setContent { ValnookTheme { Surface { CashDayGroup(day, listOf(entry)) {} } } } }
        rule.waitForIdle()
        rule.onNodeWithText(expected).assertExists()

        rule.runOnUiThread { activity.setContent { ValnookTheme { Surface { CashEntryDetailContent(entry, "合成账户") {} } } } }
        rule.waitForIdle()
        rule.onNodeWithText(expected).assertExists()
    }

    @Test fun a_trades_detail_uses_the_devices_setting() {
        val activity = rule.activity
        val is24Hour = DateFormat.is24HourFormat(activity)
        val at = LocalDate.of(2026, 3, 9).atTime(15, 5).atZone(ZoneId.systemDefault())
        val usd = Currency.of("USD")
        val asset = Investment(1, 1, 1, "合成类型", "合成基金", "SYN", usd, 100_000_000, 12_000_000_000, at.toInstant().toEpochMilli())
        val trade = Trade(1, 1, Direction.BUY, 100_000_000, 12_000_000_000, 12_000, usd, false, at.toInstant().toEpochMilli())
        val expected = at.format(clockFormatter(activity.resources.configuration.locales[0], is24Hour))
        rule.runOnUiThread { activity.setContent { ValnookTheme { Surface { TradeDetailContent(asset, trade, "合成账户", {}, {}) } } } }
        rule.waitForIdle()
        rule.onNodeWithText(expected).assertExists()
    }
}

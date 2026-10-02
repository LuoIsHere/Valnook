package dev.valnook.app

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.di.DataMode
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject

@HiltAndroidTest
class SessionIsolationTest {
    @get:Rule val hilt = HiltAndroidRule(this)
    @Inject lateinit var sessions: AppSessionManager

    @Before fun inject() = hilt.inject()

    @Test fun demo_uses_fresh_rich_data_and_rejects_commands_after_exit() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        fun demoDatabaseFiles() = context.databaseList().filter { it.startsWith("valnook-demo-") }
        val real = sessions.session.value
        real.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
            "Real account", "must remain isolated", listOf(
                CashBalanceChange("CNY", 123_456, null, name = "Real cash")
            )))
        val realBefore = real.graph.overview.snapshot()

        sessions.enterDemo()
        val demo = sessions.session.value
        assertEquals(DataMode.DEMO, demo.mode)
        assertTrue(demo.id != real.id)
        assertEquals(1, demoDatabaseFiles().count { it.endsWith(".db") })
        val snapshot = demo.graph.overview.snapshot()
        assertEquals(12, snapshot.accounts.size)
        assertEquals(48, snapshot.cash.size)
        assertEquals(60, snapshot.instruments.size)
        assertEquals(80, snapshot.positions.size)
        val demoTrades = snapshot.positions.flatMap { demo.graph.investments.trade_page(it.id, null, 100) }
        assertEquals(720, demoTrades.size)
        assertTrue(snapshot.positions.map { it.holding_quantity_e8 }.distinct().size > 10)
        assertTrue(demoTrades.map { it.quantity_e8 }.distinct().size > 10)
        assertTrue(demoTrades.map { it.fee_minor }.distinct().size > 20)
        assertTrue(demoTrades.any { it.fee_minor == 0L })
        assertTrue(demoTrades.any { it.cash_linked && it.cashAccountId != null })
        assertTrue(demoTrades.any { !it.cash_linked && it.cashAccountId == null })
        assertTrue(snapshot.instruments.any { it.symbol == "600519.SH" && it.name == "贵州茅台" })
        assertTrue(snapshot.instruments.any { it.symbol == "0700.HK" && it.name == "腾讯控股" })
        assertTrue(snapshot.instruments.any { it.symbol == "AAPL" && it.name == "Apple" })
        assertTrue(snapshot.instruments.none { it.symbol.contains("DEMO", ignoreCase = true) })
        assertEquals(24, snapshot.accounts.sumOf { account ->
            demo.graph.deposits.observe_deposits(account.id, 100, false).first().size +
                demo.graph.deposits.observe_deposits(account.id, 100, true).first().size
        })
        assertEquals(realBefore, real.graph.overview.snapshot())
        demo.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
            "Temporary demo account", "must disappear after re-entry", emptyList()))
        assertEquals(13, demo.graph.overview.snapshot().accounts.size)

        val staleDemoCommands = demo.graph.commands
        sessions.exitDemo()
        assertEquals(DataMode.REAL, sessions.session.value.mode)
        assertTrue(demoDatabaseFiles().isEmpty())
        assertEquals(realBefore, sessions.session.value.graph.overview.snapshot())
        expect(ErrorCode.SESSION_EXPIRED) {
            staleDemoCommands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
                "Stale demo write", "", emptyList()))
        }

        sessions.enterDemo()
        val restoredDemo = sessions.session.value.graph
        assertEquals(12, restoredDemo.overview.snapshot().accounts.size)
        assertTrue(restoredDemo.overview.snapshot().accounts.none { it.name == "Temporary demo account" })
        sessions.exitDemo()
        assertTrue(demoDatabaseFiles().isEmpty())
    }

    @Test fun clear_requires_current_challenge_preserves_display_preferences_and_expires_old_commands() = runBlocking {
        val active = sessions.session.value
        active.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
            "Disposable account", "", listOf(CashBalanceChange("USD", 10_000, null, name = "USD"))))
        val before = active.graph.settings.observeSettings().first()
        active.graph.settings.saveSettings(AppSettings(
            baseCurrency = Currency.of("CNY"),
            rates = listOf(FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2"))),
            language = AppLanguage.ENGLISH,
            gainLossColors = GainLossColorScheme.RED_GAIN
        ), before.revision)

        val challenge = sessions.issueClearChallenge()
        assertTrue(challenge.matches(Regex("[A-HJ-NP-Z2-9]{6}")))
        expect(ErrorCode.SESSION_EXPIRED) { sessions.clearRealData(challenge, "WRONG2") }
        assertFalse(active.graph.overview.snapshot().accounts.isEmpty())

        val staleCommands = active.graph.commands
        sessions.clearRealData(challenge, challenge)
        val cleared = sessions.session.value
        assertTrue(cleared.id != active.id)
        assertTrue(cleared.graph.overview.snapshot().accounts.isEmpty())
        val preferences = cleared.graph.settings.observeSettings().first()
        assertEquals(null, preferences.baseCurrency)
        assertTrue(preferences.rates.isEmpty())
        assertEquals(AppLanguage.ENGLISH, preferences.language)
        assertEquals(GainLossColorScheme.RED_GAIN, preferences.gainLossColors)
        expect(ErrorCode.SESSION_EXPIRED) {
            staleCommands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
                "Stale real write", "", emptyList()))
        }

        sessions.enterDemo()
        val demoPreferences = sessions.session.value.graph.settings.observeSettings().first()
        assertEquals(AppLanguage.ENGLISH, demoPreferences.language)
        assertEquals(GainLossColorScheme.RED_GAIN, demoPreferences.gainLossColors)
        expect(ErrorCode.SESSION_EXPIRED) { sessions.issueClearChallenge() }
        sessions.exitDemo()
    }

    private suspend fun expect(code: ErrorCode, action: suspend () -> Unit) {
        try {
            action()
            throw AssertionError("Expected $code")
        } catch (error: DomainException) {
            assertEquals(code, error.code)
        }
    }
}

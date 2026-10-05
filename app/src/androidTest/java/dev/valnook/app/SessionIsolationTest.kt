package dev.valnook.app

import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dev.valnook.app.di.AppSessionManager
import dev.valnook.app.di.DataMode
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.FxRate
import dev.valnook.domain.model.GainLossColorScheme
import dev.valnook.domain.model.InvestmentSection
import dev.valnook.domain.model.NavigationConfiguration
import dev.valnook.domain.model.NavigationItemId
import dev.valnook.domain.model.StatisticsGranularity
import dev.valnook.domain.model.StatisticsMetric
import dev.valnook.domain.model.StatisticsPeriod
import dev.valnook.domain.model.StatisticsRequest
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.SaveAccount
import dev.valnook.domain.repository.SaveFinancialSettings
import dev.valnook.domain.repository.SaveGainLossColors
import dev.valnook.domain.repository.SaveLanguage
import dev.valnook.domain.repository.SaveNavigationConfiguration
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
        assertEquals(37, snapshot.cash.size)
        assertEquals(10, snapshot.cash.count { it.type == BalanceAccountType.CREDIT })
        val cashById = snapshot.cash.associateBy { it.id }
        assertTrue(snapshot.cash.filter { it.creditProfile?.limitSourceAccountId != null }.all { child ->
            cashById[child.creditProfile?.limitSourceAccountId]?.account_id == child.account_id
        })
        assertEquals(60, snapshot.instruments.size)
        assertEquals(26, snapshot.positions.size)
        val demoTrades = snapshot.positions.flatMap { demo.graph.investments.trade_page(it.id, null, 100) }
        assertEquals(247, demoTrades.size)
        assertTrue(snapshot.positions.map { it.holding_quantity_e8 }.distinct().size > 10)
        assertTrue(demoTrades.map { it.quantity_e8 }.distinct().size > 10)
        assertTrue(demoTrades.map { it.fee_minor }.distinct().size > 20)
        assertTrue(demoTrades.any { it.fee_minor == 0L })
        assertTrue(demoTrades.any { it.direction == dev.valnook.domain.model.Direction.BUY &&
            it.cash_linked && it.cashAccountId != null })
        assertTrue(demoTrades.any { it.direction == dev.valnook.domain.model.Direction.SELL &&
            it.cash_linked && it.cashAccountId != null })
        assertTrue(demoTrades.any { !it.cash_linked && it.cashAccountId == null })
        assertTrue(demoTrades.maxOf { it.revision } >= 4)
        assertTrue(snapshot.instruments.any { it.symbol == "600519.SH" && it.name.isNotBlank() })
        assertTrue(snapshot.instruments.any { it.symbol == "0700.HK" && it.name.isNotBlank() })
        assertTrue(snapshot.instruments.any { it.symbol == "AAPL" && it.name == "Apple" })
        assertTrue(snapshot.instruments.none { it.symbol.contains("DEMO", ignoreCase = true) })
        val overview = AssetValuation.calculate(snapshot)
        assertTrue(overview.total.amount >= BigDecimal("100000"))
        assertTrue(overview.total.amount < BigDecimal("200000"))
        val bankIds = snapshot.accounts.filter { it.note.startsWith("银行账户") }.map { it.id }.toSet()
        val brokerIds = snapshot.accounts.filter { it.note.startsWith("证券账户") }.map { it.id }.toSet()
        assertEquals(6, bankIds.size)
        assertEquals(6, brokerIds.size)
        assertTrue(snapshot.cash.filter { it.type == BalanceAccountType.CREDIT }.all { it.account_id in bankIds })
        assertTrue(snapshot.positions.all { it.account_id in brokerIds })
        val currentStatistics = demo.graph.statistics.loadCurrent()
        assertEquals("cash overview=${overview.cash.amount} statistics=${currentStatistics.availableCash}",
            0, overview.cash.amount.compareTo(requireNotNull(currentStatistics.availableCash)))
        assertEquals("investment overview=${overview.investmentValue.amount} statistics=${currentStatistics.investmentValue}",
            0, overview.investmentValue.amount.compareTo(requireNotNull(currentStatistics.investmentValue)))
        assertEquals("total overview=${overview.total.amount} statistics=${currentStatistics.totalAssets}",
            0, overview.total.amount.compareTo(requireNotNull(currentStatistics.totalAssets)))
        assertEquals(overview.total.currency, currentStatistics.currency)
        val demoDeposits = snapshot.accounts.flatMap { account ->
            demo.graph.deposits.observe_deposits(account.id, 100, false).first() +
                demo.graph.deposits.observe_deposits(account.id, 100, true).first()
        }
        assertEquals(26, demoDeposits.size)
        assertTrue(demoDeposits.maxOf { it.revision } >= 6)
        val firstAccount = snapshot.accounts.first()
        val timelineCash = snapshot.cash.single {
            it.account_id == firstAccount.id && it.type == BalanceAccountType.SAVINGS &&
                it.currency.code == "CNY"
        }
        val timelineEntries = demo.graph.cashPages.cashAccountPage(timelineCash.id, null, 1_000)
        assertEquals(6, timelineEntries.count { it.note.startsWith("长期账本") })
        assertTrue(timelineEntries.maxOf { it.occurred_at_ms } - timelineEntries.minOf { it.occurred_at_ms } >=
            9L * 365L * 86_400_000L)
        val brokerAccount = snapshot.accounts.single { it.name == "中信证券" }
        val apple = snapshot.positions.single { it.account_id == brokerAccount.id && it.symbol == "AAPL" }
        assertTrue(demo.graph.investments.trade_page(apple.id, null, 100).maxOf { it.revision } >= 4)
        assertTrue(demo.graph.investments.observe_investments(brokerAccount.id, 100,
            InvestmentSection.CLOSED).first().any { it.symbol == "META" })
        assertEquals(realBefore, real.graph.overview.snapshot())
        demo.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
            "Temporary demo account", "must disappear after re-entry", emptyList()))
        assertEquals(13, demo.graph.overview.snapshot().accounts.size)

        val staleDemoCommands = demo.graph.commands
        val staleDemoSettings = demo.graph.settingsWriter
        val staleDemoRevision = demo.graph.settings.observeSettings().first().revision
        sessions.exitDemo()
        assertEquals(DataMode.REAL, sessions.session.value.mode)
        assertTrue(demoDatabaseFiles().isEmpty())
        assertEquals(realBefore, sessions.session.value.graph.overview.snapshot())
        expect(ErrorCode.SESSION_EXPIRED) {
            staleDemoCommands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
                "Stale demo write", "", emptyList()))
        }
        expect(ErrorCode.SESSION_EXPIRED) {
            staleDemoSettings.applyChange(SaveLanguage(staleDemoRevision,AppLanguage.ZH_HANS))
        }

        sessions.enterDemo()
        val restoredDemo = sessions.session.value.graph
        assertEquals(12, restoredDemo.overview.snapshot().accounts.size)
        assertTrue(restoredDemo.overview.snapshot().accounts.none { it.name == "Temporary demo account" })
        sessions.exitDemo()
        assertTrue(demoDatabaseFiles().isEmpty())
    }

    @Test fun demoBuiltInLabelsFollowLanguageAndKeepUserOverride() = runBlocking {
        sessions.enterDemo()
        val graph = sessions.session.value.graph
        var settings = graph.settings.observeSettings().first()
        settings = graph.settingsWriter.applyChange(SaveLanguage(settings.revision, AppLanguage.ENGLISH))
        assertEquals("China Merchants Bank", graph.overview.snapshot().accounts.first().name)
        val first = graph.overview.snapshot().accounts.first()
        graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), first.id, first.revision,
            "My custom broker", first.note, emptyList()))
        settings = graph.settingsWriter.applyChange(SaveLanguage(settings.revision, AppLanguage.ZH_HANS))
        assertEquals("My custom broker", graph.overview.snapshot().accounts.first().name)
        assertTrue(graph.overview.snapshot().accounts.drop(1).any { it.name == "华泰证券" })
        sessions.exitDemo()
    }

    @Test fun clear_requires_current_challenge_preserves_display_preferences_and_expires_old_commands() = runBlocking {
        val active = sessions.session.value
        active.graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
            "Disposable account", "", listOf(CashBalanceChange("USD", 10_000, null, name = "USD"))))
        val before = active.graph.settings.observeSettings().first()
        var stored=active.graph.settingsWriter.applyChange(SaveFinancialSettings(before.revision,
            Currency.of("CNY"),listOf(FxRate(Currency.of("USD"),Currency.of("CNY"),BigDecimal("7.2")))))
        stored=active.graph.settingsWriter.applyChange(SaveLanguage(stored.revision,AppLanguage.ENGLISH))
        stored=active.graph.settingsWriter.applyChange(SaveGainLossColors(stored.revision,GainLossColorScheme.RED_GAIN))
        val navigation = NavigationConfiguration(
            listOf(NavigationItemId.STATISTICS, NavigationItemId.SETTINGS, NavigationItemId.ACCOUNTS,
                NavigationItemId.INVESTMENTS),
            setOf(NavigationItemId.STATISTICS, NavigationItemId.SETTINGS))
        active.graph.settingsWriter.applyChange(SaveNavigationConfiguration(stored.revision, navigation))

        val challenge = sessions.issueClearChallenge()
        assertTrue(challenge.matches(Regex("[A-HJ-NP-Z2-9]{6}")))
        expect(ErrorCode.SESSION_EXPIRED) { sessions.clearRealData(challenge, "WRONG2") }
        assertFalse(active.graph.overview.snapshot().accounts.isEmpty())

        val staleCommands = active.graph.commands
        val staleSettings = active.graph.settingsWriter
        val staleStatistics = active.graph.statistics
        sessions.clearRealData(challenge, challenge)
        val cleared = sessions.session.value
        assertTrue(cleared.id != active.id)
        assertTrue(cleared.graph.overview.snapshot().accounts.isEmpty())
        val preferences = cleared.graph.settings.observeSettings().first()
        assertEquals(null, preferences.baseCurrency)
        assertTrue(preferences.rates.isEmpty())
        assertEquals(AppLanguage.ENGLISH, preferences.language)
        assertEquals(GainLossColorScheme.RED_GAIN, preferences.gainLossColors)
        assertEquals(navigation, preferences.navigation)
        expect(ErrorCode.SESSION_EXPIRED) {
            staleCommands.execute(SaveAccount(UUID.randomUUID().toString(), null, null,
                "Stale real write", "", emptyList()))
        }
        expect(ErrorCode.SESSION_EXPIRED) {
            staleSettings.applyChange(SaveLanguage(before.revision,AppLanguage.SYSTEM))
        }
        expect(ErrorCode.SESSION_EXPIRED) {
            staleStatistics.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
                StatisticsPeriod(StatisticsGranularity.MONTHLY, 2026)))
        }

        sessions.enterDemo()
        val demoPreferences = sessions.session.value.graph.settings.observeSettings().first()
        assertEquals(AppLanguage.ENGLISH, demoPreferences.language)
        assertEquals(GainLossColorScheme.RED_GAIN, demoPreferences.gainLossColors)
        expect(ErrorCode.SESSION_EXPIRED) { sessions.issueClearChallenge() }
        sessions.exitDemo()
        val cleanupGraph = sessions.session.value.graph
        var cleanup = cleanupGraph.settings.observeSettings().first()
        cleanup = cleanupGraph.settingsWriter.applyChange(SaveLanguage(cleanup.revision, AppLanguage.SYSTEM))
        cleanup = cleanupGraph.settingsWriter.applyChange(
            SaveGainLossColors(cleanup.revision, GainLossColorScheme.GREEN_GAIN))
        cleanupGraph.settingsWriter.applyChange(
            SaveNavigationConfiguration(cleanup.revision, NavigationConfiguration()))
        Unit
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

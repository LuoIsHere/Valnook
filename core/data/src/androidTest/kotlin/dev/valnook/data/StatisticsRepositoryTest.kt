package dev.valnook.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.valnook.data.database.*
import dev.valnook.data.repository.RoomStatistics
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.EditInstrumentPrice
import dev.valnook.domain.repository.SaveFinancialSettings
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.math.BigDecimal
import java.time.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.UUID

class StatisticsRepositoryTest {
    private lateinit var db: ValnookDatabase
    private val queries = CopyOnWriteArrayList<String>()
    private val clock = Clock.fixed(Instant.parse("2026-10-02T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private val octoberFirst = LocalDate.of(2026, 10, 1)

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed)
            .setQueryCallback({ sql, _ -> queries.add(sql) }, Executor { it.run() })
            .build()
        db.openHelper.writableDatabase.execSQL(
            "UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=?,source_revision=2 WHERE id=1",
            arrayOf(at(OctoberDay.START), octoberFirst.toEpochDay()))
        db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
        val account = db.accounts().insert_account(AccountEntity(name = "A", note = "", created_at_ms = at(OctoberDay.START),
            updated_at_ms = at(OctoberDay.START)))
        db.cash().insert_cash(CashEntity(account, "CNY", 10_000, 1, at(OctoberDay.START), name = "positive",
            created_at_ms = at(OctoberDay.START)))
        db.cash().insert_cash(CashEntity(account, "CNY", -2_000, 1, at(OctoberDay.START), name = "negative",
            created_at_ms = at(OctoberDay.START)))
        db.operations().insert_operation(OperationEntity("deposit", "OPEN_DEPOSIT", "test", null, null,
            at(OctoberDay.START)))
        db.deposits().insert_deposit(DepositEntity(savings_account_id = account, currency_code = "CNY",
            principal_minor = 5_000, annual_rate_percent_e8 = 0, start_epoch_day = octoberFirst.toEpochDay(),
            end_epoch_day = octoberFirst.plusDays(1).toEpochDay(), expected_interest_minor = 0,
            open_cash_linked = false, open_operation_id = "deposit", created_at_ms = at(OctoberDay.START),
            updated_at_ms = at(OctoberDay.START)))
        val type = db.instruments().insert_type(TypeEntity(name = "Stock", normalized_name = "stock",
            created_at_ms = at(OctoberDay.START), updated_at_ms = at(OctoberDay.START)))
        val instrument = db.instruments().insertInstrument(InstrumentEntity(asset_type_id = type, name = "Example",
            symbol = "EX", currency_code = "CNY", current_price_e5 = 12_000_000, currency_locked = true,
            revision = 1, price_updated_at_ms = clock.millis(), created_at_ms = at(OctoberDay.START),
            updated_at_ms = clock.millis()))
        db.positions().insert_investment(InvestmentEntity(savings_account_id = account, instrument_id = instrument,
            opening_quantity_e8 = 200_000_000, holding_quantity_e8 = 200_000_000, revision = 1,
            created_at_ms = at(OctoberDay.START), updated_at_ms = at(OctoberDay.START), opening_cost_price_e8 = 1,
            opening_at_ms = at(OctoberDay.START), remaining_cost = "0", realized_profit = "0",
            chronology_valid = true, algorithm_version = 3, position_state = "HOLDING",
            last_activity_at_ms = at(OctoberDay.START)))
        db.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrument, price_e5 = 10_000_000,
            currency_code = "CNY", effective_at_ms = at(OctoberDay.END), created_at_ms = clock.millis()))
        db.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrument, price_e5 = 11_000_000,
            currency_code = "CNY", effective_at_ms = clock.millis() - 1, created_at_ms = clock.millis()))
        db.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrument, price_e5 = 12_000_000,
            currency_code = "CNY", effective_at_ms = clock.millis(), created_at_ms = clock.millis()))
        Unit
    }

    @After fun close() = db.close()

    @Test fun valuesUseSignedCashOpenPrincipalAndLastAsOfPrice() = runBlocking {
        val repository = RoomStatistics(db, clock)
        val current = repository.loadCurrent()
        decimal("370", requireNotNull(current.totalAssets))
        decimal("80", requireNotNull(current.availableCash))
        decimal("240", requireNotNull(current.investmentValue))
        assertNull(current.monthlyChange.value)

        val series = repository.loadSeries(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        decimal("200", requireNotNull(series.points[0].value))
        decimal("240", requireNotNull(series.points[1].value))
        assertTrue(series.points[2].future)
        assertNull(series.points[2].value)
    }

    @Test fun cacheHitDoesNotReplayTradesOrCashEntries() = runBlocking {
        val repository = RoomStatistics(db, clock)
        val request = StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10))
        repository.loadSeries(request)
        queries.clear()
        repository.loadSeries(request)
        assertTrue(queries.none { it.contains("FROM investment_trades", ignoreCase = true) })
        assertTrue(queries.none { it.contains("FROM cash_entries", ignoreCase = true) })
    }

    @Test fun concurrentMetricRequestsShareOneSourceReplay() = runBlocking {
        val repository = RoomStatistics(db, clock)
        val period = StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)
        queries.clear()

        coroutineScope {
            StatisticsMetric.entries.map { metric ->
                async(Dispatchers.Default) { repository.loadSeries(StatisticsRequest(metric, period)) }
            }.awaitAll()
        }

        assertEquals(1, queries.count { it.contains("FROM investment_trades", ignoreCase = true) })
        assertEquals(1, queries.count { it.contains("FROM cash_entries", ignoreCase = true) })
    }

    @Test fun localMidnightBelongsToTheNewDayAndMonthlyPointIsABalance() = runBlocking {
        val midnight = octoberFirst.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET balance_minor=11000 WHERE id=1")
        insertCashEntry("midnight", 1, 1_000, midnight)
        db.statistics().invalidate(octoberFirst.plusDays(1).toEpochDay())
        val repository = RoomStatistics(db, clock)
        val daily = repository.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        decimal("330", requireNotNull(daily.points[0].value))
        decimal("380", requireNotNull(daily.points[1].value))
        val monthly = repository.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.MONTHLY, 2026)))
        decimal("380", requireNotNull(monthly.points[9].value))
    }

    @Test fun monthlyChangeIncludesFundingAndCanBeNegative() = runBlocking {
        val september30 = octoberFirst.minusDays(1)
        val baseline = september30.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET created_at_ms=?", arrayOf(baseline))
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(baseline, september30.toEpochDay()))
        val currentDay = octoberFirst.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET balance_minor=-30000 WHERE id=1")
        insertCashEntry("withdrawal", 1, -40_000, currentDay)
        db.statistics().invalidate(september30.toEpochDay())
        val current = RoomStatistics(db, clock).loadCurrent()
        decimal("-30", requireNotNull(current.totalAssets))
        decimal("-110", requireNotNull(current.monthlyChange.value))
        assertEquals(september30, current.monthlyChange.baselineDate)
    }

    @Test fun monthlyChangeIncludesPositiveExternalFunding() = runBlocking {
        val september30 = octoberFirst.minusDays(1)
        val baseline = september30.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET created_at_ms=?", arrayOf(baseline))
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(baseline, september30.toEpochDay()))
        val currentDay = octoberFirst.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET balance_minor=12000 WHERE id=1")
        insertCashEntry("funding", 1, 2_000, currentDay)
        db.statistics().invalidate(september30.toEpochDay())
        val current = RoomStatistics(db, clock).loadCurrent()
        decimal("390", requireNotNull(current.totalAssets))
        decimal("310", requireNotNull(current.monthlyChange.value))
    }

    @Test fun actualCloseRemovesDepositButMaturityAloneDoesNot() = runBlocking {
        val repository = RoomStatistics(db, clock)
        decimal("370", requireNotNull(repository.loadCurrent().totalAssets))
        db.openHelper.writableDatabase.execSQL(
            "UPDATE term_deposits SET status='CLOSED',closed_at_ms=? WHERE id=1",
            arrayOf(clock.millis() - 1))
        db.statistics().invalidate(octoberFirst.plusDays(1).toEpochDay())
        decimal("320", requireNotNull(repository.loadCurrent().totalAssets))
    }

    @Test fun heldPositionWithoutPastPriceKeepsInvestmentAndTotalUnknown() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM instrument_price_history")
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val repository = RoomStatistics(db, clock)
        val current = repository.loadCurrent()
        decimal("80", requireNotNull(current.availableCash))
        assertNull(current.investmentValue)
        assertNull(current.totalAssets)
        val series = repository.loadSeries(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        assertTrue(series.points.take(2).all { it.value == null && !it.future })
    }

    @Test fun clearingDerivedCacheRebuildsTheSameValues() = runBlocking {
        val repository = RoomStatistics(db, clock)
        val request = StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10))
        val before = repository.loadSeries(request)
        db.statistics().clearCache()
        val rebuilt = repository.loadSeries(request)
        assertEquals(before, rebuilt)
    }

    @Test fun displayAndNavigationSettingsReuseNumericCache() = runBlocking {
        val repository = RoomStatistics(db, clock)
        val request = StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10))
        repository.loadSeries(request)
        val revision = requireNotNull(db.statistics().state()).source_revision
        val settings = RoomSettings(db, clock)
        settings.applyChange(dev.valnook.domain.repository.SaveLanguage(1, AppLanguage.ENGLISH))
        settings.applyChange(dev.valnook.domain.repository.SaveGainLossColors(2, GainLossColorScheme.RED_GAIN))
        settings.applyChange(dev.valnook.domain.repository.SaveNavigationConfiguration(3,
            NavigationConfiguration(visible = setOf(NavigationItemId.SETTINGS))))
        queries.clear()
        repository.loadSeries(request)
        assertEquals(revision, requireNotNull(db.statistics().state()).source_revision)
        assertTrue(queries.none { it.contains("FROM investment_trades", ignoreCase = true) })
        assertTrue(queries.none { it.contains("FROM cash_entries", ignoreCase = true) })
    }

    @Test fun financialInvalidationSurvivesRepositoryRecreation() = runBlocking {
        RoomStatistics(db, clock).loadCurrent()
        val before = requireNotNull(db.statistics().state()).source_revision
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val recreated = RoomStatistics(db, clock)
        assertEquals(before + 1, recreated.observeRevision().first())
        recreated.loadSeries(StatisticsRequest(StatisticsMetric.AVAILABLE_CASH,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        assertNull(db.statistics().state()!!.earliest_invalidated_epoch_day)
    }

    @Test fun invalidationPromotesCachedPrefixInsteadOfDiscardingAllHistory() = runBlocking {
        val repository = RoomStatistics(db, clock)
        val request = StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10))
        repository.loadSeries(request)
        val before = requireNotNull(db.statistics().state()).source_revision
        db.statistics().invalidate(octoberFirst.plusDays(1).toEpochDay())
        val after = requireNotNull(db.statistics().state()).source_revision
        assertEquals(before + 1, after)
        val preserved = db.statistics().cached(StatisticsMetric.TOTAL_ASSETS.name,
            octoberFirst.toEpochDay(), octoberFirst.toEpochDay(), after, STATISTICS_RULE_VERSION)
        assertEquals(1, preserved.size)
        repository.loadSeries(request)
        val stillPreserved = db.statistics().cached(StatisticsMetric.TOTAL_ASSETS.name,
            octoberFirst.toEpochDay(), octoberFirst.toEpochDay(), after, STATISTICS_RULE_VERSION)
        assertEquals(preserved.single(), stillPreserved.single())
    }

    @Test fun correctingHistoricalPriceInvalidatesItsControlRangeWithoutChangingLaterPrice() = runBlocking {
        val repository = RoomStatistics(db, clock)
        val request = StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10))
        decimal("200", requireNotNull(repository.loadSeries(request).points[0].value))
        val old = db.statistics().prices().first { it.price_e5 == 10_000_000L }
        RoomFinancialCommands(db, clock).execute(EditInstrumentPrice(UUID.randomUUID().toString(),
            old.id, old.revision, 9_000_000, old.effective_at_ms))
        val corrected = repository.loadSeries(request)
        decimal("180", requireNotNull(corrected.points[0].value))
        decimal("240", requireNotNull(corrected.points[1].value))
    }

    @Test fun allHistoricalPointsUseCurrentManualRateAndMissingRateFallsBackToOne() = runBlocking {
        db.openHelper.writableDatabase.execSQL("UPDATE instruments SET currency_code='USD'")
        db.openHelper.writableDatabase.execSQL("UPDATE instrument_price_history SET currency_code='USD'")
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val repository = RoomStatistics(db, clock)
        decimal("240", requireNotNull(repository.loadCurrent().investmentValue))
        val writer = RoomSettings(db, clock)
        var settings = writer.applyChange(SaveFinancialSettings(1, Currency.of("CNY"), listOf(
            FxRate(Currency.of("USD"), Currency.of("CNY"), BigDecimal("7.2")))))
        decimal("1728", requireNotNull(repository.loadCurrent().investmentValue))
        settings = writer.applyChange(SaveFinancialSettings(settings.revision, Currency.of("CNY"), emptyList()))
        assertTrue(settings.rates.isEmpty())
        decimal("240", requireNotNull(repository.loadCurrent().investmentValue))
    }

    @Test fun clockRollbackBeforeBaselineReturnsUnknownCurrentWithoutChangingSources() = runBlocking {
        val sourceRevision = requireNotNull(db.statistics().state()).source_revision
        val earlier = Clock.fixed(Instant.parse("2026-09-30T04:00:00Z"), clock.zone)
        val current = RoomStatistics(db, earlier).loadCurrent()
        assertNull(current.totalAssets)
        assertNull(current.availableCash)
        assertNull(current.investmentValue)
        assertNull(current.monthlyChange.value)
        assertEquals("baseline_unavailable", current.monthlyChange.reason)
        assertEquals(sourceRevision, requireNotNull(db.statistics().state()).source_revision)
    }

    private enum class OctoberDay { START, END }
    private fun at(day: OctoberDay): Long = when (day) {
        OctoberDay.START -> octoberFirst.atStartOfDay(clock.zone).toInstant().toEpochMilli()
        OctoberDay.END -> octoberFirst.atTime(LocalTime.MAX).atZone(clock.zone).toInstant().toEpochMilli()
    }
    private suspend fun insertCashEntry(operationId: String, cashAccountId: Long, delta: Long, occurred: Long) {
        db.operations().insert_operation(OperationEntity(operationId, "CASH_SET", "statistics-test",
            "CASH_ENTRY", null, occurred))
        db.cash().insert_entry(CashEntryEntity(original_operation_id = operationId,
            savings_account_id = 1, currency_code = "CNY", cash_account_id = cashAccountId,
            source_kind = "CASH_SET", source_id = null, delta_minor = delta,
            occurred_at_ms = occurred, note = "", revision = 1, is_deleted = false,
            created_at_ms = occurred, updated_at_ms = occurred))
    }
    private fun decimal(expected: String, actual: BigDecimal) =
        assertEquals(0, BigDecimal(expected).compareTo(actual))
}

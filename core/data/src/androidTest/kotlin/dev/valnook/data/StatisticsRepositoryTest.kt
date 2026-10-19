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
        val position = db.positions().insert_investment(InvestmentEntity(savings_account_id = account,
            instrument_id = instrument, holding_quantity_e8 = 200_000_000, revision = 1,
            created_at_ms = at(OctoberDay.START), updated_at_ms = at(OctoberDay.START),
            remaining_cost = "200", realized_profit = "0", chronology_valid = true,
            algorithm_version = 4, position_state = "HOLDING", last_activity_at_ms = at(OctoberDay.START)))
        db.operations().insert_operation(OperationEntity("initial-buy", "BUY", "test", null, null,
            at(OctoberDay.START)))
        db.ledger().insert_trade(TradeEntity(investment_id = position, operation_id = "initial-buy",
            direction = Direction.BUY.name, quantity_e8 = 200_000_000, execution_price_e8 = 10_000_000_000,
            amount_minor = 20_000, currency_code = "CNY", cash_linked = false,
            occurred_at_ms = at(OctoberDay.START), created_at_ms = at(OctoberDay.START),
            updated_at_ms = at(OctoberDay.START)))
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

    @Test fun creditBalanceLowersTotalButIsExcludedFromAvailableCashHistory() = runBlocking {
        db.credit().upsert(CreditAccountProfileEntity(2, 100_000, 12,
            "AFTER_STATEMENT_DAYS", 20, null))
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val repository = RoomStatistics(db, clock)
        val current = repository.loadCurrent()
        decimal("370", requireNotNull(current.totalAssets))
        decimal("100", requireNotNull(current.availableCash))
        val total = repository.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        val cash = repository.loadSeries(StatisticsRequest(StatisticsMetric.AVAILABLE_CASH,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        decimal("330", requireNotNull(total.points.first().value))
        decimal("100", requireNotNull(cash.points.first().value))
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

    @Test fun heldPositionWithoutManualPriceIsValuedAtItsExecutionPrice() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM instrument_price_history")
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val repository = RoomStatistics(db, clock)
        val current = repository.loadCurrent()
        decimal("80", requireNotNull(current.availableCash))
        decimal("240", requireNotNull(current.investmentValue))
        decimal("370", requireNotNull(current.totalAssets))
        val period = StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)
        val investment = repository.loadSeries(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE, period))
        decimal("200", requireNotNull(investment.points[0].value))
        decimal("240", requireNotNull(investment.points[1].value))
        val total = repository.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS, period))
        decimal("330", requireNotNull(total.points[0].value))
        decimal("40", requireNotNull(total.points[1].change))
    }

    @Test fun backdatedTradeBeforeFirstManualPriceLeavesNoGapAndKeepsDailyChange() = runBlocking {
        // The instrument is priced today, but its buy was recorded for an earlier day.
        // 标的今天才有手动价格，但买入记录在更早的日期。
        db.openHelper.writableDatabase.execSQL("DELETE FROM instrument_price_history")
        db.statistics().insertPrice(InstrumentPriceEntity(instrument_id = 1, price_e5 = 12_000_000,
            currency_code = "CNY", effective_at_ms = clock.millis(), created_at_ms = clock.millis()))
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val series = RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        assertTrue(series.points.take(2).all { it.reliable && it.value != null })
        decimal("330", requireNotNull(series.points[0].value))
        decimal("370", requireNotNull(series.points[1].value))
        decimal("40", requireNotNull(series.points[1].change))
        decimal("12.12", requireNotNull(series.points[1].changePercent))
    }

    @Test fun manualPriceWinsATimestampTieWithAnExecution() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM instrument_price_history")
        db.statistics().insertPrice(InstrumentPriceEntity(instrument_id = 1, price_e5 = 9_000_000,
            currency_code = "CNY", effective_at_ms = at(OctoberDay.START), created_at_ms = clock.millis()))
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val series = RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        decimal("180", requireNotNull(series.points[0].value))
    }

    @Test fun olderRuleCacheIsDiscardedAndRecomputedOnce() = runBlocking {
        db.openHelper.writableDatabase.execSQL("DELETE FROM instrument_price_history")
        val revision = db.statistics().state()!!.source_revision
        // A rule-2 install cached the unpriced day as unknown.
        // 旧规则（版本 2）把缺价的那天缓存成了未知值。
        db.openHelper.writableDatabase.execSQL(
            "UPDATE statistics_state SET rule_version=2,earliest_invalidated_epoch_day=NULL WHERE id=1")
        db.statistics().insertCache(StatisticsMetric.entries.map { metric ->
            StatisticsCacheEntity(metric.name, octoberFirst.toEpochDay(), null, reliable = false,
                source_revision = revision, rule_version = 2, computed_at_ms = clock.millis())
        })
        val series = RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        decimal("200", requireNotNull(series.points[0].value))
        val state = db.statistics().state()!!
        assertEquals(STATISTICS_RULE_VERSION, state.rule_version)
        assertEquals(revision + 1, state.source_revision)
        assertEquals("200", db.statistics().cached(StatisticsMetric.INVESTMENT_VALUE.name, octoberFirst.toEpochDay(),
            octoberFirst.toEpochDay(), state.source_revision, STATISTICS_RULE_VERSION).single().value_decimal)
        val upgradedRevision = state.source_revision
        RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        assertEquals(upgradedRevision, db.statistics().state()!!.source_revision)
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

    @Test fun invalidatedSuffixCannotBePromotedByALaterEdit() = runBlocking {
        val metric = StatisticsMetric.TOTAL_ASSETS.name
        val request = StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10))
        val repository = RoomStatistics(db, clock)
        repository.loadSeries(request)
        val originalRevision = requireNotNull(db.statistics().state()).source_revision

        db.statistics().invalidate(octoberFirst.toEpochDay())
        val firstRevision = requireNotNull(db.statistics().state()).source_revision
        assertTrue(db.statistics().cached(metric, octoberFirst.toEpochDay(),
            octoberFirst.plusDays(2).toEpochDay(), originalRevision, STATISTICS_RULE_VERSION).isEmpty())

        db.statistics().insertCache(listOf(StatisticsCacheEntity(metric,
            octoberFirst.plusDays(2).toEpochDay(), "999", true, firstRevision,
            STATISTICS_RULE_VERSION, clock.millis())))
        db.statistics().markValid(firstRevision)
        db.statistics().invalidate(octoberFirst.plusDays(2).toEpochDay())
        val secondRevision = requireNotNull(db.statistics().state()).source_revision

        assertTrue(db.statistics().cached(metric, octoberFirst.toEpochDay(),
            octoberFirst.plusDays(1).toEpochDay(), secondRevision, STATISTICS_RULE_VERSION).isEmpty())
    }

    @Test fun depositExistsFromBusinessStartDateEvenWhenEnteredLater() = runBlocking {
        db.openHelper.writableDatabase.execSQL(
            "UPDATE term_deposits SET created_at_ms=?,updated_at_ms=? WHERE id=1",
            arrayOf(clock.millis(), clock.millis()))
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val series = RoomStatistics(db, clock).loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10)))
        decimal("330", requireNotNull(series.points[0].value))
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

    @Test fun daily_changes_use_each_metrics_previous_day_and_missing_baseline_counts_from_zero() = runBlocking {
        val repository=RoomStatistics(db,clock)
        val series=StatisticsMetric.entries.associateWith { repository.loadSeries(StatisticsRequest(it,
            StatisticsPeriod(StatisticsGranularity.DAILY,2026,10))) }
        series.values.forEach {
            assertNull(it.points[0].comparisonValue)
            decimal(it.points[0].value!!.toPlainString(),requireNotNull(it.points[0].change))
            decimal("100.00",requireNotNull(it.points[0].changePercent))
            assertNull(it.points[2].change)
        }
        decimal("40",requireNotNull(series.getValue(StatisticsMetric.TOTAL_ASSETS).points[1].change))
        decimal("40",requireNotNull(series.getValue(StatisticsMetric.INVESTMENT_VALUE).points[1].change))
        decimal("0",requireNotNull(series.getValue(StatisticsMetric.AVAILABLE_CASH).points[1].change))
        decimal("20.00",requireNotNull(series.getValue(StatisticsMetric.INVESTMENT_VALUE).points[1].changePercent))
    }
    @Test fun first_visible_day_and_month_query_an_offscreen_calendar_baseline() = runBlocking {
        val baseline=LocalDate.of(2026,9,1)
        db.openHelper.writableDatabase.execSQL("UPDATE statistics_state SET baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(baseline.atStartOfDay(clock.zone).toInstant().toEpochMilli(),baseline.toEpochDay()))
        val repository=RoomStatistics(db,clock)
        val day=repository.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.DAILY,2026,10))).points.first()
        decimal("80",requireNotNull(day.comparisonValue))
        decimal("250",requireNotNull(day.change))
        val month=repository.loadSeries(StatisticsRequest(StatisticsMetric.TOTAL_ASSETS,
            StatisticsPeriod(StatisticsGranularity.MONTHLY,2026))).points[9]
        decimal("80",requireNotNull(month.comparisonValue))
        decimal("290",requireNotNull(month.change))
    }

    @Test fun prehistory_is_zero_for_daily_and_monthly_charts_without_fabricating_comparisons() = runBlocking {
        val repository=RoomStatistics(db,clock)
        StatisticsMetric.entries.forEach { metric ->
            val daily=repository.loadSeries(StatisticsRequest(metric,StatisticsPeriod(StatisticsGranularity.DAILY,2026,9)))
            assertEquals(30,daily.points.size)
            assertTrue(daily.points.all{it.value==BigDecimal.ZERO && !it.reliable && !it.future})
            assertTrue(daily.points.all{it.change==null && it.changePercent==null})
            val monthly=repository.loadSeries(StatisticsRequest(metric,StatisticsPeriod(StatisticsGranularity.MONTHLY,2026)))
            assertTrue(monthly.points.take(9).all{it.value==BigDecimal.ZERO})
            assertNull(monthly.points[9].comparisonValue)
            assertTrue(monthly.points.drop(10).all{it.future && it.value==null})
        }
        assertTrue(db.statistics().cached("TOTAL_ASSETS",LocalDate.of(2026,9,1).toEpochDay(),
            LocalDate.of(2026,9,30).toEpochDay(),2,db.statistics().state()!!.rule_version).isEmpty())
        db.openHelper.writableDatabase.execSQL("DELETE FROM instrument_price_history")
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val executionPriced=repository.loadSeries(StatisticsRequest(StatisticsMetric.INVESTMENT_VALUE,
            StatisticsPeriod(StatisticsGranularity.DAILY,2026,10)))
        decimal("200",requireNotNull(executionPriced.points.first().value))
    }

    @Test fun current_chart_point_matches_live_book_and_is_not_cached_as_historical_close() = runBlocking {
        val future = clock.instant().plusSeconds(3 * 86400).toEpochMilli()
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET balance_minor=15000 WHERE id=1")
        insertCashEntry("future-posted", 1, 5_000, future)
        db.statistics().invalidate(octoberFirst.toEpochDay())
        val overview = dev.valnook.domain.calculation.AssetValuation.calculate(
            dev.valnook.data.repository.RoomOverview(db).snapshot())
        val repo = RoomStatistics(db, clock)
        val request = StatisticsRequest(StatisticsMetric.TOTAL_ASSETS, StatisticsPeriod(StatisticsGranularity.DAILY, 2026, 10))
        val series = repo.loadSeries(request)
        decimal(overview.total.amount.toPlainString(), requireNotNull(series.points[1].value))
        decimal("330", requireNotNull(series.points[0].value))
        assertTrue(db.statistics().cached("TOTAL_ASSETS", octoberFirst.plusDays(1).toEpochDay(),
            octoberFirst.plusDays(1).toEpochDay(), db.statistics().state()!!.source_revision, STATISTICS_RULE_VERSION).isEmpty())
        val nextDayClock = Clock.offset(clock, Duration.ofDays(1))
        val next = RoomStatistics(db, nextDayClock).loadSeries(request)
        decimal("370", requireNotNull(next.points[1].value))
        decimal("420", requireNotNull(next.points[2].value))
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

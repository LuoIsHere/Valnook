package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.calculation.StatisticsCalendar
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.repository.StatisticsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth

/** Replays each source list once per requested range. cash_entries is canonical; cash_movements is audit only. */
class RoomStatistics(
    private val db: ValnookDatabase,
    private val clock: Clock,
    private val canCommit: () -> Boolean = { true }
) : StatisticsRepository {
    private val replayMutex = Mutex()

    override fun observeRevision(): Flow<Long> = db.statistics().observeRevision().map { it ?: 0L }

    override suspend fun loadSeries(request: StatisticsRequest): StatisticsSeries = withContext(Dispatchers.IO) {
        replayMutex.withLock {
            val today = LocalDate.now(clock)
            val dates = StatisticsCalendar.slots(request.period, today)
            fun comparisonDate(date: LocalDate) = if (request.period.granularity == StatisticsGranularity.DAILY)
                date.minusDays(1) else YearMonth.from(date).minusMonths(1).atEndOfMonth()
            // Include the off-screen baseline: do not compare to the previous non-null observation.
            // 包含可视范围之外的比较基准；不能跳过缺失日期改用更早的点。
            val actualDates = (dates + dates.map(::comparisonDate)).distinct().filter { !it.isAfter(today) }
            val header = readHeader()
            val baselineDate = instantDate(header.state.baseline_at_ms)
            val reliableDates = actualDates.filter { it >= baselineDate && it != today }
            val reusableDates = header.state.earliest_invalidated_epoch_day?.let { invalidatedFrom ->
                reliableDates.filter { it.toEpochDay() < invalidatedFrom }
            } ?: reliableDates
            val cached = cacheFor(request.metric, reusableDates, header.state.source_revision, header.state.rule_version)
            val missing = reliableDates.filterNot(cached::containsKey)
            val computed = if (missing.isEmpty()) emptyMap() else compute(readSource(header, missing), missing)
            if (computed.isNotEmpty()) commit(header.state.source_revision, computed)
            val live = if (today in actualDates && today >= baselineDate) mapOf(today to readCurrent(header).point(request.metric)) else emptyMap()
            val values = cached + computed.mapValues { (_, value) -> value.point(request.metric) } + live
            StatisticsSeries(request.metric, request.period, header.settings.baseCurrency, dates.map { date ->
                when {
                    date.isAfter(today) -> StatisticsPoint(date, null, reliable = false, future = true)
                    // Display padding only: do not cache it or use it as a financial comparison baseline.
                    // 仅补齐历史起点之前的图表，不写入缓存或用于变化/百分比的真实基准。
                    date < baselineDate -> StatisticsPoint(date, BigDecimal.ZERO, reliable = false)
                    else -> StatisticsPoint(date, values[date]?.value, reliable = values[date]?.value != null,
                        comparisonValue = values[comparisonDate(date)]?.value, complete = values[date]?.complete ?: true)
                }
            })
        }
    }

    override suspend fun loadCurrent(): CurrentStatistics = withContext(Dispatchers.IO) {
        replayMutex.withLock {
            val today = LocalDate.now(clock)
            val previousMonthEnd = YearMonth.from(today).minusMonths(1).atEndOfMonth()
            val header = readHeader()
            val baselineDate = instantDate(header.state.baseline_at_ms)
            if (today < baselineDate) {
                return@withLock CurrentStatistics(null, null, null,
                    header.settings.baseCurrency, MonthlyAssetChange(null, header.settings.baseCurrency,
                        null, "baseline_unavailable"))
            }
            val neededDates = listOf(previousMonthEnd).filter { it >= baselineDate }
            val cached = if (header.state.earliest_invalidated_epoch_day == null)
                cachedValues(neededDates, header.state.source_revision, header.state.rule_version) else emptyMap()
            val missing = neededDates.filterNot(cached::containsKey)
            val computed = if (missing.isEmpty()) emptyMap() else compute(readSource(header, missing), missing)
            if (computed.isNotEmpty()) commit(header.state.source_revision, computed)
            val values = cached + computed
            // Same values as today's chart point: an amount left without a rate is shown and marked incomplete.
            val current = readCurrent(header)
            val change = if (previousMonthEnd < baselineDate) {
                MonthlyAssetChange(null, header.settings.baseCurrency, null, "baseline_unavailable")
            } else {
                val previous = values[previousMonthEnd]?.total
                MonthlyAssetChange(previous?.let { current.total?.subtract(it) }, header.settings.baseCurrency,
                    previousMonthEnd, if (previous == null || current.total == null) "valuation_unavailable" else null)
            }
            val previous = values[previousMonthEnd]
            CurrentStatistics(current.total, current.cash, current.investment,
                header.settings.baseCurrency, change, complete = current.complete &&
                    (previousMonthEnd < baselineDate || previous == null || previous.totalComplete))
        }
    }

    // Today is the live book, including entries already posted with a future date.
    // 今天与账户汇总共用估值规则（包含已入账的未来日期记录）；不缓存为历史日末值。
    private suspend fun readCurrent(header: Header): Values = db.withTransaction {
        if (db.statistics().state()?.source_revision != header.state.source_revision)
            throw DomainException(ErrorCode.STALE_RECORD)
        val overview = AssetValuation.calculate(RoomOverview(db).snapshot())
        // Without a base currency nothing is known; a missing exchange rate leaves an incomplete value.
        fun known(total: ConvertedTotal) = total.amount.takeIf { total.currency != null }
        Values(known(overview.total), known(overview.availableCash), known(overview.investmentValue),
            overview.total.complete, overview.availableCash.complete, overview.investmentValue.complete)
    }

    private suspend fun readHeader(): Header = db.withTransaction {
        val dao = db.statistics()
        var state = dao.state()
        if (state == null) {
            if (!canCommit()) throw DomainException(ErrorCode.SESSION_EXPIRED)
            val now = clock.millis()
            state = StatisticsStateEntity(source_revision = 1, baseline_at_ms = now,
                earliest_invalidated_epoch_day = instantDate(now).toEpochDay())
            dao.insertState(state)
            dao.captureCashBaseline()
            dao.captureDepositBaseline()
            dao.capturePositionBaseline()
        }
        if (state.rule_version < STATISTICS_RULE_VERSION) {
            if (!canCommit()) throw DomainException(ErrorCode.SESSION_EXPIRED)
            // Values cached under an older rule (such as unpriced gaps) are recomputed from the baseline.
            // 旧规则下缓存的值（例如缺价导致的空值）从统计起点开始重算。
            dao.clearCache()
            dao.upgradeRule(STATISTICS_RULE_VERSION, instantDate(state.baseline_at_ms).toEpochDay())
            state = dao.state()
        }
        val settings = settingsModel(dao.settings(), dao.rates())
        Header(requireNotNull(state), settings,
            dev.valnook.domain.fx.FxRateResolver(settings, db.fxContext(settings.baseCurrency?.code)))
    }

    private suspend fun readSource(header: Header, dates: List<LocalDate>): Source = db.withTransaction {
        val dao = db.statistics()
        val cash = dao.currentCash()
        val deposits = dao.deposits()
        val positions = dao.positions()
        // One read of the requested days' stored rates for the currencies this source holds, inverted once per row.
        val base = header.settings.baseCurrency?.code
        val quotes = (cash.map { it.currency_code } + deposits.map { it.currency_code } + positions.map { it.currency_code })
            .distinct().filter { it != base }
        val daily = if (base == null || quotes.isEmpty() || dates.isEmpty()) emptyMap() else db.fx()
            .historyFor(base, quotes, dates.minOf { it.toEpochDay() }, dates.maxOf { it.toEpochDay() })
            .groupBy { it.quote_currency }.mapValues { (_, rows) -> rows.associate { row ->
                row.local_day to BigDecimal.ONE.divide(BigDecimal(row.rate), java.math.MathContext.DECIMAL128)
            } }
        Source(header.state, cash, dao.creditProfiles(), dao.cashEntries(), deposits, positions,
            dao.trades(), dao.prices(), header.settings, header.rates, daily)
    }

    private suspend fun cacheFor(metric: StatisticsMetric, dates: List<LocalDate>, revision: Long,
        ruleVersion: Int): Map<LocalDate, Point> {
        if (dates.isEmpty()) return emptyMap()
        val rows = db.statistics().cached(metric.name, dates.minOf { it.toEpochDay() },
            dates.maxOf { it.toEpochDay() }, revision, ruleVersion).associateBy { it.epoch_day }
        return dates.mapNotNull { date ->
            rows[date.toEpochDay()]?.takeIf { instantDate(it.computed_at_ms) > date }
                ?.let { row -> date to Point(row.value_decimal?.toBigDecimal(), row.complete) }
        }.toMap()
    }

    private suspend fun cachedValues(dates: List<LocalDate>, revision: Long,
        ruleVersion: Int): Map<LocalDate, Values> {
        if (dates.isEmpty()) return emptyMap()
        val perMetric = StatisticsMetric.entries.associateWith { cacheFor(it, dates, revision, ruleVersion) }
        if (perMetric.values.any { it.size != dates.size }) return emptyMap()
        fun point(metric: StatisticsMetric, date: LocalDate) = perMetric.getValue(metric).getValue(date)
        return dates.associateWith { date ->
            val total = point(StatisticsMetric.TOTAL_ASSETS, date)
            val cash = point(StatisticsMetric.AVAILABLE_CASH, date)
            val investment = point(StatisticsMetric.INVESTMENT_VALUE, date)
            Values(total.value, cash.value, investment.value, total.complete, cash.complete, investment.complete)
        }
    }

    private fun compute(source: Source, dates: List<LocalDate>): Map<LocalDate, Values> {
        val base = source.settings.baseCurrency?.code ?: return dates.associateWith { Values(null, null, null) }
        val entriesByCash = source.entries.groupBy { it.cash_account_id }
        val tradesByPosition = source.trades.groupBy { it.investment_id }
        val observationsByInstrument = priceObservations(source)
        return dates.distinct().associateWith { date ->
            val cutoff = cutoff(date)
            val day = date.toEpochDay()
            // That day's stored rate, else today's rate (current or latest stored); null leaves the amount out.
            fun rate(code: String): BigDecimal? = if (code == base) BigDecimal.ONE
                else source.daily[code]?.get(day) ?: source.rates.today(code)
            var cashComplete = true
            var otherComplete = true
            var investmentComplete = true
            val creditIds = source.creditProfiles.asSequence().map { it.account_id }.toHashSet()
            var availableCash = BigDecimal.ZERO
            val balanceAccounts = source.cash.fold(BigDecimal.ZERO) { total, account ->
                val after = entriesByCash[account.id].orEmpty().asSequence()
                    .filter { it.occurred_at_ms > cutoff }.sumOf { it.delta_minor }
                val minor = account.balance_minor - after
                val available = account.id !in creditIds && account.include_in_available_cash
                val rate = rate(account.currency_code)
                if (rate == null) {
                    if (minor != 0L) { otherComplete = false; if (available) cashComplete = false }
                    return@fold total
                }
                val value = major(minor, account.currency_code).multiply(rate)
                if (available) availableCash += value
                total + value
            }
            val deposits = source.deposits.asSequence().filter { deposit ->
                deposit.start_epoch_day <= day &&
                    (deposit.closed_at_ms == null || deposit.closed_at_ms > cutoff)
            }.fold(BigDecimal.ZERO) { total, deposit ->
                val rate = rate(deposit.currency_code)
                if (rate == null) { otherComplete = false; total }
                else total + major(deposit.principal_minor, deposit.currency_code).multiply(rate)
            }
            var investment = BigDecimal.ZERO
            var investmentKnown = true
            source.positions.forEach { position ->
                var quantity = 0L
                tradesByPosition[position.id].orEmpty().forEach { trade ->
                    if (trade.occurred_at_ms <= cutoff) quantity += if (trade.direction == Direction.BUY.name)
                        trade.quantity_e8 else -trade.quantity_e8
                }
                if (quantity > 0) {
                    val price = observationsByInstrument[position.instrument_id].orEmpty()
                        .lastOrNull { it.atMs <= cutoff }
                    if (price == null) investmentKnown = false else {
                        val original = BigDecimal.valueOf(quantity).movePointLeft(8).multiply(price.price)
                        // Keep source precision through aggregation. Presentation applies currency rounding.
                        val rate = rate(position.currency_code)
                        if (rate == null) { if (original.signum() != 0) investmentComplete = false }
                        else investment += original.multiply(rate)
                    }
                }
            }
            val totalComplete = otherComplete && investmentComplete
            if (investmentKnown) Values(balanceAccounts + deposits + investment, availableCash, investment,
                totalComplete, cashComplete, investmentComplete)
            else Values(null, availableCash, null, totalComplete, cashComplete, investmentComplete)
        }
    }

    // Manual prices and trade executions are both valuation observations; the latest at the cutoff wins,
    // so a position is never unpriced once its first trade exists. A manual price wins a timestamp tie.
    // 手动价格与成交价都是估值观测，取截止时点前最新的一条；持仓一旦有交易就不会缺价。时间相同时以手动价格为准。
    private fun priceObservations(source: Source): Map<Long, List<PriceObservation>> {
        val instrumentByPosition = source.positions.associate { it.id to it.instrument_id }
        val manual = source.prices.map { PriceObservation(it.instrument_id, it.effective_at_ms,
            BigDecimal.valueOf(it.price_e5).movePointLeft(5), manual = true) }
        val executions = source.trades.mapNotNull { trade -> instrumentByPosition[trade.investment_id]?.let { instrument ->
            PriceObservation(instrument, trade.occurred_at_ms,
                BigDecimal.valueOf(trade.execution_price_e8).movePointLeft(8), manual = false)
        } }
        return (manual + executions).groupBy { it.instrumentId }.mapValues { (_, values) ->
            values.sortedWith(compareBy<PriceObservation>({ it.atMs }, { it.manual }))
        }
    }

    private suspend fun commit(revision: Long, values: Map<LocalDate, Values>) {
        if (values.isEmpty()) return
        db.withTransaction {
            if (!canCommit()) throw DomainException(ErrorCode.SESSION_EXPIRED)
            val state = db.statistics().state()
            if (state?.source_revision != revision) throw DomainException(ErrorCode.STALE_RECORD)
            val now = clock.millis()
            db.statistics().insertCache(values.flatMap { (date, value) ->
                StatisticsMetric.entries.map { metric -> StatisticsCacheEntity(metric.name, date.toEpochDay(),
                    value.forMetric(metric)?.stripTrailingZeros()?.toPlainString(),
                    reliable = value.forMetric(metric) != null, source_revision = revision,
                    rule_version = state.rule_version, computed_at_ms = now, complete = value.completeFor(metric)) }
            })
            db.statistics().markValid(revision)
        }
    }

    private fun cutoff(date: LocalDate): Long = if (date == LocalDate.now(clock)) clock.millis() else
        date.atTime(LocalTime.MAX).atZone(clock.zone).toInstant().toEpochMilli()

    private fun instantDate(milliseconds: Long): LocalDate = Instant.ofEpochMilli(milliseconds)
        .atZone(clock.zone).toLocalDate()

    private fun major(minor: Long, currencyCode: String): BigDecimal =
        BigDecimal.valueOf(minor).movePointLeft(Currency.of(currencyCode).fraction_digits)

    private data class Source(
        val state: StatisticsStateEntity,
        val cash: List<CashEntity>,
        val creditProfiles: List<CreditAccountProfileEntity>,
        val entries: List<CashEntryEntity>,
        val deposits: List<DepositEntity>,
        val positions: List<StatisticsPositionRow>,
        val trades: List<TradeEntity>,
        val prices: List<InstrumentPriceEntity>,
        val settings: AppSettings,
        val rates: dev.valnook.domain.fx.FxRateResolver,
        /** Stored rates per quote and local day, already in app direction (1 quote = x base). */
        val daily: Map<String, Map<Long, BigDecimal>>
    )

    private data class Header(val state: StatisticsStateEntity, val settings: AppSettings,
        val rates: dev.valnook.domain.fx.FxRateResolver)

    private data class PriceObservation(val instrumentId: Long, val atMs: Long, val price: BigDecimal, val manual: Boolean)

    private data class Point(val value: BigDecimal?, val complete: Boolean)

    private data class Values(val total: BigDecimal?, val cash: BigDecimal?, val investment: BigDecimal?,
        val totalComplete: Boolean = true, val cashComplete: Boolean = true, val investmentComplete: Boolean = true) {
        fun forMetric(metric: StatisticsMetric): BigDecimal? = when (metric) {
            StatisticsMetric.TOTAL_ASSETS -> total
            StatisticsMetric.AVAILABLE_CASH -> cash
            StatisticsMetric.INVESTMENT_VALUE -> investment
        }
        fun completeFor(metric: StatisticsMetric): Boolean = when (metric) {
            StatisticsMetric.TOTAL_ASSETS -> totalComplete
            StatisticsMetric.AVAILABLE_CASH -> cashComplete
            StatisticsMetric.INVESTMENT_VALUE -> investmentComplete
        }
        fun point(metric: StatisticsMetric) = Point(forMetric(metric), completeFor(metric))
        val complete: Boolean get() = totalComplete && cashComplete && investmentComplete
    }
}

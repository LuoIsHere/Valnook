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

    // Computed on each read from this account's own rows: the daily cache holds whole-book values only. It touches no
    // cache, so it does not wait for the whole-book replay lock either.
    // 每次只用该账户自己的数据现算：每日缓存只存全部账户的值。它不读写缓存，所以也不占用全局回放锁。
    override suspend fun loadAccountTrend(accountId: Long): List<StatisticsPoint> = withContext(Dispatchers.IO) {
        val today = LocalDate.now(clock)
        val dates = generateSequence(today.minusMonths(3)) { it.plusDays(1) }.takeWhile { it <= today }.toList()
        val header = readHeader()
        val baselineDate = instantDate(header.state.baseline_at_ms)
        val history = dates.filter { it >= baselineDate && it != today }
        val computed = if (history.isEmpty()) emptyMap() else compute(readSource(header, history, accountId), history, accountId)
        val live = if (today >= baselineDate) mapOf(today to readCurrentAccount(header, accountId)) else emptyMap()
        val values = computed.mapValues { (_, value) -> value.point(StatisticsMetric.TOTAL_ASSETS) } + live
        dates.map { date ->
            if (date < baselineDate) StatisticsPoint(date, BigDecimal.ZERO, reliable = false)
            else StatisticsPoint(date, values[date]?.value, reliable = values[date]?.value != null,
                complete = values[date]?.complete ?: true)
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

    private suspend fun readCurrentAccount(header: Header, accountId: Long): Point = db.withTransaction {
        if (db.statistics().state()?.source_revision != header.state.source_revision)
            throw DomainException(ErrorCode.STALE_RECORD)
        val total = AssetValuation.calculate(RoomOverview(db).snapshot()).accounts.firstOrNull { it.account.id == accountId }?.total
        Point(total?.amount?.takeIf { total.currency != null }, total?.complete ?: true)
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

    /** The whole book, or with [accountId] what that account's values need (see [compute]). */
    private suspend fun readSource(header: Header, dates: List<LocalDate>, accountId: Long? = null): Source = db.withTransaction {
        val dao = db.statistics()
        val cash = if (accountId == null) dao.currentCash() else dao.currentCashOf(accountId)
        val deposits = if (accountId == null) dao.deposits() else dao.depositsOf(accountId)
        val positions = if (accountId == null) dao.positions() else dao.positionsOfInstrumentsHeldBy(accountId)
        // One read of the requested days' stored rates for the currencies this source holds, inverted once per row.
        val base = header.settings.baseCurrency?.code
        val quotes = (cash.map { it.currency_code } + deposits.map { it.currency_code } + positions.map { it.currency_code })
            .distinct().filter { it != base }
        val daily = if (base == null || quotes.isEmpty() || dates.isEmpty()) emptyMap() else db.fx()
            .historyFor(base, quotes, dates.minOf { it.toEpochDay() }, dates.maxOf { it.toEpochDay() })
            .groupBy { it.quote_currency }.mapValues { (_, rows) -> rows.associate { row ->
                row.local_day to BigDecimal.ONE.divide(BigDecimal(row.rate), java.math.MathContext.DECIMAL128)
            } }
        Source(header.state, cash, dao.creditProfiles(),
            if (accountId == null) dao.cashEntries() else dao.cashEntriesOf(accountId), deposits, positions,
            if (accountId == null) dao.trades() else dao.tradesOfInstrumentsHeldBy(accountId),
            if (accountId == null) dao.prices() else dao.pricesOfInstrumentsHeldBy(accountId),
            header.settings, header.rates, daily)
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

    /** The whole book, or with [accountId] only what that account holds. Prices still come from every account's trades. */
    private fun compute(source: Source, dates: List<LocalDate>, accountId: Long? = null): Map<LocalDate, Values> {
        val base = source.settings.baseCurrency?.code ?: return dates.associateWith { Values(null, null, null) }
        val cashAccounts = source.cash.filter { accountId == null || it.savings_account_id == accountId }
        val heldDeposits = source.deposits.filter { accountId == null || it.savings_account_id == accountId }
        val heldPositions = source.positions.filter { accountId == null || it.savings_account_id == accountId }
        // Each day only looks up how much moved after it and how much was held by then, rather than going through
        // every entry and trade again: sums over time, worked out once.
        val entriesByCash = source.entries.groupBy { it.cash_account_id }.mapValues { (_, entries) ->
            entries.sortedBy { it.occurred_at_ms }.let { sorted ->
                Timeline(sorted.map { it.occurred_at_ms }, sorted.map { it.delta_minor })
            }
        }
        val tradesByPosition = source.trades.groupBy { it.investment_id }.mapValues { (_, trades) ->
            trades.sortedBy { it.occurred_at_ms }.let { sorted ->
                Timeline(sorted.map { it.occurred_at_ms }, sorted.map { trade ->
                    // Dividends move no units.
                    when (trade.direction) {
                        Direction.BUY.name -> trade.quantity_e8
                        Direction.SELL.name -> -trade.quantity_e8
                        else -> 0L
                    }
                })
            }
        }
        val observationsByInstrument = priceObservations(source)
        val creditIds = source.creditProfiles.asSequence().map { it.account_id }.toHashSet()
        return dates.distinct().associateWith { date ->
            val cutoff = cutoff(date)
            val day = date.toEpochDay()
            // That day's stored rate, else today's rate (current or latest stored); null leaves the amount out.
            fun rate(code: String): BigDecimal? = if (code == base) BigDecimal.ONE
                else source.daily[code]?.get(day) ?: source.rates.today(code)
            var cashComplete = true
            var otherComplete = true
            var investmentComplete = true
            var availableCash = BigDecimal.ZERO
            val balanceAccounts = cashAccounts.fold(BigDecimal.ZERO) { total, account ->
                val after = entriesByCash[account.id]?.after(cutoff) ?: 0L
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
            val deposits = heldDeposits.asSequence().filter { deposit ->
                deposit.start_epoch_day <= day &&
                    (deposit.closed_at_ms == null || deposit.closed_at_ms > cutoff)
            }.fold(BigDecimal.ZERO) { total, deposit ->
                val rate = rate(deposit.currency_code)
                if (rate == null) { otherComplete = false; total }
                else total + major(deposit.principal_minor, deposit.currency_code).multiply(rate)
            }
            var investment = BigDecimal.ZERO
            var investmentKnown = true
            heldPositions.forEach { position ->
                val quantity = tradesByPosition[position.id]?.upTo(cutoff) ?: 0L
                if (quantity > 0) {
                    val price = priceAt(observationsByInstrument[position.instrument_id].orEmpty(), cutoff)
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

    // Manual prices and buy or sell executions are valuation observations, so a position is never unpriced once
    // its first trade exists. A dividend has no price and is not one. Ordered by time, a manual price after an
    // execution at the same moment.
    // 手动价格与买卖成交价都是估值观测，持仓一旦有交易就不会缺价；派息没有价格，不算观测。按时间排序，同一时刻手动价格在后。
    private fun priceObservations(source: Source): Map<Long, List<PriceObservation>> {
        val instrumentByPosition = source.positions.associate { it.id to it.instrument_id }
        val manual = source.prices.map { PriceObservation(it.instrument_id, it.effective_at_ms,
            BigDecimal.valueOf(it.price_e5).movePointLeft(5), manual = true, day = instantDate(it.effective_at_ms)) }
        val executions = source.trades.mapNotNull { trade ->
            if (trade.direction != Direction.BUY.name && trade.direction != Direction.SELL.name) return@mapNotNull null
            instrumentByPosition[trade.investment_id]?.let { instrument ->
                PriceObservation(instrument, trade.occurred_at_ms,
                    BigDecimal.valueOf(trade.execution_price_e8).movePointLeft(8), manual = false,
                    day = instantDate(trade.occurred_at_ms))
            }
        }
        return (manual + executions).groupBy { it.instrumentId }.mapValues { (_, values) ->
            values.sortedWith(compareBy<PriceObservation>({ it.atMs }, { it.manual }))
        }
    }

    /**
     * The price at [cutoff]: from the latest day with an observation by then, that day's last manual price, else its
     * last execution. One price a day, as the instrument's trend draws it, and a manual update beats a trade's price.
     * 截止时点的价格：取截止前最近一个有观测的日子；当天有手动价格就用最后一次手动价格，否则用最后一笔成交价。
     */
    private fun priceAt(observations: List<PriceObservation>, cutoff: Long): PriceObservation? {
        // Sorted by time: the first index past the cutoff.
        var low = 0
        var high = observations.size
        while (low < high) {
            val middle = (low + high) ushr 1
            if (observations[middle].atMs <= cutoff) low = middle + 1 else high = middle
        }
        val last = low - 1
        if (last < 0) return null
        val day = observations[last].day
        var execution: PriceObservation? = null
        for (index in last downTo 0) {
            val observation = observations[index]
            if (observation.day != day) break
            if (observation.manual) return observation
            if (execution == null) execution = observation
        }
        return execution
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

    /**
     * Amounts at moments, in time order: [upTo] adds those at or before a moment, [after] those later; each is a binary
     * search over sums made once.
     */
    private class Timeline(times: List<Long>, amounts: List<Long>) {
        private val times = times.toLongArray()
        /** At index i, the sum of the first i amounts. */
        private val prefix = LongArray(amounts.size + 1).also { sums ->
            amounts.forEachIndexed { index, amount -> sums[index + 1] = sums[index] + amount }
        }
        /** How many moments are at or before [cutoff]. */
        private fun count(cutoff: Long): Int {
            var low = 0
            var high = times.size
            while (low < high) {
                val middle = (low + high) ushr 1
                if (times[middle] <= cutoff) low = middle + 1 else high = middle
            }
            return low
        }
        fun upTo(cutoff: Long): Long = prefix[count(cutoff)]
        fun after(cutoff: Long): Long = prefix[times.size] - prefix[count(cutoff)]
    }

    private data class PriceObservation(val instrumentId: Long, val atMs: Long, val price: BigDecimal, val manual: Boolean,
        val day: LocalDate)

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

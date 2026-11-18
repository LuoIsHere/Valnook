package dev.valnook.domain.repository

import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.fx.FxRateResolver
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.Bill
import dev.valnook.domain.model.BillCounting
import dev.valnook.domain.model.BillDayRates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.ZoneId

/**
 * Counts bills as income and expense count them ([BillCounting]), again whenever the settings or rates change. Reads
 * the stored rates of only the days and currencies the bills need.
 */
class BillCounter(
    private val bills: BillRepository,
    private val settings: Flow<AppSettings>,
    private val fx: Flow<FxContext>,
    private val zone: ZoneId,
) {
    fun observe(list: List<Bill>): Flow<BillCounting> {
        // Account currencies are what totals convert; bill currencies too, for discounts given in them.
        val currencies = list.flatMapTo(HashSet()) { listOf(it.accountCurrency.code, it.currency.code) }
        val days = list.map { Instant.ofEpochMilli(it.occurredAtMs).atZone(zone).toLocalDate() }
        val stored = if (list.isEmpty()) flowOf(emptyMap()) else bills.observeStoredRates(currencies, days.min(), days.max())
        return combine(stored, settings, fx) { rates, appSettings, context ->
            BillCounting(list, BillDayRates(FxRateResolver(appSettings, context), rates), zone)
        }
    }
}

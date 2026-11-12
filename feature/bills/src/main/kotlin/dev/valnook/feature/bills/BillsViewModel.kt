package dev.valnook.feature.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.fx.FxRateResolver
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Which bills the list shows; every part narrows it, null meaning no limit. */
data class BillFilter(
    val type: BillType? = null,
    val categoryId: Long? = null,
    val cashAccountId: Long? = null,
    /** True: only bills counted in income and expense; false: only those left out. */
    val counted: Boolean? = null,
) {
    val active: Boolean get() = type != null || categoryId != null || cashAccountId != null || counted != null

    fun matches(bill: Bill): Boolean =
        (type == null || bill.type == type) &&
            (categoryId == null || bill.categoryId == categoryId || bill.subCategoryId == categoryId) &&
            (cashAccountId == null || bill.cashAccountId == cashAccountId || bill.toCashAccountId == cashAccountId) &&
            (counted == null || (bill.type != BillType.TRANSFER && bill.countInStats == counted))
}

/** Expense and income in the base currency; [partial] when some bill had no rate and was left out. */
data class BillTotals(val expense: BigDecimal, val income: BigDecimal, val partial: Boolean, val converted: Boolean)

data class BillDay(val date: LocalDate, val bills: List<Bill>, val totals: BillTotals)

data class BillsState(
    val month: YearMonth,
    val query: String,
    val filter: BillFilter,
    val days: List<BillDay> = emptyList(),
    val monthTotals: BillTotals? = null,
    val categories: Map<Long, BillCategory> = emptyMap(),
    val allCategories: List<BillCategory> = emptyList(),
    val accounts: Map<Long, BillAccountChoice> = emptyMap(),
    val accountList: List<BillAccountChoice> = emptyList(),
    val base: Currency? = null,
    val loaded: Boolean = false,
    val firstMonth: YearMonth? = null,
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillsViewModel(
    private val bills: BillRepository,
    settings: Flow<AppSettings>,
    fx: Flow<FxContext>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val month = MutableStateFlow(saved.get<String>("month")?.let(YearMonth::parse) ?: YearMonth.now(clock))
    private val query = MutableStateFlow(saved.get<String>("query") ?: "")
    private val filter = MutableStateFlow(BillFilter(
        saved.get<String>("type")?.let(BillType::valueOf), saved.get<Long>("category"), saved.get<Long>("account"),
        saved.get<Boolean>("counted")))

    private val listed = combine(month, query) { m, q -> m to q.trim() }.flatMapLatest { (m, q) ->
        if (q.isEmpty()) bills.observeMonth(m, clock.zone) else bills.observeSearch(q)
    }

    val state: StateFlow<BillsState> = combine(
        combine(month, query, filter, listed) { m, q, f, l -> Listed(m, q, f, l) },
        bills.observeCategories(), bills.observeAccountChoices(), combine(settings, fx) { s, x -> s to x },
        bills.observeFirstBillAt()) { listed, categories, accounts, (settingsValue, fxValue), first ->
        val resolver = FxRateResolver(settingsValue, fxValue)
        val shown = listed.bills.filter(listed.filter::matches)
        val days = shown.groupBy { Instant.ofEpochMilli(it.occurredAtMs).atZone(clock.zone).toLocalDate() }
            .map { (date, list) -> BillDay(date, list, totals(list, resolver)) }.sortedByDescending { it.date }
        BillsState(listed.month, listed.query, listed.filter, days,
            if (listed.query.isEmpty()) totals(listed.bills, resolver) else null,
            categories.associateBy { it.id }, categories, accounts.associateBy { it.cashAccountId }, accounts,
            settingsValue.baseCurrency, true, first?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(clock.zone)) })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillsState(month.value, query.value, filter.value))

    private data class Listed(val month: YearMonth, val query: String, val filter: BillFilter, val bills: List<Bill>)

    fun setMonth(value: YearMonth) { month.value = value; saved["month"] = value.toString() }
    fun setQuery(value: String) { query.value = value; saved["query"] = value }
    fun setFilter(value: BillFilter) {
        filter.value = value
        saved["type"] = value.type?.name; saved["category"] = value.categoryId
        saved["account"] = value.cashAccountId; saved["counted"] = value.counted
    }

    companion object {
        /**
         * Expense and income counted in income and expense, at today's rate into the base currency. Refunds and
         * reimbursements arrive in a later build and are left out here.
         */
        fun totals(list: List<Bill>, resolver: FxRateResolver): BillTotals {
            val base = resolver.base
            var expense = BigDecimal.ZERO
            var income = BigDecimal.ZERO
            var partial = false
            var converted = false
            list.filter { it.countInStats && (it.type == BillType.EXPENSE || it.type == BillType.INCOME) }.forEach { bill ->
                val currency = bill.accountCurrency
                val rate = resolver.today(currency.code)
                if (rate == null) { partial = true; return@forEach }
                if (currency != base) converted = true
                val value = BigDecimal.valueOf(bill.accountAmountMinor, currency.fraction_digits).multiply(rate)
                if (bill.type == BillType.EXPENSE) expense += value else income += value
            }
            val digits = base?.fraction_digits ?: 2
            return BillTotals(expense.setScale(digits, RoundingMode.HALF_UP), income.setScale(digits, RoundingMode.HALF_UP), partial, converted)
        }
    }
}

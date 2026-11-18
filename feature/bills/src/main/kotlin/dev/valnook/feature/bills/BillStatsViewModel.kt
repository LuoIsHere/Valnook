package dev.valnook.feature.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillCounter
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth

/** One side of a month (expense or income): its parts by category and its sums by day. */
data class BillStatsSide(
    val shares: List<BillShare> = emptyList(),
    val days: Map<LocalDate, BigDecimal> = emptyMap(),
)

data class BillStatsState(
    val month: YearMonth,
    val today: LocalDate,
    val loaded: Boolean = false,
    val base: Currency? = null,
    val totals: BillTotals? = null,
    /** The month before, to compare with. */
    val previous: BillTotals? = null,
    val expense: BillStatsSide = BillStatsSide(),
    val income: BillStatsSide = BillStatsSide(),
    val savings: BillSavings? = null,
    val categories: Map<Long, BillCategory> = emptyMap(),
)

/**
 * A month of income and expense for the statistics page, counted as the bill list counts it. Expense and income
 * share the month, so switching between them keeps it.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillStatsViewModel(
    private val bills: BillRepository,
    settings: Flow<AppSettings>,
    fx: Flow<FxContext>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val month = MutableStateFlow(saved.get<String>("stats-month")?.let(YearMonth::parse) ?: YearMonth.now(clock))
    private val counter = BillCounter(bills, settings, fx, clock.zone)

    private fun counted(month: YearMonth) =
        bills.observeMonth(month, clock.zone).flatMapLatest { counter.observe(it) }

    val state: StateFlow<BillStatsState> = combine(
        month.flatMapLatest { m -> combine(counted(m), counted(m.minusMonths(1))) { now, before -> Triple(m, now, before) } },
        bills.observeCategories(),
    ) { (m, now, before), categories ->
        BillStatsState(m, LocalDate.now(clock), true, now.base, now.totals(), before.totals(),
            BillStatsSide(now.shares(BillType.EXPENSE) { it.categoryId }, now.byDay(BillType.EXPENSE)),
            BillStatsSide(now.shares(BillType.INCOME) { it.categoryId }, now.byDay(BillType.INCOME)),
            now.savings(), categories.associateBy { it.id })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillStatsState(month.value, LocalDate.now(clock)))

    fun setMonth(value: YearMonth) { month.value = value; saved["stats-month"] = value.toString() }
}

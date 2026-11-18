package dev.valnook.feature.bills

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillCounter
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.flow.*
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
            (counted == null || (bill.type != BillType.TRANSFER && bill.counted == counted))
}

/** Where a note search looks besides the month on screen (the default, null): another month, a year, or everywhere. */
sealed interface BillSearchScope {
    data class Month(val month: YearMonth) : BillSearchScope
    data class Year(val year: Int) : BillSearchScope
    data object All : BillSearchScope

    fun encode(): String = when (this) { is Month -> "M:$month"; is Year -> "Y:$year"; All -> "ALL" }

    companion object {
        fun decode(text: String?): BillSearchScope? = when {
            text == null -> null
            text == "ALL" -> All
            text.startsWith("M:") -> runCatching { Month(YearMonth.parse(text.removePrefix("M:"))) }.getOrNull()
            text.startsWith("Y:") -> text.removePrefix("Y:").toIntOrNull()?.let(::Year)
            else -> null
        }
    }
}

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
    /** Where a search looks; null is the month on screen. */
    val scope: BillSearchScope? = null,
    /** The months and years there are bills in, newest first, to search one of. */
    val months: List<YearMonth> = emptyList(),
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillsViewModel(
    private val bills: BillRepository,
    settings: Flow<AppSettings>,
    fx: Flow<FxContext>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
    /** The month to open on when none was saved; this month when null. */
    initialMonth: YearMonth? = null,
) : ViewModel() {
    private val month = MutableStateFlow(saved.get<String>("month")?.let(YearMonth::parse) ?: initialMonth ?: YearMonth.now(clock))
    private val query = MutableStateFlow(saved.get<String>("query") ?: "")
    private val filter = MutableStateFlow(BillFilter(
        saved.get<String>("type")?.let(BillType::valueOf), saved.get<Long>("category"), saved.get<Long>("account"),
        saved.get<Boolean>("counted")))

    private val scope = MutableStateFlow(BillSearchScope.decode(saved.get<String>("scope")))

    private val counter = BillCounter(bills, settings, fx, clock.zone)

    private val listed = combine(month, query, scope) { m, q, s -> Triple(m, q.trim(), s) }.flatMapLatest { (m, q, s) ->
        val found = if (q.isEmpty()) bills.observeMonth(m, clock.zone)
        else {
            fun start(day: java.time.LocalDate) = day.atStartOfDay(clock.zone).toInstant().toEpochMilli()
            when (s) {
                null -> bills.observeSearch(q, start(m.atDay(1)), start(m.plusMonths(1).atDay(1)))
                is BillSearchScope.Month -> bills.observeSearch(q, start(s.month.atDay(1)), start(s.month.plusMonths(1).atDay(1)))
                is BillSearchScope.Year -> bills.observeSearch(q, start(java.time.LocalDate.of(s.year, 1, 1)),
                    start(java.time.LocalDate.of(s.year + 1, 1, 1)))
                BillSearchScope.All -> bills.observeSearch(q, null, null)
            }
        }
        // Each list is counted at its own days' rates, so the month and day totals agree with the statistics.
        found.flatMapLatest { list -> counter.observe(list).map { Fetched(m, q, s, it) } }
    }

    val state: StateFlow<BillsState> = combine(listed, filter, bills.observeCategories(), bills.observeAccountChoices(),
        bills.observeFirstBillAt()) { fetched, filterValue, categories, accounts, first ->
        val listed = Listed(fetched.month, fetched.query, filterValue, fetched.counting.bills, fetched.scope)
        val counting = fetched.counting
        val shown = listed.bills.filter(listed.filter::matches)
        val days = shown.groupBy(counting::dateOf)
            .map { (date, list) -> BillDay(date, list, counting.totals(list)) }.sortedByDescending { it.date }
        BillsState(listed.month, listed.query, listed.filter, days,
            if (listed.query.isEmpty()) counting.totals() else null,
            categories.associateBy { it.id }, categories, accounts.associateBy { it.cashAccountId }, accounts,
            counting.base, true, first?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(clock.zone)) },
            listed.scope, first?.let { firstAt ->
                val from = YearMonth.from(Instant.ofEpochMilli(firstAt).atZone(clock.zone))
                val to = maxOf(YearMonth.now(clock), listed.month)
                generateSequence(to) { it.minusMonths(1) }.takeWhile { !it.isBefore(from) }.toList()
            } ?: listOf(listed.month))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillsState(month.value, query.value, filter.value))

    private data class Fetched(val month: YearMonth, val query: String, val scope: BillSearchScope?,
        val counting: BillCounting)

    private data class Listed(val month: YearMonth, val query: String, val filter: BillFilter, val bills: List<Bill>,
        val scope: BillSearchScope?)

    fun setMonth(value: YearMonth) { month.value = value; saved["month"] = value.toString() }
    fun setQuery(value: String) { query.value = value; saved["query"] = value }
    /** Null searches the month on screen. */
    fun setScope(value: BillSearchScope?) { scope.value = value; saved["scope"] = value?.encode() }
    fun setFilter(value: BillFilter) {
        filter.value = value
        saved["type"] = value.type?.name; saved["category"] = value.categoryId
        saved["account"] = value.cashAccountId; saved["counted"] = value.counted
    }
}

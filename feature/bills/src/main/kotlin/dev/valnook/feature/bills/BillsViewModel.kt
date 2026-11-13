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
) : ViewModel() {
    private val month = MutableStateFlow(saved.get<String>("month")?.let(YearMonth::parse) ?: YearMonth.now(clock))
    private val query = MutableStateFlow(saved.get<String>("query") ?: "")
    private val filter = MutableStateFlow(BillFilter(
        saved.get<String>("type")?.let(BillType::valueOf), saved.get<Long>("category"), saved.get<Long>("account"),
        saved.get<Boolean>("counted")))

    private val scope = MutableStateFlow(BillSearchScope.decode(saved.get<String>("scope")))

    private val listed = combine(month, query, scope) { m, q, s -> Triple(m, q.trim(), s) }.flatMapLatest { (m, q, s) ->
        if (q.isEmpty()) bills.observeMonth(m, clock.zone)
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
    }

    val state: StateFlow<BillsState> = combine(
        combine(month, query, filter, scope, listed) { m, q, f, s, l -> Listed(m, q, f, l, s) },
        bills.observeCategories(), bills.observeAccountChoices(), combine(settings, fx) { s, x -> s to x },
        bills.observeFirstBillAt()) { listed, categories, accounts, (settingsValue, fxValue), first ->
        val resolver = FxRateResolver(settingsValue, fxValue)
        val shown = listed.bills.filter(listed.filter::matches)
        val days = shown.groupBy { Instant.ofEpochMilli(it.occurredAtMs).atZone(clock.zone).toLocalDate() }
            .map { (date, list) -> BillDay(date, list, totals(list, resolver)) }.sortedByDescending { it.date }
        BillsState(listed.month, listed.query, listed.filter, days,
            if (listed.query.isEmpty()) totals(listed.bills, resolver) else null,
            categories.associateBy { it.id }, categories, accounts.associateBy { it.cashAccountId }, accounts,
            settingsValue.baseCurrency, true, first?.let { YearMonth.from(Instant.ofEpochMilli(it).atZone(clock.zone)) },
            listed.scope, first?.let { firstAt ->
                val from = YearMonth.from(Instant.ofEpochMilli(firstAt).atZone(clock.zone))
                val to = maxOf(YearMonth.now(clock), listed.month)
                generateSequence(to) { it.minusMonths(1) }.takeWhile { !it.isBefore(from) }.toList()
            } ?: listOf(listed.month))
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillsState(month.value, query.value, filter.value))

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

    companion object {
        /** [value] of what [bill] paid, scaled to what is left of it after refunds and reimbursements. */
        internal fun net(bill: Bill, value: BigDecimal): BigDecimal =
            if (bill.paidMinor <= 0 || bill.remainingMinor == bill.paidMinor) value
            else value.multiply(BigDecimal.valueOf(bill.remainingMinor.coerceAtLeast(0)))
                .divide(BigDecimal.valueOf(bill.paidMinor), 12, RoundingMode.HALF_UP)

        /**
         * Expense and income counted in income and expense, at today's rate into the base currency. An expense counts
         * net of its refunds and reimbursements, wherever they arrived ([Bill.remainingMinor] of what it paid); the
         * refunds and reimbursements themselves are neither expense nor income.
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
                if (bill.type == BillType.EXPENSE) expense += net(bill, value) else income += value
            }
            val digits = base?.fraction_digits ?: 2
            return BillTotals(expense.setScale(digits, RoundingMode.HALF_UP), income.setScale(digits, RoundingMode.HALF_UP), partial, converted)
        }
    }
}

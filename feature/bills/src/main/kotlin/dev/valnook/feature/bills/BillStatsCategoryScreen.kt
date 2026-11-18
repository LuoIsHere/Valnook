package dev.valnook.feature.bills

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dev.valnook.designsystem.*
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules
import dev.valnook.domain.repository.BillAccountChoice
import dev.valnook.domain.repository.BillCounter
import dev.valnook.domain.repository.BillRepository
import kotlinx.coroutines.flow.*
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.YearMonth

data class BillStatsCategoryState(
    val month: YearMonth,
    val loaded: Boolean = false,
    val base: Currency? = null,
    /** What the category's counted bills come to; bills left out are listed but not added. */
    val totals: BillTotals? = null,
    val counted: Int = 0,
    /** Parts by subcategory, null for bills on the category itself. */
    val shares: List<BillShare> = emptyList(),
    val days: List<BillDay> = emptyList(),
    val categories: Map<Long, BillCategory> = emptyMap(),
    val accounts: Map<Long, BillAccountChoice> = emptyMap(),
)

/**
 * One category's month on the statistics page: [categoryId] (null for bills without a category) among the
 * expenses ([expense] true) or income. Every bill of it is listed, those not counted too; only counted ones add up.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BillStatsCategoryViewModel(
    private val bills: BillRepository,
    settings: Flow<AppSettings>,
    fx: Flow<FxContext>,
    private val clock: Clock,
    private val saved: SavedStateHandle,
    val categoryId: Long?,
    val expense: Boolean,
    initialMonth: YearMonth,
) : ViewModel() {
    private val month = MutableStateFlow(saved.get<String>("month")?.let(YearMonth::parse) ?: initialMonth)
    private val counter = BillCounter(bills, settings, fx, clock.zone)
    private val type = if (expense) BillType.EXPENSE else BillType.INCOME

    val state: StateFlow<BillStatsCategoryState> = combine(
        month.flatMapLatest { m ->
            bills.observeMonth(m, clock.zone).map { list -> list.filter { it.type == type && it.categoryId == categoryId } }
                .flatMapLatest { list -> counter.observe(list).map { m to it } }
        },
        bills.observeCategories(), bills.observeAccountChoices(),
    ) { (m, counting), categories, accounts ->
        val days = counting.bills.groupBy(counting::dateOf).map { (date, list) -> BillDay(date, list, counting.totals(list)) }
            .sortedByDescending { it.date }
        BillStatsCategoryState(m, true, counting.base, counting.totals(),
            counting.shares(type) { null }.sumOf { it.count }, counting.shares(type) { it.subCategoryId }, days,
            categories.associateBy { it.id }, accounts.associateBy { it.cashAccountId })
    }.stateIn(viewModelScope, SharingStarted.Eagerly, BillStatsCategoryState(month.value))

    fun setMonth(value: YearMonth) { month.value = value; saved["month"] = value.toString() }
}

/** A category's month: its total, its subcategories' parts, and its bills by day. [onOpen] opens a bill. */
@Composable
fun BillStatsCategoryScreen(vm: BillStatsCategoryViewModel, onOpen: (Long) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val category = vm.categoryId?.let(state.categories::get)
    val locale = LocalConfiguration.current.locales[0]
    // The bill rows read names and accounts from the list's own state.
    val rows = BillsState(state.month, "", BillFilter(), categories = state.categories, accounts = state.accounts,
        base = state.base, loaded = state.loaded)
    LazyColumn(Modifier.fillMaxSize().testTag("bill-stats-category"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item("header") {
            GlassCard {
                Column(Modifier.fillMaxWidth().padding(Space.cardInset), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
                        CategoryIcon(category?.iconKey ?: "circle-question", category?.colorKey ?: "grey", size = 40.dp)
                        Text(categoryName(category), Modifier.weight(1f).testTag("bill-stats-category-name"),
                            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val totals = state.totals
                    val value = totals?.let { if (vm.expense) it.expense else it.income }
                    Text(value?.let { totalText(it, state.base?.code, totals) } ?: "—",
                        Modifier.padding(top = Space.sm).testTag("bill-stats-category-total"),
                        style = MaterialTheme.typography.headlineMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1)
                    Text(pluralStringResource(R.plurals.stats_bill_count, state.counted, state.counted),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (totals?.partial == true) Text(stringResource(R.string.bills_totals_partial),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    MonthPager(state.month, vm::setMonth, horizontalArrangement = Arrangement.Start,
                        label = { m, l -> localMonthLabel(m, l) })
                }
            }
        }
        // Worth showing only once some of the money sits in a subcategory.
        if (state.shares.any { it.categoryId != null }) item("subcategories") {
            SubcategoryCard(state, category)
        }
        if (state.loaded && state.days.isEmpty()) item("empty") {
            EmptyState(stringResource(R.string.stats_category_empty))
        }
        state.days.forEach { day ->
            item("day-${day.date}") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayHeader(day, locale, state.base?.code)
                    GlassCard {
                        day.bills.forEachIndexed { index, bill ->
                            BillRow(bill, rows, { onOpen(bill.id) }, { onOpen(bill.id) },
                                showDivider = index < day.bills.lastIndex, showNet = true)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SubcategoryCard(state: BillStatsCategoryState, category: BillCategory?) {
    val total = state.shares.fold(BigDecimal.ZERO) { sum, share -> sum + share.value }
    val largest = state.shares.firstOrNull()?.value ?: BigDecimal.ZERO
    val digits = state.base?.fraction_digits ?: 2
    val color = billColor(category?.colorKey ?: "grey")
    GlassCard {
        Column(Modifier.fillMaxWidth().padding(Space.cardInset).testTag("bill-stats-subcategories"),
            verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Text(stringResource(R.string.stats_subcategories), style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.shares.forEach { share ->
                val name = share.categoryId?.let(state.categories::get)?.let { categoryName(it) }
                    ?: stringResource(R.string.stats_no_subcategory)
                Column(Modifier.fillMaxWidth().testTag("bill-stats-sub-${share.categoryId ?: "none"}")
                    .semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                        Text(DecimalRules.format_display(share.value.setScale(digits, RoundingMode.HALF_UP), digits) + " · " +
                            percentText(share.value, total), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"))
                    }
                    ShareBar(if (largest.signum() > 0) share.value.divide(largest, 6, RoundingMode.HALF_UP).toFloat() else 0f, color)
                }
            }
        }
    }
}

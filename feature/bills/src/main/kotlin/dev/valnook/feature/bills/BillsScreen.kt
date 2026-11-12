package dev.valnook.feature.bills

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.Bill
import dev.valnook.domain.model.BillType
import dev.valnook.domain.model.TransferKind
import dev.valnook.domain.money.DecimalRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the bills page puts in the title bar: search, opened and closed from there. */
data class BillsToolbarState(val searching: Boolean, val toggleSearch: () -> Unit)

/** The bills page: one month of bills by day, with the month's and each day's expense and income. */
@Composable fun BillsScreen(vm: BillsViewModel, onOpen: (Long) -> Unit, onCopy: (Long) -> Unit,
    onToolbar: (BillsToolbarState?) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(state.query.isNotEmpty()) }
    val locale = LocalConfiguration.current.locales[0]
    val toggle = remember(vm) { { searching = !searching; if (!searching) vm.setQuery("") } }
    DisposableEffect(searching) {
        onToolbar(BillsToolbarState(searching) { toggle() })
        onDispose { onToolbar(null) }
    }
    LazyColumn(Modifier.fillMaxSize().testTag("bills-list"), contentPadding = pageContentPadding(),
        verticalArrangement = Arrangement.spacedBy(Space.md)) {
        item(key = "header") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (searching) GlassTextField(state.query, vm::setQuery, stringResource(R.string.bill_search_notes),
                    Modifier.fillMaxWidth().testTag("bills-search"))
                else MonthHeader(state, vm)
                FilterRow(state, vm)
            }
        }
        if (state.loaded && state.days.isEmpty()) item(key = "empty") {
            EmptyState(stringResource(when {
                state.query.isNotBlank() -> R.string.bills_none_found
                state.filter.active -> R.string.bills_none_filtered
                else -> R.string.bills_none_this_month
            }))
        }
        state.days.forEach { day ->
            item(key = "day-${day.date}") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    DayHeader(day, locale, state.base?.code)
                    GlassCard {
                        day.bills.forEachIndexed { index, bill ->
                            BillRow(bill, state, { onOpen(bill.id) }, { onCopy(bill.id) }, showDivider = index < day.bills.lastIndex)
                        }
                    }
                }
            }
        }
    }
}

/** The month's expense large, its income and balance small under it, and the month switcher below. */
@Composable private fun MonthHeader(state: BillsState, vm: BillsViewModel) {
    val totals = state.monthTotals
    val base = state.base?.code
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp).testTag("bills-month-summary"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.bills_month_expense), style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        Text(totals?.let { totalText(it.expense, base, it) } ?: "—", Modifier.testTag("bills-month-expense"),
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        if (totals != null) Text(stringResource(R.string.bills_month_income_balance, totalText(totals.income, base, totals),
            totalText(totals.income - totals.expense, base, totals)), style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurfaceVariant)
        if (totals?.partial == true) Text(stringResource(R.string.bills_totals_partial), style = MaterialTheme.typography.bodySmall,
            color = colors.error)
        MonthPager(state.month, vm::setMonth, horizontalArrangement = Arrangement.Start,
            label = { m, l -> localMonthLabel(m, l) })
    }
}

private fun totalText(value: java.math.BigDecimal, base: String?, totals: BillTotals): String =
    (if (totals.converted) "≈ " else "") + DecimalRules.format_display(value, value.scale()) + (base?.let { " $it" } ?: "")

@Composable private fun DayHeader(day: BillDay, locale: java.util.Locale, base: String?) {
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMdEEE")
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(day.date.format(DateTimeFormatter.ofPattern(pattern, locale)), Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val parts = listOfNotNull(
            day.totals.expense.takeIf { it.signum() != 0 }?.let { stringResource(R.string.bills_day_expense, totalText(it, base, day.totals)) },
            day.totals.income.takeIf { it.signum() != 0 }?.let { stringResource(R.string.bills_day_income, totalText(it, base, day.totals)) })
        if (parts.isNotEmpty()) Text(parts.joinToString("  "), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * One bill: its category (or transfer) icon, title, details and the amount its own account moved. The note leads
 * when there is one, with the category ("food-lunch") or the transfer under it; otherwise the category is the title.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable internal fun BillRow(bill: Bill, state: BillsState, onOpen: () -> Unit, onCopy: () -> Unit, showDivider: Boolean) {
    val category = bill.categoryId?.let(state.categories::get)
    val sub = bill.subCategoryId?.let(state.categories::get)
    val shownCategory = sub ?: category
    val transfer = bill.type == BillType.TRANSFER
    val kind = if (transfer) transferTitle(bill, state.accounts)
        else if (category != null && sub != null) categoryName(category) + "-" + categoryName(sub) else categoryName(shownCategory)
    val note = bill.note.takeIf { it.isNotBlank() }
    val title = note ?: kind
    val time = Instant.ofEpochMilli(bill.occurredAtMs).atZone(ZoneId.systemDefault())
    // The time leads, so a long account name cannot push it out of the row.
    val details = listOfNotNull(
        time.toLocalTime().format(rememberClockFormatter()),
        if (note != null) kind else null,
        if (transfer) null else sideName(bill.cashAccountId, state.accounts, false, "", bill.accountDeleted))
    val colors = MaterialTheme.colorScheme
    val counted = transfer || bill.countInStats
    val future = time.toLocalDate().isAfter(LocalDate.now())
    Column(Modifier.fillMaxWidth().combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onCopy)
        .testTag("bill-row-${bill.id}")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            if (transfer) CategoryIcon(if (bill.transferKind == TransferKind.INTERNAL) "repeat" else "globe", "grey", size = 38.dp)
            else CategoryIcon(shownCategory?.iconKey ?: "circle-question", shownCategory?.colorKey ?: "grey", size = 38.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(details.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val amount = if (transfer && bill.transferKind == TransferKind.INTERNAL) plainAmount(bill.shownMinor, bill.shownFractionDigits)
                    else signedAmount(bill.shownMinor, bill.shownFractionDigits, bill.isOutflow)
                Text("$amount ${bill.shownCurrencyCode}", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = if (counted) colors.onSurface else colors.onSurfaceVariant)
                val tags = listOfNotNull(
                    if (!counted) stringResource(R.string.bill_not_counted) else null,
                    if (future) stringResource(R.string.bill_future) else null)
                if (tags.isNotEmpty()) Text(tags.joinToString(" · "), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
            }
        }
        if (showDivider) FormDivider()
    }
}

@Composable private fun FilterRow(state: BillsState, vm: BillsViewModel) {
    val filter = state.filter
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        FilterMenu(stringResource(R.string.bills_filter_type), filter.type?.let { stringResource(typeLabel(it)) },
            listOf<BillType?>(null, BillType.EXPENSE, BillType.INCOME, BillType.TRANSFER),
            { it?.let { type -> stringResource(typeLabel(type)) } ?: stringResource(R.string.bills_filter_all) },
            { vm.setFilter(filter.copy(type = it)) }, "bills-filter-type")
        val categories = state.allCategories.filter { !it.hidden || it.id == filter.categoryId }
        FilterMenu(stringResource(R.string.bills_filter_category), filter.categoryId?.let { categoryName(state.categories[it]) },
            listOf<Long?>(null) + categories.map { it.id },
            { id -> id?.let { key -> state.categories[key]?.let { c ->
                (c.parentId?.let { p -> categoryName(state.categories[p]) + " › " } ?: "") + categoryName(c) } }
                ?: stringResource(R.string.bills_filter_all) },
            { vm.setFilter(filter.copy(categoryId = it)) }, "bills-filter-category")
        FilterMenu(stringResource(R.string.bills_filter_account), filter.cashAccountId?.let { state.accounts[it]?.label() },
            listOf<Long?>(null) + state.accountList.map { it.cashAccountId },
            { id -> id?.let { state.accounts[it]?.label() } ?: stringResource(R.string.bills_filter_all) },
            { vm.setFilter(filter.copy(cashAccountId = it)) }, "bills-filter-account")
        FilterMenu(stringResource(R.string.bills_filter_counted), filter.counted?.let {
            stringResource(if (it) R.string.bills_filter_counted_yes else R.string.bills_filter_counted_no) },
            listOf(null, true, false), { value -> stringResource(when (value) {
                true -> R.string.bills_filter_counted_yes; false -> R.string.bills_filter_counted_no; else -> R.string.bills_filter_all }) },
            { vm.setFilter(filter.copy(counted = it)) }, "bills-filter-counted")
        if (filter.active) TextButton({ vm.setFilter(BillFilter()) }, Modifier.testTag("bills-filter-clear")) {
            Text(stringResource(R.string.bills_filter_clear))
        }
    }
}

internal fun typeLabel(type: BillType): Int = when (type) {
    BillType.EXPENSE -> R.string.bill_expense
    BillType.INCOME -> R.string.bill_income
    BillType.TRANSFER -> R.string.bill_transfer
    BillType.REFUND -> R.string.bill_refund
    BillType.REIMBURSEMENT -> R.string.bill_reimbursement
}

@Composable private fun <T> FilterMenu(label: String, value: String?, options: List<T>, optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit, tag: String) {
    var open by remember { mutableStateOf(false) }
    Box {
        FilterChip(value != null, { open = true }, { Text(if (value == null) label else "$label：$value", maxLines = 1,
            overflow = TextOverflow.Ellipsis) }, Modifier.widthIn(max = 220.dp).testTag(tag),
            trailingIcon = { FaIcon(FaIcons.chevronDown, null, Modifier.size(10.dp)) })
        DropdownMenu(open, { open = false }, Modifier.heightIn(max = 420.dp)) {
            options.forEach { option ->
                DropdownMenuItem({ Text(optionLabel(option)) }, { onSelect(option); open = false })
            }
        }
    }
}

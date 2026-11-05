package dev.valnook.feature.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.PageLoading
import dev.valnook.designsystem.PageFailure
import dev.valnook.designsystem.UnderlineTabRow
import dev.valnook.domain.model.ConvertedTotal
import dev.valnook.domain.model.StatisticsPoint
import dev.valnook.domain.money.DecimalRules
import java.math.BigDecimal
import java.time.LocalDate

enum class AccountDetailSection { ACCOUNTS, DEPOSITS, INVESTMENTS }

data class AccountSectionSummary(val count: Int, val total: ConvertedTotal)

/** What the accounts tab needs from the page: how far its credit details are open, and the account's note. */
class AccountCashSlot(val expansion: () -> Float, val note: String)

@Composable
fun AccountDetailScreen(vm: AccountsViewModel, accountId: Long, initialSection: AccountDetailSection,
    cashContent: @Composable (AccountCashSlot) -> Unit, depositContent: @Composable () -> Unit,
    investmentContent: @Composable () -> Unit, onTitleReserve: (Int) -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AccountsState.Loading -> PageLoading()
        AccountsState.Failed -> PageFailure(stringResource(R.string.accounts_load_failed), vm::reload)
        is AccountsState.Ready -> {
            val assets = current.overview.accounts.firstOrNull { it.account.id == accountId }
            if (assets == null) Text(stringResource(R.string.account_detail_missing)) else {
                val cashCount = current.snapshot.cash.count { it.account_id == accountId }
                val depositCount = current.snapshot.deposits.count { it.account_id == accountId && !it.closed }
                val investmentCount = current.snapshot.positions.count {
                    it.account_id == accountId && it.holding_quantity_e8 > 0
                }
                val sections = mapOf(
                    AccountDetailSection.ACCOUNTS to AccountSectionSummary(cashCount, ConvertedTotal(
                        assets.cash.amount + assets.creditBalance.amount, assets.cash.currency,
                        assets.cash.missing + assets.creditBalance.missing)),
                    AccountDetailSection.DEPOSITS to AccountSectionSummary(depositCount, assets.depositValue),
                    AccountDetailSection.INVESTMENTS to AccountSectionSummary(investmentCount, assets.investmentValue))
                AccountDetailContent(accountId, assets.account.name, assets.account.note, assets.total, sections,
                    initialSection, flatTrend(assets.total.amount), cashContent, depositContent, investmentContent, onTitleReserve)
            }
        }
    }
}

/** A straight line at today's value until the account's own history is read. */
@Composable private fun flatTrend(value: BigDecimal): List<StatisticsPoint> {
    val today = LocalDate.now()
    return remember(value, today) {
        generateSequence(today.minusMonths(3)) { it.plusDays(1) }.takeWhile { it <= today }
            .map { StatisticsPoint(it, value, reliable = true) }.toList()
    }
}

@Composable
fun AccountDetailContent(accountId: Long, name: String, note: String, total: ConvertedTotal,
    sections: Map<AccountDetailSection, AccountSectionSummary>, initialSection: AccountDetailSection,
    trend: List<StatisticsPoint>, cashContent: @Composable (AccountCashSlot) -> Unit,
    depositContent: @Composable () -> Unit, investmentContent: @Composable () -> Unit, onTitleReserve: (Int) -> Unit = {}) {
    val collapse = rememberHeaderCollapse(accountId)
    var selected by rememberSaveable(accountId) { mutableStateOf(initialSection) }
    val sectionStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val background = MaterialTheme.colorScheme.background
    val names = mapOf(
        AccountDetailSection.ACCOUNTS to stringResource(R.string.accounts_accounts),
        AccountDetailSection.DEPOSITS to stringResource(R.string.account_tab_deposits),
        AccountDetailSection.INVESTMENTS to stringResource(R.string.accounts_investments))
    val cashSlot = remember(collapse, note) { AccountCashSlot({ collapse.eased }, note) }
    Column(Modifier.fillMaxSize().nestedScroll(collapse)) {
        AccountDetailHeader(collapse, name, detailTotal(total), detailAmountColor(total), trend, accountId, onTitleReserve)
        // The tabs and the content are opaque: they slide over the chart as the header closes.
        UnderlineTabRow(AccountDetailSection.entries, selected, { selected = it },
            label = { "${names.getValue(it)} ${sections.getValue(it).count}" },
            modifier = Modifier.background(background).headerDrag(collapse).testTag("account-detail-tabs"),
            optionModifier = { Modifier.testTag("account-section-${it.name}") })
        // With assets in one section only, its total would just repeat the balance above.
        if (sections.values.count { it.total.amount.signum() != 0 } > 1) {
            val sectionTotal = sections.getValue(selected).total
            Row(Modifier.fillMaxWidth().background(background).headerDrag(collapse)
                .padding(horizontal = 16.dp, vertical = 6.dp).testTag("account-section-total"),
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.account_section_total), Modifier.weight(1f),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(detailTotal(sectionTotal), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                    color = detailAmountColor(sectionTotal))
            }
        }
        // Keep every section's scroll state while displaying only the selected content.
        Box(Modifier.fillMaxWidth().weight(1f).background(background)) {
            sectionStates.SaveableStateProvider(selected.name) {
                when (selected) {
                    AccountDetailSection.ACCOUNTS -> cashContent(cashSlot)
                    AccountDetailSection.DEPOSITS -> depositContent()
                    AccountDetailSection.INVESTMENTS -> investmentContent()
                }
            }
        }
    }
}

@Composable private fun detailTotal(total: ConvertedTotal): String = total.currency?.let {
    DecimalRules.format_display(total.amount, it.fraction_digits) + " " + it.code
} ?: stringResource(R.string.accounts_set_base_currency)

@Composable private fun detailAmountColor(total: ConvertedTotal) =
    if (total.amount.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

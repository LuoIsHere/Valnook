package dev.valnook.feature.accounts

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.Space
import dev.valnook.designsystem.CapsuleChoiceRow
import dev.valnook.designsystem.GlassCard
import dev.valnook.designsystem.LocalPageTitle
import dev.valnook.designsystem.PageLoading
import dev.valnook.designsystem.PageFailure
import dev.valnook.domain.model.ConvertedTotal
import java.math.RoundingMode

enum class AccountDetailSection { ACCOUNTS, DEPOSITS, INVESTMENTS }

@Composable
fun AccountDetailScreen(vm: AccountsViewModel, accountId: Long, initialSection: AccountDetailSection,
    cashContent: @Composable () -> Unit, depositContent: @Composable () -> Unit,
    investmentContent: @Composable () -> Unit) {
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
                var selected by rememberSaveable(accountId) { mutableStateOf(initialSection) }
                val sectionStates = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
                Column(Modifier.fillMaxSize()) {
                    GlassCard(Modifier.padding(horizontal = 16.dp, vertical = Space.sm)) {
                    Column(Modifier.fillMaxWidth().padding(Space.cardInset),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (LocalPageTitle.current != assets.account.name) Text(assets.account.name, style = MaterialTheme.typography.titleMedium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(stringResource(R.string.accounts_total_assets), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(detailTotal(assets.total), style = MaterialTheme.typography.headlineSmall.copy(
                                fontFeatureSettings = "tnum"), color = detailAmountColor(assets.total),
                                textAlign = TextAlign.Start)
                            if (assets.account.note.isNotBlank()) Text(assets.account.note, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    }
                    val labels = mapOf(
                        AccountDetailSection.ACCOUNTS to stringResource(R.string.accounts_accounts),
                        AccountDetailSection.DEPOSITS to stringResource(R.string.account_tab_deposits),
                        AccountDetailSection.INVESTMENTS to stringResource(R.string.accounts_investments))
                    CapsuleChoiceRow(AccountDetailSection.entries, selected, { selected = it },
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                            .testTag("account-detail-tabs"),
                        optionModifier = { Modifier.testTag("account-section-${it.name}") },
                        controlHeight = 38.dp * LocalDensity.current.fontScale.coerceAtLeast(1f),
                        optionWeight = { labels.getValue(it).length.coerceAtLeast(8).toFloat() }) { section ->
                        Text(labels.getValue(section), style = MaterialTheme.typography.labelLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val count = when (selected) {
                        AccountDetailSection.ACCOUNTS -> cashCount
                        AccountDetailSection.DEPOSITS -> depositCount
                        AccountDetailSection.INVESTMENTS -> investmentCount
                    }
                    val total = when (selected) {
                        AccountDetailSection.ACCOUNTS -> ConvertedTotal(
                            assets.cash.amount + assets.creditBalance.amount, assets.cash.currency,
                            assets.cash.missing + assets.creditBalance.missing)
                        AccountDetailSection.DEPOSITS -> assets.depositValue
                        AccountDetailSection.INVESTMENTS -> assets.investmentValue
                    }
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.account_section_count, count), Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(detailTotal(total), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
                            color = detailAmountColor(total))
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    // Keep every section's scroll state while displaying only the selected content.
                    Box(Modifier.fillMaxWidth().weight(1f)) {
                        sectionStates.SaveableStateProvider(selected.name) {
                            when (selected) {
                                AccountDetailSection.ACCOUNTS -> cashContent()
                                AccountDetailSection.DEPOSITS -> depositContent()
                                AccountDetailSection.INVESTMENTS -> investmentContent()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun detailTotal(total: ConvertedTotal): String = total.currency?.let {
    total.amount.setScale(it.fraction_digits, RoundingMode.HALF_UP).toPlainString() + " " + it.code
} ?: stringResource(R.string.accounts_set_base_currency)

@Composable private fun detailAmountColor(total: ConvertedTotal) =
    if (total.amount.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

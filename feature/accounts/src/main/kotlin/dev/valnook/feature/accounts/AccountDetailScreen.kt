package dev.valnook.feature.accounts

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.Space
import dev.valnook.domain.model.ConvertedTotal
import java.math.RoundingMode

enum class AccountDetailSection { CASH, DEPOSITS, INVESTMENTS }

@Composable
fun AccountDetailScreen(vm: AccountsViewModel, accountId: Long, initialSection: AccountDetailSection,
    cashContent: @Composable () -> Unit, depositContent: @Composable () -> Unit,
    investmentContent: @Composable () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        AccountsState.Loading -> CircularProgressIndicator()
        AccountsState.Failed -> Text(stringResource(R.string.accounts_load_failed))
        is AccountsState.Ready -> {
            val assets = current.overview.accounts.firstOrNull { it.account.id == accountId }
            if (assets == null) Text(stringResource(R.string.account_detail_missing)) else {
                val cashCount = current.snapshot.cash.count { it.account_id == accountId }
                val depositCount = current.snapshot.deposits.count { it.account_id == accountId && !it.closed }
                val investmentCount = current.snapshot.positions.count {
                    it.account_id == accountId && it.holding_quantity_e8 > 0
                }
                var selected by rememberSaveable(accountId) { mutableStateOf(initialSection) }
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = Space.sm),
                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(assets.account.name, style = MaterialTheme.typography.titleLarge,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text(assets.account.note, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                                overflow = TextOverflow.Ellipsis)
                            Text(detailTotal(assets.total), style = MaterialTheme.typography.headlineSmall.copy(
                                fontFeatureSettings = "tnum"), color = detailAmountColor(assets.total),
                                textAlign = TextAlign.End)
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    AccountSectionHeader("CASH", stringResource(R.string.accounts_cash), cashCount, assets.cash,
                        selected == AccountDetailSection.CASH) { selected = AccountDetailSection.CASH }
                    if (selected == AccountDetailSection.CASH) Box(Modifier.fillMaxWidth().weight(1f)) { cashContent() }
                    AccountSectionHeader("DEPOSITS", stringResource(R.string.accounts_deposits), depositCount, assets.depositValue,
                        selected == AccountDetailSection.DEPOSITS) { selected = AccountDetailSection.DEPOSITS }
                    if (selected == AccountDetailSection.DEPOSITS) Box(Modifier.fillMaxWidth().weight(1f)) { depositContent() }
                    AccountSectionHeader("INVESTMENTS", stringResource(R.string.accounts_investments), investmentCount, assets.investmentValue,
                        selected == AccountDetailSection.INVESTMENTS) { selected = AccountDetailSection.INVESTMENTS }
                    if (selected == AccountDetailSection.INVESTMENTS) Box(Modifier.fillMaxWidth().weight(1f)) { investmentContent() }
                }
            }
        }
    }
}

@Composable
private fun AccountSectionHeader(tag: String, label: String, count: Int, total: ConvertedTotal, expanded: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag("account-section-$tag")
        .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            Text(count.toString(), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(detailTotal(total), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"),
            color = detailAmountColor(total), textAlign = TextAlign.End)
        Text(if (expanded) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable private fun detailTotal(total: ConvertedTotal): String = total.currency?.let {
    total.amount.setScale(it.fraction_digits, RoundingMode.HALF_UP).toPlainString() + " " + it.code
} ?: stringResource(R.string.accounts_set_base_currency)

@Composable private fun detailAmountColor(total: ConvertedTotal) =
    if (total.amount.signum() < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

package dev.valnook.feature.investments

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.valnook.designsystem.*
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.SavingsAccount
import dev.valnook.domain.money.DecimalRules

/** Every account can buy; only one holding units can sell; any that has held it may receive a dividend. */
internal fun accountChoices(ready: InstrumentDetailState.Ready, direction: Direction): List<SavingsAccount> = when (direction) {
    Direction.BUY -> ready.accounts
    Direction.SELL -> ready.holdings.filter { it.position.holding_quantity_e8 > 0 }.map { it.account }
    Direction.DIVIDEND -> ready.holdings.map { it.account }
}

/**
 * The account a new record starts in: the one the page was opened from, else the only one holding units, else the
 * first that can take it. Null when none can.
 */
internal fun defaultAccount(ready: InstrumentDetailState.Ready, direction: Direction, contextAccountId: Long?): SavingsAccount? {
    val choices = accountChoices(ready, direction)
    choices.firstOrNull { it.id == contextAccountId }?.let { return it }
    val held = ready.holdings.filter { it.position.holding_quantity_e8 > 0 }.map { it.account }
    held.singleOrNull()?.takeIf { only -> choices.any { it.id == only.id } }?.let { return it }
    return choices.firstOrNull()
}

/** The position the instrument has in [accountId], if it was ever held there. */
internal fun positionIn(ready: InstrumentDetailState.Ready, accountId: Long): Long? =
    ready.holdings.firstOrNull { it.account.id == accountId }?.position?.id

@Composable fun accountPickTitle(direction: Direction): String = stringResource(when (direction) {
    Direction.BUY -> R.string.instrument_buy_into
    Direction.SELL -> R.string.instrument_sell_from
    Direction.DIVIDEND -> R.string.instrument_dividend_to
})

/**
 * The accounts a new buy, sell or dividend of the instrument can use, as its own page; [selectedAccountId], the one
 * the form has now, is marked. [onChoose] gets the account and the instrument's position there, if any.
 */
@Composable fun InstrumentAccountPicker(vm: InstrumentDetailViewModel, direction: Direction, selectedAccountId: Long,
    onChoose: (accountId: Long, positionId: Long?) -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    when (val current = state) {
        InstrumentDetailState.Loading -> PageLoading()
        InstrumentDetailState.Failed -> Text(stringResource(R.string.instrument_load_failed))
        InstrumentDetailState.Missing -> EmptyState(stringResource(R.string.instrument_not_found))
        is InstrumentDetailState.Ready -> {
            val choices = accountChoices(current, direction)
            LazyColumn(Modifier.fillMaxSize().testTag("instrument-account-picker"), contentPadding = pageContentPadding()) {
                item {
                    GlassCard {
                        choices.forEachIndexed { index, account ->
                            if (index > 0) FormDivider()
                            val position = current.holdings.firstOrNull { it.account.id == account.id }?.position
                            val chosen = account.id == selectedAccountId
                            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
                                .clickable(role = Role.Button) { onChoose(account.id, position?.id) }
                                .semantics(mergeDescendants = true) { selected = chosen }
                                .testTag("instrument-account-pick-${account.id}").padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                AccountAvatar(account.icon.symbol, account.icon.imageKey, size = 32.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(account.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    if (position != null && position.holding_quantity_e8 > 0) Text(stringResource(R.string.investment_units,
                                        DecimalRules.format_e8(position.holding_quantity_e8)), style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (chosen) FaIcon(FaIcons.check, null, Modifier.size(18.dp), MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            }
        }
    }
}

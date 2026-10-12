package dev.valnook.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CashEntry

/** Cash UI supplied by the app so the wallet does not depend on another feature module. */
@Immutable
class WalletCashSlots(
    val balanceSummary: @Composable (account: CashAccount, onEdit: () -> Unit, modifier: Modifier,
        allAccounts: List<CashAccount>, accountLabels: Map<Long, String>) -> Unit,
    val entryItem: @Composable (entry: CashEntry, showDivider: Boolean, onOpen: () -> Unit) -> Unit
)

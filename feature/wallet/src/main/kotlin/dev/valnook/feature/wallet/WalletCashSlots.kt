package dev.valnook.feature.wallet

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import dev.valnook.domain.model.CashAccount
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.model.CashTrendPoint
import java.time.LocalDate

/** Cash UI supplied by the app so the wallet does not depend on another feature module. */
@Immutable
class WalletCashSlots(
    val balanceSummary: @Composable (account: CashAccount, parentName: String, trend: List<CashTrendPoint>,
        onEdit: () -> Unit, modifier: Modifier, allAccounts: List<CashAccount>, accountLabels: Map<Long, String>) -> Unit,
    /** One day's changes in one card, newest first. */
    val dayGroup: @Composable (date: LocalDate, entries: List<CashEntry>, onOpen: (Long) -> Unit) -> Unit,
    val emptyMonth: @Composable (modifier: Modifier) -> Unit
)

package dev.valnook.app.navigation

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.valnook.feature.accounts.AccountEditToolbarState
import dev.valnook.feature.accounts.AccountGroupsToolbarState
import dev.valnook.feature.cash.CashBalanceToolbarState
import dev.valnook.feature.deposits.DepositToolbarState
import dev.valnook.feature.investments.DividendToolbarState
import dev.valnook.feature.investments.InstrumentEditToolbarState
import dev.valnook.feature.investments.TypeEditToolbarState
import dev.valnook.feature.wallet.WalletFaceLibraryToolbar

/**
 * What the form and list pages put in the title bar: each page reports its own through the `on…` setter, which stays
 * the same object for the session, and the bar shows the current page's (see [RootTopBarActions]).
 */
@Stable
internal class RootToolbars {
    var balance by mutableStateOf<CashBalanceToolbarState?>(null); private set
    val onBalance: (CashBalanceToolbarState?) -> Unit = { balance = it }
    var dividend by mutableStateOf<DividendToolbarState?>(null); private set
    val onDividend: (DividendToolbarState?) -> Unit = { dividend = it }
    var instrument by mutableStateOf<InstrumentEditToolbarState?>(null); private set
    val onInstrument: (InstrumentEditToolbarState?) -> Unit = { instrument = it }
    var type by mutableStateOf<TypeEditToolbarState?>(null); private set
    val onType: (TypeEditToolbarState?) -> Unit = { type = it }
    var deposit by mutableStateOf<DepositToolbarState?>(null); private set
    val onDeposit: (DepositToolbarState?) -> Unit = { deposit = it }
    var accountEdit by mutableStateOf<AccountEditToolbarState?>(null); private set
    val onAccountEdit: (AccountEditToolbarState?) -> Unit = { accountEdit = it }
    /** The account editor's sub-account sorting, when it can open. */
    var editSort by mutableStateOf<(() -> Unit)?>(null); private set
    val onEditSort: ((() -> Unit)?) -> Unit = { editSort = it }
    var accountGroups by mutableStateOf<AccountGroupsToolbarState?>(null); private set
    val onAccountGroups: (AccountGroupsToolbarState?) -> Unit = { accountGroups = it }
    var bills by mutableStateOf<dev.valnook.feature.bills.BillsToolbarState?>(null); private set
    var billCategories by mutableStateOf<dev.valnook.feature.bills.BillCategoriesToolbarState?>(null); private set
    val onBillCategories: (dev.valnook.feature.bills.BillCategoriesToolbarState?) -> Unit = { billCategories = it }
    val onBills: (dev.valnook.feature.bills.BillsToolbarState?) -> Unit = { bills = it }
    var walletFaces by mutableStateOf<WalletFaceLibraryToolbar?>(null); private set
    val onWalletFaces: (WalletFaceLibraryToolbar?) -> Unit = { walletFaces = it }
}

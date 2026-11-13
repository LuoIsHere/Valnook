package dev.valnook.app.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.*
import dev.valnook.app.R
import dev.valnook.designsystem.MenuIcon
import dev.valnook.designsystem.SortIcon
import dev.valnook.feature.settings.NavigationToolbarState
import dev.valnook.feature.settings.NavigationSettingsActions
import dev.valnook.feature.wallet.*

/** The title bar's text for [current]; names of an account, an instrument or a sub-account come from the root's reads. */
@Composable
internal fun rootTitle(current: NavKey, walletTitle: String?, accountName: (Long) -> String,
    instrumentName: (Long) -> String?, cashName: (Long) -> String?): String = when (current) {
    WalletKey, HiddenWalletKey -> walletTitle ?: stringResource(R.string.nav_wallet)
    BillsKey, HiddenBillsKey -> stringResource(R.string.nav_bills)
    is BillEditorKey -> stringResource(if (current.billId != null) R.string.title_edit_bill else R.string.title_new_bill)
    is BillDetailKey -> stringResource(R.string.title_bill_detail)
    is BillSettleKey -> stringResource(if (current.type == "REFUND") R.string.title_bill_refund else R.string.title_bill_reimbursement)
    BillCategoriesKey -> stringResource(R.string.title_bill_categories)
    ReimbursementsKey -> stringResource(R.string.title_reimbursements)
    AccountsKey, HiddenAccountsKey -> stringResource(R.string.nav_accounts)
    InvestmentsKey, HiddenInvestmentsKey -> stringResource(R.string.nav_investments)
    StatisticsKey, HiddenStatisticsKey -> stringResource(R.string.nav_statistics)
    SettingsKey -> stringResource(R.string.nav_settings)
    AboutKey -> stringResource(dev.valnook.feature.settings.R.string.settings_about)
    PrivacyPolicyKey -> stringResource(dev.valnook.feature.settings.R.string.settings_privacy_policy)
    LicenseKey -> stringResource(dev.valnook.feature.settings.R.string.settings_license)
    ThirdPartyKey -> stringResource(dev.valnook.feature.settings.R.string.settings_third_party)
    NavigationSettingsKey -> stringResource(R.string.title_navigation_settings)
    FxSettingsKey -> stringResource(R.string.title_exchange_rates)
    LanguageSettingsKey -> stringResource(R.string.title_language)
    GainLossColorsKey -> stringResource(R.string.title_gain_loss_colors)
    ClearDataKey -> stringResource(R.string.title_clear_data)
    BackupKey -> stringResource(dev.valnook.feature.backup.R.string.backup_title)
    WebAdminKey -> stringResource(dev.valnook.feature.webadmin.R.string.webadmin_title)
    WalletFaceLibraryKey -> stringResource(R.string.title_card_faces)
    is AccountKey -> accountName(current.id)
    is AccountEditKey -> stringResource(R.string.title_edit_account)
    AccountGroupsKey -> stringResource(dev.valnook.feature.accounts.R.string.account_groups_title)
    is InstrumentLibraryKey -> stringResource(R.string.title_instrument_library)
    RealizedProfitKey -> stringResource(dev.valnook.feature.investments.R.string.realized_title)
    is TypeEditKey -> dev.valnook.feature.investments.typeEditTitle(current.id)
    TypesKey -> stringResource(dev.valnook.feature.investments.R.string.instrument_manage_types)
    is InstrumentEditKey -> dev.valnook.feature.investments.instrumentEditTitle(current.id)
    is DividendFormKey -> dev.valnook.feature.investments.dividendFormTitle(current.tradeId == null)
    is InvestmentAccountPickKey -> dev.valnook.feature.investments.accountPickTitle(current.direction)
    is AccountInvestmentsKey -> stringResource(if (current.all) dev.valnook.feature.investments.R.string.investment_closed_positions else dev.valnook.feature.investments.R.string.investment_current_holdings)
    is InstrumentKey -> instrumentName(current.id).orEmpty()
    is PositionCreateKey -> stringResource(dev.valnook.feature.investments.R.string.investment_add_to_account)
    is CashKey -> cashName(current.cashAccountId).orEmpty()
    is CashBalanceEditKey -> stringResource(R.string.title_edit_balance)
    is DepositFormKey -> dev.valnook.feature.deposits.depositFormTitle(current.mode)
    is DepositDetailKey -> stringResource(dev.valnook.core.designsystem.R.string.deposit_detail_title)
    is SettledDepositsKey -> stringResource(dev.valnook.core.designsystem.R.string.settled_deposits)
    else -> "Valnook"
}

/**
 * The title bar's buttons for [current]. Forms and lists report theirs through [toolbars]; deleting an account, a
 * sub-account or an instrument asks the root, which shows the confirmation and leaves the pages about it.
 */
@Composable
internal fun RootTopBarActions(current: NavKey, walletDetail: Boolean, wallet: WalletToolbar?,
    navigation: NavigationToolbarState?, fx: dev.valnook.feature.settings.FxToolbarState?, toolbars: RootToolbars,
    open: (NavKey) -> Unit, onDeleteAccount: (accountId: Long, cashId: Long?) -> Unit, onDeleteInstrument: (Long) -> Unit) {
    val addAccountDescription = stringResource(R.string.nav_add_account)
    val instrumentLibraryDescription = stringResource(R.string.nav_instrument_library)
    val accountGroupsDescription = stringResource(dev.valnook.feature.accounts.R.string.account_groups_title)
    val subaccountSortDescription = stringResource(dev.valnook.feature.accounts.R.string.account_order_children)
    when (current) {
        BillsKey, HiddenBillsKey -> {
            val searching = toolbars.bills?.searching == true
            val searchLabel = stringResource(if (searching) R.string.nav_bill_search_close else R.string.nav_bill_search)
            IconButton({ toolbars.bills?.toggleSearch?.invoke() }, Modifier.testTag("bills-search-toggle")
                .semantics { contentDescription = searchLabel }) {
                dev.valnook.designsystem.FaIcon(if (searching) dev.valnook.designsystem.FaIcons.xmark
                    else dev.valnook.designsystem.FaIcons.search, null, Modifier.size(20.dp))
            }
            val categoriesLabel = stringResource(R.string.nav_bill_categories)
            IconButton({ open(BillCategoriesKey) }, Modifier.testTag("bills-categories")
                .semantics { contentDescription = categoriesLabel }) {
                dev.valnook.designsystem.FaIcon(dev.valnook.designsystem.FaIcons.tags, null, Modifier.size(20.dp))
            }
        }
        BillCategoriesKey -> {
            val addLabel = stringResource(R.string.nav_add_bill_category)
            IconButton({ toolbars.billCategories?.add?.invoke() }, Modifier.testTag("bill-add-category")
                .semantics { contentDescription = addLabel }) { dev.valnook.designsystem.AddIcon() }
        }
        WalletKey, HiddenWalletKey -> if (!walletDetail) {
            val addCardDescription = dev.valnook.feature.wallet.walletAddLabel()
            IconButton({ wallet?.add?.invoke() }, Modifier.testTag("wallet-add").semantics {
                contentDescription = addCardDescription
            }) { dev.valnook.designsystem.AddIcon() }
        } else {
            wallet?.flip?.let { flip ->
                val description=if(wallet?.flipped==true)
                    dev.valnook.feature.wallet.walletFlipLabel(false) else dev.valnook.feature.wallet.walletFlipLabel(true)
                IconButton(flip,Modifier.testTag("wallet-flip").semantics{contentDescription=description},
                    enabled=wallet?.enabled==true) {
                    if(wallet?.flipLoading==true)CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp)
                    else dev.valnook.feature.wallet.WalletFlipIcon()
                }
            }
            val editLabel=stringResource(R.string.nav_edit)
            IconButton({wallet?.edit?.invoke()},
                Modifier.testTag("wallet-edit").semantics {contentDescription=editLabel},enabled=wallet?.enabled==true) {
                dev.valnook.designsystem.EditIcon()
            }
            dev.valnook.designsystem.DeleteIconButton(stringResource(R.string.wallet_delete_card),
                {wallet?.delete?.invoke()},Modifier.testTag("wallet-delete"),enabled=wallet?.enabled==true)
        }
        NavigationSettingsKey -> NavigationSettingsActions(navigation)
        FxSettingsKey -> dev.valnook.feature.settings.FxSettingsActions(fx)
        AccountGroupsKey -> dev.valnook.feature.accounts.AccountGroupsActions(toolbars.accountGroups)
        WalletFaceLibraryKey -> dev.valnook.feature.wallet.WalletFaceLibraryActions(toolbars.walletFaces)
        AccountsKey, HiddenAccountsKey -> {
            IconButton({ open(AccountGroupsKey) }, Modifier.testTag("account-groups-open").semantics {
                contentDescription = accountGroupsDescription
            }) { dev.valnook.designsystem.GroupsIcon() }
            IconButton({ open(AccountEditKey()) }, Modifier.semantics {
                contentDescription = addAccountDescription
            }) { dev.valnook.designsystem.AddIcon() }
        }
        InvestmentsKey, HiddenInvestmentsKey -> IconButton({ open(InstrumentLibraryKey) }, Modifier.semantics {
            contentDescription = instrumentLibraryDescription
        }) { MenuIcon() }
        InstrumentLibraryKey -> dev.valnook.feature.investments.InstrumentLibraryActions(
            { open(TypesKey) }, { open(InstrumentEditKey()) })
        is TypeEditKey -> dev.valnook.feature.investments.TypeEditActions(toolbars.type)
        is InstrumentEditKey -> {
            current.id?.let { id -> dev.valnook.designsystem.DeleteIconButton(
                stringResource(dev.valnook.feature.investments.R.string.instrument_delete),
                { onDeleteInstrument(id) }, Modifier.testTag("instrument-delete-open")) }
            dev.valnook.feature.investments.InstrumentEditSaveAction(toolbars.instrument)
        }
        TypesKey -> dev.valnook.feature.investments.TypesActions { open(TypeEditKey()) }
        is DividendFormKey -> dev.valnook.feature.investments.DividendSaveAction(toolbars.dividend)
        is AccountKey -> dev.valnook.designsystem.CompactEditButton(stringResource(R.string.nav_edit),
            { open(AccountEditKey(current.id)) })
        is InstrumentKey -> dev.valnook.designsystem.CompactEditButton(stringResource(R.string.nav_edit),
            { open(InstrumentEditKey(current.id)) })
        is CashBalanceEditKey -> dev.valnook.feature.cash.CashBalanceSaveAction(toolbars.balance)
        is DepositFormKey -> dev.valnook.feature.deposits.DepositSaveAction(toolbars.deposit)
        is CashKey -> dev.valnook.designsystem.DeleteIconButton(
            stringResource(dev.valnook.feature.accounts.R.string.account_delete_title),
            { onDeleteAccount(current.accountId, current.cashAccountId) }, Modifier.testTag("cash-delete-open"))
        is AccountEditKey -> {
            current.id?.let { id -> dev.valnook.designsystem.DeleteIconButton(
                stringResource(dev.valnook.feature.accounts.R.string.account_delete_title),
                { onDeleteAccount(id, null) }, Modifier.testTag("account-delete-open"),
                enabled = toolbars.accountEdit?.enabled == true) }
            IconButton({ toolbars.editSort?.invoke() },
            Modifier.testTag("subaccount-order-open").semantics { contentDescription = subaccountSortDescription },
            enabled = toolbars.editSort != null) { SortIcon() }
            dev.valnook.feature.accounts.AccountEditSaveAction(toolbars.accountEdit)
        }
        else -> Unit
    }
}

package dev.valnook.app.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import dev.valnook.domain.model.Direction
import dev.valnook.feature.investments.TradeFormMode
import dev.valnook.feature.deposits.DepositFormMode

@Serializable data object AccountsKey : NavKey
@Serializable data object InvestmentsKey : NavKey
@Serializable data object SettingsKey : NavKey
@Serializable data class AccountKey(val id: Long) : NavKey
@Serializable data class AccountEditKey(val id: Long? = null) : NavKey
@Serializable data class AccountInvestmentsKey(val accountId: Long, val all: Boolean = false) : NavKey
@Serializable data class AssetKey(val account_id: Long, val id: Long) : NavKey
@Serializable data class InstrumentKey(val id: Long) : NavKey
@Serializable data object InstrumentLibraryKey : NavKey
@Serializable data class InstrumentEditKey(val id: Long? = null) : NavKey
@Serializable data object TypesKey : NavKey
@Serializable data class TypeEditKey(val id: Long? = null, val name: String = "") : NavKey
@Serializable data class CashKey(val account_id: Long, val currency: String) : NavKey
@Serializable data class CashEntryKey(val account_id: Long, val entry_id: Long) : NavKey
@Serializable data class CashEntryEditKey(val accountId: Long, val entryId: Long) : NavKey
@Serializable data class TradeDetailKey(val account_id: Long, val trade_id: Long) : NavKey
@Serializable data class DepositDetailKey(val account_id: Long, val deposit_id: Long) : NavKey
@Serializable data class SettledDepositsKey(val accountId: Long) : NavKey
@Serializable data class TradeFormKey(val accountId: Long, val mode: TradeFormMode,
    val instrumentId: Long? = null, val positionId: Long? = null, val tradeId: Long? = null,
    val direction: Direction = Direction.BUY) : NavKey
@Serializable data class DepositFormKey(val accountId: Long, val mode: DepositFormMode, val id: Long? = null) : NavKey

internal fun NavKey.isRoot(): Boolean = this == AccountsKey || this == InvestmentsKey || this == SettingsKey

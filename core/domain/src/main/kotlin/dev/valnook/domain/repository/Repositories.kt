package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

interface AccountRepository {
    fun observe_accounts(): Flow<List<SavingsAccount>>
}
interface CashRepository {
    fun observe_cash(account_id: Long): Flow<List<CashAccount>>
    fun observeCashAccount(cashAccountId: Long): Flow<CashAccount?>
    fun observeCashEntries(cashAccountId: Long, limit: Int): Flow<List<CashEntry>>
    fun observeCashEntry(cashAccountId: Long, entryId: Long): Flow<CashEntry?>
    fun observe_entries(account_id: Long, currency_code: String, limit: Int): Flow<List<CashEntry>>
    fun observe_entry(account_id: Long, entry_id: Long): Flow<CashEntry?>
}
interface DepositRepository {
    suspend fun get_deposit(id: Long): TermDeposit?
    fun observe_deposit(account_id: Long, id: Long): Flow<TermDeposit?>
    fun observe_deposits(account_id: Long, limit: Int, closed: Boolean = false): Flow<List<TermDeposit>>
}
interface InvestmentRepository {
    suspend fun get_trade(id: Long): Trade?
    fun observe_trade(account_id: Long, id: Long): Flow<Trade?>
    fun observe_investment(id: Long): Flow<Investment?>
    fun observe_investments(account_id: Long, limit: Int, section: InvestmentSection = InvestmentSection.HOLDING): Flow<List<Investment>>
    fun observe_profit(id: Long): Flow<InvestmentProfit?>
    fun observe_types(): Flow<List<AssetType>>
    fun observe_trade_revision(investment_id: Long): Flow<Long>
    suspend fun trade_page(investment_id: Long, cursor: TradeCursor?, limit: Int = 50): List<Trade>
}
/** Each command is committed as ONE transaction by the implementation. */
interface FinancialCommands {
    suspend fun execute(command: FinancialCommand): OperationResult
    /** Reconcile a possibly committed request using the original operation ID. */
    suspend fun operationResult(operationId: String): OperationResult? = null
}

sealed interface FinancialCommand { val operation_id: String }
data class SetCashBalance(override val operation_id: String, val account_id: Long,
    val currency_code: String, val balance_minor: Long, val expected_revision: Long?,
    val cashAccountId: Long? = null, val name: String = "", val note: String = "") : FinancialCommand
data class OpenTermDeposit(override val operation_id: String, val account_id: Long,
    val currency_code: String, val principal_minor: Long, val annual_rate_percent_e8: Long,
    val start_epoch_day: Long, val end_epoch_day: Long, val cash_linked: Boolean,
    val cashAccountId: Long? = null) : FinancialCommand
data class CloseTermDeposit(override val operation_id: String, val deposit_id: Long,
    val cash_linked: Boolean, val cashAccountId: Long? = null) : FinancialCommand
data class EditTermDeposit(override val operation_id: String, val deposit_id: Long, val expected_revision: Long,
    val principal_minor: Long, val annual_rate_percent_e8: Long, val start_epoch_day: Long,
    val end_epoch_day: Long, val open_cash_linked: Boolean, val close_cash_linked: Boolean?,
    val openCashAccountId: Long? = null, val closeCashAccountId: Long? = null) : FinancialCommand
data class SetOpeningInvestmentCost(override val operation_id: String, val investment_id: Long,
    val expected_revision: Long, val price_e8: Long) : FinancialCommand
data class RecordInvestmentTrade(override val operation_id: String, val investment_id: Long,
    val direction: Direction, val quantity_e8: Long, val execution_price_e8: Long,
    val occurred_at_ms: Long, val cash_linked: Boolean, val cashAccountId: Long? = null,
    val fee_minor: Long = 0) : FinancialCommand

data class EditInvestmentTrade(override val operation_id: String, val trade_id: Long,
    val expected_revision: Long, val direction: Direction, val quantity_e8: Long,
    val execution_price_e8: Long, val occurred_at_ms: Long, val cash_linked: Boolean,
    val cashAccountId: Long? = null, val fee_minor: Long = 0) : FinancialCommand

data class DeleteInvestmentTrade(override val operation_id: String, val trade_id: Long,
    val expected_revision: Long) : FinancialCommand

data class EditCashEntry(override val operation_id: String, val entry_id: Long,
    val expected_revision: Long, val delta_minor: Long, val occurred_at_ms: Long,
    val note: String) : FinancialCommand

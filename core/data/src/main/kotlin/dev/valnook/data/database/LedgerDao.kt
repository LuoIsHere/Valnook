package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface LedgerDao {
    @Query("SELECT id,name,note,created_at_ms,updated_at_ms FROM savings_accounts ORDER BY id")
    fun accounts(): Flow<List<AccountEntity>>
    @Query("SELECT id,name,note,created_at_ms,updated_at_ms FROM savings_accounts WHERE id=:id")
    suspend fun account(id: Long): AccountEntity?
    @Insert suspend fun insert_account(value: AccountEntity): Long
    @Query("UPDATE savings_accounts SET name=:name,note=:note,updated_at_ms=:now WHERE id=:id")
    suspend fun edit_account(id: Long, name: String, note: String, now: Long): Int
    @Query("SELECT code,fraction_digits FROM currencies WHERE code=:code")
    suspend fun currency(code: String): CurrencyEntity?
    @Query("SELECT savings_account_id,currency_code,balance_minor,revision,updated_at_ms FROM cash_balances WHERE savings_account_id=:account_id ORDER BY currency_code")
    fun cash(account_id: Long): Flow<List<CashEntity>>
    @Query("SELECT savings_account_id,currency_code,balance_minor,revision,updated_at_ms FROM cash_balances WHERE savings_account_id=:account_id AND currency_code=:code")
    suspend fun cash_one(account_id: Long, code: String): CashEntity?
    @Insert suspend fun insert_cash(value: CashEntity)
    @Query("UPDATE cash_balances SET balance_minor=:balance,revision=:revision,updated_at_ms=:now WHERE savings_account_id=:account_id AND currency_code=:code AND revision=:old_revision")
    suspend fun update_cash(account_id: Long, code: String, balance: Long, revision: Long, old_revision: Long, now: Long): Int
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:account_id AND status=:status ORDER BY start_epoch_day DESC,id DESC LIMIT :limit")
    fun deposits(account_id: Long, limit: Int, status:String): Flow<List<DepositEntity>>
    @Query("SELECT id,savings_account_id,currency_code,principal_minor,annual_rate_percent_e8,start_epoch_day,end_epoch_day,interest_rule,calculation_version,rounding_mode,expected_interest_minor,status,open_cash_linked,close_cash_linked,open_operation_id,close_operation_id,closed_at_ms,created_at_ms,updated_at_ms,revision FROM term_deposits WHERE id=:id")
    suspend fun deposit(id: Long): DepositEntity?
    @Query("SELECT * FROM term_deposits WHERE savings_account_id=:account_id AND id=:id")
    fun observe_deposit(account_id: Long, id: Long): Flow<DepositEntity?>
    @Insert suspend fun insert_deposit(value: DepositEntity): Long
    @Query("UPDATE term_deposits SET revision=revision+1,status='CLOSED',close_cash_linked=:linked,close_operation_id=:operation_id,closed_at_ms=:now,updated_at_ms=:now WHERE id=:id AND status='OPEN'")
    suspend fun close_deposit(id: Long, linked: Boolean, operation_id: String, now: Long): Int
    @Query("UPDATE term_deposits SET principal_minor=:principal,annual_rate_percent_e8=:rate,start_epoch_day=:start,end_epoch_day=:end,expected_interest_minor=:interest,open_cash_linked=:opened,close_cash_linked=:closed,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision")
    suspend fun edit_deposit(id: Long, old_revision: Long, principal: Long, rate: Long, start: Long, end: Long,
        interest: Long, opened: Boolean, closed: Boolean?, now: Long): Int
    @Query("SELECT id,name,normalized_name,created_at_ms,updated_at_ms FROM asset_types ORDER BY normalized_name,id")
    fun types(): Flow<List<TypeEntity>>
    @Query("SELECT id,name,normalized_name,created_at_ms,updated_at_ms FROM asset_types WHERE id=:id")
    suspend fun type(id: Long): TypeEntity?
    @Query("SELECT id FROM asset_types WHERE normalized_name=:normalized")
    suspend fun type_by_name(normalized: String): Long?
    @Insert suspend fun insert_type(value: TypeEntity): Long
    @Query("UPDATE asset_types SET name=:name,normalized_name=:normalized,updated_at_ms=:now WHERE id=:id")
    suspend fun edit_type(id: Long, name: String, normalized: String, now: Long): Int
    @Query("SELECT * FROM investments WHERE id=:id")
    suspend fun investment(id: Long): InvestmentEntity?
    @Query("SELECT i.*,t.name AS type_name FROM investments i JOIN asset_types t ON t.id=i.asset_type_id WHERE i.id=:id")
    fun investment_detail(id: Long): Flow<InvestmentWithType?>
    @Insert suspend fun insert_investment(value: InvestmentEntity): Long
    @Query("UPDATE investments SET holding_quantity_e8=:holding,revision=:revision,updated_at_ms=:now,position_state=CASE WHEN :holding>0 THEN 'HOLDING' WHEN EXISTS(SELECT 1 FROM investment_trades t WHERE t.investment_id=:id AND t.is_deleted=0) THEN 'CLOSED' ELSE 'PENDING' END,last_activity_at_ms=COALESCE((SELECT MAX(occurred_at_ms) FROM investment_trades t WHERE t.investment_id=:id AND t.is_deleted=0),created_at_ms) WHERE id=:id AND revision=:old_revision")
    suspend fun update_holding(id: Long, holding: Long, revision: Long, old_revision: Long, now: Long): Int
    @Query("UPDATE investments SET opening_cost_price_e8=:price,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision")
    suspend fun update_opening_cost(id:Long,price:Long,old_revision:Long,now:Long):Int
    @Query("UPDATE investments SET current_price_e8=:price,price_updated_at_ms=:now,updated_at_ms=:now WHERE id=:id")
    suspend fun update_price(id: Long, price: Long, now: Long): Int
    @Query("UPDATE investments SET name=:name,symbol=:symbol,asset_type_id=:type_id,updated_at_ms=:now WHERE id=:id")
    suspend fun edit_investment(id: Long, name: String, symbol: String, type_id: Long, now: Long): Int
    @Query("SELECT id,investment_id,operation_id,direction,quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,occurred_at_ms,created_at_ms,revision,is_deleted,updated_at_ms FROM investment_trades WHERE investment_id=:investment_id AND is_deleted=0 ORDER BY occurred_at_ms DESC,id DESC LIMIT :limit")
    suspend fun first_trades(investment_id: Long, limit: Int): List<TradeEntity>
    @Query("SELECT id,investment_id,operation_id,direction,quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,occurred_at_ms,created_at_ms,revision,is_deleted,updated_at_ms FROM investment_trades WHERE investment_id=:investment_id AND is_deleted=0 AND (occurred_at_ms < :time OR (occurred_at_ms = :time AND id < :id)) ORDER BY occurred_at_ms DESC,id DESC LIMIT :limit")
    suspend fun next_trades(investment_id: Long, time: Long, id: Long, limit: Int): List<TradeEntity>
    @Query("SELECT revision FROM investments WHERE id=:investment_id")
    fun trade_revision(investment_id: Long): Flow<Long>
    @Insert suspend fun insert_trade(value: TradeEntity): Long
    @Query("SELECT id,investment_id,operation_id,direction,quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,occurred_at_ms,created_at_ms,revision,is_deleted,updated_at_ms FROM investment_trades WHERE id=:id")
    suspend fun trade(id: Long): TradeEntity?
    @Query("SELECT t.* FROM investment_trades t INNER JOIN investments i ON i.id=t.investment_id WHERE i.savings_account_id=:account_id AND t.id=:id AND t.is_deleted=0")
    fun observe_trade(account_id: Long, id: Long): Flow<TradeEntity?>
    @Query("UPDATE investment_trades SET direction=:direction,quantity_e8=:quantity,execution_price_e8=:price,amount_minor=:amount,cash_linked=:linked,occurred_at_ms=:occurred,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision AND is_deleted=0")
    suspend fun edit_trade(id: Long, old_revision: Long, direction: String, quantity: Long, price: Long, amount: Long, linked: Boolean, occurred: Long, now: Long): Int
    @Query("UPDATE investment_trades SET is_deleted=1,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision AND is_deleted=0")
    suspend fun delete_trade(id: Long, old_revision: Long, now: Long): Int
    @Query("SELECT e.id,e.original_operation_id,e.savings_account_id,e.currency_code,e.source_kind,e.source_id,e.delta_minor,e.occurred_at_ms,e.note,e.revision,e.is_deleted,e.created_at_ms,e.updated_at_ms,t.investment_id FROM cash_entries e LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id WHERE e.savings_account_id=:account_id AND e.currency_code=:code AND e.is_deleted=0 ORDER BY e.occurred_at_ms DESC,e.id DESC LIMIT :limit")
    fun cash_entries(account_id: Long, code: String, limit: Int): Flow<List<CashEntryWithSource>>
    @Query("SELECT e.*,t.investment_id FROM cash_entries e LEFT JOIN investment_trades t ON e.source_kind='TRADE' AND t.id=e.source_id WHERE e.savings_account_id=:account_id AND e.id=:entry_id AND e.is_deleted=0")
    fun observe_cash_entry(account_id: Long, entry_id: Long): Flow<CashEntryWithSource?>
    @Query("SELECT id,original_operation_id,savings_account_id,currency_code,source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms FROM cash_entries WHERE id=:id")
    suspend fun cash_entry(id: Long): CashEntryEntity?
    @Query("SELECT id,original_operation_id,savings_account_id,currency_code,source_kind,source_id,delta_minor,occurred_at_ms,note,revision,is_deleted,created_at_ms,updated_at_ms FROM cash_entries WHERE source_kind=:kind AND source_id=:source_id")
    suspend fun source_entry(kind: String, source_id: Long): CashEntryEntity?
    @Insert suspend fun insert_entry(value: CashEntryEntity): Long
    @Query("UPDATE cash_entries SET delta_minor=:delta,occurred_at_ms=:occurred,note=:note,is_deleted=:deleted,revision=revision+1,updated_at_ms=:now WHERE id=:id AND revision=:old_revision")
    suspend fun edit_entry(id: Long, old_revision: Long, delta: Long, occurred: Long, note: String, deleted: Boolean, now: Long): Int
    @Query("SELECT operation_id,kind,request_fingerprint,result_kind,result_id,created_at_ms FROM operations WHERE operation_id=:id")
    suspend fun operation(id: String): OperationEntity?
    @Insert suspend fun insert_operation(value: OperationEntity)
    @Query("UPDATE operations SET result_kind=:kind,result_id=:id WHERE operation_id=:operation_id")
    suspend fun complete_operation(operation_id: String, kind: String, id: Long)
    @Insert suspend fun insert_movement(value: MovementEntity): Long
}

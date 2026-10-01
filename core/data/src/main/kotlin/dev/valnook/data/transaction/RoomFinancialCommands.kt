package dev.valnook.data.transaction

import androidx.room.withTransaction
import dev.valnook.data.database.*
import dev.valnook.data.repository.valid_name
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import java.time.Clock
import java.time.LocalDate
import java.security.MessageDigest
import java.util.UUID

enum class TransactionPoint { AFTER_BUSINESS, AFTER_CASH }
/** Fault injection is for isolated database tests; production supplies the default no-op. */
class RoomFinancialCommands(private val db: ValnookDatabase,private val clock: Clock,
    private val fault: (TransactionPoint) -> Unit = {}) : FinancialCommands {
    private val dao = db.ledger()
    private fun fingerprint(command: FinancialCommand): Pair<String,String> {
        val fields: List<Any?> = when(command) {
            is SetCashBalance -> listOf("CASH_SET",command.account_id,Currency.of(command.currency_code).code,command.balance_minor,command.expected_revision)
            is OpenTermDeposit -> listOf("TERM_OPEN",command.account_id,Currency.of(command.currency_code).code,command.principal_minor,
                command.annual_rate_percent_e8,command.start_epoch_day,command.end_epoch_day,command.cash_linked)
            is CloseTermDeposit -> listOf("TERM_CLOSE",command.deposit_id,command.cash_linked)
            is EditTermDeposit -> listOf("TERM_EDIT",command.deposit_id,command.expected_revision,command.principal_minor,
                command.annual_rate_percent_e8,command.start_epoch_day,command.end_epoch_day,command.open_cash_linked,command.close_cash_linked)
            is CreateInvestment -> listOf("INVESTMENT_CREATE",command.account_id,command.name.trim(),command.symbol.trim(),
                command.type_id,Currency.of(command.currency_code).code,command.opening_quantity_e8,command.current_price_e8)+
                (command.opening_cost_price_e8?.let{listOf("OPENING_COST",it)} ?: emptyList())
            is SetOpeningInvestmentCost -> listOf("OPENING_COST",command.investment_id,command.expected_revision,command.price_e8)
            is RecordInvestmentTrade -> listOf(command.direction.name,command.investment_id,command.quantity_e8,
                command.execution_price_e8,command.occurred_at_ms,command.cash_linked)
            is EditInvestmentTrade -> listOf("TRADE_EDIT",command.trade_id,command.expected_revision,command.direction,
                command.quantity_e8,command.execution_price_e8,command.occurred_at_ms,command.cash_linked)
            is DeleteInvestmentTrade -> listOf("TRADE_DELETE",command.trade_id,command.expected_revision)
            is EditCashEntry -> listOf("CASH_EDIT",command.entry_id,command.expected_revision,command.delta_minor,
                command.occurred_at_ms,command.note.trim())
        }
        val canonical = fields.joinToString("") { field -> val s=field?.toString() ?: "<null>"; "${s.length}:$s" }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return fields.first().toString() to digest
    }
    override suspend fun execute(command: FinancialCommand): OperationResult {
        try { UUID.fromString(command.operation_id) } catch(_: IllegalArgumentException) { throw DomainException(ErrorCode.FORMAT) }
        val (kind,digest) = fingerprint(command)
        return db.withTransaction {
            val old = dao.operation(command.operation_id)
            if(old!=null) {
                if(old.kind!=kind || old.request_fingerprint!=digest) throw DomainException(ErrorCode.OPERATION_CONFLICT)
                return@withTransaction OperationResult(requireNotNull(old.result_kind),requireNotNull(old.result_id))
            }
            val now = clock.millis()
            // The temporary operation and final result live within the SAME transaction.
            dao.insert_operation(OperationEntity(command.operation_id,kind,digest,null,null,now))
            val result = when(command) {
                is SetCashBalance -> set_cash(command,now)
                is OpenTermDeposit -> open_deposit(command,now)
                is CloseTermDeposit -> close_deposit(command,now)
                is EditTermDeposit -> edit_deposit(command,now)
                is CreateInvestment -> create_investment(command,now)
                is SetOpeningInvestmentCost -> set_opening_cost(command,now)
                is RecordInvestmentTrade -> trade(command,now)
                is EditInvestmentTrade -> edit_trade(command,now)
                is DeleteInvestmentTrade -> delete_trade(command,now)
                is EditCashEntry -> edit_cash_entry(command,now)
            }
            dao.complete_operation(command.operation_id,result.kind,result.id)
            result
        }
    }
    private suspend fun currency(code: String): Currency {
        val value = Currency.of(code)
        val stored = dao.currency(value.code) ?: throw DomainException(ErrorCode.CURRENCY)
        if(stored.fraction_digits!=value.fraction_digits) throw DomainException(ErrorCode.CURRENCY)
        return value
    }
    private suspend fun account(id: Long) {
        if(dao.account(id)==null) throw DomainException(ErrorCode.NOT_FOUND)
    }
    private suspend fun change_cash(operation_id: String,account_id: Long,code: String,delta: Long,reason: String,now: Long): Long {
        val old = dao.cash_one(account_id,code)
        val before = old?.balance_minor ?: 0L
        val after = R.add(before,delta)
        if(after<0) throw DomainException(ErrorCode.INSUFFICIENT_CASH)
        if(old==null) dao.insert_cash(CashEntity(account_id,code,after,1,now))
        else if(dao.update_cash(account_id,code,after,R.add(old.revision,1),old.revision,now)!=1)
            throw DomainException(ErrorCode.STALE_BALANCE)
        val id=dao.insert_movement(MovementEntity(operation_id=operation_id,savings_account_id=account_id,
            currency_code=code,reason=reason,delta_minor=delta,balance_before_minor=before,balance_after_minor=after,created_at_ms=now))
        fault(TransactionPoint.AFTER_CASH)
        return id
    }
    private suspend fun set_cash(c: SetCashBalance,now: Long): OperationResult {
        account(c.account_id); val code=currency(c.currency_code).code
        R.check_nonnegative(c.balance_minor)
        val old=dao.cash_one(c.account_id,code)
        if(old?.revision!=c.expected_revision) throw DomainException(ErrorCode.STALE_BALANCE)
        val delta=Math.subtractExact(c.balance_minor,old?.balance_minor ?: 0)
        change_cash(c.operation_id,c.account_id,code,delta,"CASH_SET",now)
        val id=create_entry(c.operation_id,c.account_id,code,"CASH_SET",null,delta,now,now)
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("CASH_ENTRY",id)
    }
    private suspend fun open_deposit(c: OpenTermDeposit,now: Long): OperationResult {
        account(c.account_id); val code=currency(c.currency_code).code
        val interest=R.interest(c.principal_minor,c.annual_rate_percent_e8,c.start_epoch_day,c.end_epoch_day)
        val id=dao.insert_deposit(DepositEntity(savings_account_id=c.account_id,currency_code=code,
            principal_minor=c.principal_minor,annual_rate_percent_e8=c.annual_rate_percent_e8,
            start_epoch_day=c.start_epoch_day,end_epoch_day=c.end_epoch_day,expected_interest_minor=interest,
            open_cash_linked=c.cash_linked,open_operation_id=c.operation_id,created_at_ms=now,updated_at_ms=now))
        fault(TransactionPoint.AFTER_BUSINESS)
        if(c.cash_linked) {
            change_cash(c.operation_id,c.account_id,code,-c.principal_minor,"TERM_OPEN",now)
            create_entry(c.operation_id,c.account_id,code,"TERM_OPEN",id,-c.principal_minor,now,now)
        }
        return OperationResult("TERM_DEPOSIT",id)
    }
    private suspend fun close_deposit(c: CloseTermDeposit,now: Long): OperationResult {
        val value=dao.deposit(c.deposit_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if(value.status!="OPEN") throw DomainException(ErrorCode.ALREADY_CLOSED)
        R.add(value.revision,1)
        if(LocalDate.now(clock).toEpochDay()<value.end_epoch_day) throw DomainException(ErrorCode.NOT_MATURED)
        if(dao.close_deposit(value.id,c.cash_linked,c.operation_id,now)!=1) throw DomainException(ErrorCode.ALREADY_CLOSED)
        fault(TransactionPoint.AFTER_BUSINESS)
        if(c.cash_linked) {
            val returned=R.add(value.principal_minor,value.expected_interest_minor)
            change_cash(c.operation_id,value.savings_account_id,value.currency_code,returned,"TERM_CLOSE",now)
            create_entry(c.operation_id,value.savings_account_id,value.currency_code,"TERM_CLOSE",value.id,returned,now,now)
        }
        return OperationResult("TERM_DEPOSIT",value.id)
    }
    private suspend fun edit_deposit(c: EditTermDeposit,now: Long): OperationResult {
        val old=dao.deposit(c.deposit_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if(old.revision!=c.expected_revision)throw DomainException(ErrorCode.STALE_RECORD)
        R.add(old.revision,1);currency(old.currency_code)
        val closed=old.status=="CLOSED"
        if(closed!=(c.close_cash_linked!=null))throw DomainException(ErrorCode.FORMAT)
        if(closed&&LocalDate.now(clock).toEpochDay()<c.end_epoch_day)throw DomainException(ErrorCode.NOT_MATURED)
        val interest=R.interest(c.principal_minor,c.annual_rate_percent_e8,c.start_epoch_day,c.end_epoch_day)
        val old_return=R.add(old.principal_minor,old.expected_interest_minor)
        val new_return=R.add(c.principal_minor,interest)
        val old_effect=R.add(if(old.open_cash_linked)-old.principal_minor else 0,if(old.close_cash_linked==true)old_return else 0)
        val new_effect=R.add(if(c.open_cash_linked)-c.principal_minor else 0,if(c.close_cash_linked==true)new_return else 0)
        if(dao.edit_deposit(old.id,old.revision,c.principal_minor,c.annual_rate_percent_e8,c.start_epoch_day,
            c.end_epoch_day,interest,c.open_cash_linked,c.close_cash_linked,now)!=1)throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        if(old.open_cash_linked||old.close_cash_linked==true||c.open_cash_linked||c.close_cash_linked==true)
            change_cash(c.operation_id,old.savings_account_id,old.currency_code,R.replace_contribution(0,old_effect,new_effect),"TERM_EDIT",now)
        sync_deposit_entry(old,"TERM_OPEN",old.open_operation_id,old.open_cash_linked,c.open_cash_linked,
            -old.principal_minor,-c.principal_minor,old.created_at_ms,now)
        if(closed)sync_deposit_entry(old,"TERM_CLOSE",requireNotNull(old.close_operation_id),old.close_cash_linked==true,
            c.close_cash_linked==true,old_return,new_return,requireNotNull(old.closed_at_ms),now)
        return OperationResult("TERM_DEPOSIT",old.id)
    }
    private suspend fun sync_deposit_entry(deposit: DepositEntity,kind: String,original_operation: String,
        old_linked: Boolean,new_linked: Boolean,old_delta: Long,new_delta: Long,occurred: Long,now: Long) {
        val entry=dao.source_entry(kind,deposit.id)
        if(old_linked)check(entry!=null&&!entry.is_deleted&&entry.delta_minor==old_delta){"Inconsistent deposit source cash entry"}
        if(!old_linked&&!new_linked)return
        if(entry==null)create_entry(original_operation,deposit.savings_account_id,deposit.currency_code,kind,deposit.id,new_delta,occurred,now)
        else {
            R.add(entry.revision,1)
            if(dao.edit_entry(entry.id,entry.revision,if(new_linked)new_delta else entry.delta_minor,
                entry.occurred_at_ms,entry.note,!new_linked,now)!=1)throw DomainException(ErrorCode.STALE_RECORD)
        }
    }
    private suspend fun create_investment(c: CreateInvestment,now: Long): OperationResult {
        account(c.account_id); val code=currency(c.currency_code).code
        if(dao.type(c.type_id)==null) throw DomainException(ErrorCode.NOT_FOUND)
        R.check_nonnegative(c.opening_quantity_e8); R.check_nonnegative(c.current_price_e8)
        if(c.opening_quantity_e8>0 && c.opening_cost_price_e8==null)throw DomainException(ErrorCode.FORMAT)
        c.opening_cost_price_e8?.let{R.check_nonnegative(it,true);R.amount(c.opening_quantity_e8,it,Currency.of(code))}
        if(c.symbol.length>100) throw DomainException(ErrorCode.FORMAT)
        val id=dao.insert_investment(InvestmentEntity(savings_account_id=c.account_id,asset_type_id=c.type_id,
            name=valid_name(c.name),symbol=c.symbol.trim(),currency_code=code,opening_quantity_e8=c.opening_quantity_e8,
            holding_quantity_e8=c.opening_quantity_e8,current_price_e8=c.current_price_e8,price_updated_at_ms=now,
            revision=1,created_at_ms=now,updated_at_ms=now,opening_cost_price_e8=c.opening_cost_price_e8,
            position_state=if(c.opening_quantity_e8>0)"HOLDING" else "PENDING",last_activity_at_ms=now))
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("INVESTMENT",id)
    }
    private suspend fun set_opening_cost(c:SetOpeningInvestmentCost,now:Long):OperationResult {
        val asset=dao.investment(c.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if(asset.revision!=c.expected_revision)throw DomainException(ErrorCode.STALE_RECORD)
        if(asset.opening_quantity_e8==0L)throw DomainException(ErrorCode.FORMAT)
        R.check_nonnegative(c.price_e8,true);R.add(asset.revision,1)
        R.amount(asset.opening_quantity_e8,c.price_e8,currency(asset.currency_code))
        if(dao.update_opening_cost(asset.id,c.price_e8,asset.revision,now)!=1)throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("INVESTMENT",asset.id)
    }
    private suspend fun trade(c: RecordInvestmentTrade,now: Long): OperationResult {
        val value=dao.investment(c.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val amount=R.amount(c.quantity_e8,c.execution_price_e8,currency(value.currency_code),true)
        val holding=if(c.direction==Direction.BUY) R.add(value.holding_quantity_e8,c.quantity_e8)
            else value.holding_quantity_e8-c.quantity_e8
        if(holding<0) throw DomainException(ErrorCode.INSUFFICIENT_HOLDING)
        val id=dao.insert_trade(TradeEntity(investment_id=value.id,operation_id=c.operation_id,
            direction=c.direction.name,quantity_e8=c.quantity_e8,execution_price_e8=c.execution_price_e8,
            amount_minor=amount,currency_code=value.currency_code,cash_linked=c.cash_linked,
            occurred_at_ms=c.occurred_at_ms,created_at_ms=now,updated_at_ms=now))
        if(dao.update_holding(value.id,holding,R.add(value.revision,1),value.revision,now)!=1)
            throw DomainException(ErrorCode.STALE_BALANCE)
        fault(TransactionPoint.AFTER_BUSINESS)
        if(c.cash_linked) {
            val impact=impact(c.direction,amount)
            change_cash(c.operation_id,value.savings_account_id,value.currency_code,impact,c.direction.name,now)
            create_entry(c.operation_id,value.savings_account_id,value.currency_code,"TRADE",id,impact,c.occurred_at_ms,now)
        }
        return OperationResult("INVESTMENT_TRADE",id)
    }

    private fun impact(direction: Direction, amount: Long) = if(direction==Direction.BUY) -amount else amount
    private fun quantity(direction: Direction, value: Long) = if(direction==Direction.BUY) value else -value
    private suspend fun create_entry(operation_id: String,account_id: Long,code: String,kind: String,
        source_id: Long?,delta: Long,occurred: Long,now: Long) = dao.insert_entry(CashEntryEntity(
            original_operation_id=operation_id,savings_account_id=account_id,currency_code=code,
            source_kind=kind,source_id=source_id,delta_minor=delta,occurred_at_ms=occurred,
            note="",revision=1,is_deleted=false,created_at_ms=now,updated_at_ms=now))

    private suspend fun active_trade(id: Long,revision: Long): TradeEntity {
        val value=dao.trade(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if(value.is_deleted || value.revision!=revision) throw DomainException(ErrorCode.STALE_RECORD)
        R.add(value.revision,1)
        return value
    }
    private suspend fun sync_trade_cash(operation_id: String,old: TradeEntity,asset: InvestmentEntity,
        new_direction: Direction,new_amount: Long,new_linked: Boolean,occurred: Long,now: Long,reason: String) {
        val entry=dao.source_entry("TRADE",old.id)
        val old_impact=if(old.cash_linked) impact(Direction.valueOf(old.direction),old.amount_minor) else 0
        val new_impact=if(new_linked) impact(new_direction,new_amount) else 0
        if(old.cash_linked) check(entry!=null && !entry.is_deleted && entry.delta_minor==old_impact) {
            "Missing or inconsistent source cash entry"
        }
        if(old.cash_linked || new_linked) {
            change_cash(operation_id,asset.savings_account_id,asset.currency_code,
                R.replace_contribution(0,old_impact,new_impact),reason,now)
            if(entry==null) create_entry(old.operation_id,asset.savings_account_id,asset.currency_code,
                "TRADE",old.id,new_impact,occurred,now)
            else {
                R.add(entry.revision,1)
                if(dao.edit_entry(entry.id,entry.revision,if(new_linked)new_impact else entry.delta_minor,
                    occurred,entry.note,!new_linked,now)!=1) throw DomainException(ErrorCode.STALE_RECORD)
            }
        }
    }
    private suspend fun edit_trade(c: EditInvestmentTrade,now: Long): OperationResult {
        val old=active_trade(c.trade_id,c.expected_revision)
        val asset=dao.investment(old.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val amount=R.amount(c.quantity_e8,c.execution_price_e8,currency(asset.currency_code),true)
        val holding=R.replace_contribution(asset.holding_quantity_e8,
            quantity(Direction.valueOf(old.direction),old.quantity_e8),quantity(c.direction,c.quantity_e8))
        if(holding<0) throw DomainException(ErrorCode.INSUFFICIENT_HOLDING)
        if(dao.edit_trade(old.id,old.revision,c.direction.name,c.quantity_e8,c.execution_price_e8,amount,
            c.cash_linked,c.occurred_at_ms,now)!=1) throw DomainException(ErrorCode.STALE_RECORD)
        if(dao.update_holding(asset.id,holding,R.add(asset.revision,1),asset.revision,now)!=1)
            throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        sync_trade_cash(c.operation_id,old,asset,c.direction,amount,c.cash_linked,c.occurred_at_ms,now,"TRADE_EDIT")
        return OperationResult("INVESTMENT_TRADE",old.id)
    }
    private suspend fun delete_trade(c: DeleteInvestmentTrade,now: Long): OperationResult {
        val old=active_trade(c.trade_id,c.expected_revision)
        val asset=dao.investment(old.investment_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val holding=R.replace_contribution(asset.holding_quantity_e8,
            quantity(Direction.valueOf(old.direction),old.quantity_e8),0)
        if(holding<0) throw DomainException(ErrorCode.INSUFFICIENT_HOLDING)
        if(dao.delete_trade(old.id,old.revision,now)!=1) throw DomainException(ErrorCode.STALE_RECORD)
        if(dao.update_holding(asset.id,holding,R.add(asset.revision,1),asset.revision,now)!=1)
            throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        sync_trade_cash(c.operation_id,old,asset,Direction.valueOf(old.direction),0,false,old.occurred_at_ms,now,"TRADE_DELETE")
        return OperationResult("INVESTMENT_TRADE",old.id)
    }
    private suspend fun edit_cash_entry(c: EditCashEntry,now: Long): OperationResult {
        val old=dao.cash_entry(c.entry_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if(old.is_deleted || old.revision!=c.expected_revision) throw DomainException(ErrorCode.STALE_RECORD)
        if(old.source_kind!="CASH_SET") throw DomainException(ErrorCode.SOURCE_RECORD)
        if(c.note.length>2000 || c.delta_minor==Long.MIN_VALUE) throw DomainException(ErrorCode.FORMAT)
        currency(old.currency_code); R.add(old.revision,1)
        if(dao.edit_entry(old.id,old.revision,c.delta_minor,c.occurred_at_ms,c.note.trim(),false,now)!=1)
            throw DomainException(ErrorCode.STALE_RECORD)
        fault(TransactionPoint.AFTER_BUSINESS)
        change_cash(c.operation_id,old.savings_account_id,old.currency_code,
            R.replace_contribution(0,old.delta_minor,c.delta_minor),"CASH_EDIT",now)
        return OperationResult("CASH_ENTRY",old.id)
    }
}

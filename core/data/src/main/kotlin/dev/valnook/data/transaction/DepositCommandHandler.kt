package dev.valnook.data.transaction

import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import java.time.Clock
import java.time.LocalDate

internal class DepositCommandHandler(private val db: ValnookDatabase, private val cash: CashWriter,
    private val clock: Clock, private val fault: (TransactionPoint) -> Unit) {
    private val dao = db.deposits()
    suspend fun open(c: OpenTermDeposit,now: Long): OperationResult {
        cash.requireAccount(c.account_id)
        val code=cash.currency(c.currency_code).code
        val interest=R.interest(c.principal_minor,c.annual_rate_percent_e8,c.start_epoch_day,c.end_epoch_day)
        val id=dao.insert_deposit(DepositEntity(savings_account_id=c.account_id,currency_code=code,
            principal_minor=c.principal_minor,annual_rate_percent_e8=c.annual_rate_percent_e8,
            start_epoch_day=c.start_epoch_day,end_epoch_day=c.end_epoch_day,expected_interest_minor=interest,
            open_cash_linked=c.cash_linked,open_operation_id=c.operation_id,created_at_ms=now,updated_at_ms=now))
        fault(TransactionPoint.AFTER_BUSINESS)
        if(c.cash_linked) {
            cash.change(c.operation_id,c.account_id,code,-c.principal_minor,"TERM_OPEN",now)
            cash.entry(c.operation_id,c.account_id,code,"TERM_OPEN",id,-c.principal_minor,now,now)
        }
        return OperationResult("TERM_DEPOSIT",id)
    }
    suspend fun close(c: CloseTermDeposit,now: Long): OperationResult {
        val value=dao.deposit(c.deposit_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if(value.status!="OPEN") throw DomainException(ErrorCode.ALREADY_CLOSED)
        R.add(value.revision,1)
        if(LocalDate.now(clock).toEpochDay()<value.end_epoch_day) throw DomainException(ErrorCode.NOT_MATURED)
        if(dao.close_deposit(value.id,c.cash_linked,c.operation_id,now)!=1) throw DomainException(ErrorCode.ALREADY_CLOSED)
        fault(TransactionPoint.AFTER_BUSINESS)
        if(c.cash_linked) {
            val returned=R.add(value.principal_minor,value.expected_interest_minor)
            cash.change(c.operation_id,value.savings_account_id,value.currency_code,returned,"TERM_CLOSE",now)
            cash.entry(c.operation_id,value.savings_account_id,value.currency_code,"TERM_CLOSE",value.id,returned,now,now)
        }
        return OperationResult("TERM_DEPOSIT",value.id)
    }
    suspend fun edit(c: EditTermDeposit,now: Long): OperationResult {
        val old=dao.deposit(c.deposit_id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if(old.revision!=c.expected_revision)throw DomainException(ErrorCode.STALE_RECORD)
        R.add(old.revision,1)
        cash.currency(old.currency_code)
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
            cash.change(c.operation_id,old.savings_account_id,old.currency_code,R.replace_contribution(0,old_effect,new_effect),"TERM_EDIT",now)
        sync_deposit_entry(old,"TERM_OPEN",old.open_operation_id,old.open_cash_linked,c.open_cash_linked,
            -old.principal_minor,-c.principal_minor,old.created_at_ms,now)
        if(closed)sync_deposit_entry(old,"TERM_CLOSE",requireNotNull(old.close_operation_id),old.close_cash_linked==true,
            c.close_cash_linked==true,old_return,new_return,requireNotNull(old.closed_at_ms),now)
        return OperationResult("TERM_DEPOSIT",old.id)
    }
    private suspend fun sync_deposit_entry(deposit: DepositEntity,kind: String,original_operation: String,
        old_linked: Boolean,new_linked: Boolean,old_delta: Long,new_delta: Long,occurred: Long,now: Long) {
        val entry=db.cash().source_entry(kind,deposit.id)
        if(old_linked)check(entry!=null&&!entry.is_deleted&&entry.delta_minor==old_delta){"Inconsistent deposit source cash entry"}
        if(!old_linked&&!new_linked)return
        if(entry==null)cash.entry(original_operation,deposit.savings_account_id,deposit.currency_code,kind,deposit.id,new_delta,occurred,now)
        else {
            R.add(entry.revision,1)
            if(db.cash().edit_entry(entry.id,entry.revision,if(new_linked)new_delta else entry.delta_minor,
                entry.occurred_at_ms,entry.note,!new_linked,now)!=1)throw DomainException(ErrorCode.STALE_RECORD)
        }
    }
}

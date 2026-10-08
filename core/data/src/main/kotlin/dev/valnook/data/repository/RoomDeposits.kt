package dev.valnook.data.repository

import dev.valnook.data.database.DepositEntity
import dev.valnook.data.database.DepositDao
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.TermDeposit
import dev.valnook.domain.repository.DepositRepository
import dev.valnook.domain.repository.PagedDepositRepository
import dev.valnook.domain.repository.LedgerCursor
import kotlinx.coroutines.flow.map

internal fun DepositEntity.toModel()=TermDeposit(id,savings_account_id,Currency.of(currency_code),principal_minor,
    annual_rate_percent_e8,start_epoch_day,end_epoch_day,expected_interest_minor,status=="CLOSED",
    open_cash_linked,close_cash_linked,revision,open_cash_account_id,close_cash_account_id,closed_at_ms)

class RoomDeposits(private val dao:DepositDao):DepositRepository, PagedDepositRepository {
    override suspend fun monthPage(accountId: Long, month: dev.valnook.domain.repository.LedgerMonth, cursor: LedgerCursor?, size: Int) =
        dao.closedMonthPage(accountId, month.startMs, month.endMs, cursor?.time, cursor?.id, size.coerceIn(1,100)).map { it.toModel() }

    override fun observeRevision(accountId:Long)=dao.depositRevision(accountId)
    override suspend fun page(accountId:Long,closed:Boolean,cursor:LedgerCursor?,size:Int)=
        dao.depositPage(accountId,if(closed)"CLOSED" else "OPEN",cursor?.time,cursor?.id,size.coerceIn(1,100)).map{it.toModel()}
    override suspend fun get_deposit(id:Long)=dao.deposit(id)?.toModel()
    override fun observe_deposit(account_id:Long,id:Long)=dao.observe_deposit(account_id,id).map{it?.toModel()}
    override fun observe_deposits(account_id:Long,limit:Int,closed:Boolean)=
        dao.deposits(account_id,limit.coerceIn(1,10000),if(closed)"CLOSED" else "OPEN").map{rows->rows.map{it.toModel()}}
}

package dev.valnook.data.repository

import dev.valnook.data.database.DepositEntity
import dev.valnook.data.database.LedgerDao
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.TermDeposit
import dev.valnook.domain.repository.DepositRepository
import kotlinx.coroutines.flow.map

private fun DepositEntity.to_model()=TermDeposit(id,savings_account_id,Currency.of(currency_code),principal_minor,
    annual_rate_percent_e8,start_epoch_day,end_epoch_day,expected_interest_minor,status=="CLOSED",
    open_cash_linked,close_cash_linked,revision)

class RoomDeposits(private val dao:LedgerDao):DepositRepository {
    override suspend fun get_deposit(id:Long)=dao.deposit(id)?.to_model()
    override fun observe_deposit(account_id:Long,id:Long)=dao.observe_deposit(account_id,id).map{it?.to_model()}
    override fun observe_deposits(account_id:Long,limit:Int,closed:Boolean)=
        dao.deposits(account_id,limit.coerceIn(1,10000),if(closed)"CLOSED" else "OPEN").map{rows->rows.map{it.to_model()}}
}

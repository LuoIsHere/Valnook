package dev.valnook.data.repository

import dev.valnook.data.database.CashEntryWithSource
import dev.valnook.data.database.CashDao
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.CashRepository
import dev.valnook.domain.repository.PagedCashRepository
import dev.valnook.domain.repository.LedgerCursor
import kotlinx.coroutines.flow.map

private fun CashEntryWithSource.to_model():CashEntry {
    val e=entry
    return CashEntry(e.id,e.savings_account_id,Currency.of(e.currency_code),e.delta_minor,e.occurred_at_ms,
        CashSource.valueOf(e.source_kind),e.source_id,investment_id,e.note,e.revision,e.cash_account_id,cash_account_name)
}

class RoomCash(private val dao:CashDao):CashRepository, PagedCashRepository {
    override fun observeRevision(accountId:Long,currencyCode:String)=dao.ledgerRevision(accountId,currencyCode)
    override suspend fun page(accountId:Long,currencyCode:String,cursor:LedgerCursor?,size:Int)=
        dao.ledgerPage(accountId,currencyCode,cursor?.time,cursor?.id,size.coerceIn(1,100)).map{it.to_model()}
    override fun observeCashAccountRevision(cashAccountId:Long)=dao.cashAccountRevision(cashAccountId)
    override suspend fun cashAccountPage(cashAccountId:Long,cursor:LedgerCursor?,size:Int)=
        dao.cashAccountLedgerPage(cashAccountId,cursor?.time,cursor?.id,size.coerceIn(1,100)).map{it.to_model()}
    override fun observe_cash(account_id:Long)=dao.cash(account_id).map { rows->rows.map { it.toModel() } }
    override fun observeCashAccount(cashAccountId:Long)=dao.observeCashAccount(cashAccountId).map { it?.toModel() }
    override fun observeCashEntries(cashAccountId:Long,limit:Int)=
        dao.cashAccountEntries(cashAccountId,limit.coerceIn(1,10000)).map { rows->rows.map{it.to_model()} }
    override fun observeCashEntry(cashAccountId:Long,entryId:Long)=
        dao.observeCashAccountEntry(cashAccountId,entryId).map{it?.to_model()}
    override fun observe_entries(account_id:Long,currency_code:String,limit:Int)=
        dao.cash_entries(account_id,Currency.of(currency_code).code,limit.coerceIn(1,10000))
            .map { rows->rows.map{it.to_model()} }
    // Details remain live even when the record moves beyond the list's current page.
    override fun observe_entry(account_id:Long,entry_id:Long)=
        dao.observe_cash_entry(account_id,entry_id).map{it?.to_model()}
}

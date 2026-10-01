package dev.valnook.data.repository

import dev.valnook.data.database.CashEntryWithSource
import dev.valnook.data.database.LedgerDao
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.CashRepository
import kotlinx.coroutines.flow.map

private fun CashEntryWithSource.to_model():CashEntry {
    val e=entry
    return CashEntry(e.id,e.savings_account_id,Currency.of(e.currency_code),e.delta_minor,e.occurred_at_ms,
        e.source_kind,e.source_id,investment_id,e.note,e.revision)
}

class RoomCash(private val dao:LedgerDao):CashRepository {
    override fun observe_cash(account_id:Long)=dao.cash(account_id).map { rows->rows.map {
        CashBalance(it.savings_account_id,Currency.of(it.currency_code),it.balance_minor,it.revision)
    } }
    override fun observe_entries(account_id:Long,currency_code:String,limit:Int)=
        dao.cash_entries(account_id,Currency.of(currency_code).code,limit.coerceIn(1,10000))
            .map { rows->rows.map{it.to_model()} }
    // Details remain live even when the record moves beyond the list's current page.
    override fun observe_entry(account_id:Long,entry_id:Long)=
        dao.observe_cash_entry(account_id,entry_id).map{it?.to_model()}
}

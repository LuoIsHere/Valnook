package dev.valnook.data.repository

import dev.valnook.data.database.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import dev.valnook.domain.money.DecimalRules
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import java.time.Clock
import java.text.Normalizer
import java.util.Locale

internal fun valid_name(name: String): String {
    val result = name.trim()
    if (result.isEmpty() || result.length > 200) throw DomainException(ErrorCode.NAME)
    return result
}
class RoomAccounts(private val db: ValnookDatabase, private val clock: Clock) : AccountRepository {
    private val dao = db.ledger()
    override fun observe_accounts() = dao.accounts().map { rows -> rows.map { SavingsAccount(it.id,it.name,it.note) } }
    override suspend fun save_account(id: Long?, name: String, note: String): Long = db.withTransaction {
        val label = valid_name(name)
        if (note.length > 2000) throw DomainException(ErrorCode.FORMAT)
        if (id == null) dao.insert_account(AccountEntity(name=label,note=note,created_at_ms=clock.millis(),updated_at_ms=clock.millis()))
        else { if(dao.edit_account(id,label,note,clock.millis()) != 1) throw DomainException(ErrorCode.NOT_FOUND); id }
    }
}
class RoomCash(private val dao: LedgerDao) : CashRepository {
    override fun observe_cash(account_id: Long) = dao.cash(account_id).map { rows -> rows.map {
        CashBalance(it.savings_account_id,Currency.of(it.currency_code),it.balance_minor,it.revision) } }
    override fun observe_entries(account_id: Long, currency_code: String, limit: Int) =
        dao.cash_entries(account_id,Currency.of(currency_code).code,limit.coerceIn(1,10000)).map { rows -> rows.map {
            val e=it.entry
            CashEntry(e.id,e.savings_account_id,Currency.of(e.currency_code),e.delta_minor,e.occurred_at_ms,
                e.source_kind,e.source_id,it.investment_id,e.note,e.revision) } }
}
class RoomDeposits(private val dao: LedgerDao) : DepositRepository {
    override suspend fun get_deposit(id: Long):TermDeposit? = dao.deposit(id)?.let {
        TermDeposit(it.id,it.savings_account_id,Currency.of(it.currency_code),it.principal_minor,it.annual_rate_percent_e8,
            it.start_epoch_day,it.end_epoch_day,it.expected_interest_minor,it.status=="CLOSED",it.open_cash_linked,it.close_cash_linked,it.revision)
    }
    override fun observe_deposits(account_id: Long, limit: Int, closed:Boolean) = dao.deposits(account_id,limit.coerceIn(1,10000),if(closed)"CLOSED" else "OPEN").map { rows -> rows.map {
        TermDeposit(it.id,it.savings_account_id,Currency.of(it.currency_code),it.principal_minor,it.annual_rate_percent_e8,
            it.start_epoch_day,it.end_epoch_day,it.expected_interest_minor,it.status=="CLOSED",it.open_cash_linked,it.close_cash_linked,it.revision) } }
}

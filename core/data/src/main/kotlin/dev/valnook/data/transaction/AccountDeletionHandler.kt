package dev.valnook.data.transaction

import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/** Called only inside the financial command transaction, including preview and validation. */
internal class AccountDeletionHandler(private val db: ValnookDatabase, private val clock: Clock,
    private val fault: (TransactionPoint) -> Unit) {
    private data class Pending(val preview: AccountDeletionPreview, val generation: Long, val expires: Long)
    private val pending = linkedMapOf<String, Pending>()
    private val random = SecureRandom()
    private val sql get() = db.openHelper.writableDatabase

    suspend fun preview(accountId: Long, cashId: Long?): AccountDeletionPreview {
        val parent = db.accounts().account(accountId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val cash = cashId?.let { db.cash().cashAccount(it)?.takeIf { it.savings_account_id == accountId }
            ?: throw DomainException(ErrorCode.NOT_FOUND) }
        val scope = if (cashId == null) "savings_account_id" else "cash_account_id"
        val target = cashId ?: accountId
        val transfers = if (cashId == null) emptyList() else db.credit().dependents(cashId).map {
            val child = requireNotNull(db.cash().cashAccount(it.account_id))
            CreditLimitTransfer(child.name, child.currency_code, requireNotNull(db.credit().profile(cashId)?.credit_limit_minor))
        }
        val value = AccountDeletionPreview(UUID.randomUUID().toString(),
            random.nextInt(1_000_000).toString().padStart(6, '0'), accountId, cashId,
            cash?.name ?: parent.name, cash?.revision ?: parent.revision, cash?.currency_code, cash?.balance_minor,
            if (cashId == null) count("SELECT COUNT(*) FROM cash_accounts WHERE savings_account_id=?", accountId) else 1,
            count("SELECT COUNT(*) FROM cash_entries WHERE $scope=? AND is_deleted=0", target),
            if (cashId == null) count("SELECT COUNT(*) FROM term_deposits WHERE savings_account_id=?", accountId)
            else count("SELECT COUNT(*) FROM term_deposits WHERE open_cash_account_id=? OR close_cash_account_id=?", cashId, cashId),
            if (cashId == null) count("SELECT COUNT(*) FROM investment_trades WHERE is_deleted=0 AND investment_id IN (SELECT id FROM investments WHERE savings_account_id=?)", accountId)
            else count("SELECT COUNT(*) FROM investment_trades WHERE cash_account_id=? AND is_deleted=0", cashId), transfers)
        pending.entries.removeAll { it.value.expires <= clock.millis() }
        while (pending.size >= 32) pending.remove(pending.keys.first())
        pending[value.ticket] = Pending(value, db.audit().generation(), clock.millis() + 300_000)
        return value
    }

    fun cancel(ticket: String) { pending.remove(ticket) }

    suspend fun validate(accountId: Long, cashId: Long?, revision: Long, confirmation: DeletionConfirmation?) {
        val c = confirmation ?: throw DomainException(ErrorCode.FORMAT)
        val p = pending[c.ticket] ?: throw DomainException(ErrorCode.STALE_RECORD)
        if (p.preview.code != c.code) throw DomainException(ErrorCode.FORMAT)
        if (p.preview.accountId != accountId || p.preview.balanceAccountId != cashId ||
            p.preview.revision != revision || p.expires <= clock.millis() || p.generation != db.audit().generation())
            throw DomainException(ErrorCode.STALE_RECORD)
    }

    suspend fun deleteCash(command: DeleteBalanceAccount, now: Long): OperationResult {
        val id = command.balanceAccountId
        val row = db.cash().cashAccount(id)?.takeIf { it.savings_account_id == command.accountId }
            ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (row.revision != command.expectedRevision) throw DomainException(ErrorCode.STALE_BALANCE)
        db.credit().dependents(id).forEach { dependent ->
            db.credit().upsert(dependent.copy(limit_source_account_id = null,
                credit_limit_minor = requireNotNull(db.credit().profile(id)?.credit_limit_minor)))
            exec("UPDATE cash_accounts SET revision=revision+1,updated_at_ms=? WHERE id=?", now, dependent.account_id)
        }
        exec("UPDATE investments SET revision=revision+1,updated_at_ms=? WHERE id IN (SELECT investment_id FROM investment_trades WHERE cash_account_id=?)", now, id)
        exec("UPDATE investment_trades SET cash_account_id=NULL,cash_linked=0,revision=revision+1,updated_at_ms=? WHERE cash_account_id=?", now, id)
        exec("UPDATE term_deposits SET open_cash_account_id=NULL,open_cash_linked=0,revision=revision+1,updated_at_ms=? WHERE open_cash_account_id=?", now, id)
        exec("UPDATE term_deposits SET close_cash_account_id=NULL,close_cash_linked=0,revision=revision+1,updated_at_ms=? WHERE close_cash_account_id=?", now, id)
        exec("DELETE FROM cash_entries WHERE cash_account_id=?", id)
        exec("DELETE FROM cash_movements WHERE cash_account_id=?", id)
        exec("DELETE FROM statistics_baseline_items WHERE item_kind='CASH' AND reference_id=?", id)
        db.credit().delete(id)
        exec("DELETE FROM cash_accounts WHERE id=?", id)
        exec("DELETE FROM demo_labels WHERE entity_kind='CASH' AND entity_id=?", id)
        exec("UPDATE savings_accounts SET revision=revision+1,updated_at_ms=? WHERE id=?", now, command.accountId)
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("BALANCE_ACCOUNT", id)
    }

    suspend fun deleteAccount(command: DeleteAccount): OperationResult {
        val id = command.accountId
        val parent = db.accounts().account(id) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (parent.revision != command.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        // Keep global instrument/type/price events and events shared with other accounts.
        exec("""DELETE FROM audit_events WHERE event_id IN
            (SELECT event_id FROM audit_event_accounts WHERE account_id=?)
            AND entity_kind IN ('ACCOUNT','BALANCE_ACCOUNT','CASH_ENTRY','TERM_DEPOSIT','INVESTMENT','INVESTMENT_TRADE')
            AND NOT EXISTS (SELECT 1 FROM audit_event_accounts a WHERE a.event_id=audit_events.event_id AND a.account_id!=?)""", id, id)
        exec("DELETE FROM audit_event_accounts WHERE account_id=?", id)
        exec("DELETE FROM statistics_baseline_items WHERE account_id=?", id)
        exec("DELETE FROM cash_entries WHERE savings_account_id=?", id)
        exec("DELETE FROM cash_movements WHERE savings_account_id=?", id)
        exec("DELETE FROM investment_trades WHERE investment_id IN (SELECT id FROM investments WHERE savings_account_id=?)", id)
        exec("DELETE FROM term_deposits WHERE savings_account_id=?", id)
        exec("DELETE FROM investments WHERE savings_account_id=?", id)
        exec("DELETE FROM credit_account_profiles WHERE account_id IN (SELECT id FROM cash_accounts WHERE savings_account_id=?)", id)
        exec("DELETE FROM demo_labels WHERE entity_kind='CASH' AND entity_id IN (SELECT id FROM cash_accounts WHERE savings_account_id=?)", id)
        exec("DELETE FROM cash_accounts WHERE savings_account_id=?", id)
        exec("DELETE FROM demo_labels WHERE entity_kind='ACCOUNT' AND entity_id=?", id)
        exec("DELETE FROM savings_accounts WHERE id=?", id)
        if (parent.icon_type == "IMAGE") exec("DELETE FROM account_icon_images WHERE id=? AND NOT EXISTS (SELECT 1 FROM savings_accounts WHERE icon_type='IMAGE' AND icon_value=?)", parent.icon_value, parent.icon_value)
        // Operation receipts contain only IDs/digests and must remain to prevent old requests replaying.
        fault(TransactionPoint.AFTER_BUSINESS)
        return OperationResult("ACCOUNT", id)
    }

    private fun exec(statement: String, vararg args: Any) = sql.execSQL(statement, args)
    private fun count(statement: String, vararg args: Any): Int = sql.query(statement, args).use {
        check(it.moveToFirst()); it.getInt(0)
    }
}

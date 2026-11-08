package dev.valnook.data.transaction

import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import java.math.BigDecimal
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/**
 * Deletes an instrument with its positions, trades and prices in every account. Cash is not given back: what the
 * trades moved stays in the ledger, no longer pointing at them. Called only inside the command transaction.
 */
internal class InstrumentDeletionHandler(private val db: ValnookDatabase, private val clock: Clock,
    private val fault: (TransactionPoint) -> Unit) {
    private data class Pending(val preview: InstrumentDeletionPreview, val generation: Long, val expires: Long)
    private val pending = linkedMapOf<String, Pending>()
    private val random = SecureRandom()
    private val sql get() = db.openHelper.writableDatabase

    private val positions = "SELECT id FROM investments WHERE instrument_id=?"
    private val trades = "SELECT t.id FROM investment_trades t JOIN investments p ON p.id=t.investment_id WHERE p.instrument_id=?"

    suspend fun preview(instrumentId: Long): InstrumentDeletionPreview {
        val instrument = db.instruments().instrument(instrumentId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        val tradeCount = activeTrades(instrumentId)
        val holding = long("SELECT COALESCE(SUM(holding_quantity_e8),0) FROM investments WHERE instrument_id=?", instrumentId)
        val value = InstrumentDeletionPreview(UUID.randomUUID().toString(),
            if (tradeCount > 0) random.nextInt(1_000_000).toString().padStart(6, '0') else "",
            instrumentId, instrument.name, instrument.revision, instrument.currency_code,
            count("SELECT COUNT(DISTINCT savings_account_id) FROM investments WHERE instrument_id=?", instrumentId),
            tradeCount,
            count("SELECT COUNT(*) FROM instrument_price_history WHERE instrument_id=? AND is_deleted=0", instrumentId),
            count("SELECT COUNT(*) FROM cash_entries WHERE source_kind IN ('TRADE','DIVIDEND') AND is_deleted=0 AND source_id IN ($trades)", instrumentId),
            holding, BigDecimal.valueOf(holding, 8).multiply(BigDecimal.valueOf(instrument.current_price_e5, 5)))
        pending.entries.removeAll { it.value.expires <= clock.millis() }
        while (pending.size >= 32) pending.remove(pending.keys.first())
        pending[value.ticket] = Pending(value, db.audit().generation(), clock.millis() + 300_000)
        return value
    }

    fun cancel(ticket: String) { pending.remove(ticket) }

    /** An instrument with trades needs the code its preview showed; one without may also go without a preview. */
    suspend fun validate(command: DeleteInstrument) {
        val instrument = db.instruments().instrument(command.instrumentId) ?: throw DomainException(ErrorCode.NOT_FOUND)
        if (instrument.revision != command.expectedRevision) throw DomainException(ErrorCode.STALE_RECORD)
        val tradeCount = activeTrades(command.instrumentId)
        val c = command.confirmation
        if (c == null) {
            if (tradeCount > 0) throw DomainException(ErrorCode.FORMAT)
            return
        }
        val p = pending[c.ticket] ?: throw DomainException(ErrorCode.STALE_RECORD)
        if (p.preview.code != c.code) throw DomainException(ErrorCode.FORMAT)
        if (p.preview.instrumentId != command.instrumentId || p.preview.revision != command.expectedRevision ||
            p.expires <= clock.millis() || p.generation != db.audit().generation() ||
            (tradeCount > 0 && p.preview.code.isEmpty())) throw DomainException(ErrorCode.STALE_RECORD)
    }

    fun delete(command: DeleteInstrument, now: Long): OperationResult {
        val id = command.instrumentId
        // Cash keeps what the trades moved, so balances stay; the entries just stop pointing at the trades.
        exec("""UPDATE cash_entries SET source_kind='${CashSource.TRADE_REMOVED.name}',source_id=NULL,revision=revision+1,
            updated_at_ms=? WHERE source_kind='${CashSource.TRADE.name}' AND source_id IN ($trades)""", now, id)
        exec("""UPDATE cash_entries SET source_kind='${CashSource.DIVIDEND_REMOVED.name}',source_id=NULL,revision=revision+1,
            updated_at_ms=? WHERE source_kind='${CashSource.DIVIDEND.name}' AND source_id IN ($trades)""", now, id)
        // The history of the instrument goes with it; the event recording this deletion keeps only IDs.
        val events = """(entity_kind='INSTRUMENT' AND entity_id=CAST(? AS TEXT))
            OR (entity_kind='INVESTMENT' AND entity_id IN (SELECT CAST(id AS TEXT) FROM investments WHERE instrument_id=?))
            OR (entity_kind='INVESTMENT_TRADE' AND entity_id IN (SELECT CAST(t.id AS TEXT) FROM investment_trades t
                JOIN investments p ON p.id=t.investment_id WHERE p.instrument_id=?))
            OR (entity_kind='INSTRUMENT_PRICE' AND entity_id IN (SELECT CAST(id AS TEXT) FROM instrument_price_history WHERE instrument_id=?))"""
        exec("DELETE FROM audit_event_accounts WHERE event_id IN (SELECT event_id FROM audit_events WHERE $events)", id, id, id, id)
        exec("DELETE FROM audit_events WHERE $events", id, id, id, id)
        exec("DELETE FROM statistics_baseline_items WHERE instrument_id=? OR (item_kind='POSITION' AND reference_id IN ($positions))", id, id)
        exec("DELETE FROM demo_labels WHERE entity_kind='INSTRUMENT' AND entity_id=?", id)
        exec("DELETE FROM instrument_price_history WHERE instrument_id=?", id)
        exec("DELETE FROM investment_trades WHERE investment_id IN ($positions)", id)
        exec("DELETE FROM investments WHERE instrument_id=?", id)
        exec("DELETE FROM instruments WHERE id=?", id)
        fault(TransactionPoint.AFTER_BUSINESS)
        command.confirmation?.let { pending.remove(it.ticket) }
        return OperationResult("INSTRUMENT", id)
    }

    private fun activeTrades(instrumentId: Long): Int =
        count("SELECT COUNT(*) FROM investment_trades WHERE is_deleted=0 AND investment_id IN ($positions)", instrumentId)
    private fun exec(statement: String, vararg args: Any) = sql.execSQL(statement, args)
    private fun count(statement: String, vararg args: Any): Int = sql.query(statement, args).use {
        check(it.moveToFirst()); it.getInt(0)
    }
    private fun long(statement: String, vararg args: Any): Long = sql.query(statement, args).use {
        check(it.moveToFirst()); it.getLong(0)
    }
}

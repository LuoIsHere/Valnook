package dev.valnook.data.audit

import android.database.Cursor
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.data.database.AuditEventAccountEntity
import dev.valnook.data.database.AuditEventEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.OperationResult
import dev.valnook.domain.repository.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** Writes one immutable event inside the caller's existing Room transaction. */
class AuditRecorder(private val db: ValnookDatabase, private val clock: Clock) {
    suspend fun captureBefore(command: FinancialCommand): String? {
        val target = targetFor(command) ?: return null
        return rowJson(target.first, target.second)?.toString()
    }

    suspend fun recordCommand(
        command: FinancialCommand,
        result: OperationResult,
        beforeJson: String?,
        recordedAtMs: Long,
        source: CommandSource = CommandSource.MOBILE
    ) {
        val after = rowJson(tableForResult(result.kind), result.id)
        val context = contextSnapshot(result)
        val eventId = UUID.randomUUID().toString()
        val afterJson = enrich(after ?: commandJson(command), context).toString()
        val auditedBeforeJson = beforeJson?.let { enrich(JSONObject(it), context).toString() }
        val accounts = linkedAccounts(command, result)
        val cashEffects = cashEffects(command.operation_id)
        db.audit().insertEvent(AuditEventEntity(
            event_id = eventId,
            event_schema_version = dev.valnook.data.database.AUDIT_PROTOCOL_VERSION,
            correlation_id = command.operation_id,
            operation_id = command.operation_id,
            action = actionFor(command),
            entity_kind = result.kind,
            entity_id = result.id.toString(),
            business_at_ms = businessTime(command),
            business_local_date = businessLocalDate(command),
            recorded_at_ms = recordedAtMs,
            before_json = auditedBeforeJson,
            after_json = afterJson,
            changed_fields_json = changedFields(auditedBeforeJson, afterJson),
            cash_effects_json = cashEffects,
            source = if (source == CommandSource.WEB_ADMIN) "WEB_ADMIN" else "USER"
        ))
        if (accounts.isNotEmpty()) {
            db.audit().insertAccounts(accounts.sorted().map { AuditEventAccountEntity(eventId, it) })
        }
        check(db.audit().advanceGeneration(recordedAtMs) == 1)
    }

    suspend fun recordSetting(
        change: SettingsChange,
        beforeJson: String,
        afterJson: String,
        recordedAtMs: Long
    ) {
        val eventId = UUID.randomUUID().toString()
        db.audit().insertEvent(AuditEventEntity(
            event_id = eventId,
            event_schema_version = dev.valnook.data.database.AUDIT_PROTOCOL_VERSION,
            correlation_id = eventId,
            operation_id = null,
            action = "UPDATE",
            entity_kind = when (change) {
                is SaveFinancialSettings -> "FINANCIAL_SETTINGS"
                is SaveLanguage -> "LANGUAGE"
                is SaveGainLossColors -> "GAIN_LOSS_COLORS"
                is SaveNavigationConfiguration -> "NAVIGATION"
            },
            entity_id = "1",
            business_at_ms = recordedAtMs,
            business_local_date = Instant.ofEpochMilli(recordedAtMs).atZone(clock.zone).toLocalDate().toString(),
            recorded_at_ms = recordedAtMs,
            before_json = beforeJson,
            after_json = afterJson,
            changed_fields_json = changedFields(beforeJson, afterJson),
            cash_effects_json = "[]",
            source = "USER"
        ))
        check(db.audit().advanceGeneration(recordedAtMs) == 1)
    }

    fun settingsJson(db: SupportSQLiteDatabase = this.db.openHelper.readableDatabase): String {
        val result = JSONObject()
        result.put("settings", queryObject(db, "SELECT * FROM app_settings WHERE id=1", emptyArray()))
        val rates = JSONArray()
        db.query("SELECT * FROM fx_rates ORDER BY source_currency,target_currency").use { cursor ->
            while (cursor.moveToNext()) rates.put(cursorObject(cursor))
        }
        result.put("rates", rates)
        return result.toString()
    }

    private fun targetFor(command: FinancialCommand): Pair<String, Long>? = when (command) {
        is SaveAssetType -> command.typeId?.let { "asset_types" to it }
        is SaveAccount -> command.accountId?.let { "savings_accounts" to it }
        is SaveInstrument -> command.instrumentId?.let { "instruments" to it }
        is EditInstrumentPrice -> "instrument_price_history" to command.priceRecordId
        is CreateInvestmentPosition -> null
        is SetCashBalance -> null
        is EditCashEntry -> "cash_entries" to command.entry_id
        is OpenTermDeposit -> null
        is CloseTermDeposit -> "term_deposits" to command.deposit_id
        is EditTermDeposit -> "term_deposits" to command.deposit_id
        is RecordInvestmentTrade -> null
        is EditInvestmentTrade -> "investment_trades" to command.trade_id
        is DeleteInvestmentTrade -> "investment_trades" to command.trade_id
    }

    private fun tableForResult(kind: String): String = when (kind) {
        "ASSET_TYPE" -> "asset_types"
        "ACCOUNT" -> "savings_accounts"
        "INSTRUMENT" -> "instruments"
        "INSTRUMENT_PRICE" -> "instrument_price_history"
        "INVESTMENT" -> "investments"
        "CASH_ENTRY" -> "cash_entries"
        "TERM_DEPOSIT" -> "term_deposits"
        "INVESTMENT_TRADE" -> "investment_trades"
        else -> throw IllegalStateException("Unknown audited result kind: $kind")
    }

    private fun actionFor(command: FinancialCommand): String = when (command) {
        is SaveAssetType -> if (command.typeId == null) "CREATE" else "UPDATE"
        is SaveAccount -> if (command.accountId == null) "CREATE" else "UPDATE"
        is SaveInstrument -> if (command.instrumentId == null) "CREATE" else "UPDATE"
        is CreateInvestmentPosition, is SetCashBalance, is OpenTermDeposit, is RecordInvestmentTrade -> "CREATE"
        is DeleteInvestmentTrade -> "DELETE"
        else -> "UPDATE"
    }

    private fun businessTime(command: FinancialCommand): Long? = when (command) {
        is RecordInvestmentTrade -> command.occurred_at_ms
        is EditInvestmentTrade -> command.occurred_at_ms
        is EditCashEntry -> command.occurred_at_ms
        else -> null
    }

    private fun businessLocalDate(command: FinancialCommand): String? = when (command) {
        is OpenTermDeposit -> LocalDate.ofEpochDay(command.start_epoch_day).toString()
        is EditTermDeposit -> LocalDate.ofEpochDay(command.start_epoch_day).toString()
        else -> businessTime(command)?.let {
            Instant.ofEpochMilli(it).atZone(clock.zone).toLocalDate().toString()
        }
    }

    private suspend fun linkedAccounts(command: FinancialCommand, result: OperationResult): Set<Long> {
        val accounts = linkedSetOf<Long>()
        when (command) {
            is SaveAccount -> command.accountId?.let(accounts::add)
            is SetCashBalance -> accounts.add(command.account_id)
            is OpenTermDeposit -> accounts.add(command.account_id)
            is SaveInstrument -> accountsForInstrument(result.id).forEach(accounts::add)
            is EditInstrumentPrice -> accountsForPrice(result.id).forEach(accounts::add)
            is SaveAssetType -> accountsForType(result.id).forEach(accounts::add)
            is CreateInvestmentPosition -> accounts.add(command.accountId)
            is RecordInvestmentTrade -> accountForPosition(command.investment_id)?.let(accounts::add)
            is EditInvestmentTrade -> accountForTrade(command.trade_id)?.let(accounts::add)
            is DeleteInvestmentTrade -> accountForTrade(command.trade_id)?.let(accounts::add)
            is EditCashEntry -> accountForCashEntry(command.entry_id)?.let(accounts::add)
            is CloseTermDeposit -> accountForDeposit(command.deposit_id)?.let(accounts::add)
            is EditTermDeposit -> accountForDeposit(command.deposit_id)?.let(accounts::add)
        }
        db.openHelper.readableDatabase.query(
            "SELECT DISTINCT savings_account_id FROM cash_movements WHERE operation_id=?",
            arrayOf(command.operation_id)
        ).use { cursor -> while (cursor.moveToNext()) accounts.add(cursor.getLong(0)) }
        if (accounts.isEmpty()) accountForResult(result)?.let(accounts::add)
        return accounts
    }

    private fun accountForPosition(id: Long): Long? = scalarLong(
        "SELECT savings_account_id FROM investments WHERE id=?", id)

    private fun accountForTrade(id: Long): Long? = scalarLong(
        "SELECT p.savings_account_id FROM investment_trades t JOIN investments p ON p.id=t.investment_id WHERE t.id=?", id)

    private fun accountForCashEntry(id: Long): Long? = scalarLong(
        "SELECT savings_account_id FROM cash_entries WHERE id=?", id)

    private fun accountForDeposit(id: Long): Long? = scalarLong(
        "SELECT savings_account_id FROM term_deposits WHERE id=?", id)

    private fun accountsForInstrument(id: Long): Set<Long> = scalarLongSet(
        "SELECT DISTINCT savings_account_id FROM investments WHERE instrument_id=?", id)

    private fun accountsForPrice(id: Long): Set<Long> = scalarLongSet(
        """SELECT DISTINCT p.savings_account_id FROM instrument_price_history h
            JOIN investments p ON p.instrument_id=h.instrument_id WHERE h.id=?""", id)

    private fun accountsForType(id: Long): Set<Long> = scalarLongSet(
        """SELECT DISTINCT p.savings_account_id FROM investments p
            JOIN instruments i ON i.id=p.instrument_id WHERE i.asset_type_id=?""", id)

    private fun accountForResult(result: OperationResult): Long? = when (result.kind) {
        "ACCOUNT" -> result.id
        "INVESTMENT" -> accountForPosition(result.id)
        "INVESTMENT_TRADE" -> accountForTrade(result.id)
        "CASH_ENTRY" -> accountForCashEntry(result.id)
        "TERM_DEPOSIT" -> accountForDeposit(result.id)
        else -> null
    }

    private fun scalarLong(sql: String, id: Long): Long? =
        db.openHelper.readableDatabase.query(sql, arrayOf(id.toString())).use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }

    private fun scalarLongSet(sql: String, id: Long): Set<Long> = linkedSetOf<Long>().also { result ->
        db.openHelper.readableDatabase.query(sql, arrayOf(id.toString())).use { cursor ->
            while (cursor.moveToNext()) result += cursor.getLong(0)
        }
    }

    private fun contextSnapshot(result: OperationResult): JSONObject? {
        val query = when (result.kind) {
            "INVESTMENT" -> """SELECT i.name instrument_name,i.symbol instrument_symbol,
                i.currency_code instrument_currency,p.savings_account_id account_id
                FROM investments p JOIN instruments i ON i.id=p.instrument_id WHERE p.id=?"""
            "INVESTMENT_TRADE" -> """SELECT i.name instrument_name,i.symbol instrument_symbol,
                i.currency_code instrument_currency,p.savings_account_id account_id
                FROM investment_trades t JOIN investments p ON p.id=t.investment_id
                JOIN instruments i ON i.id=p.instrument_id WHERE t.id=?"""
            "INSTRUMENT_PRICE" -> """SELECT i.name instrument_name,i.symbol instrument_symbol,
                i.currency_code instrument_currency FROM instrument_price_history h
                JOIN instruments i ON i.id=h.instrument_id WHERE h.id=?"""
            "CASH_ENTRY" -> """SELECT c.name cash_account_name,c.currency_code cash_currency,
                c.savings_account_id account_id FROM cash_entries e
                JOIN cash_accounts c ON c.id=e.cash_account_id WHERE e.id=?"""
            "TERM_DEPOSIT" -> """SELECT currency_code deposit_currency,
                savings_account_id account_id FROM term_deposits WHERE id=?"""
            else -> return null
        }
        return queryObject(db.openHelper.readableDatabase, query, arrayOf(result.id.toString()))
    }

    private fun enrich(value: JSONObject, context: JSONObject?): JSONObject = value.apply {
        context?.keys()?.forEachRemaining { key -> put("audit_$key", context.opt(key)) }
    }

    private fun cashEffects(operationId: String): String {
        val array = JSONArray()
        db.openHelper.readableDatabase.query(
            """SELECT id,cash_account_id,savings_account_id,currency_code,reason,delta_minor,
                balance_before_minor,balance_after_minor,created_at_ms FROM cash_movements
                WHERE operation_id=? ORDER BY id""", arrayOf(operationId)
        ).use { cursor -> while (cursor.moveToNext()) array.put(cursorObject(cursor)) }
        return array.toString()
    }

    private fun rowJson(table: String, id: Long): JSONObject? = queryObject(
        db.openHelper.readableDatabase, "SELECT * FROM $table WHERE id=?", arrayOf(id.toString()))

    private fun queryObject(database: SupportSQLiteDatabase, sql: String, args: Array<out Any?>): JSONObject? =
        database.query(sql, args).use { cursor -> if (cursor.moveToFirst()) cursorObject(cursor) else null }

    private fun commandJson(command: FinancialCommand): JSONObject = JSONObject().apply {
        put("command", command::class.simpleName ?: "FinancialCommand")
        put("operationId", command.operation_id)
        put("fingerprintSource", command.toString())
    }

    private fun changedFields(beforeJson: String?, afterJson: String?): String {
        if (beforeJson == null || afterJson == null) return "[]"
        return runCatching {
            val before = JSONObject(beforeJson)
            val after = JSONObject(afterJson)
            val keys = linkedSetOf<String>()
            before.keys().forEachRemaining(keys::add)
            after.keys().forEachRemaining(keys::add)
            JSONArray(keys.filter { before.opt(it)?.toString() != after.opt(it)?.toString() }.sorted()).toString()
        }.getOrDefault("[]")
    }

    companion object {
        fun cursorObject(cursor: Cursor): JSONObject = JSONObject().apply {
            cursor.columnNames.forEachIndexed { index, name ->
                put(name, when (cursor.getType(index)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index).toString()
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getString(index)
                    Cursor.FIELD_TYPE_BLOB -> throw IllegalStateException("Blob is not portable")
                    else -> cursor.getString(index)
                })
            }
        }
    }
}

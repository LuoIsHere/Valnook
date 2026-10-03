package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.Currency
import dev.valnook.domain.money.DecimalRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

private const val MIGRATION_7_8_COST_ALGORITHM_VERSION = 4
private const val MIGRATION_7_8_STATISTICS_RULE_VERSION = 2

/** Converts legacy opening balances to ordinary, cash-unlinked purchases and adopts business dates. */
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.query("""SELECT p.id,p.opening_quantity_e8,p.opening_cost_price_e8,p.opening_at_ms,
            p.created_at_ms,p.updated_at_ms,s.currency_code
            FROM investments p JOIN instruments s ON s.id=p.instrument_id
            WHERE p.opening_quantity_e8>0 ORDER BY p.id""").use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val quantityIndex = cursor.getColumnIndexOrThrow("opening_quantity_e8")
            val priceIndex = cursor.getColumnIndexOrThrow("opening_cost_price_e8")
            val occurredIndex = cursor.getColumnIndexOrThrow("opening_at_ms")
            val createdIndex = cursor.getColumnIndexOrThrow("created_at_ms")
            val updatedIndex = cursor.getColumnIndexOrThrow("updated_at_ms")
            val currencyIndex = cursor.getColumnIndexOrThrow("currency_code")
            while (cursor.moveToNext()) {
                val positionId = cursor.getLong(idIndex)
                val quantity = cursor.getLong(quantityIndex)
                val price = if (cursor.isNull(priceIndex)) 0L else cursor.getLong(priceIndex)
                val occurred = cursor.getLong(occurredIndex)
                val created = cursor.getLong(createdIndex)
                val updated = cursor.getLong(updatedIndex)
                val currencyCode = cursor.getString(currencyIndex)
                val amount = DecimalRules.amount(quantity, price, Currency.of(currencyCode))
                val operationId = "migration-7-8-opening-$positionId"
                db.execSQL("""INSERT OR IGNORE INTO operations(operation_id,kind,request_fingerprint,
                    result_kind,result_id,created_at_ms) VALUES (?,'BUY','migration-7-8-opening',NULL,NULL,?)""",
                    arrayOf<Any?>(operationId, created))
                db.execSQL("""INSERT INTO investment_trades(investment_id,operation_id,direction,
                    quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,cash_account_id,
                    occurred_at_ms,created_at_ms,revision,is_deleted,updated_at_ms,fee_minor)
                    VALUES (?,?,'BUY',?,?,?,?,0,NULL,?,?,1,0,?,0)""",
                    arrayOf<Any?>(positionId, operationId, quantity, price, amount, currencyCode,
                        occurred, created, updated))
                db.query("SELECT last_insert_rowid()").use { result ->
                    check(result.moveToFirst())
                    db.execSQL("UPDATE operations SET result_kind='INVESTMENT_TRADE',result_id=? WHERE operation_id=?",
                        arrayOf<Any?>(result.getLong(0), operationId))
                }
            }
        }

        db.execSQL("""UPDATE investments SET opening_quantity_e8=0,opening_cost_price_e8=NULL,
            opening_at_ms=0,algorithm_version=$MIGRATION_7_8_COST_ALGORITHM_VERSION""")

        val zone = ZoneId.systemDefault()
        db.query("SELECT id,start_epoch_day FROM term_deposits").use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow("id")
            val startIndex = cursor.getColumnIndexOrThrow("start_epoch_day")
            while (cursor.moveToNext()) {
                val occurred = LocalDate.ofEpochDay(cursor.getLong(startIndex)).atStartOfDay(zone)
                    .toInstant().toEpochMilli()
                db.execSQL("""UPDATE cash_entries SET occurred_at_ms=?
                    WHERE source_kind='TERM_OPEN' AND source_id=? AND is_deleted=0""",
                    arrayOf(occurred, cursor.getLong(idIndex)))
            }
        }

        db.execSQL("DELETE FROM statistics_cache")
        db.query("SELECT baseline_at_ms FROM statistics_state WHERE id=1").use { cursor ->
            if (cursor.moveToFirst()) {
                val baselineDay = Instant.ofEpochMilli(cursor.getLong(0)).atZone(zone).toLocalDate().toEpochDay()
                db.execSQL("""UPDATE statistics_state SET source_revision=source_revision+1,
                    rule_version=$MIGRATION_7_8_STATISTICS_RULE_VERSION,
                    earliest_invalidated_epoch_day=? WHERE id=1""", arrayOf(baselineDay))
            }
        }
    }
}

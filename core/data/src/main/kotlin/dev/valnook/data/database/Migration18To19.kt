package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import java.math.BigDecimal
import java.math.RoundingMode

private const val MIGRATION_18_19_COST_RULE_VERSION = 5
private const val MIGRATION_18_19_ALLOCATION_SCALE = 32

/**
 * Frozen cost rule 5: purchases are booked at the unrounded quantity x price plus fee, so the average
 * cost no longer drifts from the execution price when the cash amount is rounded to the currency.
 * Sales still realize the settled cash amount. Holdings, states and trades are left untouched.
 */
val MIGRATION_18_19 = object : Migration(18, 19) {
    override fun migrate(db: SupportSQLiteDatabase) {
        val results = mutableListOf<Array<Any?>>()
        db.query("""SELECT p.id,p.holding_quantity_e8,c.fraction_digits,t.id,t.direction,t.quantity_e8,
            t.execution_price_e8,t.amount_minor,t.fee_minor
            FROM investments p JOIN instruments i ON i.id=p.instrument_id
            JOIN currencies c ON c.code=i.currency_code
            LEFT JOIN investment_trades t ON t.investment_id=p.id AND t.is_deleted=0
            ORDER BY p.id,t.occurred_at_ms,t.id""").use { cursor ->
            var positionId = Long.MIN_VALUE
            var holding = 0L
            var fractionDigits = 0
            var quantity = 0L
            var remaining = BigDecimal.ZERO
            var realized = BigDecimal.ZERO
            var valid = true
            fun finish() {
                if (positionId == Long.MIN_VALUE) return
                if (quantity != holding) valid = false
                results += arrayOf(remaining.stripTrailingZeros().toPlainString(),
                    realized.stripTrailingZeros().toPlainString(), if (valid) 1 else 0, positionId)
            }
            while (cursor.moveToNext()) {
                val current = cursor.getLong(0)
                if (current != positionId) {
                    finish()
                    positionId = current
                    holding = cursor.getLong(1)
                    fractionDigits = cursor.getInt(2)
                    quantity = 0L
                    remaining = BigDecimal.ZERO
                    realized = BigDecimal.ZERO
                    valid = true
                }
                if (cursor.isNull(3) || !valid) continue
                val tradeQuantity = cursor.getLong(5)
                val fee = BigDecimal.valueOf(cursor.getLong(8), fractionDigits)
                if (cursor.getString(4) == "BUY") {
                    quantity = Math.addExact(quantity, tradeQuantity)
                    remaining = remaining.add(BigDecimal.valueOf(tradeQuantity, 8)
                        .multiply(BigDecimal.valueOf(cursor.getLong(6), 8))).add(fee)
                } else {
                    if (tradeQuantity > quantity || quantity <= 0) {
                        valid = false
                        continue
                    }
                    val allocated = if (tradeQuantity == quantity) remaining else remaining
                        .multiply(BigDecimal.valueOf(tradeQuantity))
                        .divide(BigDecimal.valueOf(quantity), MIGRATION_18_19_ALLOCATION_SCALE, RoundingMode.HALF_UP)
                    realized = realized.add(BigDecimal.valueOf(cursor.getLong(7), fractionDigits))
                        .subtract(fee).subtract(allocated)
                    quantity -= tradeQuantity
                    remaining = if (quantity == 0L) BigDecimal.ZERO else remaining.subtract(allocated)
                }
            }
            finish()
        }
        results.forEach { values ->
            db.execSQL("""UPDATE investments SET remaining_cost=?,realized_profit=?,chronology_valid=?,
                algorithm_version=$MIGRATION_18_19_COST_RULE_VERSION WHERE id=?""", values)
        }
    }
}

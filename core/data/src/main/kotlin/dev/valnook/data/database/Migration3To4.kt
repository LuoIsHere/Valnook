package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.calculation.InvestmentProfitCalculator
import dev.valnook.domain.model.*
import java.math.BigDecimal
import java.math.RoundingMode

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE savings_accounts ADD COLUMN revision INTEGER NOT NULL DEFAULT 1")
        db.execSQL("""CREATE TABLE instruments (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            asset_type_id INTEGER NOT NULL,name TEXT NOT NULL,symbol TEXT NOT NULL,currency_code TEXT NOT NULL,
            current_price_e5 INTEGER NOT NULL,currency_locked INTEGER NOT NULL,revision INTEGER NOT NULL,
            price_updated_at_ms INTEGER NOT NULL,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,
            FOREIGN KEY(asset_type_id) REFERENCES asset_types(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(currency_code) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX index_instruments_asset_type_id ON instruments(asset_type_id)")
        db.execSQL("CREATE INDEX index_instruments_currency_code ON instruments(currency_code)")
        db.execSQL("CREATE INDEX index_instruments_name_id ON instruments(name,id)")
        db.execSQL("""INSERT INTO instruments SELECT id,asset_type_id,name,symbol,currency_code,
            current_price_e8/1000 + CASE WHEN current_price_e8%1000>=500 THEN 1 ELSE 0 END,
            CASE WHEN opening_quantity_e8>0 OR EXISTS(SELECT 1 FROM investment_trades WHERE investment_id=investments.id)
                THEN 1 ELSE 0 END,1,price_updated_at_ms,created_at_ms,updated_at_ms FROM investments""")
        db.execSQL("""CREATE TABLE investments_new (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            savings_account_id INTEGER NOT NULL,instrument_id INTEGER NOT NULL,
            opening_quantity_e8 INTEGER NOT NULL,holding_quantity_e8 INTEGER NOT NULL,revision INTEGER NOT NULL,
            created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,opening_cost_price_e8 INTEGER,
            opening_at_ms INTEGER NOT NULL,remaining_cost TEXT,realized_profit TEXT,chronology_valid INTEGER NOT NULL,
            algorithm_version INTEGER NOT NULL,position_state TEXT NOT NULL DEFAULT 'PENDING',
            last_activity_at_ms INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(savings_account_id) REFERENCES savings_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        // Legacy account-specific instruments stay distinct; code/name are never relationship keys.
        db.query("SELECT * FROM investments ORDER BY id").use { cursor ->
            fun number(name: String): Long = cursor.getLong(cursor.getColumnIndexOrThrow(name))
            fun text(name: String): String = cursor.getString(cursor.getColumnIndexOrThrow(name))
            while (cursor.moveToNext()) {
                val id = number("id")
                val currency = Currency.of(text("currency_code"))
                val trades = mutableListOf<Trade>()
                db.query("SELECT * FROM investment_trades WHERE investment_id=? AND is_deleted=0 ORDER BY occurred_at_ms,id",
                    arrayOf<Any>(id)).use { history ->
                    while (history.moveToNext()) {
                        fun n(name: String) = history.getLong(history.getColumnIndexOrThrow(name))
                        trades.add(Trade(n("id"), id, Direction.valueOf(history.getString(history.getColumnIndexOrThrow("direction"))),
                            n("quantity_e8"), n("execution_price_e8"), n("amount_minor"), currency,
                            n("cash_linked") != 0L, n("occurred_at_ms"), n("revision")))
                    }
                }
                val openingIndex = cursor.getColumnIndexOrThrow("opening_cost_price_e8")
                val openingPrice = if (cursor.isNull(openingIndex)) null else cursor.getLong(openingIndex)
                val openingAt = minOf(number("created_at_ms"), trades.minOfOrNull { it.occurred_at_ms } ?: Long.MAX_VALUE)
                val asset = Investment(id, number("savings_account_id"), number("asset_type_id"), "", text("name"),
                    text("symbol"), currency, number("opening_quantity_e8"), number("holding_quantity_e8"),
                    number("current_price_e8"), number("price_updated_at_ms"), openingPrice,
                    number("revision"), number("last_activity_at_ms"), id, openingAt)
                val result = InvestmentProfitCalculator.calculate(asset, trades)
                db.execSQL("""INSERT INTO investments_new(id,savings_account_id,instrument_id,
                    opening_quantity_e8,holding_quantity_e8,revision,created_at_ms,updated_at_ms,
                    opening_cost_price_e8,opening_at_ms,remaining_cost,realized_profit,chronology_valid,
                    algorithm_version,position_state,last_activity_at_ms)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""", arrayOf<Any?>(
                    id, asset.account_id, id, asset.opening_quantity_e8, asset.holding_quantity_e8, asset.revision,
                    number("created_at_ms"), number("updated_at_ms"), openingPrice, openingAt,
                    result.remainingCost?.stripTrailingZeros()?.toPlainString(),
                    result.realized?.stripTrailingZeros()?.toPlainString(), if (result.chronology_valid) 1 else 0,
                    InvestmentProfitCalculator.ALGORITHM_VERSION, text("position_state"), number("last_activity_at_ms")))
            }
        }
        db.execSQL("CREATE TEMP TABLE migration_trades AS SELECT * FROM investment_trades")
        db.execSQL("DROP TABLE investment_trades")
        db.execSQL("DROP TABLE investments")
        db.execSQL("ALTER TABLE investments_new RENAME TO investments")
        db.execSQL("""CREATE TABLE investment_trades (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            investment_id INTEGER NOT NULL,operation_id TEXT NOT NULL,direction TEXT NOT NULL,
            quantity_e8 INTEGER NOT NULL,execution_price_e8 INTEGER NOT NULL,amount_minor INTEGER NOT NULL,
            currency_code TEXT NOT NULL,cash_linked INTEGER NOT NULL,occurred_at_ms INTEGER NOT NULL,
            created_at_ms INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,is_deleted INTEGER NOT NULL DEFAULT 0,
            updated_at_ms INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(investment_id) REFERENCES investments(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(operation_id) REFERENCES operations(operation_id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(currency_code) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("INSERT INTO investment_trades SELECT * FROM migration_trades")
        db.execSQL("DROP TABLE migration_trades")
        db.execSQL("CREATE UNIQUE INDEX index_investments_savings_account_id_instrument_id ON investments(savings_account_id,instrument_id)")
        db.execSQL("CREATE INDEX index_investments_instrument_id_savings_account_id ON investments(instrument_id,savings_account_id)")
        db.execSQL("CREATE INDEX index_investments_savings_account_id_position_state_last_activity_at_ms_id ON investments(savings_account_id,position_state,last_activity_at_ms DESC,id DESC)")
        db.execSQL("CREATE INDEX index_investment_trades_investment_id_is_deleted_occurred_at_ms_id ON investment_trades(investment_id,is_deleted,occurred_at_ms DESC,id DESC)")
        db.execSQL("CREATE UNIQUE INDEX index_investment_trades_operation_id ON investment_trades(operation_id)")
        db.execSQL("CREATE INDEX index_investment_trades_currency_code ON investment_trades(currency_code)")
        db.execSQL("DROP INDEX index_cash_movements_operation_id")
        db.execSQL("CREATE UNIQUE INDEX index_cash_movements_operation_id_currency_code ON cash_movements(operation_id,currency_code)")
        db.execSQL("DROP INDEX index_cash_entries_original_operation_id")
        db.execSQL("CREATE UNIQUE INDEX index_cash_entries_original_operation_id_currency_code ON cash_entries(original_operation_id,currency_code)")
        db.execSQL("""CREATE TABLE app_settings (id INTEGER NOT NULL PRIMARY KEY,base_currency TEXT,revision INTEGER NOT NULL,
            FOREIGN KEY(base_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX index_app_settings_base_currency ON app_settings(base_currency)")
        db.execSQL("""CREATE TABLE fx_rates (source_currency TEXT NOT NULL,target_currency TEXT NOT NULL,rate TEXT NOT NULL,
            updated_at_ms INTEGER NOT NULL,PRIMARY KEY(source_currency,target_currency),
            FOREIGN KEY(source_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(target_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX index_fx_rates_target_currency ON fx_rates(target_currency)")
    }
}

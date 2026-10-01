package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * One-time v0.0.3 development reset. Schema 4 identifies cash by account + currency and cannot
 * preserve a stable target when two cash accounts use the same currency. The product prompt
 * explicitly authorizes this bounded 4 -> 5 reset; no generic destructive fallback is installed.
 */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        listOf(
            "cash_entries", "cash_movements", "investment_trades", "term_deposits", "investments",
            "instruments", "asset_types", "cash_balances", "fx_rates", "app_settings", "operations",
            "savings_accounts"
        ).forEach { db.execSQL("DROP TABLE IF EXISTS $it") }

        db.execSQL("""CREATE TABLE IF NOT EXISTS savings_accounts (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,note TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS operations (
            operation_id TEXT NOT NULL PRIMARY KEY,kind TEXT NOT NULL,request_fingerprint TEXT NOT NULL,
            result_kind TEXT,result_id INTEGER,created_at_ms INTEGER NOT NULL)""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS cash_accounts (
            savings_account_id INTEGER NOT NULL,currency_code TEXT NOT NULL,balance_minor INTEGER NOT NULL,
            revision INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,note TEXT NOT NULL,currency_locked INTEGER NOT NULL,created_at_ms INTEGER NOT NULL,
            FOREIGN KEY(savings_account_id) REFERENCES savings_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(currency_code) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_accounts_savings_account_id_currency_code_id ON cash_accounts(savings_account_id,currency_code,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_accounts_currency_code ON cash_accounts(currency_code)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS asset_types (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,name TEXT NOT NULL,normalized_name TEXT NOT NULL,
            created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_asset_types_normalized_name ON asset_types(normalized_name)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS instruments (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,asset_type_id INTEGER NOT NULL,name TEXT NOT NULL,
            symbol TEXT NOT NULL,currency_code TEXT NOT NULL,current_price_e5 INTEGER NOT NULL,
            currency_locked INTEGER NOT NULL,revision INTEGER NOT NULL,symbol_locked INTEGER NOT NULL,
            price_updated_at_ms INTEGER NOT NULL,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,
            FOREIGN KEY(asset_type_id) REFERENCES asset_types(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(currency_code) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_instruments_asset_type_id ON instruments(asset_type_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_instruments_currency_code ON instruments(currency_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_instruments_name_id ON instruments(name,id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS investments (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,savings_account_id INTEGER NOT NULL,instrument_id INTEGER NOT NULL,
            opening_quantity_e8 INTEGER NOT NULL,holding_quantity_e8 INTEGER NOT NULL,revision INTEGER NOT NULL,
            created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,opening_cost_price_e8 INTEGER,
            opening_at_ms INTEGER NOT NULL,remaining_cost TEXT,realized_profit TEXT,chronology_valid INTEGER NOT NULL,
            algorithm_version INTEGER NOT NULL,position_state TEXT NOT NULL DEFAULT 'PENDING',last_activity_at_ms INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(savings_account_id) REFERENCES savings_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(instrument_id) REFERENCES instruments(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_investments_savings_account_id_instrument_id ON investments(savings_account_id,instrument_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_investments_instrument_id_savings_account_id ON investments(instrument_id,savings_account_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_investments_savings_account_id_position_state_last_activity_at_ms_id ON investments(savings_account_id,position_state,last_activity_at_ms DESC,id DESC)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS term_deposits (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,savings_account_id INTEGER NOT NULL,currency_code TEXT NOT NULL,
            principal_minor INTEGER NOT NULL,annual_rate_percent_e8 INTEGER NOT NULL,start_epoch_day INTEGER NOT NULL,
            end_epoch_day INTEGER NOT NULL,interest_rule TEXT NOT NULL,calculation_version INTEGER NOT NULL,
            rounding_mode TEXT NOT NULL,expected_interest_minor INTEGER NOT NULL,status TEXT NOT NULL,
            open_cash_linked INTEGER NOT NULL,close_cash_linked INTEGER,open_cash_account_id INTEGER,
            close_cash_account_id INTEGER,open_operation_id TEXT NOT NULL,close_operation_id TEXT,closed_at_ms INTEGER,
            created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,
            FOREIGN KEY(savings_account_id) REFERENCES savings_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(currency_code) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(open_operation_id) REFERENCES operations(operation_id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(close_operation_id) REFERENCES operations(operation_id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(open_cash_account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(close_cash_account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_term_deposits_savings_account_id_status_end_epoch_day_id ON term_deposits(savings_account_id,status,end_epoch_day,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_term_deposits_savings_account_id_status_start_epoch_day_id ON term_deposits(savings_account_id,status,start_epoch_day DESC,id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_term_deposits_currency_code ON term_deposits(currency_code)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_term_deposits_open_operation_id ON term_deposits(open_operation_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_term_deposits_close_operation_id ON term_deposits(close_operation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_term_deposits_open_cash_account_id ON term_deposits(open_cash_account_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_term_deposits_close_cash_account_id ON term_deposits(close_cash_account_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS investment_trades (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,investment_id INTEGER NOT NULL,operation_id TEXT NOT NULL,
            direction TEXT NOT NULL,quantity_e8 INTEGER NOT NULL,execution_price_e8 INTEGER NOT NULL,
            amount_minor INTEGER NOT NULL,currency_code TEXT NOT NULL,cash_linked INTEGER NOT NULL,cash_account_id INTEGER,
            occurred_at_ms INTEGER NOT NULL,created_at_ms INTEGER NOT NULL,revision INTEGER NOT NULL DEFAULT 1,
            is_deleted INTEGER NOT NULL DEFAULT 0,updated_at_ms INTEGER NOT NULL DEFAULT 0,
            FOREIGN KEY(investment_id) REFERENCES investments(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(operation_id) REFERENCES operations(operation_id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(currency_code) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(cash_account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_investment_trades_investment_id_is_deleted_occurred_at_ms_id ON investment_trades(investment_id,is_deleted,occurred_at_ms DESC,id DESC)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_investment_trades_operation_id ON investment_trades(operation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_investment_trades_currency_code ON investment_trades(currency_code)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_investment_trades_cash_account_id ON investment_trades(cash_account_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS cash_entries (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,original_operation_id TEXT NOT NULL,savings_account_id INTEGER NOT NULL,
            currency_code TEXT NOT NULL,cash_account_id INTEGER NOT NULL,source_kind TEXT NOT NULL,source_id INTEGER,
            delta_minor INTEGER NOT NULL,occurred_at_ms INTEGER NOT NULL,note TEXT NOT NULL,revision INTEGER NOT NULL,
            is_deleted INTEGER NOT NULL,created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,
            FOREIGN KEY(cash_account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(original_operation_id) REFERENCES operations(operation_id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cash_entries_original_operation_id_cash_account_id ON cash_entries(original_operation_id,cash_account_id)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cash_entries_source_kind_source_id ON cash_entries(source_kind,source_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_entries_cash_account_id_is_deleted_occurred_at_ms_id ON cash_entries(cash_account_id,is_deleted,occurred_at_ms DESC,id DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_entries_savings_account_id_currency_code ON cash_entries(savings_account_id,currency_code)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS cash_movements (
            id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,operation_id TEXT NOT NULL,savings_account_id INTEGER NOT NULL,
            currency_code TEXT NOT NULL,cash_account_id INTEGER NOT NULL,reason TEXT NOT NULL,delta_minor INTEGER NOT NULL,
            balance_before_minor INTEGER NOT NULL,balance_after_minor INTEGER NOT NULL,created_at_ms INTEGER NOT NULL,
            FOREIGN KEY(operation_id) REFERENCES operations(operation_id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(cash_account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_cash_movements_operation_id_cash_account_id ON cash_movements(operation_id,cash_account_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_movements_cash_account_id_id ON cash_movements(cash_account_id,id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_movements_savings_account_id_currency_code ON cash_movements(savings_account_id,currency_code)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS app_settings (
            id INTEGER NOT NULL PRIMARY KEY,base_currency TEXT,revision INTEGER NOT NULL,language TEXT NOT NULL,
            gain_loss_scheme TEXT NOT NULL,
            FOREIGN KEY(base_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_app_settings_base_currency ON app_settings(base_currency)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS fx_rates (
            source_currency TEXT NOT NULL,target_currency TEXT NOT NULL,rate TEXT NOT NULL,updated_at_ms INTEGER NOT NULL,
            PRIMARY KEY(source_currency,target_currency),
            FOREIGN KEY(source_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(target_currency) REFERENCES currencies(code) ON UPDATE NO ACTION ON DELETE RESTRICT)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_fx_rates_target_currency ON fx_rates(target_currency)")
    }
}

package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_2_3=object:Migration(2,3) {
    override fun migrate(db:SupportSQLiteDatabase) {
        // A later manually updated market price cannot reconstruct an old opening cost.
        db.execSQL("ALTER TABLE investments ADD COLUMN opening_cost_price_e8 INTEGER")
        db.execSQL("ALTER TABLE investments ADD COLUMN position_state TEXT NOT NULL DEFAULT 'PENDING'")
        db.execSQL("ALTER TABLE investments ADD COLUMN last_activity_at_ms INTEGER NOT NULL DEFAULT 0")
        db.execSQL("""UPDATE investments SET
            position_state=CASE WHEN holding_quantity_e8>0 THEN 'HOLDING'
                WHEN EXISTS(SELECT 1 FROM investment_trades t WHERE t.investment_id=investments.id AND t.is_deleted=0) THEN 'CLOSED' ELSE 'PENDING' END,
            last_activity_at_ms=COALESCE((SELECT MAX(occurred_at_ms) FROM investment_trades t WHERE t.investment_id=investments.id AND t.is_deleted=0),created_at_ms)""")
        db.execSQL("CREATE INDEX index_investments_savings_account_id_position_state_last_activity_at_ms_id ON investments(savings_account_id,position_state,last_activity_at_ms DESC,id DESC)")
        db.execSQL("CREATE INDEX index_term_deposits_savings_account_id_status_start_epoch_day_id ON term_deposits(savings_account_id,status,start_epoch_day DESC,id DESC)")
    }
}

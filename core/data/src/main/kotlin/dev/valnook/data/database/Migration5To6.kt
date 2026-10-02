package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private const val MIGRATION_5_6_COST_RULE_VERSION = 3

/** Preserves v0.0.3 data while adding transaction fees to the moving-average cost chain. */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE investment_trades ADD COLUMN fee_minor INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE investments SET algorithm_version = $MIGRATION_5_6_COST_RULE_VERSION")
    }
}

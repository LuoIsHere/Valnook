package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Adds immutable business audit and local restore-generation state without rebuilding user tables. */
val MIGRATION_8_9 = object : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS audit_events (
            event_id TEXT NOT NULL PRIMARY KEY,
            event_schema_version INTEGER NOT NULL,
            correlation_id TEXT NOT NULL,
            operation_id TEXT,
            action TEXT NOT NULL,
            entity_kind TEXT NOT NULL,
            entity_id TEXT,
            business_at_ms INTEGER,
            business_local_date TEXT,
            recorded_at_ms INTEGER NOT NULL,
            before_json TEXT,
            after_json TEXT,
            changed_fields_json TEXT NOT NULL,
            cash_effects_json TEXT NOT NULL,
            source TEXT NOT NULL
        )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_audit_events_operation_id ON audit_events(operation_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_events_recorded_at_ms_event_id ON audit_events(recorded_at_ms,event_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS audit_event_accounts (
            event_id TEXT NOT NULL,
            account_id INTEGER NOT NULL,
            PRIMARY KEY(event_id,account_id),
            FOREIGN KEY(event_id) REFERENCES audit_events(event_id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_audit_event_accounts_account_id ON audit_event_accounts(account_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS audit_metadata (
            id INTEGER NOT NULL PRIMARY KEY,
            protocol_version INTEGER NOT NULL,
            tracking_start_ms INTEGER NOT NULL,
            tracking_start_database_version INTEGER NOT NULL,
            complete_since_start INTEGER NOT NULL,
            legacy_history_before_start INTEGER NOT NULL
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS local_maintenance_state (
            id INTEGER NOT NULL PRIMARY KEY,
            dataset_generation INTEGER NOT NULL,
            maintenance_in_progress INTEGER NOT NULL,
            last_restore_attempt_id TEXT,
            last_restore_backup_sha256 TEXT,
            last_restore_committed_at_ms INTEGER,
            upload_pause_reason TEXT,
            updated_at_ms INTEGER NOT NULL
        )""")
        val now = System.currentTimeMillis()
        val hasLegacy = db.query("""SELECT CASE WHEN EXISTS(SELECT 1 FROM operations LIMIT 1)
            OR EXISTS(SELECT 1 FROM savings_accounts LIMIT 1) THEN 1 ELSE 0 END""").use {
            it.moveToFirst() && it.getInt(0) != 0
        }
        db.execSQL("""INSERT INTO audit_metadata(id,protocol_version,tracking_start_ms,
            tracking_start_database_version,complete_since_start,legacy_history_before_start)
            VALUES (1,1,?,9,1,?)""", arrayOf<Any>(now, if (hasLegacy) 1 else 0))
        db.execSQL("""INSERT INTO local_maintenance_state(id,dataset_generation,maintenance_in_progress,
            last_restore_attempt_id,last_restore_backup_sha256,last_restore_committed_at_ms,
            upload_pause_reason,updated_at_ms) VALUES (1,1,0,NULL,NULL,NULL,NULL,?)""", arrayOf(now))
    }
}

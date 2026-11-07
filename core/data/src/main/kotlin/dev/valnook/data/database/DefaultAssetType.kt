package dev.valnook.data.database

import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.AssetTypeRules

/**
 * Makes sure the built-in "uncategorized" class exists. A class the user already named so becomes it, rather than
 * leaving two of the same name. Shared by database opening, restores and clearing all data.
 */
internal fun ensureDefaultAssetType(db: SupportSQLiteDatabase, nowMs: Long) {
    db.query("SELECT 1 FROM asset_types WHERE normalized_name=?", arrayOf(AssetTypeRules.DEFAULT_KEY)).use {
        if (it.moveToFirst()) return
    }
    val adopted = db.query("""SELECT id FROM asset_types WHERE normalized_name IN (?,?)
        ORDER BY normalized_name=? DESC,id LIMIT 1""",
        arrayOf(AssetTypeRules.DEFAULT_NAME, "uncategorized", AssetTypeRules.DEFAULT_NAME)).use {
        if (it.moveToFirst()) it.getLong(0) else null
    }
    if (adopted != null) db.execSQL("UPDATE asset_types SET name=?,normalized_name=?,updated_at_ms=? WHERE id=?",
        arrayOf(AssetTypeRules.DEFAULT_NAME, AssetTypeRules.DEFAULT_KEY, nowMs, adopted))
    else db.execSQL("INSERT INTO asset_types(name,normalized_name,created_at_ms,updated_at_ms) VALUES (?,?,?,?)",
        arrayOf(AssetTypeRules.DEFAULT_NAME, AssetTypeRules.DEFAULT_KEY, nowMs, nowMs))
}

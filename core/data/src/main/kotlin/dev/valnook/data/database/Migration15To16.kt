package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_15_16 = object : Migration(15, 16) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS wallet_card_images (id TEXT NOT NULL PRIMARY KEY,data BLOB NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,tint INTEGER NOT NULL)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS wallet_cards (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            name TEXT NOT NULL,image_key TEXT,bound_cash_account_id INTEGER,display_order INTEGER NOT NULL,
            created_at_ms INTEGER NOT NULL,updated_at_ms INTEGER NOT NULL,revision INTEGER NOT NULL,binding_lost INTEGER NOT NULL,
            FOREIGN KEY(image_key) REFERENCES wallet_card_images(id) ON UPDATE NO ACTION ON DELETE RESTRICT,
            FOREIGN KEY(bound_cash_account_id) REFERENCES cash_accounts(id) ON UPDATE NO ACTION ON DELETE SET NULL)""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wallet_cards_image_key ON wallet_cards(image_key)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wallet_cards_bound_cash_account_id ON wallet_cards(bound_cash_account_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_wallet_cards_display_order_id ON wallet_cards(display_order,id)")
        // Frozen as written for schema 16: the wallet goes after accounts and is shown. Later pages (bills) are added
        // when the settings are read, not here, so this step stays the same however the navigation rules grow.
        db.query("SELECT navigation_order,navigation_visible FROM app_settings WHERE id=1").use { row ->
            if (row.moveToFirst()) {
                val pages = listOf("ACCOUNTS", "INVESTMENTS", "STATISTICS", "SETTINGS")
                fun parse(raw: String?) = raw.orEmpty().split(',').filter { it in pages || it == "WALLET" }.distinct()
                val order = parse(row.getString(0)).toMutableList()
                val visible = parse(row.getString(1)).toMutableSet()
                if (order.isNotEmpty()) {
                    if ("WALLET" !in order) {
                        order.add((order.indexOf("ACCOUNTS") + 1).coerceAtLeast(0), "WALLET")
                        visible.add("WALLET")
                    }
                    pages.filterNot(order::contains).forEach(order::add)
                    visible.add("SETTINGS")
                    db.execSQL("UPDATE app_settings SET navigation_order=?,navigation_visible=? WHERE id=1", arrayOf(
                        order.joinToString(","), order.filter(visible::contains).joinToString(",")))
                }
            }
        }
    }
}

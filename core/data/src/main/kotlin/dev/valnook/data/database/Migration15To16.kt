package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.NavigationConfiguration

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
        db.query("SELECT navigation_order,navigation_visible FROM app_settings WHERE id=1").use { row ->
            if (row.moveToFirst()) {
                val navigation = NavigationConfiguration.restore(row.getString(0), row.getString(1))
                db.execSQL("UPDATE app_settings SET navigation_order=?,navigation_visible=? WHERE id=1", arrayOf(
                    navigation.order.joinToString(",") { it.name }, navigation.visible.joinToString(",") { it.name }))
            }
        }
    }
}

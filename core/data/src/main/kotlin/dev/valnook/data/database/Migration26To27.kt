package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Bill pictures: which bill holds which picture, and what this device knows about each file. */
val MIGRATION_26_27 = object : Migration(26, 27) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS `bill_attachments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
            `bill_id` INTEGER NOT NULL, `image_key` TEXT NOT NULL, `byte_size` INTEGER NOT NULL, `width` INTEGER NOT NULL,
            `height` INTEGER NOT NULL, `sort_order` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL,
            FOREIGN KEY(`bill_id`) REFERENCES `bill_entries`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_attachments_bill_id_sort_order` ON `bill_attachments` (`bill_id`, `sort_order`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_attachments_bill_id_image_key` ON `bill_attachments` (`bill_id`, `image_key`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_bill_attachments_image_key` ON `bill_attachments` (`image_key`)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS `bill_image_files` (`image_key` TEXT NOT NULL, `local_saved_at_ms` INTEGER,
            `cloud_size` INTEGER, `cloud_quick_xor` TEXT, `cloud_seen_at_ms` INTEGER, `cloud_delete_requested_at_ms` INTEGER,
            PRIMARY KEY(`image_key`))""")
    }
}

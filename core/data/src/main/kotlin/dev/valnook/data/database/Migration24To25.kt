package dev.valnook.data.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import dev.valnook.domain.model.BillCategoryPresets

/** Bills (0.0.19): bill categories with the presets, bill entries, and favourite sub-accounts for the bill account picker. */
val MIGRATION_24_25 = object : Migration(24, 25) {
    override fun migrate(db: SupportSQLiteDatabase) {
        BILL_SCHEMA_25.forEach(db::execSQL)
        db.execSQL("ALTER TABLE cash_accounts ADD COLUMN is_favorite INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE cash_accounts ADD COLUMN favorite_order INTEGER NOT NULL DEFAULT 0")
        insertDefaultBillCategories(db, System.currentTimeMillis())
    }
}

/** Room's own statements for the two bill tables at schema 25 (see schemas/.../25.json). */
internal val BILL_SCHEMA_25 = listOf(
    """CREATE TABLE IF NOT EXISTS `bill_categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `kind` TEXT NOT NULL, `parent_id` INTEGER, `builtin_key` TEXT, `custom_name` TEXT, `icon_key` TEXT NOT NULL, `color_key` TEXT NOT NULL, `sort_order` INTEGER NOT NULL, `hidden` INTEGER NOT NULL, `last_cash_account_id` INTEGER, `revision` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, FOREIGN KEY(`parent_id`) REFERENCES `bill_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`last_cash_account_id`) REFERENCES `cash_accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
    """CREATE INDEX IF NOT EXISTS `index_bill_categories_kind_parent_id_sort_order` ON `bill_categories` (`kind`, `parent_id`, `sort_order`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_categories_parent_id` ON `bill_categories` (`parent_id`)""",
    """CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_categories_builtin_key` ON `bill_categories` (`builtin_key`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_categories_last_cash_account_id` ON `bill_categories` (`last_cash_account_id`)""",
    """CREATE TABLE IF NOT EXISTS `bill_entries` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `operation_id` TEXT NOT NULL, `type` TEXT NOT NULL, `occurred_at_ms` INTEGER NOT NULL, `amount_minor` INTEGER NOT NULL, `currency_code` TEXT NOT NULL, `discount_minor` INTEGER NOT NULL, `cash_account_id` INTEGER, `account_currency_code` TEXT NOT NULL, `account_amount_minor` INTEGER NOT NULL, `rate` TEXT NOT NULL, `rate_source` TEXT NOT NULL, `transfer_kind` TEXT, `to_cash_account_id` INTEGER, `to_currency_code` TEXT, `to_amount_minor` INTEGER, `fee_minor` INTEGER NOT NULL, `counterparty` TEXT NOT NULL, `category_id` INTEGER, `sub_category_id` INTEGER, `count_in_stats` INTEGER NOT NULL, `reimbursable` INTEGER NOT NULL, `reimburse_state` TEXT NOT NULL, `parent_id` INTEGER, `batch_id` TEXT, `note` TEXT NOT NULL, `revision` INTEGER NOT NULL, `is_deleted` INTEGER NOT NULL, `created_at_ms` INTEGER NOT NULL, `updated_at_ms` INTEGER NOT NULL, FOREIGN KEY(`operation_id`) REFERENCES `operations`(`operation_id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`currency_code`) REFERENCES `currencies`(`code`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`account_currency_code`) REFERENCES `currencies`(`code`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`to_currency_code`) REFERENCES `currencies`(`code`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`cash_account_id`) REFERENCES `cash_accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`to_cash_account_id`) REFERENCES `cash_accounts`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`category_id`) REFERENCES `bill_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`sub_category_id`) REFERENCES `bill_categories`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , FOREIGN KEY(`parent_id`) REFERENCES `bill_entries`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )""",
    """CREATE UNIQUE INDEX IF NOT EXISTS `index_bill_entries_operation_id` ON `bill_entries` (`operation_id`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_is_deleted_occurred_at_ms_id` ON `bill_entries` (`is_deleted` ASC, `occurred_at_ms` DESC, `id` DESC)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_currency_code` ON `bill_entries` (`currency_code`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_account_currency_code` ON `bill_entries` (`account_currency_code`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_to_currency_code` ON `bill_entries` (`to_currency_code`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_cash_account_id` ON `bill_entries` (`cash_account_id`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_to_cash_account_id` ON `bill_entries` (`to_cash_account_id`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_category_id` ON `bill_entries` (`category_id`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_sub_category_id` ON `bill_entries` (`sub_category_id`)""",
    """CREATE INDEX IF NOT EXISTS `index_bill_entries_parent_id` ON `bill_entries` (`parent_id`)"""
)

/**
 * Adds the preset categories that are missing, parents before their children, each at the end of its siblings.
 * Used for a new database, by the migration, and for a restored backup made before bills existed.
 */
internal fun insertDefaultBillCategories(db: SupportSQLiteDatabase, nowMs: Long) {
    BillCategoryPresets.all.forEach { preset ->
        val parentId = preset.parentKey?.let { key ->
            db.query("SELECT id FROM bill_categories WHERE builtin_key=?", arrayOf(key)).use { if (it.moveToFirst()) it.getLong(0) else null }
                ?: return@forEach
        }
        db.execSQL("""INSERT OR IGNORE INTO bill_categories(kind,parent_id,builtin_key,custom_name,icon_key,color_key,
            sort_order,hidden,last_cash_account_id,revision,created_at_ms,updated_at_ms)
            VALUES (?,?,?,NULL,?,?,(SELECT COALESCE(MAX(sort_order),-1)+1 FROM bill_categories WHERE kind=? AND parent_id IS ?),
            0,NULL,1,?,?)""",
            arrayOf(preset.kind.name, parentId, preset.key, preset.icon, preset.color, preset.kind.name, parentId, nowMs, nowMs))
    }
}

package dev.valnook.data.portability

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pins the tables and columns each archive version contains, so older backups keep restoring. */
class BackupContractTest {
    private fun tables(version: Int) = BackupContract.tablesFor(version).map { it.table }.toSet()
    private fun columns(version: Int, table: String) =
        BackupContract.tablesFor(version).single { it.table == table }.columns.map { it.name }.toSet()

    @Test fun each_version_reads_only_what_it_wrote() {
        val versionOneExtras = setOf("credit_account_profiles", "account_icon_images", "wallet_cards", "wallet_card_images", "account_groups", "fx_rate_history", "bill_categories", "bill_entries", "bill_attachments", "ai_settings")
        assertEquals(BackupContract.tables.map { it.table }.toSet() - versionOneExtras, tables(1))
        assertTrue("credit_account_profiles" in tables(2) && "account_icon_images" !in tables(3))
        assertTrue("account_icon_images" in tables(4) && "wallet_cards" !in tables(5))
        assertEquals(tables(6), tables(7))
        assertTrue("wallet_cards" in tables(7) && "account_groups" !in tables(7))
        assertEquals(tables(8), tables(9))
        val bills = setOf("bill_categories", "bill_entries", "bill_attachments", "ai_settings")
        assertEquals(BackupContract.tables.map { it.table }.toSet() - "fx_rate_history" - bills, tables(9))
        assertEquals(BackupContract.tables.map { it.table }.toSet() - bills, tables(10))
        assertEquals(BackupContract.tables.map { it.table }.toSet() - "bill_attachments" - "ai_settings", tables(11))
        assertEquals(tables(11), tables(12))
        assertEquals(BackupContract.tables.map { it.table }.toSet() - "ai_settings", tables(13))
        assertEquals(BackupContract.tables.map { it.table }.toSet(), tables(14))
    }

    @Test fun bill_pictures_back_up_as_references_from_version_thirteen() {
        assertTrue("data/bill_attachments.jsonl" !in BackupContract.payloadPathsFor(12))
        assertTrue("data/bill_attachments.jsonl" in BackupContract.payloadPathsFor(13))
        // What this device knows about each file never leaves it.
        assertTrue(BackupContract.tables.none { it.table == "bill_image_files" })
        assertTrue(BackupContract.importOrder.indexOf("bill_attachments") > BackupContract.importOrder.indexOf("bill_entries"))
    }

    @Test fun rate_history_starts_at_version_ten_and_keeps_device_state_out() {
        assertEquals(setOf("base_currency", "quote_currency", "local_date", "source_date", "rate", "fetched_at_ms"),
            columns(10, "fx_rate_history"))
        assertTrue("data/fx_rate_history.jsonl" !in BackupContract.payloadPathsFor(9))
        assertTrue(BackupContract.tables.none { it.table == "fx_local_state" })
        assertTrue(BackupContract.importOrder.indexOf("fx_rate_history") > BackupContract.importOrder.indexOf("fx_rates"))
    }

    @Test fun account_columns_follow_the_version_that_added_them() {
        val base = setOf("id", "name", "note", "created_at_ms", "updated_at_ms", "revision")
        assertEquals(base, columns(2, "savings_accounts"))
        assertEquals(base + "display_order", columns(3, "savings_accounts"))
        assertEquals(base + setOf("display_order", "icon_type", "icon_value"), columns(4, "savings_accounts"))
        val presentation = base + setOf("display_order", "icon_type", "icon_value", "show_deposit_summary", "show_investment_summary")
        assertEquals(presentation, columns(7, "savings_accounts"))
        assertEquals(presentation + "group_id", columns(8, "savings_accounts"))
        assertTrue("face_style" !in columns(6, "wallet_cards") && "face_style" in columns(7, "wallet_cards"))
    }

    @Test fun face_library_columns_start_at_version_nine() {
        val image = setOf("id", "data", "width", "height", "tint")
        assertEquals(image, columns(8, "wallet_card_images"))
        assertEquals(image + setOf("label", "added_at_ms"), columns(9, "wallet_card_images"))
        assertEquals(columns(8, "savings_accounts"), columns(9, "savings_accounts"))
    }

    @Test fun bills_and_favourites_start_at_version_eleven_after_what_they_point_at() {
        assertTrue("is_favorite" !in columns(10, "cash_accounts") && "favorite_order" !in columns(10, "cash_accounts"))
        assertTrue(setOf("is_favorite", "favorite_order").all { it in columns(11, "cash_accounts") })
        val order = BackupContract.importOrder
        listOf("operations", "cash_accounts", "bill_categories").forEach {
            assertTrue(it, order.indexOf(it) in 0 until order.indexOf("bill_entries"))
        }
        assertTrue(order.indexOf("cash_accounts") < order.indexOf("bill_categories"))
        assertTrue("bills-v1" in BackupContract.requiredFeatures)
        assertTrue("account_deleted" !in columns(11, "bill_entries") && "account_deleted" in columns(12, "bill_entries"))
    }

    @Test fun ai_settings_back_up_from_version_fourteen_without_the_key() {
        assertTrue("data/ai_settings.jsonl" !in BackupContract.payloadPathsFor(13))
        assertTrue("data/ai_settings.jsonl" in BackupContract.payloadPathsFor(14))
        assertTrue("ai-settings-v1" in BackupContract.requiredFeatures)
        // The key and check results stay on the device: no column could hold them.
        assertTrue(columns(14, "ai_settings").none { it.contains("key") || it.contains("check") })
        assertTrue(setOf("base_url", "model", "my_rules", "user_nickname", "default_time_minutes").all { it in columns(14, "ai_settings") })
        // The pictures switch came later: an older backup has no such column.
        assertTrue("default_keep_images" !in columns(14, "ai_settings"))
        assertTrue("default_keep_images" in columns(15, "ai_settings"))
    }

    @Test fun the_statement_day_switch_backs_up_from_version_sixteen() {
        assertTrue("statement_day_included" !in columns(15, "credit_account_profiles"))
        assertTrue("statement_day_included" in columns(16, "credit_account_profiles"))
        assertTrue("statement_day" in columns(15, "credit_account_profiles"))
    }

    @Test fun cloud_connection_tables_stay_out_of_portable_backups() {
        val tables = BackupContract.tables.map { it.table }
        listOf("cloud_backup_state", "cloud_backup_attempts", "cloud_backup_index").forEach {
            org.junit.Assert.assertFalse(it, it in tables)
        }
    }

    @Test fun unknown_versions_have_no_tables() {
        assertTrue(BackupContract.tablesFor(0).isEmpty())
        assertTrue(BackupContract.tablesFor(BackupContract.DATA_SCHEMA_VERSION + 1).isEmpty())
    }
}

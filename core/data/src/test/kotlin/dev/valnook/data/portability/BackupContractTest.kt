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
        val versionOneExtras = setOf("credit_account_profiles", "account_icon_images", "wallet_cards", "wallet_card_images", "account_groups")
        assertEquals(BackupContract.tables.map { it.table }.toSet() - versionOneExtras, tables(1))
        assertTrue("credit_account_profiles" in tables(2) && "account_icon_images" !in tables(3))
        assertTrue("account_icon_images" in tables(4) && "wallet_cards" !in tables(5))
        assertEquals(tables(6), tables(7))
        assertTrue("wallet_cards" in tables(7) && "account_groups" !in tables(7))
        assertEquals(BackupContract.tables.map { it.table }.toSet(), tables(8))
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

    @Test fun unknown_versions_have_no_tables() {
        assertTrue(BackupContract.tablesFor(0).isEmpty())
        assertTrue(BackupContract.tablesFor(BackupContract.DATA_SCHEMA_VERSION + 1).isEmpty())
    }
}

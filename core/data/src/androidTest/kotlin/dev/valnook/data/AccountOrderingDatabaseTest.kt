package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.valnook.data.database.*
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.RoomAccountGroupWriter
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AccountOrderingDatabaseTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(),
        ValnookDatabase::class.java, emptyList(), FrameworkSQLiteOpenHelperFactory())
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.systemUTC()
    private val databases = mutableListOf<ValnookDatabase>()
    @After fun close() { databases.forEach { it.close() } }
    private fun database() = ValnookDatabase.inMemory(context).also { databases += it }
    private fun operation() = UUID.randomUUID().toString()
    private suspend fun account(db: ValnookDatabase, name: String, rows: List<BalanceAccountChange> = emptyList()) =
        RoomFinancialCommands(db, clock).execute(SaveAccount(operation(), null, null, name, "", rows)).id
    private fun count(db: ValnookDatabase, table: String): Long = db.openHelper.readableDatabase
        .query("SELECT COUNT(*) FROM $table").use { it.moveToFirst(); it.getLong(0) }
    private suspend fun expectError(code: ErrorCode, action: suspend () -> Unit) {
        try { action(); fail("Expected $code") } catch (error: DomainException) { assertEquals(code, error.code) }
    }

    @Test fun v11_migration_preserves_previous_order_and_financial_values() {
        val name = "ordering-migration-${operation()}.db"
        try {
            helper.createDatabase(name, 11).apply {
                execSQL("INSERT INTO currencies VALUES ('CNY',2)")
                execSQL("INSERT INTO savings_accounts VALUES (7,'First','note',10,20,4)")
                execSQL("INSERT INTO savings_accounts VALUES (9,'Second','',11,21,5)")
                execSQL("INSERT INTO cash_accounts VALUES (7,'CNY',-123,3,20,2,'Z','',1,10)")
                execSQL("INSERT INTO cash_accounts VALUES (7,'CNY',456,4,20,3,'A','',1,10)")
                execSQL("INSERT INTO cash_accounts VALUES (9,'CNY',789,5,21,4,'B','',1,11)")
                close()
            }
            helper.runMigrationsAndValidate(name, 12, true, MIGRATION_11_12).apply {
                query("SELECT id,revision FROM savings_accounts ORDER BY display_order,id").use {
                    assertTrue(it.moveToNext()); assertEquals(7L, it.getLong(0)); assertEquals(4L, it.getLong(1))
                    assertTrue(it.moveToNext()); assertEquals(9L, it.getLong(0))
                }
                query("SELECT id,balance_minor,revision FROM cash_accounts WHERE savings_account_id=7 ORDER BY display_order,id").use {
                    assertTrue(it.moveToNext()); assertEquals(3L, it.getLong(0)); assertEquals(456L, it.getLong(1))
                    assertTrue(it.moveToNext()); assertEquals(2L, it.getLong(0)); assertEquals(-123L, it.getLong(1)); assertEquals(3L, it.getLong(2))
                }
                query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
                close()
            }
        } finally { context.deleteDatabase(name) }
    }

    private suspend fun order(db: ValnookDatabase) = RoomOverview(db).snapshot().accounts.map { it.id }
    private fun displayOrders(db: ValnookDatabase): List<Long> = db.openHelper.readableDatabase
        .query("SELECT display_order FROM savings_accounts ORDER BY display_order").use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getLong(0) else null }.toList()
        }

    @Test fun group_changes_keep_one_global_order_and_do_not_change_financial_history(): Unit = runBlocking {
        val db = database()
        val a = account(db, "A"); val b = account(db, "B"); val c = account(db, "C")
        val stats = db.statistics().state()
        val writer = RoomAccountGroupWriter(db, clock)
        val group = writer.create("Credit")
        writer.moveAccounts(group, listOf(b))
        assertEquals(listOf(a, c, b), order(db))
        writer.reorderGroups(listOf(group, AccountGroup.DEFAULT_ID))
        assertEquals(listOf(b, a, c), order(db))
        writer.reorderAccounts(AccountGroup.DEFAULT_ID, listOf(c, a))
        assertEquals(listOf(b, c, a), order(db))
        val d = account(db, "D") // New accounts join the end of the default group.
        assertEquals(listOf(b, c, a, d), order(db))
        assertEquals(AccountGroup.DEFAULT_ID, RoomOverview(db).snapshot().accounts.single { it.id == d }.groupId)
        writer.setCollapsed(group, true)
        assertTrue(RoomOverview(db).snapshot().groups.single { it.id == group }.collapsed)
        writer.delete(group) // Its accounts return to the end of the default group.
        assertEquals(listOf(c, a, d, b), order(db))
        assertEquals(listOf(AccountGroup.DEFAULT_ID), RoomOverview(db).snapshot().groups.map { it.id })
        assertEquals(listOf(0L, 1L, 2L, 3L), displayOrders(db))
        assertEquals(stats, db.statistics().state())
        assertEquals(0L, count(db, "cash_movements"))
        expectError(ErrorCode.OPERATION_CONFLICT) { writer.rename(AccountGroup.DEFAULT_ID, "Renamed") }
        expectError(ErrorCode.OPERATION_CONFLICT) { writer.delete(AccountGroup.DEFAULT_ID) }
        expectError(ErrorCode.NAME) { writer.create("   ") }
        expectError(ErrorCode.STALE_RECORD) { writer.reorderAccounts(AccountGroup.DEFAULT_ID, listOf(a, c)) }
        expectError(ErrorCode.STALE_RECORD) { writer.moveAccounts(group, listOf(a)) }
    }

    @Test fun clearing_data_removes_groups_and_resets_the_default_group(): Unit = runBlocking {
        val db = database()
        val a = account(db, "A")
        val writer = RoomAccountGroupWriter(db, clock)
        val group = writer.create("Family")
        writer.create("Travel")
        writer.moveAccounts(group, listOf(a))
        writer.reorderGroups(listOf(group, AccountGroup.DEFAULT_ID) +
            RoomOverview(db).snapshot().groups.map { it.id }.filter { it != group && it != AccountGroup.DEFAULT_ID })
        writer.setCollapsed(AccountGroup.DEFAULT_ID, true)

        dev.valnook.data.repository.RoomDataMaintenance(db).clearBusinessData()

        assertEquals(listOf(AccountGroup(AccountGroup.DEFAULT_ID, "", 0)), RoomOverview(db).snapshot().groups)
        // The ledger starts over: new groups follow the default group again.
        val next = writer.create("Family")
        assertEquals(listOf(AccountGroup.DEFAULT_ID, next), RoomOverview(db).snapshot().groups.map { it.id })
    }

    @Test fun group_commands_are_receipted_audited_and_advance_the_generation(): Unit = runBlocking {
        val db = database()
        val a = account(db, "A"); val b = account(db, "B")
        val commands = RoomFinancialCommands(db, clock)
        fun events() = db.openHelper.readableDatabase.query(
            "SELECT action,entity_kind,source,changed_fields_json FROM audit_events WHERE entity_kind='ACCOUNT_GROUP' ORDER BY recorded_at_ms,rowid")
            .use { cursor -> generateSequence { if (cursor.moveToNext()) List(4) { cursor.getString(it) } else null }.toList() }
        val generation = db.audit().generation()
        val create = SaveAccountGroup(operation(), null, "Cards")
        val group = commands.execute(create, CommandSource.WEB_ADMIN).id
        assertEquals(group, commands.execute(create, CommandSource.WEB_ADMIN).id) // A replay returns the receipt.
        assertEquals(2L, count(db, "account_groups"))
        commands.execute(MoveAccountsToGroup(operation(), group, listOf(b)), CommandSource.WEB_ADMIN)
        commands.execute(ReorderAccountGroups(operation(), listOf(group, AccountGroup.DEFAULT_ID)))
        assertEquals(listOf(b, a), order(db))
        expectError(ErrorCode.OPERATION_CONFLICT) { commands.execute(create.copy(name = "Other")) }
        commands.execute(DeleteAccountGroup(operation(), group))
        assertEquals(listOf(a, b), order(db))
        assertEquals(generation + 4, db.audit().generation())
        assertEquals(listOf(listOf("CREATE", "ACCOUNT_GROUP", "WEB_ADMIN", "[\"groups\"]"),
            listOf("UPDATE", "ACCOUNT_GROUP", "WEB_ADMIN", "[\"groups\"]"),
            listOf("UPDATE", "ACCOUNT_GROUP", "USER", "[\"groups\"]"),
            listOf("DELETE", "ACCOUNT_GROUP", "USER", "[\"groups\"]")), events())
        RoomAccountGroupWriter(db, clock).setCollapsed(AccountGroup.DEFAULT_ID, true) // View state only.
        assertEquals(generation + 4, db.audit().generation())
        assertEquals(0L, count(db, "cash_movements"))
    }

    @Test fun failed_group_change_rolls_back_and_success_survives_reopening(): Unit = runBlocking {
        val name = "ordering-durability-${operation()}.db"
        var db = Room.databaseBuilder(context, ValnookDatabase::class.java, name).addCallback(ValnookDatabase.seed).build()
        try {
            val first = account(db, "A"); val second = account(db, "B")
            db.openHelper.writableDatabase.execSQL("""CREATE TRIGGER reject_order BEFORE UPDATE OF display_order
                ON savings_accounts WHEN NEW.id=$first AND NEW.display_order=1 BEGIN SELECT RAISE(ABORT,'test fault'); END""")
            try { RoomAccountGroupWriter(db, clock).reorderAccounts(AccountGroup.DEFAULT_ID, listOf(second, first)); fail("Expected rollback") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(listOf(first, second), order(db))
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_order")
            RoomAccountGroupWriter(db, clock).reorderAccounts(AccountGroup.DEFAULT_ID, listOf(second, first))
            db.close()
            db = Room.databaseBuilder(context, ValnookDatabase::class.java, name).build()
            assertEquals(listOf(second, first), order(db))
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun subaccount_order_keeps_shared_credit_identity_and_does_not_add_cash_records(): Unit = runBlocking {
        val db = database()
        val parent = account(db, "Bank", listOf(
            BalanceAccountChange("CNY", 12500, null, name = "Cash"),
            BalanceAccountChange("CNY", -1234, null, name = "Visa", type = BalanceAccountType.CREDIT,
                credit = CreditAccountInput(50000, 12, CreditDueRule.AfterStatementDays(20), null))))
        val other = account(db, "Other", listOf(BalanceAccountChange("USD", 100, null)))
        val before = RoomOverview(db).snapshot()
        val rows = before.cash.filter { it.account_id == parent }.reversed()
        val changes = rows.mapIndexed { index, row -> BalanceAccountChange(row.currency.code, row.balance_minor,
            row.revision, row.id, row.name, row.note, row.type,
            row.creditProfile?.let { CreditAccountInput(it.creditLimitMinor, it.statementDay, it.dueRule, it.limitSourceAccountId) }, index.toLong()) }
        val owner = before.accounts.single { it.id == parent }
        val stats = db.statistics().state()
        val entries = count(db, "cash_entries"); val movements = count(db, "cash_movements")
        val command = SaveAccount(operation(), parent, owner.revision, owner.name, owner.note, changes)
        val commands = RoomFinancialCommands(db, clock)
        commands.execute(command)
        val after = RoomOverview(db).snapshot()
        assertEquals(rows, after.cash.filter { it.account_id == parent })
        assertEquals(before.cash.filter { it.account_id == other }, after.cash.filter { it.account_id == other })
        assertEquals(stats, db.statistics().state())
        assertEquals(entries, count(db, "cash_entries")); assertEquals(movements, count(db, "cash_movements"))
        commands.execute(command) // Retry remains idempotent.
        expectError(ErrorCode.OPERATION_CONFLICT) { commands.execute(command.copy(cashChanges = changes.reversed()
            .mapIndexed { i, row -> row.copy(displayOrder = i.toLong()) })) }
        val freshOwner = after.accounts.single { it.id == parent }
        expectError(ErrorCode.STALE_RECORD) { commands.execute(command.copy(operation_id = operation(),
            expectedRevision = freshOwner.revision, cashChanges = changes.take(1))) }
        assertEquals(rows, RoomOverview(db).snapshot().cash.filter { it.account_id == parent })
    }

    @Test fun portable_backup_restores_groups_main_and_subaccount_order(): Unit = runBlocking {
        val source = database()
        val first = account(source, "A", listOf(BalanceAccountChange("CNY", 123, null, name = "Z"),
            BalanceAccountChange("USD", 456, null, name = "A")))
        val second = account(source, "B")
        val third = account(source, "C")
        val writer = RoomAccountGroupWriter(source, clock)
        writer.reorderAccounts(AccountGroup.DEFAULT_ID, listOf(second, first, third))
        val group = writer.create("Brokers")
        writer.moveAccounts(group, listOf(third))
        writer.reorderGroups(listOf(group, AccountGroup.DEFAULT_ID))
        writer.setCollapsed(group, true)
        val cash = source.overview().allCash().filter { it.savings_account_id == first }
        cash.reversed().forEachIndexed { index, row -> source.cash().setDisplayOrder(first, row.id, index.toLong()) }
        val target = database()
        val build = AppBuildInfo("valnook", "0.0.7", 7, "20261005.1", "20261005.1", 12)
        val sourceEngine = RoomPortabilityEngine(context, source, clock, build)
        val targetEngine = RoomPortabilityEngine(context, target, clock, build)
        val bytes = ByteArrayOutputStream()
        sourceEngine.createBackup(operation(), bytes) {}
        val staged = targetEngine.prepareRestore(ByteArrayInputStream(bytes.toByteArray()), "ordering.val_backup") {}
        try {
            assertEquals(dev.valnook.domain.portability.BACKUP_DATA_SCHEMA_VERSION, targetEngine.preview(staged).dataSchemaVersion)
            targetEngine.commitRestore(staged) {}
        } finally { targetEngine.close(staged) }
        assertEquals(listOf(third, second, first), RoomOverview(target).snapshot().accounts.map { it.id })
        assertEquals(RoomOverview(source).snapshot().groups, RoomOverview(target).snapshot().groups)
        assertEquals(RoomOverview(source).snapshot().accounts, RoomOverview(target).snapshot().accounts)
        assertEquals(RoomOverview(source).snapshot().cash, RoomOverview(target).snapshot().cash)
    }
}

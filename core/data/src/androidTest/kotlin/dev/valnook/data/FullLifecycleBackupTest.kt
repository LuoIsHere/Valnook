package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.Direction
import dev.valnook.domain.money.DecimalRules as R
import dev.valnook.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FullLifecycleBackupTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T08:30:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private val databases = mutableListOf<Pair<String, ValnookDatabase>>()

    @Before fun clearWorkFiles() {
        context.cacheDir.resolve("valnook-portability").deleteRecursively()
    }

    @After fun closeDatabases() {
        databases.forEach { (name, database) -> database.close(); context.deleteDatabase(name) }
        context.cacheDir.resolve("valnook-portability").deleteRecursively()
    }

    @Test fun multi_edit_long_span_lifecycle_survives_backup_and_restore() = runBlocking {
        val source = database("full-lifecycle-source")
        val commands = RoomFinancialCommands(source, clock)
        val accountId = commands.execute(SaveAccount(id(), null, null, "生命周期测试账户", "创建时备注", listOf(
            CashBalanceChange("CNY", 10_000_000L, null, name = "人民币现金"),
            CashBalanceChange("USD", -50_000L, null, name = "美元负余额")
        ))).id
        var cny = source.cash().cashCandidates(accountId, "CNY").single()

        val repeatedlyEdited = commands.execute(SetCashBalance(id(), accountId, "CNY",
            cny.balance_minor + 300_000L, cny.revision, cny.id)).id
        commands.execute(EditCashEntry(id(), repeatedlyEdited, 1, 320_000L,
            at("2011-01-15"), "第一次修正"))
        commands.execute(EditCashEntry(id(), repeatedlyEdited, 2, 340_000L,
            at("2011-02-20"), "第二次修正"))
        commands.execute(EditCashEntry(id(), repeatedlyEdited, 3, 325_000L,
            at("2011-03-05"), "最终修正"))

        listOf(
            "2014-06-18" to -125_000L,
            "2018-09-07" to 480_000L,
            "2021-12-31" to -205_000L,
            "2026-09-20" to 610_000L
        ).forEach { (date, delta) ->
            cny = source.cash().cashAccount(cny.id)!!
            val entry = commands.execute(SetCashBalance(id(), accountId, "CNY",
                cny.balance_minor + delta, cny.revision, cny.id)).id
            commands.execute(EditCashEntry(id(), entry, 1, delta, at(date), "跨期记录 $date"))
        }

        cny = source.cash().cashAccount(cny.id)!!
        commands.execute(SaveAccount(id(), accountId, 1, "生命周期测试账户（已修改）", "最终备注", listOf(
            CashBalanceChange("CNY", cny.balance_minor, cny.revision, cny.id,
                name = "人民币长期现金", note = "允许多次修正")
        )))

        val depositId = commands.execute(OpenTermDeposit(id(), accountId, "CNY", 2_000_000L,
            e("3.10"), day("2016-01-10"), day("2017-01-10"), true, cny.id)).id
        commands.execute(EditTermDeposit(id(), depositId, 1, 2_100_000L, e("3.20"),
            day("2016-01-12"), day("2017-01-12"), true, null, cny.id))
        commands.execute(EditTermDeposit(id(), depositId, 2, 2_050_000L, e("3.30"),
            day("2016-01-15"), day("2017-01-15"), true, null, cny.id))
        commands.execute(CloseTermDeposit(id(), depositId, true, cny.id))
        commands.execute(EditTermDeposit(id(), depositId, 4, 2_025_000L, e("3.35"),
            day("2016-01-16"), day("2017-01-16"), true, true, cny.id, cny.id))
        commands.execute(EditTermDeposit(id(), depositId, 5, 2_040_000L, e("3.40"),
            day("2016-01-18"), day("2017-01-18"), true, true, cny.id, cny.id))

        val typeId = commands.execute(SaveAssetType(id(), null, "股票")).id
        val instrumentId = commands.execute(SaveInstrument(id(), null, null, "长期测试股票", "LIFE",
            typeId, "CNY", R.parse_units("175.50", 5))).id
        val positionId = commands.execute(CreateInvestmentPosition(id(), accountId, instrumentId)).id
        val buyId = commands.execute(RecordInvestmentTrade(id(), positionId, Direction.BUY,
            e("10"), e("100"), at("2012-03-01"), true, cny.id, 180)).id
        commands.execute(EditInvestmentTrade(id(), buyId, 1, Direction.BUY,
            e("12"), e("95"), at("2012-03-02"), true, cny.id, 210))
        commands.execute(EditInvestmentTrade(id(), buyId, 2, Direction.BUY,
            e("11"), e("98"), at("2012-03-03"), true, cny.id, 195))
        commands.execute(EditInvestmentTrade(id(), buyId, 3, Direction.BUY,
            e("11.5"), e("97"), at("2012-03-04"), true, cny.id, 205))
        commands.execute(RecordInvestmentTrade(id(), positionId, Direction.BUY,
            e("4"), e("120"), at("2018-08-10"), true, cny.id, 225))
        val sellId = commands.execute(RecordInvestmentTrade(id(), positionId, Direction.SELL,
            e("5"), e("160"), at("2024-04-08"), true, cny.id, 160)).id
        commands.execute(EditInvestmentTrade(id(), sellId, 1, Direction.SELL,
            e("4.5"), e("162"), at("2024-04-09"), true, cny.id, 170))
        commands.execute(EditInvestmentTrade(id(), sellId, 2, Direction.SELL,
            e("4.75"), e("161.5"), at("2024-04-10"), true, cny.id, 165))

        assertLifecycle(source, accountId, cny.id, repeatedlyEdited, depositId, positionId, buyId, sellId)

        val output = ByteArrayOutputStream()
        val backup = engine(source).createBackup(id(), output) {}
        assertTrue(backup.byteCount > 0)
        val target = database("full-lifecycle-target")
        val targetEngine = engine(target)
        val staged = targetEngine.prepareRestore(ByteArrayInputStream(output.toByteArray()),
            "full-lifecycle.val_backup") {}
        try {
            targetEngine.commitRestore(staged) {}
        } finally {
            targetEngine.close(staged)
        }
        assertLifecycle(target, accountId, cny.id, repeatedlyEdited, depositId, positionId, buyId, sellId)
        assertEquals(RoomOverview(source).snapshot(), RoomOverview(target).snapshot())
    }

    private suspend fun assertLifecycle(
        database: ValnookDatabase,
        accountId: Long,
        cashAccountId: Long,
        cashEntryId: Long,
        depositId: Long,
        positionId: Long,
        buyId: Long,
        sellId: Long
    ) {
        val account = database.accounts().account(accountId)!!
        assertEquals("生命周期测试账户（已修改）", account.name)
        assertEquals(2, account.revision)
        val cash = database.cash().cashAccount(cashAccountId)!!
        assertTrue(cash.balance_minor < 20_000_000L)
        assertEquals(4, database.cash().cash_entry(cashEntryId)!!.revision)
        assertEquals("最终修正", database.cash().cash_entry(cashEntryId)!!.note)
        database.openHelper.readableDatabase.query(
            "SELECT COALESCE(SUM(delta_minor),0),MIN(occurred_at_ms),MAX(occurred_at_ms) FROM cash_entries WHERE cash_account_id=? AND is_deleted=0",
            arrayOf(cashAccountId.toString())
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(cash.balance_minor, it.getLong(0))
            assertTrue(it.getLong(2) - it.getLong(1) >= 15L * 365L * 86_400_000L)
        }
        val deposit = database.deposits().deposit(depositId)!!
        assertEquals("CLOSED", deposit.status)
        assertEquals(6, deposit.revision)
        assertEquals(2_040_000L, deposit.principal_minor)
        val position = database.positions().investment(positionId)!!
        assertEquals("HOLDING", position.position_state)
        assertEquals(e("10.75"), position.holding_quantity_e8)
        assertEquals(4, database.trades().trade(buyId)!!.revision)
        assertEquals(3, database.trades().trade(sellId)!!.revision)
        assertTrue(position.realized_profit!!.toBigDecimal() > java.math.BigDecimal.ZERO)
    }

    private fun database(prefix: String): ValnookDatabase {
        val name = "$prefix-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, ValnookDatabase::class.java, name)
            .addCallback(ValnookDatabase.seed).build()
        databases += name to database
        return database
    }

    private fun engine(database: ValnookDatabase) = RoomPortabilityEngine(context, database, clock,
        AppBuildInfo("valnook", "0.0.5", 5, "20261003.4", "20261003.4", 10))

    private fun id(): String = UUID.randomUUID().toString()
    private fun e(value: String): Long = R.parse_e8(value)
    private fun day(value: String): Long = LocalDate.parse(value).toEpochDay()
    private fun at(value: String): Long = LocalDate.parse(value).atTime(10, 30)
        .atZone(clock.zone).toInstant().toEpochMilli()
}

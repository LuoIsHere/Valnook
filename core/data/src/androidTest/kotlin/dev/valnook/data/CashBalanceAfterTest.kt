package dev.valnook.data

import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.CashEntry
import dev.valnook.domain.repository.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

/** The balance shown under each change is worked out from the current balance, so it follows later corrections. */
@RunWith(AndroidJUnit4::class)
class CashBalanceAfterTest {
    private val zone = ZoneId.of("Asia/Hong_Kong")
    private val clock = Clock.fixed(Instant.parse("2026-08-01T04:00:00Z"), zone)
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private lateinit var cash: RoomCash
    private var cashId = 0L
    private lateinit var source: CashEntryEntity
    private var operations = 0

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), ValnookDatabase::class.java)
            .addCallback(ValnookDatabase.seed).build()
        commands = RoomFinancialCommands(db, clock)
        cash = RoomCash(db.cash())
        val account = commands.testAccount("A")
        commands.execute(SetCashBalance(testOperationId(), account, "CNY", 1000, null))
        cashId = RoomOverview(db).snapshot().cash.single().id
        source = db.cash().cashAccountMonthPage(cashId, 0, Long.MAX_VALUE, null, null, 1).single().entry
    }

    @After fun close() { db.close() }

    @Test fun a_balance_without_a_sub_account_is_refused_when_several_could_be_meant() = runBlocking {
        val account = commands.testAccount("Two of a kind")
        commands.execute(SetCashBalance(testOperationId(), account, "CNY", 100, null))
        // A second CNY sub-account, as the account editor can add.
        db.cash().insert_cash(CashEntity(account, "CNY", 0, 1, clock.millis(), name = "second", created_at_ms = clock.millis()))
        val before = db.cash().cashCandidates(account, "CNY").size
        val refused = runCatching { commands.execute(SetCashBalance(testOperationId(), account, "CNY", 500, 1)) }.exceptionOrNull()
        assertEquals(ErrorCode.WRONG_CASH_ACCOUNT, (refused as DomainException).code)
        assertEquals(before, db.cash().cashCandidates(account, "CNY").size)
    }

    private fun at(date: String, time: String = "12:00") = LocalDateTime.parse("${date}T$time").atZone(zone).toInstant().toEpochMilli()

    /** Records a change at [time] and moves the stored balance with it, as the app's commands do. */
    private suspend fun change(time: Long, delta: Long): Long = db.withTransaction {
        val operation = "balance-after-${operations++}"
        db.openHelper.writableDatabase.execSQL("INSERT INTO operations SELECT ?,kind,request_fingerprint,result_kind,result_id,created_at_ms FROM operations WHERE operation_id=?",
            arrayOf(operation, source.original_operation_id))
        val id = db.ledger().insert_entry(source.copy(id = 0, original_operation_id = operation, occurred_at_ms = time,
            delta_minor = delta, source_id = null))
        db.openHelper.writableDatabase.execSQL("UPDATE cash_accounts SET balance_minor=balance_minor+? WHERE id=?", arrayOf(delta, cashId))
        id
    }

    private fun month(year: Int, month: Int) = LedgerMonth(YearMonth.of(year, month), zone)

    private suspend fun page(month: LedgerMonth, size: Int = 100) = cash.cashAccountMonthPage(cashId, month, null, size)

    /** Every row of [month] one at a time through the cursor. */
    private suspend fun pagedOneByOne(month: LedgerMonth): List<CashEntry> {
        val rows = mutableListOf<CashEntry>()
        var cursor: LedgerCursor? = null
        while (true) {
            val next = cash.cashAccountMonthPage(cashId, month, cursor, 1)
            if (next.isEmpty()) break
            rows += next
            cursor = LedgerCursor(next.last().occurred_at_ms, next.last().id)
        }
        return rows
    }

    private fun List<CashEntry>.after() = map { it.delta_minor to it.balanceAfterMinor }

    @Test fun each_change_shows_the_balance_right_after_it_and_follows_a_backdated_entry() = runBlocking {
        change(at("2026-09-05"), 100)
        change(at("2026-09-20"), -30)
        change(at("2026-10-02"), 50)
        // Entered last but dated earlier: everything after it moves.
        change(at("2026-09-10"), 7)

        assertEquals(listOf(-30L to 1077L, 7L to 1107L, 100L to 1100L), page(month(2026, 9)).after())
        assertEquals(listOf(50L to 1127L), page(month(2026, 10)).after())
        assertEquals(listOf(1000L to 1000L), page(month(2026, 8)).after())
        assertEquals(page(month(2026, 9)), pagedOneByOne(month(2026, 9)))
    }

    @Test fun same_moment_changes_follow_their_record_order_and_a_balance_can_go_negative() = runBlocking {
        val time = at("2026-09-15", "09:30")
        change(time, -600)
        change(time, -700)
        change(at("2026-09-16"), 200)

        // Newest first: the later record of the same moment comes first and ends lower.
        assertEquals(listOf(200L to -100L, -700L to -300L, -600L to 400L), page(month(2026, 9)).after())
        assertEquals(page(month(2026, 9)), pagedOneByOne(month(2026, 9)))
        assertEquals(-100L, RoomOverview(db).snapshot().cash.single().balance_minor)
    }
}

package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.portability.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.*
import java.util.UUID

/** Refunds and reimbursements come back to an expense, never past what it paid, and go with it. Synthetic data only. */
@RunWith(AndroidJUnit4::class)
class BillSettlementTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val now = clock.millis()
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private val names = mutableListOf<String>()
    private fun id() = UUID.randomUUID().toString()
    private fun database(): ValnookDatabase {
        val name = "settle-test-${id()}.db"; names += name
        return Room.databaseBuilder(context, ValnookDatabase::class.java, name).addCallback(ValnookDatabase.seed).build()
    }
    @Before fun prepare() { db = database(); commands = RoomFinancialCommands(db, clock) }
    @After fun close() { db.close(); names.forEach(context::deleteDatabase) }

    private var a = 0L
    private var b = 0L
    private var usd = 0L
    private suspend fun accounts() {
        val bank = commands.execute(SaveAccount(id(), null, null, "Synthetic Bank", "", listOf(
            CashBalanceChange("CNY", 1_000_000, null, name = "A"), CashBalanceChange("CNY", 500_000, null, name = "B")))).id
        val broker = commands.execute(SaveAccount(id(), null, null, "Synthetic Broker", "", listOf(
            CashBalanceChange("USD", 100_000, null, name = "U")))).id
        val cash = RoomOverview(db).snapshot().cash
        a = cash.single { it.account_id == bank && it.name == "A" }.id
        b = cash.single { it.account_id == bank && it.name == "B" }.id
        usd = cash.single { it.account_id == broker }.id
    }
    private suspend fun balance(cashId: Long) = db.cash().cashAccount(cashId)!!.balance_minor
    private fun category(key: String) = db.openHelper.readableDatabase
        .query("SELECT id FROM bill_categories WHERE builtin_key=?", arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }
    private suspend fun expect(code: ErrorCode, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: DomainException) { assertEquals(code, e.code) }
    }
    private val bills get() = RoomBills(db)
    private suspend fun bill(billId: Long) = bills.observeBill(billId).first()!!
    private suspend fun expense(cash: Long?, amount: Long, discount: Long = 0, reimbursable: Boolean = false, counterparty: String = "") =
        commands.execute(SaveBill(id(), null, null, BillDraft(BillType.EXPENSE, now, amount, "CNY", discount, cash,
            amount - discount, categoryId = category("food"), subCategoryId = category("food.lunch"), note = "Synthetic dinner",
            countInStats = !reimbursable, reimbursable = reimbursable, counterparty = counterparty))).id
    private suspend fun settle(parent: Long, type: BillType, amount: Long, into: Long, received: Long = amount,
        rate: BigDecimal = BigDecimal.ONE, source: BillRateSource = BillRateSource.SAME) =
        commands.execute(SettleBill(id(), parent, bill(parent).revision, type,
            SettlementDraft(now + 60_000, amount, into, received, rate, source))).id

    @Test fun refunds_come_back_up_to_what_was_paid_and_can_be_undone() = runBlocking {
        accounts()
        val dinner = expense(a, 10_000, discount = 1_000)
        assertEquals(1_000_000L - 9_000, balance(a))
        // Part back to another account, then the rest; never more than was paid.
        val first = settle(dinner, BillType.REFUND, 3_000, b)
        assertEquals(500_000L + 3_000, balance(b))
        assertEquals(listOf(3_000L, 0L, 6_000L), bill(dinner).let { listOf(it.refundedMinor, it.reimbursedMinor, it.remainingMinor) })
        expect(ErrorCode.SETTLEMENT_LIMIT) { settle(dinner, BillType.REFUND, 6_001, a) }
        val second = settle(dinner, BillType.REFUND, 6_000, a)
        assertEquals(0L, bill(dinner).remainingMinor)
        expect(ErrorCode.SETTLEMENT_LIMIT) { settle(dinner, BillType.REFUND, 1, a) }
        // A refund carries its bill's category and names it by its note; it cannot be edited, only undone.
        val refund = bill(second)
        assertEquals(listOf(BillType.REFUND, dinner, category("food"), category("food.lunch"), "Synthetic dinner"),
            listOf(refund.type, refund.parentId, refund.categoryId, refund.subCategoryId, refund.parentNote))
        assertEquals(listOf(listOf(first, second)), listOf(bills.observeChildren(dinner).first().map { it.id }))
        expect(ErrorCode.SOURCE_RECORD) { commands.execute(SaveBill(id(), second, refund.revision, refund.toDraft())) }
        commands.execute(DeleteBill(id(), first, bill(first).revision))
        assertEquals(500_000L, balance(b))
        assertEquals(3_000L, bill(dinner).remainingMinor)
        // Only an expense takes a refund, and only a reimbursable one a reimbursement.
        expect(ErrorCode.FORMAT) { settle(dinner, BillType.REIMBURSEMENT, 100, a) }
        expect(ErrorCode.FORMAT) { settle(second, BillType.REFUND, 100, a) }
        // Deleting the bill takes what came back with it, and every account moves back.
        commands.execute(DeleteBill(id(), dinner, bill(dinner).revision))
        assertEquals(1_000_000L, balance(a))
        assertNull(bills.observeBill(second).first())
    }

    @Test fun a_refund_into_another_currency_converts_at_its_own_rate() = runBlocking {
        accounts()
        val dinner = expense(a, 7_100)
        expect(ErrorCode.FORMAT) { settle(dinner, BillType.REFUND, 7_100, usd, received = 1_000) }
        settle(dinner, BillType.REFUND, 7_100, usd, received = 1_000, rate = BigDecimal("0.140845"), source = BillRateSource.ONE_TIME)
        assertEquals(100_000L + 1_000, balance(usd))
        assertEquals(7_100L, bill(dinner).refundedMinor)
        // The amount actually received can set the rate instead.
        val lunch = expense(a, 1_000)
        val derived = BillRules.derivedRate(1_000, Currency.of("CNY"), 141, Currency.of("USD"))
        settle(lunch, BillType.REFUND, 1_000, usd, received = 141, rate = derived, source = BillRateSource.DERIVED)
        assertEquals(100_000L + 1_141, balance(usd))
    }

    @Test fun reimbursements_settle_one_or_many_and_can_be_waived() = runBlocking {
        accounts()
        val taxi = expense(a, 5_000, reimbursable = true, counterparty = "Synthetic Employer")
        val hotel = expense(a, 20_000, reimbursable = true)
        val plain = expense(a, 1_000)
        assertEquals(listOf(ReimburseState.PENDING, ReimburseState.PENDING, ReimburseState.NONE),
            listOf(taxi, hotel, plain).map { bill(it).reimburseState })
        assertEquals("Synthetic Employer", bill(taxi).counterparty)
        assertEquals(listOf(taxi, hotel), bills.observeReimbursable().first().map { it.id }.sortedBy { it })
        // Only an expense to be paid back names who pays.
        expect(ErrorCode.FORMAT) { commands.execute(SaveBill(id(), null, null, bill(plain).toDraft().copy(counterparty = "Someone"))) }

        settle(taxi, BillType.REIMBURSEMENT, 2_000, b)
        assertEquals(ReimburseState.PARTIAL, bill(taxi).reimburseState)
        // One batch into one account: each its own bill, the account moving once by the sum.
        commands.execute(SettleBills(id(), b, now + 120_000, listOf(
            SettlementItem(taxi, bill(taxi).revision, 3_000, 3_000, BigDecimal.ONE, BillRateSource.SAME),
            SettlementItem(hotel, bill(hotel).revision, 15_000, 15_000, BigDecimal.ONE, BillRateSource.SAME))))
        assertEquals(500_000L + 20_000, balance(b))
        assertEquals(listOf(ReimburseState.DONE, ReimburseState.PARTIAL), listOf(taxi, hotel).map { bill(it).reimburseState })
        val batch = db.openHelper.readableDatabase.query(
            "SELECT COUNT(DISTINCT batch_id),COUNT(*) FROM bill_entries WHERE type='REIMBURSEMENT' AND batch_id IS NOT NULL").use {
            it.moveToFirst(); it.getInt(0) to it.getInt(1) }
        assertEquals(1 to 2, batch)
        expect(ErrorCode.SETTLEMENT_LIMIT) { settle(taxi, BillType.REIMBURSEMENT, 1, b) }

        // What is left of the hotel is given up, which stops reimbursements until taken back.
        expect(ErrorCode.FORMAT) { commands.execute(WaiveReimbursement(id(), taxi, bill(taxi).revision, true)) }
        commands.execute(WaiveReimbursement(id(), hotel, bill(hotel).revision, true))
        assertEquals(ReimburseState.WAIVED, bill(hotel).reimburseState)
        expect(ErrorCode.FORMAT) { settle(hotel, BillType.REIMBURSEMENT, 1_000, b) }
        commands.execute(WaiveReimbursement(id(), hotel, bill(hotel).revision, false))
        assertEquals(ReimburseState.PARTIAL, bill(hotel).reimburseState)
        // Undoing the batch's hotel part puts it back to pending.
        val hotelBack = bills.observeChildren(hotel).first().single()
        commands.execute(DeleteBill(id(), hotelBack.id, hotelBack.revision))
        assertEquals(ReimburseState.PENDING, bill(hotel).reimburseState)
        assertEquals(500_000L + 5_000, balance(b))
    }

    @Test fun an_edit_keeps_what_came_back_and_takes_it_along() = runBlocking {
        accounts()
        val trip = expense(a, 10_000, reimbursable = true)
        settle(trip, BillType.REFUND, 2_000, a)
        settle(trip, BillType.REIMBURSEMENT, 3_000, a)
        val draft = bill(trip).toDraft()
        fun edit(change: BillDraft) = SaveBill(id(), trip, runBlocking { bill(trip).revision }, change)
        expect(ErrorCode.BILL_SETTLED) { commands.execute(edit(draft.copy(amountMinor = 4_999, accountAmountMinor = 4_999))) }
        expect(ErrorCode.BILL_SETTLED) { commands.execute(edit(draft.copy(reimbursable = false))) }
        expect(ErrorCode.BILL_SETTLED) { commands.execute(edit(draft.copy(type = BillType.INCOME, reimbursable = false,
            categoryId = category("income.other"), subCategoryId = null))) }
        // Down to exactly what came back is fine: nothing is left, so the reimbursement is done.
        commands.execute(edit(draft.copy(amountMinor = 5_000, accountAmountMinor = 5_000, subCategoryId = category("food.dinner"))))
        assertEquals(ReimburseState.DONE, bill(trip).reimburseState)
        assertEquals(1_000_000L - 5_000 + 5_000, balance(a))
        assertTrue(bills.observeChildren(trip).first().all { it.subCategoryId == category("food.dinner") })
    }

    @Test fun refunds_and_reimbursements_survive_a_backup_and_restore() = runBlocking {
        accounts()
        val trip = expense(a, 10_000, reimbursable = true, counterparty = "Synthetic Employer")
        settle(trip, BillType.REFUND, 1_000, b)
        val back = settle(trip, BillType.REIMBURSEMENT, 2_000, usd, received = 280, rate = BigDecimal("0.14"), source = BillRateSource.ONE_TIME)
        commands.execute(DeleteBill(id(), back, bill(back).revision))
        settle(trip, BillType.REIMBURSEMENT, 4_000, a)
        val build = AppBuildInfo("valnook", "0.0.19", 1903, "20261113.1", "20261113.1", DATABASE_SCHEMA_VERSION)
        val backup = ByteArrayOutputStream()
        RoomPortabilityEngine(context, db, clock, build).createBackup(id(), backup) {}
        val target = database()
        try {
            val restored = RoomPortabilityEngine(context, target, clock, build)
            val staged = restored.prepareRestore(ByteArrayInputStream(backup.toByteArray()), "settle.val_backup") {}
            try { restored.commitRestore(staged) {} } finally { restored.close(staged) }
            assertEquals(bills.observeBill(trip).first(), RoomBills(target).observeBill(trip).first())
            assertEquals(bills.observeChildren(trip).first(), RoomBills(target).observeChildren(trip).first())
            assertEquals(RoomOverview(db).snapshot(), RoomOverview(target).snapshot())
        } finally { target.close() }
    }

    @Test fun a_deleted_account_leaves_its_refunds_and_reimbursements_in_place() = runBlocking {
        accounts()
        val trip = expense(a, 10_000, reimbursable = true)
        val refund = settle(trip, BillType.REFUND, 1_000, usd, received = 140, rate = BigDecimal("0.14"), source = BillRateSource.ONE_TIME)
        val broker = db.cash().cashAccount(usd)!!.savings_account_id
        val preview = commands.previewAccountDeletion(broker, null)
        commands.execute(DeleteAccount(id(), broker, preview.revision, DeletionConfirmation(preview.ticket, preview.code)))
        val kept = bill(refund)
        assertEquals(listOf(null, true, 1_000L), listOf(kept.cashAccountId, kept.accountDeleted, kept.amountMinor))
        assertEquals(1_000L, bill(trip).refundedMinor)
    }
}

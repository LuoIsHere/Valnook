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

/** Bills move money through their own cash entries, survive account deletion, and travel through backups. */
@RunWith(AndroidJUnit4::class)
class BillDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-11-12T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
    private val now = clock.millis()
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private val names = mutableListOf<String>()
    private fun id() = UUID.randomUUID().toString()
    private fun database(): ValnookDatabase {
        val name = "bill-test-${id()}.db"; names += name
        return Room.databaseBuilder(context, ValnookDatabase::class.java, name).addCallback(ValnookDatabase.seed).build()
    }
    @Before fun prepare() { db = database(); commands = RoomFinancialCommands(db, clock) }
    @After fun close() { db.close(); names.forEach(context::deleteDatabase) }

    /** A synthetic bank with two CNY sub-accounts A and B, and a broker with a USD one. */
    private suspend fun accounts(): Triple<Long, Long, Long> {
        val bank = commands.execute(SaveAccount(id(), null, null, "Synthetic Bank", "", listOf(
            CashBalanceChange("CNY", 1_000_000, null, name = "A"), CashBalanceChange("CNY", 500_000, null, name = "B")))).id
        val broker = commands.execute(SaveAccount(id(), null, null, "Synthetic Broker", "", listOf(
            CashBalanceChange("USD", 100_000, null, name = "U")))).id
        val cash = RoomOverview(db).snapshot().cash
        return Triple(cash.single { it.account_id == bank && it.name == "A" }.id,
            cash.single { it.account_id == bank && it.name == "B" }.id, cash.single { it.account_id == broker }.id)
    }
    private suspend fun balance(cashId: Long) = db.cash().cashAccount(cashId)!!.balance_minor
    private fun category(key: String) = db.openHelper.readableDatabase
        .query("SELECT id FROM bill_categories WHERE builtin_key=?", arrayOf(key)).use { it.moveToFirst(); it.getLong(0) }
    private suspend fun legs(billId: Long) = db.bills().legs(billId).filter { !it.is_deleted }
        .map { Triple(it.source_kind, it.cash_account_id, it.delta_minor) }.toSet()
    private suspend fun expect(code: ErrorCode, block: suspend () -> Unit) {
        try { block(); fail("Expected $code") } catch (e: DomainException) { assertEquals(code, e.code) }
    }
    private suspend fun save(draft: BillDraft, billId: Long? = null, revision: Long? = null) =
        commands.execute(SaveBill(id(), billId, revision, draft)).id
    private fun expense(cash: Long, amount: Long, discount: Long = 0, key: String = "food", sub: String? = "food.lunch") =
        BillDraft(BillType.EXPENSE, now, amount, "CNY", discount, cash, amount - discount,
            categoryId = category(key), subCategoryId = sub?.let(::category), note = "Synthetic lunch")
    private fun transfer(from: Long?, to: Long?, amount: Long, kind: TransferKind, fee: Long = 0, counterparty: String = "") =
        BillDraft(BillType.TRANSFER, now, amount, "CNY", 0, from, amount, transferKind = kind, toCashAccountId = to,
            toAmountMinor = if (kind == TransferKind.TO_EXTERNAL) null else amount, feeMinor = fee, counterparty = counterparty)

    @Test fun every_preset_is_there_once_under_its_parent() = runBlocking {
        val categories = RoomBills(db).observeCategories().first()
        assertEquals(BillCategoryPresets.all.map { it.key }.toSet(), categories.mapNotNull { it.builtinKey }.toSet())
        val lunch = categories.single { it.builtinKey == "food.lunch" }
        assertEquals(categories.single { it.builtinKey == "food" }.id, lunch.parentId)
        assertEquals("午餐", lunch.name(english = false))
        assertEquals("Lunch", lunch.name(english = true))
    }

    @Test fun expenses_income_and_transfers_move_balances_through_their_own_entries() = runBlocking {
        val (a, b, usd) = accounts()
        val lunch = save(expense(a, 3_500, discount = 500))
        assertEquals(1_000_000L - 3_000, balance(a))
        assertEquals(setOf(Triple("BILL", a, -3_000L)), legs(lunch))

        save(BillDraft(BillType.INCOME, now, 10_000, "CNY", 0, b, 10_000, categoryId = category("income.pay"),
            subCategoryId = category("income.pay.salary")))
        assertEquals(510_000L, balance(b))

        // USD 20.00 paid from a CNY account at 7.1234 is CNY 142.47; another amount is refused.
        val abroad = BillDraft(BillType.EXPENSE, now, 2_000, "USD", 0, a, 14_247, BigDecimal("7.1234"), BillRateSource.ONE_TIME,
            categoryId = category("transport"))
        save(abroad)
        expect(ErrorCode.FORMAT) { save(abroad.copy(accountAmountMinor = 14_248)) }
        // The bank's own charge works out the rate instead: 145.00 / 20.00.
        save(abroad.copy(accountAmountMinor = 14_500, rate = BigDecimal("7.25"), rateSource = BillRateSource.DERIVED))
        assertEquals(1_000_000L - 3_000 - 14_247 - 14_500, balance(a))

        // A to B with a fee, B to an outside account, and money in from outside to the USD account.
        val internal = save(transfer(a, b, 10_000, TransferKind.INTERNAL, fee = 100))
        assertEquals(setOf(Triple("BILL", a, -10_100L), Triple("BILL_TRANSFER_IN", b, 10_000L)), legs(internal))
        save(transfer(b, null, 5_000, TransferKind.TO_EXTERNAL, counterparty = "Synthetic family"))
        save(BillDraft(BillType.TRANSFER, now, 1_000, "USD", 0, null, 1_000, transferKind = TransferKind.FROM_EXTERNAL,
            toCashAccountId = usd, toAmountMinor = 1_000))
        assertEquals(1_000_000L - 3_000 - 14_247 - 14_500 - 10_100, balance(a))
        assertEquals(510_000L + 10_000 - 5_000, balance(b))
        assertEquals(101_000L, balance(usd))

        // Across currencies the received amount follows the rate.
        val exchange = BillDraft(BillType.TRANSFER, now, 7_100, "CNY", 0, a, 7_100, BigDecimal("0.140845"),
            BillRateSource.MANUAL, TransferKind.INTERNAL, usd, 1_000)
        save(exchange)
        assertEquals(102_000L, balance(usd))

        expect(ErrorCode.WRONG_CASH_ACCOUNT) { save(transfer(a, a, 100, TransferKind.INTERNAL)) }
        expect(ErrorCode.FORMAT) { save(expense(a, 100).copy(categoryId = category("income.pay"), subCategoryId = null)) }
        expect(ErrorCode.FORMAT) { save(expense(a, 100, discount = 100)) }
        expect(ErrorCode.FORMAT) { save(expense(a, 100).copy(type = BillType.REFUND)) }
    }

    @Test fun edits_update_in_place_and_moving_a_side_replaces_its_entry() = runBlocking {
        val (a, b, _) = accounts()
        val lunch = save(expense(a, 2_000))
        val entryId = db.bills().legs(lunch).single().id
        save(expense(a, 2_600), lunch, 1)
        assertEquals(entryId, db.bills().legs(lunch).single().id)
        assertEquals(1_000_000L - 2_600, balance(a))
        expect(ErrorCode.STALE_RECORD) { save(expense(a, 1), lunch, 1) }

        save(expense(b, 2_600), lunch, 2)
        assertEquals(setOf(Triple("BILL", b, -2_600L)), legs(lunch))
        assertEquals(1_000_000L, balance(a))
        assertEquals(500_000L - 2_600, balance(b))

        // Swapping a transfer's two sides keeps one entry per side.
        val move = save(transfer(a, b, 1_000, TransferKind.INTERNAL))
        save(transfer(b, a, 1_000, TransferKind.INTERNAL), move, 1)
        assertEquals(setOf(Triple("BILL", b, -1_000L), Triple("BILL_TRANSFER_IN", a, 1_000L)), legs(move))
        assertEquals(1_001_000L, balance(a))
        assertEquals(500_000L - 2_600 - 1_000, balance(b))

        commands.execute(DeleteBill(id(), move, 2))
        commands.execute(DeleteBill(id(), lunch, 3))
        assertEquals(1_000_000L, balance(a))
        assertEquals(500_000L, balance(b))
        assertTrue(RoomBills(db).observeMonth(YearMonth.of(2026, 11), clock.zone).first().isEmpty())
        // The same request sent again is answered from its receipt, not applied twice.
        val again = SaveBill(id(), null, null, expense(a, 500))
        assertEquals(commands.execute(again), commands.execute(again))
        assertEquals(1_000_000L - 500, balance(a))
    }

    @Test fun categories_can_be_added_renamed_hidden_ordered_and_deleted_into_another() = runBlocking {
        val (a, _, _) = accounts()
        val pets = commands.execute(SaveBillCategory(id(), null, null, BillCategoryKind.EXPENSE, null, "Synthetic pets", "paw", "brown")).id
        val food = commands.execute(SaveBillCategory(id(), null, null, BillCategoryKind.EXPENSE, pets, "Pet food", "bowl-food", "brown")).id
        save(expense(a, 300, key = "food", sub = null).copy(categoryId = pets, subCategoryId = food))
        // A preset can be renamed and put back to the name that follows the language, but not deleted.
        val preset = db.bills().category(category("food"))!!
        commands.execute(SaveBillCategory(id(), preset.id, 1, BillCategoryKind.EXPENSE, null, "Meals", "utensils", "orange"))
        assertEquals("Meals", db.bills().category(preset.id)!!.custom_name)
        commands.execute(SaveBillCategory(id(), preset.id, 2, BillCategoryKind.EXPENSE, null, null, "utensils", "orange"))
        assertNull(db.bills().category(preset.id)!!.custom_name)
        expect(ErrorCode.SOURCE_RECORD) { commands.execute(DeleteBillCategory(id(), preset.id, 3, null)) }
        commands.execute(SetBillCategoryHidden(id(), preset.id, 3, true))
        assertTrue(db.bills().category(preset.id)!!.hidden)

        val tops = db.bills().siblings("EXPENSE", null).map { it.id }
        commands.execute(ReorderBillCategories(id(), BillCategoryKind.EXPENSE, null, tops.reversed()))
        assertEquals(tops.reversed(), db.bills().siblings("EXPENSE", null).map { it.id })

        // A used category needs somewhere for its bills to go; its subcategories go with it.
        val petsRevision = db.bills().category(pets)!!.revision
        expect(ErrorCode.FORMAT) { commands.execute(DeleteBillCategory(id(), pets, petsRevision, null)) }
        commands.execute(DeleteBillCategory(id(), pets, petsRevision, category("other.uncategorized")))
        assertNull(db.bills().category(food))
        val bill = RoomBills(db).observeMonth(YearMonth.of(2026, 11), clock.zone).first().single()
        assertEquals(category("other") to category("other.uncategorized"), bill.categoryId to bill.subCategoryId)
    }

    @Test fun deleted_accounts_leave_their_bills_and_turn_transfers_outward() = runBlocking {
        val (a, b, _) = accounts()
        val bank = db.cash().cashAccount(a)!!.savings_account_id
        val lunch = save(expense(a, 1_000))
        val moved = save(transfer(a, b, 2_000, TransferKind.INTERNAL, fee = 10))
        val gift = save(transfer(a, null, 300, TransferKind.TO_EXTERNAL, counterparty = "Synthetic friend"))
        commands.execute(SaveBillFavorites(id(), listOf(b, a)))
        assertEquals(listOf(b, a), RoomBills(db).observeAccountChoices().first().filter { it.favorite }
            .sortedBy { it.favoriteOrder }.map { it.cashAccountId })

        val preview = commands.previewAccountDeletion(bank, a)
        assertEquals(2, preview.billCount)
        commands.execute(DeleteBalanceAccount(id(), bank, a, preview.revision, DeletionConfirmation(preview.ticket, preview.code)))

        val bills = RoomBills(db)
        assertNull(bills.observeBill(lunch).first()!!.cashAccountId)
        val inward = bills.observeBill(moved).first()!!
        assertEquals(TransferKind.FROM_EXTERNAL, inward.transferKind)
        assertEquals("Synthetic Bank · A", inward.counterparty)
        assertEquals(listOf(null, b, 2_000L, 0L), listOf(inward.cashAccountId, inward.toCashAccountId, inward.amountMinor, inward.feeMinor))
        assertNull(bills.observeBill(gift).first())
        assertEquals(500_000L + 2_000, balance(b))
        assertEquals(setOf(Triple("BILL_TRANSFER_IN", b, 2_000L)), legs(moved))
        // The kept transfer can still be edited and deleted.
        commands.execute(DeleteBill(id(), moved, inward.revision))
        assertEquals(500_000L, balance(b))
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
    }

    @Test fun credit_sub_accounts_take_bills_and_show_what_is_left_to_spend() = runBlocking {
        val card = commands.execute(SaveAccount(id(), null, null, "Synthetic Card", "", listOf(CashBalanceChange("CNY", 0, null,
            name = "Card", type = BalanceAccountType.CREDIT, credit = CreditAccountInput(1_000_000, 5,
                CreditDueRule.AfterStatementDays(20), null))))).id
        val cash = RoomOverview(db).snapshot().cash.single { it.account_id == card }.id
        save(expense(cash, 25_000))
        val choice = RoomBills(db).observeAccountChoices().first().single { it.cashAccountId == cash }
        assertTrue(choice.isCredit)
        assertEquals(-25_000L, choice.balanceMinor)
        assertEquals(975_000L, choice.availableCreditMinor)
    }

    @Test fun bills_and_favourites_survive_a_backup_and_restore() = runBlocking {
        val (a, b, usd) = accounts()
        val pets = commands.execute(SaveBillCategory(id(), null, null, BillCategoryKind.EXPENSE, null, "Synthetic pets", "paw", "brown")).id
        save(expense(a, 1_234, discount = 34).copy(categoryId = pets, subCategoryId = null))
        save(transfer(a, b, 500, TransferKind.INTERNAL, fee = 5))
        save(BillDraft(BillType.TRANSFER, now, 7_100, "CNY", 0, a, 7_100, BigDecimal("0.140845"), BillRateSource.MANUAL,
            TransferKind.INTERNAL, usd, 1_000))
        commands.execute(SaveBillFavorites(id(), listOf(usd)))
        val build = AppBuildInfo("valnook", "0.0.19", 1901, "20261112.1", "20261112.1", DATABASE_SCHEMA_VERSION)
        val backup = ByteArrayOutputStream()
        RoomPortabilityEngine(context, db, clock, build).createBackup(id(), backup) {}
        val target = database()
        try {
            val restored = RoomPortabilityEngine(context, target, clock, build)
            val staged = restored.prepareRestore(ByteArrayInputStream(backup.toByteArray()), "bills.val_backup") {}
            try { restored.commitRestore(staged) {} } finally { restored.close(staged) }
            val month = YearMonth.of(2026, 11)
            assertEquals(RoomBills(db).observeMonth(month, clock.zone).first(), RoomBills(target).observeMonth(month, clock.zone).first())
            assertEquals(RoomBills(db).observeCategories().first(), RoomBills(target).observeCategories().first())
            assertEquals(RoomBills(db).observeAccountChoices().first(), RoomBills(target).observeAccountChoices().first())
            assertEquals(RoomOverview(db).snapshot(), RoomOverview(target).snapshot())
        } finally { target.close() }
    }
}

package dev.valnook.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class CreditAccountDatabaseTest {
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands

    @Before fun setup() {
        db = ValnookDatabase.inMemory(ApplicationProvider.getApplicationContext())
        commands = RoomFinancialCommands(db, Clock.fixed(Instant.parse("2026-10-05T08:00:00Z"),
            ZoneId.of("Asia/Hong_Kong")))
    }

    @After fun close() = db.close()

    private suspend fun createCredit(accountId: Long, name: String, currency: String = "CNY",
        limit: Long? = 2_000_000L, source: Long? = null, balance: Long = 0): Long {
        val owner = requireNotNull(db.accounts().account(accountId))
        commands.execute(SaveAccount(testOperationId(), accountId, owner.revision, owner.name, owner.note,
            listOf(BalanceAccountChange(currency, balance, null, name = name, type = BalanceAccountType.CREDIT,
                credit = CreditAccountInput(limit, 12, CreditDueRule.AfterStatementDays(20), source)))))
        return db.cash().cash(accountId).first().single { it.account.name == name }.account.id
    }

    @Test fun sharedLimitRequiresSameParentAndRejectsChainCurrencyAndRootReparenting() = runBlocking {
        val first = commands.testAccount("A")
        val second = commands.testAccount("B")
        val root = createCredit(first, "Root")
        val otherRoot = createCredit(first, "Other root")
        val crossParent = assertThrows(DomainException::class.java) {
            runBlocking { createCredit(second, "Cross-parent child", source = root, limit = null) }
        }
        assertEquals(ErrorCode.CREDIT_SOURCE_PARENT, crossParent.code)

        val child = createCredit(first, "Child", source = root, limit = null)
        assertEquals(root, db.credit().profile(child)?.limit_source_account_id)

        val childAsSource = assertThrows(DomainException::class.java) {
            runBlocking { createCredit(first, "Grandchild", source = child, limit = null) }
        }
        assertEquals(ErrorCode.CREDIT_SOURCE_CHAIN, childAsSource.code)
        val crossCurrency = assertThrows(DomainException::class.java) {
            runBlocking { createCredit(first, "USD child", "USD", source = root, limit = null) }
        }
        assertEquals(ErrorCode.CREDIT_SOURCE_CURRENCY, crossCurrency.code)

        val rootRow = requireNotNull(db.cash().cashAccount(root))
        val owner = requireNotNull(db.accounts().account(first))
        val inUse = assertThrows(DomainException::class.java) { runBlocking {
            commands.execute(SaveAccount(testOperationId(), first, owner.revision, owner.name, owner.note,
                listOf(BalanceAccountChange("CNY", rootRow.balance_minor, rootRow.revision, root, rootRow.name,
                    rootRow.note, BalanceAccountType.CREDIT,
                    CreditAccountInput(null, 12, CreditDueRule.AfterStatementDays(20), otherRoot)))))
        } }
        assertEquals(ErrorCode.CREDIT_LIMIT_IN_USE, inUse.code)
    }

    @Test fun creditAccountCannotBeUsedAsDepositCashLink() = runBlocking {
        val account = commands.testAccount("A")
        val credit = createCredit(account, "Visa")
        val error = assertThrows(DomainException::class.java) { runBlocking {
            commands.execute(OpenTermDeposit(testOperationId(), account, "CNY", 100_000,
                300_000_000, 20_000, 20_365, true, credit))
        } }
        assertEquals(ErrorCode.WRONG_CASH_ACCOUNT, error.code)
    }

    @Test fun creditAccountCannotBeUsedAsInvestmentTradeCashLink() = runBlocking {
        val account = commands.testAccount("A")
        val credit = createCredit(account, "Visa")
        val type = commands.testType("ETF")
        val position = commands.testInvestment(account, "沪深300ETF", "510300", type,
            "CNY", 0, 4_0000_0000L, null)

        val error = assertThrows(DomainException::class.java) { runBlocking {
            commands.execute(RecordInvestmentTrade(testOperationId(), position, Direction.BUY,
                100_000_000L, 4_0000_0000L, 1, true, credit))
        } }

        assertEquals(ErrorCode.WRONG_CASH_ACCOUNT, error.code)
        assertTrue(db.trades().replayTrades(position).isEmpty())
    }

    @Test fun independentCreditCanLowerLimitBelowDebtAndBecomesOverLimit() = runBlocking {
        val account = commands.testAccount("A")
        val credit = createCredit(account, "Visa", limit = 2_000_000L, balance = -1_500_000L)
        val row = requireNotNull(db.cash().cashAccount(credit))
        val owner = requireNotNull(db.accounts().account(account))

        commands.execute(SaveAccount(testOperationId(), account, owner.revision, owner.name, owner.note,
            listOf(BalanceAccountChange("CNY", row.balance_minor, row.revision, credit, row.name,
                row.note, BalanceAccountType.CREDIT,
                CreditAccountInput(1_000_000L, 12, CreditDueRule.AfterStatementDays(20), null)))))

        val summary = dev.valnook.domain.calculation.CreditLimitCalculator.calculate(
            credit,
            dev.valnook.data.repository.RoomOverview(db).snapshot().cash
        )
        assertEquals(java.math.BigDecimal("500000"), summary.overLimitMinor)
        assertEquals(-1_500_000L, requireNotNull(db.cash().cashAccount(credit)).balance_minor)
    }

    @Test fun profileOnlyEditBumpsUnifiedBalanceAccountRevision() = runBlocking {
        val account = commands.testAccount("A")
        val credit = createCredit(account, "Visa")
        val before = requireNotNull(db.cash().cashAccount(credit))
        val owner = requireNotNull(db.accounts().account(account))
        val editOperation = testOperationId()
        commands.execute(SaveAccount(editOperation, account, owner.revision, owner.name, owner.note,
            listOf(BalanceAccountChange("CNY", before.balance_minor, before.revision, credit, before.name,
                before.note, BalanceAccountType.CREDIT,
                CreditAccountInput(1_000_000, 18, CreditDueRule.FixedDayOfMonth(5), null)))))
        val after = requireNotNull(db.cash().cashAccount(credit))
        assertEquals(before.revision + 1, after.revision)
        assertEquals(18, db.credit().profile(credit)?.statement_day)
        db.openHelper.readableDatabase.query(
            "SELECT before_json,after_json FROM audit_events WHERE operation_id=?",
            arrayOf(editOperation)
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.getString(0).contains("statement_day"))
            assertTrue(cursor.getString(1).contains("\"statement_day\":\"18\""))
        }
    }
}

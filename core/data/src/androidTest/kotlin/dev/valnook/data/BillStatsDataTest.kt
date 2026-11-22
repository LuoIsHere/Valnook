package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.valnook.data.database.*
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.*
import dev.valnook.domain.fx.FxContext
import dev.valnook.domain.model.*
import dev.valnook.domain.repository.*
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.math.BigDecimal
import java.math.MathContext
import java.time.*
import java.util.UUID

/** Bills are counted at the rates stored for their own days, and refunds come off the month the expense was in. */
@RunWith(AndroidJUnit4::class)
class BillStatsDataTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val zone = ZoneId.of("Asia/Shanghai")
    private val clock = Clock.fixed(Instant.parse("2026-11-12T04:00:00Z"), zone)
    private lateinit var db: ValnookDatabase
    private lateinit var commands: RoomFinancialCommands
    private val bills get() = RoomBills(db)
    private fun id() = UUID.randomUUID().toString()
    private fun day(d: Int) = LocalDate.of(2026, 10, d)
    private fun inv(raw: String) = BigDecimal.ONE.divide(BigDecimal(raw), MathContext.DECIMAL128)

    @Before fun prepare() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(context, ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
        db.overview().insertSettings(SettingsEntity(base_currency = "CNY", revision = 1))
        commands = RoomFinancialCommands(db, clock)
    }

    @After fun close() = db.close()

    private suspend fun store(vararg rows: Triple<String, Int, String>, base: String = "CNY") =
        db.fx().insertHistory(rows.map { (quote, d, raw) -> FxRateHistoryEntity(base, quote, day(d).toEpochDay(), day(d).toEpochDay(), raw, 1) })

    @Test fun stored_rates_are_read_by_day_for_the_currencies_asked() = runBlocking {
        store(Triple("USD", 1, "0.14"), Triple("USD", 2, "0.15"), Triple("USD", 9, "0.16"), Triple("EUR", 1, "0.125"))
        val rates = bills.observeStoredRates(setOf("USD", "CNY"), day(1), day(3)).first()
        // Inverted to what an amount is multiplied by; only the days asked, only the currencies asked, base left out.
        assertEquals(setOf("USD"), rates.keys)
        assertEquals(mapOf(day(1) to inv("0.14"), day(2) to inv("0.15")), rates.getValue("USD"))
    }

    @Test fun stored_rates_follow_new_days_and_the_base_currency() = runBlocking {
        store(Triple("USD", 1, "0.14"))
        val flow = bills.observeStoredRates(setOf("USD", "CNY"), day(1), day(3))
        // The first reading is seen before the second rate is stored, so the update really comes from invalidation.
        val sizes = kotlinx.coroutines.channels.Channel<Int?>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        val collecting = launch { flow.collect { sizes.send(it["USD"]?.size) } }
        assertEquals(1, withTimeout(5_000) { sizes.receive() })
        store(Triple("USD", 2, "0.15"))
        withTimeout(5_000) { while (sizes.receive() != 2) Unit }
        collecting.cancel()
        // Under USD as the base, the rates stored for that base are the ones read, now for CNY.
        store(Triple("CNY", 1, "7.2"), base = "USD")
        RoomSettings(db, clock).applyChange(SaveFinancialSettings(1, Currency.of("USD"), emptyList()))
        val underUsd = withTimeout(5_000) { flow.first { "CNY" in it } }
        assertEquals(mapOf("CNY" to mapOf(day(1) to inv("7.2"))), underUsd)
    }

    @Test fun a_refund_next_month_comes_off_the_expense_in_its_own_month() = runBlocking {
        val bank = commands.execute(SaveAccount(id(), null, null, "Synthetic Bank", "", listOf(
            CashBalanceChange("CNY", 1_000_000, null, name = "A")))).id
        val cash = RoomOverview(db).snapshot().cash.single { it.account_id == bank }.id
        val food = db.openHelper.readableDatabase
            .query("SELECT id FROM bill_categories WHERE builtin_key='food'").use { it.moveToFirst(); it.getLong(0) }
        val spentAt = LocalDate.of(2026, 10, 30).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        val dinner = commands.execute(SaveBill(id(), null, null, BillDraft(BillType.EXPENSE, spentAt, 10_000, "CNY", 0,
            cash, 10_000, categoryId = food, note = "Synthetic dinner"))).id
        val parent = bills.observeBill(dinner).first()!!
        val backAt = LocalDate.of(2026, 11, 2).atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
        commands.execute(SettleBill(id(), dinner, parent.revision, BillType.REFUND, SettlementDraft(backAt, 4_000, cash, 4_000)))

        val counter = BillCounter(bills, flowOf(AppSettings(Currency.of("CNY"))), flowOf(FxContext()), zone)
        val october = bills.observeRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 11, 1), zone).first()
        val november = bills.observeRange(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 1), zone).first()
        assertEquals(listOf(BillType.EXPENSE), october.map { it.type })
        assertEquals(listOf(BillType.REFUND), november.map { it.type })
        // October spent 60 after the 40 that came back in November; November spent nothing and earned nothing.
        assertEquals(BigDecimal("60.00"), counter.observe(october).first().totals().expense)
        val inNovember = counter.observe(november).first().totals()
        assertEquals(BigDecimal("0.00"), inNovember.expense)
        assertEquals(BigDecimal("0.00"), inNovember.income)
    }
}

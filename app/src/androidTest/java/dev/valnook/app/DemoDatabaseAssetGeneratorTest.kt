package dev.valnook.app

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.app.di.AppGraph
import dev.valnook.app.di.DemoDataSeeder
import dev.valnook.app.di.createDatabaseGraph
import dev.valnook.app.di.currentBuildInfo
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.AppSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.YearMonth
import java.math.BigDecimal
import dev.valnook.data.database.InstrumentPriceEntity

@RunWith(AndroidJUnit4::class)
class DemoDatabaseAssetGeneratorTest {
    @Test
    fun exportRoomV12DemoDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(EXPORT_DATABASE_NAME)
        val clock = Clock.fixed(Instant.parse("2026-10-02T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"))
        val database = Room.databaseBuilder(context, ValnookDatabase::class.java, EXPORT_DATABASE_NAME)
            .addCallback(ValnookDatabase.seed)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            val raw = createDatabaseGraph(context,database,clock,currentBuildInfo(), persistentPrivateCards = false)
            val graph = AppGraph("demo-asset-generator",raw.accounts,raw.cash,raw.deposits,
                raw.investments,raw.commands,raw.clock,raw.overview,
                dev.valnook.data.repository.RoomStatistics(database, clock),raw.settings,raw.settingsWriter,
                raw.instruments,raw.cashPages,raw.depositPages)
            DemoDataSeeder(graph, clock).seed(AppSettings())
            enrichHistoricalSources(database, clock)
            seedWallet(database, clock)
            val snapshot = graph.overview.snapshot()
            assertEquals(12, snapshot.accounts.size)
            assertEquals(37, snapshot.cash.size)
            assertEquals(10, snapshot.cash.count { it.creditProfile != null })
            assertEquals(60, snapshot.instruments.size)
            assertEquals(26, snapshot.positions.size)
            val totalAssets = AssetValuation.calculate(snapshot).total.amount
            assertTrue(totalAssets >= BigDecimal("100000"))
            assertTrue(totalAssets < BigDecimal("200000"))
            assertLifecycleFixtures(database)
        } finally {
            database.close()
        }

        val source = context.getDatabasePath(EXPORT_DATABASE_NAME)
        PlatformTestStorageRegistry.getInstance().openOutputFile(ASSET_FILE_NAME).use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        assertTrue(source.length() > 0)
    }

    private suspend fun seedWallet(database: ValnookDatabase, clock: Clock) {
        val repository=dev.valnook.data.repository.RoomWallet(database,clock)
        val cash=dev.valnook.data.repository.RoomOverview(database).snapshot().cash
        val commands=dev.valnook.data.transaction.RoomFinancialCommands(database,clock)
        cash.filter { it.creditProfile==null }.take(1).forEach { account ->
            commands.execute(dev.valnook.domain.repository.SetCashBalance(java.util.UUID.randomUUID().toString(),account.account_id,
                account.currency.code,account.balance_minor+13500,account.revision,account.id,account.name,account.note))
            commands.execute(dev.valnook.domain.repository.SetCashBalance(java.util.UUID.randomUUID().toString(),account.account_id,
                account.currency.code,account.balance_minor+11100,account.revision+1,account.id,account.name,account.note))
        }
        val savings=cash.first{it.creditProfile==null}.id
        val credit=cash.first{it.creditProfile!=null}.id
        val bindings=List<Long?>(15){index->when(index){0,4->savings;1,7->credit;2,8,14->null;else->cash[(index*3)%cash.size].id}}
        val colors=listOf(0xff687983,0xff888078,0xff363a42,0xff8b929a,0xff778681,
            0xff817788,0xff9a897b,0xff506474,0xff8c9090,0xff736e68,
            0xff697877,0xff7c7d8e,0xffa09284,0xff57606a,0xff727579).map{it.toInt()}
        val names=listOf("晨雾 · Mist","砂岩 · Sandstone","夜幕 · Dusk","雾银 · Silver","苔影 · Moss",
            "暮紫 · Mauve","暖沙 · Sand","深湾 · Bay","月岩 · Moonstone","烟棕 · Umber",
            "静湖 · Lake","蓝灰 · Slate","晨光 · Dawn","石墨 · Graphite","薄雾 · Haze")
        colors.forEachIndexed { index,color ->
            val bitmap=android.graphics.Bitmap.createBitmap(1268,800,android.graphics.Bitmap.Config.ARGB_8888)
            val canvas=android.graphics.Canvas(bitmap)
            val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
            paint.shader=android.graphics.LinearGradient(0f,0f,1268f,800f,intArrayOf(color,android.graphics.Color.rgb(25+index*8,29+index*8,35+index*8)),null,android.graphics.Shader.TileMode.CLAMP)
            canvas.drawRect(0f,0f,1268f,800f,paint)
            paint.shader=android.graphics.RadialGradient(250f,80f,850f,intArrayOf(0x55ffffff,0x00ffffff),null,android.graphics.Shader.TileMode.CLAMP)
            canvas.drawCircle(250f,80f,850f,paint)
            paint.shader=null;paint.style=android.graphics.Paint.Style.STROKE;paint.strokeWidth=2f;paint.color=0x18ffffff
            for(r in 0..8)canvas.drawCircle(1120f,760f,280f+r*45f,paint)
            val image=dev.valnook.data.image.WalletImages.encode(bitmap,1f,0f,0f);bitmap.recycle()
            repository.save(null,null,names[index],bindings[index],image.key,image)
        }
    }

    private suspend fun enrichHistoricalSources(database: ValnookDatabase, clock: Clock) {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val baseline = LocalDate.of(today.year - 10, 1, 1)
        val baselineMs = baseline.atStartOfDay(zone).toInstant().toEpochMilli()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("UPDATE cash_accounts SET created_at_ms=?,updated_at_ms=MAX(updated_at_ms,?)",
            arrayOf(baselineMs, baselineMs))
        sql.execSQL("""UPDATE cash_entries SET occurred_at_ms=? + (id % 55) * 86400000
            WHERE source_kind='CASH_SET' AND note NOT LIKE '长期账本%'""", arrayOf(baselineMs))
        sql.execSQL("""UPDATE term_deposits SET created_at_ms=start_epoch_day*86400000,
            closed_at_ms=CASE WHEN status='CLOSED' THEN end_epoch_day*86400000+43200000 ELSE NULL END""")
        sql.execSQL("""UPDATE cash_entries SET occurred_at_ms=(SELECT d.closed_at_ms FROM term_deposits d
            WHERE d.id=cash_entries.source_id) WHERE source_kind='TERM_CLOSE'""")
        sql.execSQL("""UPDATE cash_accounts SET created_at_ms=COALESCE((SELECT MIN(e.occurred_at_ms)
            FROM cash_entries e WHERE e.cash_account_id=cash_accounts.id AND e.is_deleted=0),created_at_ms)""")
        sql.execSQL("""UPDATE savings_accounts SET created_at_ms=MIN(created_at_ms,COALESCE(
            (SELECT MIN(c.created_at_ms) FROM cash_accounts c WHERE c.savings_account_id=savings_accounts.id),
            created_at_ms))""")
        database.statistics().clearCache()
        database.statistics().clearBaselineItems()
        sql.execSQL("UPDATE statistics_state SET source_revision=source_revision+1,baseline_at_ms=?,earliest_invalidated_epoch_day=? WHERE id=1",
            arrayOf(baselineMs, baseline.toEpochDay()))
        insertDemoLabels(database)

        val instruments = database.overview().allInstruments().map { it.instrument }
        instruments.forEachIndexed { index, instrument ->
            var month = YearMonth.from(baseline)
            val finalMonth = YearMonth.from(today).minusMonths(1)
            var monthIndex = 0
            while (month <= finalMonth) {
                val factorBasisPoints = 8200 + ((index * 131 + monthIndex * 73) % 3300)
                val price = instrument.current_price_e5 * factorBasisPoints / 10_000L
                val effective = month.atEndOfMonth().atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
                database.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrument.id,
                    price_e5 = price.coerceAtLeast(1), currency_code = instrument.currency_code,
                    effective_at_ms = effective, created_at_ms = clock.millis()))
                month = month.plusMonths(1)
                monthIndex++
            }
            (1..today.dayOfMonth).forEach { day ->
                val factorBasisPoints = 9700 + ((index * 19 + day * 41) % 650)
                val price = instrument.current_price_e5 * factorBasisPoints / 10_000L
                val effective = today.withDayOfMonth(day).atTime(16, 0).atZone(zone).toInstant().toEpochMilli()
                database.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrument.id,
                    price_e5 = if (day == today.dayOfMonth) instrument.current_price_e5 else price.coerceAtLeast(1),
                    currency_code = instrument.currency_code,
                    effective_at_ms = effective.coerceAtMost(clock.millis()), created_at_ms = clock.millis()))
            }
        }
    }

    private suspend fun insertDemoLabels(database: ValnookDatabase) {
        val sql = database.openHelper.writableDatabase
        fun label(kind: String, id: Long, field: String, zh: String, en: String) {
            sql.execSQL("INSERT OR REPLACE INTO demo_labels(entity_kind,entity_id,field_name,zh_hans,english) VALUES (?,?,?,?,?)",
                arrayOf<Any>(kind, id, field, zh, en))
        }
        val accountEnglish = listOf("China Merchants Bank", "Industrial and Commercial Bank of China",
            "Bank of China", "China Construction Bank", "Bank of Communications", "HSBC",
            "CITIC Securities", "Huatai Securities", "GF Securities", "CICC Wealth",
            "Futu Securities", "Interactive Brokers")
        val noteEnglish = listOf("Bank account · salary and daily spending", "Bank account · household spending",
            "Bank account · cross-border funds", "Bank account · household reserve",
            "Bank account · transport and travel", "Bank account · foreign currency",
            "Brokerage account · China A-shares and long-term holdings", "Brokerage account · balanced portfolio",
            "Brokerage account · value investing", "Brokerage account · multi-market portfolio",
            "Brokerage account · Hong Kong and US trading", "Brokerage account · overseas long-term holdings")
        database.overview().allAccounts().forEachIndexed { index, account ->
            label("ACCOUNT", account.id, "NAME", account.name, accountEnglish[index])
            label("ACCOUNT", account.id, "NOTE", account.note, noteEnglish[index])
        }
        database.overview().allCash().forEach { cash ->
            val english = when (cash.name) {
                "招商银行活期" -> "CMB current account"
                "工商银行活期" -> "ICBC current account"
                "中国银行活期" -> "BOC current account"
                "建设银行活期" -> "CCB current account"
                "交通银行活期" -> "BCM current account"
                "人民币长期资金" -> "CNY long-term funds"
                "美元储蓄" -> "USD savings"
                "港币储蓄" -> "HKD savings"
                "美元往来账户" -> "USD current account"
                "港币往来账户" -> "HKD current account"
                "人民币交易资金" -> "CNY trading cash"
                "美元交易资金" -> "USD trading cash"
                "港币交易资金" -> "HKD trading cash"
                "招商银行 Visa" -> "CMB Visa"
                "招商银行 Mastercard" -> "CMB Mastercard"
                "工商银行共享额度主卡" -> "ICBC shared-limit primary"
                "工商银行附属卡 · 家庭" -> "ICBC supplementary · household"
                "工商银行附属卡 · 出行" -> "ICBC supplementary · travel"
                "工商银行附属卡 · 网购" -> "ICBC supplementary · online shopping"
                "建设银行 Visa" -> "CCB Visa"
                "交通银行银联信用卡" -> "BCM UnionPay credit card"
                "汇丰银行 Mastercard" -> "HSBC Mastercard"
                "中国银行信用卡" -> "BOC credit card"
                else -> cash.currency_code
            }
            label("CASH", cash.id, "NAME", cash.name, english)
        }
        val cursor = sql.query("SELECT id,name FROM asset_types ORDER BY id")
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val name = it.getString(1)
                val english = when (name) { "A股股票" -> "China A-shares"; "港股股票" -> "Hong Kong stocks"; else -> "US stocks" }
                label("TYPE", id, "NAME", name, english)
            }
        }
        database.overview().allInstruments().forEach { row ->
            val instrument = row.instrument
            val english = if (instrument.currency_code == "USD") instrument.name else
                "${instrument.symbol} ${when (instrument.currency_code) { "CNY" -> "A-share"; else -> "HK stock" }}"
            label("INSTRUMENT", instrument.id, "NAME", instrument.name, english)
        }
    }

    private fun assertLifecycleFixtures(database: ValnookDatabase) {
        val sql = database.openHelper.readableDatabase
        sql.query("""SELECT COUNT(*) FROM credit_account_profiles p
            JOIN cash_accounts child ON child.id=p.account_id
            JOIN cash_accounts source ON source.id=p.limit_source_account_id
            WHERE child.savings_account_id<>source.savings_account_id""").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        sql.query("SELECT COUNT(*) FROM savings_accounts WHERE note LIKE '银行账户 · %'").use {
            assertTrue(it.moveToFirst())
            assertEquals(6, it.getInt(0))
        }
        sql.query("SELECT COUNT(*) FROM savings_accounts WHERE note LIKE '证券账户 · %'").use {
            assertTrue(it.moveToFirst())
            assertEquals(6, it.getInt(0))
        }
        sql.query("""SELECT COUNT(*) FROM term_deposits d JOIN savings_accounts a
            ON a.id=d.savings_account_id WHERE a.note LIKE '证券账户 · %'""").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        sql.query("""SELECT COUNT(*) FROM credit_account_profiles p JOIN cash_accounts c ON c.id=p.account_id
            JOIN savings_accounts a ON a.id=c.savings_account_id WHERE a.note LIKE '证券账户 · %'""").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        sql.query("""SELECT COUNT(*) FROM investments p JOIN savings_accounts a ON a.id=p.savings_account_id
            WHERE a.note NOT LIKE '证券账户 · %'""").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
        sql.query("""SELECT
            SUM(CASE WHEN cash_linked=1 AND direction='BUY' THEN 1 ELSE 0 END),
            SUM(CASE WHEN cash_linked=1 AND direction='SELL' THEN 1 ELSE 0 END),
            SUM(CASE WHEN cash_linked=0 THEN 1 ELSE 0 END)
            FROM investment_trades""").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getInt(0) > 0)
            assertTrue(it.getInt(1) > 0)
            assertTrue(it.getInt(2) > 0)
        }
        sql.query("SELECT COUNT(*),MAX(revision) FROM cash_entries WHERE note LIKE '长期账本%'").use {
            assertTrue(it.moveToFirst())
            assertEquals(6, it.getInt(0))
            assertTrue(it.getLong(1) >= 4)
        }
        sql.query("SELECT COUNT(*),MAX(revision) FROM term_deposits").use {
            assertTrue(it.moveToFirst())
            assertEquals(26, it.getInt(0))
            assertTrue(it.getLong(1) >= 6)
        }
        sql.query("""SELECT p.position_state,p.holding_quantity_e8,MAX(t.revision)
            FROM investments p JOIN instruments i ON i.id=p.instrument_id
            JOIN savings_accounts a ON a.id=p.savings_account_id
            JOIN investment_trades t ON t.investment_id=p.id
            WHERE i.symbol='AAPL' AND a.name='中信证券' GROUP BY p.id""").use {
            assertTrue(it.moveToFirst())
            assertEquals("HOLDING", it.getString(0))
            assertTrue(it.getLong(1) > 0)
            assertTrue(it.getLong(2) >= 4)
        }
        sql.query("""SELECT p.position_state,p.holding_quantity_e8,MAX(t.revision)
            FROM investments p JOIN instruments i ON i.id=p.instrument_id
            JOIN savings_accounts a ON a.id=p.savings_account_id
            JOIN investment_trades t ON t.investment_id=p.id
            WHERE i.symbol='META' AND a.name='中信证券' GROUP BY p.id""").use {
            assertTrue(it.moveToFirst())
            assertEquals("CLOSED", it.getString(0))
            assertEquals(0L, it.getLong(1))
            assertTrue(it.getLong(2) >= 3)
        }
        sql.query("SELECT MIN(occurred_at_ms),MAX(occurred_at_ms) FROM cash_entries WHERE is_deleted=0").use {
            assertTrue(it.moveToFirst())
            val span = it.getLong(1) - it.getLong(0)
            assertTrue(span >= 9L * 365L * 86_400_000L)
        }
    }

    private companion object {
        const val EXPORT_DATABASE_NAME = "valnook-demo-asset-export.db"
        const val ASSET_FILE_NAME = "valnook-demo-v12.db"
    }
}

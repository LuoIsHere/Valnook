package dev.valnook.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.app.di.AppGraph
import dev.valnook.app.di.DemoDataSeeder
import dev.valnook.app.di.createDatabaseGraph
import dev.valnook.app.di.currentBuildInfo
import dev.valnook.data.database.InstrumentPriceEntity
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.calculation.AssetValuation
import dev.valnook.domain.model.AccountGroup
import dev.valnook.domain.model.AppSettings
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.model.WalletFaceBackground
import dev.valnook.domain.model.WalletFaceGeometry
import dev.valnook.domain.model.WalletFaceStyle
import dev.valnook.domain.model.WalletImage
import dev.valnook.domain.model.WalletImageFormat
import dev.valnook.domain.repository.CashBalanceChange
import dev.valnook.domain.repository.DeleteBalanceAccount
import dev.valnook.domain.repository.DeletionConfirmation
import dev.valnook.domain.repository.SaveAccount
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID

/**
 * Builds the bundled demo database `valnook-demo-v21.db` from [DemoDataSeeder], adds the wallet, the price history
 * and the English labels, checks that the ledger reads like a real one, and writes the file to test storage.
 */
@RunWith(AndroidJUnit4::class)
class DemoDatabaseAssetGeneratorTest {
    @Test
    fun exportDemoDatabase() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(EXPORT_DATABASE_NAME)
        val clock = Clock.fixed(Instant.parse("2026-10-20T04:00:00Z"), ZoneId.of("Asia/Shanghai"))
        val database = Room.databaseBuilder(context, ValnookDatabase::class.java, EXPORT_DATABASE_NAME)
            .addCallback(ValnookDatabase.seed)
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.TRUNCATE)
            .build()
        try {
            val raw = createDatabaseGraph(context, database, clock, currentBuildInfo(), persistentPrivateCards = false)
            val graph = AppGraph("demo-asset-generator", raw.accounts, raw.cash, raw.deposits,
                raw.investments, raw.commands, raw.clock, raw.overview,
                dev.valnook.data.repository.RoomStatistics(database, clock), raw.settings, raw.settingsWriter,
                raw.instruments, raw.cashPages, raw.depositPages)
            DemoDataSeeder(graph, clock).seed(AppSettings())
            seedWallet(database, graph, clock)
            dev.valnook.data.repository.RoomAccountGroupWriter(database, clock).setCollapsed(AccountGroup.DEFAULT_ID, true)
            enrichHistory(database, clock)
            insertLabels(database)
            check(database, graph)
        } finally {
            database.close()
        }
        val source = context.getDatabasePath(EXPORT_DATABASE_NAME)
        PlatformTestStorageRegistry.getInstance().openOutputFile(ASSET_FILE_NAME).use { output ->
            source.inputStream().use { input -> input.copyTo(output) }
        }
        assertTrue(source.length() > 0)
    }

    /** Sixteen everyday cards: bank cards bound to their accounts, a few unbound cards and one closed card. */
    private suspend fun seedWallet(database: ValnookDatabase, graph: AppGraph, clock: Clock) {
        val wallet = dev.valnook.data.repository.RoomWallet(database, clock)
        val cash = graph.overview.snapshot().cash
        fun bound(name: String) = cash.single { it.name == name }.id
        val sunflower = photo(0xfff2c14e, 0xffd9534f, 0xfffff4d6)
        val mountains = photo(0xff3b5b7a, 0xff9fb8c8, 0xffeaf2f6, mountains = true)
        val sunset = photo(0xff6b4c7a, 0xfff08a5d, 0xfffde2c9)
        wallet.addFace("纸纹", photo(0xffe6e1d8, 0xffbfb7a8, 0xffffffff))
        wallet.addFace("极光", photo(0xff12324a, 0xff3fbf9f, 0xffb8f2e6))
        fun style(background: WalletFaceBackground, geometry: WalletFaceGeometry, base: Long, secondary: Long,
            accent: Long) = WalletFaceStyle(background, geometry, base, secondary, accent)
        suspend fun card(name: String, binding: Long?, face: WalletImage? = null, style: WalletFaceStyle? = null) =
            wallet.save(null, null, name, binding, face?.key, face, style)
        card("招行储蓄卡", bound("招行储蓄卡"), face = sunflower)
        card("招行 Visa", bound("招行 Visa 全币种"),
            style = style(WalletFaceBackground.LINEAR, WalletFaceGeometry.DIAGONAL, 0xff1d2b4f, 0xff0d1426, 0xffc9a86a))
        card("招行经典白", bound("招行经典白"),
            style = style(WalletFaceBackground.SOLID, WalletFaceGeometry.NONE, 0xffe9e6df, 0xffc8c3b8, 0xff3a3a3a))
        card("工行储蓄卡", bound("工行储蓄卡"),
            style = style(WalletFaceBackground.RADIAL, WalletFaceGeometry.CIRCLES, 0xff9b2c2c, 0xff5a1818, 0xfff3d9a4))
        card("工行信用卡", bound("工行信用卡主卡"),
            style = style(WalletFaceBackground.LINEAR, WalletFaceGeometry.RINGS, 0xff7a1f2b, 0xff2b0d12, 0xffe8c37c))
        card("工行附属卡", bound("工行附属卡 · 家人"),
            style = style(WalletFaceBackground.LINEAR, WalletFaceGeometry.RINGS, 0xffb3545f, 0xff5c2229, 0xfff6dcc0))
        card("建行储蓄卡", bound("建行储蓄卡"),
            style = style(WalletFaceBackground.SOLID, WalletFaceGeometry.BLOCKS, 0xff1f4e8c, 0xff123058, 0xffdfe8f3))
        card("建行龙卡", bound("建行龙卡"), face = mountains)
        card("中行外币卡", bound("中行美元"),
            style = style(WalletFaceBackground.RADIAL, WalletFaceGeometry.ARC, 0xff8c1d24, 0xff3d0b0f, 0xffe4e8ed))
        card("公交卡", null,
            style = style(WalletFaceBackground.LINEAR, WalletFaceGeometry.DIAGONAL, 0xff2e7d5b, 0xff164030, 0xffd9f2e6))
        card("社保卡", null,
            style = style(WalletFaceBackground.SOLID, WalletFaceGeometry.BLOCKS, 0xff4f7fb8, 0xff2c4a70, 0xffffffff))
        card("图书馆借书证", null,
            style = style(WalletFaceBackground.RADIAL, WalletFaceGeometry.CIRCLES, 0xff8a6d4b, 0xff4a3826, 0xfff2e3cc))
        card("超市会员卡", null,
            style = style(WalletFaceBackground.LINEAR, WalletFaceGeometry.NONE, 0xff5b6770, 0xff2f363b, 0xffe4e8ed))
        // The work badge and the building pass were printed with the same artwork.
        card("公司工卡", null, face = sunset)
        card("小区门禁卡", null, face = sunset)

        // A cancelled card: its balance account was deleted, so the wallet keeps the card but loses the binding.
        val boc = graph.overview.snapshot().accounts.single { it.name == DemoDataSeeder.BOC }
        graph.commands.execute(SaveAccount(UUID.randomUUID().toString(), boc.id, boc.revision, boc.name, boc.note,
            listOf(CashBalanceChange("CNY", 0, null, name = "中行信用卡", type = BalanceAccountType.CREDIT,
                credit = CreditAccountInput(5_000_00, 5, CreditDueRule.AfterStatementDays(20), null)))))
        val closed = graph.overview.snapshot().cash.single { it.name == "中行信用卡" }
        card("中行信用卡（已注销）", closed.id,
            style = style(WalletFaceBackground.LINEAR, WalletFaceGeometry.ARC, 0xff6e6e73, 0xff3a3a3d, 0xffd0d0d4))
        val preview = graph.commands.previewAccountDeletion(boc.id, closed.id)
        graph.commands.execute(DeleteBalanceAccount(UUID.randomUUID().toString(), boc.id, closed.id, preview.revision,
            DeletionConfirmation(preview.ticket, preview.code)))
    }

    /** A static card photo: a soft gradient with a few shapes, at the card's aspect ratio. */
    private fun photo(base: Long, glow: Long, light: Long, mountains: Boolean = false): WalletImage {
        val width = 1586
        val height = 1000
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawPaint(Paint().apply {
            shader = LinearGradient(0f, 0f, width.toFloat(), height.toFloat(), base.toInt(), glow.toInt(), Shader.TileMode.CLAMP)
        })
        canvas.drawCircle(width * .78f, height * .28f, height * .55f, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(width * .78f, height * .28f, height * .55f, (light and 0x00ffffffL or 0x99000000L).toInt(),
                (light and 0x00ffffffL).toInt(), Shader.TileMode.CLAMP)
        })
        if (mountains) {
            val path = android.graphics.Path().apply {
                moveTo(0f, height * .78f); lineTo(width * .22f, height * .52f); lineTo(width * .4f, height * .7f)
                lineTo(width * .62f, height * .44f); lineTo(width * .86f, height * .68f); lineTo(width.toFloat(), height * .58f)
                lineTo(width.toFloat(), height.toFloat()); lineTo(0f, height.toFloat()); close()
            }
            canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (base.toInt() and 0x00ffffff) or 0xcc000000.toInt() })
        } else {
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 6f
                color = (light.toInt() and 0x00ffffff) or 0x55000000 }
            repeat(5) { canvas.drawCircle(width * .15f, height * 1.05f, height * (.35f + it * .14f), stroke) }
        }
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 82, it); it.toByteArray() }
        bitmap.recycle()
        val tint = 0xff000000L or (((base shr 16 and 0xff) + (glow shr 16 and 0xff)) / 2 shl 16) or
            (((base shr 8 and 0xff) + (glow shr 8 and 0xff)) / 2 shl 8) or (((base and 0xff) + (glow and 0xff)) / 2)
        return WalletImage(WalletImageFormat.digest(bytes), bytes, width, height, tint)
    }

    /**
     * Records were written today with their real dates; give accounts and deposits matching creation times,
     * restart statistics from the first day of the ledger and add the price history the charts read.
     */
    private suspend fun enrichHistory(database: ValnookDatabase, clock: Clock) {
        val zone = clock.zone
        val today = LocalDate.now(clock)
        val start = LocalDate.of(today.year - 9, 1, 1)
        val startMs = start.atStartOfDay(zone).toInstant().toEpochMilli()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("""UPDATE term_deposits SET created_at_ms=start_epoch_day*86400000+32400000,
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
            arrayOf(startMs, start.toEpochDay()))

        val instruments = database.overview().allInstruments().map { it.instrument }
        instruments.forEach { instrument ->
            val item = DemoDataSeeder.DEMO_INSTRUMENTS.single { it.symbol == instrument.symbol }
            var month = YearMonth.of(start.year + item.listed, 1)
            while (month < YearMonth.from(today)) {
                val date = month.atEndOfMonth()
                insertPrice(database, instrument.id, instrument.currency_code,
                    DemoDataSeeder.demoPrice(item.symbol, date, today), date.atTime(16, 0).atZone(zone).toInstant().toEpochMilli(), clock)
                month = month.plusMonths(1)
            }
            (1 until today.dayOfMonth).forEach { day ->
                val date = today.withDayOfMonth(day)
                insertPrice(database, instrument.id, instrument.currency_code,
                    DemoDataSeeder.demoPrice(item.symbol, date, today), date.atTime(16, 0).atZone(zone).toInstant().toEpochMilli(), clock)
            }
        }
    }

    private suspend fun insertPrice(database: ValnookDatabase, instrumentId: Long, currency: String, price: BigDecimal,
        at: Long, clock: Clock) {
        database.statistics().insertPrice(InstrumentPriceEntity(instrument_id = instrumentId,
            price_e5 = price.movePointRight(5).longValueExact(), currency_code = currency,
            effective_at_ms = at, created_at_ms = clock.millis()))
    }

    /** Every demo text the user can see gets an English twin; switching the app language swaps them. */
    private suspend fun insertLabels(database: ValnookDatabase) {
        val sql = database.openHelper.writableDatabase
        val dictionary = DemoDataSeeder.ENGLISH + WALLET_ENGLISH
        fun label(kind: String, id: Long, field: String, zh: String) {
            if (zh.isEmpty()) return
            val english = requireNotNull(dictionary[zh]) { "No English label for $zh" }
            sql.execSQL("INSERT OR REPLACE INTO demo_labels(entity_kind,entity_id,field_name,zh_hans,english) VALUES (?,?,?,?,?)",
                arrayOf<Any>(kind, id, field, zh, english))
        }
        database.overview().allAccounts().forEach {
            label("ACCOUNT", it.id, "NAME", it.name)
            label("ACCOUNT", it.id, "NOTE", it.note)
        }
        database.overview().allCash().forEach {
            label("CASH", it.id, "NAME", it.name)
            label("CASH", it.id, "NOTE", it.note)
        }
        fun rows(query: String, each: (android.database.Cursor) -> Unit) = sql.query(query).use { while (it.moveToNext()) each(it) }
        rows("SELECT id,note FROM cash_entries WHERE source_kind='CASH_SET' AND note<>''") {
            label("ENTRY", it.getLong(0), "NOTE", it.getString(1))
        }
        rows("SELECT id,name FROM account_groups WHERE id<>${AccountGroup.DEFAULT_ID}") {
            label("GROUP", it.getLong(0), "NAME", it.getString(1))
        }
        rows("SELECT id,name FROM wallet_cards") { label("CARD", it.getLong(0), "NAME", it.getString(1)) }
        rows("SELECT rowid,label FROM wallet_card_images") { label("FACE", it.getLong(0), "LABEL", it.getString(1)) }
        sql.query("SELECT id,name FROM asset_types ORDER BY id").use {
            while (it.moveToNext()) label("TYPE", it.getLong(0), "NAME", it.getString(1))
        }
        database.overview().allInstruments().forEach { label("INSTRUMENT", it.instrument.id, "NAME", it.instrument.name) }
    }

    /** Shape checks, plus the checks a reader would make: no savings account ever runs below zero. */
    private suspend fun check(database: ValnookDatabase, graph: AppGraph) {
        val snapshot = graph.overview.snapshot()
        val summary = StringBuilder()
        snapshot.cash.forEach { summary.appendLine("${it.name} ${it.currency.code} ${it.balance_minor}") }
        Log.i("DemoAsset", summary.toString())
        assertEquals(10, snapshot.accounts.size)
        assertEquals(4, snapshot.groups.size)
        assertEquals(21, snapshot.cash.size)
        assertEquals(5, snapshot.cash.count { it.creditProfile != null })
        assertEquals(DemoDataSeeder.DEMO_INSTRUMENTS.size, snapshot.instruments.size)
        assertEquals(15, snapshot.positions.size)
        assertTrue(snapshot.cash.filter { it.creditProfile == null }.all { it.balance_minor >= 0 })
        val total = AssetValuation.calculate(snapshot).total.amount
        Log.i("DemoAsset", "total=$total")
        assertTrue(total > BigDecimal("1000000") && total < BigDecimal("3000000"))
        val sql = database.openHelper.readableDatabase
        // Running balance of every savings account, in date order, from its own ledger.
        sql.query("""SELECT c.name,e.delta_minor FROM cash_entries e JOIN cash_accounts c ON c.id=e.cash_account_id
            WHERE e.is_deleted=0 AND NOT EXISTS(SELECT 1 FROM credit_account_profiles p WHERE p.account_id=c.id)
            ORDER BY c.id,e.occurred_at_ms,e.id""").use {
            val running = mutableMapOf<String, Long>()
            val lowest = mutableMapOf<String, Long>()
            while (it.moveToNext()) {
                val value = running.getOrDefault(it.getString(0), 0L) + it.getLong(1)
                running[it.getString(0)] = value
                lowest[it.getString(0)] = minOf(lowest[it.getString(0)] ?: value, value)
            }
            Log.i("DemoAsset", "lowest=$lowest")
            assertTrue("Negative running balance: $lowest", lowest.values.all { value -> value >= 0 })
        }
        sql.query("SELECT MIN(occurred_at_ms),MAX(occurred_at_ms) FROM cash_entries WHERE is_deleted=0").use {
            assertTrue(it.moveToFirst())
            assertTrue(it.getLong(1) - it.getLong(0) >= 9L * 365L * 86_400_000L)
        }
        sql.query("SELECT COUNT(*) FROM investment_trades t JOIN instruments i ON i.id=(SELECT instrument_id FROM investments p WHERE p.id=t.investment_id) WHERE i.symbol='510300.SH'").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) > 100)
        }
        val cards = dev.valnook.data.repository.RoomWallet(database, graph.clock).observeCards().first()
        assertEquals(16, cards.size)
        assertEquals(1, cards.count { it.bindingLost })
        val faces = dev.valnook.data.repository.RoomWallet(database, graph.clock).observeFaces().first()
        assertEquals(5, faces.size)
        assertEquals(2, faces.count { !it.inUse })
        sql.execSQL("VACUUM")
    }

    private companion object {
        /** Card names, and the labels of faces named after them, in both languages. */
        val WALLET_ENGLISH = mapOf(
            "招行储蓄卡" to "CMB debit card",
            "招行 Visa" to "CMB Visa",
            "招行经典白" to "CMB Classic White",
            "工行储蓄卡" to "ICBC debit card",
            "工行信用卡" to "ICBC credit card",
            "工行附属卡" to "ICBC supplementary card",
            "建行储蓄卡" to "CCB debit card",
            "建行龙卡" to "CCB Long Card",
            "中行外币卡" to "BOC multi-currency card",
            "公交卡" to "Transit card",
            "社保卡" to "Social security card",
            "图书馆借书证" to "Library card",
            "超市会员卡" to "Supermarket membership",
            "公司工卡" to "Work badge",
            "小区门禁卡" to "Building pass",
            "中行信用卡（已注销）" to "BOC credit card (cancelled)",
            "纸纹" to "Paper", "极光" to "Aurora"
        )
        const val EXPORT_DATABASE_NAME = "valnook-demo-asset-export.db"
        const val ASSET_FILE_NAME = "valnook-demo-v21.db"
    }
}

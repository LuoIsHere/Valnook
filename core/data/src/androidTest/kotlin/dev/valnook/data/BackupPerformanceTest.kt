package dev.valnook.data

import android.content.Context
import android.os.Debug
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.repository.SaveAccount
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPerformanceTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T08:30:00Z"), ZoneOffset.UTC)

    @Test fun streams_large_backup_and_restore_with_measured_resources() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        val tradeCount = arguments.getString("trades")?.toIntOrNull() ?: 100_000
        require(tradeCount in 1..250_000)
        val sourceName = "backup-performance-source-${UUID.randomUUID()}.db"
        val targetName = "backup-performance-target-${UUID.randomUUID()}.db"
        val output = File(context.cacheDir, "backup-performance-${UUID.randomUUID()}.val_backup")
        var source: ValnookDatabase? = null
        var target: ValnookDatabase? = null
        try {
            source = database(sourceName)
            seed(source, tradeCount)
            val engine = engine(source)
            val snapshotStarted = CompletableDeferred<Unit>()
            val writeWait = async(Dispatchers.IO) {
                snapshotStarted.await()
                val start = System.nanoTime()
                RoomFinancialCommands(source, clock).execute(SaveAccount(
                    UUID.randomUUID().toString(), null, null, "Concurrent write", "", emptyList()))
                elapsedMs(start)
            }
            val backupMeasure = measure {
                withContext(Dispatchers.IO) {
                    FileOutputStream(output).use { sink ->
                        engine.createBackup(UUID.randomUUID().toString(), sink) {
                            if (it.stage == dev.valnook.domain.portability.PortabilityStage.PREPARING_SNAPSHOT) {
                                snapshotStarted.complete(Unit)
                            }
                        }
                    }
                }
            }
            val writeBlockedMs = writeWait.await()

            target = database(targetName)
            val targetEngine = engine(target)
            val restoreMeasure = measure {
                val staged = withContext(Dispatchers.IO) {
                    FileInputStream(output).use { targetEngine.prepareRestore(it, output.name) {} }
                }
                try {
                    targetEngine.commitRestore(staged) {}
                } finally {
                    targetEngine.close(staged)
                }
            }
            target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM investment_trades").use {
                it.moveToFirst()
                assertEquals(tradeCount, it.getInt(0))
            }
            val report = JSONObject()
                .put("device", android.os.Build.MODEL)
                .put("api", android.os.Build.VERSION.SDK_INT)
                .put("trade_count", tradeCount)
                .put("corresponding_audit_count", tradeCount)
                .put("archive_bytes", output.length())
                .put("backup_elapsed_ms", backupMeasure.elapsedMs)
                .put("restore_elapsed_ms", restoreMeasure.elapsedMs)
                .put("sampled_peak_java_heap_bytes", maxOf(backupMeasure.peakJavaHeap, restoreMeasure.peakJavaHeap))
                .put("sampled_peak_total_pss_kib", maxOf(backupMeasure.peakPssKib, restoreMeasure.peakPssKib))
                .put("sampled_peak_private_staging_bytes",
                    maxOf(backupMeasure.peakStagingBytes, restoreMeasure.peakStagingBytes))
                .put("concurrent_business_write_wait_ms", writeBlockedMs)
                .put("measurement_note", "10 ms in-process samples; isolated synthetic Room databases; output stream is a file")
            PlatformTestStorageRegistry.getInstance().openOutputFile("backup-performance.json").use {
                it.write(report.toString(2).toByteArray())
            }
        } finally {
            source?.close()
            target?.close()
            context.deleteDatabase(sourceName)
            context.deleteDatabase(targetName)
            output.delete()
            context.cacheDir.resolve("valnook-portability").deleteRecursively()
        }
    }

    private fun database(name: String): ValnookDatabase =
        Room.databaseBuilder(context, ValnookDatabase::class.java, name)
            .addCallback(ValnookDatabase.seed).build().also { it.openHelper.writableDatabase }

    private fun engine(database: ValnookDatabase) = RoomPortabilityEngine(context, database, clock,
        AppBuildInfo("valnook", "0.0.5", 5, "20261003.2", "20261003.2", 9))

    private suspend fun seed(database: ValnookDatabase, count: Int) = database.withTransaction {
        val raw = database.openHelper.writableDatabase
        raw.execSQL("INSERT INTO savings_accounts(id,name,note,created_at_ms,updated_at_ms,revision) VALUES (1,'Scale account','synthetic',1,1,1)")
        raw.execSQL("INSERT INTO asset_types(id,name,normalized_name,created_at_ms,updated_at_ms) VALUES (1,'Equity','equity',1,1)")
        raw.execSQL("""INSERT INTO instruments(id,asset_type_id,name,symbol,currency_code,current_price_e5,
            currency_locked,revision,symbol_locked,price_updated_at_ms,created_at_ms,updated_at_ms)
            VALUES (1,1,'Synthetic scale instrument','SCALE','CNY',100000,1,1,1,1,1,1)""")
        raw.execSQL("""INSERT INTO investments(id,savings_account_id,instrument_id,opening_quantity_e8,
            holding_quantity_e8,revision,created_at_ms,updated_at_ms,opening_cost_price_e8,opening_at_ms,
            remaining_cost,realized_profit,chronology_valid,algorithm_version,position_state,last_activity_at_ms)
            VALUES (1,1,1,0,?,1,1,?,NULL,0,?,'0',1,4,'HOLDING',?)""",
            arrayOf<Any>(count.toLong() * 100_000_000L, count.toLong(), count.toString(), count.toLong()))
        val operation = raw.compileStatement("""INSERT INTO operations(operation_id,kind,request_fingerprint,
            result_kind,result_id,created_at_ms) VALUES (?,?,?,?,?,?)""")
        val trade = raw.compileStatement("""INSERT INTO investment_trades(id,investment_id,operation_id,direction,
            quantity_e8,execution_price_e8,amount_minor,currency_code,cash_linked,cash_account_id,occurred_at_ms,
            created_at_ms,revision,is_deleted,updated_at_ms,fee_minor) VALUES (?,1,?,'BUY',100000000,100000000,
            100,'CNY',0,NULL,?,?,1,0,?,0)""")
        val audit = raw.compileStatement("""INSERT INTO audit_events(event_id,event_schema_version,correlation_id,
            operation_id,action,entity_kind,entity_id,business_at_ms,business_local_date,recorded_at_ms,before_json,
            after_json,changed_fields_json,cash_effects_json,source) VALUES (?,1,?,?,'CREATE','INVESTMENT_TRADE',
            ?,?,'2026-01-01',?,NULL,?,'[]','[]','SYNTHETIC_TEST')""")
        val accountLink = raw.compileStatement("INSERT INTO audit_event_accounts(event_id,account_id) VALUES (?,1)")
        repeat(count) { offset ->
            val id = offset + 1L
            val suffix = id.toString().padStart(12, '0')
            val operationId = "00000000-0000-0000-0000-$suffix"
            val eventId = "10000000-0000-0000-0000-$suffix"
            operation.clearBindings(); operation.bindString(1, operationId); operation.bindString(2, "ACCOUNT_TRADE")
            operation.bindString(3, "synthetic-$id"); operation.bindString(4, "INVESTMENT_TRADE")
            operation.bindLong(5, id); operation.bindLong(6, id); operation.executeInsert()
            trade.clearBindings(); trade.bindLong(1, id); trade.bindString(2, operationId)
            trade.bindLong(3, id); trade.bindLong(4, id); trade.bindLong(5, id); trade.executeInsert()
            val after = "{\"id\":\"$id\",\"quantity_e8\":\"100000000\",\"execution_price_e8\":\"100000000\",\"amount_minor\":\"100\",\"fee_minor\":\"0\",\"currency_code\":\"CNY\",\"audit_instrument_name\":\"Synthetic scale instrument\",\"audit_instrument_symbol\":\"SCALE\"}"
            audit.clearBindings(); audit.bindString(1, eventId); audit.bindString(2, operationId)
            audit.bindString(3, operationId); audit.bindString(4, id.toString()); audit.bindLong(5, id)
            audit.bindLong(6, id); audit.bindString(7, after); audit.executeInsert()
            accountLink.clearBindings(); accountLink.bindString(1, eventId); accountLink.executeInsert()
        }
    }

    private suspend fun measure(block: suspend () -> Unit): Measurement = coroutineScope {
        val running = AtomicBoolean(true)
        val peakHeap = AtomicLong(0)
        val peakPss = AtomicLong(0)
        val peakStaging = AtomicLong(0)
        val sampler = async(Dispatchers.Default) {
            val memory = Debug.MemoryInfo()
            while (running.get()) {
                val runtime = Runtime.getRuntime()
                peakHeap.accumulateAndGet(runtime.totalMemory() - runtime.freeMemory(), ::maxOf)
                Debug.getMemoryInfo(memory)
                peakPss.accumulateAndGet(memory.totalPss.toLong(), ::maxOf)
                peakStaging.accumulateAndGet(
                    directorySize(context.cacheDir.resolve("valnook-portability")), ::maxOf)
                delay(10)
            }
        }
        val started = System.nanoTime()
        try {
            block()
        } finally {
            running.set(false)
            sampler.await()
        }
        Measurement(elapsedMs(started), peakHeap.get(), peakPss.get(), peakStaging.get())
    }

    private fun elapsedMs(started: Long): Long = (System.nanoTime() - started) / 1_000_000L
    private fun directorySize(root: File): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    private data class Measurement(
        val elapsedMs: Long,
        val peakJavaHeap: Long,
        val peakPssKib: Long,
        val peakStagingBytes: Long
    )
}

package dev.valnook.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.io.PlatformTestStorageRegistry
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.ArchiveManifest
import dev.valnook.data.portability.BackupReadLimits
import dev.valnook.data.portability.CompatibilityRules
import dev.valnook.data.portability.PayloadFileInfo
import dev.valnook.data.portability.PortableJson
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.portability.RestoreImporter
import dev.valnook.data.portability.RestorePoint
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.data.transaction.TransactionPoint
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.Currency
import dev.valnook.domain.model.Direction
import dev.valnook.domain.model.BalanceAccountType
import dev.valnook.domain.model.CreditAccountInput
import dev.valnook.domain.model.CreditDueRule
import dev.valnook.domain.portability.PortabilityException
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.repository.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupPortabilityTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-10-03T08:30:00Z"), ZoneId.of("Asia/Hong_Kong"))
    private val databases = mutableListOf<Pair<String, ValnookDatabase>>()

    @Before fun cleanPortabilityCache() {
        context.cacheDir.resolve("valnook-portability").deleteRecursively()
    }

    @After fun closeDatabases() {
        databases.forEach { (name, database) -> database.close(); context.deleteDatabase(name) }
        context.cacheDir.resolve("valnook-portability").deleteRecursively()
    }

    @Test fun portable_backup_round_trips_authoritative_rows_and_excel_is_a_real_workbook() = runBlocking {
        val source = database("source")
        val sourceCommands = RoomFinancialCommands(source, clock)
        val accountId = sourceCommands.execute(SaveAccount(testOperationId(), null, null,
            "=安全名称", "Unicode 备注 中文", listOf(CashBalanceChange("CNY", -12345, null,
                name = "现金账户", note = "允许负余额")))).id
        val secondAccountId = sourceCommands.execute(SaveAccount(testOperationId(), null, null,
            "Schwab", "+formula-looking note", listOf(
                CashBalanceChange("USD", 0, null, name = "Brokerage cash"),
                CashBalanceChange("USD", 98765, null, name = "Settlement cash")
            ))).id
        val thirdAccountId = sourceCommands.execute(SaveAccount(testOperationId(), null, null,
            "汇丰香港", "@not-a-formula", listOf(CashBalanceChange("HKD", 54321, null,
                name = "港币现金")))).id
        val firstOwner = requireNotNull(source.accounts().account(accountId))
        sourceCommands.execute(SaveAccount(testOperationId(), accountId, firstOwner.revision, firstOwner.name, firstOwner.note,
            listOf(BalanceAccountChange("CNY", -63_284, null, name = "Visa 主卡", type = BalanceAccountType.CREDIT,
                credit = CreditAccountInput(2_000_000, 12, CreditDueRule.AfterStatementDays(20), null)))))
        val creditRoot = RoomOverview(source).snapshot().cash.single { it.name == "Visa 主卡" }.id
        val refreshedFirstOwner = requireNotNull(source.accounts().account(accountId))
        sourceCommands.execute(SaveAccount(testOperationId(), accountId, refreshedFirstOwner.revision,
            refreshedFirstOwner.name, refreshedFirstOwner.note,
            listOf(BalanceAccountChange("CNY", -20_000, null, name = "Visa 附属卡", type = BalanceAccountType.CREDIT,
                credit = CreditAccountInput(null, 12, CreditDueRule.FixedDayOfMonth(5), creditRoot)))))
        val typeId = sourceCommands.testType("股票")
        val instrumentId = sourceCommands.testInstrument("平安银行", "000001.SZ", typeId, "CNY", 1_468_200_000_00L)
        val positionId = sourceCommands.execute(CreateInvestmentPosition(testOperationId(), accountId, instrumentId)).id
        sourceCommands.execute(RecordInvestmentTrade(testOperationId(), positionId, Direction.BUY,
            123_456_789L, 100_123_456_78L, clock.millis() - 86_400_000L, false, fee_minor = 37))
        sourceCommands.execute(RecordInvestmentTrade(testOperationId(), positionId, Direction.SELL,
            23_456_789L, 110_000_000_00L, clock.millis() - 43_200_000L, false, fee_minor = 19))
        val sharedPositionId = sourceCommands.execute(
            CreateInvestmentPosition(testOperationId(), secondAccountId, instrumentId)).id
        sourceCommands.execute(RecordInvestmentTrade(testOperationId(), sharedPositionId, Direction.BUY,
            10_000_000L, 99_000_000_00L, clock.millis() - 72_000_000L, false, fee_minor = 5))
        sourceCommands.execute(RecordInvestmentTrade(testOperationId(), sharedPositionId, Direction.SELL,
            10_000_000L, 101_000_000_00L, clock.millis() - 36_000_000L, false, fee_minor = 7))
        val sharedPriceOperation = testOperationId()
        val price = source.statistics().prices().single { it.instrument_id == instrumentId }
        sourceCommands.execute(EditInstrumentPrice(sharedPriceOperation, price.id, price.revision,
            1_500_000_00L, clock.millis() - 1_000L))
        source.openHelper.readableDatabase.query(
            """SELECT COUNT(*) FROM audit_event_accounts a JOIN audit_events e ON e.event_id=a.event_id
                WHERE e.operation_id=?""", arrayOf(sharedPriceOperation)
        ).use { assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0)) }
        sourceCommands.execute(OpenTermDeposit(testOperationId(), thirdAccountId, "HKD", 88_800,
            350_000_000L, java.time.LocalDate.of(2026, 1, 15).toEpochDay(),
            java.time.LocalDate.of(2027, 1, 15).toEpochDay(), false))
        RoomSettings(source, clock).applyChange(SaveFinancialSettings(0, Currency.of("CNY"), emptyList()))

        val sourceEngine = engine(source)
        val backupOutput = ByteArrayOutputStream()
        val backup = sourceEngine.createBackup(UUID.randomUUID().toString(), backupOutput) {}
        assertTrue(backup.byteCount > 0)
        assertTrue(backup.recordCount > 0)
        val packageBytes = backupOutput.toByteArray()
        PlatformTestStorageRegistry.getInstance().openOutputFile("synthetic-roundtrip.val_backup").use {
            it.write(packageBytes)
        }
        val entries = zipEntries(packageBytes)
        assertTrue("manifest.json" in entries)
        assertTrue("data/audit_events.jsonl" in entries)
        assertTrue("data/credit_account_profiles.jsonl" in entries)
        assertTrue("verification/snapshot_totals.json" in entries)
        val termRows = zipText(packageBytes, "data/term_deposits.jsonl")
        assertTrue(termRows.contains("\"start_date\":\"2026-01-15\""))
        assertTrue(termRows.contains("\"end_date\":\"2027-01-15\""))
        assertFalse(termRows.contains("start_epoch_day"))

        val target = database("target")
        RoomFinancialCommands(target, clock).testAccount("must be replaced")
        val targetEngine = engine(target)
        val staged = targetEngine.prepareRestore(ByteArrayInputStream(packageBytes), "roundtrip.val_backup") {}
        try {
            assertEquals(3, targetEngine.preview(staged).accountCount)
            targetEngine.commitRestore(staged) {}
        } finally {
            targetEngine.close(staged)
        }

        val restored = RoomOverview(target).snapshot()
        assertEquals(listOf("=安全名称", "Schwab", "汇丰香港"), restored.accounts.map { it.name })
        assertEquals(
            -12345L,
            restored.cash.single { it.account_id == accountId && it.name == "现金账户" }.balance_minor,
        )
        assertEquals(6, restored.cash.size)
        assertEquals(2, restored.cash.count { it.type == BalanceAccountType.CREDIT })
        val restoredChild = restored.cash.single { it.name == "Visa 附属卡" }
        assertEquals(creditRoot, restoredChild.creditProfile?.limitSourceAccountId)
        assertEquals(100_000_000L, restored.positions.first { it.id == positionId }.holding_quantity_e8)
        assertEquals(0L, restored.positions.first { it.id == sharedPositionId }.holding_quantity_e8)
        assertEquals(1, restored.deposits.size)
        assertEquals(37L, target.trades().trade(1)!!.fee_minor)
        assertEquals("CNY", restored.settings.baseCurrency!!.code)
        target.openHelper.readableDatabase.query("SELECT COUNT(*) FROM audit_events").use {
            assertTrue(it.moveToFirst()); assertTrue(it.getInt(0) >= 6)
        }

        val normalizedOutput = ByteArrayOutputStream()
        targetEngine.createBackup(UUID.randomUUID().toString(), normalizedOutput) {}
        val normalizedPayload = zipPayload(normalizedOutput.toByteArray())
        val sourcePayload = zipPayload(packageBytes)
        assertEquals(sourcePayload.keys, normalizedPayload.keys)
        sourcePayload.forEach { (path, bytes) ->
            assertArrayEquals("Normalized payload differs at $path", bytes, normalizedPayload[path])
        }

        val workbookOutput = ByteArrayOutputStream()
        val workbook = targetEngine.exportWorkbook(UUID.randomUUID().toString(), AppLanguage.ZH_HANS,
            false, workbookOutput) {}
        assertTrue(workbook.byteCount > 0)
        val workbookBytes = workbookOutput.toByteArray()
        PlatformTestStorageRegistry.getInstance().openOutputFile("synthetic-report.xlsx").use {
            it.write(workbookBytes)
        }
        val workbookEntries = zipEntries(workbookBytes)
        assertTrue("[Content_Types].xml" in workbookEntries)
        assertTrue("xl/workbook.xml" in workbookEntries)
        assertEquals(7, workbookEntries.count { it.startsWith("xl/worksheets/sheet") })
        val workbookXml = zipText(workbookBytes, "xl/worksheets/sheet1.xml")
        assertTrue(workbookXml.contains("仅供分析"))
        assertTrue(workbookXml.contains("信用账户数"))
        assertFalse(workbookXml.contains("<f>"))
        assertTrue(workbookXml.contains("<hyperlink"))
        target.openHelper.readableDatabase.query(
            "SELECT event_schema_version,correlation_id,business_local_date FROM audit_events WHERE operation_id IS NOT NULL ORDER BY recorded_at_ms LIMIT 1"
        ).use {
            assertTrue(it.moveToFirst())
            assertEquals(1, it.getInt(0))
            assertTrue(it.getString(1).isNotBlank())
        }
    }

    @Test fun empty_english_workbook_is_valid_and_contains_only_the_summary_sheet() = runBlocking {
        val output = ByteArrayOutputStream()
        engine(database("empty-workbook")).exportWorkbook(
            UUID.randomUUID().toString(), AppLanguage.ENGLISH, false, output
        ) {}
        val bytes = output.toByteArray()
        assertEquals(1, zipEntries(bytes).count { it.startsWith("xl/worksheets/sheet") })
        val summary = zipText(bytes, "xl/worksheets/sheet1.xml")
        assertTrue(summary.contains("For analysis only"))
        assertFalse(summary.contains("<f>"))
    }

    @Test fun data_schema_v1_restores_all_legacy_balance_accounts_as_savings() = runBlocking {
        val source = database("v1-source")
        RoomFinancialCommands(source, clock).execute(SaveAccount(testOperationId(), null, null, "Legacy", "",
            listOf(CashBalanceChange("CNY", -12_345, null, name = "Legacy negative balance"))))
        val output = ByteArrayOutputStream()
        engine(source).createBackup(UUID.randomUUID().toString(), output) {}
        val v1 = downgradeToDataSchemaV1(output.toByteArray())
        val target = database("v1-target")
        val targetEngine = engine(target)
        val staged = targetEngine.prepareRestore(ByteArrayInputStream(v1), "legacy-v1.val_backup") {}
        try {
            assertEquals(1, targetEngine.preview(staged).dataSchemaVersion)
            targetEngine.commitRestore(staged) {}
        } finally { targetEngine.close(staged) }
        val restored = RoomOverview(target).snapshot().cash.single()
        assertEquals(-12_345, restored.balance_minor)
        assertEquals(BalanceAccountType.SAVINGS, restored.type)
        assertNull(restored.creditProfile)
    }

    @Test fun restore_faults_roll_back_the_complete_original_dataset() = runBlocking {
        val source = database("fault-source")
        RoomFinancialCommands(source, clock).testAccount("replacement")
        val packageOutput = ByteArrayOutputStream()
        engine(source).createBackup(UUID.randomUUID().toString(), packageOutput) {}

        RestorePoint.entries.forEach { point ->
            val target = database("fault-${point.name.lowercase()}")
            RoomFinancialCommands(target, clock).testAccount("keep")
            val targetEngine = engine(target)
            val staged = targetEngine.prepareRestore(
                ByteArrayInputStream(packageOutput.toByteArray()), "fault.val_backup"
            ) {}
            try {
                val importer = RestoreImporter(target, clock) { current ->
                    if (current == point) error("injected $point")
                }
                try {
                    importer.commit(staged, staged.generationAtPreview)
                    fail("Expected injected restore failure at $point")
                } catch (_: IllegalStateException) {
                    assertEquals(listOf("keep"), RoomOverview(target).snapshot().accounts.map { it.name })
                    target.openHelper.readableDatabase.query(
                        "SELECT maintenance_in_progress FROM local_maintenance_state WHERE id=1"
                    ).use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(0, cursor.getInt(0))
                    }
                }
            } finally {
                targetEngine.close(staged)
            }
        }
    }

    @Test fun compatibility_uses_numeric_versions_and_rejects_unknown_required_features() {
        val reader = buildInfo()
        CompatibilityRules.validate(manifest(reader.copy(appVersion = "0.0.4", appVersionCode = 4,
            internalBuildRevision = "20261003.1", databaseSchemaVersion = 8)), reader)
        expect(PortabilityErrorCode.INCOMPATIBLE_VERSION) {
            CompatibilityRules.validate(manifest(reader.copy(appVersion = "0.0.10")), reader)
        }
        expect(PortabilityErrorCode.INCOMPATIBLE_VERSION) {
            CompatibilityRules.validate(manifest(reader.copy(internalBuildRevision = "20261003.10")), reader)
        }
        expect(PortabilityErrorCode.INCOMPATIBLE_VERSION) {
            CompatibilityRules.validate(manifest(reader.copy(appVersion = "0.0.x")), reader)
        }
        expect(PortabilityErrorCode.UNKNOWN_FEATURE) {
            CompatibilityRules.validate(manifest(reader, listOf("future-feature-v2")), reader)
        }
    }

    @Test fun strict_json_rejects_duplicate_keys_and_excessive_depth() {
        expect(PortabilityErrorCode.INVALID_DATA) {
            PortableJson.parse("{\"a\":1,\"a\":2}", 32)
        }
        expect(PortabilityErrorCode.LIMIT_EXCEEDED) {
            PortableJson.parse("[".repeat(34) + "]".repeat(34), 32)
        }
    }

    @Test fun duplicate_and_unexpected_zip_entries_are_rejected_before_any_database_change() = runBlocking {
        val target = database("hostile-target")
        RoomFinancialCommands(target, clock).testAccount("keep")
        val targetEngine = engine(target)
        val oneEntry = zipOf("manifest.json" to "{}".toByteArray())
        val central = findSignature(oneEntry, byteArrayOf(0x50, 0x4b, 0x01, 0x02))
        val duplicate = oneEntry.copyOfRange(0, central) + oneEntry
        expect(PortabilityErrorCode.DUPLICATE_ENTRY) {
            runBlocking { targetEngine.prepareRestore(ByteArrayInputStream(duplicate), "duplicate.val_backup") {} }
        }
        val traversal = zipOf("../escape" to "bad".toByteArray())
        expectOneOf(setOf(PortabilityErrorCode.UNEXPECTED_FILE, PortabilityErrorCode.INVALID_ARCHIVE)) {
            runBlocking { targetEngine.prepareRestore(ByteArrayInputStream(traversal), "traversal.val_backup") {} }
        }
        assertFalse(context.cacheDir.resolve("escape").exists())
        val fakeWorkbook = zipOf("[Content_Types].xml" to "xlsx".toByteArray())
        expect(PortabilityErrorCode.UNEXPECTED_FILE) {
            runBlocking { targetEngine.prepareRestore(ByteArrayInputStream(fakeWorkbook), "renamed.val_backup") {} }
        }
        assertEquals("keep", RoomOverview(target).snapshot().accounts.single().name)
    }

    @Test fun archive_size_limit_is_enforced_while_copying_input() = runBlocking {
        val target = database("limited-target")
        val targetEngine = RoomPortabilityEngine(context, target, clock, buildInfo(),
            BackupReadLimits(maxArchiveBytes = 16))
        expect(PortabilityErrorCode.LIMIT_EXCEEDED) {
            runBlocking {
                targetEngine.prepareRestore(ByteArrayInputStream(ByteArray(17)), "large.val_backup") {}
            }
        }
    }

    @Test fun corrupted_package_is_rejected_before_target_rows_change() = runBlocking {
        val source = database("corrupt-source")
        RoomFinancialCommands(source, clock).testAccount("source")
        val output = ByteArrayOutputStream()
        engine(source).createBackup(UUID.randomUUID().toString(), output) {}
        val corrupt = output.toByteArray().copyOf().also { bytes ->
            val index = bytes.size / 2
            bytes[index] = (bytes[index].toInt() xor 0x5a).toByte()
        }
        val target = database("corrupt-target")
        RoomFinancialCommands(target, clock).testAccount("keep")
        val targetEngine = engine(target)
        try {
            targetEngine.prepareRestore(ByteArrayInputStream(corrupt), "corrupt.val_backup") {}
            fail("Expected corrupt package rejection")
        } catch (_: PortabilityException) {
            assertEquals("keep", RoomOverview(target).snapshot().accounts.single().name)
        }
    }

    @Test fun crossParentCreditGraphIsRejectedBeforeTargetRowsChange() = runBlocking {
        val source = database("cross-parent-source")
        val sourceCommands = RoomFinancialCommands(source, clock)
        val first = sourceCommands.testAccount("root parent")
        val second = sourceCommands.testAccount("child parent")

        suspend fun independent(parentId: Long, name: String): Long {
            val owner = requireNotNull(source.accounts().account(parentId))
            sourceCommands.execute(SaveAccount(testOperationId(), parentId, owner.revision, owner.name, owner.note,
                listOf(BalanceAccountChange("CNY", 0, null, name = name, type = BalanceAccountType.CREDIT,
                    credit = CreditAccountInput(100_000, 12, CreditDueRule.AfterStatementDays(20), null)))))
            return RoomOverview(source).snapshot().cash.single { it.account_id == parentId && it.name == name }.id
        }

        val root = independent(first, "Root")
        val child = independent(second, "Child")
        val output = ByteArrayOutputStream()
        engine(source).createBackup(UUID.randomUUID().toString(), output) {}
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            files[entry.name] = zip.readBytes()
        } }
        val profiles = files.getValue("data/credit_account_profiles.jsonl").toString(Charsets.UTF_8)
            .lineSequence().filter(String::isNotBlank).map { line -> JSONObject(line).also { row ->
                if (row.getLong("account_id") == child) {
                    row.put("credit_limit_minor", JSONObject.NULL)
                    row.put("limit_source_account_id", root.toString())
                }
            }.toString() }.joinToString("\n", postfix = "\n").toByteArray()
        files["data/credit_account_profiles.jsonl"] = profiles
        val manifest = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
        val manifestFiles = manifest.getJSONArray("files")
        repeat(manifestFiles.length()) { index ->
            val row = manifestFiles.getJSONObject(index)
            if (row.getString("path") == "data/credit_account_profiles.jsonl") {
                row.put("uncompressedBytes", profiles.size)
                row.put("sha256", MessageDigest.getInstance("SHA-256").digest(profiles)
                    .joinToString("") { "%02x".format(it) })
            }
        }
        files["manifest.json"] = manifest.toString().toByteArray()
        val invalidPackage = ByteArrayOutputStream().also { bytes -> ZipOutputStream(bytes).use { zip ->
            files.forEach { (name, value) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry()
            }
        } }.toByteArray()

        val target = database("cross-parent-target")
        RoomFinancialCommands(target, clock).testAccount("keep")
        val targetEngine = engine(target)
        expect(PortabilityErrorCode.RELATIONSHIP_ERROR) {
            runBlocking {
                targetEngine.prepareRestore(ByteArrayInputStream(invalidPackage), "cross-parent.val_backup") {}
            }
        }
        assertEquals("keep", RoomOverview(target).snapshot().accounts.single().name)
    }

    @Test fun audit_is_idempotent_and_rolls_back_with_the_business_transaction() = runBlocking {
        val database = database("audit")
        val operationId = testOperationId()
        val commands = RoomFinancialCommands(database, clock)
        val request = SaveAccount(operationId, null, null, "once", "", emptyList())
        val first = commands.execute(request)
        assertEquals(first, commands.execute(request))
        database.openHelper.readableDatabase.query(
            "SELECT COUNT(*) FROM audit_events WHERE operation_id=?", arrayOf(operationId)
        ).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }

        val failingOperation = testOperationId()
        val failing = RoomFinancialCommands(database, clock) { point ->
            if (point == TransactionPoint.BEFORE_RECEIPT) error("injected")
        }
        try {
            failing.execute(SaveAccount(failingOperation, null, null, "rollback", "", emptyList()))
            fail("Expected injected failure")
        } catch (_: IllegalStateException) {
            database.openHelper.readableDatabase.query(
                "SELECT COUNT(*) FROM audit_events WHERE operation_id=?", arrayOf(failingOperation)
            ).use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
            assertEquals(listOf("once"), RoomOverview(database).snapshot().accounts.map { it.name })
        }
    }

    private fun database(prefix: String): ValnookDatabase {
        val name = "$prefix-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, ValnookDatabase::class.java, name)
            .addCallback(ValnookDatabase.seed).build()
        databases += name to db
        return db
    }

    private fun buildInfo() = AppBuildInfo("valnook", "0.0.5", 5, "20261003.2", "20261003.2", 9)

    private fun engine(database: ValnookDatabase) = RoomPortabilityEngine(context, database, clock, buildInfo())

    private fun manifest(producer: AppBuildInfo, features: List<String> = listOf(
        "audit-v1", "overwrite-restore-v1", "portable-model-v1")) = ArchiveManifest(
        backupId = UUID.randomUUID().toString(),
        createdAtUtc = "2026-10-03T08:30:00Z",
        snapshotAtUtc = "2026-10-03T08:30:00Z",
        snapshotId = UUID.randomUUID().toString(),
        accountCount = 0,
        cashAccountCount = 0,
        tradeCount = 0,
        auditEventCount = 0,
        producer = producer,
        files = emptyList<PayloadFileInfo>(),
        requiredFeatures = features
    )

    private fun expect(code: PortabilityErrorCode, action: () -> Unit) {
        try {
            action()
            fail("Expected $code")
        } catch (error: PortabilityException) {
            assertEquals(code, error.errorCode)
        }
    }

    private fun expectOneOf(codes: Set<PortabilityErrorCode>, action: () -> Unit) {
        try {
            action()
            fail("Expected one of $codes")
        } catch (error: PortabilityException) {
            assertTrue("Unexpected ${error.errorCode}", error.errorCode in codes)
        }
    }

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun findSignature(bytes: ByteArray, signature: ByteArray): Int {
        for (index in 0..bytes.size - signature.size) {
            if (signature.indices.all { bytes[index + it] == signature[it] }) return index
        }
        throw AssertionError("ZIP signature not found")
    }

    private fun zipEntries(bytes: ByteArray): Set<String> {
        val result = linkedSetOf<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) result += zip.nextEntry?.name ?: break
        }
        return result
    }

    private fun zipText(bytes: ByteArray, target: String): String {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name == target) return zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        throw AssertionError("Missing $target")
    }

    private fun zipPayload(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.name !in setOf(
                        "manifest.json",
                        "verification/snapshot_totals.json",
                        // Restore intentionally invalidates derived statistics state before re-export.
                        "data/valuation_baselines.jsonl"
                    )) {
                    result[entry.name] = zip.readBytes()
                }
            }
        }
        return result
    }

    private fun downgradeToDataSchemaV1(bytes: ByteArray): ByteArray {
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip -> while (true) {
            val entry = zip.nextEntry ?: break
            files[entry.name] = zip.readBytes()
        } }
        files.remove("data/credit_account_profiles.jsonl")
        val totals = JSONObject(files.getValue("verification/snapshot_totals.json").toString(Charsets.UTF_8))
        totals.getJSONObject("recordCounts").remove("data/credit_account_profiles.jsonl")
        files["verification/snapshot_totals.json"] = totals.toString().toByteArray()
        val manifest = JSONObject(files.getValue("manifest.json").toString(Charsets.UTF_8))
        manifest.put("dataSchemaVersion", 1)
        manifest.put("requiredFeatures", JSONArray(listOf("audit-v1", "overwrite-restore-v1", "portable-model-v1")))
        val fileRows = manifest.getJSONArray("files")
        val rebuilt = JSONArray()
        repeat(fileRows.length()) { index ->
            val row = fileRows.getJSONObject(index)
            val path = row.getString("path")
            if (path != "data/credit_account_profiles.jsonl") {
                if (path == "verification/snapshot_totals.json") {
                    val value = files.getValue(path)
                    row.put("uncompressedBytes", value.size)
                    row.put("sha256", MessageDigest.getInstance("SHA-256").digest(value)
                        .joinToString("") { "%02x".format(it) })
                }
                rebuilt.put(row)
            }
        }
        manifest.put("files", rebuilt)
        files["manifest.json"] = manifest.toString().toByteArray()
        return ByteArrayOutputStream().also { output -> ZipOutputStream(output).use { zip ->
            files.forEach { (name, value) -> zip.putNextEntry(ZipEntry(name)); zip.write(value); zip.closeEntry() }
        } }.toByteArray()
    }
}

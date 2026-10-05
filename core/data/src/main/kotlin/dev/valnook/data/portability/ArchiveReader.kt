package dev.valnook.data.portability

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.Currency
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class StagedRestore(
    val candidateId: String,
    val sourceName: String,
    val packageFile: File,
    val packageSha256: String,
    val extractedDirectory: File,
    val stagingDatabaseName: String,
    val stagingDatabase: ValnookDatabase,
    val manifest: ArchiveManifest,
    val totalRecordCount: Long,
    val auditCoverageStartMs: Long?,
    val auditCoverageComplete: Boolean,
    val generationAtPreview: Long,
    val workDirectory: File
)

internal class ArchiveReader(
    private val context: android.content.Context,
    private val readerBuild: AppBuildInfo,
    private val limits: BackupReadLimits,
    private val workRoot: File
) {
    suspend fun stage(
        packageFile: File,
        packageSha256: String,
        sourceName: String,
        generationAtPreview: Long
    ): StagedRestore {
        if (packageFile.length() > limits.maxArchiveBytes) limit()
        val work = File(workRoot, "read-${UUID.randomUUID()}").also(BackupSnapshotWriter::secureDirectory)
        val extracted = File(work, "extracted").also(BackupSnapshotWriter::secureDirectory)
        var staging: ValnookDatabase? = null
        var stagingName: String? = null
        try {
            extract(packageFile, extracted)
            val manifestFile = BackupSnapshotWriter.safeFile(extracted, "manifest.json")
            val manifest = ArchiveManifest.parse(readTextBounded(manifestFile, limits.maxManifestBytes), limits)
            CompatibilityRules.validate(manifest, readerBuild)
            val expectedPayloadPaths = BackupContract.payloadPathsFor(manifest.dataSchemaVersion)
            if (manifest.files.map { it.path }.toSet() != expectedPayloadPaths) {
                throw PortabilityException(PortabilityErrorCode.MISSING_FILE)
            }
            var totalRecords = 0L
            manifest.files.forEach { declared ->
                val file = BackupSnapshotWriter.safeFile(extracted, declared.path)
                if (file.length() != declared.uncompressedBytes) mismatch()
                if (BackupSnapshotWriter.sha256(file) != declared.sha256) {
                    throw PortabilityException(PortabilityErrorCode.HASH_MISMATCH)
                }
                totalRecords = checkedAdd(totalRecords, declared.recordCount)
            }
            if (totalRecords > limits.maxRecords) limit()

            stagingName = "valnook-restore-${UUID.randomUUID()}.db"
            staging = ValnookDatabase.staging(context, stagingName)
            // Force schema creation before loading the isolated candidate.
            staging.openHelper.writableDatabase
            val counts = loadCandidate(staging, extracted, manifest.dataSchemaVersion)
            manifest.files.forEach { declared ->
                if (counts[declared.path] != declared.recordCount) {
                    throw PortabilityException(PortabilityErrorCode.RECORD_COUNT_MISMATCH)
                }
            }
            validateCandidate(staging, extracted, manifest, counts)
            val metadata = staging.audit().metadata()
            return StagedRestore(
                candidateId = UUID.randomUUID().toString(),
                sourceName = displayName(sourceName),
                packageFile = packageFile,
                packageSha256 = packageSha256,
                extractedDirectory = extracted,
                stagingDatabaseName = stagingName,
                stagingDatabase = staging,
                manifest = manifest,
                totalRecordCount = totalRecords,
                auditCoverageStartMs = metadata.tracking_start_ms,
                auditCoverageComplete = metadata.complete_since_start && !metadata.legacy_history_before_start,
                generationAtPreview = generationAtPreview,
                workDirectory = work
            )
        } catch (error: Exception) {
            staging?.close()
            stagingName?.let(context::deleteDatabase)
            work.deleteRecursively()
            packageFile.parentFile?.takeIf { it.name.startsWith("input-") }?.deleteRecursively()
            if (error is CancellationException) throw error
            throw mapError(error)
        }
    }

    fun close(staged: StagedRestore) {
        staged.stagingDatabase.close()
        context.deleteDatabase(staged.stagingDatabaseName)
        staged.workDirectory.deleteRecursively()
        staged.packageFile.parentFile?.takeIf { it.name.startsWith("input-") }?.deleteRecursively()
    }

    private fun extract(source: File, root: File) {
        val seen = linkedSetOf<String>()
        var totalBytes = 0L
        var entries = 0
        try {
            ZipInputStream(FileInputStream(source), Charsets.UTF_8).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries++
                    if (entries > limits.maxEntries) limit()
                    val name = entry.name
                    if (entry.isDirectory || name !in BackupContract.zipPaths) {
                        throw PortabilityException(PortabilityErrorCode.UNEXPECTED_FILE)
                    }
                    if (!seen.add(name)) throw PortabilityException(PortabilityErrorCode.DUPLICATE_ENTRY)
                    if (entry.method != ZipEntry.DEFLATED && entry.method != ZipEntry.STORED) invalid()
                    val maxEntry = if (name == "manifest.json") limits.maxManifestBytes else limits.maxPayloadBytes
                    val output = BackupSnapshotWriter.safeFile(root, name)
                    output.parentFile?.let(BackupSnapshotWriter::secureDirectory)
                    var entryBytes = 0L
                    FileOutputStream(output).use { sink ->
                        val buffer = ByteArray(COPY_BUFFER_SIZE)
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            entryBytes = checkedAdd(entryBytes, read.toLong())
                            totalBytes = checkedAdd(totalBytes, read.toLong())
                            if (entryBytes > maxEntry || totalBytes > limits.maxExpandedBytes) limit()
                            sink.write(buffer, 0, read)
                        }
                    }
                    zip.closeEntry()
                }
            }
        } catch (error: ZipException) {
            throw PortabilityException(PortabilityErrorCode.INVALID_ARCHIVE, error)
        }
        if ("manifest.json" !in seen) throw PortabilityException(PortabilityErrorCode.MISSING_FILE)
    }

    private suspend fun loadCandidate(db: ValnookDatabase, root: File, dataSchemaVersion: Int): Map<String, Long> {
        val counts = linkedMapOf<String, Long>()
        db.withTransaction {
            counts["data/currencies.json"] = loadCurrencies(db, root)
            BackupContract.tablesFor(dataSchemaVersion).sortedBy { BackupContract.importOrder.indexOf(it.table) }.forEach { table ->
                counts[table.path] = loadTable(db, root, table)
            }
            if (dataSchemaVersion < 3) dev.valnook.data.database.initializeAccountDisplayOrder(db.openHelper.writableDatabase)
            counts["data/valuation_baselines.jsonl"] = loadValuationBaselines(db, root)
            counts["data/audit_metadata.json"] = loadAuditMetadata(db, root)
            counts.putAll(loadPreferences(db, root))
            counts["verification/snapshot_totals.json"] = 1L
            val now = System.currentTimeMillis()
            val local = ContentValues().apply {
                put("id", 1)
                put("dataset_generation", 1)
                put("maintenance_in_progress", 0)
                putNull("last_restore_attempt_id")
                putNull("last_restore_backup_sha256")
                putNull("last_restore_committed_at_ms")
                putNull("upload_pause_reason")
                put("updated_at_ms", now)
            }
            insert(db, "local_maintenance_state", local)
        }
        return counts
    }

    private fun loadCurrencies(db: ValnookDatabase, root: File): Long {
        val file = BackupSnapshotWriter.safeFile(root, "data/currencies.json")
        val list = PortableJson.parse(readTextBounded(file, limits.maxPayloadBytes), limits.maxJsonDepth) as? List<*>
            ?: invalid()
        val seen = mutableSetOf<String>()
        list.forEach { item ->
            val value = item as? Map<*, *> ?: invalid()
            if (value.keys != setOf("code", "fractionDigits")) invalid()
            val code = value["code"] as? String ?: invalid()
            val digitsRaw = value["fractionDigits"] as? String ?: invalid()
            val digits = digitsRaw.toIntOrNull() ?: invalid()
            if (!seen.add(code) || code !in Currency.supported.map { it.code } ||
                Currency.of(code).fraction_digits != digits) invalid()
            insert(db, "currencies", ContentValues().apply {
                put("code", code)
                put("fraction_digits", digits)
            })
        }
        if (seen != Currency.supported.mapTo(mutableSetOf()) { it.code }) invalid()
        return list.size.toLong()
    }

    private fun loadTable(db: ValnookDatabase, root: File, table: PortableTable): Long {
        val file = BackupSnapshotWriter.safeFile(root, table.path)
        var count = 0L
        readLinesBounded(file) { line ->
            insert(db, table.table, PortableJson.parseRow(line, table, limits.maxJsonDepth))
            count++
            if (count > limits.maxRecords) limit()
        }
        return count
    }

    private fun loadValuationBaselines(db: ValnookDatabase, root: File): Long {
        var count = 0L
        readLinesBounded(BackupSnapshotWriter.safeFile(root, "data/valuation_baselines.jsonl")) { line ->
            val value = PortableJson.parse(line, limits.maxJsonDepth) as? Map<*, *> ?: invalid()
            when (value["recordType"] as? String ?: invalid()) {
                "STATE" -> {
                    requireKeys(value, setOf("recordType", "id", "sourceRevision", "ruleVersion",
                        "baselineAtMs", "earliestInvalidatedEpochDay"))
                    insert(db, "statistics_state", ContentValues().apply {
                        put("id", long(value, "id"))
                        put("source_revision", long(value, "sourceRevision"))
                        put("rule_version", long(value, "ruleVersion"))
                        put("baseline_at_ms", long(value, "baselineAtMs"))
                        nullableLong(this, "earliest_invalidated_epoch_day", value["earliestInvalidatedEpochDay"])
                    })
                }
                "ITEM" -> {
                    requireKeys(value, setOf("recordType", "itemKind", "referenceId", "accountId",
                        "instrumentId", "currencyCode", "amountLong", "secondaryLong"))
                    insert(db, "statistics_baseline_items", ContentValues().apply {
                        put("item_kind", value["itemKind"] as? String ?: invalid())
                        put("reference_id", long(value, "referenceId"))
                        nullableLong(this, "account_id", value["accountId"])
                        nullableLong(this, "instrument_id", value["instrumentId"])
                        put("currency_code", value["currencyCode"] as? String ?: invalid())
                        put("amount_long", long(value, "amountLong"))
                        nullableLong(this, "secondary_long", value["secondaryLong"])
                    })
                }
                else -> invalid()
            }
            count++
        }
        return count
    }

    private fun loadAuditMetadata(db: ValnookDatabase, root: File): Long {
        val value = PortableJson.parse(readTextBounded(
            BackupSnapshotWriter.safeFile(root, "data/audit_metadata.json"), limits.maxPayloadBytes),
            limits.maxJsonDepth) as? Map<*, *> ?: invalid()
        requireKeys(value, setOf("protocolVersion", "trackingStartMs", "trackingStartDatabaseVersion",
            "completeSinceStart", "legacyHistoryBeforeStart"))
        if (number(value, "protocolVersion") != 1L) incompatible()
        insert(db, "audit_metadata", ContentValues().apply {
            put("id", 1)
            put("protocol_version", 1)
            put("tracking_start_ms", long(value, "trackingStartMs"))
            put("tracking_start_database_version", number(value, "trackingStartDatabaseVersion"))
            put("complete_since_start", bool(value, "completeSinceStart"))
            put("legacy_history_before_start", bool(value, "legacyHistoryBeforeStart"))
        })
        return 1
    }

    private fun loadPreferences(db: ValnookDatabase, root: File): Map<String, Long> {
        val financial = PortableJson.parse(readTextBounded(
            BackupSnapshotWriter.safeFile(root, "preferences/financial.json"), limits.maxPayloadBytes),
            limits.maxJsonDepth) as? Map<*, *> ?: invalid()
        val appearance = PortableJson.parse(readTextBounded(
            BackupSnapshotWriter.safeFile(root, "preferences/appearance.json"), limits.maxPayloadBytes),
            limits.maxJsonDepth) as? Map<*, *> ?: invalid()
        requireKeys(financial, setOf("settingsPresent", "id", "baseCurrency", "revision", "rates"))
        requireKeys(appearance, setOf("settingsPresent", "language", "gainLossScheme", "navigationOrder", "navigationVisible"))
        val present = financial["settingsPresent"] as? Boolean ?: invalid()
        if (present != (appearance["settingsPresent"] as? Boolean ?: invalid())) invalid()
        if (present) {
            val navigationOrder = appearance["navigationOrder"] as? String ?: invalid()
            val navigationVisible = appearance["navigationVisible"] as? String ?: invalid()
            validateNavigation(navigationOrder, navigationVisible)
            insert(db, "app_settings", ContentValues().apply {
                put("id", stringLong(financial["id"]))
                val base = financial["baseCurrency"]
                if (base == null) putNull("base_currency") else put("base_currency", base as? String ?: invalid())
                put("revision", stringLong(financial["revision"]))
                put("language", enumValue(appearance, "language", setOf("SYSTEM", "ZH_HANS", "ENGLISH")))
                put("gain_loss_scheme", enumValue(appearance, "gainLossScheme", setOf("GREEN_GAIN", "RED_GAIN")))
                put("navigation_order", navigationOrder)
                put("navigation_visible", navigationVisible)
            })
        }
        val rates = financial["rates"] as? List<*> ?: invalid()
        val pairs = mutableSetOf<Pair<String, String>>()
        rates.forEach { item ->
            val rate = item as? Map<*, *> ?: invalid()
            requireKeys(rate, setOf("source_currency", "target_currency", "rate", "updated_at_ms"))
            val source = rate["source_currency"] as? String ?: invalid()
            val target = rate["target_currency"] as? String ?: invalid()
            val decimal = (rate["rate"] as? String ?: invalid()).toBigDecimalOrNull() ?: invalid()
            if (!pairs.add(source to target) || decimal.signum() <= 0 || decimal.precision() > 40 ||
                decimal.stripTrailingZeros().scale() > 12) invalid()
            insert(db, "fx_rates", ContentValues().apply {
                put("source_currency", source)
                put("target_currency", target)
                put("rate", decimal.stripTrailingZeros().toPlainString())
                put("updated_at_ms", stringLong(rate["updated_at_ms"]))
            })
        }
        return mapOf("preferences/financial.json" to 1L, "preferences/appearance.json" to 1L)
    }

    private suspend fun validateCandidate(
        db: ValnookDatabase,
        root: File,
        manifest: ArchiveManifest,
        counts: Map<String, Long>
    ) {
        val sql = db.openHelper.readableDatabase
        sql.query("PRAGMA foreign_key_check").use { if (it.moveToFirst()) relationship() }
        sql.query("SELECT 1 FROM savings_accounts WHERE display_order<0 LIMIT 1")
            .use { if (it.moveToFirst()) invalid() }
        sql.query("SELECT 1 FROM cash_accounts WHERE display_order<0 LIMIT 1")
            .use { if (it.moveToFirst()) invalid() }
        validateCreditAccounts(sql)
        if (counts["data/accounts.jsonl"] != manifest.accountCount ||
            counts["data/cash_accounts.jsonl"] != manifest.cashAccountCount ||
            counts["data/investment_trades.jsonl"] != manifest.tradeCount ||
            counts["data/audit_events.jsonl"] != manifest.auditEventCount) summaryMismatch()
        sql.query("SELECT 1 FROM operations WHERE result_kind IS NULL OR result_id IS NULL LIMIT 1")
            .use { if (it.moveToFirst()) invalid() }
        sql.query("""SELECT 1 FROM investments p WHERE p.opening_quantity_e8!=0 OR p.opening_cost_price_e8 IS NOT NULL
            OR p.opening_at_ms!=0 OR p.algorithm_version!=${BackupContract.POSITION_COST_RULE} LIMIT 1""")
            .use { if (it.moveToFirst()) incompatible() }
        validatePositionCosts(sql)
        sql.query("""SELECT 1 FROM term_deposits WHERE status NOT IN ('OPEN','CLOSED')
            OR start_epoch_day>end_epoch_day OR principal_minor<=0 OR annual_rate_percent_e8<0
            OR (status='OPEN' AND (close_operation_id IS NOT NULL OR closed_at_ms IS NOT NULL))
            OR (status='CLOSED' AND (close_operation_id IS NULL OR closed_at_ms IS NULL)) LIMIT 1""")
            .use { if (it.moveToFirst()) invalid() }
        sql.query("""SELECT before_json,after_json,changed_fields_json,cash_effects_json FROM audit_events""")
            .use { cursor -> while (cursor.moveToNext()) {
                listOf(0, 1, 2, 3).forEach { index ->
                    if (!cursor.isNull(index)) PortableJson.parse(cursor.getString(index), limits.maxJsonDepth)
                }
            } }
        val totals = PortableJson.parse(readTextBounded(
            BackupSnapshotWriter.safeFile(root, "verification/snapshot_totals.json"), limits.maxPayloadBytes),
            limits.maxJsonDepth) as? Map<*, *> ?: invalid()
        requireKeys(totals, setOf("snapshotId", "snapshotAtUtc", "recordCounts", "cashBalanceMinorByCurrency"))
        if (totals["snapshotId"] != manifest.snapshotId || totals["snapshotAtUtc"] != manifest.snapshotAtUtc) summaryMismatch()
        val declaredCounts = totals["recordCounts"] as? Map<*, *> ?: invalid()
        counts.filterKeys { it != "verification/snapshot_totals.json" }.forEach { (path, count) ->
            if (declaredCounts[path] != count.toString()) summaryMismatch()
        }
        val declaredCash = totals["cashBalanceMinorByCurrency"] as? Map<*, *> ?: invalid()
        val actualCash = linkedMapOf<String, String>()
        sql.query("SELECT currency_code,COALESCE(SUM(balance_minor),0) FROM cash_accounts GROUP BY currency_code ORDER BY currency_code")
            .use { cursor -> while (cursor.moveToNext()) actualCash[cursor.getString(0)] = cursor.getLong(1).toString() }
        if (declaredCash != actualCash) summaryMismatch()
        val state = db.statistics().state()
        if (state != null && state.rule_version != BackupContract.HISTORICAL_VALUATION_RULE) incompatible()
    }

    private fun validateCreditAccounts(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("""SELECT 1 FROM credit_account_profiles p
            JOIN cash_accounts a ON a.id=p.account_id
            LEFT JOIN credit_account_profiles source ON source.account_id=p.limit_source_account_id
            LEFT JOIN cash_accounts source_account ON source_account.id=p.limit_source_account_id
            WHERE p.statement_day NOT BETWEEN 1 AND 31
               OR p.due_rule_type NOT IN ('AFTER_STATEMENT_DAYS','FIXED_DAY_OF_MONTH')
               OR (p.due_rule_type='AFTER_STATEMENT_DAYS' AND p.due_rule_value NOT BETWEEN 1 AND 365)
               OR (p.due_rule_type='FIXED_DAY_OF_MONTH' AND p.due_rule_value NOT BETWEEN 1 AND 31)
               OR (p.limit_source_account_id IS NULL AND (p.credit_limit_minor IS NULL OR p.credit_limit_minor<=0))
               OR (p.limit_source_account_id IS NOT NULL AND p.credit_limit_minor IS NOT NULL)
               OR p.limit_source_account_id=p.account_id
               OR (p.limit_source_account_id IS NOT NULL AND source.account_id IS NULL)
               OR (p.limit_source_account_id IS NOT NULL AND source.limit_source_account_id IS NOT NULL)
               OR (p.limit_source_account_id IS NOT NULL AND (source.credit_limit_minor IS NULL OR source.credit_limit_minor<=0))
               OR (p.limit_source_account_id IS NOT NULL AND source_account.savings_account_id!=a.savings_account_id)
               OR (p.limit_source_account_id IS NOT NULL AND source_account.currency_code!=a.currency_code)
            LIMIT 1""").use { if (it.moveToFirst()) relationship() }
        db.query("""SELECT 1 FROM credit_account_profiles root
            JOIN credit_account_profiles child ON child.limit_source_account_id=root.account_id
            WHERE root.limit_source_account_id IS NOT NULL LIMIT 1""")
            .use { if (it.moveToFirst()) relationship() }
    }

    private fun validatePositionCosts(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("""SELECT p.id,p.holding_quantity_e8,p.remaining_cost,p.realized_profit,
            p.chronology_valid,p.position_state,c.fraction_digits,t.id,t.direction,t.quantity_e8,
            t.amount_minor,t.fee_minor,t.is_deleted
            FROM investments p JOIN instruments i ON i.id=p.instrument_id
            JOIN currencies c ON c.code=i.currency_code
            LEFT JOIN investment_trades t ON t.investment_id=p.id
            ORDER BY p.id,t.occurred_at_ms,t.id""").use { cursor ->
            var positionId = Long.MIN_VALUE
            var declaredHolding = 0L
            var declaredCost: BigDecimal? = null
            var declaredRealized: BigDecimal? = null
            var declaredValid = false
            var declaredState = ""
            var fractionDigits = 0
            var quantity = 0L
            var remaining = BigDecimal.ZERO
            var realized = BigDecimal.ZERO
            var hasTrade = false
            fun finish() {
                if (positionId == Long.MIN_VALUE) return
                val expectedState = if (quantity > 0) "HOLDING" else if (hasTrade) "CLOSED" else "PENDING"
                if (!declaredValid || quantity != declaredHolding || declaredCost == null || declaredRealized == null ||
                    remaining.compareTo(declaredCost) != 0 || realized.compareTo(declaredRealized) != 0 ||
                    declaredState != expectedState) summaryMismatch()
            }
            while (cursor.moveToNext()) {
                val current = cursor.getLong(0)
                if (current != positionId) {
                    finish()
                    positionId = current
                    declaredHolding = cursor.getLong(1)
                    declaredCost = portableDecimal(if (cursor.isNull(2)) null else cursor.getString(2))
                    declaredRealized = portableDecimal(if (cursor.isNull(3)) null else cursor.getString(3))
                    declaredValid = cursor.getInt(4) != 0
                    declaredState = cursor.getString(5)
                    fractionDigits = cursor.getInt(6)
                    quantity = 0L
                    remaining = BigDecimal.ZERO
                    realized = BigDecimal.ZERO
                    hasTrade = false
                }
                if (cursor.isNull(7) || cursor.getInt(12) != 0) continue
                hasTrade = true
                val direction = cursor.getString(8)
                val tradeQuantity = cursor.getLong(9)
                if (tradeQuantity <= 0) invalid()
                val amount = BigDecimal.valueOf(cursor.getLong(10), fractionDigits)
                val fee = BigDecimal.valueOf(cursor.getLong(11), fractionDigits)
                if (direction == "BUY") {
                    quantity = checkedAdd(quantity, tradeQuantity)
                    remaining = remaining.add(amount).add(fee)
                } else if (direction == "SELL") {
                    if (quantity <= 0 || tradeQuantity > quantity) invalid()
                    val allocated = if (tradeQuantity == quantity) remaining else remaining
                        .multiply(BigDecimal.valueOf(tradeQuantity))
                        .divide(BigDecimal.valueOf(quantity), 32, RoundingMode.HALF_UP)
                    realized = realized.add(amount.subtract(fee).subtract(allocated))
                    quantity -= tradeQuantity
                    remaining = if (quantity == 0L) BigDecimal.ZERO else remaining.subtract(allocated)
                } else invalid()
            }
            finish()
        }
    }

    private fun portableDecimal(raw: String?): BigDecimal? {
        if (raw == null || raw.length > 128) return null
        return raw.toBigDecimalOrNull()?.takeIf { it.precision() <= 40 } ?: invalid()
    }

    private fun readLinesBounded(file: File, action: (String) -> Unit) {
        FileInputStream(file).use { input ->
            val line = ByteArrayOutputStream()
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                for (index in 0 until read) {
                    val byte = buffer[index]
                    if (byte == '\n'.code.toByte()) {
                        if (line.size() > 0) action(decodeUtf8(line.toByteArray()).removeSuffix("\r"))
                        line.reset()
                    } else {
                        line.write(byte.toInt())
                        if (line.size() > limits.maxJsonLineBytes) limit()
                    }
                }
            }
            if (line.size() > 0) action(decodeUtf8(line.toByteArray()).removeSuffix("\r"))
        }
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (error: Exception) {
        throw PortabilityException(PortabilityErrorCode.INVALID_DATA, error)
    }

    private fun readTextBounded(file: File, maxBytes: Long): String {
        if (file.length() > maxBytes || file.length() > Int.MAX_VALUE) limit()
        return decodeUtf8(file.readBytes())
    }

    private fun insert(db: ValnookDatabase, table: String, values: ContentValues) {
        val result = db.openHelper.writableDatabase.insert(table, SQLiteDatabase.CONFLICT_ABORT, values)
        if (result == -1L) relationship()
    }

    private fun validateNavigation(order: String, visible: String) {
        val all = setOf("ACCOUNTS", "INVESTMENTS", "STATISTICS", "SETTINGS")
        val orderValues = order.split(',')
        val visibleValues = visible.split(',').filter(String::isNotBlank)
        if (orderValues.toSet() != all || orderValues.size != all.size ||
            visibleValues.any { it !in all } || visibleValues.toSet().size != visibleValues.size ||
            "SETTINGS" !in visibleValues) invalid()
    }

    private fun enumValue(map: Map<*, *>, key: String, allowed: Set<String>): String {
        val value = map[key] as? String ?: invalid()
        if (value !in allowed) invalid()
        return value
    }

    private fun long(map: Map<*, *>, key: String): Long = stringLong(map[key])
    private fun stringLong(value: Any?): Long {
        val raw = value as? String ?: invalid()
        if (!LONG.matches(raw)) invalid()
        return raw.toLongOrNull() ?: invalid()
    }
    private fun number(map: Map<*, *>, key: String): Long =
        ((map[key] as? PortableJson.JsonNumber)?.raw ?: invalid()).toLongOrNull() ?: invalid()
    private fun bool(map: Map<*, *>, key: String): Int = if (map[key] as? Boolean ?: invalid()) 1 else 0
    private fun nullableLong(values: ContentValues, key: String, value: Any?) {
        if (value == null) values.putNull(key) else values.put(key, stringLong(value))
    }
    private fun requireKeys(value: Map<*, *>, expected: Set<String>) { if (value.keys != expected) invalid() }
    private fun displayName(value: String): String = value.substringAfterLast('/').substringAfterLast('\\').take(200)
    private fun checkedAdd(left: Long, right: Long): Long = try { Math.addExact(left, right) } catch (_: ArithmeticException) { limit() }
    private fun mapError(error: Exception): Exception = when (error) {
        is PortabilityException -> error
        is java.io.IOException -> PortabilityException(PortabilityErrorCode.INVALID_ARCHIVE, error)
        else -> PortabilityException(PortabilityErrorCode.INVALID_DATA, error)
    }
    private fun invalid(): Nothing = throw PortabilityException(PortabilityErrorCode.INVALID_DATA)
    private fun relationship(): Nothing = throw PortabilityException(PortabilityErrorCode.RELATIONSHIP_ERROR)
    private fun mismatch(): Nothing = throw PortabilityException(PortabilityErrorCode.HASH_MISMATCH)
    private fun summaryMismatch(): Nothing = throw PortabilityException(PortabilityErrorCode.SUMMARY_MISMATCH)
    private fun incompatible(): Nothing = throw PortabilityException(PortabilityErrorCode.INCOMPATIBLE_VERSION)
    private fun limit(): Nothing = throw PortabilityException(PortabilityErrorCode.LIMIT_EXCEEDED)

    private companion object {
        val LONG = Regex("-?(0|[1-9][0-9]*)")
        const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

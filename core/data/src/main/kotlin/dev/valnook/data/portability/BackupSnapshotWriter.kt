package dev.valnook.data.portability

import androidx.room.withTransaction
import dev.valnook.data.audit.AuditRecorder
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.portability.PortabilityErrorCode
import dev.valnook.domain.portability.PortabilityException
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal data class SnapshotPackage(
    val archive: File,
    val manifest: ArchiveManifest,
    val recordCount: Long,
    val packageSha256: String
)

internal class BackupSnapshotWriter(
    private val db: ValnookDatabase,
    private val clock: Clock,
    private val buildInfo: AppBuildInfo,
    private val workRoot: File,
    private val limits: BackupReadLimits
) {
    suspend fun create(backupId: String): SnapshotPackage {
        runCatching { UUID.fromString(backupId) }.getOrElse {
            throw PortabilityException(PortabilityErrorCode.INVALID_DATA, it)
        }
        val jobDir = File(workRoot, "write-${UUID.randomUUID()}").also { secureDirectory(it) }
        val payloadDir = File(jobDir, "payload").also { secureDirectory(it) }
        val snapshotAt = clock.instant()
        val snapshotId = UUID.randomUUID().toString()
        val fileRecords = linkedMapOf<String, Long>()
        try {
            db.withTransaction {
                BackupContract.tables.forEach { table ->
                    fileRecords[table.path] = writeTable(payloadDir, table)
                }
                fileRecords["data/currencies.json"] = writeCurrencies(payloadDir)
                fileRecords["data/valuation_baselines.jsonl"] = writeValuationBaselines(payloadDir)
                fileRecords["data/audit_metadata.json"] = writeAuditMetadata(payloadDir)
                val settingCounts = writePreferences(payloadDir)
                fileRecords.putAll(settingCounts)
                fileRecords["verification/snapshot_totals.json"] = writeTotals(payloadDir, fileRecords, snapshotId, snapshotAt)
            }
            val files = BackupContract.payloadPaths.sorted().map { path ->
                val file = safeFile(payloadDir, path)
                if (!file.isFile) throw PortabilityException(PortabilityErrorCode.MISSING_FILE)
                PayloadFileInfo(path, fileRecords[path] ?: 0, file.length(), sha256(file))
            }
            if (files.sumOf { it.uncompressedBytes } > limits.maxExpandedBytes ||
                files.sumOf { it.recordCount } > limits.maxRecords) {
                throw PortabilityException(PortabilityErrorCode.LIMIT_EXCEEDED)
            }
            val createdAt = formatUtc(clock.instant())
            val manifest = ArchiveManifest(
                backupId = backupId,
                createdAtUtc = createdAt,
                snapshotAtUtc = formatUtc(snapshotAt),
                snapshotId = snapshotId,
                accountCount = fileRecords["data/accounts.jsonl"] ?: 0,
                cashAccountCount = fileRecords["data/cash_accounts.jsonl"] ?: 0,
                tradeCount = fileRecords["data/investment_trades.jsonl"] ?: 0,
                auditEventCount = fileRecords["data/audit_events.jsonl"] ?: 0,
                producer = buildInfo,
                files = files,
                requiredFeatures = BackupContract.requiredFeatures
            )
            val manifestFile = File(payloadDir, "manifest.json")
            manifestFile.writeText(manifest.toJson(), Charsets.UTF_8)
            if (manifestFile.length() > limits.maxManifestBytes) {
                throw PortabilityException(PortabilityErrorCode.LIMIT_EXCEEDED)
            }
            val archive = File(jobDir, "package.val_backup")
            zip(payloadDir, archive)
            if (archive.length() > limits.maxArchiveBytes) {
                throw PortabilityException(PortabilityErrorCode.LIMIT_EXCEEDED)
            }
            return SnapshotPackage(archive, manifest, files.sumOf { it.recordCount }, sha256(archive))
        } catch (error: Exception) {
            jobDir.deleteRecursively()
            throw error
        }
    }

    private fun writeTable(root: File, table: PortableTable): Long {
        val target = safeFile(root, table.path)
        target.parentFile?.let(::secureDirectory)
        var count = 0L
        BufferedWriter(OutputStreamWriter(FileOutputStream(target), StandardCharsets.UTF_8)).use { writer ->
            val sql = "SELECT ${table.selectList} FROM ${table.table} ORDER BY ${table.orderBy}"
            db.openHelper.readableDatabase.query(sql).use { cursor ->
                while (cursor.moveToNext()) {
                    val line = PortableJson.row(cursor, table)
                    if (line.toByteArray(Charsets.UTF_8).size > limits.maxJsonLineBytes) {
                        throw PortabilityException(PortabilityErrorCode.LIMIT_EXCEEDED)
                    }
                    writer.write(line)
                    writer.newLine()
                    count++
                }
            }
        }
        return count
    }

    private fun writeCurrencies(root: File): Long {
        val array = JSONArray()
        db.openHelper.readableDatabase.query(
            "SELECT code,fraction_digits FROM currencies ORDER BY code"
        ).use { cursor ->
            while (cursor.moveToNext()) array.put(JSONObject().apply {
                put("code", cursor.getString(0))
                put("fractionDigits", cursor.getLong(1).toString())
            })
        }
        writeJson(root, "data/currencies.json", array.toString())
        return array.length().toLong()
    }

    private fun writeValuationBaselines(root: File): Long {
        val target = safeFile(root, "data/valuation_baselines.jsonl")
        target.parentFile?.let(::secureDirectory)
        var count = 0L
        target.bufferedWriter(Charsets.UTF_8).use { writer ->
            db.openHelper.readableDatabase.query(
                "SELECT id,source_revision,rule_version,baseline_at_ms,earliest_invalidated_epoch_day FROM statistics_state ORDER BY id"
            ).use { cursor -> while (cursor.moveToNext()) {
                val value = JSONObject().apply {
                    put("recordType", "STATE")
                    put("id", cursor.getLong(0).toString())
                    put("sourceRevision", cursor.getLong(1).toString())
                    put("ruleVersion", cursor.getLong(2).toString())
                    put("baselineAtMs", cursor.getLong(3).toString())
                    put("earliestInvalidatedEpochDay", if (cursor.isNull(4)) JSONObject.NULL else cursor.getLong(4).toString())
                }
                writer.appendLine(value.toString())
                count++
            } }
            db.openHelper.readableDatabase.query(
                """SELECT item_kind,reference_id,account_id,instrument_id,currency_code,amount_long,secondary_long
                   FROM statistics_baseline_items ORDER BY item_kind,reference_id"""
            ).use { cursor -> while (cursor.moveToNext()) {
                val value = JSONObject().apply {
                    put("recordType", "ITEM")
                    put("itemKind", cursor.getString(0))
                    put("referenceId", cursor.getLong(1).toString())
                    put("accountId", if (cursor.isNull(2)) JSONObject.NULL else cursor.getLong(2).toString())
                    put("instrumentId", if (cursor.isNull(3)) JSONObject.NULL else cursor.getLong(3).toString())
                    put("currencyCode", cursor.getString(4))
                    put("amountLong", cursor.getLong(5).toString())
                    put("secondaryLong", if (cursor.isNull(6)) JSONObject.NULL else cursor.getLong(6).toString())
                }
                writer.appendLine(value.toString())
                count++
            } }
        }
        return count
    }

    private suspend fun writeAuditMetadata(root: File): Long {
        val metadata = db.audit().metadata()
        writeJson(root, "data/audit_metadata.json", JSONObject().apply {
            put("protocolVersion", metadata.protocol_version)
            put("trackingStartMs", metadata.tracking_start_ms.toString())
            put("trackingStartDatabaseVersion", metadata.tracking_start_database_version)
            put("completeSinceStart", metadata.complete_since_start)
            put("legacyHistoryBeforeStart", metadata.legacy_history_before_start)
        }.toString())
        return 1
    }

    private fun writePreferences(root: File): Map<String, Long> {
        val dbValue = db.openHelper.readableDatabase
        val settings = dbValue.query(
            "SELECT id,base_currency,revision,language,gain_loss_scheme,navigation_order,navigation_visible FROM app_settings WHERE id=1"
        ).use { cursor -> if (cursor.moveToFirst()) AuditRecorder.cursorObject(cursor) else null }
        val rates = JSONArray()
        dbValue.query("SELECT source_currency,target_currency,rate,updated_at_ms FROM fx_rates ORDER BY source_currency,target_currency")
            .use { cursor -> while (cursor.moveToNext()) rates.put(AuditRecorder.cursorObject(cursor)) }
        writeJson(root, "preferences/financial.json", JSONObject().apply {
            put("settingsPresent", settings != null)
            put("id", settings?.opt("id") ?: JSONObject.NULL)
            put("baseCurrency", settings?.opt("base_currency") ?: JSONObject.NULL)
            put("revision", settings?.opt("revision") ?: JSONObject.NULL)
            put("rates", rates)
        }.toString())
        writeJson(root, "preferences/appearance.json", JSONObject().apply {
            put("settingsPresent", settings != null)
            put("language", settings?.opt("language") ?: "SYSTEM")
            put("gainLossScheme", settings?.opt("gain_loss_scheme") ?: "GREEN_GAIN")
            put("navigationOrder", settings?.opt("navigation_order") ?: "ACCOUNTS,WALLET,INVESTMENTS,STATISTICS,SETTINGS")
            put("navigationVisible", settings?.opt("navigation_visible") ?: "ACCOUNTS,WALLET,INVESTMENTS,STATISTICS,SETTINGS")
        }.toString())
        return mapOf("preferences/financial.json" to 1L, "preferences/appearance.json" to 1L)
    }

    private fun writeTotals(
        root: File,
        records: Map<String, Long>,
        snapshotId: String,
        snapshotAt: Instant
    ): Long {
        val cashByCurrency = JSONObject()
        db.openHelper.readableDatabase.query(
            "SELECT currency_code,COALESCE(SUM(balance_minor),0) FROM cash_accounts GROUP BY currency_code ORDER BY currency_code"
        ).use { cursor -> while (cursor.moveToNext()) cashByCurrency.put(cursor.getString(0), cursor.getLong(1).toString()) }
        val counts = JSONObject()
        records.toSortedMap().forEach { (path, count) -> counts.put(path, count.toString()) }
        writeJson(root, "verification/snapshot_totals.json", JSONObject().apply {
            put("snapshotId", snapshotId)
            put("snapshotAtUtc", formatUtc(snapshotAt))
            put("recordCounts", counts)
            put("cashBalanceMinorByCurrency", cashByCurrency)
        }.toString())
        return 1
    }

    private fun writeJson(root: File, path: String, content: String) {
        val target = safeFile(root, path)
        target.parentFile?.let(::secureDirectory)
        target.writeText(content, Charsets.UTF_8)
    }

    private fun zip(root: File, target: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(target)), Charsets.UTF_8).use { zip ->
            BackupContract.zipPaths.sorted().forEach { path ->
                val source = safeFile(root, path)
                zip.putNextEntry(ZipEntry(path).apply { method = ZipEntry.DEFLATED })
                FileInputStream(source).use { it.copyTo(zip, COPY_BUFFER_SIZE) }
                zip.closeEntry()
            }
        }
    }

    companion object {
        fun formatUtc(value: Instant): String = UTC_FORMAT.format(value)

        fun fileName(instant: Instant, backupId: String): String =
            "Valnook_${FILE_FORMAT.format(instant)}_${backupId.take(8)}.val_backup"

        fun sha256(file: File): String = FileInputStream(file).use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(COPY_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        }

        fun safeFile(root: File, path: String): File {
            if (path.startsWith('/') || path.startsWith('\\') || path.contains(':') ||
                path.split('/', '\\').any { it == ".." || it.isEmpty() }) {
                throw PortabilityException(PortabilityErrorCode.INVALID_ARCHIVE)
            }
            val rootPath = root.canonicalFile
            val candidate = File(rootPath, path).canonicalFile
            if (!candidate.path.startsWith(rootPath.path + File.separator)) {
                throw PortabilityException(PortabilityErrorCode.INVALID_ARCHIVE)
            }
            return candidate
        }

        fun secureDirectory(directory: File) {
            if (!directory.exists() && !directory.mkdirs()) {
                throw PortabilityException(PortabilityErrorCode.STORAGE_FULL)
            }
            if (!directory.isDirectory) throw PortabilityException(PortabilityErrorCode.STORAGE_FULL)
        }

        private val UTC_FORMAT = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
        private val FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss'Z'").withZone(ZoneOffset.UTC)
        private const val COPY_BUFFER_SIZE = 64 * 1024
    }
}

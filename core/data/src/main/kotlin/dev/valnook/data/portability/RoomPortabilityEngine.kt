package dev.valnook.data.portability

import android.content.Context
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.portability.*
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class RoomPortabilityEngine(
    context: Context,
    private val db: ValnookDatabase,
    private val clock: Clock,
    private val buildInfo: AppBuildInfo,
    private val limits: BackupReadLimits = BackupReadLimits()
) {
    private val appContext = context.applicationContext
    private val workRoot = File(appContext.cacheDir, "valnook-portability").also(BackupSnapshotWriter::secureDirectory)
    private val snapshotWriter = BackupSnapshotWriter(db, clock, buildInfo, workRoot, limits)
    private val archiveReader = ArchiveReader(appContext, buildInfo, limits, workRoot)
    private val importer = RestoreImporter(db, clock)
    private val spreadsheetWriter = SpreadsheetWriter(db, clock, buildInfo, workRoot)

    fun buildInfo(): AppBuildInfo = buildInfo

    suspend fun generation(): Long = db.audit().generation()

    suspend fun createBackup(
        backupId: String,
        output: OutputStream,
        progress: (PortabilityProgress) -> Unit
    ): PortableFileResult {
        progress(PortabilityProgress(PortabilityStage.PREPARING_SNAPSHOT))
        val value = snapshotWriter.create(backupId)
        try {
            progress(PortabilityProgress(PortabilityStage.VALIDATING_PACKAGE, value.recordCount))
            val check = archiveReader.stage(value.archive, value.packageSha256, "self-check.val_backup", generation())
            archiveReader.close(check)
            progress(PortabilityProgress(PortabilityStage.COPYING_OUTPUT, value.recordCount))
            value.archive.inputStream().use { it.copyTo(output, COPY_BUFFER_SIZE) }
            output.flush()
            progress(PortabilityProgress(PortabilityStage.COMPLETE, value.recordCount))
            return PortableFileResult(
                fileName = BackupSnapshotWriter.fileName(clock.instant(), backupId),
                byteCount = value.archive.length(),
                sha256 = value.packageSha256,
                recordCount = value.recordCount,
                createdAtUtc = value.manifest.createdAtUtc,
                outputVerified = false
            )
        } catch (error: PortabilityException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw PortabilityException(PortabilityErrorCode.OUTPUT_UNAVAILABLE, error)
        } finally {
            value.archive.parentFile?.deleteRecursively()
        }
    }

    suspend fun prepareRestore(
        input: InputStream,
        sourceName: String,
        progress: (PortabilityProgress) -> Unit
    ): StagedRestore {
        progress(PortabilityProgress(PortabilityStage.COPYING_INPUT))
        val inputDir = File(workRoot, "input-${UUID.randomUUID()}").also(BackupSnapshotWriter::secureDirectory)
        val packageFile = File(inputDir, "candidate.val_backup")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytes = 0L
        try {
            FileOutputStream(packageFile).use { output ->
                val buffer = ByteArray(COPY_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    bytes = try { Math.addExact(bytes, read.toLong()) } catch (_: ArithmeticException) { limit() }
                    if (bytes > limits.maxArchiveBytes) limit()
                    if (appContext.cacheDir.usableSpace < maxOf(MINIMUM_FREE_SPACE, bytes * 2)) {
                        throw PortabilityException(PortabilityErrorCode.STORAGE_FULL)
                    }
                    digest.update(buffer, 0, read)
                    output.write(buffer, 0, read)
                }
            }
            if (bytes == 0L) throw PortabilityException(PortabilityErrorCode.INVALID_ARCHIVE)
            progress(PortabilityProgress(PortabilityStage.VALIDATING_INPUT))
            return archiveReader.stage(packageFile,
                digest.digest().joinToString("") { "%02x".format(it) }, sourceName, generation())
        } catch (error: PortabilityException) {
            inputDir.deleteRecursively()
            throw error
        } catch (cancelled: CancellationException) {
            inputDir.deleteRecursively()
            throw cancelled
        } catch (error: Exception) {
            inputDir.deleteRecursively()
            throw PortabilityException(PortabilityErrorCode.INPUT_UNAVAILABLE, error)
        }
    }

    suspend fun commitRestore(staged: StagedRestore, progress: (PortabilityProgress) -> Unit): RestoreResult {
        progress(PortabilityProgress(PortabilityStage.RESTORING, staged.totalRecordCount))
        val commit = importer.commit(staged, staged.generationAtPreview)
        progress(PortabilityProgress(PortabilityStage.COMPLETE, staged.totalRecordCount))
        return RestoreResult(commit.attemptId, commit.datasetGeneration, commit.committedAtUtc)
    }

    fun close(staged: StagedRestore) = archiveReader.close(staged)

    suspend fun exportWorkbook(
        reportId: String,
        language: AppLanguage,
        demo: Boolean,
        output: OutputStream,
        progress: (PortabilityProgress) -> Unit
    ): PortableFileResult {
        progress(PortabilityProgress(PortabilityStage.PREPARING_SNAPSHOT))
        val workbook = spreadsheetWriter.create(reportId, language, demo)
        try {
            progress(PortabilityProgress(PortabilityStage.COPYING_OUTPUT, workbook.recordCount))
            workbook.file.inputStream().use { it.copyTo(output, COPY_BUFFER_SIZE) }
            output.flush()
            progress(PortabilityProgress(PortabilityStage.COMPLETE, workbook.recordCount))
            return PortableFileResult(workbook.fileName, workbook.file.length(), workbook.sha256,
                workbook.recordCount, workbook.createdAtUtc, outputVerified = false)
        } catch (error: PortabilityException) {
            throw error
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            throw PortabilityException(PortabilityErrorCode.OUTPUT_UNAVAILABLE, error)
        } finally {
            workbook.file.parentFile?.deleteRecursively()
        }
    }

    fun preview(staged: StagedRestore): RestorePreview = RestorePreview(
        candidateId = staged.candidateId,
        sourceName = staged.sourceName,
        packageSha256 = staged.packageSha256,
        backupId = staged.manifest.backupId,
        createdAtUtc = staged.manifest.createdAtUtc,
        appVersion = staged.manifest.producer.appVersion,
        appVersionCode = staged.manifest.producer.appVersionCode,
        internalBuildRevision = staged.manifest.producer.internalBuildRevision,
        databaseSchemaVersion = staged.manifest.producer.databaseSchemaVersion,
        formatVersion = BackupContract.FORMAT_VERSION,
        dataSchemaVersion = staged.manifest.dataSchemaVersion,
        accountCount = staged.manifest.accountCount,
        cashAccountCount = staged.manifest.cashAccountCount,
        tradeCount = staged.manifest.tradeCount,
        totalRecordCount = staged.totalRecordCount,
        auditEventCount = staged.manifest.auditEventCount,
        auditCoverageStartUtc = staged.auditCoverageStartMs?.let { UTC.format(Instant.ofEpochMilli(it)) },
        auditCoverageComplete = staged.auditCoverageComplete,
        warnings = buildList {
            add("OVERWRITES_ALL_FINANCIAL_AND_DISPLAY_DATA")
            add("UNENCRYPTED_ARCHIVE")
            if (!staged.auditCoverageComplete) add("LEGACY_AUDIT_HISTORY_INCOMPLETE")
        }
    )

    private fun limit(): Nothing = throw PortabilityException(PortabilityErrorCode.LIMIT_EXCEEDED)

    private companion object {
        const val COPY_BUFFER_SIZE = 64 * 1024
        const val MINIMUM_FREE_SPACE = 16L * 1024L * 1024L
        val UTC: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)
    }
}

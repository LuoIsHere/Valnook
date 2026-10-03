package dev.valnook.app.di

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.StagedRestore
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.OperationResult
import dev.valnook.domain.repository.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class DataMode { REAL, DEMO }

data class ActiveSession(val id: String, val mode: DataMode, val graph: AppGraph)

@Singleton
class AppSessionManager @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val realDatabaseGraph: DatabaseGraph,
    private val realDatabase: ValnookDatabase,
    private val clock: Clock,
    private val cloudCoordinator: dev.valnook.domain.cloud.CloudBackupService =
        dev.valnook.domain.cloud.UnavailableCloudBackupService,
    val googleAuthorization: dev.valnook.feature.backup.GoogleDriveAuthorization =
        dev.valnook.feature.backup.UnavailableGoogleDriveAuthorization
) {
    /** Single non-reentrant boundary for writes, switches, and destructive maintenance. */
    private val writeMutex = Mutex()
    private var demoDatabase: ValnookDatabase? = null
    private var demoDatabaseName: String? = null
    private var pendingClear: PendingClear? = null
    private var pendingRestore: PendingRestore? = null
    private var activeFileJob: ActiveFileJob? = null
    private val initialId = UUID.randomUUID().toString()
    @Volatile private var currentSessionId = initialId
    private val mutable = MutableStateFlow(ActiveSession(initialId, DataMode.REAL,
        bind(realDatabaseGraph, initialId, DataMode.REAL)))
    val session = mutable.asStateFlow()
    fun observeCloudState() = cloudCoordinator.observeState()
    suspend fun consumeCloudBanner(eventId: String) = cloudCoordinator.consumeBanner(eventId)
    suspend fun reconcileCloudSchedule() = cloudCoordinator.reconcileSchedule()

    suspend fun enterDemo() = writeMutex.withLock {
        if (mutable.value.mode == DataMode.DEMO) return@withLock
        if (activeFileJob != null) throw dev.valnook.domain.portability.PortabilityException(
            dev.valnook.domain.portability.PortabilityErrorCode.JOB_IN_PROGRESS)
        cancelPendingRestoreLocked()
        pendingClear = null
        val displayPreferences = realDatabaseGraph.settings.observeSettings().first()
        clearAbandonedDemoDatabases()
        val databaseName = "$DEMO_DATABASE_PREFIX${UUID.randomUUID()}.db"
        val database = ValnookDatabase.fromAsset(context, databaseName, DEMO_DATABASE_ASSET)
        try {
            val raw = createDatabaseGraph(context, database, clock, realDatabaseGraph.portability.buildInfo())
            var fixtureSettings = raw.settings.observeSettings().first()
            fixtureSettings = raw.settingsWriter.applyChange(
                SaveLanguage(fixtureSettings.revision, displayPreferences.language))
            if (fixtureSettings.gainLossColors != displayPreferences.gainLossColors) {
                raw.settingsWriter.applyChange(
                    SaveGainLossColors(fixtureSettings.revision, displayPreferences.gainLossColors))
            }
            val id = UUID.randomUUID().toString()
            currentSessionId = id
            demoDatabase = database
            demoDatabaseName = databaseName
            mutable.value = ActiveSession(id, DataMode.DEMO, bind(raw, id, DataMode.DEMO))
        } catch (error: Exception) {
            database.close()
            context.deleteDatabase(databaseName)
            throw error
        }
    }

    suspend fun exitDemo() = writeMutex.withLock {
        if (mutable.value.mode != DataMode.DEMO) return@withLock
        if (activeFileJob != null) throw dev.valnook.domain.portability.PortabilityException(
            dev.valnook.domain.portability.PortabilityErrorCode.JOB_IN_PROGRESS)
        cancelPendingRestoreLocked()
        pendingClear = null
        publishRealSession()
        demoDatabase?.close()
        demoDatabaseName?.let(context::deleteDatabase)
        demoDatabase = null
        demoDatabaseName = null
    }

    suspend fun issueClearChallenge(): String = writeMutex.withLock {
        val active = mutable.value
        if (active.mode != DataMode.REAL) throw DomainException(ErrorCode.SESSION_EXPIRED)
        rejectWhenFileJobIsActive()
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val challenge = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        pendingClear = PendingClear(active.id, challenge)
        challenge
    }

    suspend fun cancelClearChallenge(challenge: String) = writeMutex.withLock {
        if (pendingClear?.challenge == challenge) pendingClear = null
    }

    suspend fun cancelPendingRestore(sessionId: String) = writeMutex.withLock {
        if (mutable.value.id == sessionId && pendingRestore?.sessionId == sessionId) {
            cancelPendingRestoreLocked()
        }
    }

    suspend fun syncPlatformLanguage(sessionId: String, language: AppLanguage) = writeMutex.withLock {
        val active = mutable.value
        if (active.id != sessionId || active.mode != DataMode.REAL) return@withLock
        val tags = when (language) {
            AppLanguage.SYSTEM -> ""
            AppLanguage.ZH_HANS -> "zh-Hans"
            AppLanguage.ENGLISH -> "en"
        }
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val requested = LocaleList.forLanguageTags(tags)
        if (localeManager.applicationLocales != requested) localeManager.applicationLocales = requested
    }

    suspend fun clearRealData(challenge: String, input: String) = writeMutex.withLock {
        val active = mutable.value
        rejectWhenFileJobIsActive()
        val pending = pendingClear
        if (active.mode != DataMode.REAL || pending == null || pending.sessionId != active.id ||
            pending.challenge != challenge || input != challenge) {
            throw DomainException(ErrorCode.SESSION_EXPIRED)
        }
        pendingClear = null
        cancelPendingRestoreLocked()
        // Always revoke the old generation before the mutex is released, including cancellation paths.
        try {
            realDatabaseGraph.maintenance.clearBusinessData()
        } finally {
            publishRealSession()
        }
    }

    private fun publishRealSession() {
        val id = UUID.randomUUID().toString()
        currentSessionId = id
        mutable.value = ActiveSession(id, DataMode.REAL, bind(realDatabaseGraph, id, DataMode.REAL))
    }

    private fun bind(source: DatabaseGraph, sessionId: String, mode: DataMode): AppGraph = AppGraph(
        sessionId = sessionId,
        accounts = source.accounts,
        cash = source.cash,
        deposits = source.deposits,
        investments = source.investments,
        commands = SessionCommands(sessionId, source.commands),
        clock = source.clock,
        overview = source.overview,
        statistics = dev.valnook.data.repository.RoomStatistics(source.database, source.clock) {
            currentSessionId == sessionId
        },
        settings = source.settings,
        settingsWriter = SessionSettingsWriter(sessionId, source.settingsWriter),
        instruments = source.instruments,
        cashPages = source.cashPages,
        depositPages = source.depositPages,
        portability = SessionPortability(sessionId, mode, source.portability),
        cloudBackup = SessionCloudBackup(sessionId, mode, cloudCoordinator)
    )

    private inner class SessionCloudBackup(
        private val sessionId: String,
        private val mode: DataMode,
        private val delegate: dev.valnook.domain.cloud.CloudBackupService
    ) : dev.valnook.domain.cloud.CloudBackupService {
        override val cloudAllowed: Boolean = mode == DataMode.REAL
        override fun observeState() = if (mode == DataMode.REAL) delegate.observeState()
            else kotlinx.coroutines.flow.flowOf(dev.valnook.domain.cloud.CloudBackupRuntimeState())
        private fun check() {
            requireCurrent(sessionId)
            if (mode != DataMode.REAL) throw dev.valnook.domain.cloud.CloudBackupException(
                dev.valnook.domain.cloud.CloudBackupError.DEMO_RESTRICTED)
        }
        override suspend fun connect(grant: dev.valnook.domain.cloud.CloudAuthorizationGrant) { check(); delegate.connect(grant) }
        override suspend fun disconnect() { check(); delegate.disconnect() }
        override suspend fun manualBackup() { check(); delegate.manualBackup() }
        override suspend fun refresh() { check(); delegate.refresh() }
        override suspend fun setAutomatic(enabled: Boolean) { check(); delegate.setAutomatic(enabled) }
        override suspend fun setIntervalHours(hours: Int) { check(); delegate.setIntervalHours(hours) }
        override suspend fun resumeAfterRestore() { check(); delegate.resumeAfterRestore() }
        override suspend fun reconcileSchedule() { check(); delegate.reconcileSchedule() }
        override suspend fun downloadOriginal(fileId: String, output: OutputStream) { check(); delegate.downloadOriginal(fileId, output) }
        override suspend fun stageForRestore(fileId: String): dev.valnook.domain.cloud.CloudRestoreDownload {
            check(); return delegate.stageForRestore(fileId)
        }
        override fun openStagedRestore(localId: String): InputStream { check(); return delegate.openStagedRestore(localId) }
        override fun releaseStagedRestore(localId: String) { delegate.releaseStagedRestore(localId) }
        override suspend fun consumeBanner(eventId: String) { check(); delegate.consumeBanner(eventId) }
    }

    private fun clearAbandonedDemoDatabases() {
        context.databaseList().filter { it.startsWith(DEMO_DATABASE_PREFIX) }.forEach(context::deleteDatabase)
    }

    private inner class SessionCommands(
        private val sessionId: String,
        private val delegate: FinancialCommands
    ) : FinancialCommands {
        override suspend fun execute(command: FinancialCommand): OperationResult = writeMutex.withLock {
            requireCurrent(sessionId)
            delegate.execute(command)
        }

        override suspend fun operationResult(operationId: String): OperationResult? = writeMutex.withLock {
            requireCurrent(sessionId)
            delegate.operationResult(operationId)
        }
    }

    private inner class SessionSettingsWriter(
        private val sessionId: String,
        private val delegate: SettingsWriter
    ) : SettingsWriter {
        override suspend fun applyChange(change: SettingsChange): dev.valnook.domain.model.AppSettings =
            writeMutex.withLock {
                requireCurrent(sessionId)
                delegate.applyChange(change)
            }
    }

    private inner class SessionPortability(
        private val sessionId: String,
        private val mode: DataMode,
        private val engine: dev.valnook.data.portability.RoomPortabilityEngine
    ) : dev.valnook.domain.portability.DataPortability {
        override val backupAndRestoreAllowed: Boolean = mode == DataMode.REAL

        override suspend fun createBackup(
            backupId: String,
            output: OutputStream,
            progress: (dev.valnook.domain.portability.PortabilityProgress) -> Unit
        ): dev.valnook.domain.portability.PortableFileResult = fileJob(sessionId) {
            requireReal()
            engine.createBackup(backupId, output, progress)
        }

        override suspend fun prepareRestore(
            input: InputStream,
            sourceName: String,
            progress: (dev.valnook.domain.portability.PortabilityProgress) -> Unit
        ): dev.valnook.domain.portability.RestorePreview = fileJob(sessionId) {
            requireReal()
            val staged = engine.prepareRestore(input, sourceName, progress)
            try {
                writeMutex.withLock {
                    requireCurrent(sessionId)
                    cancelPendingRestoreLocked()
                    pendingRestore = PendingRestore(sessionId, staged)
                }
            } catch (error: Throwable) {
                engine.close(staged)
                throw error
            }
            engine.preview(staged)
        }

        override suspend fun issueRestoreChallenge(candidateId: String): dev.valnook.domain.portability.RestoreChallenge =
            writeMutex.withLock {
                requireReal()
                requireCurrent(sessionId)
                val pending = pendingRestore?.takeIf {
                    it.sessionId == sessionId && it.staged.candidateId == candidateId
                } ?: expired()
                val generation = engine.generation()
                pending.staged = pending.staged.copy(generationAtPreview = generation)
                val challenge = randomChallenge()
                pending.challenge = challenge
                pending.challengeGeneration = generation
                dev.valnook.domain.portability.RestoreChallenge(candidateId, challenge)
            }

        override suspend fun commitRestore(
            candidateId: String,
            challenge: String,
            confirmation: String,
            progress: (dev.valnook.domain.portability.PortabilityProgress) -> Unit
        ): dev.valnook.domain.portability.RestoreResult = writeMutex.withLock {
            requireReal()
            requireCurrent(sessionId)
            val pending = pendingRestore?.takeIf {
                it.sessionId == sessionId && it.staged.candidateId == candidateId
            } ?: expired()
            val expected = pending.challenge
            pending.challenge = null
            if (expected == null || expected != challenge) expired()
            if (confirmation != expected) throw dev.valnook.domain.portability.PortabilityException(
                dev.valnook.domain.portability.PortabilityErrorCode.CONFIRMATION_MISMATCH)
            if (engine.generation() != pending.challengeGeneration) {
                throw dev.valnook.domain.portability.PortabilityException(
                    dev.valnook.domain.portability.PortabilityErrorCode.STALE_PREVIEW)
            }
            val result = engine.commitRestore(pending.staged, progress)
            withContext(NonCancellable) {
                engine.close(pending.staged)
                pendingRestore = null
                publishRealSession()
                cloudCoordinator.reconcileSchedule()
            }
            result
        }

        override suspend fun cancelRestore(candidateId: String) = writeMutex.withLock {
            val pending = pendingRestore
            if (pending != null && pending.sessionId == sessionId && pending.staged.candidateId == candidateId) {
                cancelPendingRestoreLocked()
            }
        }

        override suspend fun exportWorkbook(
            reportId: String,
            language: AppLanguage,
            demo: Boolean,
            output: OutputStream,
            progress: (dev.valnook.domain.portability.PortabilityProgress) -> Unit
        ): dev.valnook.domain.portability.PortableFileResult = fileJob(sessionId) {
            if (demo != (mode == DataMode.DEMO)) expired()
            engine.exportWorkbook(reportId, language, demo, output, progress)
        }

        private fun requireReal() {
            if (mode != DataMode.REAL) throw dev.valnook.domain.portability.PortabilityException(
                dev.valnook.domain.portability.PortabilityErrorCode.DEMO_RESTRICTED)
        }
    }

    private suspend fun <T> fileJob(sessionId: String, block: suspend () -> T): T {
        val jobId = writeMutex.withLock {
            requireCurrent(sessionId)
            if (activeFileJob != null) throw dev.valnook.domain.portability.PortabilityException(
                dev.valnook.domain.portability.PortabilityErrorCode.JOB_IN_PROGRESS)
            UUID.randomUUID().toString().also { activeFileJob = ActiveFileJob(it, sessionId) }
        }
        try {
            return block().also { writeMutex.withLock { requireCurrent(sessionId) } }
        } finally {
            writeMutex.withLock { if (activeFileJob?.id == jobId) activeFileJob = null }
        }
    }

    private fun cancelPendingRestoreLocked() {
        pendingRestore?.let { it.engineClose() }
        pendingRestore = null
    }

    private fun PendingRestore.engineClose() = realDatabaseGraph.portability.close(staged)

    private fun randomChallenge(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        return buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
    }

    private fun expired(): Nothing = throw dev.valnook.domain.portability.PortabilityException(
        dev.valnook.domain.portability.PortabilityErrorCode.SESSION_EXPIRED)

    private fun requireCurrent(sessionId: String) {
        if (mutable.value.id != sessionId) throw DomainException(ErrorCode.SESSION_EXPIRED)
    }

    private fun rejectWhenFileJobIsActive() {
        if (activeFileJob != null) throw dev.valnook.domain.portability.PortabilityException(
            dev.valnook.domain.portability.PortabilityErrorCode.JOB_IN_PROGRESS)
    }

    private data class PendingClear(val sessionId: String, val challenge: String)
    private data class ActiveFileJob(val id: String, val sessionId: String)
    private data class PendingRestore(
        val sessionId: String,
        var staged: StagedRestore,
        var challenge: String? = null,
        var challengeGeneration: Long = -1
    )

    private companion object {
        const val DEMO_DATABASE_ASSET = "database/valnook-demo-v10.db"
        const val DEMO_DATABASE_PREFIX = "valnook-demo-"
    }
}

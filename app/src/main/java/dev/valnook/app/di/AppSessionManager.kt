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
import kotlinx.coroutines.launch
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class DataMode { REAL, DEMO }

data class ActiveSession(val id: String, val mode: DataMode, val graph: AppGraph)

/** The first real session's id; see [AppSessionManager]. */
internal const val REAL_SESSION_ID = "real"

@Singleton
class AppSessionManager @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val realDatabaseGraph: DatabaseGraph,
    private val realDatabase: ValnookDatabase,
    private val clock: Clock,
    private val cloudCoordinator: dev.valnook.domain.cloud.CloudBackupService =
        dev.valnook.domain.cloud.UnavailableCloudBackupService,
    val cloudAuthorization: dev.valnook.feature.backup.CloudAuthorization =
        dev.valnook.feature.backup.UnavailableCloudAuthorization,
    val onboarding: dev.valnook.app.onboarding.OnboardingStorage =
        dev.valnook.app.onboarding.OnboardingPreferences(context),
    private val fxEngine: dev.valnook.data.fx.FxSyncEngine = dev.valnook.data.fx.DisabledFxSyncEngine
) {
    /** Single non-reentrant boundary for writes, switches, and destructive maintenance. */
    private val writeMutex = Mutex()
    private var demoDatabase: ValnookDatabase? = null
    private var demoDatabaseName: String? = null
    @Volatile private var demoDatabaseGraph: DatabaseGraph? = null
    private var pendingClear: PendingClear? = null
    private var pendingRestore: PendingRestore? = null
    private var activeFileJob: ActiveFileJob? = null
    @Volatile private var writeOwner: WriteOwner = WriteOwner.Mobile
    @Volatile private var webAdminReservation: WebAdminReservation? = null
    // The same every launch, so state saved before the system ended the process (the page stack, drafts) is found
    // again. Entering demo, clearing data or restoring a backup still starts a session with a new id.
    private val initialId = REAL_SESSION_ID
    @Volatile private var currentSessionId = initialId
    private val mutable = MutableStateFlow(ActiveSession(initialId, DataMode.REAL,
        bind(realDatabaseGraph, initialId, DataMode.REAL)))
    val session = mutable.asStateFlow()
    init {
        // Sync writes wait for restore, clear and other local writes; web admin leases do not block them.
        fxEngine.attach { block -> writeMutex.withLock { block() } }
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO).launch {
            // Reconcile unreferenced ciphertext after interrupted deletion/restore; no values are decoded.
            runCatching { writeMutex.withLock { realDatabaseGraph.walletPrivate.cleanup() } }
            // Staging left by a process that died during a restore or an export: plain copies of the data, never
            // used again. Not while a preview waits for confirmation or a file job runs.
            runCatching { writeMutex.withLock {
                if (pendingRestore == null && activeFileJob == null)
                    java.io.File(context.cacheDir, "valnook-portability").listFiles()?.forEach { it.deleteRecursively() }
            } }
        }
    }
    fun observeCloudState() = cloudCoordinator.observeState()
    suspend fun consumeCloudBanner(eventId: String) = cloudCoordinator.consumeBanner(eventId)
    suspend fun reconcileCloudSchedule() = cloudCoordinator.reconcileSchedule()

    /** Daily automatic exchange rates, for the real dataset only. */
    fun requestFxSync() {
        if (mutable.value.mode == DataMode.REAL) fxEngine.request(dev.valnook.domain.fx.FxSyncTrigger.START)
    }

    suspend fun enterDemo() = writeMutex.withLock {
        rejectWhenWebAdminOpen()
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
            val raw = createDatabaseGraph(context, database, clock, realDatabaseGraph.portability.buildInfo(), persistentPrivateCards = false)
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
            demoDatabaseGraph = raw
            mutable.value = ActiveSession(id, DataMode.DEMO, bind(raw, id, DataMode.DEMO))
        } catch (error: Exception) {
            database.close()
            context.deleteDatabase(databaseName)
            throw error
        }
    }

    suspend fun exitDemo() = writeMutex.withLock {
        rejectWhenWebAdminOpen()
        if (mutable.value.mode != DataMode.DEMO) return@withLock
        if (activeFileJob != null) throw dev.valnook.domain.portability.PortabilityException(
            dev.valnook.domain.portability.PortabilityErrorCode.JOB_IN_PROGRESS)
        cancelPendingRestoreLocked()
        pendingClear = null
        publishRealSession()
        demoDatabase?.close()
        demoDatabaseName?.let(context::deleteDatabase)
        demoDatabaseGraph = null
        demoDatabase = null
        demoDatabaseName = null
    }

    suspend fun issueClearChallenge(): String = writeMutex.withLock {
        rejectWhenWebOwnsWrites()
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
        rejectWhenWebOwnsWrites()
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
            withContext(NonCancellable) {
                realDatabaseGraph.maintenance.clearBusinessData()
                realDatabaseGraph.walletPrivate.cleanup(clearPreferences = true)
                withContext(kotlinx.coroutines.Dispatchers.IO) {
                    onboarding.save(dev.valnook.app.onboarding.OnboardingDraft())
                }
            }
        } finally {
            publishRealSession()
        }
    }

    private fun publishRealSession() {
        publishSession(realDatabaseGraph, DataMode.REAL)
    }

    private fun publishSession(source: DatabaseGraph, mode: DataMode) {
        val id = UUID.randomUUID().toString()
        currentSessionId = id
        mutable.value = ActiveSession(id, mode, bind(source, id, mode))
    }

    internal suspend fun reserveWebAdminServer(reservationId: String) = writeMutex.withLock {
        if (webAdminReservation != null || writeOwner !is WriteOwner.Mobile) {
            throw dev.valnook.domain.webadmin.WebAdminException(
                dev.valnook.domain.webadmin.WebAdminError.SESSION_BUSY)
        }
        if (activeFileJob != null || pendingRestore != null || pendingClear != null) {
            throw dev.valnook.domain.webadmin.WebAdminException(
                dev.valnook.domain.webadmin.WebAdminError.MAINTENANCE_ACTIVE)
        }
        webAdminReservation = WebAdminReservation(reservationId, mutable.value.mode)
    }

    internal suspend fun releaseWebAdminServer(reservationId: String) = writeMutex.withLock {
        if (webAdminReservation?.id == reservationId) webAdminReservation = null
    }

    internal suspend fun acquireWebWriteLease(webSessionId: String): dev.valnook.domain.webadmin.WebWriteLease =
        writeMutex.withLock {
            val reservation = webAdminReservation ?: throw dev.valnook.domain.webadmin.WebAdminException(
                dev.valnook.domain.webadmin.WebAdminError.SESSION_EXPIRED)
            if (mutable.value.mode != reservation.mode) throw dev.valnook.domain.webadmin.WebAdminException(
                dev.valnook.domain.webadmin.WebAdminError.SESSION_EXPIRED)
            if (writeOwner !is WriteOwner.Mobile) throw dev.valnook.domain.webadmin.WebAdminException(
                dev.valnook.domain.webadmin.WebAdminError.SESSION_BUSY)
            if (activeFileJob != null || pendingRestore != null || pendingClear != null) {
                throw dev.valnook.domain.webadmin.WebAdminException(
                    dev.valnook.domain.webadmin.WebAdminError.MAINTENANCE_ACTIVE)
            }
            val graph = databaseGraphFor(reservation.mode)
            writeOwner = WriteOwner.Web(webSessionId)
            publishSession(graph, reservation.mode)
            dev.valnook.domain.webadmin.WebWriteLease(webSessionId, graph.database.audit().generation())
        }

    internal suspend fun releaseWebWriteLease(webSessionId: String) = writeMutex.withLock {
        if ((writeOwner as? WriteOwner.Web)?.sessionId == webSessionId) {
            val reservation = webAdminReservation
            writeOwner = WriteOwner.Mobile
            if (reservation != null && mutable.value.mode == reservation.mode) {
                publishSession(databaseGraphFor(reservation.mode), reservation.mode)
            } else {
                publishRealSession()
            }
        }
    }

    internal fun webAdminReads(): dev.valnook.domain.webadmin.WebAdminReadRepository =
        webAdminGraph().webAdminReads

    internal suspend fun previewWebAccountDeletion(webSessionId: String, accountId: Long, cashId: Long?) = writeMutex.withLock {
        requireWebOwner(webSessionId)
        webAdminGraph().commands.previewAccountDeletion(accountId, cashId)
    }

    internal suspend fun cancelWebAccountDeletion(webSessionId: String, ticket: String) = writeMutex.withLock {
        requireWebOwner(webSessionId)
        webAdminGraph().commands.cancelAccountDeletion(ticket)
    }

    internal suspend fun executeWebCommand(
        webSessionId: String,
        expectedGeneration: Long,
        command: FinancialCommand
    ): dev.valnook.domain.webadmin.WebMutationReceipt = writeMutex.withLock {
        requireWebOwner(webSessionId)
        val graph = webAdminGraph()
        if (graph.database.audit().generation() != expectedGeneration) throw DomainException(ErrorCode.STALE_RECORD)
        val result = graph.commands.execute(command, CommandSource.WEB_ADMIN)
        dev.valnook.domain.webadmin.WebMutationReceipt(result, graph.database.audit().generation())
    }

    internal suspend fun webOperationResult(webSessionId: String, operationId: String): OperationResult? =
        writeMutex.withLock {
            requireWebOwner(webSessionId)
            webAdminGraph().commands.operationResult(operationId)
        }

    private fun webAdminGraph(): DatabaseGraph {
        val reservation = webAdminReservation ?: throw DomainException(ErrorCode.SESSION_EXPIRED)
        if (mutable.value.mode != reservation.mode) throw DomainException(ErrorCode.SESSION_EXPIRED)
        return databaseGraphFor(reservation.mode)
    }

    private fun databaseGraphFor(mode: DataMode): DatabaseGraph = when (mode) {
        DataMode.REAL -> realDatabaseGraph
        DataMode.DEMO -> demoDatabaseGraph ?: throw DomainException(ErrorCode.SESSION_EXPIRED)
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
        settingsWriter = SessionSettingsWriter(sessionId, mode, source),
        instruments = source.instruments,
        cashPages = source.cashPages,
        depositPages = source.depositPages,
        portability = SessionPortability(sessionId, mode, source.portability),
        cloudBackup = SessionCloudBackup(sessionId, mode, cloudCoordinator),
        fxSync = SessionFxSync(sessionId, mode),
        wallet = object : dev.valnook.domain.repository.WalletRepository {
            private val delegate = dev.valnook.data.repository.RoomWallet(source.database,source.clock)
            override fun observeCards() = delegate.observeCards()
            override suspend fun image(key: String) = delegate.image(key)
            override suspend fun save(id: Long?, expectedRevision: Long?, name: String, cashAccountId: Long?, imageKey: String?, image: dev.valnook.domain.model.WalletImage?, faceStyle: dev.valnook.domain.model.WalletFaceStyle?) = writeMutex.withLock {
                requireMobileWrite(sessionId)
                delegate.save(id,expectedRevision,name,cashAccountId,imageKey,image,faceStyle)
            }
            override suspend fun delete(id: Long, expectedRevision: Long) = writeMutex.withLock {
                requireMobileWrite(sessionId); delegate.delete(id,expectedRevision)
                runCatching { source.walletPrivate.cleanup() }
                Unit
            }
            override suspend fun reorder(expected: List<Long>, ordered: List<Long>) = writeMutex.withLock {
                requireMobileWrite(sessionId); delegate.reorder(expected,ordered)
            }
            override fun observeFaces() = delegate.observeFaces()
            override suspend fun addFace(label: String, image: dev.valnook.domain.model.WalletImage) = writeMutex.withLock {
                requireMobileWrite(sessionId); delegate.addFace(label,image)
            }
            override suspend fun deleteFace(key: String) = writeMutex.withLock {
                requireMobileWrite(sessionId); delegate.deleteFace(key)
            }
        },
        walletPrivate = if(mode == DataMode.DEMO) null else object : dev.valnook.domain.repository.WalletPrivateRepository {
            override suspend fun read(cardId: Long) = writeMutex.withLock {
                requireMobileWrite(sessionId); source.walletPrivate.read(cardId)
            }
            override suspend fun save(cardId: Long, content: dev.valnook.domain.repository.WalletPrivateContent) = writeMutex.withLock {
                requireMobileWrite(sessionId); source.walletPrivate.save(cardId, content)
            }
            override suspend fun warningDismissed() = writeMutex.withLock {
                requireMobileWrite(sessionId); source.walletPrivate.warningDismissed()
            }
            override suspend fun dismissWarning() = writeMutex.withLock {
                requireMobileWrite(sessionId); source.walletPrivate.dismissWarning()
            }
        },
        accountGroupWriter = object : AccountGroupWriter {
            private val delegate = dev.valnook.data.repository.RoomAccountGroupWriter(source.database, source.clock, source.commands)
            private suspend fun <T> mobile(block: suspend AccountGroupWriter.() -> T): T = writeMutex.withLock {
                requireMobileWrite(sessionId); delegate.block()
            }
            override suspend fun create(name: String) = mobile { create(name) }
            override suspend fun rename(groupId: Long, name: String) = mobile { rename(groupId, name) }
            override suspend fun delete(groupId: Long) = mobile { delete(groupId) }
            override suspend fun reorderGroups(orderedIds: List<Long>) = mobile { reorderGroups(orderedIds) }
            override suspend fun moveAccounts(groupId: Long, accountIds: List<Long>) = mobile { moveAccounts(groupId, accountIds) }
            override suspend fun reorderAccounts(groupId: Long, orderedIds: List<Long>) = mobile { reorderAccounts(groupId, orderedIds) }
            override suspend fun setCollapsed(groupId: Long, collapsed: Boolean) = mobile { setCollapsed(groupId, collapsed) }
        }
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
            if (writeOwner !is WriteOwner.Mobile) throw dev.valnook.domain.cloud.CloudBackupException(
                dev.valnook.domain.cloud.CloudBackupError.WEB_ADMIN_ACTIVE)
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
        override suspend fun previewAccountDeletion(accountId: Long, balanceAccountId: Long?) = writeMutex.withLock {
            requireMobileWrite(sessionId)
            delegate.previewAccountDeletion(accountId, balanceAccountId)
        }
        override suspend fun cancelAccountDeletion(ticket: String) = writeMutex.withLock {
            delegate.cancelAccountDeletion(ticket)
        }
        override suspend fun previewInstrumentDeletion(instrumentId: Long) = writeMutex.withLock {
            requireMobileWrite(sessionId)
            delegate.previewInstrumentDeletion(instrumentId)
        }
        override suspend fun cancelInstrumentDeletion(ticket: String) = writeMutex.withLock {
            delegate.cancelInstrumentDeletion(ticket)
        }

        override suspend fun execute(command: FinancialCommand): OperationResult = writeMutex.withLock {
            requireMobileWrite(sessionId)
            delegate.execute(command)
        }

        override suspend fun execute(command: FinancialCommand, source: CommandSource): OperationResult =
            execute(command)

        override suspend fun operationResult(operationId: String): OperationResult? = writeMutex.withLock {
            requireMobileWrite(sessionId)
            delegate.operationResult(operationId)
        }
    }

    private inner class SessionSettingsWriter(
        private val sessionId: String,
        private val mode: DataMode,
        private val source: DatabaseGraph
    ) : SettingsWriter {
        override suspend fun applyChange(change: SettingsChange): dev.valnook.domain.model.AppSettings {
            var baseChanged = false
            val result = writeMutex.withLock {
                requireMobileWrite(sessionId)
                val previous = source.database.fx().baseCurrency()
                source.settingsWriter.applyChange(change).also { baseChanged = it.baseCurrency?.code != previous }
            }
            // A new base starts its history over; the sync itself checks whether automatic rates are on.
            if (baseChanged && mode == DataMode.REAL) fxEngine.request(dev.valnook.domain.fx.FxSyncTrigger.BASE_CHANGED)
            return result
        }
    }

    private inner class SessionFxSync(
        private val sessionId: String,
        private val mode: DataMode
    ) : dev.valnook.domain.fx.FxRateSync {
        private val real = mode == DataMode.REAL
        override fun observeStatus() = if (real) fxEngine.observeStatus()
            else kotlinx.coroutines.flow.flowOf(dev.valnook.domain.fx.FxSyncStatus())
        override fun observeNotice() = if (real) fxEngine.observeNotice()
            else kotlinx.coroutines.flow.flowOf(null)
        override suspend fun consumeNotice(id: String) { if (real) fxEngine.consumeNotice(id) }
        // The demo never goes online and its switch stays off.
        override suspend fun refreshNow() {
            if (!real) return
            writeMutex.withLock { requireMobileWrite(sessionId) }
            fxEngine.sync(dev.valnook.domain.fx.FxSyncTrigger.MANUAL)
        }
        override suspend fun setAutoEnabled(on: Boolean) {
            if (!real) return
            fxEngine.setAutoEnabled(on) { block -> writeMutex.withLock { requireMobileWrite(sessionId); block() } }
        }
        override suspend fun dismissNotice() {
            if (!real) return
            fxEngine.dismissNotice { block -> writeMutex.withLock { requireMobileWrite(sessionId); block() } }
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
                requireMobilePortability(sessionId)
                requireReal()
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
            requireMobilePortability(sessionId)
            requireReal()
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
                // FK cascades have already invalidated all local card identities transactionally.
                runCatching { realDatabaseGraph.walletPrivate.cleanup() }
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
            requireMobilePortability(sessionId)
            if (activeFileJob != null) throw dev.valnook.domain.portability.PortabilityException(
                dev.valnook.domain.portability.PortabilityErrorCode.JOB_IN_PROGRESS)
            UUID.randomUUID().toString().also { activeFileJob = ActiveFileJob(it, sessionId) }
        }
        try {
            return block().also { writeMutex.withLock { requireMobilePortability(sessionId) } }
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

    private fun requireMobileWrite(sessionId: String) {
        if (writeOwner !is WriteOwner.Mobile) throw DomainException(ErrorCode.WEB_ADMIN_ACTIVE)
        requireCurrent(sessionId)
    }

    private fun requireMobilePortability(sessionId: String) {
        if (writeOwner !is WriteOwner.Mobile) throw dev.valnook.domain.portability.PortabilityException(
            dev.valnook.domain.portability.PortabilityErrorCode.WEB_ADMIN_ACTIVE)
        requireCurrent(sessionId)
    }

    private fun requireWebOwner(webSessionId: String) {
        if ((writeOwner as? WriteOwner.Web)?.sessionId != webSessionId) {
            throw DomainException(ErrorCode.SESSION_EXPIRED)
        }
    }

    private fun rejectWhenWebOwnsWrites() {
        if (writeOwner !is WriteOwner.Mobile) throw DomainException(ErrorCode.WEB_ADMIN_ACTIVE)
    }

    private fun rejectWhenWebAdminOpen() {
        if (webAdminReservation != null || writeOwner !is WriteOwner.Mobile) {
            throw DomainException(ErrorCode.WEB_ADMIN_ACTIVE)
        }
    }

    private fun rejectWhenFileJobIsActive() {
        if (activeFileJob != null) throw dev.valnook.domain.portability.PortabilityException(
            dev.valnook.domain.portability.PortabilityErrorCode.JOB_IN_PROGRESS)
    }

    private data class PendingClear(val sessionId: String, val challenge: String)
    private data class WebAdminReservation(val id: String, val mode: DataMode)
    private sealed interface WriteOwner {
        data object Mobile : WriteOwner
        data class Web(val sessionId: String) : WriteOwner
    }
    private data class ActiveFileJob(val id: String, val sessionId: String)
    private data class PendingRestore(
        val sessionId: String,
        var staged: StagedRestore,
        var challenge: String? = null,
        var challengeGeneration: Long = -1
    )

    private companion object {
        const val DEMO_DATABASE_ASSET = "database/valnook-demo-v23.db"
        const val DEMO_DATABASE_PREFIX = "valnook-demo-"
    }
}

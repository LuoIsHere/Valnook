package dev.valnook.app.di

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.valnook.data.database.ValnookDatabase
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
    private val clock: Clock
) {
    /** Single non-reentrant boundary for writes, switches, and destructive maintenance. */
    private val writeMutex = Mutex()
    private var demoDatabase: ValnookDatabase? = null
    private var demoDatabaseName: String? = null
    private var pendingClear: PendingClear? = null
    private val initialId = UUID.randomUUID().toString()
    @Volatile private var currentSessionId = initialId
    private val mutable = MutableStateFlow(ActiveSession(initialId, DataMode.REAL,
        bind(realDatabaseGraph, initialId)))
    val session = mutable.asStateFlow()

    suspend fun enterDemo() = writeMutex.withLock {
        if (mutable.value.mode == DataMode.DEMO) return@withLock
        pendingClear = null
        val displayPreferences = realDatabaseGraph.settings.observeSettings().first()
        clearAbandonedDemoDatabases()
        val databaseName = "$DEMO_DATABASE_PREFIX${UUID.randomUUID()}.db"
        val database = ValnookDatabase.fromAsset(context, databaseName, DEMO_DATABASE_ASSET)
        try {
            val raw = createDatabaseGraph(database, clock)
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
            mutable.value = ActiveSession(id, DataMode.DEMO, bind(raw, id))
        } catch (error: Exception) {
            database.close()
            context.deleteDatabase(databaseName)
            throw error
        }
    }

    suspend fun exitDemo() = writeMutex.withLock {
        if (mutable.value.mode != DataMode.DEMO) return@withLock
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
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val challenge = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        pendingClear = PendingClear(active.id, challenge)
        challenge
    }

    suspend fun cancelClearChallenge(challenge: String) = writeMutex.withLock {
        if (pendingClear?.challenge == challenge) pendingClear = null
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
        val pending = pendingClear
        if (active.mode != DataMode.REAL || pending == null || pending.sessionId != active.id ||
            pending.challenge != challenge || input != challenge) {
            throw DomainException(ErrorCode.SESSION_EXPIRED)
        }
        pendingClear = null
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
        mutable.value = ActiveSession(id, DataMode.REAL, bind(realDatabaseGraph, id))
    }

    private fun bind(source: DatabaseGraph, sessionId: String): AppGraph = AppGraph(
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
        depositPages = source.depositPages
    )

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

    private fun requireCurrent(sessionId: String) {
        if (mutable.value.id != sessionId) throw DomainException(ErrorCode.SESSION_EXPIRED)
    }

    private data class PendingClear(val sessionId: String, val challenge: String)

    private companion object {
        const val DEMO_DATABASE_ASSET = "database/valnook-demo-v7.db"
        const val DEMO_DATABASE_PREFIX = "valnook-demo-"
    }
}

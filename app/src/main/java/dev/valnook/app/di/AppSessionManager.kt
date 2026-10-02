package dev.valnook.app.di

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.RoomAccounts
import dev.valnook.data.repository.RoomCash
import dev.valnook.data.repository.RoomDataMaintenance
import dev.valnook.data.repository.RoomDeposits
import dev.valnook.data.repository.RoomInstruments
import dev.valnook.data.repository.RoomInvestments
import dev.valnook.data.repository.RoomOverview
import dev.valnook.data.repository.RoomSettings
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.model.AppLanguage
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.model.OperationResult
import dev.valnook.domain.repository.FinancialCommand
import dev.valnook.domain.repository.FinancialCommands
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
class AppSessionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val realGraph: AppGraph,
    private val realDatabase: ValnookDatabase,
    private val clock: Clock
) {
    private val mutex = Mutex()
    private var demoDatabase: ValnookDatabase? = null
    private var demoDatabaseName: String? = null
    private var pendingClear: PendingClear? = null
    private val initialId = UUID.randomUUID().toString()
    private val mutable = MutableStateFlow(ActiveSession(initialId, DataMode.REAL,
        bind(realGraph, realDatabase, initialId)))
    val session = mutable.asStateFlow()

    suspend fun enterDemo() = mutex.withLock {
        if (mutable.value.mode == DataMode.DEMO) return@withLock
        pendingClear = null
        val displayPreferences = realGraph.settings.observeSettings().first()
        clearAbandonedDemoDatabases()
        val databaseName = "$DEMO_DATABASE_PREFIX${UUID.randomUUID()}.db"
        val database = ValnookDatabase.fromAsset(context, databaseName, DEMO_DATABASE_ASSET)
        try {
            val raw = graphFor(database, RoomFinancialCommands(database, clock))
            val fixtureSettings = raw.settings.observeSettings().first()
            raw.settings.saveSettings(fixtureSettings.copy(
                language = displayPreferences.language,
                gainLossColors = displayPreferences.gainLossColors
            ), fixtureSettings.revision)
            val id = UUID.randomUUID().toString()
            demoDatabase = database
            demoDatabaseName = databaseName
            mutable.value = ActiveSession(id, DataMode.DEMO, bind(raw, database, id))
        } catch (error: Exception) {
            database.close()
            context.deleteDatabase(databaseName)
            throw error
        }
    }

    suspend fun exitDemo() = mutex.withLock {
        if (mutable.value.mode != DataMode.DEMO) return@withLock
        pendingClear = null
        val id = UUID.randomUUID().toString()
        mutable.value = ActiveSession(id, DataMode.REAL, bind(realGraph, realDatabase, id))
        demoDatabase?.close()
        demoDatabaseName?.let(context::deleteDatabase)
        demoDatabase = null
        demoDatabaseName = null
    }

    fun issueClearChallenge(): String {
        val active = mutable.value
        if (active.mode != DataMode.REAL) throw DomainException(ErrorCode.SESSION_EXPIRED)
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val random = SecureRandom()
        val challenge = buildString { repeat(6) { append(alphabet[random.nextInt(alphabet.length)]) } }
        pendingClear = PendingClear(active.id, challenge)
        return challenge
    }

    fun cancelClearChallenge(challenge: String) {
        if (pendingClear?.challenge == challenge) pendingClear = null
    }

    fun syncPlatformLanguage(language: AppLanguage) {
        if (mutable.value.mode != DataMode.REAL) return
        val tags = when (language) {
            AppLanguage.SYSTEM -> ""
            AppLanguage.ZH_HANS -> "zh-Hans"
            AppLanguage.ENGLISH -> "en"
        }
        val localeManager = context.getSystemService(LocaleManager::class.java)
        val requested = LocaleList.forLanguageTags(tags)
        if (localeManager.applicationLocales != requested) {
            localeManager.applicationLocales = requested
        }
    }

    suspend fun clearRealData(challenge: String, input: String) = mutex.withLock {
        val active = mutable.value
        val pending = pendingClear
        if (active.mode != DataMode.REAL || pending == null || pending.sessionId != active.id ||
            pending.challenge != challenge || input != challenge) {
            throw DomainException(ErrorCode.SESSION_EXPIRED)
        }
        pendingClear = null
        realGraph.maintenance.clearBusinessData()
        val id = UUID.randomUUID().toString()
        mutable.value = ActiveSession(id, DataMode.REAL, bind(realGraph, realDatabase, id))
    }

    private fun bind(source: AppGraph, database: ValnookDatabase, sessionId: String): AppGraph {
        val commands = SessionCommands(sessionId, source.commands)
        return AppGraph(source.accounts, source.cash, source.deposits, source.investments, commands,
            source.clock, source.overview, source.settings, RoomInstruments(database, commands),
            source.cashPages, source.depositPages, source.maintenance)
    }

    private fun graphFor(database: ValnookDatabase, commands: FinancialCommands): AppGraph {
        val accounts = RoomAccounts(database, clock)
        val cash = RoomCash(database.cash())
        val deposits = RoomDeposits(database.deposits())
        return AppGraph(accounts, cash, deposits, RoomInvestments(database, clock), commands, clock,
            RoomOverview(database), RoomSettings(database, clock), RoomInstruments(database, commands),
            cash, deposits, RoomDataMaintenance(database))
    }

    private fun clearAbandonedDemoDatabases() {
        context.databaseList()
            .filter { it.startsWith(DEMO_DATABASE_PREFIX) }
            .forEach(context::deleteDatabase)
    }

    private inner class SessionCommands(
        private val sessionId: String,
        private val delegate: FinancialCommands
    ) : FinancialCommands {
        override suspend fun execute(command: FinancialCommand): OperationResult = mutex.withLock {
            if (mutable.value.id != sessionId) throw DomainException(ErrorCode.SESSION_EXPIRED)
            delegate.execute(command)
        }

        override suspend fun operationResult(operationId: String): OperationResult? = mutex.withLock {
            if (mutable.value.id != sessionId) throw DomainException(ErrorCode.SESSION_EXPIRED)
            delegate.operationResult(operationId)
        }
    }

    private data class PendingClear(val sessionId: String, val challenge: String)

    private companion object {
        const val DEMO_DATABASE_ASSET = "database/valnook-demo-v6.db"
        const val DEMO_DATABASE_PREFIX = "valnook-demo-"
    }
}

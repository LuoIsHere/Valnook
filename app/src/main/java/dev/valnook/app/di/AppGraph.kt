package dev.valnook.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.portability.AppBuildInfo
import dev.valnook.data.portability.RoomPortabilityEngine
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.repository.*
import dev.valnook.domain.cloud.BackupScheduler
import dev.valnook.domain.cloud.CloudAccessProvider
import dev.valnook.domain.cloud.CloudBackupService
import java.time.Clock
import javax.inject.Singleton

/** The only graph exposed to screens. Every mutable capability is bound to [sessionId]. */
class AppGraph(
    val sessionId: String,
    val accounts: AccountRepository,
    val cash: CashRepository,
    val deposits: DepositRepository,
    val investments: InvestmentRepository,
    val commands: FinancialCommands,
    val clock: Clock,
    val overview: OverviewRepository,
    val statistics: StatisticsRepository,
    val settings: SettingsRepository,
    val settingsWriter: SettingsWriter,
    val instruments: InstrumentRepository,
    val cashPages: PagedCashRepository,
    val depositPages: PagedDepositRepository,
    val portability: dev.valnook.domain.portability.DataPortability =
        dev.valnook.domain.portability.UnavailableDataPortability,
    val cloudBackup: CloudBackupService = dev.valnook.domain.cloud.UnavailableCloudBackupService,
    val wallet: WalletRepository? = null,
    val accountGroupWriter: AccountGroupWriter? = null,
    val walletPrivate: WalletPrivateRepository? = null,
    val fxSync: dev.valnook.domain.fx.FxRateSync = dev.valnook.domain.fx.UnavailableFxRateSync
)

/** Raw database capabilities stay inside the session manager and are never handed to UI code. */
internal data class DatabaseGraph(
    val database: ValnookDatabase,
    val accounts: AccountRepository,
    val cash: CashRepository,
    val deposits: DepositRepository,
    val investments: InvestmentRepository,
    val commands: FinancialCommands,
    val clock: Clock,
    val overview: OverviewRepository,
    val settings: SettingsRepository,
    val settingsWriter: SettingsWriter,
    val instruments: InstrumentRepository,
    val cashPages: PagedCashRepository,
    val depositPages: PagedDepositRepository,
    val maintenance: DataMaintenance,
    val portability: RoomPortabilityEngine,
    val webAdminReads: dev.valnook.domain.webadmin.WebAdminReadRepository,
    val walletPrivate: LocalWalletPrivateRepository
)

internal fun createDatabaseGraph(
    context: Context,
    database: ValnookDatabase,
    clock: Clock,
    buildInfo: AppBuildInfo,
    persistentPrivateCards: Boolean = true
): DatabaseGraph {
    val cash = RoomCash(database.cash())
    val deposits = RoomDeposits(database.deposits())
    // The device's own language, unaffected by the language chosen in this app.
    val localeManager = context.getSystemService(android.app.LocaleManager::class.java)
    val settings = RoomSettings(database, clock) {
        localeManager.systemLocales.get(0)?.language ?: java.util.Locale.getDefault().language
    }
    return DatabaseGraph(
        database = database,
        accounts = RoomAccounts(database),
        cash = cash,
        deposits = deposits,
        investments = RoomInvestments(database),
        commands = RoomFinancialCommands(database, clock),
        clock = clock,
        overview = RoomOverview(database),
        settings = settings,
        settingsWriter = settings,
        instruments = RoomInstruments(database),
        cashPages = cash,
        depositPages = deposits,
        maintenance = RoomDataMaintenance(database, clock),
        portability = RoomPortabilityEngine(context, database, clock, buildInfo),
        webAdminReads = dev.valnook.data.webadmin.RoomWebAdminReadRepository(
            database, RoomOverview(database), RoomStatistics(database, clock),
            deposits, RoomInvestments(database)),
        walletPrivate = LocalWalletPrivateRepository(context, database, persistentPrivateCards)
    )
}

internal fun currentBuildInfo(): AppBuildInfo = AppBuildInfo(
    applicationFamily = "valnook",
    appVersion = dev.valnook.app.BuildConfig.VERSION_NAME,
    appVersionCode = dev.valnook.app.BuildConfig.VERSION_CODE.toLong(),
    internalBuildRevision = dev.valnook.app.BuildConfig.INTERNAL_BUILD_ID,
    internalBuildLabel = dev.valnook.app.BuildConfig.INTERNAL_BUILD_ID,
    databaseSchemaVersion = dev.valnook.data.database.DATABASE_SCHEMA_VERSION
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun onboarding(@ApplicationContext context: Context): dev.valnook.app.onboarding.OnboardingStorage =
        dev.valnook.app.onboarding.OnboardingPreferences(context)

    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ValnookDatabase = ValnookDatabase.open(context)

    @Provides
    @Singleton
    fun clock(): Clock = DeviceClock

    /**
     * The system clock in the device's current time zone, read on each use: after travelling, months and days follow
     * the new zone without restarting, as the dates shown with [java.time.ZoneId.systemDefault] already do.
     */
    private object DeviceClock : Clock() {
        override fun getZone(): java.time.ZoneId = java.time.ZoneId.systemDefault()
        override fun withZone(zone: java.time.ZoneId): Clock = system(zone)
        override fun instant(): java.time.Instant = java.time.Instant.now()
        override fun millis(): Long = System.currentTimeMillis()
    }

    @Provides
    @Singleton
    internal fun databaseGraph(
        @ApplicationContext context: Context,
        database: ValnookDatabase,
        clock: Clock
    ): DatabaseGraph = createDatabaseGraph(context, database, clock, currentBuildInfo())

    @Provides
    @Singleton
    fun cloudAccessProvider(gateway: dev.valnook.app.cloud.MicrosoftAuthorizationGateway): CloudAccessProvider = gateway

    @Provides
    @Singleton
    fun cloudAuthorization(gateway: dev.valnook.app.cloud.MicrosoftAuthorizationGateway):
        dev.valnook.feature.backup.CloudAuthorization = gateway

    @Provides
    @Singleton
    fun cloudBackupService(coordinator: dev.valnook.data.cloud.CloudBackupCoordinator): CloudBackupService = coordinator

    @Provides
    @Singleton
    fun fxSyncEngine(database: ValnookDatabase, clock: Clock): dev.valnook.data.fx.FxSyncEngine =
        dev.valnook.data.fx.createFxSyncCoordinator(database, clock)

    @Provides
    @Singleton
    fun backupScheduler(@ApplicationContext context: Context): BackupScheduler =
        dev.valnook.app.cloud.WorkManagerBackupScheduler(context)

    @Provides
    @Singleton
    internal fun cloudBackupCoordinator(
        @ApplicationContext context: Context,
        database: ValnookDatabase,
        graph: DatabaseGraph,
        accessProvider: CloudAccessProvider,
        scheduler: BackupScheduler
    ): dev.valnook.data.cloud.CloudBackupCoordinator = dev.valnook.data.cloud.createCloudBackupCoordinator(
        context, database, graph.portability, accessProvider, scheduler)
}

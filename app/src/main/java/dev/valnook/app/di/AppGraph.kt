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
    val cloudBackup: CloudBackupService = dev.valnook.domain.cloud.UnavailableCloudBackupService
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
    val webAdminReads: dev.valnook.domain.webadmin.WebAdminReadRepository
)

internal fun createDatabaseGraph(
    context: Context,
    database: ValnookDatabase,
    clock: Clock,
    buildInfo: AppBuildInfo
): DatabaseGraph {
    val cash = RoomCash(database.cash())
    val deposits = RoomDeposits(database.deposits())
    val settings = RoomSettings(database, clock)
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
        maintenance = RoomDataMaintenance(database),
        portability = RoomPortabilityEngine(context, database, clock, buildInfo),
        webAdminReads = dev.valnook.data.webadmin.RoomWebAdminReadRepository(
            database, RoomOverview(database), RoomStatistics(database, clock),
            deposits, RoomInvestments(database))
    )
}

internal fun currentBuildInfo(): AppBuildInfo = AppBuildInfo(
    applicationFamily = "valnook",
    appVersion = dev.valnook.app.BuildConfig.VERSION_NAME,
    appVersionCode = dev.valnook.app.BuildConfig.VERSION_CODE.toLong(),
    internalBuildRevision = dev.valnook.app.BuildConfig.INTERNAL_BUILD_ID,
    internalBuildLabel = dev.valnook.app.BuildConfig.INTERNAL_BUILD_ID,
    databaseSchemaVersion = 10
)

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun database(@ApplicationContext context: Context): ValnookDatabase = ValnookDatabase.open(context)

    @Provides
    @Singleton
    fun clock(): Clock = Clock.systemDefaultZone()

    @Provides
    @Singleton
    internal fun databaseGraph(
        @ApplicationContext context: Context,
        database: ValnookDatabase,
        clock: Clock
    ): DatabaseGraph = createDatabaseGraph(context, database, clock, currentBuildInfo())

    @Provides
    @Singleton
    fun cloudAccessProvider(gateway: dev.valnook.app.cloud.GoogleAuthorizationGateway): CloudAccessProvider = gateway

    @Provides
    @Singleton
    fun googleDriveAuthorization(gateway: dev.valnook.app.cloud.GoogleAuthorizationGateway):
        dev.valnook.feature.backup.GoogleDriveAuthorization = gateway

    @Provides
    @Singleton
    fun cloudBackupService(coordinator: dev.valnook.data.cloud.CloudBackupCoordinator): CloudBackupService = coordinator

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

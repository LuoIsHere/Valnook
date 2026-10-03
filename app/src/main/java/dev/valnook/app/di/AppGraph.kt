package dev.valnook.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.repository.*
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
    val depositPages: PagedDepositRepository
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
    val maintenance: DataMaintenance
)

internal fun createDatabaseGraph(database: ValnookDatabase, clock: Clock): DatabaseGraph {
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
        maintenance = RoomDataMaintenance(database)
    )
}

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
    internal fun databaseGraph(database: ValnookDatabase, clock: Clock): DatabaseGraph =
        createDatabaseGraph(database, clock)
}

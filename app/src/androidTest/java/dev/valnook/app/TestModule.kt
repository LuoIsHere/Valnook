package dev.valnook.app
import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Singleton
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import dev.valnook.app.di.AppModule
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.repository.*

@Module
@TestInstallIn(components=[SingletonComponent::class],replaces=[AppModule::class])
object TestModule {
    @Provides @Singleton fun database(@ApplicationContext context:Context)=
        Room.inMemoryDatabaseBuilder(context,ValnookDatabase::class.java).addCallback(ValnookDatabase.seed).build()
    @Provides @Singleton fun clock():Clock=Clock.fixed(Instant.parse("2026-09-30T12:00:00Z"),ZoneId.systemDefault())
    @Provides @Singleton fun accounts(db:ValnookDatabase,clock:Clock):AccountRepository=RoomAccounts(db,clock)
    @Provides @Singleton fun cash(db:ValnookDatabase):CashRepository=RoomCash(db.ledger())
    @Provides @Singleton fun deposits(db:ValnookDatabase):DepositRepository=RoomDeposits(db.ledger())
    @Provides @Singleton fun investments(db:ValnookDatabase,clock:Clock):InvestmentRepository=RoomInvestments(db,clock)
    @Provides @Singleton fun commands(db:ValnookDatabase,clock:Clock):FinancialCommands=RoomFinancialCommands(db,clock)
    @Provides @Singleton fun overview(db:ValnookDatabase):OverviewRepository=RoomOverview(db)
    @Provides @Singleton fun settings(db:ValnookDatabase,clock:Clock):SettingsRepository=RoomSettings(db,clock)
    @Provides @Singleton fun instruments(db:ValnookDatabase,commands:FinancialCommands):InstrumentRepository=RoomInstruments(db,commands)
    @Provides @Singleton fun cashPages(cash:CashRepository):PagedCashRepository=cash as PagedCashRepository
    @Provides @Singleton fun depositPages(deposits:DepositRepository):PagedDepositRepository=deposits as PagedDepositRepository
    @Provides @Singleton fun maintenance(db:ValnookDatabase):DataMaintenance=RoomDataMaintenance(db)
}

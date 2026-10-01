package dev.valnook.app.di
import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import java.time.Clock
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.data.repository.*
import dev.valnook.data.transaction.RoomFinancialCommands
import dev.valnook.domain.repository.*

class AppGraph @Inject constructor(val accounts:AccountRepository,val cash:CashRepository,
    val deposits:DepositRepository,val investments:InvestmentRepository,val commands:FinancialCommands,val clock:Clock,
    val overview:OverviewRepository,val settings:SettingsRepository,val instruments:InstrumentRepository,
    val cashPages:PagedCashRepository,val depositPages:PagedDepositRepository)
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton fun database(@ApplicationContext context:Context)=ValnookDatabase.open(context)
    @Provides @Singleton fun clock():Clock=Clock.systemDefaultZone()
    @Provides @Singleton fun accounts(db:ValnookDatabase,clock:Clock):AccountRepository=RoomAccounts(db,clock)
    @Provides @Singleton fun cash(db:ValnookDatabase):CashRepository=RoomCash(db.cash())
    @Provides @Singleton fun cashPages(cash:CashRepository):PagedCashRepository=cash as PagedCashRepository
    @Provides @Singleton fun deposits(db:ValnookDatabase):DepositRepository=RoomDeposits(db.deposits())
    @Provides @Singleton fun depositPages(deposits:DepositRepository):PagedDepositRepository=deposits as PagedDepositRepository
    @Provides @Singleton fun investments(db:ValnookDatabase,clock:Clock):InvestmentRepository=RoomInvestments(db,clock)
    @Provides @Singleton fun commands(db:ValnookDatabase,clock:Clock):FinancialCommands=RoomFinancialCommands(db,clock)
    @Provides @Singleton fun overview(db:ValnookDatabase):OverviewRepository=RoomOverview(db)
    @Provides @Singleton fun settings(db:ValnookDatabase,clock:Clock):SettingsRepository=RoomSettings(db,clock)
    @Provides @Singleton fun instruments(db:ValnookDatabase,commands:FinancialCommands):InstrumentRepository=RoomInstruments(db,commands)
}

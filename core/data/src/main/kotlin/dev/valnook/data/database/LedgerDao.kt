package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

/** Compatibility facade for existing integration fixtures; production uses focused DAOs. */
@Dao
interface LedgerDao : AccountDao, CashDao, DepositDao, TradeDao, OperationDao, PositionDao, InstrumentDao

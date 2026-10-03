package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

const val INSTRUMENT_PROJECTION = "SELECT s.*,t.name AS type_name FROM instruments s JOIN asset_types t ON t.id=s.asset_type_id"

@Dao
interface InstrumentDao {
    @Query(INSTRUMENT_PROJECTION + " ORDER BY s.name,s.id")
    fun instruments(): Flow<List<InstrumentWithType>>
    @Query(INSTRUMENT_PROJECTION + " WHERE s.id=:id")
    fun observeInstrument(id: Long): Flow<InstrumentWithType?>
    @Query("SELECT * FROM instruments WHERE id=:id")
    suspend fun instrument(id: Long): InstrumentEntity?
    @Insert suspend fun insertInstrument(value: InstrumentEntity): Long
    @Update suspend fun updateInstrument(value: InstrumentEntity): Int
    @Query("UPDATE instruments SET currency_locked=1 WHERE id=:id")
    suspend fun lockCurrency(id: Long): Int
    @Query("UPDATE instruments SET currency_locked=1,symbol_locked=1 WHERE id=:id")
    suspend fun lockTradeIdentity(id: Long): Int
    @Query("""UPDATE instruments SET current_price_e5=:price,price_updated_at_ms=:effective,
        revision=revision+1,updated_at_ms=:now WHERE id=:id""")
    suspend fun updateCurrentPriceFromHistory(id: Long, price: Long, effective: Long, now: Long): Int
    @Query("SELECT * FROM asset_types ORDER BY normalized_name,id")
    fun types(): Flow<List<TypeEntity>>
    @Query("SELECT * FROM asset_types WHERE id=:id")
    suspend fun type(id: Long): TypeEntity?
    @Query("SELECT id FROM asset_types WHERE normalized_name=:normalized")
    suspend fun type_by_name(normalized: String): Long?
    @Insert suspend fun insert_type(value: TypeEntity): Long
    @Query("UPDATE asset_types SET name=:name,normalized_name=:normalized,updated_at_ms=:now WHERE id=:id")
    suspend fun edit_type(id: Long, name: String, normalized: String, now: Long): Int
}

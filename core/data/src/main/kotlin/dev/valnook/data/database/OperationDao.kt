package dev.valnook.data.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface OperationDao {
    @Query("SELECT operation_id,kind,request_fingerprint,result_kind,result_id,created_at_ms FROM operations WHERE operation_id=:id")
    suspend fun operation(id: String): OperationEntity?
    @Insert suspend fun insert_operation(value: OperationEntity)
    @Query("UPDATE operations SET result_kind=:kind,result_id=:id WHERE operation_id=:operation_id")
    suspend fun complete_operation(operation_id: String, kind: String, id: Long)
}

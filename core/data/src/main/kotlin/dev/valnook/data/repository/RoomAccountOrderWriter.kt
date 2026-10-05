package dev.valnook.data.repository

import androidx.room.withTransaction
import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.model.DomainException
import dev.valnook.domain.model.ErrorCode
import dev.valnook.domain.repository.AccountOrderWriter

class RoomAccountOrderWriter(private val db: ValnookDatabase) : AccountOrderWriter {
    override suspend fun saveOrder(expectedOrder: List<Long>, orderedIds: List<Long>) {
        db.withTransaction {
            val current = db.overview().allAccounts().map { it.id }
            if (current != expectedOrder) throw DomainException(ErrorCode.STALE_RECORD)
            if (orderedIds.size != current.size || orderedIds.toSet() != current.toSet()) {
                throw DomainException(ErrorCode.OPERATION_CONFLICT)
            }
            orderedIds.forEachIndexed { index, id ->
                if (db.accounts().setDisplayOrder(id, index.toLong()) != 1) {
                    throw DomainException(ErrorCode.STALE_RECORD)
                }
            }
        }
    }
}

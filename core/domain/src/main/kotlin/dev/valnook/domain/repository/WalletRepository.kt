package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

interface WalletRepository {
    fun observeCards(): Flow<List<WalletCard>>
    suspend fun image(key: String): WalletImage?
    suspend fun save(id: Long?, expectedRevision: Long?, name: String, cashAccountId: Long?,
        imageKey: String?, image: WalletImage? = null): Long
    suspend fun delete(id: Long, expectedRevision: Long)
    suspend fun reorder(expected: List<Long>, ordered: List<Long>)
}

data class LedgerBounds(val firstMs: Long?, val lastMs: Long?)

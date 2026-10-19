package dev.valnook.domain.repository

import dev.valnook.domain.model.*
import kotlinx.coroutines.flow.Flow

interface WalletRepository {
    fun observeCards(): Flow<List<WalletCard>>
    suspend fun image(key: String): WalletImage?
    suspend fun save(id: Long?, expectedRevision: Long?, name: String, cashAccountId: Long?,
        imageKey: String?, image: WalletImage? = null, faceStyle: WalletFaceStyle? = null): Long
    suspend fun delete(id: Long, expectedRevision: Long)
    suspend fun reorder(expected: List<Long>, ordered: List<Long>)
    /** Newest first; carries no image bytes, which [image] loads on demand. */
    fun observeFaces(): Flow<List<WalletFace>>
    /** Adds a face to the library without applying it to a card. */
    suspend fun addFace(label: String, image: WalletImage)
    /** Deletes a face no card uses. */
    suspend fun deleteFace(key: String)
}

data class LedgerBounds(val firstMs: Long?, val lastMs: Long?)

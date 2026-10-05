package dev.valnook.domain.repository

/** Presentation metadata; updating it must not create financial movements or invalidate valuations. */
interface AccountOrderWriter {
    suspend fun saveOrder(expectedOrder: List<Long>, orderedIds: List<Long>)
}

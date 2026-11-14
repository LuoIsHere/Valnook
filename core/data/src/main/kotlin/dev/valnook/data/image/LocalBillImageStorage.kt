package dev.valnook.data.image

import dev.valnook.data.database.ValnookDatabase
import dev.valnook.domain.repository.BillImageCleanup
import dev.valnook.domain.repository.BillImageStorage
import dev.valnook.domain.repository.BillImageUsage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LocalBillImageStorage(private val store: BillImageStore, private val db: ValnookDatabase,
    private val preferences: BillImagePreferences) : BillImageStorage {
    override fun observeUsage(): Flow<BillImageUsage> = db.bills().observeImageUsage().map {
        BillImageUsage(it.on_device, it.on_device_bytes, it.only_in_cloud, it.not_uploaded)
    }
    override fun observeAutoCleanMonths(): Flow<Int?> = preferences.autoCleanMonths
    override suspend fun setAutoCleanMonths(months: Int?) = preferences.setAutoCleanMonths(months)
    override suspend fun clearDevice(savedBeforeMs: Long?): BillImageCleanup = store.clearDevice(savedBeforeMs)
    override suspend fun deleteEverywhere(savedBeforeMs: Long?): Int = store.deleteEverywhere(savedBeforeMs)
}

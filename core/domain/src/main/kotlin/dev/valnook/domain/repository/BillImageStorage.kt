package dev.valnook.domain.repository

import kotlinx.coroutines.flow.Flow

/** How much room bill pictures take here, and where the rest are. */
data class BillImageUsage(
    val onDevice: Int = 0,
    val onDeviceBytes: Long = 0,
    /** Bills' pictures only in OneDrive. */
    val onlyInCloud: Int = 0,
    /** Bills' pictures on this device that OneDrive does not have yet. */
    val notUploaded: Int = 0,
)

/** What a clean-up of this device did: pictures removed, and those kept because OneDrive does not have them yet. */
data class BillImageCleanup(val removed: Int, val keptNotUploaded: Int)

/** Choices for the automatic clean-up, in months of 30 days. */
val BILL_IMAGE_CLEANUP_MONTHS = listOf(1, 3, 6, 12)

/** Keeping the device's bill pictures in check: by hand, or automatically once they are in OneDrive. */
interface BillImageStorage {
    fun observeUsage(): Flow<BillImageUsage>
    /** Months after which pictures in OneDrive leave this device; null when the automatic clean-up is off. */
    fun observeAutoCleanMonths(): Flow<Int?>
    suspend fun setAutoCleanMonths(months: Int?)
    /**
     * Removes from this device the bills' pictures saved here before [savedBeforeMs] (all when null) that OneDrive
     * holds; they stay on their bills, to fetch again. Those OneDrive lacks are kept.
     */
    suspend fun clearDevice(savedBeforeMs: Long?): BillImageCleanup
    /**
     * Takes the bills' pictures saved here before [savedBeforeMs] (all when null) off their bills and this device for
     * good; OneDrive's copies go at the next backup. Returns how many went.
     */
    suspend fun deleteEverywhere(savedBeforeMs: Long?): Int
}

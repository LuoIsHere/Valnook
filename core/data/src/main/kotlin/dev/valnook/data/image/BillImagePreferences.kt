package dev.valnook.data.image

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** This device's automatic clean-up of bill pictures: months of 30 days, or off. Never backed up. */
class BillImagePreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("valnook_bill_images", Context.MODE_PRIVATE)
    private val months = MutableStateFlow(prefs.getInt(KEY, 0).takeIf { it > 0 })

    val autoCleanMonths: StateFlow<Int?> get() = months

    fun setAutoCleanMonths(value: Int?) {
        prefs.edit().putInt(KEY, value ?: 0).apply()
        months.value = value
    }

    /** Pictures saved here before this time go when the clean-up runs; null when it is off. */
    fun cutoff(nowMs: Long): Long? = months.value?.let { nowMs - it * MONTH_MS }

    companion object {
        private const val KEY = "auto_clean_months"
        const val MONTH_MS = 30L * 24 * 60 * 60 * 1000
        @Volatile private var shared: BillImagePreferences? = null
        /** One per process, so a change made on the settings page is what the next backup sees. */
        fun of(context: Context): BillImagePreferences =
            shared ?: synchronized(this) { shared ?: BillImagePreferences(context).also { shared = it } }
    }
}

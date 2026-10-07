package dev.valnook.data.cloud

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import dev.valnook.domain.cloud.NetworkUtcClock
import java.time.DateTimeException

internal fun interface NetworkAvailability { fun connected(): Boolean }

internal class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val manager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    override fun connected(): Boolean {
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}

/** API 33+ trusted network clock. It cannot be changed through the device wall-clock setting. */
internal object AndroidNetworkUtcClock : NetworkUtcClock {
    override suspend fun nowUtcMs(): Long? = try {
        SystemClock.currentNetworkTimeClock().millis()
    } catch (_: DateTimeException) {
        null
    } catch (_: IllegalStateException) {
        null
    }
}

package dev.valnook.data.webadmin

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Inet4Address

/** Platform boundary for targetSdk-specific LAN access policy. v0.0.6 targets API 36. */
interface LocalNetworkAccessPolicy {
    fun allowedWithoutRuntimePermission(): Boolean
}

class Target36LocalNetworkAccessPolicy : LocalNetworkAccessPolicy {
    override fun allowedWithoutRuntimePermission(): Boolean = true
}

class LanAddressResolver(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    fun resolvePrivateIpv4(): String? {
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!eligible(capabilities)) return null
        return connectivity.getLinkProperties(network)?.linkAddresses.orEmpty()
            .asSequence().map { it.address }.filterIsInstance<Inet4Address>()
            .firstOrNull(::isPrivateLan)?.hostAddress
    }

    fun watch(expectedAddress: String, onInvalidated: () -> Unit): AutoCloseable {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLost(network: Network) = check()
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = check()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = check()
            private fun check() {
                // Callbacks can arrive for unrelated transports. This matters on emulators and
                // devices that expose Wi-Fi and Ethernet concurrently: losing either one must not
                // stop a server whose exact bound address is still present on the other network.
                if (!addressStillAvailable(expectedAddress)) onInvalidated()
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        return AutoCloseable { runCatching { connectivity.unregisterNetworkCallback(callback) } }
    }

    private fun addressStillAvailable(expectedAddress: String): Boolean = connectivity.allNetworks.any { network ->
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return@any false
        if (!eligible(capabilities)) return@any false
        connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().any { link ->
            link.address is Inet4Address && link.address.hostAddress == expectedAddress &&
                isPrivateLan(link.address as Inet4Address)
        }
    }

    private fun eligible(capabilities: NetworkCapabilities): Boolean {
        val supported = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        return supported && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    companion object {
        fun isPrivateLan(address: Inet4Address): Boolean {
            val bytes = address.address.map { it.toInt() and 0xff }
            return bytes[0] == 10 ||
                (bytes[0] == 172 && bytes[1] in 16..31) ||
                (bytes[0] == 192 && bytes[1] == 168)
        }
    }
}

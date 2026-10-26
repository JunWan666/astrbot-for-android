package io.astrbot.android.runtime

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Display Wi-Fi/Ethernet IPv4 addresses, excluding mobile and VPN-only interfaces. */
object LanAccess {
    fun addresses(context: Context): List<String> = runCatching {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        connectivity.allNetworks.flatMap { network ->
            val capabilities = connectivity.getNetworkCapabilities(network)
            if (capabilities == null || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
                !(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))) emptyList()
            else connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                val address = link.address
                address.hostAddress?.takeIf { address is Inet4Address && !address.isLoopbackAddress && !address.isAnyLocalAddress }
            }
        }.distinct().sorted()
    }.getOrDefault(emptyList())
}

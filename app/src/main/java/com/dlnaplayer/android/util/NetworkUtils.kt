package com.dlnaplayer.android.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.util.Log
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    private const val TAG = "NetworkUtils"

    /**
     * Retrieves the IPv4 address of the device on the local network (Wi-Fi or Ethernet).
     */
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            // Prioritize wlan0, then eth0, then others
            val sortedInterfaces = interfaces.sortedByDescending { iface ->
                when {
                    iface.name.startsWith("wlan", ignoreCase = true) -> 3
                    iface.name.startsWith("eth", ignoreCase = true) -> 2
                    iface.isLoopback || !iface.isUp -> 0
                    else -> 1
                }
            }

            for (networkInterface in sortedInterfaces) {
                if (networkInterface.isLoopback || !networkInterface.isUp) continue

                val addresses = Collections.list(networkInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        val host = address.hostAddress
                        if (host != null && !host.startsWith("127.")) {
                            return host
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error obtaining local IP address", e)
        }
        return null
    }

    /**
     * Finds the local IP address on the interface matching the target device's /24 subnet.
     * Prevents advertising cellular or VPN IPs to a TV on the local Wi-Fi subnet.
     */
    fun getLocalIpForTarget(targetIp: String?): String {
        val fallback = getLocalIpAddress() ?: "127.0.0.1"
        if (targetIp.isNullOrBlank()) return fallback

        try {
            val parts = targetIp.trim().split(".").take(3)
            if (parts.size == 3) {
                val targetPrefix = parts.joinToString(".") + "."
                val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
                for (iface in interfaces) {
                    if (iface.isLoopback || !iface.isUp) continue
                    for (address in Collections.list(iface.inetAddresses)) {
                        if (!address.isLoopbackAddress && address is Inet4Address) {
                            val host = address.hostAddress ?: continue
                            if (host.startsWith(targetPrefix)) {
                                return host
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error matching local IP for target $targetIp", e)
        }
        return fallback
    }

    /**
     * Checks whether the device is currently connected to an active Wi-Fi network.
     */
    fun isWifiConnected(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Retrieves all active non-loopback network interfaces supporting multicast with IPv4 addresses.
     */
    fun getAllActiveMulticastInterfaces(): List<NetworkInterface> {
        return try {
            Collections.list(NetworkInterface.getNetworkInterfaces()).filter { iface ->
                !iface.isLoopback && iface.isUp &&
                Collections.list(iface.inetAddresses).any { !it.isLoopbackAddress && it is Inet4Address }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Returns the /24 subnet prefix of the local IP (e.g. "192.168.1.").
     */
    fun getSubnetPrefix(): String? {
        val ip = getLocalIpAddress() ?: return null
        val lastDot = ip.lastIndexOf('.')
        return if (lastDot != -1) ip.substring(0, lastDot + 1) else null
    }

    /**
     * Acquires a WifiManager MulticastLock to allow incoming and outgoing UDP multicast
     * packets for SSDP discovery on Android.
     */
    fun createMulticastLock(context: Context, tag: String = "DLNA_SSDP_LOCK"): WifiManager.MulticastLock? {
        return try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifiManager?.createMulticastLock(tag)?.apply {
                setReferenceCounted(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MulticastLock", e)
            null
        }
    }
}

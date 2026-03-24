package com.gamebooster.app

import android.content.Context
import android.net.*
import android.net.wifi.WifiManager
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

/**
 * NetworkMonitor — Real-time network statistics using Android SDK.
 *
 * HOW IT WORKS:
 * - ConnectivityManager.NetworkCallback tracks active network changes
 * - WifiManager provides signal strength for WiFi
 * - Ping is measured by attempting ICMP reachability to 8.8.8.8 (Google DNS)
 *   via InetAddress.isReachable(), which is a real round-trip measurement
 * - TrafficStats provides cumulative TX/RX byte counts per process
 *
 * No fake latency values — all data comes from real OS measurements.
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val wifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    enum class NetworkType { WIFI, MOBILE_5G, MOBILE_4G, MOBILE_3G, MOBILE_2G, NONE }

    data class NetworkStatus(
        val type: NetworkType,
        val isConnected: Boolean,
        val pingMs: Long,          // Real measured ping in milliseconds
        val wifiSignalBars: Int,   // 0–4 signal bars for WiFi
        val suggestion: String     // Human-readable recommendation
    )

    // ─── Network Type Detection ───────────────────────────────────────────────

    fun getNetworkType(): NetworkType {
        val network = connectivityManager.activeNetwork ?: return NetworkType.NONE
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return NetworkType.NONE

        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> detectMobileGeneration(caps)
            else -> NetworkType.NONE
        }
    }

    private fun detectMobileGeneration(caps: NetworkCapabilities): NetworkType {
        // Bandwidth-based heuristic for mobile generation detection
        val downMbps = caps.linkDownstreamBandwidthKbps / 1000
        return when {
            downMbps >= 100 -> NetworkType.MOBILE_5G    // 5G: typically 100+ Mbps
            downMbps >= 20  -> NetworkType.MOBILE_4G    // LTE: 20–150 Mbps
            downMbps >= 1   -> NetworkType.MOBILE_3G    // 3G: 1–20 Mbps
            else            -> NetworkType.MOBILE_2G    // 2G: < 1 Mbps
        }
    }

    fun isConnected(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        val caps = connectivityManager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    // ─── Ping Measurement ────────────────────────────────────────────────────

    /**
     * Measures real ICMP ping to 8.8.8.8 (Google Public DNS).
     * Uses InetAddress.isReachable() which performs an actual round-trip.
     * Returns -1 if unreachable.
     */
    suspend fun measurePingMs(): Long = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        return@withContext try {
            val reachable = InetAddress.getByName("8.8.8.8")
                .isReachable(3000) // 3 second timeout
            if (reachable) System.currentTimeMillis() - start else -1L
        } catch (_: Exception) {
            -1L
        }
    }

    /**
     * Measures ping via system ping binary for better accuracy (ICMP).
     * Falls back to InetAddress if ping binary is unavailable.
     */
    suspend fun measurePingAccurate(): Long = withContext(Dispatchers.IO) {
        return@withContext try {
            val start = System.currentTimeMillis()
            val process = Runtime.getRuntime().exec("ping -c 1 -W 2 8.8.8.8")
            val exitCode = process.waitFor()
            if (exitCode == 0) System.currentTimeMillis() - start else -1L
        } catch (_: Exception) {
            measurePingMs()
        }
    }

    // ─── WiFi Signal ─────────────────────────────────────────────────────────

    fun getWifiSignalBars(): Int {
        val wifiInfo = wifiManager.connectionInfo ?: return 0
        val rssi = wifiInfo.rssi
        return WifiManager.calculateSignalLevel(rssi, 5) // Returns 0–4
    }

    fun getWifiSpeedMbps(): Int {
        return try {
            wifiManager.connectionInfo?.linkSpeed ?: 0 // In Mbps
        } catch (_: Exception) { 0 }
    }

    // ─── Data Usage ──────────────────────────────────────────────────────────

    /**
     * Returns cumulative bytes received since device boot for the current UID.
     * Useful for showing per-session data usage.
     */
    fun getSessionRxBytes(): Long = android.net.TrafficStats.getUidRxBytes(context.applicationInfo.uid)
    fun getSessionTxBytes(): Long = android.net.TrafficStats.getUidTxBytes(context.applicationInfo.uid)

    // ─── Full Status ──────────────────────────────────────────────────────────

    suspend fun getNetworkStatus(): NetworkStatus {
        val type = getNetworkType()
        val connected = isConnected()
        val ping = if (connected) measurePingAccurate() else -1L
        val wifiSignal = if (type == NetworkType.WIFI) getWifiSignalBars() else 0

        val suggestion = when {
            !connected -> "No connection — connect to WiFi or Mobile Data"
            ping > 150 -> "High latency (${ping}ms) — switch to WiFi for better gaming"
            ping > 80  -> "Moderate latency — close background downloads"
            type == NetworkType.MOBILE_2G || type == NetworkType.MOBILE_3G ->
                "Slow mobile network — switch to WiFi for best experience"
            type == NetworkType.WIFI && wifiSignal <= 1 ->
                "Weak WiFi signal — move closer to router"
            else -> "Network looks good for gaming (${ping}ms)"
        }

        return NetworkStatus(
            type = type,
            isConnected = connected,
            pingMs = ping,
            wifiSignalBars = wifiSignal,
            suggestion = suggestion
        )
    }

    fun getNetworkTypeLabel(type: NetworkType): String = when (type) {
        NetworkType.WIFI       -> "WiFi"
        NetworkType.MOBILE_5G  -> "5G"
        NetworkType.MOBILE_4G  -> "4G LTE"
        NetworkType.MOBILE_3G  -> "3G"
        NetworkType.MOBILE_2G  -> "2G"
        NetworkType.NONE       -> "No Network"
    }

    // ─── Network Callback ────────────────────────────────────────────────────

    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    fun startMonitoring(onChanged: (NetworkType) -> Unit) {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val type = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ->
                        detectMobileGeneration(caps)
                    else -> NetworkType.NONE
                }
                onChanged(type)
            }
            override fun onLost(network: Network) = onChanged(NetworkType.NONE)
        }

        connectivityManager.registerNetworkCallback(request, networkCallback!!)
    }

    fun stopMonitoring() {
        networkCallback?.let {
            try { connectivityManager.unregisterNetworkCallback(it) } catch (_: Exception) {}
            networkCallback = null
        }
    }
}

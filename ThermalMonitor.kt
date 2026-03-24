package com.gamebooster.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData

/**
 * ThermalMonitor — Device temperature monitoring using BatteryManager.
 *
 * HOW IT WORKS:
 * Android exposes battery temperature via the ACTION_BATTERY_CHANGED sticky broadcast.
 * Temperature is reported in tenths of a degree Celsius (divide by 10.0 to get °C).
 *
 * On Android 10+ we also check ThermalService for CPU/skin thermal status,
 * which provides more comprehensive thermal information.
 *
 * Note: Battery temperature is a proxy for device temperature. Under heavy
 * gaming load, battery temp closely correlates with SoC temperature.
 */
class ThermalMonitor(private val context: Context) {

    enum class ThermalState {
        COOL,       // < 35°C
        WARM,       // 35–40°C
        HOT,        // 40–45°C
        CRITICAL    // > 45°C
    }

    data class ThermalStatus(
        val batteryTempCelsius: Float,
        val thermalState: ThermalState,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val warning: String?
    )

    private val _thermalStatus = MutableLiveData<ThermalStatus>()
    val thermalStatus: LiveData<ThermalStatus> = _thermalStatus

    // ─── Sticky Broadcast Reading ─────────────────────────────────────────────

    /**
     * Reads the current battery / thermal status.
     * ACTION_BATTERY_CHANGED is a sticky broadcast — we can get the last
     * value instantly without registering a persistent receiver.
     */
    fun getCurrentStatus(): ThermalStatus {
        val intent = context.registerReceiver(
            null,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )

        // Temperature in tenths of a degree Celsius
        val rawTemp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val tempC = rawTemp / 10.0f

        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val batteryPct = (level * 100 / scale)

        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                       status == BatteryManager.BATTERY_STATUS_FULL

        val state = when {
            tempC >= 45f -> ThermalState.CRITICAL
            tempC >= 40f -> ThermalState.HOT
            tempC >= 35f -> ThermalState.WARM
            else         -> ThermalState.COOL
        }

        val warning = when (state) {
            ThermalState.CRITICAL ->
                "⚠️ Critical: ${tempC}°C — Stop gaming to prevent throttling!"
            ThermalState.HOT ->
                "🔥 Hot: ${tempC}°C — Device may throttle. Take a break."
            ThermalState.WARM ->
                "🌡 Warm: ${tempC}°C — Monitor temperature"
            ThermalState.COOL -> null
        }

        return ThermalStatus(
            batteryTempCelsius = tempC,
            thermalState = state,
            batteryLevel = batteryPct,
            isCharging = charging,
            warning = warning
        )
    }

    // ─── Continuous Monitoring ────────────────────────────────────────────────

    private var batteryReceiver: BroadcastReceiver? = null

    fun startMonitoring() {
        batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    _thermalStatus.postValue(getCurrentStatus())
                }
            }
        }
        context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
    }

    fun stopMonitoring() {
        batteryReceiver?.let {
            try { context.unregisterReceiver(it) } catch (_: Exception) {}
            batteryReceiver = null
        }
    }

    // ─── Android 10+ Thermal API ──────────────────────────────────────────────

    /**
     * Android 10 (API 29) introduced PowerManager.getThermalHeadroom()
     * which provides normalized thermal headroom (0.0 = cool, 1.0 = throttling).
     * We use this as an additional signal when available.
     */
    fun getThermalHeadroom(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val pm = context.getSystemService(Context.POWER_SERVICE)
                    as android.os.PowerManager
            pm.getThermalHeadroom(5) // Forecast 5 seconds ahead
        } catch (_: Exception) {
            null
        }
    }

    fun getThermalStateLabel(state: ThermalState): String = when (state) {
        ThermalState.COOL     -> "Cool"
        ThermalState.WARM     -> "Warm"
        ThermalState.HOT      -> "Hot"
        ThermalState.CRITICAL -> "Critical"
    }

    fun getThermalStateColor(state: ThermalState): Int = when (state) {
        ThermalState.COOL     -> 0xFF4CAF50.toInt()  // Green
        ThermalState.WARM     -> 0xFFFF9800.toInt()  // Orange
        ThermalState.HOT      -> 0xFFFF5722.toInt()  // Deep Orange
        ThermalState.CRITICAL -> 0xFFF44336.toInt()  // Red
    }
}

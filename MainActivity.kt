package com.gamebooster.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.gamebooster.app.databinding.ActivityMainBinding
import kotlinx.coroutines.*

/**
 * MainActivity — The main Game Booster dashboard.
 *
 * Layout sections:
 * ┌──────────────────────────────┐
 * │  RAM Bar + Usage %           │
 * │  Temperature + State         │
 * │  Network Status + Ping       │
 * │  [BOOST] Button (center)     │
 * │  DND Toggle                  │
 * │  FPS Overlay Toggle          │
 * │  [Open Game Library] Button  │
 * └──────────────────────────────┘
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var boostEngine: BoostEngine
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var dndManager: DndManager

    private var isBoosting = false
    private var isFpsOverlayOn = false
    private var updateJob: Job? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        boostEngine = BoostEngine(this)
        thermalMonitor = ThermalMonitor(this)
        networkMonitor = NetworkMonitor(this)
        dndManager = DndManager(this)

        setupUI()
        startPeriodicUpdate()
    }

    override fun onResume() {
        super.onResume()
        refreshStats()
        updateDndToggleState()
    }

    override fun onDestroy() {
        updateJob?.cancel()
        networkMonitor.stopMonitoring()
        super.onDestroy()
    }

    // ─── UI Setup ─────────────────────────────────────────────────────────────

    private fun setupUI() {

        // ── BOOST Button ──────────────────────────────────────────────────────
        binding.btnBoost.setOnClickListener {
            if (!isBoosting) performBoost()
        }

        // ── DND Toggle ────────────────────────────────────────────────────────
        binding.switchDnd.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!dndManager.hasDndPermission()) {
                    binding.switchDnd.isChecked = false
                    Toast.makeText(this,
                        "Grant Do Not Disturb access in Settings",
                        Toast.LENGTH_LONG).show()
                    dndManager.requestDndPermission()
                } else {
                    dndManager.enableGamingDnd()
                    binding.tvDndStatus.text = "DND: Gaming Mode (Alarms Only)"
                }
            } else {
                dndManager.disableDnd()
                binding.tvDndStatus.text = "DND: Off"
            }
        }

        // ── FPS Overlay Toggle ────────────────────────────────────────────────
        binding.switchFps.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                if (!Settings.canDrawOverlays(this)) {
                    binding.switchFps.isChecked = false
                    Toast.makeText(this,
                        "Grant 'Display over other apps' permission",
                        Toast.LENGTH_LONG).show()
                    requestOverlayPermission()
                } else {
                    startFpsOverlay()
                    isFpsOverlayOn = true
                }
            } else {
                stopFpsOverlay()
                isFpsOverlayOn = false
            }
        }

        // ── Game Library ──────────────────────────────────────────────────────
        binding.btnGameLibrary.setOnClickListener {
            startActivity(Intent(this, GameLibraryActivity::class.java))
        }

        // ── Gaming Mode Toggle ────────────────────────────────────────────────
        binding.switchGamingMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                BoostForegroundService.start(this)
                binding.tvGamingModeStatus.text = "Gaming Mode: Active"
                binding.tvGamingModeStatus.setTextColor(0xFF4CAF50.toInt())
            } else {
                BoostForegroundService.stop(this)
                binding.tvGamingModeStatus.text = "Gaming Mode: Off"
                binding.tvGamingModeStatus.setTextColor(0xFF9E9E9E.toInt())
            }
        }
    }

    // ─── Periodic Stats Update ────────────────────────────────────────────────

    private fun startPeriodicUpdate() {
        updateJob = lifecycleScope.launch {
            while (isActive) {
                refreshStats()
                delay(5_000L) // Update every 5 seconds
            }
        }
    }

    private fun refreshStats() {
        lifecycleScope.launch {
            updateRamStats()
            updateThermalStats()
            updateNetworkStats()
        }
    }

    // ─── RAM Stats ────────────────────────────────────────────────────────────

    private fun updateRamStats() {
        val snap = boostEngine.getMemorySnapshot()
        val usedPct = boostEngine.getRamUsagePercent()

        binding.tvRamUsed.text = boostEngine.formatBytes(snap.usedRam)
        binding.tvRamTotal.text = boostEngine.formatBytes(snap.totalRam)
        binding.tvRamPercent.text = "$usedPct%"
        binding.progressRam.progress = usedPct

        // Color code the progress bar
        val barColor = when {
            usedPct >= 85 -> 0xFFF44336.toInt()  // Red
            usedPct >= 70 -> 0xFFFF9800.toInt()  // Orange
            else          -> 0xFF4CAF50.toInt()  // Green
        }
        binding.progressRam.progressTintList =
            android.content.res.ColorStateList.valueOf(barColor)

        binding.tvBgApps.text = "Background processes: " +
            boostEngine.getBackgroundPackages().size
    }

    // ─── Thermal Stats ────────────────────────────────────────────────────────

    private fun updateThermalStats() {
        val thermal = thermalMonitor.getCurrentStatus()

        binding.tvTemperature.text = "%.1f°C".format(thermal.batteryTempCelsius)
        binding.tvThermalState.text = thermalMonitor.getThermalStateLabel(thermal.thermalState)
        binding.tvThermalState.setTextColor(
            thermalMonitor.getThermalStateColor(thermal.thermalState)
        )
        binding.tvBatteryLevel.text = "Battery: ${thermal.batteryLevel}%"

        // Show warning banner if hot
        if (thermal.thermalState == ThermalMonitor.ThermalState.HOT ||
            thermal.thermalState == ThermalMonitor.ThermalState.CRITICAL) {
            binding.tvThermalWarning.visibility = View.VISIBLE
            binding.tvThermalWarning.text = thermal.warning
        } else {
            binding.tvThermalWarning.visibility = View.GONE
        }
    }

    // ─── Network Stats ────────────────────────────────────────────────────────

    private fun updateNetworkStats() {
        lifecycleScope.launch(Dispatchers.IO) {
            val status = networkMonitor.getNetworkStatus()
            withContext(Dispatchers.Main) {
                binding.tvNetworkType.text = networkMonitor.getNetworkTypeLabel(status.type)
                binding.tvPing.text = if (status.pingMs >= 0) "${status.pingMs}ms" else "N/A"
                binding.tvNetworkSuggestion.text = status.suggestion

                val pingColor = when {
                    !status.isConnected    -> 0xFF9E9E9E.toInt()
                    status.pingMs < 50     -> 0xFF4CAF50.toInt()  // Green
                    status.pingMs < 100    -> 0xFFFF9800.toInt()  // Orange
                    else                   -> 0xFFF44336.toInt()  // Red
                }
                binding.tvPing.setTextColor(pingColor)
            }
        }
    }

    // ─── Boost Action ─────────────────────────────────────────────────────────

    private fun performBoost() {
        isBoosting = true
        binding.btnBoost.isEnabled = false
        binding.tvBoostStatus.text = "Boosting..."
        binding.tvBoostStatus.visibility = View.VISIBLE

        lifecycleScope.launch {
            val result = boostEngine.boost()

            withContext(Dispatchers.Main) {
                isBoosting = false
                binding.btnBoost.isEnabled = true

                val freedMb = result.after.freedRam / 1_048_576.0
                val msg = if (freedMb > 0) {
                    "✓ Freed %.0f MB | Killed ${result.killedProcessCount} processes".format(freedMb)
                } else {
                    "✓ Memory already optimized (${result.killedProcessCount} processes cleared)"
                }

                binding.tvBoostStatus.text = msg
                updateRamStats()

                // Hide status after 4 seconds
                Handler(Looper.getMainLooper()).postDelayed({
                    binding.tvBoostStatus.visibility = View.GONE
                }, 4000)
            }
        }
    }

    // ─── DND State Sync ───────────────────────────────────────────────────────

    private fun updateDndToggleState() {
        val isDndOn = dndManager.isDndActive() && dndManager.hasDndPermission()
        binding.switchDnd.isChecked = isDndOn
        binding.tvDndStatus.text = if (isDndOn) {
            "DND: ${dndManager.getCurrentFilterLabel()}"
        } else {
            "DND: Off"
        }
    }

    // ─── FPS Overlay ──────────────────────────────────────────────────────────

    private fun startFpsOverlay() {
        val intent = Intent(this, FpsOverlayService::class.java)
        startService(intent)
        Toast.makeText(this, "FPS overlay started", Toast.LENGTH_SHORT).show()
    }

    private fun stopFpsOverlay() {
        val intent = Intent(this, FpsOverlayService::class.java)
            .apply { action = FpsOverlayService.ACTION_STOP }
        startService(intent)
    }

    // ─── Permissions ──────────────────────────────────────────────────────────

    private fun requestOverlayPermission() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName")
        )
        startActivity(intent)
    }
}

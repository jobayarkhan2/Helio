package com.gamebooster.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

/**
 * BoostForegroundService — Keeps the boost session alive while the user is gaming.
 *
 * HOW IT WORKS:
 * As a foreground service, Android gives this process elevated scheduling priority
 * and won't kill it when under memory pressure. The service:
 *
 * 1. Runs periodic RAM cleanup every 90 seconds (kills newly started background apps)
 * 2. Monitors device temperature and posts warnings via notification update
 * 3. Keeps DND active throughout the gaming session
 * 4. Displays a persistent notification (Android requirement for foreground services)
 *
 * The foreground service runs at elevated process priority, which also helps
 * keep the game's companion services alive.
 */
class BoostForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var boostEngine: BoostEngine
    private lateinit var thermalMonitor: ThermalMonitor
    private lateinit var dndManager: DndManager

    private val handler = Handler(Looper.getMainLooper())
    private var monitorJob: Job? = null

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        boostEngine = BoostEngine(this)
        thermalMonitor = ThermalMonitor(this)
        dndManager = DndManager(this)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopBoostSession()
                return START_NOT_STICKY
            }
            else -> startBoostSession()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopBoostSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ─── Boost Session ────────────────────────────────────────────────────────

    private fun startBoostSession() {
        startForeground(NOTIFICATION_ID, buildNotification("Boost Active", "Gaming mode ON"))

        // Enable DND if permission is granted
        if (dndManager.hasDndPermission()) {
            dndManager.enableGamingDnd()
        }

        // Boost CPU priority for our service thread
        boostEngine.setHighCpuPriority()

        // Start periodic background monitoring
        monitorJob = serviceScope.launch {
            while (isActive) {
                delay(90_000L) // Every 90 seconds

                // Re-kill newly launched background apps
                boostEngine.killBackgroundProcesses()

                // Check temperature
                val thermal = thermalMonitor.getCurrentStatus()
                if (thermal.thermalState == ThermalMonitor.ThermalState.CRITICAL ||
                    thermal.thermalState == ThermalMonitor.ThermalState.HOT) {
                    updateNotification(
                        "⚠️ Device Heating Up",
                        "${thermal.batteryTempCelsius}°C — Consider taking a break"
                    )
                } else {
                    updateNotification(
                        "Boost Active",
                        "Gaming mode ON — ${thermal.batteryTempCelsius}°C"
                    )
                }
            }
        }
    }

    private fun stopBoostSession() {
        monitorJob?.cancel()
        dndManager.disableDnd()
        boostEngine.setNormalCpuPriority()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    // ─── Notifications ────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Game Booster Session",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows active boost session status"
            setShowBadge(false)
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(title: String, message: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, BoostForegroundService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_delete, "Stop Boost", stopIntent)
            .setOngoing(true)
            .setShowWhen(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(title: String, message: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(title, message))
    }

    companion object {
        const val ACTION_STOP = "com.gamebooster.app.BOOST_STOP"
        private const val CHANNEL_ID = "boost_session"
        private const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, BoostForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, BoostForegroundService::class.java)
                .apply { action = ACTION_STOP }
            context.startService(intent)
        }
    }
}

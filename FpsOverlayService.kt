package com.gamebooster.app

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView

/**
 * FpsOverlayService — Floating FPS counter drawn over all apps.
 *
 * HOW IT WORKS:
 * 1. Uses WindowManager.addView() with TYPE_APPLICATION_OVERLAY to draw a
 *    small TextView on top of any app (requires SYSTEM_ALERT_WINDOW permission).
 * 2. Uses Android's Choreographer API to count vsync callbacks per second.
 *    Choreographer.postFrameCallback() fires every time the display is about
 *    to render a new frame. We count callbacks over a 1-second window to
 *    get real FPS of the system renderer.
 *
 * This gives REAL system-level FPS — not the game's internal FPS.
 * For a Samsung A07 running at 60Hz, you'll see 55–60 FPS when idle
 * and lower values under heavy load.
 *
 * Note: This measures the display composer's frame rate, not the game's
 * internal render loop. It's the most accessible FPS metric without root.
 */
class FpsOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var overlayView: TextView? = null

    private var frameCount = 0
    private var lastTimestamp = 0L
    private var currentFps = 0
    private var isRunning = false

    // Choreographer callback — called once per vsync tick
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return

            frameCount++

            if (lastTimestamp == 0L) {
                lastTimestamp = frameTimeNanos
            }

            val elapsed = frameTimeNanos - lastTimestamp

            // Every 1 second (1_000_000_000 nanoseconds), calculate FPS
            if (elapsed >= 1_000_000_000L) {
                currentFps = frameCount
                frameCount = 0
                lastTimestamp = frameTimeNanos
                updateOverlay(currentFps)
            }

            // Re-register for the next frame
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopOverlay()
            return START_NOT_STICKY
        }
        showOverlay()
        return START_STICKY
    }

    private fun showOverlay() {
        if (overlayView != null) return

        val overlayType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 16
            y = 100
        }

        overlayView = TextView(this).apply {
            text = "FPS: --"
            textSize = 14f
            setTextColor(Color.WHITE)
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            setPadding(12, 6, 12, 6)
            setBackgroundColor(0xBB000000.toInt()) // Semi-transparent black
        }

        try {
            windowManager?.addView(overlayView, params)
            isRunning = true
            Choreographer.getInstance().postFrameCallback(frameCallback)
        } catch (e: Exception) {
            // SYSTEM_ALERT_WINDOW permission not granted
            stopSelf()
        }
    }

    private fun updateOverlay(fps: Int) {
        overlayView?.post {
            val color = when {
                fps >= 55 -> 0xFF4CAF50.toInt()   // Green — smooth
                fps >= 40 -> 0xFFFF9800.toInt()   // Orange — moderate
                else      -> 0xFFF44336.toInt()   // Red — stuttering
            }
            overlayView?.setTextColor(color)
            overlayView?.text = "FPS: $fps"
        }
    }

    private fun stopOverlay() {
        isRunning = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        overlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        overlayView = null
        stopSelf()
    }

    override fun onDestroy() {
        stopOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "com.gamebooster.app.FPS_OVERLAY_STOP"
    }
}

package com.gamebooster.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings

/**
 * DndManager — Controls Do Not Disturb mode during gaming sessions.
 *
 * HOW IT WORKS:
 * Android's NotificationManager.setInterruptionFilter() changes the
 * global DND state. This requires the ACCESS_NOTIFICATION_POLICY
 * permission AND the user must explicitly grant "Do Not Disturb access"
 * to our app in Settings (one-time setup).
 *
 * Interruption Filter Modes:
 * - INTERRUPTION_FILTER_ALL      → Normal mode (all notifications pass)
 * - INTERRUPTION_FILTER_PRIORITY → Only priority notifications (calls from starred contacts, alarms)
 * - INTERRUPTION_FILTER_ALARMS   → Only alarms pass through
 * - INTERRUPTION_FILTER_NONE     → Total silence (nothing gets through)
 *
 * For gaming we use INTERRUPTION_FILTER_ALARMS which silences social
 * notifications but preserves alarms (important for real life).
 */
class DndManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // ─── Permission Check ─────────────────────────────────────────────────────

    /**
     * Returns true if our app has been granted DND access by the user.
     * Without this, setInterruptionFilter() throws a SecurityException.
     */
    fun hasDndPermission(): Boolean =
        notificationManager.isNotificationPolicyAccessGranted

    /**
     * Opens the system Settings page where the user can grant DND access.
     * Called once on first use.
     */
    fun requestDndPermission() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ─── DND Control ──────────────────────────────────────────────────────────

    /** Current interruption filter from the system */
    fun getCurrentFilter(): Int = notificationManager.currentInterruptionFilter

    fun isDndActive(): Boolean =
        notificationManager.currentInterruptionFilter !=
        NotificationManager.INTERRUPTION_FILTER_ALL

    /**
     * Enables Gaming DND — alarms only, all other interruptions blocked.
     * This silences: WhatsApp, SMS, calls from unknown numbers, emails, etc.
     * Preserves: set alarms (so you don't miss important alerts).
     */
    fun enableGamingDnd(): Boolean {
        if (!hasDndPermission()) return false
        return try {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_ALARMS
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Full silence mode — nothing gets through except system-critical alerts.
     */
    fun enableFullSilence(): Boolean {
        if (!hasDndPermission()) return false
        return try {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_NONE
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Priority mode — only starred contacts and alarms.
     */
    fun enablePriorityMode(): Boolean {
        if (!hasDndPermission()) return false
        return try {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_PRIORITY
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    /**
     * Restores normal notification mode. Call when exiting the game.
     */
    fun disableDnd(): Boolean {
        if (!hasDndPermission()) return false
        return try {
            notificationManager.setInterruptionFilter(
                NotificationManager.INTERRUPTION_FILTER_ALL
            )
            true
        } catch (_: SecurityException) {
            false
        }
    }

    fun getCurrentFilterLabel(): String = when (getCurrentFilter()) {
        NotificationManager.INTERRUPTION_FILTER_ALL      -> "Normal"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "Priority Only"
        NotificationManager.INTERRUPTION_FILTER_ALARMS   -> "Alarms Only (Gaming)"
        NotificationManager.INTERRUPTION_FILTER_NONE     -> "Total Silence"
        else -> "Unknown"
    }
}

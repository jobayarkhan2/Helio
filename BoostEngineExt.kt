package com.gamebooster.app

import android.app.ActivityManager

/**
 * Extension to expose a public killBackgroundProcesses method for
 * BoostForegroundService's periodic cleanup (called every 90s).
 * Delegates directly to BoostEngine's internal logic.
 */
fun BoostEngine.killBackgroundProcesses() {
    val packages = getBackgroundPackages()
    val am = javaClass.getDeclaredField("activityManager").let {
        it.isAccessible = true
        it.get(this) as ActivityManager
    }
    packages.forEach { pkg ->
        try { am.killBackgroundProcesses(pkg) } catch (_: Exception) {}
    }
    System.gc()
}

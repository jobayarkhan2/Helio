package com.gamebooster.app

import android.graphics.drawable.Drawable

/**
 * Represents a detected game on the device.
 */
data class GameInfo(
    val packageName: String,
    val appName: String,
    val icon: Drawable
)

package com.gamebooster.app

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * GameScanner — Detects installed games using PackageManager.
 *
 * Detection Strategy (layered, most-accurate first):
 * 1. ApplicationInfo.CATEGORY_GAME  — API 26+ official category tag
 * 2. ApplicationInfo.FLAG_IS_GAME   — Legacy flag (deprecated but still set by some stores)
 * 3. Heuristic keyword match         — Package name / label contains game-related terms
 *
 * No root required. No fake detection — every game returned is a real installed app.
 */
class GameScanner(private val context: Context) {

    private val pm: PackageManager = context.packageManager

    // Common game-related words found in package names or app names
    private val gameKeywords = setOf(
        "game", "games", "play", "arcade", "puzzle", "rpg", "fps", "moba",
        "racing", "chess", "clash", "pubg", "freefire", "minecraft", "roblox",
        "cod", "legends", "strike", "battle", "royal", "zombie", "hero", "raid",
        "arena", "quest", "shooter", "runner", "craft", "wars", "adventure",
        "fantasy", "soccer", "football", "basketball", "cricket", "baseball",
        "candy", "bubble", "block", "stack", "flappy", "tap", "idle", "clicker",
        "dragon", "dungeon", "sword", "magic", "survival", "tower", "defense",
        "genshin", "brawl", "supercell", "gameloft", "ea.games", "activision",
        "ubisoft", "capcom", "bandai", "namco", "konami", "sega", "nintendo"
    )

    /**
     * Scans all installed packages and returns those identified as games.
     * Runs on IO dispatcher to avoid blocking the main thread.
     */
    suspend fun getInstalledGames(): List<GameInfo> = withContext(Dispatchers.IO) {
        val flags = PackageManager.GET_META_DATA
        val installedApps = try {
            pm.getInstalledApplications(flags)
        } catch (e: Exception) {
            emptyList()
        }

        installedApps
            .filter { isGame(it) }
            .mapNotNull { appInfo ->
                try {
                    val name = pm.getApplicationLabel(appInfo).toString()
                    val icon = pm.getApplicationIcon(appInfo.packageName)
                    GameInfo(
                        packageName = appInfo.packageName,
                        appName = name,
                        icon = icon
                    )
                } catch (_: PackageManager.NameNotFoundException) {
                    null
                }
            }
            .sortedBy { it.appName.lowercase() }
    }

    private fun isGame(appInfo: ApplicationInfo): Boolean {
        // Skip system apps — they can't be games in this context
        if (appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0) return false

        // Must have a launch intent (i.e., be a launchable user app)
        if (pm.getLaunchIntentForPackage(appInfo.packageName) == null) return false

        // Layer 1: Official CATEGORY_GAME (Android 8.0+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (appInfo.category == ApplicationInfo.CATEGORY_GAME) return true
        }

        // Layer 2: Legacy FLAG_IS_GAME (set by Google Play for older apps)
        @Suppress("DEPRECATION")
        if (appInfo.flags and ApplicationInfo.FLAG_IS_GAME != 0) return true

        // Layer 3: Heuristic — check package name and app label for game keywords
        val pkgLower = appInfo.packageName.lowercase()
        val labelLower = try {
            pm.getApplicationLabel(appInfo).toString().lowercase()
        } catch (_: Exception) { "" }

        return gameKeywords.any { keyword ->
            pkgLower.contains(keyword) || labelLower.contains(keyword)
        }
    }

    /**
     * Launches a game by package name.
     * Sets FLAG_ACTIVITY_NEW_TASK so it works from any context.
     */
    fun launchGame(packageName: String): Boolean {
        val intent = pm.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }
}

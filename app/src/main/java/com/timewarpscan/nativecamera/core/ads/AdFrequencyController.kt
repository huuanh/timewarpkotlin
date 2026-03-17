package com.timewarpscan.nativecamera.core.ads

import android.os.SystemClock
import com.timewarpscan.nativecamera.core.config.ConfigKeys
import com.timewarpscan.nativecamera.core.config.ConfigManager

/**
 * Controls how often interstitial ads are displayed.
 *
 * Two independent gates — BOTH must pass for [shouldShowAd] to return true:
 *   1. Action counter: show every N user actions (e.g. every 3 scans)
 *   2. Cooldown timer: minimum time between two interstitials
 *
 * All thresholds are read from [ConfigManager] so they can be changed remotely.
 */
class AdFrequencyController {

    private var actionCount = 0
    private var lastShowTimeMs = 0L

    /** Call this when the user performs a countable action (e.g. completes a scan). */
    fun recordAction() {
        actionCount++
    }

    /** Returns true if an interstitial should be displayed right now. */
    fun shouldShowAd(): Boolean {
        if (!ConfigManager.isAdsEnabled()) return false

        val frequency = ConfigManager.getInt(ConfigKeys.INTERSTITIAL_FREQUENCY)
        val cooldownMs = ConfigManager.getInt(ConfigKeys.INTERSTITIAL_COOLDOWN_MS).toLong()

        // Gate 1: enough actions accumulated
        if (frequency <= 0 || actionCount < frequency) return false

        // Gate 2: cooldown elapsed
        val now = SystemClock.elapsedRealtime()
        if (lastShowTimeMs > 0 && (now - lastShowTimeMs) < cooldownMs) return false

        return true
    }

    /** Call this after an interstitial is actually shown. Resets counters. */
    fun onAdShown() {
        actionCount = 0
        lastShowTimeMs = SystemClock.elapsedRealtime()
    }

    /** Reset all counters (e.g. on app cold start). */
    fun reset() {
        actionCount = 0
        lastShowTimeMs = 0L
    }
}

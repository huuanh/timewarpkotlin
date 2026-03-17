package com.timewarpscan.nativecamera.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log

/**
 * Centralized configuration system — singleton.
 *
 * Architecture:
 *   1. Local defaults (hardcoded in [LocalConfigProvider])
 *   2. Remote overrides (interface [RemoteConfigProvider] — implement with Firebase later)
 *   3. Runtime overrides (e.g. IAPManager sets isPremium)
 *
 * Resolution order: runtime override > remote > local default.
 * Thread-safe: runtime overrides stored in a ConcurrentHashMap.
 */
object ConfigManager {

    private const val TAG = "ConfigManager"
    private const val PREFS_NAME = "core_config"

    private lateinit var prefs: SharedPreferences

    // --- Config layers ---
    private val localDefaults = LocalConfigProvider.defaults
    private val runtimeOverrides = java.util.concurrent.ConcurrentHashMap<String, Any>()
    private var remoteProvider: RemoteConfigProvider? = null

    /** Must be called once from Application.onCreate(). */
    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Restore persisted premium state
        if (prefs.contains(ConfigKeys.IS_PREMIUM)) {
            runtimeOverrides[ConfigKeys.IS_PREMIUM] = prefs.getBoolean(ConfigKeys.IS_PREMIUM, false)
        }
        Log.d(TAG, "ConfigManager initialized")
    }

    /** Attach a remote config provider (e.g. Firebase Remote Config). */
    fun setRemoteProvider(provider: RemoteConfigProvider) {
        remoteProvider = provider
    }

    // -----------------------------------------------------------------------
    // Typed getters — resolve in order: runtime > remote > local
    // -----------------------------------------------------------------------

    fun getBoolean(key: String): Boolean {
        (runtimeOverrides[key] as? Boolean)?.let { return it }
        remoteProvider?.getBoolean(key)?.let { return it }
        return localDefaults[key] as? Boolean ?: false
    }

    fun getInt(key: String): Int {
        (runtimeOverrides[key] as? Int)?.let { return it }
        remoteProvider?.getInt(key)?.let { return it }
        return localDefaults[key] as? Int ?: 0
    }

    fun getLong(key: String): Long {
        (runtimeOverrides[key] as? Long)?.let { return it }
        remoteProvider?.getLong(key)?.let { return it }
        return localDefaults[key] as? Long ?: 0L
    }

    fun getString(key: String): String {
        (runtimeOverrides[key] as? String)?.let { return it }
        remoteProvider?.getString(key)?.let { return it }
        return localDefaults[key] as? String ?: ""
    }

    // -----------------------------------------------------------------------
    // Runtime override setters
    // -----------------------------------------------------------------------

    fun setBoolean(key: String, value: Boolean) {
        runtimeOverrides[key] = value
    }

    fun setInt(key: String, value: Int) {
        runtimeOverrides[key] = value
    }

    // -----------------------------------------------------------------------
    // Convenience accessors
    // -----------------------------------------------------------------------

    /** True if ads should be displayed (not premium AND ads feature enabled). */
    fun isAdsEnabled(): Boolean =
        !isPremium() && getBoolean(ConfigKeys.ADS_ENABLED)

    /** True if user has purchased premium (removes ads, unlocks features). */
    fun isPremium(): Boolean = getBoolean(ConfigKeys.IS_PREMIUM)

    /** Called by IAPManager when purchase state changes. Persists across restarts. */
    fun setPremium(premium: Boolean) {
        runtimeOverrides[ConfigKeys.IS_PREMIUM] = premium
        if (::prefs.isInitialized) {
            prefs.edit().putBoolean(ConfigKeys.IS_PREMIUM, premium).apply()
        }
        Log.d(TAG, "Premium state updated: $premium")
    }
}

// ---------------------------------------------------------------------------
// Local defaults — hardcoded fallback values
// ---------------------------------------------------------------------------

internal object LocalConfigProvider {
    val defaults: Map<String, Any> = mapOf(
        // Feature flags
        ConfigKeys.ADS_ENABLED to true,
        ConfigKeys.IAP_ENABLED to true,
        ConfigKeys.APP_OPEN_AD_ENABLED to true,
        ConfigKeys.INTERSTITIAL_ENABLED to true,
        ConfigKeys.REWARDED_ENABLED to true,
        ConfigKeys.NATIVE_AD_ENABLED to true,
        ConfigKeys.BANNER_ENABLED to true,

        // Ad frequency
        ConfigKeys.INTERSTITIAL_FREQUENCY to 3,       // every 3 actions
        ConfigKeys.INTERSTITIAL_COOLDOWN_MS to 30_000, // 30s cooldown

        // Scan defaults
        ConfigKeys.SCAN_DURATION_MS to 15_000L,
        ConfigKeys.SCAN_DEFAULT_DIRECTION to "down",

        // Premium
        ConfigKeys.IS_PREMIUM to false,
    )
}

// ---------------------------------------------------------------------------
// Remote config provider interface — implement with Firebase later
// ---------------------------------------------------------------------------

/**
 * Interface for remote configuration sources.
 * Returns null if the key is not available remotely (falls back to local).
 */
interface RemoteConfigProvider {
    fun getBoolean(key: String): Boolean?
    fun getInt(key: String): Int?
    fun getLong(key: String): Long?
    fun getString(key: String): String?
}

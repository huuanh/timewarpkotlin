package com.timewarpscan.nativecamera.core.config

/**
 * Centralized config key constants — prevents hardcoded strings throughout the app.
 * All keys follow a "category.name" naming convention.
 */
object ConfigKeys {

    // --- Feature flags ---
    const val ADS_ENABLED = "ads.enabled"
    const val IAP_ENABLED = "iap.enabled"
    const val APP_OPEN_AD_ENABLED = "ads.app_open_enabled"
    const val INTERSTITIAL_ENABLED = "ads.interstitial_enabled"
    const val REWARDED_ENABLED = "ads.rewarded_enabled"
    const val NATIVE_AD_ENABLED = "ads.native_enabled"
    const val BANNER_ENABLED = "ads.banner_enabled"

    // --- Ad frequency ---
    const val INTERSTITIAL_FREQUENCY = "ads.interstitial_frequency"   // show every N actions
    const val INTERSTITIAL_COOLDOWN_MS = "ads.interstitial_cooldown"  // min ms between interstitials

    // --- Scan defaults ---
    const val SCAN_DURATION_MS = "scan.duration_ms"
    const val SCAN_DEFAULT_DIRECTION = "scan.default_direction"

    // --- Premium ---
    const val IS_PREMIUM = "user.is_premium"
}

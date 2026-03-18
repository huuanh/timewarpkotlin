package com.timewarpscan.nativecamera.core.ads

/**
 * Ad unit IDs and format-level enable/disable flags.
 *
 * Currently using Google's official test IDs.
 * Replace with real IDs before publishing to production.
 * https://developers.google.com/admob/android/test-ads
 */
object AdConfig {

    /** AdMob Application ID — set in AndroidManifest.xml <meta-data>. */
    const val APP_ID = "ca-app-pub-3940256099942544~3347511713" // test app ID

    // --- Test Ad Unit IDs ---
    const val APP_OPEN_ID       = "ca-app-pub-3940256099942544/9257395921"
    const val REWARDED_ID       = "ca-app-pub-3940256099942544/5224354917"
    const val NATIVE_ID         = "ca-app-pub-3940256099942544/2247696110"
    const val BANNER_ID         = "ca-app-pub-3940256099942544/9214589741"

    // --- Interstitial Ad Unit IDs (multiple placements) ---
    /** Default interstitial — kept for backward compatibility */
    const val INTERSTITIAL_ID   = "ca-app-pub-3940256099942544/1033173712"

    /** All interstitial ad unit IDs that should be preloaded. */
    val INTERSTITIAL_IDS: List<String> = listOf(
        INTERSTITIAL_ID,
        // Add more interstitial IDs for different placements here:
        // "ca-app-pub-xxxxxxx/yyyyyyyy",
    )
}

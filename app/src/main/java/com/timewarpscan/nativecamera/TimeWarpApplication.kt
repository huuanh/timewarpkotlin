package com.timewarpscan.nativecamera

import android.app.Application
import android.util.Log
import com.timewarpscan.nativecamera.core.ads.AdManager
import com.timewarpscan.nativecamera.core.config.ConfigManager
import com.timewarpscan.nativecamera.core.iap.IAPManager

/**
 * Application class — initializes all core modules in the correct order:
 *   1. ConfigManager (no dependencies)
 *   2. AdManager (depends on ConfigManager for feature flags)
 *   3. IAPManager (depends on ConfigManager for premium state)
 *
 * AdManager preloads all enabled ad formats.
 * IAPManager connects to Google Play Billing and restores purchases.
 * AppOpenAdManager is registered as a lifecycle observer automatically.
 */
class TimeWarpApplication : Application() {

    companion object {
        private const val TAG = "TimeWarpApp"
    }

    override fun onCreate() {
        super.onCreate()

        // 1. Config — must be first, other modules read from it
        ConfigManager.init(this)
        Log.d(TAG, "ConfigManager ready")

        // 2. Ads — init SDK, register lifecycle observers, preload
        AdManager.init(this)
        AdManager.loadAds()
        Log.d(TAG, "AdManager ready, ads preloading")

        // 3. IAP — connect billing client, restore purchases
        IAPManager.init(this)
        Log.d(TAG, "IAPManager ready, connecting to billing")
    }
}

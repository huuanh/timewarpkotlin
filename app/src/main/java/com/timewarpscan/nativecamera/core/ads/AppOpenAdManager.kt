package com.timewarpscan.nativecamera.core.ads

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.appopen.AppOpenAd
import com.timewarpscan.nativecamera.core.config.ConfigKeys
import com.timewarpscan.nativecamera.core.config.ConfigManager

/**
 * Manages App Open Ads — shows an ad when the app comes to the foreground.
 *
 * Uses [ProcessLifecycleOwner] to detect foreground events and
 * [Application.ActivityLifecycleCallbacks] to track the current activity.
 *
 * The ad is preloaded and cached. When the app resumes from background,
 * the cached ad is shown on the current activity. After dismissal (or failure),
 * a new ad is preloaded automatically.
 */
class AppOpenAdManager(
    private val application: Application
) : DefaultLifecycleObserver, Application.ActivityLifecycleCallbacks {

    companion object {
        private const val TAG = "AppOpenAdManager"
    }

    private var appOpenAd: AppOpenAd? = null
    private var isLoadingAd = false
    private var isShowingAd = false
    private var currentActivity: Activity? = null

    init {
        application.registerActivityLifecycleCallbacks(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    // -----------------------------------------------------------------------
    // Load
    // -----------------------------------------------------------------------

    fun loadAd() {
        if (!ConfigManager.getBoolean(ConfigKeys.APP_OPEN_AD_ENABLED)) return
        if (isLoadingAd || appOpenAd != null) return
        isLoadingAd = true

        AppOpenAd.load(
            application,
            AdConfig.APP_OPEN_ID,
            AdRequest.Builder().build(),
            object : AppOpenAd.AppOpenAdLoadCallback() {
                override fun onAdLoaded(ad: AppOpenAd) {
                    appOpenAd = ad
                    isLoadingAd = false
                    Log.d(TAG, "App Open Ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingAd = false
                    Log.w(TAG, "App Open Ad load failed: ${error.message}")
                }
            }
        )
    }

    // -----------------------------------------------------------------------
    // Show
    // -----------------------------------------------------------------------

    /** Show the cached App Open Ad on the given activity. */
    fun showIfAvailable(activity: Activity) {
        if (isShowingAd) return
        if (!ConfigManager.isAdsEnabled() || !ConfigManager.getBoolean(ConfigKeys.APP_OPEN_AD_ENABLED)) return

        val ad = appOpenAd ?: run {
            loadAd()
            return
        }

        isShowingAd = true
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                appOpenAd = null
                isShowingAd = false
                loadAd() // auto-reload
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                appOpenAd = null
                isShowingAd = false
                loadAd()
            }
        }
        ad.show(activity)
    }

    // -----------------------------------------------------------------------
    // Lifecycle: show ad when app comes to foreground
    // -----------------------------------------------------------------------

    override fun onStart(owner: LifecycleOwner) {
        currentActivity?.let { showIfAvailable(it) }
    }

    // -----------------------------------------------------------------------
    // Activity lifecycle tracking
    // -----------------------------------------------------------------------

    override fun onActivityStarted(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity == activity) currentActivity = null
    }
}

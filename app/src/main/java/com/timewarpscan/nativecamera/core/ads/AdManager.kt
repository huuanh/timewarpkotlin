package com.timewarpscan.nativecamera.core.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdLoader
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.rewarded.RewardedAd
import com.google.android.gms.ads.rewarded.RewardedAdLoadCallback
import com.timewarpscan.nativecamera.core.config.ConfigKeys
import com.timewarpscan.nativecamera.core.config.ConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Centralized ad management — singleton.
 *
 * Manages all AdMob ad formats with preloading, caching, automatic reload,
 * and frequency control. Integrates with [ConfigManager] for feature flags
 * and premium state.
 *
 * Lifecycle: call [init] from Application.onCreate(), then [loadAds].
 * Call [destroyAds] when the app is being destroyed.
 *
 * App Open Ads are managed separately by [AppOpenAdManager].
 */
object AdManager {

    private const val TAG = "AdManager"

    private lateinit var appContext: Context
    private var isInitialized = false

    // --- Cached ads ---
    private var interstitialAd: InterstitialAd? = null
    private var rewardedAd: RewardedAd? = null
    private var nativeAd: NativeAd? = null

    // --- Loading state (prevent double-loads) ---
    private var isLoadingInterstitial = false
    private var isLoadingRewarded = false
    private var isLoadingNative = false

    // --- Frequency control ---
    val frequencyController = AdFrequencyController()

    // --- App Open Ad manager ---
    lateinit var appOpenAdManager: AppOpenAdManager
        private set

    // -----------------------------------------------------------------------
    // Initialization
    // -----------------------------------------------------------------------

    /**
     * Initialize AdMob SDK and prepare the manager.
     * Must be called once from Application.onCreate().
     */
    fun init(application: Application) {
        if (isInitialized) return
        appContext = application.applicationContext

        MobileAds.initialize(application) { initStatus ->
            Log.d(TAG, "MobileAds initialized: ${initStatus.adapterStatusMap}")
        }

        appOpenAdManager = AppOpenAdManager(application)
        isInitialized = true
        Log.d(TAG, "AdManager initialized")
    }

    /**
     * Preload all enabled ad formats.
     * Safe to call multiple times — skips already-loaded or in-flight ads.
     */
    fun loadAds() {
        if (!isInitialized) {
            Log.w(TAG, "loadAds() called before init()")
            return
        }
        if (!ConfigManager.isAdsEnabled()) {
            Log.d(TAG, "Ads disabled (premium or config) — skipping load")
            return
        }

        if (ConfigManager.getBoolean(ConfigKeys.INTERSTITIAL_ENABLED)) loadInterstitial()
        if (ConfigManager.getBoolean(ConfigKeys.REWARDED_ENABLED)) loadRewarded()
        if (ConfigManager.getBoolean(ConfigKeys.NATIVE_AD_ENABLED)) loadNativeAd()
        if (ConfigManager.getBoolean(ConfigKeys.APP_OPEN_AD_ENABLED)) appOpenAdManager.loadAd()
    }

    // -----------------------------------------------------------------------
    // Interstitial
    // -----------------------------------------------------------------------

    private fun loadInterstitial() {
        if (interstitialAd != null || isLoadingInterstitial) return
        isLoadingInterstitial = true

        InterstitialAd.load(
            appContext,
            AdConfig.INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAd = ad
                    isLoadingInterstitial = false
                    Log.d(TAG, "Interstitial loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    interstitialAd = null
                    isLoadingInterstitial = false
                    Log.w(TAG, "Interstitial load failed: ${error.message}")
                }
            }
        )
    }

    /**
     * Show an interstitial ad if one is cached and frequency rules allow it.
     * @param activity the hosting activity
     * @param onDismiss callback after ad is dismissed or if no ad was shown
     */
    fun showInterstitial(activity: Activity, onDismiss: () -> Unit = {}) {
        if (!ConfigManager.isAdsEnabled() || !ConfigManager.getBoolean(ConfigKeys.INTERSTITIAL_ENABLED)) {
            onDismiss()
            return
        }

        val ad = interstitialAd
        if (ad == null) {
            Log.d(TAG, "No interstitial cached — loading for next time")
            loadInterstitial()
            onDismiss()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAd = null
                loadInterstitial() // auto-reload
                onDismiss()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                interstitialAd = null
                loadInterstitial()
                onDismiss()
            }
        }

        frequencyController.onAdShown()
        ad.show(activity)
    }

    /**
     * Convenience: show interstitial only if frequency rules pass.
     * Call [frequencyController.recordAction()] before this.
     */
    fun showInterstitialIfReady(activity: Activity, onDismiss: () -> Unit = {}) {
        if (frequencyController.shouldShowAd()) {
            showInterstitial(activity, onDismiss)
        } else {
            onDismiss()
        }
    }

    // -----------------------------------------------------------------------
    // Rewarded
    // -----------------------------------------------------------------------

    private fun loadRewarded() {
        if (rewardedAd != null || isLoadingRewarded) return
        isLoadingRewarded = true

        RewardedAd.load(
            appContext,
            AdConfig.REWARDED_ID,
            AdRequest.Builder().build(),
            object : RewardedAdLoadCallback() {
                override fun onAdLoaded(ad: RewardedAd) {
                    rewardedAd = ad
                    isLoadingRewarded = false
                    Log.d(TAG, "Rewarded ad loaded")
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    rewardedAd = null
                    isLoadingRewarded = false
                    Log.w(TAG, "Rewarded load failed: ${error.message}")
                }
            }
        )
    }

    /**
     * Show a rewarded ad.
     * @param onRewarded called when user earns the reward
     * @param onDismiss called after ad is dismissed (regardless of reward)
     */
    fun showRewarded(activity: Activity, onRewarded: () -> Unit, onDismiss: () -> Unit = {}) {
        if (!ConfigManager.isAdsEnabled() || !ConfigManager.getBoolean(ConfigKeys.REWARDED_ENABLED)) {
            onDismiss()
            return
        }

        val ad = rewardedAd
        if (ad == null) {
            Log.d(TAG, "No rewarded cached — loading for next time")
            loadRewarded()
            onDismiss()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                rewardedAd = null
                loadRewarded() // auto-reload
                onDismiss()
            }

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                rewardedAd = null
                loadRewarded()
                onDismiss()
            }
        }

        ad.show(activity) { rewardItem ->
            Log.d(TAG, "Reward earned: ${rewardItem.type} x${rewardItem.amount}")
            onRewarded()
        }
    }

    /** True if a rewarded ad is cached and ready to show. */
    fun isRewardedReady(): Boolean = rewardedAd != null

    // -----------------------------------------------------------------------
    // Native Ad
    // -----------------------------------------------------------------------

    private fun loadNativeAd() {
        if (nativeAd != null || isLoadingNative) return
        isLoadingNative = true

        AdLoader.Builder(appContext, AdConfig.NATIVE_ID)
            .forNativeAd { ad ->
                nativeAd?.destroy() // destroy previous if any
                nativeAd = ad
                isLoadingNative = false
                Log.d(TAG, "Native ad loaded")
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(error: LoadAdError) {
                    isLoadingNative = false
                    Log.w(TAG, "Native ad load failed: ${error.message}")
                }
            })
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    /**
     * Get the cached native ad, or null if not available.
     * After consuming, call [loadNativeAd] to reload.
     */
    fun getNativeAd(): NativeAd? {
        if (!ConfigManager.isAdsEnabled() || !ConfigManager.getBoolean(ConfigKeys.NATIVE_AD_ENABLED)) {
            return null
        }
        val ad = nativeAd
        if (ad == null) {
            loadNativeAd()
        }
        return ad
    }

    /** Consume and reload — use when the native ad view is detached. */
    fun consumeNativeAd() {
        nativeAd = null
        loadNativeAd()
    }

    // -----------------------------------------------------------------------
    // Cleanup
    // -----------------------------------------------------------------------

    /** Release all cached ads. Call from Application.onTerminate() or when going premium. */
    fun destroyAds() {
        interstitialAd = null
        rewardedAd = null
        nativeAd?.destroy()
        nativeAd = null
        Log.d(TAG, "All ads destroyed")
    }
}

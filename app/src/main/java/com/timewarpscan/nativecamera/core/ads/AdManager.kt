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

/**
 * Centralized ad management — singleton.
 *
 * Manages all AdMob ad formats with preloading, caching, automatic reload,
 * and frequency control. Integrates with [ConfigManager] for feature flags
 * and premium state.
 *
 * Interstitial ads are cached per ad unit ID in a Map, so multiple
 * placements can be preloaded independently and shown by ID.
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

    // --- Cached ads (interstitials keyed by ad unit ID) ---
    private val interstitialAds = mutableMapOf<String, InterstitialAd>()
    private var rewardedAd: RewardedAd? = null
    private var nativeAd: NativeAd? = null

    // --- Loading state (prevent double-loads) ---
    private val loadingInterstitialIds = mutableSetOf<String>()
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

        if (ConfigManager.getBoolean(ConfigKeys.INTERSTITIAL_ENABLED)) {
            loadAllInterstitials()
        }
        if (ConfigManager.getBoolean(ConfigKeys.REWARDED_ENABLED)) loadRewarded()
        if (ConfigManager.getBoolean(ConfigKeys.NATIVE_AD_ENABLED)) loadNativeAd()
        if (ConfigManager.getBoolean(ConfigKeys.APP_OPEN_AD_ENABLED)) appOpenAdManager.loadAd()
    }

    /**
     * Returns true when all interstitial ad unit IDs have finished loading
     * (success or failure). Useful for the Loading Screen to wait until
     * preload completes before navigating.
     */
    fun areInterstitialsPreloaded(): Boolean {
        if (!ConfigManager.getBoolean(ConfigKeys.INTERSTITIAL_ENABLED)) return true
        return loadingInterstitialIds.isEmpty()
    }

    // -----------------------------------------------------------------------
    // Interstitial
    // -----------------------------------------------------------------------

    /**
     * Preload all interstitial ad unit IDs defined in [AdConfig.INTERSTITIAL_IDS].
     */
    private fun loadAllInterstitials() {
        for (adId in AdConfig.INTERSTITIAL_IDS) {
            loadInterstitial(adId)
        }
    }

    /**
     * Load a single interstitial ad by its ad unit ID.
     * Skips if already cached or currently loading.
     */
    private fun loadInterstitial(adId: String) {
        if (interstitialAds.containsKey(adId) || loadingInterstitialIds.contains(adId)) return
        loadingInterstitialIds.add(adId)

        InterstitialAd.load(
            appContext,
            adId,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    interstitialAds[adId] = ad
                    loadingInterstitialIds.remove(adId)
                    Log.d(TAG, "Interstitial loaded for ID: $adId")
                }

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    loadingInterstitialIds.remove(adId)
                    Log.w(TAG, "Interstitial load failed for ID $adId: ${loadAdError.message}")
                }
            }
        )
    }

    /**
     * Show an interstitial ad for a specific ad unit ID.
     *
     * @param activity the hosting activity
     * @param adId the interstitial ad unit ID to show (default: AdConfig.INTERSTITIAL_ID)
     * @param onDismiss callback after ad is dismissed or if no ad was shown
     */
    fun showInterstitial(
        activity: Activity,
        adId: String = AdConfig.INTERSTITIAL_ID,
        onDismiss: () -> Unit = {}
    ) {
        if (!ConfigManager.isAdsEnabled() || !ConfigManager.getBoolean(ConfigKeys.INTERSTITIAL_ENABLED)) {
            onDismiss()
            return
        }

        val ad = interstitialAds[adId]
        if (ad == null) {
            Log.d(TAG, "No interstitial cached for ID $adId — loading for next time")
            loadInterstitial(adId)
            onDismiss()
            return
        }

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitialAds.remove(adId)
                loadInterstitial(adId) // auto-reload
                onDismiss()
            }

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
                interstitialAds.remove(adId)
                loadInterstitial(adId)
                onDismiss()
            }
        }

        frequencyController.onAdShown()
        ad.show(activity)
    }

    /**
     * Convenience: show interstitial only if frequency rules pass.
     * Call frequencyController.recordAction() before this.
     *
     * @param adId the interstitial ad unit ID to show (default: AdConfig.INTERSTITIAL_ID)
     */
    fun showInterstitialIfReady(
        activity: Activity,
        adId: String = AdConfig.INTERSTITIAL_ID,
        onDismiss: () -> Unit = {}
    ) {
        if (frequencyController.shouldShowAd()) {
            showInterstitial(activity, adId, onDismiss)
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

                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    rewardedAd = null
                    isLoadingRewarded = false
                    Log.w(TAG, "Rewarded load failed: ${loadAdError.message}")
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

            override fun onAdFailedToShowFullScreenContent(adError: AdError) {
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
                nativeAd?.destroy()
                nativeAd = ad
                isLoadingNative = false
                Log.d(TAG, "Native ad loaded")
            }
            .withAdListener(object : AdListener() {
                override fun onAdFailedToLoad(loadAdError: LoadAdError) {
                    isLoadingNative = false
                    Log.w(TAG, "Native ad load failed: ${loadAdError.message}")
                }
            })
            .build()
            .loadAd(AdRequest.Builder().build())
    }

    /**
     * Get the cached native ad, or null if not available.
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
        interstitialAds.clear()
        loadingInterstitialIds.clear()
        rewardedAd = null
        nativeAd?.destroy()
        nativeAd = null
        Log.d(TAG, "All ads destroyed")
    }
}


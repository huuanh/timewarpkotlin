package com.timewarpscan.nativecamera.ui.loading

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.appcompat.app.AppCompatActivity
import com.timewarpscan.nativecamera.core.ads.AdManager
import com.timewarpscan.nativecamera.core.preferences.AppPreferences
import com.timewarpscan.nativecamera.databinding.ActivityLoadingBinding
import com.timewarpscan.nativecamera.ui.home.HomeActivity
import com.timewarpscan.nativecamera.ui.language.SelectLanguageActivity

class LoadingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoadingBinding

    private val handler = Handler(Looper.getMainLooper())

    /** Minimum time the loading screen is shown (ms). */
    private val MIN_LOADING_TIME_MS = 2000L

    /** Maximum time to wait for ads to preload before navigating anyway (ms). */
    private val MAX_WAIT_FOR_ADS_MS = 5000L

    /** Interval for polling ad preload status (ms). */
    private val POLL_INTERVAL_MS = 200L

    private var minTimeElapsed = false
    private var adsReady = false
    private var hasNavigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoadingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startDotAnimation()

        // Trigger interstitial preload (AdManager.loadAds() was already called
        // in Application.onCreate(), but we ensure it here as well)
        AdManager.loadAds()

        // Gate 1: minimum loading screen display time
        handler.postDelayed({
            minTimeElapsed = true
            tryNavigate()
        }, MIN_LOADING_TIME_MS)

        // Gate 2: poll for interstitial ads to be preloaded
        pollAdsReady()

        // Safety net: hard timeout — navigate regardless after MAX_WAIT_FOR_ADS_MS
        handler.postDelayed({
            if (!hasNavigated) {
                adsReady = true
                tryNavigate()
            }
        }, MAX_WAIT_FOR_ADS_MS)
    }

    /**
     * Polls [AdManager.areInterstitialsPreloaded] until all interstitial IDs
     * have finished loading (success or failure).
     */
    private fun pollAdsReady() {
        handler.postDelayed({
            if (hasNavigated) return@postDelayed

            if (AdManager.areInterstitialsPreloaded()) {
                adsReady = true
                tryNavigate()
            } else {
                pollAdsReady() // keep polling
            }
        }, POLL_INTERVAL_MS)
    }

    /**
     * Navigate only when BOTH gates have passed:
     *   1. Minimum loading time elapsed
     *   2. Interstitial ads preloaded (or timed out)
     */
    private fun tryNavigate() {
        if (hasNavigated) return
        if (minTimeElapsed && adsReady) {
            hasNavigated = true
            navigateNext()
        }
    }

    private fun navigateNext() {
        val target = if (AppPreferences.isFirstLaunch) {
            SelectLanguageActivity::class.java
        } else {
            HomeActivity::class.java
        }
        startActivity(Intent(this, target))
        finish()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startDotAnimation() {
        val dots = listOf(binding.dot1, binding.dot2, binding.dot3)
        val animatorSet = AnimatorSet()
        val animations = dots.mapIndexed { index, dot ->
            ObjectAnimator.ofFloat(dot, View.ALPHA, 0.3f, 1f, 0.3f).apply {
                duration = 800
                startDelay = (index * 250).toLong()
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
            }
        }
        animatorSet.playTogether(animations.map { it as android.animation.Animator })
        animatorSet.start()
    }
}

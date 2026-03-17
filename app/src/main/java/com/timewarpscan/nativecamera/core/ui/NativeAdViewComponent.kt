package com.timewarpscan.nativecamera.core.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RatingBar
import android.widget.TextView
import com.google.android.gms.ads.nativead.MediaView
import com.google.android.gms.ads.nativead.NativeAd
import com.google.android.gms.ads.nativead.NativeAdView
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.core.ads.AdManager

/**
 * Reusable Native Ad component — drop into any layout via XML or code.
 *
 * XML attributes:
 *   app:showMedia="true|false"   — show the media view (large style only)
 *   app:showIcon="true|false"    — show advertiser icon
 *   app:showCTA="true|false"     — show call-to-action button
 *   app:adStyle="small|medium|large" — layout variant
 *
 * Usage:
 *   <com.timewarpscan.nativecamera.core.ui.NativeAdViewComponent
 *       android:layout_width="match_parent"
 *       android:layout_height="wrap_content"
 *       app:adStyle="medium"
 *       app:showCTA="true" />
 *
 * Then call [loadAndBind] or [bindAd] from code.
 * Hides itself gracefully if no ad is available.
 */
class NativeAdViewComponent @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    enum class AdStyle(val value: Int) { SMALL(0), MEDIUM(1), LARGE(2) }

    private var showMedia = true
    private var showIcon = true
    private var showCTA = true
    private var adStyle = AdStyle.MEDIUM

    private var nativeAdView: NativeAdView? = null
    private var boundAd: NativeAd? = null

    init {
        attrs?.let {
            val ta = context.obtainStyledAttributes(it, R.styleable.NativeAdViewComponent)
            showMedia = ta.getBoolean(R.styleable.NativeAdViewComponent_showMedia, true)
            showIcon = ta.getBoolean(R.styleable.NativeAdViewComponent_showIcon, true)
            showCTA = ta.getBoolean(R.styleable.NativeAdViewComponent_showCTA, true)
            adStyle = when (ta.getInt(R.styleable.NativeAdViewComponent_adStyle, 1)) {
                0 -> AdStyle.SMALL
                2 -> AdStyle.LARGE
                else -> AdStyle.MEDIUM
            }
            ta.recycle()
        }
        inflateLayout()
    }

    private fun inflateLayout() {
        removeAllViews()
        val layoutRes = when (adStyle) {
            AdStyle.SMALL -> R.layout.view_native_ad_small
            AdStyle.MEDIUM -> R.layout.view_native_ad_medium
            AdStyle.LARGE -> R.layout.view_native_ad_large
        }
        val view = LayoutInflater.from(context).inflate(layoutRes, this, true)
        nativeAdView = view.findViewById(android.R.id.content) ?: view as? NativeAdView
            ?: findNativeAdView(view)
    }

    private fun findNativeAdView(view: View): NativeAdView? {
        if (view is NativeAdView) return view
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findNativeAdView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Public API
    // -----------------------------------------------------------------------

    /** Fetch a native ad from AdManager and bind it. Hides view if unavailable. */
    fun loadAndBind() {
        val ad = AdManager.getNativeAd()
        bindAd(ad)
    }

    /**
     * Bind a [NativeAd] to this view. Pass null to hide the component.
     */
    fun bindAd(ad: NativeAd?) {
        if (ad == null) {
            visibility = GONE
            return
        }

        val adView = nativeAdView
        if (adView == null) {
            visibility = GONE
            return
        }

        boundAd = ad
        visibility = VISIBLE

        // Headline (required)
        val headlineView = adView.findViewById<TextView>(R.id.adHeadline)
        headlineView?.text = ad.headline
        adView.headlineView = headlineView

        // Body
        val bodyView = adView.findViewById<TextView>(R.id.adBody)
        bodyView?.text = ad.body ?: ""
        bodyView?.visibility = if (ad.body != null) VISIBLE else GONE
        adView.bodyView = bodyView

        // Icon
        val iconView = adView.findViewById<ImageView>(R.id.adIcon)
        if (showIcon && ad.icon != null) {
            iconView?.setImageDrawable(ad.icon!!.drawable)
            iconView?.visibility = VISIBLE
        } else {
            iconView?.visibility = GONE
        }
        adView.iconView = iconView

        // CTA
        val ctaView = adView.findViewById<TextView>(R.id.adCTA)
        if (showCTA && ad.callToAction != null) {
            ctaView?.text = ad.callToAction
            ctaView?.visibility = VISIBLE
        } else {
            ctaView?.visibility = GONE
        }
        adView.callToActionView = ctaView

        // Media (large layout only)
        val mediaView = adView.findViewById<MediaView>(R.id.adMedia)
        if (showMedia && mediaView != null) {
            mediaView.visibility = VISIBLE
            adView.mediaView = mediaView
        } else {
            mediaView?.visibility = GONE
        }

        // Advertiser
        val advertiserView = adView.findViewById<TextView>(R.id.adAdvertiser)
        advertiserView?.text = ad.advertiser ?: ""
        advertiserView?.visibility = if (ad.advertiser != null) VISIBLE else GONE
        adView.advertiserView = advertiserView

        // Star rating (large layout only)
        val ratingView = adView.findViewById<RatingBar>(R.id.adStarRating)
        if (ratingView != null && ad.starRating != null) {
            ratingView.rating = ad.starRating!!.toFloat()
            ratingView.visibility = VISIBLE
            adView.starRatingView = ratingView
        } else {
            ratingView?.visibility = GONE
        }

        // Register the ad view with the NativeAd
        adView.setNativeAd(ad)
    }

    /** Unbind and release the current ad. */
    fun unbind() {
        boundAd = null
        visibility = GONE
    }
}

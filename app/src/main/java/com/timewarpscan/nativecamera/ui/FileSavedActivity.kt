package com.timewarpscan.nativecamera.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.gms.ads.nativead.NativeAdView
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.core.ads.AdManager

class FileSavedActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SAVED_URI = "saved_uri"
    }

    private var nativeAdView: NativeAdView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        setContentView(R.layout.activity_file_saved)

        val savedUriString = intent.getStringExtra(EXTRA_SAVED_URI)

        val btnBack = findViewById<ImageView>(R.id.btnBack)
        val ivThumbnail = findViewById<ImageView>(R.id.ivThumbnail)
        val btnContinue = findViewById<TextView>(R.id.btnContinue)
        val btnGoToCollection = findViewById<TextView>(R.id.btnGoToCollection)
        nativeAdView = findViewById(R.id.nativeAdView)

        // Load thumbnail from saved URI
        if (savedUriString != null) {
            try {
                val uri = Uri.parse(savedUriString)
                contentResolver.openInputStream(uri)?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    ivThumbnail.setImageBitmap(bitmap)
                }
            } catch (e: Exception) {
                // Thumbnail unavailable — leave placeholder
            }
        }

        showNativeAd()

        btnBack.setOnClickListener { finish() }

        btnContinue.setOnClickListener {
            AdManager.showInterstitialIfReady(this) { finish() }
        }

        btnGoToCollection.setOnClickListener {
            startActivity(Intent(this, com.timewarpscan.nativecamera.ui.collection.CollectionActivity::class.java))
            finish()
        }
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun showNativeAd() {
        val ad = AdManager.getNativeAd() ?: return
        val adView = nativeAdView ?: return

        val adHeadline = adView.findViewById<TextView>(R.id.adHeadline)
        val adIcon = adView.findViewById<ImageView>(R.id.adIcon)
        val adCTA = adView.findViewById<TextView>(R.id.adCTA)

        adHeadline.text = ad.headline
        ad.icon?.drawable?.let { adIcon.setImageDrawable(it) }
        adCTA.text = ad.callToAction ?: "Install Now"

        adView.headlineView = adHeadline
        adView.iconView = adIcon
        adView.callToActionView = adCTA

        adView.setNativeAd(ad)
        adView.visibility = View.VISIBLE
    }

    private fun openGallery(savedUriString: String?) {
        val intent = if (savedUriString != null) {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(savedUriString), "image/jpeg")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                type = "image/*"
            }
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // Fallback if no viewer available
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        AdManager.consumeNativeAd()
    }
}

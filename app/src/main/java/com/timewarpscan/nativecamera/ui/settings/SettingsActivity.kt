package com.timewarpscan.nativecamera.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.timewarpscan.nativecamera.core.iap.IAPManager
import com.timewarpscan.nativecamera.core.preferences.AppPreferences
import com.timewarpscan.nativecamera.databinding.ActivitySettingsBinding
import com.timewarpscan.nativecamera.ui.language.SelectLanguageActivity

class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PRIVACY_URL = "https://sites.google.com/view/timewarp-privacy-policy"
    }

    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupHeader()
        setupProBanner()
        setupNativeAd()
        setupSettingsItems()
    }

    private fun setupHeader() {
        binding.btnClose.setOnClickListener { finish() }
    }

    private fun setupProBanner() {
        binding.bannerPro.setOnClickListener {
            IAPManager.purchase(this, com.timewarpscan.nativecamera.core.iap.IAPConfig.PRODUCT_REMOVE_ADS)
        }
    }

    private fun setupNativeAd() {
        binding.nativeAd.loadAndBind()
    }

    private fun setupSettingsItems() {
        // Sound toggle
        binding.switchSound.isChecked = AppPreferences.soundEnabled
        binding.switchSound.setOnCheckedChangeListener { _, isChecked ->
            AppPreferences.soundEnabled = isChecked
        }

        // Share
        binding.itemShare.setOnClickListener {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "Time Warp Scan")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "Check out Time Warp Scan! https://play.google.com/store/apps/details?id=$packageName"
                )
            }
            startActivity(Intent.createChooser(shareIntent, "Share via"))
        }

        // Language
        binding.itemLanguage.setOnClickListener {
            startActivity(Intent(this, SelectLanguageActivity::class.java))
        }

        // Rate Us
        binding.itemRateUs.setOnClickListener {
            RateAppDialog().show(supportFragmentManager, "rate_dialog")
        }

        // Privacy Policy
        binding.itemPrivacy.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
        }
    }
}

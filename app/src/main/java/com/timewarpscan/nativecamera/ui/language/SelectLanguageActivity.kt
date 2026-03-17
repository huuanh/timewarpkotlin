package com.timewarpscan.nativecamera.ui.language

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.core.preferences.AppPreferences
import com.timewarpscan.nativecamera.databinding.ActivitySelectLanguageBinding
import com.timewarpscan.nativecamera.ui.onboarding.OnboardingActivity

class SelectLanguageActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySelectLanguageBinding
    private lateinit var adapter: LanguageAdapter

    data class Language(
        val code: String,
        val name: String,
        val flagRes: Int
    )

    private val languages = listOf(
        Language("vi", "Tiếng Việt", R.drawable.flag_vietnam),
        Language("en", "English", R.drawable.flag_united_states),
        Language("ko", "한국인", R.drawable.flag_south_korea),
        Language("ja", "日本語", R.drawable.flag_japan),
        Language("es", "Español", R.drawable.flag_spain)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectLanguageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupLanguageList()

        binding.btnDone.setOnClickListener {
            navigateToOnboarding()
        }

        binding.nativeAd.loadAndBind()
    }

    private fun setupLanguageList() {
        val saved = AppPreferences.selectedLanguage
        val initialIndex = languages.indexOfFirst { it.code == saved }.coerceAtLeast(0)

        adapter = LanguageAdapter(languages, initialIndex) { position ->
            AppPreferences.selectedLanguage = languages[position].code
        }

        binding.rvLanguages.layoutManager = LinearLayoutManager(this)
        binding.rvLanguages.adapter = adapter
    }

    private fun navigateToOnboarding() {
        AppPreferences.isFirstLaunch = false
        startActivity(Intent(this, OnboardingActivity::class.java))
        finish()
    }
}

package com.timewarpscan.nativecamera.ui.onboarding

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.viewpager2.widget.ViewPager2
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.databinding.ActivityOnboardingBinding
import com.timewarpscan.nativecamera.ui.home.HomeActivity

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding
    private lateinit var dots: List<View>

    private val pages = listOf(
        OnboardingPage(R.drawable.onboarding_1, "Trend the Moment", "Create viral effects in one scan"),
        OnboardingPage(R.drawable.onboarding_2, "Warp Your Face", "Freeze lines. Shape your look."),
        OnboardingPage(R.drawable.onboarding_3, "Share the Fun", "Save fast. Impress instantly.")
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupViewPager()
        setupDots()
        updateDots(0)

        binding.btnNext.setOnClickListener {
            val current = binding.viewPager.currentItem
            if (current < pages.size - 1) {
                binding.viewPager.currentItem = current + 1
            } else {
                goToHome()
            }
        }

        binding.nativeAd.loadAndBind()
    }

    private fun setupViewPager() {
        binding.viewPager.adapter = OnboardingAdapter(pages)
        binding.viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                updateDots(position)
                binding.btnNext.text = if (position == pages.size - 1) "Get Started" else "Next"
            }
        })
    }

    private fun setupDots() {
        val container = binding.dotsContainer
        container.removeAllViews()
        dots = List(pages.size) { index ->
            View(this).apply {
                val size = resources.getDimensionPixelSize(android.R.dimen.app_icon_size) / 6 // ~8dp
                val params = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = if (index == 0) 0 else 8
                    gravity = Gravity.CENTER_VERTICAL
                }
                layoutParams = params
                setBackgroundResource(R.drawable.bg_dot_inactive)
                container.addView(this)
            }
        }
    }

    private fun updateDots(activeIndex: Int) {
        dots.forEachIndexed { index, dot ->
            if (index == activeIndex) {
                val lp = dot.layoutParams as LinearLayout.LayoutParams
                lp.width = (20 * resources.displayMetrics.density).toInt()
                lp.height = (8 * resources.displayMetrics.density).toInt()
                dot.layoutParams = lp
                dot.setBackgroundResource(R.drawable.bg_dot_active)
            } else {
                val lp = dot.layoutParams as LinearLayout.LayoutParams
                val size = (8 * resources.displayMetrics.density).toInt()
                lp.width = size
                lp.height = size
                dot.layoutParams = lp
                dot.setBackgroundResource(R.drawable.bg_dot_inactive)
            }
        }
    }

    private fun goToHome() {
        startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }
}

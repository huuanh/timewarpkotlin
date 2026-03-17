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
import com.timewarpscan.nativecamera.core.preferences.AppPreferences
import com.timewarpscan.nativecamera.databinding.ActivityLoadingBinding
import com.timewarpscan.nativecamera.ui.home.HomeActivity
import com.timewarpscan.nativecamera.ui.language.SelectLanguageActivity

class LoadingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoadingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoadingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        startDotAnimation()

        Handler(Looper.getMainLooper()).postDelayed({
            navigateNext()
        }, 2000)
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

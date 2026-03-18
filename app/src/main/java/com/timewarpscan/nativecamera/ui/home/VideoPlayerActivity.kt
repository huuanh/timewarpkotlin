package com.timewarpscan.nativecamera.ui.home

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.timewarpscan.nativecamera.core.ads.AdManager
import com.timewarpscan.nativecamera.databinding.ActivityVideoPlayerBinding
import com.timewarpscan.nativecamera.ui.CameraActivity

class VideoPlayerActivity : AppCompatActivity() {

    private lateinit var binding: ActivityVideoPlayerBinding
    private var effect: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityVideoPlayerBinding.inflate(layoutInflater)
        setContentView(binding.root)

        effect = intent.getStringExtra("effect") ?: ""
        val rawResName = intent.getStringExtra("rawResName") ?: ""

        setupVideo(rawResName)
        setupButtons()
    }

    private fun setupVideo(rawResName: String) {
        if (rawResName.isEmpty()) return
        val rawResId = resources.getIdentifier(rawResName, "raw", packageName)
        if (rawResId == 0) return

        val videoUri = Uri.parse("android.resource://$packageName/$rawResId")
        binding.videoView.setVideoURI(videoUri)
        binding.videoView.setOnPreparedListener { mp: MediaPlayer ->
            mp.isLooping = true
            binding.videoView.start()
        }
        binding.videoView.start()
    }

    private fun setupButtons() {
        binding.btnBack.setOnClickListener {
            finish()
        }

        binding.btnTryNow.setOnClickListener {
            AdManager.frequencyController.recordAction()
            AdManager.showInterstitialIfReady(this) {
                val intent = Intent(this, CameraActivity::class.java).apply {
                    putExtra("effect", effect)
                }
                startActivity(intent)
            }
        }
    }

    override fun onPause() {
        super.onPause()
        binding.videoView.pause()
    }

    override fun onResume() {
        super.onResume()
        binding.videoView.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.videoView.stopPlayback()
    }
}

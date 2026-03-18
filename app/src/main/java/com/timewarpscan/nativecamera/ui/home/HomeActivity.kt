package com.timewarpscan.nativecamera.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.GridLayoutManager
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.core.ads.AdManager
import com.timewarpscan.nativecamera.databinding.ActivityHomeBinding
import com.timewarpscan.nativecamera.model.VideoItem
import com.timewarpscan.nativecamera.ui.CameraActivity
import com.timewarpscan.nativecamera.ui.home.adapter.VideoGridAdapter
import com.timewarpscan.nativecamera.ui.iap.IAPActivity
import com.timewarpscan.nativecamera.ui.settings.SettingsActivity
import com.timewarpscan.nativecamera.ui.home.VideoPlayerActivity

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var videoAdapter: VideoGridAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupBottomNav()
        setupTopBar()
        loadVideos()
    }

    private fun setupTopBar() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.btnPremium.setOnClickListener {
            startActivity(Intent(this, IAPActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        videoAdapter = VideoGridAdapter { item ->
            onVideoItemClicked(item)
        }
        binding.rvVideos.apply {
            layoutManager = GridLayoutManager(this@HomeActivity, 2)
            adapter = videoAdapter
        }
    }

    private fun setupBottomNav() {
        binding.btnCamera.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        binding.navCollection.setOnClickListener {
            startActivity(Intent(this, com.timewarpscan.nativecamera.ui.collection.CollectionActivity::class.java))
        }
    }

    private fun loadVideos() {
        val effects = listOf("swirl", "mirror", "grid", "double", "waterfall", "split", "single")
        val videos = (1..10).map { index ->
            VideoItem(
                id = "video_$index",
                thumbnail = "video_$index",
                effect = effects[(index - 1) % effects.size]
            )
        }
        videoAdapter.setItems(videos)
    }

    private fun onVideoItemClicked(item: VideoItem) {
        val intent = Intent(this, VideoPlayerActivity::class.java).apply {
            putExtra("rawResName", item.thumbnail)
            putExtra("effect", item.effect)
        }
        startActivity(intent)
    }
}

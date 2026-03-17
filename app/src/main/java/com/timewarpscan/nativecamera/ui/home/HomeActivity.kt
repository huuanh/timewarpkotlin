package com.timewarpscan.nativecamera.ui.home

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.timewarpscan.nativecamera.core.ads.AdManager
import com.timewarpscan.nativecamera.core.remote.RemoteConfigManager
import com.timewarpscan.nativecamera.databinding.ActivityHomeBinding
import com.timewarpscan.nativecamera.model.VideoItem
import com.timewarpscan.nativecamera.ui.CameraActivity
import com.timewarpscan.nativecamera.ui.home.adapter.SectionAdapter
import com.timewarpscan.nativecamera.ui.settings.SettingsActivity
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var sectionAdapter: SectionAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupRecyclerView()
        setupBottomNav()
        setupTopBar()
        loadSections()
    }

    private fun setupTopBar() {
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun setupRecyclerView() {
        sectionAdapter = SectionAdapter { item ->
            onVideoItemClicked(item)
        }
        binding.rvSections.apply {
            layoutManager = LinearLayoutManager(this@HomeActivity)
            adapter = sectionAdapter
        }
    }

    private fun setupBottomNav() {
        binding.btnCamera.setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
    }

    private fun loadSections() {
        lifecycleScope.launch {
            val sections = RemoteConfigManager.getSections(this@HomeActivity)
            sectionAdapter.setSections(sections)
        }
    }

    private fun onVideoItemClicked(item: VideoItem) {
        AdManager.frequencyController.recordAction()
        AdManager.showInterstitialIfReady(this) {
            navigateToCamera(item.effect)
        }
    }

    private fun navigateToCamera(effect: String) {
        val intent = Intent(this, CameraActivity::class.java).apply {
            putExtra("effect", effect)
        }
        startActivity(intent)
    }
}

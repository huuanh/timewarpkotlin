package com.timewarpscan.nativecamera.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.core.ads.AdManager
import com.timewarpscan.nativecamera.util.BitmapUtils
import kotlinx.coroutines.launch
import java.io.File

class PreviewActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_TEMP_PATH = "temp_path"
        const val EXTRA_IS_VIDEO = "is_video"
    }

    private lateinit var ivPreview: ImageView
    private lateinit var videoPreview: VideoView
    private lateinit var btnBack: ImageView
    private lateinit var btnSave: TextView
    private lateinit var btnRetry: TextView

    private var tempFile: File? = null
    private var isVideo = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        setContentView(R.layout.activity_preview)

        val tempPath = intent.getStringExtra(EXTRA_TEMP_PATH) ?: run { finish(); return }
        isVideo = intent.getBooleanExtra(EXTRA_IS_VIDEO, false)
        tempFile = File(tempPath)

        ivPreview = findViewById(R.id.ivPreview)
        videoPreview = findViewById(R.id.videoPreview)
        btnBack = findViewById(R.id.btnBack)
        btnSave = findViewById(R.id.btnSave)
        btnRetry = findViewById(R.id.btnRetry)

        loadPreview()
        setupButtons()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun loadPreview() {
        val file = tempFile ?: return
        if (!file.exists()) return

        if (isVideo) {
            ivPreview.visibility = View.GONE
            videoPreview.visibility = View.VISIBLE
            videoPreview.setVideoURI(Uri.fromFile(file))
            videoPreview.setOnPreparedListener { mp: MediaPlayer ->
                mp.isLooping = true
                videoPreview.start()
            }
            videoPreview.start()
        } else {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ivPreview.setImageBitmap(bitmap)
        }
    }

    private fun setupButtons() {
        btnBack.setOnClickListener {
            deleteTempAndFinish()
        }

        btnRetry.setOnClickListener {
            deleteTempAndFinish()
        }

        btnSave.setOnClickListener {
            saveToGallery()
        }
    }

    private fun saveToGallery() {
        val file = tempFile ?: return
        btnSave.isClickable = false
        btnRetry.isClickable = false

        lifecycleScope.launch {
            val savedUri = if (isVideo) {
                BitmapUtils.saveVideoToGallery(this@PreviewActivity, file.absolutePath)
            } else {
                BitmapUtils.saveFileToGallery(this@PreviewActivity, file)
            }

            if (!isVideo) deleteTempFile() // video temp deleted by saveVideoToGallery()

            if (savedUri != null) {
                AdManager.frequencyController.recordAction()
                val intent = Intent(this@PreviewActivity, FileSavedActivity::class.java).apply {
                    putExtra(FileSavedActivity.EXTRA_SAVED_URI, savedUri)
                }
                startActivity(intent)
                finish()
            } else {
                btnSave.isClickable = true
                btnRetry.isClickable = true
                Toast.makeText(this@PreviewActivity, "Failed to save", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun deleteTempFile() {
        tempFile?.delete()
        tempFile = null
    }

    private fun deleteTempAndFinish() {
        deleteTempFile()
        finish()
    }

    override fun onPause() {
        super.onPause()
        if (isVideo) videoPreview.pause()
    }

    override fun onResume() {
        super.onResume()
        if (isVideo && ::videoPreview.isInitialized) videoPreview.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isVideo) videoPreview.stopPlayback()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        deleteTempFile()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

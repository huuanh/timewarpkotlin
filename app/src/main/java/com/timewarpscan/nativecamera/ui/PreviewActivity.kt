package com.timewarpscan.nativecamera.ui

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
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
    }

    private lateinit var ivPreview: ImageView
    private lateinit var btnBack: ImageView
    private lateinit var btnSave: TextView
    private lateinit var btnRetry: TextView

    private var tempFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        setContentView(R.layout.activity_preview)

        val tempPath = intent.getStringExtra(EXTRA_TEMP_PATH) ?: run { finish(); return }
        tempFile = File(tempPath)

        ivPreview = findViewById(R.id.ivPreview)
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
        if (file.exists()) {
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
            val savedUri = BitmapUtils.saveFileToGallery(this@PreviewActivity, file)
            deleteTempFile()

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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        deleteTempFile()
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }
}

package com.timewarpscan.nativecamera.ui.collection

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.model.MediaItem
import com.timewarpscan.nativecamera.ui.CameraActivity
import com.timewarpscan.nativecamera.ui.iap.IAPActivity
import com.timewarpscan.nativecamera.ui.settings.SettingsActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CollectionActivity : AppCompatActivity() {

    private enum class MediaFilter { ALL, VIDEOS, PHOTOS }

    private lateinit var rvMedia: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var tabAll: TextView
    private lateinit var tabVideos: TextView
    private lateinit var tabPhotos: TextView

    private lateinit var adapter: CollectionAdapter
    private var currentFilter = MediaFilter.ALL

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadMedia(currentFilter) else showEmpty()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        setContentView(R.layout.activity_collection)

        bindViews()
        setupAdapter()
        setupTabs()
        setupBottomNav()
        checkPermissionsAndLoad()
    }

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun bindViews() {
        rvMedia = findViewById(R.id.rvMedia)
        tvEmpty = findViewById(R.id.tvEmpty)
        tabAll = findViewById(R.id.tabAll)
        tabVideos = findViewById(R.id.tabVideos)
        tabPhotos = findViewById(R.id.tabPhotos)

        findViewById<ImageView>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<ImageView>(R.id.btnPremium).setOnClickListener {
            startActivity(Intent(this, IAPActivity::class.java))
        }
    }

    private fun setupAdapter() {
        adapter = CollectionAdapter(this) { item -> openMedia(item) }
        rvMedia.layoutManager = GridLayoutManager(this, 3)
        rvMedia.adapter = adapter
    }

    private fun setupTabs() {
        tabAll.setOnClickListener { selectTab(MediaFilter.ALL) }
        tabVideos.setOnClickListener { selectTab(MediaFilter.VIDEOS) }
        tabPhotos.setOnClickListener { selectTab(MediaFilter.PHOTOS) }
    }

    private fun selectTab(filter: MediaFilter) {
        currentFilter = filter
        updateTabUI(filter)
        loadMedia(filter)
    }

    private fun updateTabUI(filter: MediaFilter) {
        val activeDrawable = R.drawable.bg_collection_tab_active
        val inactiveColor = Color.parseColor("#80FFFFFF")
        val activeColor = Color.parseColor("#1A1A1A")

        tabAll.apply {
            background = if (filter == MediaFilter.ALL) getDrawable(activeDrawable) else null
            setTextColor(if (filter == MediaFilter.ALL) activeColor else inactiveColor)
        }
        tabVideos.apply {
            background = if (filter == MediaFilter.VIDEOS) getDrawable(activeDrawable) else null
            setTextColor(if (filter == MediaFilter.VIDEOS) activeColor else inactiveColor)
        }
        tabPhotos.apply {
            background = if (filter == MediaFilter.PHOTOS) getDrawable(activeDrawable) else null
            setTextColor(if (filter == MediaFilter.PHOTOS) activeColor else inactiveColor)
        }
    }

    private fun setupBottomNav() {
        findViewById<View>(R.id.navHome).setOnClickListener { finish() }
        findViewById<ImageView>(R.id.btnCamera).setOnClickListener {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        // navCollection is the current screen — no action
    }

    private fun checkPermissionsAndLoad() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                return
            }
        }
        loadMedia(currentFilter)
    }

    private fun loadMedia(filter: MediaFilter) {
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) {
                val result = mutableListOf<MediaItem>()
                if (filter != MediaFilter.VIDEOS) result.addAll(queryImages())
                if (filter != MediaFilter.PHOTOS) result.addAll(queryVideos())
                result.sortedByDescending { it.dateAdded }
            }
            if (items.isEmpty()) {
                showEmpty()
            } else {
                rvMedia.visibility = View.VISIBLE
                tvEmpty.visibility = View.GONE
                adapter.setItems(items)
            }
        }
    }

    private fun showEmpty() {
        rvMedia.visibility = View.GONE
        tvEmpty.visibility = View.VISIBLE
    }

    private fun queryImages(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED,
        )
        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?" to arrayOf<String?>("%TimeWarpScan%")
        } else {
            "${MediaStore.Images.Media.DATA} LIKE ?" to arrayOf<String?>("%TimeWarpScan%")
        }

        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                items.add(MediaItem(id, uri, cursor.getString(nameCol), false, cursor.getLong(dateCol)))
            }
        }
        return items
    }

    private fun queryVideos(): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DURATION,
        )
        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to arrayOf<String?>("%TimeWarpScan%")
        } else {
            "${MediaStore.Video.Media.DATA} LIKE ?" to arrayOf<String?>("%TimeWarpScan%")
        }

        contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                items.add(MediaItem(id, uri, cursor.getString(nameCol), true, cursor.getLong(dateCol), cursor.getLong(durCol)))
            }
        }
        return items
    }

    private fun openMedia(item: MediaItem) {
        val mime = if (item.isVideo) "video/mp4" else "image/jpeg"
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(item.uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            // No suitable viewer installed
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh when returning from camera
        loadMedia(currentFilter)
    }
}

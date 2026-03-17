package com.timewarpscan.nativecamera.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PorterDuff
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.CountDownTimer
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
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
import com.timewarpscan.nativecamera.camera.CameraController
import com.timewarpscan.nativecamera.render.ScanRenderer
import com.timewarpscan.nativecamera.scan.WaterfallScanEngine
import com.timewarpscan.nativecamera.util.BitmapUtils
import kotlinx.coroutines.launch

/**
 * Main activity — fullscreen camera preview with waterfall scan effect.
 *
 * UI layout matches the TimeWarpScan React Native project:
 *   - Top toolbar: scan direction, scan line color, timer, speed
 *   - Bottom: mode selector (Video/Photo), capture button, effect selector, rotate camera
 *   - Effect panel overlay with grid of warp effects
 */
class CameraActivity : AppCompatActivity() {

    // --- Views ---
    private lateinit var glSurfaceView: GLSurfaceView
    private lateinit var btnBack: ImageView
    private lateinit var btnDirection: FrameLayout
    private lateinit var icDirection: ImageView
    private lateinit var btnScanColor: FrameLayout
    private lateinit var icScanColor: ImageView
    private lateinit var btnTimer: FrameLayout
    private lateinit var tvTimerBadge: TextView
    private lateinit var btnSpeed: FrameLayout
    private lateinit var tvSpeedBadge: TextView
    private lateinit var tvTimerCountdown: TextView
    private lateinit var tvStatus: TextView
    private lateinit var effectPanel: LinearLayout
    private lateinit var btnCloseEffects: ImageView
    private lateinit var rvEffects: RecyclerView
    private lateinit var btnModeVideo: TextView
    private lateinit var btnModePhoto: TextView
    private lateinit var btnEffects: ImageView
    private lateinit var btnCapture: ImageView
    private lateinit var btnSwitchCamera: ImageView

    // --- Engine / Renderer ---
    private val scanEngine = WaterfallScanEngine()
    private val cameraController = CameraController()
    private lateinit var renderer: ScanRenderer

    // --- UI state (mirrors TimeWarpScan RN state) ---
    private var mode: String = "photo"              // "photo" or "video"
    private var isRecording = false
    private var isScanning = false
    private var selectedEffectId: String = "normal"

    private var scanDirection: String = "down"       // "down" / "up"
    private val scanLineColors = listOf("#00FFFF", "#FF0000", "#00FF00", "#FFFF00", "#FF00FF", "#FFFFFF")
    private var scanLineColorIndex = 0
    private val timerOptions = intArrayOf(0, 3, 5, 10)
    private var timerIndex = 0
    private val speedOptions = floatArrayOf(1f, 1.5f, 2f, 3f)
    private var speedIndex = 0

    private var countdownTimer: CountDownTimer? = null

    // --- Effects list (matches TimeWarpScan effectsConfig.js) ---
    private val effects = listOf(
        Effect("normal", "Normal", R.drawable.ic_effect_normal),
        Effect("swirl", "Swirl", R.drawable.ic_effect_swirl),
        Effect("grid", "Grid", R.drawable.ic_effect_grid),
        Effect("mirror", "Mirror", R.drawable.ic_effect_mirror),
        Effect("double", "Double", R.drawable.ic_effect_double),
        Effect("waterfall", "Waterfall", R.drawable.ic_effect_waterfall),
        Effect("split", "Split", R.drawable.ic_effect_split),
        Effect("single", "Single", R.drawable.ic_effect_single),
    )

    private lateinit var effectAdapter: EffectAdapter

    // --- Permission ---
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initializeCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupFullscreen()
        setContentView(R.layout.activity_camera)

        bindViews()
        setupEffectsPanel()
        setupToolbar()
        setupModeSelector()
        setupBottomButtons()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            initializeCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::glSurfaceView.isInitialized) glSurfaceView.onResume()
    }

    override fun onPause() {
        if (::glSurfaceView.isInitialized) glSurfaceView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        countdownTimer?.cancel()
        cameraController.stopCamera()
        if (::renderer.isInitialized) {
            glSurfaceView.queueEvent { renderer.release() }
        }
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // View binding
    // -----------------------------------------------------------------------

    private fun bindViews() {
        glSurfaceView = findViewById(R.id.glSurfaceView)
        btnBack = findViewById(R.id.btnBack)
        btnDirection = findViewById(R.id.btnDirection)
        icDirection = findViewById(R.id.icDirection)
        btnScanColor = findViewById(R.id.btnScanColor)
        icScanColor = findViewById(R.id.icScanColor)
        btnTimer = findViewById(R.id.btnTimer)
        tvTimerBadge = findViewById(R.id.tvTimerBadge)
        btnSpeed = findViewById(R.id.btnSpeed)
        tvSpeedBadge = findViewById(R.id.tvSpeedBadge)
        tvTimerCountdown = findViewById(R.id.tvTimerCountdown)
        tvStatus = findViewById(R.id.tvStatus)
        effectPanel = findViewById(R.id.effectPanel)
        btnCloseEffects = findViewById(R.id.btnCloseEffects)
        rvEffects = findViewById(R.id.rvEffects)
        btnModeVideo = findViewById(R.id.btnModeVideo)
        btnModePhoto = findViewById(R.id.btnModePhoto)
        btnEffects = findViewById(R.id.btnEffects)
        btnCapture = findViewById(R.id.btnCapture)
        btnSwitchCamera = findViewById(R.id.btnSwitchCamera)
    }

    // -----------------------------------------------------------------------
    // Setup helpers
    // -----------------------------------------------------------------------

    private fun setupFullscreen() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    private fun initializeCamera() {
        renderer = ScanRenderer(scanEngine)

        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        renderer.onSurfaceTextureAvailable = { surfaceTexture ->
            runOnUiThread {
                cameraController.startCamera(
                    lifecycleOwner = this,
                    surfaceTexture = surfaceTexture,
                    previewWidth = 1080,
                    previewHeight = 1920
                )
            }
        }

        renderer.onScanComplete = {
            runOnUiThread {
                isScanning = false
                isRecording = false
                tvStatus.visibility = View.VISIBLE
                tvStatus.text = "Scan complete — tap to save"
                updateCaptureButton()
            }
        }
    }

    // -----------------------------------------------------------------------
    // Top Toolbar
    // -----------------------------------------------------------------------

    private fun setupToolbar() {
        btnBack.setOnClickListener { finish() }

        // Scan direction toggle
        btnDirection.setOnClickListener {
            scanDirection = if (scanDirection == "down") "up" else "down"
            val rotation = if (scanDirection == "up") 180f else 0f
            icDirection.rotation = rotation
        }

        // Scan line color cycle
        btnScanColor.setOnClickListener {
            scanLineColorIndex = (scanLineColorIndex + 1) % scanLineColors.size
            val color = Color.parseColor(scanLineColors[scanLineColorIndex])
            icScanColor.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            // TODO: Pass color to renderer's scan line
        }

        // Timer cycle
        btnTimer.setOnClickListener {
            timerIndex = (timerIndex + 1) % timerOptions.size
            val seconds = timerOptions[timerIndex]
            if (seconds > 0) {
                tvTimerBadge.text = "${seconds}s"
                tvTimerBadge.visibility = View.VISIBLE
            } else {
                tvTimerBadge.visibility = View.GONE
            }
        }

        // Speed cycle
        btnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speedOptions.size
            val speed = speedOptions[speedIndex]
            if (speed != 1f) {
                tvSpeedBadge.text = "${speed}x"
                tvSpeedBadge.visibility = View.VISIBLE
            } else {
                tvSpeedBadge.visibility = View.GONE
            }
            // Update scan duration based on speed
            scanEngine.scanDurationMs =
                (WaterfallScanEngine.DEFAULT_DURATION_MS / speed).toLong()
        }
    }

    // -----------------------------------------------------------------------
    // Mode Selector (Video / Photo)
    // -----------------------------------------------------------------------

    private fun setupModeSelector() {
        updateModeUI()

        btnModePhoto.setOnClickListener {
            mode = "photo"
            updateModeUI()
            updateCaptureButton()
        }

        btnModeVideo.setOnClickListener {
            mode = "video"
            updateModeUI()
            updateCaptureButton()
        }
    }

    private fun updateModeUI() {
        if (mode == "photo") {
            btnModePhoto.setBackgroundResource(R.drawable.bg_mode_active)
            btnModePhoto.setTextColor(Color.WHITE)
            btnModeVideo.setBackgroundResource(R.drawable.bg_mode_inactive)
            btnModeVideo.setTextColor(Color.parseColor("#80FFFFFF"))
        } else {
            btnModeVideo.setBackgroundResource(R.drawable.bg_mode_active)
            btnModeVideo.setTextColor(Color.WHITE)
            btnModePhoto.setBackgroundResource(R.drawable.bg_mode_inactive)
            btnModePhoto.setTextColor(Color.parseColor("#80FFFFFF"))
        }
    }

    // -----------------------------------------------------------------------
    // Bottom Buttons: capture, effects, switch camera
    // -----------------------------------------------------------------------

    private fun setupBottomButtons() {
        // Effects button — toggles the effect panel
        btnEffects.setOnClickListener {
            toggleEffectPanel()
        }

        // Switch camera (placeholder — currently only front camera)
        btnSwitchCamera.setOnClickListener {
            Toast.makeText(this, "Camera switch not yet implemented", Toast.LENGTH_SHORT).show()
        }

        // Capture button
        btnCapture.setOnClickListener {
            handleCapture()
        }

        updateCaptureButton()
    }

    private fun updateCaptureButton() {
        when {
            isScanning -> btnCapture.setImageResource(R.drawable.ic_pause)
            mode == "video" -> btnCapture.setImageResource(R.drawable.ic_video)
            else -> btnCapture.setImageResource(R.drawable.ic_camera)
        }
    }

    private fun handleCapture() {
        val timerSeconds = timerOptions[timerIndex]

        when (scanEngine.currentState()) {
            WaterfallScanEngine.State.IDLE -> {
                if (timerSeconds > 0) {
                    startCountdown(timerSeconds) { startScan() }
                } else {
                    startScan()
                }
            }
            WaterfallScanEngine.State.SCANNING -> {
                // Stop scan early
                glSurfaceView.queueEvent { scanEngine.stopScan() }
            }
            WaterfallScanEngine.State.COMPLETE -> {
                // Save the result
                saveCompositePhoto()
            }
        }
    }

    private fun startScan() {
        glSurfaceView.queueEvent {
            renderer.clearComposite()
            scanEngine.startScan()
        }
        isScanning = true
        isRecording = true
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Scanning… tap to stop"
        updateCaptureButton()
    }

    private fun startCountdown(seconds: Int, onComplete: () -> Unit) {
        tvTimerCountdown.visibility = View.VISIBLE
        tvTimerCountdown.text = seconds.toString()

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val remaining = (millisUntilFinished / 1000).toInt() + 1
                tvTimerCountdown.text = remaining.toString()
            }

            override fun onFinish() {
                tvTimerCountdown.visibility = View.GONE
                onComplete()
            }
        }.start()
    }

    // -----------------------------------------------------------------------
    // Effect Selector Panel
    // -----------------------------------------------------------------------

    private fun setupEffectsPanel() {
        effectAdapter = EffectAdapter(effects, selectedEffectId) { effect ->
            selectedEffectId = effect.id
            effectAdapter.setSelected(effect.id)
            btnEffects.setImageResource(effect.imageResId)
            effectPanel.visibility = View.GONE
            // Apply effect to the GL renderer
            if (::renderer.isInitialized) {
                renderer.currentEffect = effect.id
            }
        }

        rvEffects.layoutManager = GridLayoutManager(this, 4)
        rvEffects.adapter = effectAdapter

        btnCloseEffects.setOnClickListener {
            effectPanel.visibility = View.GONE
        }
    }

    private fun toggleEffectPanel() {
        effectPanel.visibility =
            if (effectPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
    }

    // -----------------------------------------------------------------------
    // Photo save
    // -----------------------------------------------------------------------

    private fun saveCompositePhoto() {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Saving…"

        glSurfaceView.queueEvent {
            val bitmap = renderer.readCompositePixels()
            if (bitmap != null) {
                lifecycleScope.launch {
                    val uri = BitmapUtils.saveToGallery(this@CameraActivity, bitmap)
                    bitmap.recycle()

                    runOnUiThread {
                        if (uri != null) {
                            tvStatus.text = "Saved to gallery!"
                            Toast.makeText(this@CameraActivity, "Photo saved", Toast.LENGTH_SHORT).show()
                        } else {
                            tvStatus.text = "Save failed"
                            Toast.makeText(this@CameraActivity, "Failed to save", Toast.LENGTH_SHORT).show()
                        }

                        // Reset for new scan
                        glSurfaceView.queueEvent {
                            scanEngine.reset()
                            renderer.clearComposite()
                        }
                        tvStatus.postDelayed({
                            tvStatus.visibility = View.GONE
                        }, 2000)
                        updateCaptureButton()
                    }
                }
            } else {
                runOnUiThread {
                    tvStatus.text = "No image to save"
                }
            }
        }
    }
}

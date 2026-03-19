package com.timewarpscan.nativecamera.ui

import android.Manifest
import android.content.Intent
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
import android.opengl.EGL14
import com.timewarpscan.nativecamera.R
import com.timewarpscan.nativecamera.camera.CameraController
import com.timewarpscan.nativecamera.record.VideoRecorder
import com.timewarpscan.nativecamera.render.ScanRenderer
import com.timewarpscan.nativecamera.scan.WaterfallScanEngine
import com.timewarpscan.nativecamera.util.BitmapUtils
import com.timewarpscan.nativecamera.core.ads.AdManager
import kotlinx.coroutines.launch
import java.io.File

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

    // --- Video ---
    private var videoRecorder: VideoRecorder? = null
    private var selectedEffectId: String = "normal"

    private var scanReversed = false
    private val scanLineColors = listOf("#00FFFF", "#FF0000", "#00FF00", "#FFFF00", "#FF00FF", "#FFFFFF")
    private var scanLineColorIndex = 0
    private val timerOptions = intArrayOf(0, 3, 5, 10)
    private var timerIndex = 0
    private val speedOptions = floatArrayOf(1f, 1.5f, 2f, 3f)
    private var speedIndex = 0

    private var countdownTimer: CountDownTimer? = null
    private var rendererSet = false

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

        // Apply effect from intent (e.g. from HomeActivity)
        intent.getStringExtra("effect")?.let { effectId ->
            if (effects.any { it.id == effectId }) {
                selectedEffectId = effectId
            }
        }
        updateToolbarForEffect()

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
        if (rendererSet) glSurfaceView.onResume()
    }

    override fun onPause() {
        // Stop recording before pausing GL thread so the file is properly finalized.
        // Must happen here (not onDestroy) because GL thread is still alive.
        videoRecorder?.let { rec ->
            isRecording = false
            isScanning = false
            glSurfaceView.queueEvent {
                scanEngine.reset()
                if (::renderer.isInitialized) {
                    renderer.clearComposite()
                    renderer.videoRecorder = null
                }
                rec.stop()
            }
            videoRecorder = null
        }
        if (rendererSet) glSurfaceView.onPause()
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
        rendererSet = true
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY

        renderer.onSurfaceTextureAvailable = { surfaceTexture ->
            runOnUiThread {
                cameraController.startCamera(
                    lifecycleOwner = this,
                    surfaceTexture = surfaceTexture,
                    previewWidth = 1080,
                    previewHeight = 1920
                ) { width, height ->
                    glSurfaceView.queueEvent {
                        renderer.setCameraResolution(width, height)
                    }
                }
            }
        }

        renderer.onScanComplete = {
            runOnUiThread {
                isScanning = false
                if (mode == "video") {
                    // Scan sweep finished during video recording — keep recording,
                    // just update status so user knows they can stop.
                    tvStatus.text = "Sweep done — tap to stop"
                } else {
                    isRecording = false
                    tvStatus.visibility = View.VISIBLE
                    tvStatus.text = "Scan complete — tap to save"
                    AdManager.frequencyController.recordAction()
                }
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
            scanReversed = !scanReversed
            icDirection.rotation = if (scanReversed) 180f else 0f
            if (::renderer.isInitialized) renderer.scanReversed = scanReversed
        }

        // Scan line color cycle
        btnScanColor.setOnClickListener {
            scanLineColorIndex = (scanLineColorIndex + 1) % scanLineColors.size
            val hex = scanLineColors[scanLineColorIndex]
            val color = Color.parseColor(hex)
            icScanColor.setColorFilter(color, PorterDuff.Mode.SRC_IN)
            if (::renderer.isInitialized) {
                val r = Color.red(color) / 255f
                val g = Color.green(color) / 255f
                val b = Color.blue(color) / 255f
                renderer.scanLineColor = floatArrayOf(r, g, b, 1f)
            }
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

    private fun updateToolbarForEffect() {
        val isScanEffect = selectedEffectId == "waterfall" || selectedEffectId == "single"
        btnDirection.visibility = if (isScanEffect) View.VISIBLE else View.GONE
        btnScanColor.visibility = if (isScanEffect) View.VISIBLE else View.GONE
        // Reset direction state when effect changes
        scanReversed = false
        icDirection.rotation = 0f
        if (::renderer.isInitialized) renderer.scanReversed = false
        // Use the correct direction icon for the active scan type
        val dirIcon = if (selectedEffectId == "single") R.drawable.ic_left_right else R.drawable.ic_top_bot
        icDirection.setImageResource(dirIcon)
    }

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
            btnModePhoto.setTextColor(Color.parseColor("#1A1A1A"))
            btnModeVideo.setBackgroundResource(R.drawable.bg_mode_inactive)
            btnModeVideo.setTextColor(Color.parseColor("#80FFFFFF"))
        } else {
            btnModeVideo.setBackgroundResource(R.drawable.bg_mode_active)
            btnModeVideo.setTextColor(Color.parseColor("#1A1A1A"))
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
            mode == "video" && isRecording -> btnCapture.setImageResource(R.drawable.ic_pause)
            mode == "video" -> btnCapture.setImageResource(R.drawable.ic_video)
            else -> btnCapture.setImageResource(R.drawable.ic_camera)
        }
    }

    private fun handleCapture() {
        val timerSeconds = timerOptions[timerIndex]

        // VIDEO MODE
        if (mode == "video") {
            if (isRecording) {
                stopVideoRecording()
            } else {
                if (timerSeconds > 0) {
                    startCountdown(timerSeconds) { startVideoRecording() }
                } else {
                    startVideoRecording()
                }
            }
            return
        }

        // PHOTO MODE — Effects without a scan line capture instantly
        val isScanEffect = selectedEffectId == "waterfall" || selectedEffectId == "single"
        if (!isScanEffect && scanEngine.currentState() == WaterfallScanEngine.State.IDLE) {
            if (timerSeconds > 0) {
                startCountdown(timerSeconds) { captureInstantPhoto() }
            } else {
                captureInstantPhoto()
            }
            return
        }

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

    private fun captureInstantPhoto() {
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Processing…"

        glSurfaceView.queueEvent {
            val bitmap = renderer.captureCurrentFrame()
            if (bitmap != null) {
                lifecycleScope.launch {
                    val tempFile = BitmapUtils.saveTempFile(this@CameraActivity, bitmap)
                    bitmap.recycle()
                    runOnUiThread {
                        tvStatus.visibility = View.GONE
                        if (tempFile != null) {
                            startActivity(
                                Intent(this@CameraActivity, PreviewActivity::class.java).apply {
                                    putExtra(PreviewActivity.EXTRA_TEMP_PATH, tempFile.absolutePath)
                                }
                            )
                        } else {
                            tvStatus.text = "Capture failed"
                            tvStatus.postDelayed({ tvStatus.visibility = View.GONE }, 2000)
                        }
                    }
                }
            } else {
                runOnUiThread {
                    tvStatus.text = "Capture failed"
                    tvStatus.postDelayed({ tvStatus.visibility = View.GONE }, 2000)
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Video recording
    // -----------------------------------------------------------------------

    private fun startVideoRecording() {
        val isScanEffect = selectedEffectId == "waterfall" || selectedEffectId == "single"
        val outputPath = File(cacheDir, "video_${System.currentTimeMillis()}.mp4").absolutePath
        isRecording = true
        if (isScanEffect) isScanning = true
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Recording\u2026 tap to stop"
        updateCaptureButton()

        glSurfaceView.queueEvent {
            // Start scan engine for scan effects so the sweep appears in the video.
            if (isScanEffect) {
                renderer.clearComposite()
                scanEngine.startScan()
            }
            val recorder = VideoRecorder()
            recorder.prepare(renderer.rendererWidth, renderer.rendererHeight, 30, outputPath, EGL14.eglGetCurrentContext())
            // If hardware encoder fails mid-recording, auto-stop and notify user
            recorder.onError = {
                runOnUiThread {
                    if (isRecording) {
                        stopVideoRecording()
                        Toast.makeText(this@CameraActivity, "Recording failed on this device", Toast.LENGTH_SHORT).show()
                    }
                }
            }
            recorder.start()
            renderer.videoRecorder = recorder
            videoRecorder = recorder
        }
    }

    private fun stopVideoRecording() {
        isRecording = false
        isScanning = false
        tvStatus.visibility = View.VISIBLE
        tvStatus.text = "Saving\u2026"
        updateCaptureButton()

        glSurfaceView.queueEvent {
            // Reset scan engine in case it was running for scan effect.
            scanEngine.reset()
            renderer.clearComposite()
            val path = videoRecorder?.stop()
            renderer.videoRecorder = null
            videoRecorder = null

            runOnUiThread {
                tvStatus.visibility = View.GONE
                if (path != null) {
                    startActivity(
                        Intent(this@CameraActivity, PreviewActivity::class.java).apply {
                            putExtra(PreviewActivity.EXTRA_TEMP_PATH, path)
                            putExtra(PreviewActivity.EXTRA_IS_VIDEO, true)
                        }
                    )
                } else {
                    tvStatus.text = "Recording failed"
                    tvStatus.postDelayed({ tvStatus.visibility = View.GONE }, 2000)
                }
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
            updateToolbarForEffect()
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
        tvStatus.text = "Processing…"

        glSurfaceView.queueEvent {
            val bitmap = renderer.readCompositePixels()
            if (bitmap != null) {
                lifecycleScope.launch {
                    val tempFile = BitmapUtils.saveTempFile(this@CameraActivity, bitmap)
                    bitmap.recycle()

                    runOnUiThread {
                        tvStatus.visibility = View.GONE
                        // Reset scan engine for next capture
                        glSurfaceView.queueEvent {
                            scanEngine.reset()
                            renderer.clearComposite()
                        }
                        isScanning = false
                        isRecording = false
                        updateCaptureButton()

                        if (tempFile != null) {
                            startActivity(
                                Intent(this@CameraActivity, PreviewActivity::class.java).apply {
                                    putExtra(PreviewActivity.EXTRA_TEMP_PATH, tempFile.absolutePath)
                                }
                            )
                        } else {
                            tvStatus.text = "Failed to process"
                            tvStatus.postDelayed({ tvStatus.visibility = View.GONE }, 2000)
                        }
                    }
                }
            } else {
                runOnUiThread {
                    tvStatus.text = "No image to save"
                    tvStatus.postDelayed({ tvStatus.visibility = View.GONE }, 2000)
                }
            }
        }
    }
}

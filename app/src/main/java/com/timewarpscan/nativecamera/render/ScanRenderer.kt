package com.timewarpscan.nativecamera.render

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.timewarpscan.nativecamera.record.VideoRecorder
import com.timewarpscan.nativecamera.scan.WaterfallScanEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 renderer for the waterfall scan effect.
 *
 * Renders three layers each frame:
 *   1. Live camera preview (full screen, below scan line)
 *   2. Composite image (accumulated strips, above scan line)
 *   3. Cyan scan line overlay
 *
 * The composite is stored in a Framebuffer Object (FBO). Each frame during
 * scanning, a horizontal strip is copied from the camera OES texture into
 * the composite FBO using GL scissor test (no CPU copies, no Bitmap allocs).
 *
 * Coordinate system note:
 *   OpenGL Y=0 is BOTTOM of screen. Android Y=0 is TOP.
 *   The scan moves top→bottom visually, which in GL is height→0.
 *   We use a flipped projection matrix so that Y=0 in our draw calls
 *   corresponds to the TOP of the screen (matching Android coords).
 */
class ScanRenderer(
    private val scanEngine: WaterfallScanEngine
) : GLSurfaceView.Renderer {

    /** Set this to start/stop video recording. Must only be assigned from the GL thread. */
    @Volatile
    var videoRecorder: VideoRecorder? = null

    companion object {
        private const val TAG = "ScanRenderer"

        /** Thickness of the cyan scan line in pixels. */
        private const val SCAN_LINE_THICKNESS = 4

        /** Cyan color: #00FFFF with full alpha. */
        private val SCAN_LINE_COLOR = floatArrayOf(0f, 1f, 1f, 1f)

        // Full-screen quad: position (x,y) + texcoord (s,t)
        // Two triangles covering the entire [-1, 1] range
        private val QUAD_COORDS = floatArrayOf(
            // x,    y,   s,   t
            -1f, -1f,  0f, 0f,  // bottom-left
             1f, -1f,  1f, 0f,  // bottom-right
            -1f,  1f,  0f, 1f,  // top-left
             1f,  1f,  1f, 1f   // top-right
        )
        private const val COORDS_PER_VERTEX = 4 // x, y, s, t
        private const val FLOAT_SIZE = 4
    }

    private enum class ScanMode { NONE, VERTICAL, HORIZONTAL }

    private val scanMode: ScanMode
        get() = when (currentEffect) {
            "waterfall" -> ScanMode.VERTICAL
            "single"    -> ScanMode.HORIZONTAL
            else        -> ScanMode.NONE
        }

    // --- GL resources ---
    private var cameraTextureId = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val flipMvpMatrix = FloatArray(16) // flipped projection for screen rendering

    // Shader programs
    private var cameraProgramId = 0
    private var textureProgramId = 0
    private var colorProgramId = 0

    // Effect shader programs (effect ID → GL program handle)
    private val effectPrograms = mutableMapOf<String, Int>()

    /** Currently active camera effect. Set from the UI thread; read on GL thread. */
    @Volatile
    var currentEffect: String = "normal"

    /** Scan line color (RGBA). Can be updated from the UI thread. */
    @Volatile
    var scanLineColor: FloatArray = floatArrayOf(0f, 1f, 1f, 1f)

    /** If true, reverses the scan direction (bottom→top for VERTICAL, right→left for HORIZONTAL). */
    @Volatile
    var scanReversed: Boolean = false

    // Composite FBO
    private var fboId = 0
    private var fboTextureId = 0

    // Output FBO — holds the final composed frame so the encoder can blit it
    // as a regular TEXTURE_2D without ever touching the camera OES texture.
    private var outputFboId = 0
    private var outputTexId = 0

    // Quad VBO
    private var quadBuffer: FloatBuffer? = null

    // Surface dimensions
    private var viewWidth = 0
    private var viewHeight = 0

    /** Surface width in pixels — valid after onSurfaceChanged. */
    val rendererWidth: Int get() = viewWidth

    /** Surface height in pixels — valid after onSurfaceChanged. */
    val rendererHeight: Int get() = viewHeight

    // Camera aspect-ratio correction (center-crop scale)
    private val cameraMvpMatrix = FloatArray(16).apply { Matrix.setIdentityM(this, 0) }
    private var camPortraitW = 0f
    private var camPortraitH = 0f

    /**
     * Set actual camera buffer resolution for center-crop aspect ratio correction.
     * Call from GL thread after camera resolution is known.
     */
    fun setCameraResolution(width: Int, height: Int) {
        // Effective portrait dimensions (camera sensor may report landscape)
        camPortraitW = minOf(width, height).toFloat()
        camPortraitH = maxOf(width, height).toFloat()
        if (viewWidth > 0 && viewHeight > 0) {
            updateCameraCropScale()
        }
    }

    private fun updateCameraCropScale() {
        val viewAspect = viewWidth.toFloat() / viewHeight.toFloat()
        val camAspect = camPortraitW / camPortraitH

        Matrix.setIdentityM(cameraMvpMatrix, 0)
        if (camAspect > viewAspect) {
            // Camera is wider than view → crop sides
            val scaleX = camAspect / viewAspect
            Matrix.scaleM(cameraMvpMatrix, 0, scaleX, 1f, 1f)
        } else {
            // Camera is taller than view → crop top/bottom
            val scaleY = viewAspect / camAspect
            Matrix.scaleM(cameraMvpMatrix, 0, 1f, scaleY, 1f)
        }
        Log.d(TAG, "Camera crop scale: cam=${camPortraitW}x${camPortraitH} view=${viewWidth}x${viewHeight}")
    }

    // Callback to notify CameraController that the SurfaceTexture is ready
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

    // Callback to notify activity that scan completed (called on GL thread)
    var onScanComplete: (() -> Unit)? = null
    private var scanCompleteNotified = false

    // -----------------------------------------------------------------------
    // GLSurfaceView.Renderer callbacks
    // -----------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)

        // Create OES texture for camera frames
        cameraTextureId = createOESTexture()
        val st = SurfaceTexture(cameraTextureId)
        surfaceTexture = st

        // Compile shader programs
        cameraProgramId = ShaderProgram.createProgram(
            ShaderProgram.CAMERA_VERTEX, ShaderProgram.CAMERA_FRAGMENT
        )
        textureProgramId = ShaderProgram.createProgram(
            ShaderProgram.TEXTURE_VERTEX, ShaderProgram.TEXTURE_FRAGMENT
        )
        colorProgramId = ShaderProgram.createProgram(
            ShaderProgram.COLOR_VERTEX, ShaderProgram.COLOR_FRAGMENT
        )

        if (cameraProgramId == 0 || textureProgramId == 0 || colorProgramId == 0) {
            Log.e(TAG, "Failed to compile one or more shader programs")
        }

        // Compile effect-specific shader programs
        compileEffectPrograms()

        // Create full-screen quad vertex buffer
        quadBuffer = ByteBuffer
            .allocateDirect(QUAD_COORDS.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(QUAD_COORDS)
                position(0)
            }

        // Notify that the SurfaceTexture is ready for camera binding
        onSurfaceTextureAvailable?.invoke(st)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES20.glViewport(0, 0, width, height)

        // Set up the scan engine with the preview dimensions
        scanEngine.previewHeight = height

        // Create an orthographic projection where Y=0 is TOP of screen
        // This maps (0,0) to top-left and (width,height) to bottom-right,
        // matching Android's coordinate system.
        Matrix.setIdentityM(mvpMatrix, 0)
        Matrix.orthoM(mvpMatrix, 0, 0f, width.toFloat(), height.toFloat(), 0f, -1f, 1f)

        // For rendering to screen, we use NDC [-1,1] with the quad
        // So we need identity MVP (quad already fills clip space)
        Matrix.setIdentityM(flipMvpMatrix, 0)

        // Recalculate camera scale if resolution is already known
        if (camPortraitW > 0f) {
            updateCameraCropScale()
        }

        // Create composite FBO
        createCompositeFBO(width, height)

        // Create output FBO (final rendered frame for encoder)
        createOutputFBO(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return

        // Pull the latest camera frame
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        // Update scan engine length based on current effect's scan axis
        val mode = scanMode
        scanEngine.previewHeight = if (mode == ScanMode.HORIZONTAL) viewWidth else viewHeight

        // Get current scan state
        val scanState = scanEngine.update()

        // --- Step 1: If scanning, copy camera strip into composite FBO ---
        if (mode != ScanMode.NONE && scanState.state == WaterfallScanEngine.State.SCANNING && scanState.scanY > scanState.lastScanY) {
            val from = scanState.lastScanY
            val to = scanState.scanY
            val delta = to - from
            when (mode) {
                ScanMode.VERTICAL -> {
                    // Forward: glY = viewHeight - to (strip at top of accumulated area)
                    // Reversed: glY = from (strip accumulates from bottom upward)
                    val glY = if (scanReversed) from else (viewHeight - to)
                    copyToCompositeGL(0, glY, viewWidth, delta)
                }
                ScanMode.HORIZONTAL -> {
                    // Forward: glX = from (strip accumulates left→right)
                    // Reversed: glX = viewWidth - to (strip accumulates right→left)
                    val glX = if (scanReversed) (viewWidth - to) else from
                    copyToCompositeGL(glX, 0, delta, viewHeight)
                }
                else -> {}
            }
        }

        // --- Step 2: Render to screen ---
        renderToScreen(scanState)

        // --- Step 3: If recording, render composition to outputFBO (GL context, OES is safe here),
        //             then switch to encoder context and blit outputTex (TEXTURE_2D — no OES involved).
        videoRecorder?.let { recorder ->
            if (recorder.isRecording() && outputFboId != 0) {
                // 3a. Bake final frame into outputFBO while OES context is still current
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFboId)
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                renderComposition(scanState)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

                // 3b. Switch to encoder EGL surface and blit the plain texture
                recorder.makeCurrent()
                GLES20.glViewport(0, 0, viewWidth, viewHeight)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                drawEncoderFrame()
                recorder.swapBuffers()
                recorder.makeNonCurrent()
                recorder.frameAvailable()
            }
        }

        // --- Step 4: Notify scan complete (once) ---
        if (scanState.state == WaterfallScanEngine.State.COMPLETE && !scanCompleteNotified) {
            scanCompleteNotified = true
            onScanComplete?.invoke()
        }

        // Reset notification flag when scan is reset
        if (scanState.state == WaterfallScanEngine.State.IDLE) {
            scanCompleteNotified = false
        }
    }

    // -----------------------------------------------------------------------
    // Strip compositing — copies a horizontal band from camera into the FBO
    // -----------------------------------------------------------------------

    /**
     * Copy a rectangular region into the composite FBO using GL scissor.
     * All coordinates are in GL space (Y=0 bottom).
     */
    private fun copyToCompositeGL(glX: Int, glY: Int, glWidth: Int, glHeight: Int) {
        if (glWidth <= 0 || glHeight <= 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(glX, glY, glWidth, glHeight)
        drawCameraTexture()
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // -----------------------------------------------------------------------
    // Screen rendering — composites all layers
    // -----------------------------------------------------------------------

    /**
     * Render the final composition to the default framebuffer (screen).
     */
    private fun renderToScreen(scanState: WaterfallScanEngine.ScanState) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        renderComposition(scanState)
    }

    /**
     * Shared composition logic — used for both screen and encoder surface.
     *
     * Layer order:
     *   1. Live camera preview (full screen background)
     *   2. Composite FBO texture (clipped above scan line)
     *   3. Cyan scan line
     */
    private fun renderComposition(scanState: WaterfallScanEngine.ScanState) {
        val isScanning = scanState.state == WaterfallScanEngine.State.SCANNING
        val isComplete = scanState.state == WaterfallScanEngine.State.COMPLETE

        when (scanMode) {
            ScanMode.NONE -> {
                // No scan — always show live camera with effect shader
                drawCameraTexture()
            }
            ScanMode.VERTICAL -> {
                val scanY = scanState.scanY
                // In reversed mode the visual scan line starts at screen-bottom and moves up.
                // actualScanScreenY: screen Y (0=top) of the scan line.
                val actualScanScreenY = if (scanReversed) viewHeight - scanY else scanY

                // Layer 1: Live camera on the "ahead-of-scan" side
                if (!isComplete) {
                    if (isScanning) {
                        if (!scanReversed) {
                            // Forward (top→bottom): live camera is BELOW scan line
                            // GL: y=0...(viewHeight-scanY)
                            val glH = viewHeight - scanY
                            if (glH > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(0, 0, viewWidth, glH)
                                drawCameraTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        } else {
                            // Reversed (bottom→top): live camera is ABOVE scan line
                            // GL: y=scanY...viewHeight
                            val glH = viewHeight - scanY
                            if (glH > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(0, scanY, viewWidth, glH)
                                drawCameraTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        }
                    } else {
                        drawCameraTexture()
                    }
                }
                // Layer 2: Composite on the "already-scanned" side
                if (isScanning || isComplete) {
                    if (isScanning) {
                        if (!scanReversed) {
                            // Forward: composite is ABOVE scan line → GL y=(viewHeight-scanY)...viewHeight
                            val glBottom = viewHeight - scanY
                            if (scanY > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(0, glBottom, viewWidth, scanY)
                                drawCompositeTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        } else {
                            // Reversed: composite is BELOW scan line → GL y=0...scanY
                            if (scanY > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(0, 0, viewWidth, scanY)
                                drawCompositeTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        }
                    } else {
                        drawCompositeTexture()
                    }
                }
                // Layer 3: Horizontal scan line at visual position
                if (isScanning) drawScanLine(actualScanScreenY)
            }
            ScanMode.HORIZONTAL -> {
                val scanX = scanState.scanY // scanY repurposed as X position
                // actualScanScreenX: screen X of the scan line.
                val actualScanScreenX = if (scanReversed) viewWidth - scanX else scanX

                // Layer 1: Live camera on the "ahead-of-scan" side
                if (!isComplete) {
                    if (isScanning) {
                        if (!scanReversed) {
                            // Forward (left→right): live camera is RIGHT of scan line
                            val glW = viewWidth - scanX
                            if (glW > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(scanX, 0, glW, viewHeight)
                                drawCameraTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        } else {
                            // Reversed (right→left): live camera is LEFT of scan line
                            if (actualScanScreenX > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(0, 0, actualScanScreenX, viewHeight)
                                drawCameraTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        }
                    } else {
                        drawCameraTexture()
                    }
                }
                // Layer 2: Composite on the "already-scanned" side
                if (isScanning || isComplete) {
                    if (isScanning) {
                        if (!scanReversed) {
                            // Forward: composite is LEFT of scan line
                            if (scanX > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(0, 0, scanX, viewHeight)
                                drawCompositeTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        } else {
                            // Reversed: composite is RIGHT of scan line
                            val glW = viewWidth - actualScanScreenX
                            if (glW > 0) {
                                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                                GLES20.glScissor(actualScanScreenX, 0, glW, viewHeight)
                                drawCompositeTexture()
                                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                            }
                        }
                    } else {
                        drawCompositeTexture()
                    }
                }
                // Layer 3: Vertical scan line at visual position
                if (isScanning) drawVerticalScanLine(actualScanScreenX)
            }
        }
    }

    // -----------------------------------------------------------------------
    // Draw helpers
    // -----------------------------------------------------------------------

    /** Draw the camera OES texture as a full-screen quad, applying the current effect. */
    private fun drawCameraTexture() {
        // Scan-line effects (waterfall/single) implement their effect via strip compositing,
        // not via a shader — use plain camera shader so the live preview looks normal.
        val effect = currentEffect
        val programId = if (effect != "normal" && scanMode == ScanMode.NONE) {
            effectPrograms[effect] ?: cameraProgramId
        } else {
            cameraProgramId
        }

        GLES20.glUseProgram(programId)

        val posLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")

        // Use center-crop scale matrix for camera (preserves aspect ratio)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, cameraMvpMatrix, 0)
        GLES20.glUniformMatrix4fv(texMatLoc, 1, false, texMatrix, 0)

        val buf = quadBuffer ?: return
        buf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * FLOAT_SIZE, buf)
        GLES20.glEnableVertexAttribArray(posLoc)

        buf.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * FLOAT_SIZE, buf)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programId, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    /** Draw the composite FBO texture as a full-screen quad. */
    /**
     * Blit [outputTexId] (a plain TEXTURE_2D) to the currently bound EGL surface.
     * Called from the encoder's EGL context — safe because no OES texture is used.
     */
    private fun drawEncoderFrame() {
        GLES20.glUseProgram(textureProgramId)

        val posLoc = GLES20.glGetAttribLocation(textureProgramId, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(textureProgramId, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(textureProgramId, "uMVPMatrix")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, flipMvpMatrix, 0)

        val buf = quadBuffer ?: return
        buf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * FLOAT_SIZE, buf)
        GLES20.glEnableVertexAttribArray(posLoc)

        buf.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * FLOAT_SIZE, buf)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(textureProgramId, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    private fun drawCompositeTexture() {
        GLES20.glUseProgram(textureProgramId)

        val posLoc = GLES20.glGetAttribLocation(textureProgramId, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(textureProgramId, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(textureProgramId, "uMVPMatrix")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, flipMvpMatrix, 0)

        val buf = quadBuffer ?: return
        buf.position(0)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * FLOAT_SIZE, buf)
        GLES20.glEnableVertexAttribArray(posLoc)

        buf.position(2)
        GLES20.glVertexAttribPointer(texLoc, 2, GLES20.GL_FLOAT, false, COORDS_PER_VERTEX * FLOAT_SIZE, buf)
        GLES20.glEnableVertexAttribArray(texLoc)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(textureProgramId, "uTexture"), 0)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(texLoc)
    }

    /**
     * Draw a cyan horizontal line at [scanY] (screen coords, Y=0 at top).
     *
     * Creates a thin quad at the scan position and draws it with solid color.
     */
    private fun drawScanLine(scanY: Int) {
        GLES20.glUseProgram(colorProgramId)

        val mvpLoc = GLES20.glGetUniformLocation(colorProgramId, "uMVPMatrix")
        val colorLoc = GLES20.glGetUniformLocation(colorProgramId, "uColor")
        val posLoc = GLES20.glGetAttribLocation(colorProgramId, "aPosition")

        // Use the ortho projection (Y=0 at top, Y=height at bottom)
        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorLoc, 1, scanLineColor, 0)

        // Build a thin horizontal quad at scanY
        val y1 = scanY.toFloat()
        val y2 = (scanY + SCAN_LINE_THICKNESS).toFloat()
        val w = viewWidth.toFloat()
        val lineCoords = floatArrayOf(
            0f, y1,   // top-left
            w,  y1,   // top-right
            0f, y2,   // bottom-left
            w,  y2    // bottom-right
        )

        val lineBuf = ByteBuffer
            .allocateDirect(lineCoords.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(lineCoords)
                position(0)
            }

        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, lineBuf)
        GLES20.glEnableVertexAttribArray(posLoc)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
    }

    /** Draw a vertical scan line at [scanX] (screen coords, X=0 at left) for horizontal scan mode. */
    private fun drawVerticalScanLine(scanX: Int) {
        GLES20.glUseProgram(colorProgramId)

        val mvpLoc   = GLES20.glGetUniformLocation(colorProgramId, "uMVPMatrix")
        val colorLoc = GLES20.glGetUniformLocation(colorProgramId, "uColor")
        val posLoc   = GLES20.glGetAttribLocation(colorProgramId, "aPosition")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniform4fv(colorLoc, 1, scanLineColor, 0)

        val x1 = scanX.toFloat()
        val x2 = (scanX + SCAN_LINE_THICKNESS).toFloat()
        val h  = viewHeight.toFloat()
        val lineCoords = floatArrayOf(
            x1, 0f,   // top-left
            x2, 0f,   // top-right
            x1, h,    // bottom-left
            x2, h     // bottom-right
        )

        val lineBuf = ByteBuffer
            .allocateDirect(lineCoords.size * FLOAT_SIZE)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(lineCoords); position(0) }

        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 0, lineBuf)
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(posLoc)
    }

    // -----------------------------------------------------------------------
    // FBO management
    // -----------------------------------------------------------------------

    /**
     * Create the composite framebuffer object with an RGBA texture attachment.
     * This stores the accumulated scan result (frozen strips from camera).
     */
    private fun createOutputFBO(width: Int, height: Int) {
        if (outputFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(outputFboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(outputTexId), 0)
        }
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        outputTexId = texIds[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTexId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        outputFboId = fboIds[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, outputFboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, outputTexId, 0
        )
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun createCompositeFBO(width: Int, height: Int) {
        // Delete previous FBO if exists
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
        }

        // Create texture for FBO
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        fboTextureId = texIds[0]

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            width, height, 0,
            GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null
        )

        // Create FBO and attach texture
        val fboIds = IntArray(1)
        GLES20.glGenFramebuffers(1, fboIds, 0)
        fboId = fboIds[0]

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glFramebufferTexture2D(
            GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
            GLES20.GL_TEXTURE_2D, fboTextureId, 0
        )

        val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            Log.e(TAG, "Framebuffer not complete: $status")
        }

        // Clear the composite to black
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Unbind
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)

        Log.d(TAG, "Composite FBO created: ${width}x${height}")
    }

    // -----------------------------------------------------------------------
    // Effect program compilation
    // -----------------------------------------------------------------------

    /** Compile shader programs for all camera effects. */
    private fun compileEffectPrograms() {
        val effectShaders = mapOf(
            "swirl"     to ShaderProgram.EFFECT_SWIRL_FRAGMENT,
            "grid"      to ShaderProgram.EFFECT_GRID_FRAGMENT,
            "mirror"    to ShaderProgram.EFFECT_MIRROR_FRAGMENT,
            "double"    to ShaderProgram.EFFECT_DOUBLE_FRAGMENT,
            "waterfall" to ShaderProgram.EFFECT_WATERFALL_FRAGMENT,
            "split"     to ShaderProgram.EFFECT_SPLIT_FRAGMENT,
            "single"    to ShaderProgram.EFFECT_SINGLE_FRAGMENT,
        )
        for ((id, fragSrc) in effectShaders) {
            val program = ShaderProgram.createProgram(ShaderProgram.EFFECT_VERTEX, fragSrc)
            if (program != 0) {
                effectPrograms[id] = program
            } else {
                Log.e(TAG, "Failed to compile effect program: $id")
            }
        }
        Log.d(TAG, "Compiled ${effectPrograms.size} effect programs")
    }

    // -----------------------------------------------------------------------
    // OES texture creation
    // -----------------------------------------------------------------------

    /** Create an OpenGL external texture for receiving camera frames. */
    private fun createOESTexture(): Int {
        val texIds = IntArray(1)
        GLES20.glGenTextures(1, texIds, 0)
        val texId = texIds[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return texId
    }

    // -----------------------------------------------------------------------
    // Public API for photo capture
    // -----------------------------------------------------------------------

    /**
     * Read pixels from the composite FBO into a Bitmap.
     * Must be called from the GL thread (e.g. via GLSurfaceView.queueEvent).
     *
     * @return Bitmap containing the composite image (scan result), or null on error
     */
    fun readCompositePixels(): Bitmap? {
        if (fboId == 0 || viewWidth <= 0 || viewHeight <= 0) return null

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)

        val buffer = IntBuffer.allocate(viewWidth * viewHeight)
        GLES20.glReadPixels(0, 0, viewWidth, viewHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)

        // glReadPixels returns bottom-to-top; flip vertically for Android Bitmap (top-to-bottom)
        val pixels = buffer.array()
        val flipped = IntArray(viewWidth * viewHeight)
        for (row in 0 until viewHeight) {
            val srcOffset = row * viewWidth
            val dstOffset = (viewHeight - 1 - row) * viewWidth
            System.arraycopy(pixels, srcOffset, flipped, dstOffset, viewWidth)
        }

        // RGBA from GL → ARGB for Bitmap.
        // Android drivers return bytes in BGRA order despite the GL_RGBA flag:
        //   bits 0-7  = B, bits 8-15 = G, bits 16-23 = R, bits 24-31 = A
        for (i in flipped.indices) {
            val pixel = flipped[i]
            val b = (pixel and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val r = ((pixel shr 16) and 0xFF)
            val a = ((pixel shr 24) and 0xFF)
            flipped[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        val bitmap = Bitmap.createBitmap(viewWidth, viewHeight, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(IntBuffer.wrap(flipped))
        return bitmap
    }

    /** Clear the composite FBO (for starting a new scan). */
    fun clearComposite() {
        if (fboId == 0) return
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    /**
     * Capture the current camera frame with effect applied into a Bitmap.
     * Used for instant photo capture when no scan is needed (NONE scan mode).
     * Must be called from the GL thread.
     */
    fun captureCurrentFrame(): Bitmap? {
        if (fboId == 0 || viewWidth <= 0 || viewHeight <= 0) return null
        // Render live camera + effect into the composite FBO
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawCameraTexture()
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return readCompositePixels()
    }

    /** Release all GL resources. */
    fun release() {
        if (fboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
            fboId = 0
        }
        if (fboTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(fboTextureId), 0)
            fboTextureId = 0
        }
        if (outputFboId != 0) {
            GLES20.glDeleteFramebuffers(1, intArrayOf(outputFboId), 0)
            outputFboId = 0
        }
        if (outputTexId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(outputTexId), 0)
            outputTexId = 0
        }
        if (cameraTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(cameraTextureId), 0)
            cameraTextureId = 0
        }
        if (cameraProgramId != 0) {
            GLES20.glDeleteProgram(cameraProgramId)
            cameraProgramId = 0
        }
        if (textureProgramId != 0) {
            GLES20.glDeleteProgram(textureProgramId)
            textureProgramId = 0
        }
        if (colorProgramId != 0) {
            GLES20.glDeleteProgram(colorProgramId)
            colorProgramId = 0
        }
        for ((_, program) in effectPrograms) {
            GLES20.glDeleteProgram(program)
        }
        effectPrograms.clear()
        surfaceTexture?.release()
        surfaceTexture = null
    }
}

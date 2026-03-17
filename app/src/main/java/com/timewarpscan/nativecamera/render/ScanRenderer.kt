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
    private val scanEngine: WaterfallScanEngine,
    private val videoRecorder: VideoRecorder? = null
) : GLSurfaceView.Renderer {

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

    // Composite FBO
    private var fboId = 0
    private var fboTextureId = 0

    // Quad VBO
    private var quadBuffer: FloatBuffer? = null

    // Surface dimensions
    private var viewWidth = 0
    private var viewHeight = 0

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

        // Create composite FBO
        createCompositeFBO(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val st = surfaceTexture ?: return

        // Pull the latest camera frame
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)

        // Get current scan state
        val scanState = scanEngine.update()

        // --- Step 1: If scanning, copy camera strip into composite FBO ---
        if (scanState.state == WaterfallScanEngine.State.SCANNING && scanState.scanY > scanState.lastScanY) {
            copyStripToComposite(scanState.lastScanY, scanState.scanY)
        }

        // --- Step 2: Render to screen ---
        renderToScreen(scanState)

        // --- Step 3: If recording, also render to encoder surface ---
        videoRecorder?.let { recorder ->
            if (recorder.isRecording()) {
                recorder.makeCurrent()
                renderComposition(scanState)
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
     * Copy pixels from camera texture into the composite FBO.
     *
     * Uses GL scissor test to restrict drawing to just the strip between
     * [fromY] and [toY]. The camera texture is drawn as a full-screen quad,
     * but only the scissored region is written to the FBO.
     *
     * Coordinates are in "screen space" where Y=0 is top.
     * In GL framebuffer coords, we need to flip: glY = height - screenY.
     */
    private fun copyStripToComposite(fromY: Int, toY: Int) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId)
        GLES20.glViewport(0, 0, viewWidth, viewHeight)

        // Convert screen coords (Y=0 top) to GL coords (Y=0 bottom)
        val glBottom = viewHeight - toY
        val stripHeight = toY - fromY

        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(0, glBottom, viewWidth, stripHeight)

        // Draw the full camera frame — only the scissored strip is actually written
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

        // Layer 1: Live camera preview (only draw below scan line during scan,
        // or not at all when complete — the composite covers everything)
        if (!isComplete) {
            if (isScanning) {
                // Only show live feed below the scan line
                val glBottom = 0
                val glHeight = viewHeight - scanState.scanY
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor(0, glBottom, viewWidth, glHeight)
                drawCameraTexture()
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
            } else {
                // IDLE: show full camera preview
                drawCameraTexture()
            }
        }

        // Layer 2: Composite image (above scan line during scan, full when complete)
        if (isScanning || isComplete) {
            if (isScanning) {
                // Show composite above scan line
                val glBottom = viewHeight - scanState.scanY
                val glHeight = scanState.scanY
                if (glHeight > 0) {
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                    GLES20.glScissor(0, glBottom, viewWidth, glHeight)
                    drawCompositeTexture()
                    GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                }
            } else {
                // COMPLETE: show full composite
                drawCompositeTexture()
            }
        }

        // Layer 3: Cyan scan line
        if (isScanning) {
            drawScanLine(scanState.scanY)
        }
    }

    // -----------------------------------------------------------------------
    // Draw helpers
    // -----------------------------------------------------------------------

    /** Draw the camera OES texture as a full-screen quad, applying the current effect. */
    private fun drawCameraTexture() {
        // Pick the right program: effect program if available, otherwise default camera
        val effect = currentEffect
        val programId = if (effect != "normal") {
            effectPrograms[effect] ?: cameraProgramId
        } else {
            cameraProgramId
        }

        GLES20.glUseProgram(programId)

        val posLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        val texLoc = GLES20.glGetAttribLocation(programId, "aTexCoord")
        val mvpLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        val texMatLoc = GLES20.glGetUniformLocation(programId, "uTexMatrix")

        GLES20.glUniformMatrix4fv(mvpLoc, 1, false, flipMvpMatrix, 0)
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
        GLES20.glUniform4fv(colorLoc, 1, SCAN_LINE_COLOR, 0)

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

    // -----------------------------------------------------------------------
    // FBO management
    // -----------------------------------------------------------------------

    /**
     * Create the composite framebuffer object with an RGBA texture attachment.
     * This stores the accumulated scan result (frozen strips from camera).
     */
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

        // RGBA from GL → ARGB for Bitmap: swap R and B channels
        for (i in flipped.indices) {
            val pixel = flipped[i]
            val r = (pixel and 0xFF)
            val g = ((pixel shr 8) and 0xFF)
            val b = ((pixel shr 16) and 0xFF)
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

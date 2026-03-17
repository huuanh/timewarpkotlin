package com.timewarpscan.nativecamera.record

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.util.Log
import android.view.Surface

/**
 * Video recorder using MediaCodec H.264 encoder + MediaMuxer.
 *
 * Unlike the existing RN VideoEncoderModule which decodes base64 JPEGs on each
 * frame, this recorder shares the GL context with the renderer's GLSurfaceView.
 * The renderer draws the composition directly onto the encoder's input Surface
 * via EGL, avoiding CPU-side bitmap decoding entirely.
 *
 * Workflow:
 *   1. Call [prepare] with the shared EGLContext from GLSurfaceView
 *   2. Call [start] to begin encoding
 *   3. Each frame: renderer calls [makeCurrent], draws, [swapBuffers], [makeNonCurrent]
 *   4. Call [frameAvailable] to drain encoder output
 *   5. Call [stop] when done — flushes, stops muxer, releases resources
 */
class VideoRecorder {

    companion object {
        private const val TAG = "VideoRecorder"
        private const val BIT_RATE = 4_000_000 // 4 Mbps
        private const val I_FRAME_INTERVAL = 1  // seconds
        private const val TIMEOUT_US = 10_000L
    }

    // MediaCodec
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private val bufferInfo = MediaCodec.BufferInfo()
    private var outputPath: String? = null

    // EGL for rendering to encoder surface
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var savedDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var savedDrawSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var savedReadSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var savedContext: EGLContext = EGL14.EGL_NO_CONTEXT

    @Volatile
    private var recording = false

    /**
     * Prepare the encoder and EGL surface.
     *
     * @param width video width in pixels
     * @param height video height in pixels
     * @param fps target frame rate
     * @param path output MP4 file path
     * @param sharedContext the EGLContext from GLSurfaceView to share textures with
     */
    fun prepare(width: Int, height: Int, fps: Int, path: String, sharedContext: EGLContext) {
        outputPath = path
        trackIndex = -1
        muxerStarted = false

        // Configure H.264 encoder
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL)
        }

        val c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        c.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = c.createInputSurface()
        c.start()
        codec = c

        muxer = MediaMuxer(path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        // Create EGL surface for the encoder's input surface
        setupEGL(sharedContext)

        Log.d(TAG, "Recorder prepared: ${width}x${height} @ ${fps}fps → $path")
    }

    /** Start recording. Must be called after [prepare]. */
    fun start() {
        recording = true
    }

    /** Check if currently recording. */
    fun isRecording(): Boolean = recording

    /**
     * Make the encoder's EGL surface current so the renderer can draw to it.
     * Saves the current EGL context/surface so it can be restored later.
     */
    fun makeCurrent() {
        savedDisplay = EGL14.eglGetCurrentDisplay()
        savedDrawSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_DRAW)
        savedReadSurface = EGL14.eglGetCurrentSurface(EGL14.EGL_READ)
        savedContext = EGL14.eglGetCurrentContext()

        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    /** Swap buffers on the encoder surface — presents the rendered frame to the encoder. */
    fun swapBuffers() {
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
    }

    /** Restore the previous EGL context/surface (back to GLSurfaceView). */
    fun makeNonCurrent() {
        EGL14.eglMakeCurrent(savedDisplay, savedDrawSurface, savedReadSurface, savedContext)
    }

    /**
     * Drain encoded output buffers into the muxer.
     * Call after each frame is rendered to the encoder surface.
     */
    fun frameAvailable() {
        drainEncoder(false)
    }

    /**
     * Stop recording — signals end of stream, drains remaining buffers,
     * stops the muxer, and releases all resources.
     *
     * @return path to the finished MP4 file
     */
    fun stop(): String? {
        recording = false
        val c = codec ?: return null

        try {
            c.signalEndOfInputStream()
            drainEncoder(true)

            muxer?.stop()
            muxer?.release()
            c.stop()
            c.release()
            inputSurface?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recorder", e)
        }

        releaseEGL()

        val path = outputPath
        codec = null
        muxer = null
        inputSurface = null
        outputPath = null
        trackIndex = -1
        muxerStarted = false

        Log.d(TAG, "Recording stopped: $path")
        return path
    }

    // -----------------------------------------------------------------------
    // EGL setup for shared context rendering to encoder surface
    // -----------------------------------------------------------------------

    private fun setupEGL(sharedContext: EGLContext) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("eglGetDisplay failed")
        }

        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw RuntimeException("eglInitialize failed")
        }

        // Choose config compatible with recording surface
        val configAttribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGLExt.EGL_RECORDABLE_ANDROID, 1,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
        val config = configs[0] ?: throw RuntimeException("No suitable EGL config found")

        // Create context sharing textures with the GLSurfaceView context
        val contextAttribs = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        eglContext = EGL14.eglCreateContext(eglDisplay, config, sharedContext, contextAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw RuntimeException("eglCreateContext failed")
        }

        // Create window surface from the encoder's input Surface
        val surfaceAttribs = intArrayOf(EGL14.EGL_NONE)
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, config, inputSurface!!, surfaceAttribs, 0
        )
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw RuntimeException("eglCreateWindowSurface failed")
        }

        // Set presentation timestamp source
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, 0)
    }

    private fun releaseEGL() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
            }
            // Don't terminate display — it's shared with GLSurfaceView
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    // -----------------------------------------------------------------------
    // Encoder drain loop — same pattern as VideoEncoderModule
    // -----------------------------------------------------------------------

    private fun drainEncoder(endOfStream: Boolean) {
        val c = codec ?: return
        val m = muxer ?: return

        while (true) {
            val index = c.dequeueOutputBuffer(bufferInfo, if (endOfStream) TIMEOUT_US else 0L)
            when {
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    trackIndex = m.addTrack(c.outputFormat)
                    m.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = c.getOutputBuffer(index)
                    if (buffer != null && bufferInfo.size > 0 && muxerStarted &&
                        (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0
                    ) {
                        buffer.position(bufferInfo.offset)
                        buffer.limit(bufferInfo.offset + bufferInfo.size)
                        m.writeSampleData(trackIndex, buffer, bufferInfo)
                    }
                    c.releaseOutputBuffer(index, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return
                }
                else -> {
                    if (!endOfStream) return
                }
            }
        }
    }
}

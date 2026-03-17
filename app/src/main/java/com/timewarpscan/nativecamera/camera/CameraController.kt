package com.timewarpscan.nativecamera.camera

import android.graphics.SurfaceTexture
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner

/**
 * Wraps CameraX to bind the front camera preview to a SurfaceTexture
 * provided by the OpenGL renderer.
 *
 * Usage:
 *   1. Renderer creates an OES texture + SurfaceTexture in onSurfaceCreated
 *   2. Renderer calls [startCamera] with that SurfaceTexture
 *   3. CameraX pushes frames into the SurfaceTexture → OES texture
 *   4. Renderer calls surfaceTexture.updateTexImage() each frame
 */
class CameraController {

    companion object {
        private const val TAG = "CameraController"
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var surfaceTexture: SurfaceTexture? = null

    /**
     * Bind the front camera preview to the given [surfaceTexture].
     *
     * @param lifecycleOwner activity or fragment for CameraX lifecycle binding
     * @param surfaceTexture the SurfaceTexture backed by the OES texture in GL
     * @param previewWidth desired preview width (camera will pick closest match)
     * @param previewHeight desired preview height
     */
    fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceTexture: SurfaceTexture,
        previewWidth: Int,
        previewHeight: Int
    ) {
        this.surfaceTexture = surfaceTexture

        val context = when (lifecycleOwner) {
            is android.app.Activity -> lifecycleOwner
            is android.content.ContextWrapper -> lifecycleOwner.baseContext
            else -> throw IllegalArgumentException("LifecycleOwner must provide a Context")
        }

        val future = ProcessCameraProvider.getInstance(context as android.content.Context)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider

            val resolutionSelector = ResolutionSelector.Builder()
                .setResolutionStrategy(
                    ResolutionStrategy(
                        Size(previewWidth, previewHeight),
                        ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                    )
                )
                .build()

            val preview = Preview.Builder()
                .setResolutionSelector(resolutionSelector)
                .build()

            // Feed frames into our SurfaceTexture
            preview.setSurfaceProvider { request: SurfaceRequest ->
                val resolution = request.resolution
                Log.d(TAG, "Camera resolution: ${resolution.width}x${resolution.height}")

                // Set default buffer size to match camera output
                surfaceTexture.setDefaultBufferSize(resolution.width, resolution.height)

                val surface = android.view.Surface(surfaceTexture)
                request.provideSurface(surface, ContextCompat.getMainExecutor(context)) { result ->
                    surface.release()
                    Log.d(TAG, "Surface released, result code: ${result.resultCode}")
                }
            }

            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_FRONT)
                .build()

            try {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                Log.d(TAG, "Camera bound successfully (front camera)")
            } catch (e: Exception) {
                Log.e(TAG, "Camera bind failed", e)
            }
        }, ContextCompat.getMainExecutor(context as android.content.Context))
    }

    /** Unbind all use cases and release the camera. */
    fun stopCamera() {
        cameraProvider?.unbindAll()
        cameraProvider = null
        surfaceTexture = null
    }
}

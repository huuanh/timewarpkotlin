package com.timewarpscan.nativecamera.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility functions for saving Bitmaps to the device gallery.
 *
 * Uses MediaStore API (scoped storage) — works on API 29+ without
 * WRITE_EXTERNAL_STORAGE permission. On API 26-28 the manifest
 * declares the permission with maxSdkVersion=28.
 */
object BitmapUtils {

    private const val TAG = "BitmapUtils"
    private const val JPEG_QUALITY = 95
    private const val SUBFOLDER = "TimeWarpScan"

    /**
     * Save a Bitmap to the Pictures gallery.
     *
     * Runs on [Dispatchers.IO] — call from a coroutine scope.
     *
     * @param context application or activity context
     * @param bitmap the image to save (not recycled by this function)
     * @param displayName optional filename (without extension). Defaults to timestamp.
     * @return the content URI of the saved image, or null on failure
     */
    suspend fun saveToGallery(
        context: Context,
        bitmap: Bitmap,
        displayName: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = displayName ?: generateTimestampName()
            val contentValues = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                Log.e(TAG, "MediaStore insert returned null URI")
                return@withContext null
            }

            resolver.openOutputStream(uri)?.use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            } ?: run {
                Log.e(TAG, "Failed to open output stream for $uri")
                return@withContext null
            }

            // Publish the image (make it visible in gallery)
            contentValues.clear()
            contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            Log.d(TAG, "Image saved: $uri ($name.jpg)")
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            null
        }
    }

    /**
     * Save a video file to the Movies gallery.
     *
     * @param context application or activity context
     * @param videoPath absolute path to the MP4 file
     * @return the content URI of the saved video, or null on failure
     */
    suspend fun saveVideoToGallery(
        context: Context,
        videoPath: String,
        displayName: String? = null
    ): String? = withContext(Dispatchers.IO) {
        try {
            val name = displayName ?: generateTimestampName()
            val contentValues = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, "$name.mp4")
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/$SUBFOLDER"
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            if (uri == null) {
                Log.e(TAG, "MediaStore insert returned null URI for video")
                return@withContext null
            }

            // Copy video file to MediaStore URI
            val inputFile = java.io.File(videoPath)
            resolver.openOutputStream(uri)?.use { outStream ->
                inputFile.inputStream().use { inStream ->
                    inStream.copyTo(outStream)
                }
            }

            // Publish
            contentValues.clear()
            contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, contentValues, null, null)

            // Clean up temp file
            inputFile.delete()

            Log.d(TAG, "Video saved: $uri ($name.mp4)")
            uri.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save video", e)
            null
        }
    }

    private fun generateTimestampName(): String {
        val sdf = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return "TWS_${sdf.format(Date())}"
    }

    /**
     * Save a Bitmap to a temporary JPEG file in the app cache directory.
     * The caller is responsible for deleting the file when done.
     */
    suspend fun saveTempFile(context: Context, bitmap: Bitmap): java.io.File? = withContext(Dispatchers.IO) {
        try {
            val file = java.io.File(context.cacheDir, "preview_${System.currentTimeMillis()}.jpg")
            file.outputStream().use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, stream)
            }
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save temp file", e)
            null
        }
    }

    /**
     * Copy a JPEG file from the app cache into the Pictures gallery via MediaStore.
     * @return the content URI string of the saved image, or null on failure.
     */
    suspend fun saveFileToGallery(context: Context, file: java.io.File, displayName: String? = null): String? =
        withContext(Dispatchers.IO) {
            try {
                val name = displayName ?: generateTimestampName()
                val contentValues = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/$SUBFOLDER")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext null
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { inp -> inp.copyTo(out) }
                } ?: return@withContext null
                contentValues.clear()
                contentValues.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
                Log.d(TAG, "File saved to gallery: $uri ($name.jpg)")
                uri.toString()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save file to gallery", e)
                null
            }
        }
}

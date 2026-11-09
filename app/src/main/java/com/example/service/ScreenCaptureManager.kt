package com.example.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * Owns one MediaProjection capture session. A single VirtualDisplay is retained for the
 * lifetime of the permission grant; individual captures only acquire the latest frame.
 */
class ScreenCaptureManager(private val context: Context) {
    @Volatile private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val captureMutex = Mutex()

    @Volatile private var screenWidth = 1080
    @Volatile private var screenHeight = 2400
    @Volatile private var screenDensity = 420
    private var displayWidth = 0
    private var displayHeight = 0
    private var displayDensity = 0

    fun updateMetrics(width: Int, height: Int, density: Int) {
        if (width > 0 && height > 0 && density > 0) {
            screenWidth = width
            screenHeight = height
            screenDensity = density
        }
    }

    @Synchronized
    fun setMediaProjection(projection: MediaProjection) {
        if (mediaProjection === projection) return
        releaseCaptureResources()
        mediaProjection?.let { old ->
            try { projectionCallback?.let(old::unregisterCallback) } catch (_: Exception) { }
            try { old.stop() } catch (_: Exception) { }
        }

        mediaProjection = projection
        val callback = object : MediaProjection.Callback() {
            override fun onStop() {
                Log.i(TAG, "MediaProjection session stopped by system or user")
                synchronized(this@ScreenCaptureManager) {
                    releaseCaptureResources()
                    mediaProjection = null
                    projectionCallback = null
                }
            }
        }
        projectionCallback = callback
        projection.registerCallback(callback, mainHandler)
    }

    fun hasMediaProjection(): Boolean = mediaProjection != null

    private fun refreshDisplayMetrics() {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            updateMetrics(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
        } catch (e: Exception) {
            Log.w(TAG, "Could not refresh display metrics", e)
        }
    }

    @Synchronized
    private fun ensureVirtualDisplay(projection: MediaProjection): Boolean {
        if (virtualDisplay != null && imageReader != null) {
            if (displayWidth == screenWidth && displayHeight == screenHeight && displayDensity == screenDensity) {
                return true
            }
            // Keep the same VirtualDisplay for this MediaProjection grant. Resize it when the
            // device rotates rather than creating a second display in the same session.
            return try {
                val replacementReader = ImageReader.newInstance(
                    screenWidth, screenHeight, PixelFormat.RGBA_8888, 3
                )
                virtualDisplay?.resize(screenWidth, screenHeight, screenDensity)
                virtualDisplay?.setSurface(replacementReader.surface)
                val oldReader = imageReader
                imageReader = replacementReader
                displayWidth = screenWidth
                displayHeight = screenHeight
                displayDensity = screenDensity
                try { oldReader?.close() } catch (_: Exception) { }
                true
            } catch (e: Exception) {
                Log.e(TAG, "Unable to resize screen capture display", e)
                false
            }
        }
        return try {
            val reader = ImageReader.newInstance(
                screenWidth, screenHeight, PixelFormat.RGBA_8888, 3
            )
            val display = projection.createVirtualDisplay(
                "AskScreenCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                mainHandler
            )
            imageReader = reader
            virtualDisplay = display
            displayWidth = screenWidth
            displayHeight = screenHeight
            displayDensity = screenDensity
            true
        } catch (e: Exception) {
            Log.e(TAG, "Unable to create screen capture display", e)
            releaseCaptureResources()
            false
        }
    }

    suspend fun captureScreen(regionCrop: Rect? = null): Bitmap? = captureMutex.withLock {
        withContext(Dispatchers.Default) {
            refreshDisplayMetrics()
            val projection = mediaProjection ?: run {
                Log.e(TAG, "MediaProjection is null")
                return@withContext null
            }
            if (!ensureVirtualDisplay(projection)) return@withContext null
            val reader = imageReader ?: return@withContext null

            var capturedImage: Image? = null
            try {
                // Wait briefly for a fresh frame. The VirtualDisplay remains alive between calls.
                for (attempt in 0 until 20) {
                    capturedImage = reader.acquireLatestImage()
                    if (capturedImage != null) break
                    delay(40)
                }
                val image = capturedImage ?: run {
                    Log.w(TAG, "Timed out waiting for a screen frame")
                    return@withContext null
                }

                val plane = image.planes.firstOrNull() ?: return@withContext null
                val buffer: ByteBuffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val paddedWidth = screenWidth + (rowStride - pixelStride * screenWidth) / pixelStride
                val fullBitmap = Bitmap.createBitmap(
                    paddedWidth, screenHeight, Bitmap.Config.ARGB_8888
                )
                buffer.rewind()
                fullBitmap.copyPixelsFromBuffer(buffer)
                val cleanBitmap = Bitmap.createBitmap(fullBitmap, 0, 0, screenWidth, screenHeight)
                if (cleanBitmap !== fullBitmap) fullBitmap.recycle()

                val crop = regionCrop
                if (crop == null || crop.width() <= 20 || crop.height() <= 20) {
                    return@withContext cleanBitmap
                }

                val left = crop.left.coerceIn(0, screenWidth - 1)
                val top = crop.top.coerceIn(0, screenHeight - 1)
                val right = crop.right.coerceIn(left + 1, screenWidth)
                val bottom = crop.bottom.coerceIn(top + 1, screenHeight)
                if (right - left <= 10 || bottom - top <= 10) return@withContext cleanBitmap

                val cropped = Bitmap.createBitmap(cleanBitmap, left, top, right - left, bottom - top)
                cleanBitmap.recycle()
                cropped
            } catch (e: Exception) {
                Log.e(TAG, "Error capturing screen", e)
                null
            } finally {
                try { capturedImage?.close() } catch (_: Exception) { }
            }
        }
    }

    @Synchronized
    private fun releaseCaptureResources() {
        try { virtualDisplay?.release() } catch (_: Exception) { }
        virtualDisplay = null
        try { imageReader?.close() } catch (_: Exception) { }
        imageReader = null
        displayWidth = 0
        displayHeight = 0
        displayDensity = 0
    }

    @Synchronized
    fun stop() {
        val projection = mediaProjection
        val callback = projectionCallback
        mediaProjection = null
        projectionCallback = null
        releaseCaptureResources()
        try {
            if (projection != null) {
                if (callback != null) projection.unregisterCallback(callback)
                projection.stop()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping MediaProjection", e)
        }
    }

    private companion object {
        const val TAG = "ScreenCaptureManager"
    }
}

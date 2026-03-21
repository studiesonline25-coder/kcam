package com.virtucam.hooks

import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Caches RGBA_8888 output from VirtualRenderThread and elegantly overwrites real, physically-allocated
 * ImageReader YUV buffers via Data Overwriting. This prevents VirtualApp IPC drops ("GraphicBuffer is null")
 * and cleanly bypasses hardware usage flag validation errors.
 */
class FormatConverterBridge(
    val width: Int,
    val height: Int,
    val outputSurface: Surface? = null,
    val outputFormat: Int = android.graphics.ImageFormat.YUV_420_888
) {
    companion object {
        private const val TAG = "VirtuCam_Bridge"
    }

    private var imageReader: ImageReader? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var imageWriter: android.media.ImageWriter? = null
    
    val hasImageWriter: Boolean
        get() = imageWriter != null

    @Volatile
    private var cachedRgbaData: ByteArray? = null

    // This is the surface we hand to the VirtualRenderThread (it receives RGBA from OpenGL)
    val inputSurface: Surface?
        get() = imageReader?.surface

    init {
        try {
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            handlerThread = HandlerThread("VirtuCamRgbaCacheThread").apply { start() }
            handler = Handler(handlerThread!!.looper)
            
            if (outputSurface != null) {
                try {
                    imageWriter = android.media.ImageWriter.newInstance(outputSurface, 2)
                    Log.d(TAG, "FormatConverterBridge: ImageWriter connected to output surface")
                } catch (e: Throwable) {
                    Log.e(TAG, "FormatConverterBridge: Failed to connect ImageWriter", e)
                }
            }
            
            imageReader?.setOnImageAvailableListener({ reader ->
                val rgbaImage = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    val planes = rgbaImage.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val remaining = buffer.remaining()
                        
                        var data = cachedRgbaData
                        if (data == null || data.size != remaining) {
                            data = ByteArray(remaining)
                            cachedRgbaData = data
                        }
                        
                        buffer.position(0)
                        buffer.get(data)
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Cache loop error", e)
                } finally {
                    try {
                        rgbaImage.close()
                    } catch (e: Throwable) {}
                }
            }, handler)
            Log.d(TAG, "FormatConverterBridge (Cache Mode) started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FormatConverterBridge", e)
            release()
        }
    }

    // Reuse bulk allocation to prevent garbage collection stuttering during synchronously blocking overwrite
    private var yRowBytes: ByteArray? = null

    /**
     * Synchronously overwrites the physically-allocated target buffer with our cached YUV computations.
     * This avoids ImageWriter usage flags rejections natively.
     */
    fun overwriteImageWithLatestYuv(targetImage: Image) {
        val rgbaBytes = cachedRgbaData ?: return
        
        val targetPlanes = targetImage.planes
        if (targetPlanes.size < 3) return
        
        val yBuffer = targetPlanes[0].buffer
        val uBuffer = targetPlanes[1].buffer
        val vBuffer = targetPlanes[2].buffer
        
        val yRowStride = targetPlanes[0].rowStride
        val uRowStride = targetPlanes[1].rowStride
        val vRowStride = targetPlanes[2].rowStride
        val uvPixelStride = targetPlanes[1].pixelStride
        
        val rgbaRowStride = width * 4 // RGBA_8888 tightly packed from our ImageReader cache
        
        if (yRowBytes == null || yRowBytes!!.size < yRowStride) {
            yRowBytes = ByteArray(yRowStride)
        }
        val yRow = yRowBytes!!
        
        yBuffer.position(0)
        
        for (row in 0 until height) {
            // OpenGL source image is bottom-to-top, but YUV buffers are top-to-bottom.
            // We invert the row index here to fix the vertical flip.
            var rgbaOffset = (height - 1 - row) * rgbaRowStride
            if (rgbaOffset < 0 || rgbaOffset + width * 4 > rgbaBytes.size) break
            
            val isEvenRow = (row % 2 == 0)
            var uIndex = (row / 2) * uRowStride
            var vIndex = (row / 2) * vRowStride
            
            var yIndex = 0
            for (col in 0 until width) {
                val r = rgbaBytes[rgbaOffset].toInt() and 0xFF
                val g = rgbaBytes[rgbaOffset + 1].toInt() and 0xFF
                val b = rgbaBytes[rgbaOffset + 2].toInt() and 0xFF
                
                // integer math approximation (shifts and adds)
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yRow[yIndex++] = y.coerceIn(16, 235).toByte()
                
                if (isEvenRow && (col % 2 == 0)) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    
                    if (uIndex < uBuffer.capacity()) uBuffer.put(uIndex, u.coerceIn(16, 240).toByte())
                    if (vIndex < vBuffer.capacity()) vBuffer.put(vIndex, v.coerceIn(16, 240).toByte())
                    
                    uIndex += uvPixelStride
                    vIndex += uvPixelStride
                }
                
                rgbaOffset += 4
            }
            
            // Fast bulk copy of the heavy Y-plane row by row
            val pos = row * yRowStride
            if (pos < yBuffer.capacity()) {
                yBuffer.position(pos)
                yBuffer.put(yRow, 0, yIndex.coerceAtMost(yBuffer.remaining()))
            }
        }
    }

    /**
     * Overwrite a JPEG-format Image buffer with our spoofed content.
     */
    fun overwriteImageWithLatestJpeg(targetImage: Image) {
        val rgbaBytes = cachedRgbaData ?: return
        
        val planes = targetImage.planes
        if (planes.isEmpty()) return
            
            val jpegBuffer = planes[0].buffer
            if (!jpegBuffer.hasRemaining()) return
            
            val expectedSize = width * height * 4
            if (rgbaBytes.size < expectedSize) {
                throw IllegalStateException("FormatConverterBridge: rgbaBytes too small! expected $expectedSize, got ${rgbaBytes.size}")
            }
            
            // Create Bitmap from cached RGBA
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rgbaBuffer = ByteBuffer.wrap(rgbaBytes)
            bitmap.copyPixelsFromBuffer(rgbaBuffer)
            
            // Compress to JPEG
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()
            
            val jpegBytes = baos.toByteArray()
            
            // Write JPEG into the image buffer
            jpegBuffer.clear()
            val bytesToWrite = jpegBytes.size.coerceAtMost(jpegBuffer.capacity())
            jpegBuffer.put(jpegBytes, 0, bytesToWrite)
            
            // Do NOT flip. We want the camera framework to read up to its native size.
            // Appending our JPEG at the start of the buffer is enough; BitmapFactory stops at EOF.
            jpegBuffer.position(0)
            jpegBuffer.limit(jpegBuffer.capacity())
            
            Log.d(TAG, "FormatConverterBridge: Overwrote JPEG image (${jpegBytes.size} bytes)")
    }

    /**
     * Manually push the latest cached RGBA frame into the native ImageWriter.
     * Call this ONLY when the real camera signals a capture event to prevent native BufferQueue starvation and SIGSEGV block crashing.
     */
    fun pushLatestFrameToWriter() {
        if (imageWriter == null) return
        Thread {
            try {
                val outImage = imageWriter!!.dequeueInputImage()
                if (outImage != null) {
                    if (outImage.format == 256 || outputFormat == 256) {
                        overwriteImageWithLatestJpeg(outImage)
                    } else {
                        overwriteImageWithLatestYuv(outImage)
                    }
                    imageWriter!!.queueInputImage(outImage)
                }
            } catch (e: IllegalStateException) {
                // Expected backpressure behavior: No free buffers until app initiates CaptureRequest
            } catch (e: Exception) {
                Log.e(TAG, "FormatConverterBridge: ImageWriter push failed", e)
            }
        }.apply {
            name = "VirtuCam-WriterPush"
            start()
        }
    }

    fun release() {
        try {
            imageWriter?.close()
            imageWriter = null
            imageReader?.close()
            handlerThread?.quitSafely()
            imageReader = null
        } catch (t: Throwable) {
            Log.e(TAG, "Error during Bridge release", t)
        }
    }
}

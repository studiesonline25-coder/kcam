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
            var bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rgbaBuffer = ByteBuffer.wrap(rgbaBytes)
            bitmap.copyPixelsFromBuffer(rgbaBuffer)
            
            // Extract original JPEG bytes from buffer to detect Hardware EXIF Rotation
            val originalLimit = jpegBuffer.limit()
            jpegBuffer.position(0)
            val originalJpegBytes = ByteArray(originalLimit)
            jpegBuffer.get(originalJpegBytes)
            
            try {
                // Parse Hardware JPEG to read physical sensor orientation (usually 90 or 270 on phones, or 180 for upside-down)
                val exifInterface = android.media.ExifInterface(java.io.ByteArrayInputStream(originalJpegBytes))
                val orientation = exifInterface.getAttributeInt(
                    android.media.ExifInterface.TAG_ORIENTATION,
                    android.media.ExifInterface.ORIENTATION_NORMAL
                )
                
                val rotationDegrees = when (orientation) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                    else -> 0f
                }
                
                // Physically apply the rotation to our upright spoofed Bitmap
                // This guarantees our spoofed pixels exactly mimic the hardware sensor's rotated layout
                // before the OEM camera app processes and tags the final EXIF (e.g. CAM_ExifTool putting orientation=180).
                if (rotationDegrees != 0f) {
                    val matrix = android.graphics.Matrix()
                    matrix.postRotate(rotationDegrees)
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    bitmap.recycle()
                    bitmap = rotatedBitmap
                    Log.d(TAG, "FormatConverterBridge: Physically rotated spoofed Bitmap by $rotationDegrees degrees to mimic Hardware EXIF")
                }
            } catch (e: Exception) {
                Log.e(TAG, "FormatConverterBridge: Failed to parse/apply Original EXIF rotation", e)
            }
            
            // Compress to JPEG
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()
            
            val jpegBytes = baos.toByteArray()
            
            // Write Spoofed JPEG into the image buffer
            jpegBuffer.clear()
            val bytesToWrite = jpegBytes.size.coerceAtMost(jpegBuffer.capacity())
            jpegBuffer.put(jpegBytes, 0, bytesToWrite)
            
            // Do NOT flip. We want the camera framework to read up to its native size.
            // Appending our naked JPEG without EXIF APP1 blocks is enough because modern 
            // camera zipping modules (like Xiaomi CAM_ParallelDataZipper) parse and overwrite EXIF 
            // based on CaptureRequest parameters downstream automatically. Let them do their job.
            jpegBuffer.position(0)
            jpegBuffer.limit(bytesToWrite)
            
            Log.d(TAG, "FormatConverterBridge: Overwrote JPEG image (${jpegBytes.size} bytes) with mimicked physical rotation")
    }

    /**
     * Manually push the latest cached RGBA frame into the native ImageWriter.
     * Call this ONLY when the real camera signals a capture event to prevent native BufferQueue starvation and SIGSEGV block crashing.
     */
    fun pushLatestFrameToWriter(timestamp: Long = 0L) {
        if (imageWriter == null) return
        try {
            val outImage = imageWriter!!.dequeueInputImage()
            if (outImage != null) {
                var success = false
                try {
                    if (timestamp > 0) {
                        outImage.timestamp = timestamp
                    }
                    if (outImage.format == 256 || outputFormat == 256) {
                        overwriteImageWithLatestJpeg(outImage)
                    } else {
                        overwriteImageWithLatestYuv(outImage)
                    }
                    imageWriter!!.queueInputImage(outImage)
                    success = true
                } finally {
                    if (!success) {
                        try {
                            outImage.close()
                        } catch (e: Exception) {}
                    }
                }
            }
        } catch (e: IllegalStateException) {
            // Expected backpressure behavior: No free buffers until app initiates CaptureRequest
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: ImageWriter push failed", e)
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



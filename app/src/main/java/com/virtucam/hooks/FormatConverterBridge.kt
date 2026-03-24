package com.virtucam.hooks

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
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
                val rgbaImage = try { reader.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
                try {
                    val planes = rgbaImage.planes
                    if (planes.isNotEmpty()) {
                        val buffer = planes[0].buffer
                        val rowStride = planes[0].rowStride
                        val pixelStride = planes[0].pixelStride
                        
                        val expectedSize = width * height * 4
                        var data = cachedRgbaData
                        if (data == null || data.size != expectedSize) {
                            data = ByteArray(expectedSize)
                            cachedRgbaData = data
                        }
                        
                        // Copy row-by-row to handle stride correctly
                        buffer.position(0)
                        if (rowStride == width * 4 && pixelStride == 4) {
                            // Fast path: no padding
                            buffer.get(data)
                        } else {
                            // Slow path: copy row by row ignoring padding
                            for (row in 0 until height) {
                                buffer.position(row * rowStride)
                                buffer.get(data, row * width * 4, width * 4)
                            }
                        }
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Cache loop error: ${e.message}")
                } finally {
                    try {
                        rgbaImage.close()
                    } catch (e: Exception) {}
                }
            }, handler)
            Log.d(TAG, "FormatConverterBridge (Cache Mode) started successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FormatConverterBridge", e)
            release()
        }
    }

    // Reusable buffers for performance
    private var yRowBytes: ByteArray? = null
    private var uvRowBytes: ByteArray? = null

    /**
     * Synchronously overwrites the physically-allocated target buffer with our cached YUV computations.
     * Optimized for speed AND robustness to prevent Xiaomi Parallel Service timeouts/crashes.
     */
    fun overwriteImageWithLatestYuv(targetImage: Image) {
        val rgbaBytes = cachedRgbaData ?: return
        
        try {
            val targetPlanes = targetImage.planes
            if (targetPlanes.size < 2) return
            
            val tW = targetImage.width
            val tH = targetImage.height
            
            // Detect rotation: If dimensions are swapped (Portrait source -> Landscape buffer), we rotate.
            val rotationNeeded = if (tW == height && tH == width) 90 else 0
            
            val yBuffer = targetPlanes[0].buffer
            val yRowStride = targetPlanes[0].rowStride
            
            val uPlane = targetPlanes[1]
            val vPlane = if (targetPlanes.size > 2) targetPlanes[2] else null
            val uBuffer = uPlane.buffer
            val vBuffer = vPlane?.buffer ?: uBuffer
            
            val uRowStride = uPlane.rowStride
            val vRowStride = vPlane?.rowStride ?: uRowStride
            val uPixelStride = uPlane.pixelStride
            val vPixelStride = vPlane?.pixelStride ?: uPixelStride
            
            val isInterleaved = (uPixelStride == 2)
            val vOffsetInU = if (isInterleaved && vPlane == null) 1 else 0
            
            if (yRowBytes == null || yRowBytes!!.size < yRowStride) {
                yRowBytes = ByteArray(yRowStride)
            }
            if (uvRowBytes == null || uvRowBytes!!.size < uRowStride) {
                uvRowBytes = ByteArray(uRowStride)
            }
            val yRowArr = yRowBytes!!
            val uvRowArr = uvRowBytes!!
            
            val srcStride = width * 4
            val srcH = height
            val srcW = width

            for (row in 0 until tH) {
                val isEvenRow = (row % 2 == 0)
                val uvRow = row / 2
                val populateUV = isEvenRow 
                
                var yIdx = 0
                val colToSrcRowStep = srcStride 
                val baseSrcCol: Int
                val baseSrcRow: Int
                
                if (rotationNeeded == 90) {
                    // srcRow = col, srcCol = (srcH - 1 - row)
                    baseSrcRow = 0
                    baseSrcCol = srcH - 1 - row
                } else {
                    // srcRow = srcH - 1 - row, srcCol = col
                    baseSrcRow = srcH - 1 - row
                    baseSrcCol = 0
                }
                
                // Pre-calculate the starting offset for this row
                var currentRgbaOffset = (baseSrcRow * srcStride) + (baseSrcCol * 4)

                for (col in 0 until tW) {
                    if (currentRgbaOffset < 0 || currentRgbaOffset + 3 >= rgbaBytes.size) {
                        yRowArr[yIdx++] = 16 
                        if (rotationNeeded == 90) currentRgbaOffset += colToSrcRowStep else currentRgbaOffset += 4
                        continue
                    }
                    
                    val r = rgbaBytes[currentRgbaOffset].toInt() and 0xFF
                    val g = rgbaBytes[currentRgbaOffset + 1].toInt() and 0xFF
                    val b = rgbaBytes[currentRgbaOffset + 2].toInt() and 0xFF
                    
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    if (yIdx < yRowArr.size) yRowArr[yIdx++] = y.coerceIn(16, 235).toByte()
                    
                    if (populateUV && (col % 2 == 0)) {
                        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                        
                        val uvCol = col / 2
                        if (isInterleaved) {
                            val uIdx = uvCol * uPixelStride
                            val vIdx = uIdx + vOffsetInU
                            if (vIdx < uvRowArr.size) {
                                uvRowArr[uIdx] = u.coerceIn(16, 240).toByte()
                                uvRowArr[vIdx] = v.coerceIn(16, 240).toByte()
                            }
                        } else {
                            val uIdxTotal = uvRow * uRowStride + uvCol
                            if (uIdxTotal < uBuffer.capacity()) uBuffer.put(uIdxTotal, u.coerceIn(16, 240).toByte())
                            if (vPlane != null) {
                                val vIdxTotal = uvRow * vRowStride + uvCol
                                if (vIdxTotal < vBuffer.capacity()) vBuffer.put(vIdxTotal, v.coerceIn(16, 240).toByte())
                            }
                        }
                    }
                    
                    if (rotationNeeded == 90) {
                        currentRgbaOffset += colToSrcRowStep // Moving col in target moves srcRow in source
                    } else {
                        currentRgbaOffset += 4 // Moving col in target moves srcCol in source
                    }
                }
                
                // Final Bulk Writes (Sanity checked)
                val yPos = row * yRowStride
                if (yPos + yIdx <= yBuffer.capacity()) {
                    yBuffer.position(yPos)
                    yBuffer.put(yRowArr, 0, yIdx)
                }
                
                if (populateUV && isInterleaved) {
                    val uvPos = uvRow * uRowStride
                    val uvLen = uRowStride.coerceAtMost(uvRowArr.size)
                    if (uvPos + uvLen <= uBuffer.capacity()) {
                        uBuffer.position(uvPos)
                        uBuffer.put(uvRowArr, 0, uvLen)
                    }
                }
            }
            Log.d(TAG, "FormatConverterBridge: Successfully overwrote hardware YUV buffer (${tW}x${tH})")
        } catch (e: Throwable) {
            Log.e(TAG, "FormatConverterBridge: CRITICAL ERROR during YUV overwrite", e)
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
            if (!jpegBuffer.hasRemaining()) {
                 Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Target JPEG buffer has no remaining capacity!")
                 return
            }
            
            val tW = targetImage.width
            val tH = targetImage.height
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Overwriting JPEG: Target=${tW}x${tH}, Bridge=${width}x${height}, capacity=${jpegBuffer.capacity()}")
            
            val expectedSize = width * height * 4
            if (rgbaBytes.size < expectedSize) {
                Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: rgbaBytes too small! expected $expectedSize, got ${rgbaBytes.size}")
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
                // Parse Hardware JPEG to read the physical sensor orientation (usually 90 or 270 on phones)
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
                
                // Live preview buffers strictly expect Upright frames, which VirtualRenderThread now provides natively.
                // However, the OEM Camera App intercepts this target spoofed `ImageWriter` buffer and natively slaps its 
                // Hardware EXIF tags onto it. For instance, if the camera physically mounts sideways, it tags `Orientation=90`.
                // If we pass an Upright spoofed frame to the OEM, the Gallery will read `Orientation=90` and blindly double-rotate 
                // our frame SIDEWAYS. 
                // To prevent this, we mathematically PRE-ROTATE our Bitmap in the Exact Opposite Direction (-rotationDegrees) 
                // perfectly mimicking the Native Hardware Sensor's sideways layout, ensuring the OEM downstream rotation 
                // stands our captured photo perfectly back Upright!
                if (rotationDegrees != 0f) {
                    val matrix = android.graphics.Matrix()
                    // INVERT the rotation direction (CCW)
                    matrix.postRotate(-rotationDegrees)
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                    bitmap.recycle()
                    bitmap = rotatedBitmap
                    Log.d(TAG, "FormatConverterBridge: Physically pre-rotated spoofed Bitmap by -$rotationDegrees degrees to perfectly offset Downstream OEM Hardware EXIF appending")
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
            
            Log.d(TAG, "FormatConverterBridge: Overwrote JPEG image (${jpegBytes.size} bytes) Target=${tW}x${tH}")
    }

    fun connectToImageReader(imageReader: ImageReader) {
        try {
            this.imageReader = imageReader
            this.imageWriter = ImageWriter.newInstance(imageReader.surface, 2, ImageFormat.YUV_420_888)
            Log.i(TAG, "FormatConverterBridge: Connected to ImageReader ${width}x${height} for format ${imageReader.imageFormat}")
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: Failed to connect to ImageReader", e)
        }
    }

    /**
     * Pushes the latest cached RGBA frame into the target ImageReader using the ImageWriter.
     * Synchronized via the Capture Session's sensor timestamp.
     */
    fun pushLatestFrameToWriter(timestamp: Long) {
        val writer = imageWriter ?: return
        
        try {
            val outImage = writer.dequeueInputImage() ?: return
            
            var success = false
            try {
                // Critical: Xiaomi ParallelDataZipper matches frames by SENSOR_TIMESTAMP
                outImage.timestamp = timestamp
                
                val fmt = outImage.format
                if (fmt == 256) { // JPEG
                    overwriteImageWithLatestJpeg(outImage)
                } else { // YUV_420_888 (35) or similar
                    overwriteImageWithLatestYuv(outImage)
                }
                
                writer.queueInputImage(outImage)
                success = true
                Log.d(TAG, "FormatConverterBridge: Successfully pushed captured frame (${width}x${height}) to ImageWriter (Format: $fmt, TS: $timestamp)")
            } finally {
                if (!success) {
                    try { outImage.close() } catch (e: Exception) {}
                }
            }
        } catch (e: IllegalStateException) {
            // Normal backpressure: No free buffers to dequeue
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: CRITICAL ERROR during capture push", e)
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



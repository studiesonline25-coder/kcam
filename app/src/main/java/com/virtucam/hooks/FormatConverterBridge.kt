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
import java.io.File
import java.io.FileOutputStream
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
    
    private var debugCounter = 0

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

    private fun checkDataIntegrity(data: ByteArray?): Boolean {
        if (data == null) return false
        // Quick check: if the first 1KB is all zeros, the buffer is likely stale/black/green
        val checkSize = 1024.coerceAtMost(data.size)
        for (i in 0 until checkSize) {
            if (data[i] != 0.toByte()) return true
        }
        return false
    }

    /**
     * Synchronously overwrites the target image with our RGBA data converted to YUV.
     * Uses absolute indexing for maximum robustness against stride and interleave variations.
     */
    /**
     * Synchronously overwrites the target image with our RGBA data converted to YUV.
     * Engineered for Xiaomi MiAlgoEngine (Parallel Service) compatibility.
     * Strict NV21 (V before U) layout for 2-plane semi-planar buffers.
     */
    fun overwriteImageWithLatestYuv(targetImage: Image, timestamp: Long) {
        val rgbaBytes = cachedRgbaData
        if (rgbaBytes == null || !checkDataIntegrity(rgbaBytes)) {
            Log.e(TAG, "FormatConverterBridge: Cannot overwrite YUV - source data is missing or blank (all zeros)!")
            return
        }
        
        try {
            val w = targetImage.width
            val h = targetImage.height
            val planes = targetImage.planes
            val format = targetImage.format
            
            Log.d(TAG, "DIAGNOSTIC_VIRTUCAM: Intercepting YUV Capture. Target=${w}x${h}, Format=$format, Bridge=${width}x${height}")
            
            // Detect rotation: Source is always width x height. 
            val rotation = if (w == height && h == width) 90 else 0

            // Y Plane (Plane 0)
            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            
            val srcW = width
            val srcH = height
            val srcStrideArr = srcW * 4

            // 1. Process Y Plane
            for (row in 0 until h) {
                val rowPos = row * yRowStride
                for (col in 0 until w) {
                    val srcRow = if (rotation == 90) col else row
                    val srcCol = if (rotation == 90) (h - 1 - row) else col
                    
                    val rgbaOff = (srcRow * srcStrideArr) + (srcCol * 4)
                    if (rgbaOff >= 0 && rgbaOff + 3 < rgbaBytes.size) {
                        val r = rgbaBytes[rgbaOff].toInt() and 0xFF
                        val g = rgbaBytes[rgbaOff+1].toInt() and 0xFF
                        val b = rgbaBytes[rgbaOff+2].toInt() and 0xFF
                        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                        val pos = rowPos + (col * yPixStride)
                        if (pos < yBuffer.capacity()) {
                            yBuffer.put(pos, y.coerceIn(16, 235).toByte())
                        }
                    }
                }
            }

            // 2. Process Chroma Planes (U/V)
            // Xiaomi NativeTool expects NV21 (0x11) where V precedes U in memory
            val isYV12 = (format == 842094169)
            val isNv21 = (format == 0x11)
            val isSemiPlanar = (planes.size == 2)

            if (isSemiPlanar) {
                // [XIAOMI FIX] 2-plane semi-planar layout
                val uvPlane = planes[1]
                val uvBuffer = uvPlane.buffer
                val pStride = uvPlane.rowStride
                val pixStride = uvPlane.pixelStride // Should be 2
                
                val tW = w / 2
                val tH = h / 2
                
                for (row in 0 until tH) {
                    val rowPos = row * pStride
                    for (col in 0 until tW) {
                        val srcRowForUV = (if (rotation == 90) col else row) * 2
                        val srcColForUV = (if (rotation == 90) (tH - 1 - row) else col) * 2
                        
                        val rgbaOff = (srcRowForUV * srcStrideArr) + (srcColForUV * 4)
                        if (rgbaOff >= 0 && rgbaOff + 3 < rgbaBytes.size) {
                            val r = rgbaBytes[rgbaOff].toInt() and 0xFF
                            val g = rgbaBytes[rgbaOff+1].toInt() and 0xFF
                            val b = rgbaBytes[rgbaOff+2].toInt() and 0xFF
                            
                            val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                            val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                            
                            // NV21 layout: V is at 0, U is at 1
                            // NV12 layout: U is at 0, V is at 1
                            val firstByte = if (isNv21) v else u
                            val secondByte = if (isNv21) u else v
                            
                            val pos = rowPos + (col * pixStride)
                            if (pos + 1 < uvBuffer.capacity()) {
                                uvBuffer.put(pos, firstByte.coerceIn(16, 240).toByte())
                                uvBuffer.put(pos + 1, secondByte.coerceIn(16, 240).toByte())
                            }
                        }
                    }
                }
            } else {
                // Standard 3-plane or fallback
                val uPlane = if (isYV12) (if (planes.size > 2) planes[2] else null) else (if (planes.size > 1) planes[1] else null)
                val vPlane = if (isYV12) (if (planes.size > 1) planes[1] else null) else (if (planes.size > 2) planes[2] else null)

                fun writeChroma(plane: Image.Plane?, isU: Boolean) {
                    if (plane == null) return
                    val buffer = plane.buffer
                    val pStride = plane.rowStride
                    val pixStride = plane.pixelStride
                    val tW = w / 2
                    val tH = h / 2
                    
                    for (row in 0 until tH) {
                        val rowPos = row * pStride
                        for (col in 0 until tW) {
                            val srcRowForUV = (if (rotation == 90) col else row) * 2
                            val srcColForUV = (if (rotation == 90) (tH - 1 - row) else col) * 2
                            
                            val rgbaOff = (srcRowForUV * srcStrideArr) + (srcColForUV * 4)
                            if (rgbaOff >= 0 && rgbaOff + 3 < rgbaBytes.size) {
                                val r = rgbaBytes[rgbaOff].toInt() and 0xFF
                                val g = rgbaBytes[rgbaOff+1].toInt() and 0xFF
                                val b = rgbaBytes[rgbaOff+2].toInt() and 0xFF
                                
                                val chroma = if (isU) {
                                    ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                } else {
                                    ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                }
                                
                                val pos = rowPos + (col * pixStride)
                                if (pos < buffer.capacity()) {
                                    buffer.put(pos, chroma.coerceIn(16, 240).toByte())
                                }
                            }
                        }
                    }
                }
                writeChroma(uPlane, true)
                writeChroma(vPlane, false)
            }
            
            // diagnostic dump (YUV is hard to view directly, so we just log metadata or dump raw bytes)
            if (debugCounter % 5 == 0) {
                dumpRawBuffer(targetImage, "capture_yuv_${System.currentTimeMillis()}.raw")
            }
            debugCounter++
            
            Log.d(TAG, "FormatConverterBridge: Captured frame (${w}x${h}) rewritten successfully. Format=$format")
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: Error overwriting capture buffer", e)
        }
    }

    /**
     * Overwrite a JPEG-format Image buffer with our spoofed content.
     */
    fun overwriteImageWithLatestJpeg(targetImage: Image) {
        val rgbaBytes = cachedRgbaData
        if (rgbaBytes == null || !checkDataIntegrity(rgbaBytes)) {
            Log.e(TAG, "FormatConverterBridge: Cannot overwrite JPEG - source data is missing or blank (all zeros)!")
            return
        }
        
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
            
            // NEW: Late-Stage Storage Interception Bypass!
            // Store the perfectly spoofed JPEG in a global variable. When Xiaomi completes its Native 
            // AlgoEngine processing, it will attempt to save its (likely corrupted) image to the disk.
            // Our storage hook will decisively swap it out with this payload at the very last millisecond!
            CameraHook.latestVirtualJpeg = jpegBytes
            Log.d(TAG, "FormatConverterBridge: Captured and stored Virtual JPEG (${jpegBytes.size} bytes) for late-stage interception")
            
            // Write Spoofed JPEG into the image buffer (just in case the pipeline uses it directly)
            jpegBuffer.clear()
            val bytesToWrite = jpegBytes.size.coerceAtMost(jpegBuffer.capacity())
            jpegBuffer.put(jpegBytes, 0, bytesToWrite)
            
            // Do NOT flip. We want the camera framework to read up to its native size.
            // Appending our naked JPEG without EXIF APP1 blocks is enough because modern 
            // camera zipping modules (like Xiaomi CAM_ParallelDataZipper) parse and overwrite EXIF 
            // based on CaptureRequest parameters downstream automatically. Let them do their job.
            jpegBuffer.position(0)
            jpegBuffer.limit(bytesToWrite)
            
            // DIAGNOSTIC DUMP: Save the spoofed JPEG to SD card to see exactly what Veriff sees
            saveDebugImage(jpegBytes, "capture_jpeg_${System.currentTimeMillis()}.jpg")
            
            Log.d(TAG, "FormatConverterBridge: Overwrote JPEG image (${jpegBytes.size} bytes) Target=${tW}x${tH}")
    }

    private fun saveDebugImage(data: ByteArray, filename: String) {
        try {
            val dir = File("/sdcard/Download/virtucam_debug")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { it.write(data) }
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Saved debug image to ${file.absolutePath} (${data.size} bytes)")
        } catch (e: Exception) {
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Failed to save debug image", e)
        }
    }
    
    private fun dumpRawBuffer(image: Image, filename: String) {
        try {
            val dir = File("/sdcard/Download/virtucam_debug")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, filename)
            FileOutputStream(file).use { out ->
                for (plane in image.planes) {
                    val buf = plane.buffer
                    val prevPos = buf.position()
                    buf.position(0)
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    out.write(bytes)
                    buf.position(prevPos)
                }
            }
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Saved raw buffer to ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Failed to save raw buffer", e)
        }
    }

    fun connectToImageReader(imageReader: ImageReader) {
        try {
            this.imageReader = imageReader
            this.imageWriter = ImageWriter.newInstance(imageReader.surface, 5, ImageFormat.YUV_420_888)
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
                } else {
                    overwriteImageWithLatestYuv(outImage, timestamp)
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



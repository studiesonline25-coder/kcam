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
    val outputFormat: Int = android.graphics.ImageFormat.YUV_420_888,
    val sensorOrientation: Int = 270, // Default for most front cameras
    val rotationOffset: Int = 0,
    val isColorSwapped: Boolean = false
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
            // USAGE_HW_CAMERA_WRITE (0x20000) helps masquerade as a valid hardware source for MIVI
            val usage = 0x20000L or 0x3L // HW_CAMERA_WRITE | CPU_READ_OFTEN
            imageReader = try {
                val newInstanceMethod = ImageReader::class.java.getMethod("newInstance", Int::class.java, Int::class.java, Int::class.java, Int::class.java, Long::class.javaPrimitiveType)
                newInstanceMethod.invoke(null, width, height, PixelFormat.RGBA_8888, 2, usage) as ImageReader
            } catch (e: Exception) {
                ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            }
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
     * Extracts RGBA cache, converts to Bitmap, compresses to JPEG, and stores in CameraHook.
     * Required for URI Direct Write bypass because native camera often uses YUV buffers 
     * but writes JPEGs to the sandboxed disk later.
     */
    private fun generateAndStoreSpoofedJpeg(): ByteArray? {
        val rgbaBytes = cachedRgbaData
        if (rgbaBytes == null || !checkDataIntegrity(rgbaBytes)) return null
        
        val expectedSize = width * height * 4
        if (rgbaBytes.size < expectedSize) return null
        
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val rgbaBuffer = ByteBuffer.wrap(rgbaBytes)
            bitmap.copyPixelsFromBuffer(rgbaBuffer)
            
            val baos = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
            bitmap.recycle()
            
            var jpegBytes = baos.toByteArray()
            
            // [WYSIWYG EXIF INJECTION] 
            // Bitmap.compress does not write EXIF tags. Without tags, the Gallery displays the file 
            // sideways since we mathematically rotated it array-wise to use CENTER_CROP perfectly.
            try {
                val context = android.app.AndroidAppHelper.currentApplication()
                if (context != null) {
                    val tempFile = java.io.File(context.cacheDir, "vc_exif_inject_${System.currentTimeMillis()}.jpg")
                    tempFile.writeBytes(jpegBytes)
                    val exif = android.media.ExifInterface(tempFile.absolutePath)
                    exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, "1") // Normal (0 deg)
                    exif.saveAttributes()
                    jpegBytes = tempFile.readBytes()
                    tempFile.delete()
                    Log.d("DIAGNOSTIC_VIRTUCAM", "FormatConverterBridge: Embedded EXIF Orientation=1 into JPEG payload")
                }
            } catch (e: Throwable) {
                Log.e("DIAGNOSTIC_VIRTUCAM", "FormatConverterBridge: Failed EXIF injection", e)
            }
            
            val area = width * height
            synchronized(CameraHook) {
                if (area >= CameraHook.latestVirtualJpegArea) {
                    CameraHook.latestVirtualJpeg = jpegBytes
                    CameraHook.latestVirtualJpegArea = area
                    Log.d(TAG, "FormatConverterBridge: Stored Virtual JPEG (${jpegBytes.size} bytes) for late-stage interception (Area: $area)")
                } else {
                    Log.d(TAG, "FormatConverterBridge: Discarding ${width}x${height} JPEG, a larger capture already won the race.")
                }
            }
            
            return jpegBytes
        } catch (e: Exception) {
            Log.e(TAG, "Failed to generate spoofed JPEG", e)
            return null
        }
    }

    /**
     * Synchronously overwrites the target image with our RGBA data converted to YUV.
     * Engineered for Xiaomi MiAlgoEngine (Parallel Service) compatibility.
     * Strict NV21 (V before U) layout for 2-plane semi-planar buffers.
     */
    private var lastJpegGenTimeMs = 0L

    fun overwriteImageWithLatestYuv(targetImage: Image, timestamp: Long) {
        // [PHASE 15 STABILITY] Generate JPEG payload for late-stage file swap.
        // Throttled to once per second to avoid burning CPU on every preview frame.
        val now = System.currentTimeMillis()
        if (now - lastJpegGenTimeMs > 1000) {
            lastJpegGenTimeMs = now
            generateAndStoreSpoofedJpeg()
        }
        
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

            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            
            Log.d(TAG, "DIAGNOSTIC_VIRTUCAM: Intercepting YUV Capture. Target=${w}x${h}, Format=$format, Bridge=${width}x${height}, SensorRot=$sensorOrientation")
            
            // --- SMART-FIT PIXEL MAPPING ---
            // Goal: Fill Target(w, h) from Source(width, height) without stretching.
            // Includes Sensor Rotation (Hardware Mimicry) and Center-Cropping.
            
            val srcW = width.toFloat()
            val srcH = height.toFloat()
        
            val context = android.app.AndroidAppHelper.currentApplication()
            var zoom = CameraHook.zoomFactor
            var comp = CameraHook.compensationFactor
            var userRot = CameraHook.rotation
            
            // [Multi-Process Sync] MiAlgoEngine often runs in a background process.
            // Force reload from disk to stay in sync with UI sliders.
            if (context != null) {
                try {
                    val config = com.virtucam.data.VirtuCamConfig.getInstance(context)
                    zoom = config.zoomFactor
                    comp = config.compensationFactor
                    userRot = config.rotation
                } catch (_: Exception) {}
            }

            // --- UNIFIED ROTATION SYNC ---
            // Force bridge to match the confirmed upright rotation from Build 214.5.
            val appRotation = CameraHook.lastRequestedOrientation.let { if (it == -1) 0 else it }
            val totalRotation = (sensorOrientation + userRot + rotationOffset + appRotation) % 360

            val tgtW = w.toFloat()
            val tgtH = h.toFloat()

            // Calculate base source dimensions
            val baseSrcW = if (totalRotation % 180 == 0) width.toFloat() else height.toFloat()
            val baseSrcH = if (totalRotation % 180 == 0) height.toFloat() else width.toFloat()
            
            Log.d(TAG, "DIAGNOSTIC_VIRTUCAM: Intercepting YUV Capture. Target=${w}x${h}, Bridge=${width}x${height}, SensorRot=$sensorOrientation, Zoom=$zoom, Comp=$comp, Rot=$totalRotation")

            // Apply Compensation (Stretch) to the logic width to squash width vs height
            // Multiplier > 1.0 makes content 'thinner' (Vertical Stretch relative to screen).
            val logicSrcW = baseSrcW * comp
            val logicSrcH = baseSrcH
            
            // --- FIT_CENTER STRATEGY ---
            // Use Math.min (Fit-Center) to fill as much as possible without cropping margins.
            // This matches the Preview's 'Pillarbox' behavior if ratios mismatch.
            val baseScale = Math.min(tgtW / logicSrcW, tgtH / logicSrcH)
            val scale = baseScale * zoom
            
            val offsetX = (logicSrcW - tgtW / scale) / 2f
            val offsetY = (logicSrcH - tgtH / scale) / 2f

            val srcStrideArr = width * 4

            // 1. Process Y Plane
            for (ty in 0 until tgtH.toInt()) {
                val rowPos = ty * yRowStride
                for (tx in 0 until tgtW.toInt()) {
                    // a) Map target(tx, ty) back to logic source coordinate system
                    val lx = tx / scale + offsetX
                    val ly = ty / scale + offsetY

                    // b) Apply rotation to get physical source coordinates (RGBA cache)
                    var sx = 0f
                    var sy = 0f
                    when (totalRotation % 360) {
                        0 -> { sx = lx; sy = ly }
                        90 -> { sx = ly; sy = srcH - 1 - lx }
                        180 -> { sx = srcW - 1 - lx; sy = srcH - 1 - ly }
                        270 -> { sx = srcW - 1 - ly; sy = lx }
                        else -> { sx = lx; sy = ly }
                    }

                    val srcRow = sx.toInt().coerceIn(0, width - 1)
                    val srcCol = sy.toInt().coerceIn(0, height - 1)
                    
                    // Actually, sx is horizontal (width) and sy is vertical (height)
                    // so sx -> col, sy -> row
                    val rgbaOff = (srcCol * srcStrideArr) + (srcRow * 4)
                    
                    if (rgbaOff >= 0 && rgbaOff + 3 < rgbaBytes.size) {
                        val r = rgbaBytes[rgbaOff].toInt() and 0xFF
                        val g = rgbaBytes[rgbaOff+1].toInt() and 0xFF
                        val b = rgbaBytes[rgbaOff+2].toInt() and 0xFF
                        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                        val pos = rowPos + (tx * yPixStride)
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
                // [XIAOMI FIX] 2-plane semi-planar layout (NV21/NV12)
                val uvPlane = planes[1]
                val uvBuffer = uvPlane.buffer
                val pStride = uvPlane.rowStride
                val pixStride = uvPlane.pixelStride // Should be 2
                
                val tW_out = w / 2
                val tH_out = h / 2
                
                for (ty in 0 until tH_out) {
                    val rowPos = ty * pStride
                    for (tx in 0 until tW_out) {
                        // a) Map target(tx, ty) back to logic source coordinate system
                        // Multiply by 2 because this is Chroma (half res)
                        // [PRECISION FIX] Float-to-Int conversion centering
                        val lx = ((tx * 2) / scale + offsetX).coerceIn(0f, logicSrcW - 1)
                        val ly = ((ty * 2) / scale + offsetY).coerceIn(0f, logicSrcH - 1)

                        // b) Apply rotation to get physical source coordinates (RGBA cache)
                        var sx = 0f
                        var sy = 0f
                        when (totalRotation % 360) {
                            0 -> { sx = lx; sy = ly }
                            90 -> { sx = ly; sy = srcH - 1 - lx }
                            180 -> { sx = srcW - 1 - lx; sy = srcH - 1 - ly }
                            270 -> { sx = srcW - 1 - ly; sy = lx }
                            else -> { sx = lx; sy = ly }
                        }

                        val srcRow = sx.toInt().coerceIn(0, width - 1)
                        val srcCol = sy.toInt().coerceIn(0, height - 1)
                        
                        val rgbaOff = (srcCol * srcStrideArr) + (srcRow * 4)
                        if (rgbaOff >= 0 && rgbaOff + 3 < rgbaBytes.size) {
                            val r = rgbaBytes[rgbaOff].toInt() and 0xFF
                            val g = rgbaBytes[rgbaOff+1].toInt() and 0xFF
                            val b = rgbaBytes[rgbaOff+2].toInt() and 0xFF
                            
                            val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                            val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                            
                            val effectivelyNv21 = if (isColorSwapped) !isNv21 else isNv21
                            val firstByte = if (effectivelyNv21) v else u
                            val secondByte = if (effectivelyNv21) u else v
                            
                            val pos = rowPos + (tx * pixStride)
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
                    val tW_out = w / 2
                    val tH_out = h / 2
                    
                    for (ty in 0 until tH_out) {
                        val rowPos = ty * pStride
                        for (tx in 0 until tW_out) {
                            val lx = (tx * 2) / scale + offsetX
                            val ly = (ty * 2) / scale + offsetY

                            var sx = 0f
                            var sy = 0f
                            when (totalRotation % 360) {
                                0 -> { sx = lx; sy = ly }
                                90 -> { sx = ly; sy = srcH - 1 - lx }
                                180 -> { sx = srcW - 1 - lx; sy = srcH - 1 - ly }
                                270 -> { sx = srcW - 1 - ly; sy = lx }
                                else -> { sx = lx; sy = ly }
                            }

                            val srcRow = sx.toInt().coerceIn(0, width - 1)
                            val srcCol = sy.toInt().coerceIn(0, height - 1)
                            
                            val rgbaOff = (srcCol * srcStrideArr) + (srcRow * 4)
                            if (rgbaOff >= 0 && rgbaOff + 3 < rgbaBytes.size) {
                                val r = rgbaBytes[rgbaOff].toInt() and 0xFF
                                val g = rgbaBytes[rgbaOff+1].toInt() and 0xFF
                                val b = rgbaBytes[rgbaOff+2].toInt() and 0xFF
                                
                                val chroma = if (isU) {
                                    ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                } else {
                                    ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                }
                                
                                val pos = rowPos + (tx * pixStride)
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
            val jpegBytes = generateAndStoreSpoofedJpeg()
            if (jpegBytes == null) {
                Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Failed to generate Virtual JPEG.")
                return
            }
            
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



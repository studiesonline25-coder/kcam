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

    private val bufferLock = Any()
    @Volatile private var isBufferReady = false
    private var writeBuffer: ByteArray? = null
    private var readyBuffer: ByteArray? = null
    private var conversionBuffer: ByteArray? = null

    private var debugCounter = 0
    private var lastBufferDumpMs = 0L

    // [HARDENING] Push Thread to prevent deadlocks in browser ImageCapture
    private var pushThread: HandlerThread? = null
    private var pushHandler: Handler? = null
    
    // [HARDENING] Last Good Frame Fallback
    private var lastGoodRgba: ByteArray? = null
    private val lastGoodLock = Any()

    // Stage-dump markers — dump once per session on first valid frame
    private var didDumpStage1 = false  // post-GL RGBA (bridge cache capture)
    private var didDumpStage2 = false  // post-bridge YUV (overwrite)
    private var didDumpStage3 = false  // final consumed capture (downstream reader)

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
                    
                    // Initialize Push Thread
                    pushThread = HandlerThread("VirtuCamPushThread").apply { start() }
                    pushHandler = Handler(pushThread!!.looper)
                    
                    Log.d(TAG, "FormatConverterBridge: ImageWriter and PushThread connected")
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
                        var wBuf = writeBuffer
                        if (wBuf == null || wBuf.size != expectedSize) {
                            wBuf = ByteArray(expectedSize)
                            writeBuffer = wBuf
                            readyBuffer = ByteArray(expectedSize)
                            conversionBuffer = ByteArray(expectedSize)
                        }
                        
                        // Copy row-by-row to handle stride correctly
                        buffer.position(0)
                        if (rowStride == width * 4 && pixelStride == 4) {
                            // Fast path: no padding
                            buffer.get(wBuf)
                        } else {
                            // Slow path: copy row by row ignoring padding
                            for (row in 0 until height) {
                                buffer.position(row * rowStride)
                                buffer.get(wBuf, row * width * 4, width * 4)
                            }
                        }
                        
                        synchronized(bufferLock) {
                            System.arraycopy(wBuf, 0, readyBuffer!!, 0, expectedSize)
                            isBufferReady = true
                            
                            // [FALLBACK] Update last good frame
                            if (checkDataIntegrity(wBuf)) {
                                synchronized(lastGoodLock) {
                                    if (lastGoodRgba == null || lastGoodRgba!!.size != expectedSize) {
                                        lastGoodRgba = ByteArray(expectedSize)
                                    }
                                    System.arraycopy(wBuf, 0, lastGoodRgba!!, 0, expectedSize)
                                }
                            }
                        }
                    }
                    // [INVESTIGATION] Periodic buffer dump for rotation analysis.
                    // Throttled to once per 5 seconds per bridge to avoid spamming disk.
                    val nowMs = System.currentTimeMillis()
                    if (CameraHook.isBufferCaptureEnabled && nowMs - lastBufferDumpMs > 5_000L) {
                        lastBufferDumpMs = nowMs
                        val dumpBuf = synchronized(bufferLock) { readyBuffer?.copyOf() }
                        if (dumpBuf != null) {
                            try {
                                BufferDumper.dumpRgba(width, height, dumpBuf, "bridge_${width}x${height}")
                            } catch (_: Throwable) {}
                        }
                    }

                    // [STAGE DUMP 1] Post-GL RGBA — one-shot per session
                    if (CameraHook.isBufferCaptureEnabled && !didDumpStage1 && isBufferReady) {
                        didDumpStage1 = true
                        val dumpBuf = synchronized(bufferLock) { readyBuffer?.copyOf() }
                        if (dumpBuf != null) {
                            try {
                                BufferDumper.dumpRgba(width, height, dumpBuf, "stage1_gl_rgba_${width}x${height}")
                                Log.i(TAG, "[STAGE_DUMP_1] Saved post-GL RGBA buffer to virtucam_audit/buffers/stage1_gl_rgba_${width}x${height}_*.png")
                            } catch (_: Throwable) {}
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
    internal fun generateAndStoreSpoofedJpeg() {
        // Primary source: the bridge's own readyBuffer (set by ImageReader callback)
        // Fallback source: conversionBuffer (already filled by overwriteImageWithLatestYuv's fallback logic)
        val sourceBuffer = if (isBufferReady && readyBuffer != null) readyBuffer 
                           else if (conversionBuffer != null && checkDataIntegrity(conversionBuffer!!)) conversionBuffer
                           else return
        if (sourceBuffer == null) return
        
        val w = width
        val h = height
        val expectedSize = w * h * 4
        
        // Copy source data into conversion buffer (if not already there)
        if (sourceBuffer !== conversionBuffer) {
            synchronized(bufferLock) {
                System.arraycopy(sourceBuffer, 0, conversionBuffer!!, 0, expectedSize)
            }
        }
        val rgbaBytes = conversionBuffer!!
        
        if (!checkDataIntegrity(rgbaBytes)) return
        
        // [SHUTTER OPTIMIZATION] Throttle: only one bridge processes JPEG at a time
        if (CameraHook.isGeneratingJpeg) return
        
        Thread {
            try {
                CameraHook.isGeneratingJpeg = true
                Log.d(TAG, "FormatConverterBridge: Async JPEG start for ${w}x${h}")
                
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val rgbaBuffer = ByteBuffer.wrap(rgbaBytes)
                bitmap.copyPixelsFromBuffer(rgbaBuffer)
                
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                bitmap.recycle()
                
                var jpegBytes = baos.toByteArray()
                
                // [HARDWARE PARITY FIX] Disabled EXIF INJECTION
                // Let the OS handle real EXIF orientation naturally.
                
                val area = w * h
                synchronized(CameraHook) {
                    if (area >= CameraHook.latestVirtualJpegArea) {
                        CameraHook.latestVirtualJpeg = jpegBytes
                        CameraHook.latestVirtualJpegArea = area
                        Log.d(TAG, "FormatConverterBridge: Stored Virtual JPEG (${jpegBytes.size} bytes) (Area: $area)")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Async JPEG generation failed", e)
            } finally {
                CameraHook.isGeneratingJpeg = false
                Log.d(TAG, "FormatConverterBridge: Async JPEG done.")
            }
        }.start()
    }

    /**
     * Synchronously overwrites the target image with our RGBA data converted to YUV.
     * Engineered for Xiaomi MiAlgoEngine (Parallel Service) compatibility.
     * Strict NV21 (V before U) layout for 2-plane semi-planar buffers.
     */
    private var lastJpegGenTimeMs = 0L

    // [DIAGNOSTIC] Frame-drop tracking
    private var diagCallCount = 0
    private var diagDropBufferNotReady = 0
    private var diagDropIntegrity = 0
    private var diagDropGuard = 0
    private var diagSuccess = 0

    fun overwriteImageWithLatestYuv(targetImage: Image, timestamp: Long) {
        diagCallCount++
        val expectedSize = width * height * 4
        
        // [ON-DEMAND ALLOCATION] Ensure buffers exist even if ImageReader callback never fired.
        // This happens when the VirtualRenderThread doesn't render to this bridge's internal surface.
        if (conversionBuffer == null || conversionBuffer!!.size != expectedSize) {
            conversionBuffer = ByteArray(expectedSize)
        }
        if (readyBuffer == null || readyBuffer!!.size != expectedSize) {
            readyBuffer = ByteArray(expectedSize)
        }
        if (writeBuffer == null || writeBuffer!!.size != expectedSize) {
            writeBuffer = ByteArray(expectedSize)
        }
        
        // [GREEN SCREEN FIX] Fallback to last good frame if current buffer is stale
        if (!isBufferReady || readyBuffer == null) {
            val fallback = synchronized(lastGoodLock) { lastGoodRgba?.copyOf() }
            if (fallback != null) {
                System.arraycopy(fallback, 0, conversionBuffer!!, 0, expectedSize)
            } else {
                // Total failure: Pre-fill with Black (RGBA 0,0,0,255) to avoid Green
                java.util.Arrays.fill(conversionBuffer!!, 0.toByte())
                for (i in 3 until conversionBuffer!!.size step 4) conversionBuffer!![i] = 255.toByte()
            }
        } else {
            synchronized(bufferLock) {
                System.arraycopy(readyBuffer!!, 0, conversionBuffer!!, 0, expectedSize)
            }
        }
        
        // [Fix] Generate JPEG payload for late-stage file swap.
        // Moved AFTER buffer fill so conversionBuffer has valid data.
        // Throttled to once per second to avoid burning CPU on every preview frame.
        val now = System.currentTimeMillis()
        if (now - lastJpegGenTimeMs > 1000) {
            lastJpegGenTimeMs = now
            generateAndStoreSpoofedJpeg()
        }
        
        val rgbaBytes = conversionBuffer!!
        
        // Final sanity check
        if (!checkDataIntegrity(rgbaBytes)) {
             // Fallback to black if still zeroed
             java.util.Arrays.fill(rgbaBytes, 0.toByte())
             for (i in 3 until rgbaBytes.size step 4) rgbaBytes[i] = 255.toByte()
        }

        // [GREEN SCREEN FIX] Circuit breaker for connecting RTSP streams removed.
        // It was blocking Static Image and Test Pattern modes.        
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
            
            val context = android.app.AndroidAppHelper.currentApplication()
            var zoom = CameraHook.zoomFactor
            var comp = CameraHook.compensationFactor
            var userRot = CameraHook.rotationOffset
            
            // --- METADATA COURIER SYNC (Build 220: The 'Truth' Sync) ---
            // We use the 'Courier' to sync all transformation parameters in real-time.
            // This bypasses slow disk reads and stale SharedPreferences in background processes.
            val couriedState = CameraHook.getLatestCouriedState(timestamp)
            if (couriedState != null) {
                comp = couriedState.compensationFactor
                userRot = couriedState.rotationOffset
                Log.d(TAG, "DIAGNOSTIC_VIRTUCAM: GLOBAL SYNC SUCCESS via Courier. Stretch=$comp, RotOffset=$userRot (TS $timestamp)")
            } else {
                // Fallback for logic consistency
                Log.d(TAG, "DIAGNOSTIC_VIRTUCAM: GLOBAL SYNC CHECK (Courier Pending). Current Stretch=$comp, RotOffset=$userRot")
            }

            // --- PERFECT HARDWARE 1:1 MAPPING ---
            // Because VirtualRenderThread's isotropic math in TextureRenderer ALREADY
            // perfectly rotated, scaled, and geometrically cropped the image into the exact
            // dimensions of the EGL buffer (matching target size), applying math here causes Double-Rotation
            // and Double-Cropping (the "massive black bars & sideways" bug).
            // We just perform a direct 1:1 byte copy from our pre-rendered RGBA EGL cache into the YUV target.
            
            val srcStrideArr = width * 4

            // 1. Process Y Plane
            if (rgbaBytes.isEmpty()) {
                Log.e(TAG, "FormatConverterBridge: RGBA source is empty!")
                return
            }
            
            // Bounds safety: Only process up to what we actually have in our source buffer
            val processH = h.coerceAtMost(height)
            val processW = w.coerceAtMost(width)

            for (ty in 0 until processH) {
                val rowPos = ty * yRowStride
                val srcRowBase = ty * srcStrideArr
                for (tx in 0 until processW) {
                    val rgbaOff = srcRowBase + (tx * 4)
                    if (rgbaOff + 3 < rgbaBytes.size) {
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
            val isYV12 = (format == 842094169)
            val isNv21 = (format == 0x11)
            val isSemiPlanar = (planes.size == 2)

            if (isSemiPlanar) {
                // NV21/NV12 2-plane semi-planar layout
                val uvPlane = planes[1]
                val uvBuffer = uvPlane.buffer
                val pStride = uvPlane.rowStride
                val pixStride = uvPlane.pixelStride
                
                val tW_out = (w / 2).coerceAtMost(width / 2)
                val tH_out = (h / 2).coerceAtMost(height / 2)
                
                for (ty in 0 until tH_out) {
                    val rowPos = ty * pStride
                    val srcRowBase = (ty * 2) * srcStrideArr
                    for (tx in 0 until tW_out) {
                        val rgbaOff = srcRowBase + ((tx * 2) * 4)
                        if (rgbaOff + 3 < rgbaBytes.size) {
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
                // Standard 3-plane layout (YUV420P / YV12)
                val uPlane = if (isYV12) (if (planes.size > 2) planes[2] else null) else (if (planes.size > 1) planes[1] else null)
                val vPlane = if (isYV12) (if (planes.size > 1) planes[1] else null) else (if (planes.size > 2) planes[2] else null)

                fun writeChroma(plane: Image.Plane?, isU: Boolean) {
                    if (plane == null) return
                    val buffer = plane.buffer
                    val pStride = plane.rowStride
                    val pixStride = plane.pixelStride
                    val tW_out = (w / 2).coerceAtMost(width / 2)
                    val tH_out = (h / 2).coerceAtMost(height / 2)
                    
                    for (ty in 0 until tH_out) {
                        val rowPos = ty * pStride
                        val srcRowBase = (ty * 2) * srcStrideArr
                        for (tx in 0 until tW_out) {
                            val rgbaOff = srcRowBase + ((tx * 2) * 4)
                            if (rgbaOff + 3 < rgbaBytes.size) {
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
            if (CameraHook.isBufferCaptureEnabled && debugCounter % 5 == 0) {
                dumpRawBuffer(targetImage, "capture_yuv_${System.currentTimeMillis()}.raw")
            }
            debugCounter++

            // [STAGE DUMP 2] Post-bridge YUV — one-shot per session
            if (CameraHook.isBufferCaptureEnabled && !didDumpStage2) {
                didDumpStage2 = true
                try {
                    // Dump the target YUV as-is (our rewritten buffer)
                    BufferDumper.dumpYuvImage(targetImage, "stage2_bridge_yuv_${w}x${h}")
                    Log.i(TAG, "[STAGE_DUMP_2] Saved post-bridge YUV buffer to virtucam_audit/buffers/stage2_bridge_yuv_${w}x${h}_*.png")
                } catch (_: Throwable) {}
            }

            diagSuccess++
            if (diagCallCount % 30 == 0) {
                val coveragePct = (processW.toLong() * processH * 100) / (w.toLong() * h).coerceAtLeast(1)
                // Sample RGBA at 5 points across buffer to check if data is real
                val bufSize = rgbaBytes.size
                val positions = listOf(0, bufSize/4, bufSize/2, bufSize*3/4, bufSize-8).filter { it >= 0 && it+4 <= bufSize }
                val samples = positions.map { pos ->
                    val r = rgbaBytes[pos].toInt() and 0xFF
                    val g = rgbaBytes[pos+1].toInt() and 0xFF
                    val b = rgbaBytes[pos+2].toInt() and 0xFF
                    val a = rgbaBytes[pos+3].toInt() and 0xFF
                    "@${pos}:R${r}G${g}B${b}A${a}"
                }
                // Count non-zero bytes in a quick 1000-byte sample from the middle
                val midStart = (bufSize / 2).coerceAtMost(bufSize - 1000).coerceAtLeast(0)
                var nonZeroMid = 0
                for (i in midStart until (midStart + 1000).coerceAtMost(bufSize)) {
                    if (rgbaBytes[i] != 0.toByte()) nonZeroMid++
                }
                Log.e(TAG, "GREEN_DIAG: calls=$diagCallCount success=$diagSuccess | target=${w}x${h} bridge=${width}x${height} coverage=${coveragePct}% | midNonZero=$nonZeroMid/1000 | ${samples.joinToString(" ")}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: Error overwriting capture buffer", e)
        }
    }

    /**
     * Overwrite a JPEG-format Image buffer with our spoofed content.
     */
    fun overwriteImageWithLatestJpeg(targetImage: Image) {
        // Local buffer readiness is not required for JPEG because we pull from the globally cached CameraHook.latestVirtualJpeg
        
        // Note: checkDataIntegrity is done inside generateAndStoreSpoofedJpeg
        
        val planes = targetImage.planes
        if (planes.isEmpty()) return
            
            val jpegBuffer = planes[0].buffer
            if (!jpegBuffer.hasRemaining()) {
                 Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Target JPEG buffer has no remaining capacity!")
                 return
            }
            
            val tW = targetImage.width
            val tH = targetImage.height
            Log.e(TAG, "CAPT_LOG [3c]: overwriteImageWithLatestJpeg executing. TargetImage: ${tW}x${tH}")
            
            // Trigger background JPEG generation from THIS bridge first
            generateAndStoreSpoofedJpeg()
            
            // [STRICT ISOLATION FIX] If this bridge has no buffer (e.g. it's a JPEG-only bridge 
            // that we deliberately don't push frames to), ask any YUV bridge that IS receiving 
            // live frames to generate the global JPEG snapshot instead.
            if (CameraHook.latestVirtualJpeg == null) {
                for (bridge in CameraHook.formatBridges.values) {
                    if (bridge !== this && bridge.isBufferReady) {
                        bridge.generateAndStoreSpoofedJpeg()
                        if (CameraHook.latestVirtualJpeg != null) break
                    }
                }
            }
            
            // [HARDWARE PARITY FIX] Browsers don't pre-cache JPEGs during preview.
            // When takePhoto() is called, the GPU needs a few milliseconds to read pixels 
            // and the async thread needs time to encode the JPEG. We must wait for it.
            var jpegBytes = CameraHook.latestVirtualJpeg
            var waitCount = 0
            while (jpegBytes == null && waitCount < 5) { // Wait up to 100ms only
                Thread.sleep(20)
                // Try any bridge with a ready buffer
                for (bridge in CameraHook.formatBridges.values) {
                    if (bridge.isBufferReady) {
                        bridge.generateAndStoreSpoofedJpeg()
                    }
                }
                jpegBytes = CameraHook.latestVirtualJpeg
                waitCount++
            }
            
            if (jpegBytes == null) {
                Log.e(TAG, "CAPT_LOG [3d]: Failed to get latest Virtual JPEG after waiting ${waitCount * 20}ms. BufferReady=$isBufferReady")
                return
            }
            
            Log.e(TAG, "CAPT_LOG [3e]: Virtual JPEG acquired after ${waitCount * 20}ms. Size=${jpegBytes.size} bytes. Writing to Target Buffer Capacity=${jpegBuffer.capacity()}")
            
            // [Surgical Scrub] Zero out the hardware buffer first to prevent "file corrupted" 
            // errors caused by trailing sensor data at the end of the buffer.
            try {
                jpegBuffer.clear()
                val zeroArray = ByteArray(Math.min(1024, jpegBuffer.capacity()))
                var written = 0
                while (written < jpegBuffer.capacity()) {
                    val toWrite = Math.min(zeroArray.size, jpegBuffer.capacity() - written)
                    jpegBuffer.put(zeroArray, 0, toWrite)
                    written += toWrite
                }
                jpegBuffer.clear()
            } catch (_: Exception) {
                jpegBuffer.clear()
            }
            
            val finalLimit: Int
            if (jpegBytes.size > jpegBuffer.capacity()) {
                Log.w(TAG, "FormatConverterBridge: Spoofed JPEG (${jpegBytes.size}) > Buffer Capacity (${jpegBuffer.capacity()}). Skipping buffer overwrite. Relying on FileOutputStream hook.")
                finalLimit = jpegBuffer.capacity() // Keep original hardware JPEG size
            } else {
                jpegBuffer.put(jpegBytes, 0, jpegBytes.size)
                finalLimit = jpegBytes.size
            }
            
            // Do NOT flip. We want the camera framework to read up to its native size.
            // Appending our naked JPEG without EXIF APP1 blocks is enough because modern 
            // camera zipping modules (like Xiaomi CAM_ParallelDataZipper) parse and overwrite EXIF 
            // based on CaptureRequest parameters downstream automatically. Let them do their job.
            jpegBuffer.position(0)
            jpegBuffer.limit(finalLimit)
            
            // DIAGNOSTIC DUMP: Save the spoofed JPEG to SD card to see exactly what Veriff sees
            if (CameraHook.isBufferCaptureEnabled) {
                saveDebugImage(jpegBytes, "capture_jpeg_${System.currentTimeMillis()}.jpg")
            }
            
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
            this.imageWriter = ImageWriter.newInstance(imageReader.surface, 5, outputFormat)
            Log.i(TAG, "FormatConverterBridge: Connected to ImageReader ${width}x${height} for format $outputFormat")
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: Failed to connect to ImageReader", e)
        }
    }

    /**
     * Pushes the latest cached RGBA frame into the target ImageReader using the ImageWriter.
     * Synchronized via the Capture Session's sensor timestamp.
     */
    fun pushLatestFrameToWriter(timestamp: Long) {
        val writer = imageWriter ?: run {
            Log.e(TAG, "CAPT_LOG [3a]: pushLatestFrameToWriter called but imageWriter is NULL!")
            return
        }
        
        Log.e(TAG, "CAPT_LOG [3b]: pushLatestFrameToWriter starting Thread to dequeue and push frame. timestamp=$timestamp")
        Thread {
            try {

                val outImage = try { writer.dequeueInputImage() } catch (e: Exception) { 
                    Log.e(TAG, "CAPT_LOG [3-ERR]: dequeueInputImage threw exception: ${e.message}")
                    null 
                } ?: run {
                    Log.e(TAG, "CAPT_LOG [3-ERR]: dequeueInputImage returned NULL! Buffer queue likely full or not ready.")
                    return@Thread
                }
                
                var success = false
                try {
                    outImage.timestamp = timestamp
                    
                    val fmt = outImage.format
                    if (fmt == 256) { // JPEG
                        overwriteImageWithLatestJpeg(outImage)
                    } else {
                        overwriteImageWithLatestYuv(outImage, timestamp)
                    }
                    
                    writer.queueInputImage(outImage)
                    success = true
                } finally {
                    if (!success) {
                        try { outImage.close() } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) {}
        }.start()
    }

    fun release() {
        try {
            imageWriter?.close()
            imageWriter = null
            imageReader?.close()
            handlerThread?.quitSafely()
            pushThread?.quitSafely()
            imageReader = null
        } catch (t: Throwable) {
            Log.e(TAG, "Error during Bridge release", t)
        }
    }
}



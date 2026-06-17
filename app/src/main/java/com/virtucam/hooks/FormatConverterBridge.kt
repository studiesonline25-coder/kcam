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
import java.util.concurrent.Executors
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
    
    // [STEALTH MODE] Conditional logging wrappers
    private fun logD(tag: String, msg: String) {
        if (CameraHook.enableDiagnosticLogs) android.util.Log.d(tag, msg)
    }
    
    private fun logE(tag: String, msg: String, throwable: Throwable? = null) {
        if (CameraHook.enableDiagnosticLogs) {
            if (throwable != null) android.util.Log.e(tag, msg, throwable)
            else android.util.Log.e(tag, msg)
        }
    }
    
    private fun logW(tag: String, msg: String) {
        if (CameraHook.enableDiagnosticLogs) android.util.Log.w(tag, msg)
    }
    
    private fun logV(tag: String, msg: String) {
        if (CameraHook.enableDiagnosticLogs) android.util.Log.v(tag, msg)
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

    // [PERF] Pre-allocated row caches for bulk YUV conversion (avoid per-frame GC)
    private var yRowCache: ByteArray? = null
    private var uvRowCache: ByteArray? = null
    private var chromaRowCache: ByteArray? = null

    // [REFINE] TAA Caches
    private var prevYCache: ByteArray? = null
    private var prevUvCache: ByteArray? = null

    // [MEMORY POOL] Pre-allocated resources for JPEG generation
    private var cachedBitmap: Bitmap? = null
    private var cachedRotatedBitmap: Bitmap? = null
    private var cachedBaos: ByteArrayOutputStream? = null

    private var debugCounter = 0
    private var lastBufferDumpMs = 0L

    // [HARDENING] Push Thread to prevent deadlocks in browser ImageCapture
    private var pushThread: HandlerThread? = null
    
    // [PERFORMANCE FIX] Dedicated thread pool for heavy YUV conversion/pushing to avoid blocking HAL's ImageAvailableListener
    private val writerExecutor = Executors.newSingleThreadExecutor()

    // Threads for caching and pushing
    private var cacheThread: HandlerThread? = null
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
                if (outputFormat == 256 || outputFormat == ImageFormat.JPEG || outputFormat == 35 || outputFormat == ImageFormat.YUV_420_888 || outputFormat == ImageFormat.YV12) {
                    try {
                        // [BROWSER CAPTURE FIX] Try API 29+ 3-arg ImageWriter.newInstance(surface, maxImages, format)
                        // first. This is more reliable for JPEG surfaces because the format is explicitly specified,
                        // avoiding auto-detection failures on some OEM implementations.
                        imageWriter = try {
                            val newInstanceMethod = ImageWriter::class.java.getMethod(
                                "newInstance", Surface::class.java, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
                            )
                            val writer = newInstanceMethod.invoke(null, outputSurface, 1, outputFormat) as ImageWriter
                            Log.d(TAG, "FormatConverterBridge: ImageWriter created via API 29+ (format=$outputFormat)")
                            writer
                        } catch (e: Throwable) {
                            Log.d(TAG, "FormatConverterBridge: API 29+ ImageWriter failed (${e.message}), trying 2-arg fallback with maxImages=1")
                            android.media.ImageWriter.newInstance(outputSurface, 1)
                        }
                        
                        // Initialize Push Thread
                        pushThread = HandlerThread("VirtuCamPushThread").apply { start() }
                        pushHandler = Handler(pushThread!!.looper)
                        
                        Log.d(TAG, "FormatConverterBridge: ImageWriter and PushThread connected for ${width}x${height} format=$outputFormat")
                    } catch (e: Throwable) {
                        Log.e(TAG, "FormatConverterBridge: Failed to connect ImageWriter for ${width}x${height} format=$outputFormat — will use direct overwrite fallback", e)
                        imageWriter = null
                    }
                } else {
                    Log.d(TAG, "FormatConverterBridge: Skipping ImageWriter for format=$outputFormat (YUV streams use synchronous overwrite)")
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
                        
                        // [FACE DETECTION] Process frame for STATISTICS_FACES metadata (async, non-blocking)
                        try {
                            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                            bitmap.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(wBuf))
                            // Feed the frame to ML Kit for face detection (non-blocking)
                            // We need to pass the active array size so the face bounding boxes are mapped correctly
                            val activeArraySize = CameraHook.cameraActiveArraySizes[CameraHook.activeCameraId]
                            FaceDetectionHelper.processFrameAsync(bitmap, width, height, activeArraySize)
                            bitmap.recycle()
                        } catch (e: Exception) {
                            // Face detection is optional, don't crash if it fails
                            if (CameraHook.enableDiagnosticLogs) {
                                Log.w(TAG, "Face detection skipped: ${e.message}")
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
    /**
     * Public entry point for periodic JPEG pre-warming from the render loop.
     * Keeps latestVirtualJpeg fresh so takePhoto() is instant.
     */
    fun warmJpegCache() {
        if (!isBufferReady) return
        val now = System.currentTimeMillis()
        
        if (width * height > 1920 * 1080) {
            // [NATIVE CAMERA FILTER] Never cache massive JPEGs for YUV bridges.
            // YUV bridges capture instantly via memory copy, they don't need JPEGs.
            // This prevents OOMs and preview stutter in Native Camera!
            if (outputFormat != 256) return
            
            // For massive JPEG bridges (Other Camera), throttle to 1s
            if (now - lastJpegGenTimeMs < 1000) return
        } else {
            // Preview bridges: warm cache frequently (every 1 second)
            if (now - lastJpegGenTimeMs < 1000) return
        }
        
        lastJpegGenTimeMs = now
        generateAndStoreSpoofedJpeg()
    }

    /**
     * Core JPEG encoding from RGBA cache. Called by both async and sync paths.
     * @param maxBytes If > 0, re-encodes at progressively lower quality until the JPEG fits.
     *                 This is critical because HAL JPEG buffers are often smaller than what
     *                 Bitmap.compress() produces at high quality.
     * Returns the JPEG bytes or null on failure.
     */
    private fun encodeJpegFromCache(maxBytes: Int = 0): ByteArray? {
        if (!isBufferReady || readyBuffer == null || conversionBuffer == null) return null
        
        val w = width
        val h = height
        val expectedSize = w * h * 4
        
        // Fast synchronized copy into conversion buffer
        // [RACE CONDITION FIX] Double-buffer copy to prevent split-line artifacts
        // Copy readyBuffer → conversionBuffer atomically to avoid reading half-old half-new data
        synchronized(bufferLock) {
            if (readyBuffer == null || conversionBuffer == null) {
                Log.w(TAG, "encodeJpegFromCache: Buffer became null during copy, using fallback")
                val fallback = synchronized(lastGoodLock) { lastGoodRgba?.copyOf() }
                if (fallback != null && conversionBuffer != null) {
                    System.arraycopy(fallback, 0, conversionBuffer!!, 0, expectedSize.coerceAtMost(fallback.size))
                } else {
                    return null  // No buffer available
                }
            } else {
                System.arraycopy(readyBuffer!!, 0, conversionBuffer!!, 0, expectedSize)
            }
        }
        val rgbaBytes = conversionBuffer!!
        
        if (!checkDataIntegrity(rgbaBytes)) return null
        
        // [MEMORY POOL FIX] Reuse Bitmap and ByteArrayOutputStream to prevent heap fragmentation
        var bitmap = cachedBitmap
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cachedBitmap = bitmap
        }
        
        val rgbaBuffer = ByteBuffer.wrap(rgbaBytes)
        bitmap.copyPixelsFromBuffer(rgbaBuffer)
        
        // Counter-rotate to upright (undo the sensor orientation baked by TextureRenderer)
        val rotationToApply = sensorOrientation
        val uprightBitmap = if (rotationToApply != 0) {
            val sw = if (rotationToApply % 180 == 0) w else h
            val sh = if (rotationToApply % 180 == 0) h else w
            var rotated = cachedRotatedBitmap
            if (rotated == null || rotated.width != sw || rotated.height != sh) {
                rotated?.recycle()
                val matrix = android.graphics.Matrix()
                matrix.postRotate(rotationToApply.toFloat())
                rotated = Bitmap.createBitmap(bitmap, 0, 0, w, h, matrix, true)
                cachedRotatedBitmap = rotated
            } else {
                val canvas = android.graphics.Canvas(rotated)
                val matrix = android.graphics.Matrix()
                matrix.postTranslate(-w / 2f, -h / 2f)
                matrix.postRotate(rotationToApply.toFloat())
                matrix.postTranslate(rotated.width / 2f, rotated.height / 2f)
                canvas.drawBitmap(bitmap, matrix, null)
            }
            rotated
        } else {
            bitmap
        }
        
        // Encode with quality stepping to fit buffer if maxBytes is specified
        var quality = 50
        var jpegBytes: ByteArray
        var baos = cachedBaos
        if (baos == null) {
            baos = ByteArrayOutputStream(1024 * 512) // Pre-allocate 512KB
            cachedBaos = baos
        }
        
        do {
            baos.reset()
            uprightBitmap.compress(Bitmap.CompressFormat.JPEG, quality, baos)
            jpegBytes = baos.toByteArray()
            if (maxBytes <= 0 || jpegBytes.size <= maxBytes) break
            Log.d(TAG, "JPEG quality $quality produced ${jpegBytes.size} bytes (max=$maxBytes), reducing...")
            quality -= 10
        } while (quality >= 15)
        
        // DO NOT recycle cached bitmaps here, they are reused!
        
        if (maxBytes > 0 && jpegBytes.size > maxBytes) {
            Log.w(TAG, "JPEG still ${jpegBytes.size} bytes at quality $quality (max=$maxBytes)")
        }
        
        return jpegBytes
    }

    private fun generateAndStoreSpoofedJpeg() {
        if (!isBufferReady || readyBuffer == null || conversionBuffer == null) {
            Log.d(TAG, "generateAndStoreSpoofedJpeg: Buffer not ready (isBufferReady=$isBufferReady, readyBuffer=${readyBuffer != null}, conversionBuffer=${conversionBuffer != null})")
            return
        }
        
        // [SHUTTER OPTIMIZATION] Throttle: only one bridge processes JPEG at a time
        // Safety timeout: auto-reset if stuck for >5 seconds
        if (CameraHook.isGeneratingJpeg) {
            if (System.currentTimeMillis() - CameraHook.jpegGenStartMs > 5000) {
                Log.w(TAG, "FormatConverterBridge: isGeneratingJpeg stuck for >5s, force-resetting")
                CameraHook.isGeneratingJpeg = false
            } else {
                return
            }
        }
        
        Thread {
            try {
                CameraHook.isGeneratingJpeg = true
                CameraHook.jpegGenStartMs = System.currentTimeMillis()
                Log.d(TAG, "FormatConverterBridge: Async JPEG start for ${width}x${height}")
                
                val jpegBytes = encodeJpegFromCache() ?: return@Thread
                
                val area = width * height
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
     * Synchronous JPEG generation — blocks caller until JPEG is ready.
     * Used as a fallback when async generation hasn't produced a result yet.
     */
    fun generateJpegSync(): ByteArray? {
        return try {
            encodeJpegFromCache()
        } catch (e: Exception) {
            Log.e(TAG, "Sync JPEG generation failed", e)
            null
        }
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
        
        // [Fix] Generate JPEG payload for late-stage file swap.
        // Throttled to once per second to avoid burning CPU on every preview frame.
        val now = System.currentTimeMillis()
        if (now - lastJpegGenTimeMs > 1000) {
            lastJpegGenTimeMs = now
            if (width * height <= 1920 * 1080) {
                generateAndStoreSpoofedJpeg()
            }
        }
        
        // [GREEN SCREEN FIX] Fallback to last good frame if current buffer is stale
        if (!isBufferReady || readyBuffer == null || conversionBuffer == null) {
            val fallback = synchronized(lastGoodLock) { lastGoodRgba?.copyOf() }
            if (fallback != null) {
                System.arraycopy(fallback, 0, conversionBuffer!!, 0, expectedSize)
            } else {
                // Total failure: Pre-fill with Black (RGBA 0,0,0,255) to avoid Green
                java.util.Arrays.fill(conversionBuffer!!, 0.toByte())
                for (i in 3 until conversionBuffer!!.size step 4) conversionBuffer!![i] = 255.toByte()
            }
        } else {
            // [RACE CONDITION FIX] Atomic copy to prevent split-line artifacts
            synchronized(bufferLock) {
                if (readyBuffer == null || conversionBuffer == null) {
                    Log.w(TAG, "overwriteImageWithLatestYuv: Buffer became null, using fallback")
                    val fallback = synchronized(lastGoodLock) { lastGoodRgba?.copyOf() }
                    if (fallback != null && conversionBuffer != null) {
                        System.arraycopy(fallback, 0, conversionBuffer!!, 0, expectedSize.coerceAtMost(fallback.size))
                    } else {
                        // Total failure: fill with black
                        if (conversionBuffer != null) {
                            java.util.Arrays.fill(conversionBuffer!!, 0.toByte())
                            for (i in 3 until conversionBuffer!!.size step 4) conversionBuffer!![i] = 255.toByte()
                        }
                        return
                    }
                } else {
                    System.arraycopy(readyBuffer!!, 0, conversionBuffer!!, 0, expectedSize)
                }
            }
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

            // [REFINE] Allocate TAA caches and Noise LUT if enabled
            val noiseLut = if (CameraHook.isRefineEnabled) {
                val reqY = processW * processH
                if (prevYCache == null || prevYCache!!.size != reqY) prevYCache = ByteArray(reqY)
                val reqUv = (processW / 2) * (processH / 2) * 2
                if (prevUvCache == null || prevUvCache!!.size != reqUv) prevUvCache = ByteArray(reqUv)
                
                ByteArray(256) { RealisticNoiseGenerator.cmosNoise(800.0, diagCallCount.toLong() + it).toInt().toByte() }
            } else null

            // [rPPG] Calculate synthetic heartbeat multiplier ONCE per frame (not per pixel)
            // This is the key to passing FFT-based liveness detectors.
            val rppgMultiplier = if (CameraHook.isRppgEnabled) {
                RealisticNoiseGenerator.rppgPulseModulator(
                    frameNumber = diagCallCount.toLong(),
                    fps = 30.0,
                    targetBpm = CameraHook.rppgBpm,
                    amplitude = 0.015
                )
            } else {
                1.0  // No modulation when disabled
            }

            // [ANTI-DETECTION] Calculate ambient screen color reflection ONCE per frame
            val screenColor = if (CameraHook.isColorFlashEnabled) {
                ScreenColorDetector.getInstance().getCurrentColor()
            } else {
                ScreenColorDetector.DetectedColor.NONE
            }
            
            // Fast integer math multipliers for ambient reflection (0-255 scale)
            val isFlashActive = screenColor.isSignificant()
            val tintR = if (isFlashActive) (screenColor.r * screenColor.intensity * 0.4f * 255).toInt() else 0
            val tintG = if (isFlashActive) (screenColor.g * screenColor.intensity * 0.4f * 255).toInt() else 0
            val tintB = if (isFlashActive) (screenColor.b * screenColor.intensity * 0.4f * 255).toInt() else 0

            // [PERF] Bulk row-based Y plane write to avoid per-pixel JNI boundary checks
            if (yPixStride == 1) {
                // Fast bulk path (most common: contiguous Y plane)
                var yRow = yRowCache
                if (yRow == null || yRow.size < processW) {
                    yRow = ByteArray(processW)
                    yRowCache = yRow
                }
                for (ty in 0 until processH) {
                    val rowPos = ty * yRowStride
                    val srcRowBase = ty * srcStrideArr
                    for (tx in 0 until processW) {
                        val rgbaOff = srcRowBase + (tx * 4)
                        if (rgbaOff + 3 < rgbaBytes.size) {
                            // [ANTI-DETECTION] Add color flash reflection
                            val r = java.lang.Math.min((rgbaBytes[rgbaOff].toInt() and 0xFF) + tintR, 255)
                            val gRaw = java.lang.Math.min((rgbaBytes[rgbaOff+1].toInt() and 0xFF) + tintG, 255)
                            val b = java.lang.Math.min((rgbaBytes[rgbaOff+2].toInt() and 0xFF) + tintB, 255)
                            
                            // [rPPG] Modulate Green channel with synthetic blood volume pulse
                            val g = (gRaw * rppgMultiplier).toInt().coerceIn(0, 255)
                            var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                            
                            // [HDR FIX] Add ultra-fast pseudo-random temporal noise to prevent HDR algorithms from stalling on identical frames
                            val noise = ((tx * 31 + ty * 17 + diagCallCount) % 5) - 2
                            y += noise
                            
                            
                            if (CameraHook.isRefineEnabled) {
                                // 1. Horizontal Box Blur (Fast sub-pixel blur to destroy FFT grids)
                                if (tx > 0) {
                                    val leftY = yRow[tx - 1].toInt() and 0xFF
                                    y = (leftY + y) / 2
                                }
                                
                                // 2. Temporal Anti-Aliasing (TAA Blend with previous frame)
                                val pYCache = prevYCache
                                if (pYCache != null && pYCache.size == processW * processH) {
                                    val cacheIdx = ty * processW + tx
                                    val prevY = pYCache[cacheIdx].toInt() and 0xFF
                                    y = ((y * 85) + (prevY * 15)) / 100
                                    pYCache[cacheIdx] = y.toByte()
                                }
                            }
                            yRow[tx] = y.coerceIn(16, 235).toByte()
                        }
                    }
                    if (rowPos + processW <= yBuffer.capacity()) {
                        yBuffer.position(rowPos)
                        yBuffer.put(yRow, 0, processW)
                    }
                }
            } else {
                // Slow fallback path for non-contiguous Y planes
                for (ty in 0 until processH) {
                    val rowPos = ty * yRowStride
                    val srcRowBase = ty * srcStrideArr
                    for (tx in 0 until processW) {
                        val rgbaOff = srcRowBase + (tx * 4)
                        if (rgbaOff + 3 < rgbaBytes.size) {
                            // [ANTI-DETECTION] Add color flash reflection
                            val r = java.lang.Math.min((rgbaBytes[rgbaOff].toInt() and 0xFF) + tintR, 255)
                            val gRaw = java.lang.Math.min((rgbaBytes[rgbaOff+1].toInt() and 0xFF) + tintG, 255)
                            val b = java.lang.Math.min((rgbaBytes[rgbaOff+2].toInt() and 0xFF) + tintB, 255)
                            
                            // [rPPG] Modulate Green channel with synthetic blood volume pulse
                            val g = (gRaw * rppgMultiplier).toInt().coerceIn(0, 255)
                            var y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                            
                            // [HDR FIX] Add ultra-fast pseudo-random temporal noise to prevent HDR algorithms from stalling on identical frames
                            val noise = ((tx * 31 + ty * 17 + diagCallCount) % 5) - 2
                            y += noise
                            
                            
                            if (CameraHook.isRefineEnabled) {
                                // Slow path TAA only (no easy blur access)
                                val pYCache = prevYCache
                                if (pYCache != null && pYCache.size == processW * processH) {
                                    val cacheIdx = ty * processW + tx
                                    val prevY = pYCache[cacheIdx].toInt() and 0xFF
                                    y = ((y * 85) + (prevY * 15)) / 100
                                    pYCache[cacheIdx] = y.toByte()
                                }
                            }
                            val pos = rowPos + (tx * yPixStride)
                            if (pos < yBuffer.capacity()) {
                                yBuffer.put(pos, y.coerceIn(16, 235).toByte())
                            }
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
                
                val effectivelyNv21 = if (isColorSwapped) !isNv21 else isNv21

                // [PERF] Bulk row-based UV write
                if (pixStride == 2) {
                    // Fast bulk path (most common for NV21/NV12: interleaved UV pairs)
                    val rowBytes = tW_out * 2
                    var uvRow = uvRowCache
                    if (uvRow == null || uvRow.size < rowBytes) {
                        uvRow = ByteArray(rowBytes)
                        uvRowCache = uvRow
                    }
                    for (ty in 0 until tH_out) {
                        val rowPos = ty * pStride
                        val srcRowBase = (ty * 2) * srcStrideArr
                        for (tx in 0 until tW_out) {
                            val rgbaOff = srcRowBase + ((tx * 2) * 4)
                            if (rgbaOff + 3 < rgbaBytes.size) {
                                // [ANTI-DETECTION] Add color flash reflection
                                val r = java.lang.Math.min((rgbaBytes[rgbaOff].toInt() and 0xFF) + tintR, 255)
                                val gRaw = java.lang.Math.min((rgbaBytes[rgbaOff+1].toInt() and 0xFF) + tintG, 255)
                                val b = java.lang.Math.min((rgbaBytes[rgbaOff+2].toInt() and 0xFF) + tintB, 255)
                                val g = (gRaw * rppgMultiplier).toInt().coerceIn(0, 255)
                                var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                
                                if (CameraHook.isRefineEnabled && noiseLut != null) {
                                    val noise = noiseLut[(tx + ty * 3) and 255].toInt()
                                    u += (noise * 2)
                                    v += (noise * 2)
                                    
                                    val pUvCache = prevUvCache
                                    if (pUvCache != null && pUvCache.size == tW_out * tH_out * 2) {
                                        val cacheIdx = (ty * tW_out + tx) * 2
                                        val prevU = pUvCache[cacheIdx].toInt() and 0xFF
                                        val prevV = pUvCache[cacheIdx + 1].toInt() and 0xFF
                                        u = ((u * 85) + (prevU * 15)) / 100
                                        v = ((v * 85) + (prevV * 15)) / 100
                                        pUvCache[cacheIdx] = u.toByte()
                                        pUvCache[cacheIdx + 1] = v.toByte()
                                    }
                                }
                                val firstByte = if (effectivelyNv21) v else u
                                val secondByte = if (effectivelyNv21) u else v
                                val off = tx * 2
                                uvRow[off] = firstByte.coerceIn(16, 240).toByte()
                                uvRow[off + 1] = secondByte.coerceIn(16, 240).toByte()
                            }
                        }
                        if (rowPos + rowBytes <= uvBuffer.capacity()) {
                            uvBuffer.position(rowPos)
                            uvBuffer.put(uvRow, 0, rowBytes)
                        }
                    }
                } else {
                    // Slow fallback path for unusual pixStride
                    for (ty in 0 until tH_out) {
                        val rowPos = ty * pStride
                        val srcRowBase = (ty * 2) * srcStrideArr
                        for (tx in 0 until tW_out) {
                            val rgbaOff = srcRowBase + ((tx * 2) * 4)
                            if (rgbaOff + 3 < rgbaBytes.size) {
                                // [ANTI-DETECTION] Add color flash reflection
                                val r = java.lang.Math.min((rgbaBytes[rgbaOff].toInt() and 0xFF) + tintR, 255)
                                val gRaw = java.lang.Math.min((rgbaBytes[rgbaOff+1].toInt() and 0xFF) + tintG, 255)
                                val b = java.lang.Math.min((rgbaBytes[rgbaOff+2].toInt() and 0xFF) + tintB, 255)
                                val g = (gRaw * rppgMultiplier).toInt().coerceIn(0, 255)
                                var u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                var v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                
                                if (CameraHook.isRefineEnabled && noiseLut != null) {
                                    val noise = noiseLut[(tx + ty * 3) and 255].toInt()
                                    u += (noise * 2)
                                    v += (noise * 2)
                                    
                                    val pUvCache = prevUvCache
                                    if (pUvCache != null && pUvCache.size == tW_out * tH_out * 2) {
                                        val cacheIdx = (ty * tW_out + tx) * 2
                                        val prevU = pUvCache[cacheIdx].toInt() and 0xFF
                                        val prevV = pUvCache[cacheIdx + 1].toInt() and 0xFF
                                        u = ((u * 85) + (prevU * 15)) / 100
                                        v = ((v * 85) + (prevV * 15)) / 100
                                        pUvCache[cacheIdx] = u.toByte()
                                        pUvCache[cacheIdx + 1] = v.toByte()
                                    }
                                }
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
                    
                    // [PERF] Bulk row-based chroma write
                    if (pixStride == 1) {
                        // Fast bulk path (most common for planar: contiguous chroma)
                        var cRow = chromaRowCache
                        if (cRow == null || cRow.size < tW_out) {
                            cRow = ByteArray(tW_out)
                            chromaRowCache = cRow
                        }
                        for (ty in 0 until tH_out) {
                            val rowPos = ty * pStride
                            val srcRowBase = (ty * 2) * srcStrideArr
                            for (tx in 0 until tW_out) {
                                val rgbaOff = srcRowBase + ((tx * 2) * 4)
                                if (rgbaOff + 3 < rgbaBytes.size) {
                                    // [ANTI-DETECTION] Add color flash reflection
                                    val r = java.lang.Math.min((rgbaBytes[rgbaOff].toInt() and 0xFF) + tintR, 255)
                                    val gRaw = java.lang.Math.min((rgbaBytes[rgbaOff+1].toInt() and 0xFF) + tintG, 255)
                                    val b = java.lang.Math.min((rgbaBytes[rgbaOff+2].toInt() and 0xFF) + tintB, 255)
                                    val g = (gRaw * rppgMultiplier).toInt().coerceIn(0, 255)
                                    var chroma = if (isU) {
                                        ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                    } else {
                                        ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                    }
                                    
                                    if (CameraHook.isRefineEnabled && noiseLut != null) {
                                        val noise = noiseLut[(tx + ty * 3) and 255].toInt()
                                        chroma += (noise * 2)
                                        // For planar, TAA cache is more complex so we just add noise for speed
                                    }
                                    cRow[tx] = chroma.coerceIn(16, 240).toByte()
                                }
                            }
                            if (rowPos + tW_out <= buffer.capacity()) {
                                buffer.position(rowPos)
                                buffer.put(cRow, 0, tW_out)
                            }
                        }
                    } else {
                        // Slow fallback path for non-contiguous chroma planes
                        for (ty in 0 until tH_out) {
                            val rowPos = ty * pStride
                            val srcRowBase = (ty * 2) * srcStrideArr
                            for (tx in 0 until tW_out) {
                                val rgbaOff = srcRowBase + ((tx * 2) * 4)
                                if (rgbaOff + 3 < rgbaBytes.size) {
                                    // [ANTI-DETECTION] Add color flash reflection
                                    val r = java.lang.Math.min((rgbaBytes[rgbaOff].toInt() and 0xFF) + tintR, 255)
                                    val gRaw = java.lang.Math.min((rgbaBytes[rgbaOff+1].toInt() and 0xFF) + tintG, 255)
                                    val b = java.lang.Math.min((rgbaBytes[rgbaOff+2].toInt() and 0xFF) + tintB, 255)
                                    val g = (gRaw * rppgMultiplier).toInt().coerceIn(0, 255)
                                    var chroma = if (isU) {
                                        ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                                    } else {
                                        ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                                    }
                                    
                                    if (CameraHook.isRefineEnabled && noiseLut != null) {
                                        val noise = noiseLut[(tx + ty * 3) and 255].toInt()
                                        chroma += (noise * 2)
                                    }
                                    val pos = rowPos + (tx * pixStride)
                                    if (pos < buffer.capacity()) {
                                        buffer.put(pos, chroma.coerceIn(16, 240).toByte())
                                    }
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
            
            // Try cached JPEG first (instant if pre-warmed during preview)
            var jpegBytes = CameraHook.latestVirtualJpeg

            // If no cached JPEG, generate synchronously. Use PREVIEW bridge if capture bridge is cold.
            if (jpegBytes == null) {
                if (!isBufferReady) {
                    Log.w(TAG, "CAPT_LOG [3c]: JPEG buffer cold. Forcing VirtualRenderThread to render a frame!")
                    synchronized(CameraHook) {
                        CameraHook.captureCount++
                        CameraHook.captureQueue.offer(Pair(System.nanoTime(), CameraHook.captureCount))
                    }
                    var waitCount = 0
                    while (!isBufferReady && waitCount < 50) { // Max 1s wait
                        Thread.sleep(20)
                        waitCount++
                    }
                }

                if (isBufferReady) {
                    jpegBytes = generateJpegSync()
                } else {
                    // [FIX] Fallback immediately to PREVIEW bridge if JPEG bridge is cold.
                    // This prevents 1-second timeouts that crash the Native Camera.
                    val activePreviewBridge = CameraHook.formatBridges.values.firstOrNull { it.isBufferReady }
                    if (activePreviewBridge != null) {
                        Log.w(TAG, "CAPT_LOG [3c]: JPEG buffer cold. Generating synchronously from PREVIEW bridge.")
                        jpegBytes = activePreviewBridge.generateJpegSync()
                    }
                }
                
                if (jpegBytes != null) {
                    synchronized(CameraHook) {
                        CameraHook.latestVirtualJpeg = jpegBytes
                        CameraHook.latestVirtualJpegArea = width * height
                    }
                }
            }

            // [RESOLUTION FIX] Native camera apps enforce strict resolution checks. 
            // If the app requested 2560x1920 but receives 1440x1080, it will fail with "photo capture failed".
            if (jpegBytes != null) {
                val area = CameraHook.latestVirtualJpegArea
                if (area > 0 && area != tW * tH) {
                    Log.e(TAG, "[TELEMETRY] Resolution mismatch! App Requested: ${tW}x${tH} | Virtual Camera Provided Area: $area | Scaling Triggered: YES")
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes!!.size)
                        if (bmp != null) {
                            val scaledBmp = android.graphics.Bitmap.createScaledBitmap(bmp, tW, tH, true)
                            val baos = java.io.ByteArrayOutputStream()
                            scaledBmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, baos)
                            val newBytes = baos.toByteArray()
                            scaledBmp.recycle()
                            bmp.recycle()
                            Log.e(TAG, "[TELEMETRY] Successfully scaled Virtual JPEG to ${tW}x${tH} (${newBytes.size} bytes)")
                            jpegBytes = newBytes
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "[TELEMETRY] Failed to scale Virtual JPEG", e)
                    }
                }
            }

            if (jpegBytes == null) {
                Log.e(TAG, "CAPT_LOG [3d]: Failed to get latest Virtual JPEG. Aggressively scrubbing buffer to prevent leak.")
                scrubBufferToBlack(jpegBuffer)
                return
            }
            
            Log.e(TAG, "CAPT_LOG [3e]: Virtual JPEG acquired synchronously. Size=${jpegBytes.size} bytes. Target Capacity=${jpegBuffer.capacity()}")
            
            // If cached JPEG is too big for the HAL buffer, re-encode at lower quality to fit.
            // HAL buffers are typically ~290KB while our quality-85 JPEG can be ~520KB.
            val bufCap = jpegBuffer.capacity()
            
            // --- EXIF PRESERVATION MAGIC ---
            // Extract EXIF (APP1) from the ORIGINAL hardware buffer before we overwrite it.
            var exifBytes: ByteArray? = null
            try {
                jpegBuffer.position(0)
                val header = ByteArray(2)
                jpegBuffer.get(header)
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) {
                    while (jpegBuffer.remaining() >= 4) {
                        val marker = ByteArray(2)
                        jpegBuffer.get(marker)
                        val lenBytes = ByteArray(2)
                        jpegBuffer.get(lenBytes)
                        val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                        
                        if (marker[0] == 0xFF.toByte() && marker[1] == 0xE1.toByte()) {
                            // Found APP1 (EXIF)!
                            Log.d(TAG, "FormatConverterBridge: Found APP1 EXIF marker of length $len")
                            val fullExifLen = len + 2
                            jpegBuffer.position(jpegBuffer.position() - 4) // Back up to marker start
                            val exif = ByteArray(fullExifLen)
                            jpegBuffer.get(exif)
                            exifBytes = exif
                            break
                        } else if (marker[0] == 0xFF.toByte() && marker[1] == 0xDA.toByte()) {
                            break // SOS (Start of Scan) - stop searching
                        } else {
                            jpegBuffer.position(jpegBuffer.position() + len - 2) // Skip segment
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "FormatConverterBridge: Failed to extract EXIF: ${e.message}")
            }
            jpegBuffer.clear()

            // Re-encode if Virtual JPEG + EXIF is too big
            val targetSize = jpegBytes.size + (exifBytes?.size ?: 0)
            if (targetSize > bufCap) {
                Log.w(TAG, "FormatConverterBridge: Spoofed JPEG+EXIF ($targetSize) > Buffer Capacity ($bufCap). Re-encoding to fit...")
                var fitted: ByteArray? = null
                
                if (isBufferReady) {
                    fitted = encodeJpegFromCache(maxBytes = bufCap - (exifBytes?.size ?: 0))
                } else {
                    val activePreviewBridge = CameraHook.formatBridges.values.firstOrNull { it.isBufferReady }
                    if (activePreviewBridge != null) {
                        fitted = activePreviewBridge.encodeJpegFromCache(maxBytes = bufCap - (exifBytes?.size ?: 0))
                    }
                }
                
                if (fitted == null || fitted.size + (exifBytes?.size ?: 0) > bufCap) {
                    try {
                        val bmp = android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
                        if (bmp != null) {
                            var q = 80
                            do {
                                val baos = java.io.ByteArrayOutputStream()
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, q, baos)
                                val out = baos.toByteArray()
                                if (out.size + (exifBytes?.size ?: 0) <= bufCap) {
                                    fitted = out
                                    break
                                }
                                q -= 10
                            } while (q >= 10)
                            bmp.recycle()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "FormatConverterBridge: Manual Bitmap fallback resize failed", e)
                    }
                }

                if (fitted != null && fitted.size + (exifBytes?.size ?: 0) <= bufCap) {
                    jpegBytes = fitted
                    Log.d(TAG, "FormatConverterBridge: Re-encoded JPEG fits: ${fitted.size} + EXIF <= $bufCap")
                } else {
                    Log.e(TAG, "FormatConverterBridge: Re-encode failed or still too big (${fitted?.size}). Skipping buffer overwrite.")
                }
            }
            
            val finalLimit: Int
            val finalTargetSize = jpegBytes.size
            if (finalTargetSize > bufCap) {
                Log.e(TAG, "FormatConverterBridge: CRITICAL! Spoofed JPEG STILL TOO BIG. Scrubbing hardware buffer to prevent leak!")
                scrubBufferToBlack(jpegBuffer)
                return 
            } else {
                       // --- EXIF PRESERVATION MAGIC ---
            // Extract EXIF (APP1) from the ORIGINAL hardware buffer before we overwrite it.
            var exifBytes: ByteArray? = null
            try {
                jpegBuffer.position(0)
                val header = ByteArray(2)
                jpegBuffer.get(header)
                if (header[0] == 0xFF.toByte() && header[1] == 0xD8.toByte()) {
                    while (jpegBuffer.remaining() >= 4) {
                        val marker = ByteArray(2)
                        jpegBuffer.get(marker)
                        val lenBytes = ByteArray(2)
                        jpegBuffer.get(lenBytes)
                        val len = ((lenBytes[0].toInt() and 0xFF) shl 8) or (lenBytes[1].toInt() and 0xFF)
                        
                        if (marker[0] == 0xFF.toByte() && marker[1] == 0xE1.toByte()) {
                            // Found APP1 (EXIF)!
                            Log.e(TAG, "[TELEMETRY] Found APP1 EXIF marker of length $len")
                            val fullExifLen = len + 2
                            jpegBuffer.position(jpegBuffer.position() - 4) // Back up to marker start
                            val exif = ByteArray(fullExifLen)
                            jpegBuffer.get(exif)
                            exifBytes = exif
                            break
                        } else {
                            // Skip other markers
                            jpegBuffer.position(jpegBuffer.position() + len - 2)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "[TELEMETRY] Failed to extract EXIF from hardware buffer", e)
            }

            // BEFORE clearing the buffer, extract the last 32 bytes which may contain the camera3_jpeg_blob
            val blobBackup = ByteArray(32)
            try {
                jpegBuffer.position(Math.max(0, jpegBuffer.capacity() - 32))
                jpegBuffer.get(blobBackup)
            } catch (e: Exception) {}

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
                
                // Write SOI
                jpegBuffer.put(0xFF.toByte())
                jpegBuffer.put(0xD8.toByte())
                
                // Write Extracted EXIF
                if (exifBytes != null) {
                    jpegBuffer.put(exifBytes)
                }
                
                // Strip ALL APPn blocks (APP0 to APP15) from Virtual JPEG so they don't conflict with Hardware EXIF
                var vOffset = 2
                try {
                    while (vOffset < jpegBytes.size - 4) {
                        val b0 = jpegBytes[vOffset]
                        val b1 = jpegBytes[vOffset+1]
                        if (b0 == 0xFF.toByte() && (b1 >= 0xE0.toByte() && b1 <= 0xEF.toByte())) {
                            val len = ((jpegBytes[vOffset+2].toInt() and 0xFF) shl 8) or (jpegBytes[vOffset+3].toInt() and 0xFF)
                            vOffset += 2 + len
                        } else {
                            break // Reached DQT or SOF
                        }
                    }
                } catch (_: Exception) {}
                
                val toWrite = jpegBytes.size - vOffset
                val totalCurrentSize = 2 + (exifBytes?.size ?: 0) + toWrite
                val paddingNeeded = bufCap - totalCurrentSize

                if (paddingNeeded > 0) {
                    // Write everything except FF D9
                    jpegBuffer.put(jpegBytes, vOffset, toWrite - 2)

                    // Pad the JPEG using valid COM (Comment) segments so it perfectly fills the capacity without trailing zeroes
                    var remainingPad = paddingNeeded
                    while (remainingPad > 0) {
                        val padSize = Math.min(65535, remainingPad)
                        if (padSize < 4) {
                            for (i in 0 until padSize) jpegBuffer.put(0.toByte())
                            remainingPad -= padSize
                            continue
                        }
                        jpegBuffer.put(0xFF.toByte())
                        jpegBuffer.put(0xFE.toByte())
                        val len = padSize - 2
                        jpegBuffer.put(((len shr 8) and 0xFF).toByte())
                        jpegBuffer.put((len and 0xFF).toByte())
                        for (i in 0 until len - 2) {
                            jpegBuffer.put(0.toByte())
                        }
                        remainingPad -= padSize
                    }

                    // Write FF D9 at the exact end of the buffer
                    jpegBuffer.put(0xFF.toByte())
                    jpegBuffer.put(0xD9.toByte())
                } else {
                    if (toWrite > 0 && jpegBuffer.position() + toWrite <= bufCap) {
                        jpegBuffer.put(jpegBytes, vOffset, toWrite)
                    }
                }
                
                val finalLimit = jpegBuffer.position()
                
                // ONLY restore camera3_jpeg_blob if it natively existed in the buffer.
                try {
                    var blobFound = false
                    var blobOffset = -1
                    for (i in 0..blobBackup.size - 8) {
                        if (blobBackup[i] == 0xFF.toByte() && blobBackup[i+1] == 0x00.toByte()) {
                            blobBackup[i+4] = (finalLimit and 0xFF).toByte()
                            blobBackup[i+5] = ((finalLimit shr 8) and 0xFF).toByte()
                            blobBackup[i+6] = ((finalLimit shr 16) and 0xFF).toByte()
                            blobBackup[i+7] = ((finalLimit shr 24) and 0xFF).toByte()
                            blobFound = true
                            blobOffset = i
                            break
                        }
                    }
                    
                    if (blobFound) {
                        jpegBuffer.position(Math.max(0, jpegBuffer.capacity() - 32))
                        jpegBuffer.put(blobBackup)
                        val absOffset = Math.max(0, jpegBuffer.capacity() - 32) + blobOffset
                        Log.e(TAG, "[TELEMETRY] camera3_jpeg_blob [NATIVE] struct restored at absolute offset $absOffset. Size updated to $finalLimit")
                    } else {
                        Log.e(TAG, "[TELEMETRY] camera3_jpeg_blob [ABSENT]. Framework stripped it or it's not a blob buffer. Skipping blob injection.")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "[TELEMETRY] Failed to restore camera3_jpeg_blob", e)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[TELEMETRY] Failed to construct padded JPEG", e)
            }
            
            // Limit must ALWAYS be set to capacity if we padded it, or finalLimit if we didn't.
            jpegBuffer.position(0)
            jpegBuffer.limit(bufCap)
        }
            
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
            Log.e(TAG, "DIAGNOSTIC_VIRTUCAM: Failed to save debug image: ${e.message}")
        }
    }

    /**
     * Aggressively scrubs the target buffer to prevent real-world images from leaking
     * through if spoofing fails. We write a tiny, invalid/black JPEG payload.
     */
    private fun scrubBufferToBlack(buffer: ByteBuffer) {
        try {
            buffer.clear()
            val zeroArray = ByteArray(Math.min(1024, buffer.capacity()))
            var written = 0
            while (written < buffer.capacity()) {
                val toWrite = Math.min(zeroArray.size, buffer.capacity() - written)
                buffer.put(zeroArray, 0, toWrite)
                written += toWrite
            }
            // Add minimal JPEG header so the app doesn't crash on invalid data, just gets a black image.
            buffer.clear()
            val minJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
            buffer.put(minJpeg)
            buffer.position(0)
            buffer.limit(minJpeg.size)
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: Failed to scrub buffer to black", e)
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
            if (outputFormat == 256 || outputFormat == ImageFormat.JPEG || outputFormat == 35 || outputFormat == ImageFormat.YUV_420_888 || outputFormat == ImageFormat.YV12) {
                this.imageWriter = ImageWriter.newInstance(imageReader.surface, 1, outputFormat)
                
                if (pushThread == null) {
                    pushThread = HandlerThread("VirtuCamPushThread").apply { start() }
                    pushHandler = Handler(pushThread!!.looper)
                }
                
                Log.i(TAG, "FormatConverterBridge: Connected ImageWriter to ImageReader ${width}x${height} for format $outputFormat")
            } else {
                Log.i(TAG, "FormatConverterBridge: Connected to ImageReader ${width}x${height} for format $outputFormat (Skipping ImageWriter)")
            }
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: Failed to connect to ImageReader", e)
        }
    }

    @Volatile private var isReleasing = false

    /**
     * Asynchronously pushes the latest cached RGBA frame into the target ImageReader.
     * Prevents blocking the caller (dummySinkHandler) so hardware frames don't queue up.
     */
    fun asyncPushLatestFrameToWriter(timestamp: Long) {
        if (imageWriter == null || isReleasing) return
        writerExecutor.execute {
            if (isReleasing) return@execute
            pushLatestFrameToWriter(timestamp)
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
        
        Log.e(TAG, "CAPT_LOG [3b]: pushLatestFrameToWriter dequeueing and pushing frame synchronously on current thread. timestamp=$timestamp")
        try {
            val outImage = try { writer.dequeueInputImage() } catch (e: Exception) { 
                Log.e(TAG, "CAPT_LOG [3-ERR]: dequeueInputImage threw exception: ${e.message}")
                null 
            } ?: run {
                Log.e(TAG, "CAPT_LOG [3-ERR]: dequeueInputImage returned NULL! Buffer queue likely full or not ready.")
                return
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
        } catch (e: Exception) {
            Log.e(TAG, "CAPT_LOG [3-ERR]: pushLatestFrameToWriter failed: ${e.message}")
        }
    }

    fun release() {
        isReleasing = true
        try {
            // [HARDENING] Gracefully shut down the executor and wait to prevent Use-After-Free native crashes!
            writerExecutor.shutdownNow()
            try {
                writerExecutor.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS)
            } catch (e: Exception) {
                // Ignore
            }

            imageWriter?.close()
            imageWriter = null
            imageReader?.close()
            handlerThread?.quitSafely()
            pushThread?.quitSafely()
            imageReader = null
            
            cachedBitmap?.recycle()
            cachedBitmap = null
            cachedRotatedBitmap?.recycle()
            cachedRotatedBitmap = null
            cachedBaos = null
        } catch (t: Throwable) {
            Log.e(TAG, "Error during Bridge release", t)
        }
    }
}



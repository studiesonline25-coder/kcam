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
    
    private var pushThread: HandlerThread? = null
    private var pushHandler: Handler? = null
    
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
            
            pushThread = HandlerThread("VirtuCamPushThread").apply { start() }
            pushHandler = Handler(pushThread!!.looper)
            
            if (outputSurface != null) {
                try {
                    imageWriter = android.media.ImageWriter.newInstance(outputSurface, 5)
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
                        var wBuf = writeBuffer
                        if (wBuf == null || wBuf.size != expectedSize) {
                            wBuf = ByteArray(expectedSize)
                            // Pre-fill with black (RGBA 0,0,0,255) to prevent green artifacts
                            for (i in 0 until expectedSize step 4) {
                                wBuf[i] = 0; wBuf[i+1] = 0; wBuf[i+2] = 0; wBuf[i+3] = 255.toByte()
                            }
                            writeBuffer = wBuf
                            readyBuffer = wBuf.copyOf()
                            conversionBuffer = wBuf.copyOf()
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
        val checkSize = 1024.coerceAtMost(data.size)
        for (i in 0 until checkSize) {
            if (data[i] != 0.toByte()) return true
        }
        return false
    }

    private fun generateAndStoreSpoofedJpeg() {
        if (!isBufferReady || readyBuffer == null || conversionBuffer == null) return
        
        val w = width
        val h = height
        val expectedSize = w * h * 4
        
        synchronized(bufferLock) {
            System.arraycopy(readyBuffer!!, 0, conversionBuffer!!, 0, expectedSize)
        }
        val rgbaBytes = conversionBuffer!!
        
        if (!checkDataIntegrity(rgbaBytes)) return
        if (CameraHook.isGeneratingJpeg) return
        
        Thread {
            try {
                CameraHook.isGeneratingJpeg = true
                val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val rgbaBuffer = ByteBuffer.wrap(rgbaBytes)
                bitmap.copyPixelsFromBuffer(rgbaBuffer)
                
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, baos)
                bitmap.recycle()
                
                var jpegBytes = baos.toByteArray()
                val area = w * h
                synchronized(CameraHook) {
                    if (area >= CameraHook.latestVirtualJpegArea) {
                        CameraHook.latestVirtualJpeg = jpegBytes
                        CameraHook.latestVirtualJpegArea = area
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Async JPEG generation failed", e)
            } finally {
                CameraHook.isGeneratingJpeg = false
            }
        }.start()
    }

    fun overwriteImageWithLatestYuv(targetImage: Image, timestamp: Long) {
        if (!isBufferReady || readyBuffer == null || conversionBuffer == null) {
            // Green Screen Prevention: Use last good frame if current is missing
            if (conversionBuffer == null) return
        } else {
            val expectedSize = width * height * 4
            synchronized(bufferLock) {
                System.arraycopy(readyBuffer!!, 0, conversionBuffer!!, 0, expectedSize)
            }
        }
        val rgbaBytes = conversionBuffer!!
        if (!checkDataIntegrity(rgbaBytes)) return

        try {
            val w = targetImage.width
            val h = targetImage.height
            val planes = targetImage.planes
            val format = targetImage.format
            
            val yPlane = planes[0]
            val yBuffer = yPlane.buffer
            val yRowStride = yPlane.rowStride
            val yPixStride = yPlane.pixelStride
            
            val srcStrideArr = width * 4
            val processH = h.coerceAtMost(height)
            val processW = w.coerceAtMost(width)
            val noiseSeed = (timestamp % 256).toInt()
            
            for (ty in 0 until processH) {
                val rowPos = ty * yRowStride
                val srcRowBase = ty * srcStrideArr
                val rowNoiseOff = (ty + noiseSeed) % 128 
                
                for (tx in 0 until processW) {
                    val rgbaOff = srcRowBase + (tx * 4)
                    if (rgbaOff + 3 < rgbaBytes.size) {
                        val r = rgbaBytes[rgbaOff].toInt() and 0xFF
                        val g = rgbaBytes[rgbaOff+1].toInt() and 0xFF
                        val b = rgbaBytes[rgbaOff+2].toInt() and 0xFF
                        
                        var y = ((54 * r + 183 * g + 18 * b + 128) shr 8) + 16
                        val pixelNoise = ((tx + rowNoiseOff) xor noiseSeed) and 0x03
                        val noiseOffset = when (pixelNoise) {
                            0 -> -1
                            1 -> 1
                            else -> 0
                        }
                        y += noiseOffset
                        
                        val pos = rowPos + (tx * yPixStride)
                        if (pos < yBuffer.capacity()) {
                            yBuffer.put(pos, y.coerceIn(16, 235).toByte())
                        }
                    }
                }
            }

            // Chroma processing (NV21/YUV420P)
            val isYV12 = (format == 842094169)
            val isNv21 = (format == 0x11)
            val isSemiPlanar = (planes.size == 2)

            if (isSemiPlanar) {
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
            }
        } catch (e: Exception) {
            Log.e(TAG, "FormatConverterBridge: Error overwriting capture buffer", e)
        }
    }

    fun overwriteImageWithLatestJpeg(targetImage: Image) {
        if (!isBufferReady || readyBuffer == null) return
        val planes = targetImage.planes
        if (planes.isEmpty()) return
        val jpegBuffer = planes[0].buffer
        generateAndStoreSpoofedJpeg()
        val jpegBytes = CameraHook.latestVirtualJpeg ?: return
        jpegBuffer.clear()
        val bytesToWrite = jpegBytes.size.coerceAtMost(jpegBuffer.capacity())
        jpegBuffer.put(jpegBytes, 0, bytesToWrite)
        jpegBuffer.position(0)
        jpegBuffer.limit(bytesToWrite)
    }

    fun pushLatestFrameToWriter(timestamp: Long) {
        val writer = imageWriter ?: return
        pushHandler?.post {
            try {
                val outImage = try { writer.dequeueInputImage() } catch (e: Exception) { null } ?: return@post
                try {
                    outImage.timestamp = timestamp
                    if (outImage.format == 256) overwriteImageWithLatestJpeg(outImage)
                    else overwriteImageWithLatestYuv(outImage, timestamp)
                    writer.queueInputImage(outImage)
                } catch (e: Exception) {
                    try { outImage.close() } catch (_: Exception) {}
                }
            } catch (_: Exception) {}
        }
    }

    fun release() {
        imageWriter?.close()
        imageWriter = null
        imageReader?.close()
        handlerThread?.quitSafely()
        pushThread?.quitSafely()
        imageReader = null
    }
}

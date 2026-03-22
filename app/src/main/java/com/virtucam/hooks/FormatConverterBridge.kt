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
            
            var jpegBytes = baos.toByteArray()
            
            // Extract original JPEG bytes from buffer to preserve EXIF (Rotation, OEM tags)
            val originalLimit = jpegBuffer.limit()
            jpegBuffer.position(0)
            val originalJpegBytes = ByteArray(originalLimit)
            jpegBuffer.get(originalJpegBytes)
            
            // Transplant the original EXIF header into our spoofed JPEG
            jpegBytes = ExifUtils.transplantExif(originalJpegBytes, jpegBytes)
            
            // Write Spoofed+EXIF JPEG into the image buffer
            jpegBuffer.clear()
            val bytesToWrite = jpegBytes.size.coerceAtMost(jpegBuffer.capacity())
            jpegBuffer.put(jpegBytes, 0, bytesToWrite)
            
            // Do NOT flip. We want the camera framework to read up to its native size.
            // Appending our JPEG at the start of the buffer is enough; BitmapFactory stops at EOF.
            jpegBuffer.position(0)
            jpegBuffer.limit(bytesToWrite)
            
            Log.d(TAG, "FormatConverterBridge: Overwrote JPEG image (${jpegBytes.size} bytes) with preserved EXIF")
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

/**
 * Utility to byte-splice EXIF APP1 segments from one JPEG to another.
 * Prevents EXIF loss which causes Images to lose Rotation and other OEM data,
 * and stops strict Gallery apps from deleting 'corrupt' files.
 */
private object ExifUtils {

    fun transplantExif(originalJpeg: ByteArray, spoofedJpeg: ByteArray): ByteArray {
        val originalExif = extractApp1Prefix(originalJpeg) ?: return spoofedJpeg
        return insertApp1Prefix(spoofedJpeg, originalExif)
    }

    private fun extractApp1Prefix(jpeg: ByteArray): ByteArray? {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return null

        var offset = 2
        while (offset + 4 < jpeg.size) {
            val marker = jpeg[offset + 1].toInt() and 0xFF
            val length = ((jpeg[offset + 2].toInt() and 0xFF) shl 8) or (jpeg[offset + 3].toInt() and 0xFF)

            if (marker == 0xE1) { // APP1 EXIF marker
                val exifEnd = offset + 2 + length
                if (exifEnd <= jpeg.size) {
                    val exifChunk = ByteArray(2 + length)
                    System.arraycopy(jpeg, offset, exifChunk, 0, exifChunk.size)
                    // Check if it's actually an Exif header
                    if (exifChunk.size >= 10 &&
                        exifChunk[4] == 'E'.toByte() &&
                        exifChunk[5] == 'x'.toByte() &&
                        exifChunk[6] == 'i'.toByte() &&
                        exifChunk[7] == 'f'.toByte()) {
                        return exifChunk
                    }
                }
            } else if (marker == 0xDA || marker == 0xD9) { // SOS or EOI
                break
            }
            offset += 2 + length
        }
        return null
    }

    private fun insertApp1Prefix(jpeg: ByteArray, exifChunk: ByteArray): ByteArray {
        if (jpeg.size < 4 || jpeg[0] != 0xFF.toByte() || jpeg[1] != 0xD8.toByte()) return jpeg

        val baos = ByteArrayOutputStream(jpeg.size + exifChunk.size)
        baos.write(0xFF)
        baos.write(0xD8)

        var offset = 2
        var injected = false

        while (offset + 4 < jpeg.size) {
            if (jpeg[offset] != 0xFF.toByte()) break

            val marker = jpeg[offset + 1].toInt() and 0xFF
            val length = ((jpeg[offset + 2].toInt() and 0xFF) shl 8) or (jpeg[offset + 3].toInt() and 0xFF)

            if (marker == 0xE0 && !injected) { // APP0 JFIF, skip or keep, usually we inject EXIF after JFIF or replace it
                // We keep JFIF and append EXIF immediately after
                baos.write(jpeg, offset, 2 + length)
                baos.write(exifChunk)
                injected = true
            } else if (marker == 0xE1) {
                // If the spoofed JPEG already has an EXIF, we skip writing the original spoofed one
                // and write our injected one instead if we haven't already.
                if (!injected) {
                    baos.write(exifChunk)
                    injected = true
                }
            } else {
                if (!injected && marker != 0xD8 && marker != 0xD9 && marker != 0x00) {
                    baos.write(exifChunk)
                    injected = true
                }
                baos.write(jpeg, offset, 2 + length)
            }
            
            if (marker == 0xDA) { // Start Of Scan (Image Data)
                offset += 2 + length
                break
            }
            offset += 2 + length
        }

        // Copy the rest of the image data
        if (offset < jpeg.size) {
            baos.write(jpeg, offset, jpeg.size - offset)
        }

        return baos.toByteArray()
    }
}

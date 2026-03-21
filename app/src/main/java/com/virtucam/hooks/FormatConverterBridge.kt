package com.virtucam.hooks

import android.graphics.ImageFormat
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import android.media.ImageWriter
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer

/**
 * Bridges RGBA_8888 output from VirtualRenderThread into whatever YUV format the app's ImageReader expects.
 * This prevents the "UnsupportedOperationException: buffer format 0x1 doesn't match 0x32315659" crash natively.
 */
class FormatConverterBridge(
    private val targetSurface: Surface,
    private val width: Int,
    private val height: Int
) {
    companion object {
        private const val TAG = "VirtuCam_Bridge"
    }

    private var imageReader: ImageReader? = null
    private var imageWriter: ImageWriter? = null
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null

    // This is the surface we hand to the VirtualRenderThread (it receives RGBA from OpenGL)
    val inputSurface: Surface?
        get() = imageReader?.surface

    init {
        try {
            // Instantiate ImageWriter wrapping the app's original surface. Max images = 2 (standard double-buffering)
            imageWriter = ImageWriter.newInstance(targetSurface, 2)
            val format = imageWriter?.format ?: ImageFormat.UNKNOWN
            Log.d(TAG, "Initialized ImageWriter with format: 0x${Integer.toHexString(format)} for size ${width}x${height}")
            
            // Optimization: If the target format is PRIVATE (0x22) or RGBA_8888 (0x1), 
            // OpenGL can render to it natively without format crashing. We abort the CPU bridge.
            if (format == 0x22 || format == 0x1) {
                Log.d(TAG, "Format 0x${Integer.toHexString(format)} natively supports EGL. Bypassing CPU bridge.")
                release()
                return
            }
            
            // If the app expects something strict like YUV_420_888,
            // we configure our receiver ImageReader to RGBA_8888 since that's what OpenGL natively produces.
            imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            
            handlerThread = HandlerThread("VirtuCamConversionThread").apply { start() }
            handler = Handler(handlerThread!!.looper)
            
            imageReader?.setOnImageAvailableListener({ reader ->
                val rgbaImage = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                var yuvImage: Image? = null
                try {
                    // Pull a blank, natively formatted buffer from the app's target surface queue
                    yuvImage = imageWriter?.dequeueInputImage()
                    if (yuvImage != null) {
                        convertRgbaToYuvFast(rgbaImage, yuvImage)
                        // Feed the correctly formatted buffer back into the app's queue
                        imageWriter?.queueInputImage(yuvImage)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Bridge loop error", e)
                    // If queueing fails, we have to abort the image
                } finally {
                    rgbaImage.close()
                }
            }, handler)
            Log.d(TAG, "FormatConverterBridge started successfully. Buffer bridge active.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize FormatConverterBridge", e)
            release()
        }
    }

    /**
     * Converts RGBA pixels to the planes of the target image.
     * Handles standard YUV_420_888 and YV12 formats manually in an optimized loop.
     */
    private fun convertRgbaToYuvFast(rgbaImage: Image, targetImage: Image) {
        val rgbaPlanes = rgbaImage.planes
        val rgbaBuffer = rgbaPlanes[0].buffer
        
        // Ensure buffers have enough data
        if (rgbaBuffer.remaining() < (width * height * 4)) {
            return
        }
        
        val rgbaRowStride = rgbaPlanes[0].rowStride
        val rgbaPixelStride = rgbaPlanes[0].pixelStride
        
        val targetPlanes = targetImage.planes
        if (targetPlanes.size < 3) return
        
        val yBuffer = targetPlanes[0].buffer
        val yRowStride = targetPlanes[0].rowStride
        
        val uBuffer = targetPlanes[1].buffer
        val uRowStride = targetPlanes[1].rowStride
        val uvPixelStride = targetPlanes[1].pixelStride
        
        val vBuffer = targetPlanes[2].buffer
        val vRowStride = targetPlanes[2].rowStride

        // Convert the RGBA to simple ByteArrays
        val rgbaBytes = ByteArray(rgbaRowStride * height)
        rgbaBuffer.position(0)
        rgbaBuffer.get(rgbaBytes, 0, rgbaBytes.size)
        
        val yBytes = ByteArray(yBuffer.capacity())
        val uBytes = ByteArray(uBuffer.capacity())
        val vBytes = ByteArray(vBuffer.capacity())
        
        for (row in 0 until height) {
            var rgbaOffset = row * rgbaRowStride
            var yIndex = row * yRowStride
            
            val isEvenRow = (row % 2 == 0)
            var uIndex = (row / 2) * uRowStride
            var vIndex = (row / 2) * vRowStride
            
            for (col in 0 until width) {
                // RGBA
                val r = rgbaBytes[rgbaOffset].toInt() and 0xFF
                val g = rgbaBytes[rgbaOffset + 1].toInt() and 0xFF
                val b = rgbaBytes[rgbaOffset + 2].toInt() and 0xFF
                
                // standard BT.601 math
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBytes[yIndex++] = y.coerceIn(16, 235).toByte()
                
                if (isEvenRow && (col % 2 == 0)) {
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    
                    if (uIndex < uBytes.size) uBytes[uIndex] = u.coerceIn(16, 240).toByte()
                    if (vIndex < vBytes.size) vBytes[vIndex] = v.coerceIn(16, 240).toByte()
                    
                    uIndex += uvPixelStride
                    vIndex += uvPixelStride
                }
                
                rgbaOffset += rgbaPixelStride
            }
        }
        
        yBuffer.clear()
        yBuffer.put(yBytes, 0, yBytes.size.coerceAtMost(yBuffer.capacity()))
        uBuffer.clear()
        uBuffer.put(uBytes, 0, uBytes.size.coerceAtMost(uBuffer.capacity()))
        vBuffer.clear()
        vBuffer.put(vBytes, 0, vBytes.size.coerceAtMost(vBuffer.capacity()))
    }
    
    fun release() {
        try {
            imageReader?.close()
            imageWriter?.close()
            handlerThread?.quitSafely()
            imageReader = null
            imageWriter = null
        } catch (e: Exception) {
            Log.e(TAG, "Error during Bridge release", e)
        }
    }
}

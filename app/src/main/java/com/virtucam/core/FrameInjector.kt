package com.virtucam.core

import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.media.Image
import android.util.Log
import com.virtucam.ModuleMain
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * Frame Injector
 * 
 * FIX 3: All YUV/NV21/ARGB buffers are pre-allocated once per resolution
 * and reused every frame. Zero allocations inside the frame loop.
 */
class FrameInjector {
    
    companion object {
        private const val TAG = "FrameInjector"
    }

    // FIX 3: Pre-allocated buffers — never allocate inside the frame loop
    private var cachedWidth = 0
    private var cachedHeight = 0
    private var cachedYuvData: ByteArray? = null
    private var cachedNv21Data: ByteArray? = null
    private var cachedArgbPixels: IntArray? = null

    /** Ensure cached buffers match the target resolution */
    private fun ensureBuffers(width: Int, height: Int) {
        if (width == cachedWidth && height == cachedHeight && cachedArgbPixels != null) return
        cachedWidth = width
        cachedHeight = height
        val ySize = width * height
        val uvSize420 = width * height / 4
        val uvSizeNv21 = width * height / 2
        cachedYuvData = ByteArray(ySize + uvSize420 * 2)
        cachedNv21Data = ByteArray(ySize + uvSizeNv21)
        cachedArgbPixels = IntArray(width * height)
        Log.d(TAG, "FIX3: Pre-allocated buffers for ${width}x${height} (yuv=${cachedYuvData!!.size}, nv21=${cachedNv21Data!!.size}, argb=${cachedArgbPixels!!.size})")
    }

    fun injectFrame(image: Image, bitmap: Bitmap) {
        when (image.format) {
            ImageFormat.YUV_420_888 -> injectYUV420(image, bitmap)
            ImageFormat.JPEG -> injectJPEG(image, bitmap)
            ImageFormat.NV21 -> injectNV21(image, bitmap)
            else -> Log.e(TAG, "Unsupported image format: ${image.format}")
        }
    }
    
    private fun injectYUV420(image: Image, bitmap: Bitmap) {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, image.width, image.height, true)
        
        rgbToYuv420(scaledBitmap)
        val yuvData = cachedYuvData!!
        
        val planes = image.planes
        val width = image.width
        val height = image.height
        val ySize = width * height
        val uvSize = width * height / 4
        
        val yBuffer = planes[0].buffer
        if (yBuffer.remaining() >= ySize) yBuffer.put(yuvData, 0, minOf(ySize, yBuffer.remaining()))
        
        val uBuffer = planes[1].buffer
        if (uBuffer.remaining() >= uvSize) uBuffer.put(yuvData, ySize, minOf(uvSize, uBuffer.remaining()))
        
        val vBuffer = planes[2].buffer
        if (vBuffer.remaining() >= uvSize) vBuffer.put(yuvData, ySize + uvSize, minOf(uvSize, vBuffer.remaining()))
        
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
    }
    
    private fun injectJPEG(image: Image, bitmap: Bitmap) {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, image.width, image.height, true)
        val outputStream = ByteArrayOutputStream()
        scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        val jpegData = outputStream.toByteArray()
        val buffer = image.planes[0].buffer
        if (buffer.remaining() >= jpegData.size) buffer.put(jpegData)
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
    }
    
    private fun injectNV21(image: Image, bitmap: Bitmap) {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, image.width, image.height, true)
        
        rgbToNV21(scaledBitmap)
        val nv21Data = cachedNv21Data!!
        
        val yBuffer = image.planes[0].buffer
        val vuBuffer = image.planes[1].buffer
        val ySize = image.width * image.height
        
        yBuffer.put(nv21Data, 0, minOf(ySize, yBuffer.remaining()))
        vuBuffer.put(nv21Data, ySize, minOf(nv21Data.size - ySize, vuBuffer.remaining()))
        
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
    }
    
    /** FIX 3: Writes into pre-allocated cachedYuvData — zero allocs */
    private fun rgbToYuv420(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        ensureBuffers(width, height)
        
        val yuvData = cachedYuvData!!
        val argbPixels = cachedArgbPixels!!
        bitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
        
        val ySize = width * height
        val uvSize = width * height / 4
        var yIndex = 0
        var uIndex = ySize
        var vIndex = ySize + uvSize
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val argb = argbPixels[j * width + i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                
                yuvData[yIndex++] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                
                if (j % 2 == 0 && i % 2 == 0) {
                    if (uIndex < ySize + uvSize) yuvData[uIndex++] = clamp(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                    if (vIndex < yuvData.size)    yuvData[vIndex++] = clamp(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                }
            }
        }
    }
    
    /** FIX 3: Writes into pre-allocated cachedNv21Data — zero allocs */
    private fun rgbToNV21(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        ensureBuffers(width, height)
        
        val nv21Data = cachedNv21Data!!
        val argbPixels = cachedArgbPixels!!
        bitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
        
        val ySize = width * height
        var yIndex = 0
        var uvIndex = ySize
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val argb = argbPixels[j * width + i]
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                
                nv21Data[yIndex++] = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
                
                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < nv21Data.size) {
                    nv21Data[uvIndex++] = clamp(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                    nv21Data[uvIndex++] = clamp(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                }
            }
        }
    }
    
    private fun clamp(value: Int): Int = when {
        value < 0 -> 0
        value > 255 -> 255
        else -> value
    }
    
    fun createYuvBuffer(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        rgbToYuv420(scaledBitmap)
        if (scaledBitmap != bitmap) scaledBitmap.recycle()
        return ByteBuffer.wrap(cachedYuvData!!.copyOf())
    }
}

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
 * Converts Bitmap images to camera frame format (YUV_420_888) and injects them
 * into the camera pipeline. This allows us to replace live camera frames with
 * our custom image.
 */
class FrameInjector {
    
    companion object {
        private const val TAG = "FrameInjector"
    }
    
    /**
     * Inject a bitmap into a camera Image object
     * This modifies the image planes to contain our bitmap data
     */
    fun injectFrame(image: Image, bitmap: Bitmap) {
        when (image.format) {
            ImageFormat.YUV_420_888 -> injectYUV420(image, bitmap)
            ImageFormat.JPEG -> injectJPEG(image, bitmap)
            ImageFormat.NV21 -> injectNV21(image, bitmap)
            else -> Log.e(TAG, "Unsupported image format: ${image.format}")
        }
    }
    
    /**
     * Inject bitmap as YUV_420_888 format (most common for camera preview)
     */
    private fun injectYUV420(image: Image, bitmap: Bitmap) {
        try {
            // Scale bitmap to match image dimensions
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap, 
                image.width, 
                image.height, 
                true
            )
            
            // Convert ARGB to YUV
            val yuvData = rgbToYuv420(scaledBitmap)
            
            // Get image planes
            val planes = image.planes
            val yPlane = planes[0] // Y plane
            val uPlane = planes[1] // U plane  
            val vPlane = planes[2] // V plane
            
            // Calculate plane sizes
            val width = image.width
            val height = image.height
            val ySize = width * height
            val uvSize = width * height / 4
            
            // Copy Y data
            val yBuffer = yPlane.buffer
            if (yBuffer.remaining() >= ySize) {
                yBuffer.put(yuvData, 0, minOf(ySize, yBuffer.remaining()))
            }
            
            // Copy U data
            val uBuffer = uPlane.buffer
            if (uBuffer.remaining() >= uvSize) {
                uBuffer.put(yuvData, ySize, minOf(uvSize, uBuffer.remaining()))
            }
            
            // Copy V data
            val vBuffer = vPlane.buffer
            if (vBuffer.remaining() >= uvSize) {
                vBuffer.put(yuvData, ySize + uvSize, minOf(uvSize, vBuffer.remaining()))
            }
            
            // Clean up scaled bitmap if different from original
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject YUV420", e)
        }
    }
    
    /**
     * Inject bitmap as JPEG format
     */
    private fun injectJPEG(image: Image, bitmap: Bitmap) {
        try {
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                image.width,
                image.height,
                true
            )
            
            // Compress to JPEG
            val outputStream = ByteArrayOutputStream()
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
            val jpegData = outputStream.toByteArray()
            
            // Write to image plane
            val buffer = image.planes[0].buffer
            if (buffer.remaining() >= jpegData.size) {
                buffer.put(jpegData)
            }
            
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject JPEG", e)
        }
    }
    
    /**
     * Inject bitmap as NV21 format
     */
    private fun injectNV21(image: Image, bitmap: Bitmap) {
        try {
            val scaledBitmap = Bitmap.createScaledBitmap(
                bitmap,
                image.width,
                image.height,
                true
            )
            
            val nv21Data = rgbToNV21(scaledBitmap)
            
            // NV21 typically has 2 planes: Y and interleaved VU
            val yBuffer = image.planes[0].buffer
            val vuBuffer = image.planes[1].buffer
            
            val ySize = image.width * image.height
            
            yBuffer.put(nv21Data, 0, minOf(ySize, yBuffer.remaining()))
            vuBuffer.put(nv21Data, ySize, minOf(nv21Data.size - ySize, vuBuffer.remaining()))
            
            if (scaledBitmap != bitmap) {
                scaledBitmap.recycle()
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to inject frame in current format", e)
        }
    }
    
    /**
     * Convert ARGB Bitmap to YUV420 byte array
     */
    private fun rgbToYuv420(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        
        val ySize = width * height
        val uvSize = width * height / 4
        val yuvData = ByteArray(ySize + uvSize * 2)
        
        val argbPixels = IntArray(width * height)
        bitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
        
        var yIndex = 0
        var uIndex = ySize
        var vIndex = ySize + uvSize
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val argb = argbPixels[j * width + i]
                
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                
                // Convert RGB to YUV
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                
                // Store Y for every pixel
                yuvData[yIndex++] = clamp(y).toByte()
                
                // Store U and V for every 2x2 block
                if (j % 2 == 0 && i % 2 == 0) {
                    if (uIndex < ySize + uvSize) {
                        yuvData[uIndex++] = clamp(u).toByte()
                    }
                    if (vIndex < yuvData.size) {
                        yuvData[vIndex++] = clamp(v).toByte()
                    }
                }
            }
        }
        
        return yuvData
    }
    
    /**
     * Convert ARGB Bitmap to NV21 byte array (Y plane + interleaved VU)
     */
    private fun rgbToNV21(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        
        val ySize = width * height
        val uvSize = width * height / 2
        val nv21Data = ByteArray(ySize + uvSize)
        
        val argbPixels = IntArray(width * height)
        bitmap.getPixels(argbPixels, 0, width, 0, 0, width, height)
        
        var yIndex = 0
        var uvIndex = ySize
        
        for (j in 0 until height) {
            for (i in 0 until width) {
                val argb = argbPixels[j * width + i]
                
                val r = (argb shr 16) and 0xFF
                val g = (argb shr 8) and 0xFF
                val b = argb and 0xFF
                
                val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                
                nv21Data[yIndex++] = clamp(y).toByte()
                
                // NV21: V comes before U in interleaved plane
                if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < nv21Data.size) {
                    nv21Data[uvIndex++] = clamp(v).toByte()
                    nv21Data[uvIndex++] = clamp(u).toByte()
                }
            }
        }
        
        return nv21Data
    }
    
    /**
     * Clamp value to byte range (0-255)
     */
    private fun clamp(value: Int): Int {
        return when {
            value < 0 -> 0
            value > 255 -> 255
            else -> value
        }
    }
    
    /**
     * Create a YUV buffer from bitmap for direct surface rendering
     */
    fun createYuvBuffer(bitmap: Bitmap, targetWidth: Int, targetHeight: Int): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
        val yuvData = rgbToYuv420(scaledBitmap)
        
        if (scaledBitmap != bitmap) {
            scaledBitmap.recycle()
        }
        
        return ByteBuffer.wrap(yuvData)
    }
}

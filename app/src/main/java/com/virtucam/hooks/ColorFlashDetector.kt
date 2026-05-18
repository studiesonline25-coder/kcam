package com.virtucam.hooks

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects color flash challenges (green circles, red backgrounds, etc.)
 * Used by KYC providers for liveness detection
 * 
 * Analyzes screen content to detect dominant colors and adjusts video accordingly
 */
class ColorFlashDetector {
    
    companion object {
        private const val TAG = "VirtuCam_ColorFlash"
        
        // Color thresholds
        private const val GREEN_THRESHOLD = 0.6f  // 60% green dominance
        private const val RED_THRESHOLD = 0.6f
        private const val BLUE_THRESHOLD = 0.6f
        private const val WHITE_THRESHOLD = 0.8f  // 80% brightness for white
        
        // Sampling parameters
        private const val SAMPLE_SIZE = 100  // Sample 100 pixels
        private const val EDGE_MARGIN = 50   // Sample from screen edges (where UI elements are)
    }
    
    data class ColorFlash(
        val type: FlashType,
        val intensity: Float,  // 0.0 to 1.0
        val confidence: Float  // How confident we are this is a flash
    )
    
    enum class FlashType {
        NONE,
        GREEN,
        RED,
        BLUE,
        WHITE,
        YELLOW,
        CYAN,
        MAGENTA
    }
    
    /**
     * Analyzes a frame to detect if there's a color flash on screen
     * This would typically analyze the UI overlay, not the camera feed
     * 
     * Note: In Xposed context, we can't easily capture the screen,
     * so we'll need to hook into the WebView or detect via other means
     */
    fun detectFlash(frame: Bitmap): ColorFlash {
        val colors = sampleColors(frame)
        return analyzeColors(colors)
    }
    
    /**
     * Sample colors from frame edges (where UI overlays typically appear)
     */
    private fun sampleColors(frame: Bitmap): List<Int> {
        val colors = mutableListOf<Int>()
        val width = frame.width
        val height = frame.height
        
        if (width == 0 || height == 0) return colors
        
        // Sample from edges (top, bottom, left, right)
        // This is where color flash overlays typically appear
        
        // Top edge
        for (i in 0 until SAMPLE_SIZE / 4) {
            val x = (i * width / (SAMPLE_SIZE / 4)).coerceIn(0, width - 1)
            val y = EDGE_MARGIN.coerceIn(0, height - 1)
            colors.add(frame.getPixel(x, y))
        }
        
        // Bottom edge
        for (i in 0 until SAMPLE_SIZE / 4) {
            val x = (i * width / (SAMPLE_SIZE / 4)).coerceIn(0, width - 1)
            val y = (height - EDGE_MARGIN).coerceIn(0, height - 1)
            colors.add(frame.getPixel(x, y))
        }
        
        // Left edge
        for (i in 0 until SAMPLE_SIZE / 4) {
            val x = EDGE_MARGIN.coerceIn(0, width - 1)
            val y = (i * height / (SAMPLE_SIZE / 4)).coerceIn(0, height - 1)
            colors.add(frame.getPixel(x, y))
        }
        
        // Right edge
        for (i in 0 until SAMPLE_SIZE / 4) {
            val x = (width - EDGE_MARGIN).coerceIn(0, width - 1)
            val y = (i * height / (SAMPLE_SIZE / 4)).coerceIn(0, height - 1)
            colors.add(frame.getPixel(x, y))
        }
        
        return colors
    }
    
    /**
     * Analyze sampled colors to detect dominant color flash
     */
    private fun analyzeColors(colors: List<Int>): ColorFlash {
        if (colors.isEmpty()) {
            return ColorFlash(FlashType.NONE, 0f, 0f)
        }
        
        // Calculate average RGB
        var totalR = 0f
        var totalG = 0f
        var totalB = 0f
        
        colors.forEach { color ->
            totalR += Color.red(color) / 255f
            totalG += Color.green(color) / 255f
            totalB += Color.blue(color) / 255f
        }
        
        val avgR = totalR / colors.size
        val avgG = totalG / colors.size
        val avgB = totalB / colors.size
        
        val brightness = (avgR + avgG + avgB) / 3f
        
        // Detect white flash (all channels high)
        if (avgR > WHITE_THRESHOLD && avgG > WHITE_THRESHOLD && avgB > WHITE_THRESHOLD) {
            return ColorFlash(FlashType.WHITE, brightness, 0.9f)
        }
        
        // Detect primary color flashes
        val maxChannel = maxOf(avgR, avgG, avgB)
        val minChannel = minOf(avgR, avgG, avgB)
        val colorSaturation = maxChannel - minChannel
        
        // Need significant saturation to be a color flash
        if (colorSaturation < 0.3f) {
            return ColorFlash(FlashType.NONE, 0f, 0f)
        }
        
        // Determine dominant color
        return when {
            avgG > avgR && avgG > avgB && avgG > GREEN_THRESHOLD -> {
                ColorFlash(FlashType.GREEN, avgG, colorSaturation)
            }
            avgR > avgG && avgR > avgB && avgR > RED_THRESHOLD -> {
                ColorFlash(FlashType.RED, avgR, colorSaturation)
            }
            avgB > avgR && avgB > avgG && avgB > BLUE_THRESHOLD -> {
                ColorFlash(FlashType.BLUE, avgB, colorSaturation)
            }
            avgR > 0.6f && avgG > 0.6f && avgB < 0.4f -> {
                ColorFlash(FlashType.YELLOW, (avgR + avgG) / 2f, colorSaturation)
            }
            avgG > 0.6f && avgB > 0.6f && avgR < 0.4f -> {
                ColorFlash(FlashType.CYAN, (avgG + avgB) / 2f, colorSaturation)
            }
            avgR > 0.6f && avgB > 0.6f && avgG < 0.4f -> {
                ColorFlash(FlashType.MAGENTA, (avgR + avgB) / 2f, colorSaturation)
            }
            else -> {
                ColorFlash(FlashType.NONE, 0f, 0f)
            }
        }
    }
    
    /**
     * Apply color adjustment to video frame based on detected flash
     * This makes the face appear to reflect the ambient light
     */
    fun applyColorAdjustment(
        frameData: ByteArray,
        width: Int,
        height: Int,
        flash: ColorFlash,
        format: Int = android.graphics.ImageFormat.NV21
    ): ByteArray {
        if (flash.type == FlashType.NONE || flash.confidence < 0.3f) {
            return frameData // No adjustment needed
        }
        
        Log.d(TAG, "Applying color adjustment: ${flash.type} (intensity=${flash.intensity}, confidence=${flash.confidence})")
        
        // For NV21 format (YUV)
        // Y = brightness (first width*height bytes)
        // UV = chrominance (remaining bytes)
        
        val adjustedData = frameData.copyOf()
        val ySize = width * height
        
        // Adjustment strength (0.1 to 0.3 for natural look)
        val strength = (flash.intensity * flash.confidence * 0.2f).coerceIn(0.05f, 0.25f)
        
        when (flash.type) {
            FlashType.GREEN -> {
                // Increase green channel in UV plane
                adjustUVPlane(adjustedData, ySize, strength, 0f, 1f, 0f)
            }
            FlashType.RED -> {
                // Increase red channel
                adjustUVPlane(adjustedData, ySize, strength, 1f, 0f, 0f)
            }
            FlashType.BLUE -> {
                // Increase blue channel
                adjustUVPlane(adjustedData, ySize, strength, 0f, 0f, 1f)
            }
            FlashType.WHITE -> {
                // Increase brightness (Y channel)
                adjustYPlane(adjustedData, ySize, strength)
            }
            FlashType.YELLOW -> {
                adjustUVPlane(adjustedData, ySize, strength, 1f, 1f, 0f)
            }
            FlashType.CYAN -> {
                adjustUVPlane(adjustedData, ySize, strength, 0f, 1f, 1f)
            }
            FlashType.MAGENTA -> {
                adjustUVPlane(adjustedData, ySize, strength, 1f, 0f, 1f)
            }
            else -> {}
        }
        
        return adjustedData
    }
    
    /**
     * Adjust Y (brightness) plane
     */
    private fun adjustYPlane(data: ByteArray, ySize: Int, strength: Float) {
        for (i in 0 until ySize) {
            val y = data[i].toInt() and 0xFF
            val adjusted = (y + (255 - y) * strength).toInt().coerceIn(0, 255)
            data[i] = adjusted.toByte()
        }
    }
    
    /**
     * Adjust UV (chrominance) plane for color tinting
     * This is a simplified approach - proper YUV color adjustment is more complex
     */
    private fun adjustUVPlane(
        data: ByteArray,
        ySize: Int,
        strength: Float,
        r: Float,
        g: Float,
        b: Float
    ) {
        // Convert RGB to UV offset
        // This is a simplified conversion - real YUV color space is more complex
        val uOffset = ((b - g) * strength * 127).toInt()
        val vOffset = ((r - g) * strength * 127).toInt()
        
        // Adjust UV plane
        for (i in ySize until data.size step 2) {
            // U component
            val u = data[i].toInt() and 0xFF
            val adjustedU = (u + uOffset).coerceIn(0, 255)
            data[i] = adjustedU.toByte()
            
            // V component
            if (i + 1 < data.size) {
                val v = data[i + 1].toInt() and 0xFF
                val adjustedV = (v + vOffset).coerceIn(0, 255)
                data[i + 1] = adjustedV.toByte()
            }
        }
    }
    
    /**
     * Add natural variation to color adjustment
     * Real skin doesn't reflect light perfectly uniformly
     */
    fun addNaturalVariation(
        frameData: ByteArray,
        width: Int,
        height: Int,
        seed: Long = System.currentTimeMillis()
    ): ByteArray {
        val adjustedData = frameData.copyOf()
        val ySize = width * height
        
        // Add subtle random variation to Y channel (±2%)
        val random = java.util.Random(seed)
        
        for (i in 0 until ySize step 10) { // Sample every 10th pixel for performance
            val y = adjustedData[i].toInt() and 0xFF
            val variation = (random.nextGaussian() * 5).toInt() // ±5 levels
            val adjusted = (y + variation).coerceIn(0, 255)
            adjustedData[i] = adjusted.toByte()
        }
        
        return adjustedData
    }
}

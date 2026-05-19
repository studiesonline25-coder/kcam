package com.virtucam.hooks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Detects dominant screen colors by hooking into View/WebView drawing.
 * Used to detect color flash challenges (green circles, red backgrounds, etc.)
 * and adjust VirtuCam video color accordingly.
 * 
 * This hooks at the View level to capture what's being displayed on screen,
 * then analyzes the dominant color to simulate ambient light reflection.
 */
class ScreenColorDetector {
    
    companion object {
        private const val TAG = "VirtuCam_ScreenColor"
        
        // Singleton instance for global access
        @Volatile
        private var instance: ScreenColorDetector? = null
        
        fun getInstance(): ScreenColorDetector {
            return instance ?: synchronized(this) {
                instance ?: ScreenColorDetector().also { instance = it }
            }
        }
        
        // Detection thresholds
        private const val COLOR_CHANGE_THRESHOLD = 0.10f  // 10% change to trigger (more sensitive)
        private const val SATURATION_THRESHOLD = 0.25f   // Minimum saturation for color flash
        private const val BRIGHTNESS_THRESHOLD = 0.3f    // Minimum brightness
        
        // Sampling parameters - OPTIMIZED FOR FAST COLOR FLASHES
        private const val SAMPLE_INTERVAL_MS = 16L       // Sample every 16ms (~60 FPS) for fast response
        private const val EDGE_SAMPLE_PERCENT = 0.20f    // Sample from outer 20% of screen (larger area)
    }
    
    // Current detected color state
    data class DetectedColor(
        val r: Float,           // 0-1 range
        val g: Float,           // 0-1 range
        val b: Float,           // 0-1 range
        val intensity: Float,   // How strong/saturated the color is (0-1)
        val confidence: Float,  // How confident we are this is a flash (0-1)
        val timestamp: Long     // When this was detected
    ) {
        companion object {
            val NONE = DetectedColor(0f, 0f, 0f, 0f, 0f, 0L)
        }
        
        fun isSignificant(): Boolean = intensity > 0.1f && confidence > 0.3f
    }
    
    // Atomic reference for thread-safe access from render thread
    private val currentColor = AtomicReference(DetectedColor.NONE)
    private val previousColor = AtomicReference(DetectedColor.NONE)
    
    // Continuous color tracking for rapid sequential flashes
    // Uses exponential smoothing for natural, realistic transitions
    private var smoothedR = 0f
    private var smoothedG = 0f
    private var smoothedB = 0f
    private var smoothedIntensity = 0f
    
    // Smoothing factor: higher = faster response, lower = smoother
    // 0.3 gives ~50ms effective response time while staying smooth
    private val smoothingFactor = 0.35f
    
    // Track color history for natural decay
    private var lastColorTime = 0L
    private val colorDecayMs = 200L  // Color fades over 200ms after flash ends
    
    // Last sample time to avoid over-sampling
    private var lastSampleTime = 0L
    
    // Transition timing for smooth color changes
    private var transitionStartTime = 0L
    
    // Cached bitmap for sampling (reused to avoid allocations)
    private var sampleBitmap: Bitmap? = null
    private var sampleCanvas: Canvas? = null
    
    /**
     * Get the current detected color for use in rendering.
     * Uses continuous exponential smoothing for realistic, natural color transitions.
     * 
     * This creates the effect of ambient light gradually washing over the face,
     * just like real skin responds to colored light in the environment.
     */
    fun getCurrentColor(): DetectedColor {
        val current = currentColor.get()
        val now = System.currentTimeMillis()
        
        // Apply exponential smoothing for continuous, natural transitions
        // This handles rapid sequential flashes smoothly
        if (current.isSignificant()) {
            // Smoothly blend towards the detected color
            smoothedR = lerp(smoothedR, current.r, smoothingFactor)
            smoothedG = lerp(smoothedG, current.g, smoothingFactor)
            smoothedB = lerp(smoothedB, current.b, smoothingFactor)
            smoothedIntensity = lerp(smoothedIntensity, current.intensity, smoothingFactor)
            lastColorTime = now
        } else {
            // Natural decay when no color flash is active
            val timeSinceLastColor = now - lastColorTime
            if (timeSinceLastColor < colorDecayMs) {
                // Gradual fade out
                val decayFactor = 1f - (timeSinceLastColor.toFloat() / colorDecayMs)
                smoothedIntensity = smoothedIntensity * (0.95f + 0.05f * decayFactor)
            } else {
                // Fully decayed - smoothly return to zero
                smoothedR = lerp(smoothedR, 0f, 0.1f)
                smoothedG = lerp(smoothedG, 0f, 0.1f)
                smoothedB = lerp(smoothedB, 0f, 0.1f)
                smoothedIntensity = lerp(smoothedIntensity, 0f, 0.15f)
            }
        }
        
        // Add subtle natural variation (skin doesn't reflect light perfectly uniformly)
        val timeVariation = (Math.sin(now / 100.0) * 0.02f).toFloat()
        val naturalIntensity = (smoothedIntensity + timeVariation).coerceIn(0f, 1f)
        
        // Return if intensity is too low
        if (naturalIntensity < 0.05f) {
            return DetectedColor.NONE
        }
        
        return DetectedColor(
            r = smoothedR,
            g = smoothedG,
            b = smoothedB,
            intensity = naturalIntensity,
            confidence = current.confidence.coerceAtLeast(0.5f),
            timestamp = now
        )
    }
    
    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
    
    /**
     * Analyze a view's content to detect dominant colors.
     * Called from hooked View.draw() method.
     */
    fun analyzeView(view: View) {
        val now = System.currentTimeMillis()
        if (now - lastSampleTime < SAMPLE_INTERVAL_MS) {
            return  // Rate limit sampling
        }
        lastSampleTime = now
        
        try {
            val width = view.width
            val height = view.height
            
            if (width <= 0 || height <= 0) return
            
            // Create or reuse sample bitmap (scaled down for performance)
            val sampleWidth = (width / 8).coerceAtLeast(50)
            val sampleHeight = (height / 8).coerceAtLeast(50)
            
            if (sampleBitmap == null || sampleBitmap!!.width != sampleWidth || sampleBitmap!!.height != sampleHeight) {
                sampleBitmap?.recycle()
                sampleBitmap = Bitmap.createBitmap(sampleWidth, sampleHeight, Bitmap.Config.ARGB_8888)
                sampleCanvas = Canvas(sampleBitmap!!)
            }
            
            // Scale and draw view to sample bitmap
            sampleCanvas?.let { canvas ->
                canvas.save()
                canvas.scale(sampleWidth.toFloat() / width, sampleHeight.toFloat() / height)
                view.draw(canvas)
                canvas.restore()
            }
            
            // Analyze the sampled bitmap
            sampleBitmap?.let { bitmap ->
                val detected = analyzeEdgeColors(bitmap)
                updateDetectedColor(detected)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error analyzing view", e)
        }
    }
    
    /**
     * Analyze edge colors of the bitmap (where UI overlays typically appear).
     */
    private fun analyzeEdgeColors(bitmap: Bitmap): DetectedColor {
        val width = bitmap.width
        val height = bitmap.height
        val edgeWidth = (width * EDGE_SAMPLE_PERCENT).toInt().coerceAtLeast(5)
        val edgeHeight = (height * EDGE_SAMPLE_PERCENT).toInt().coerceAtLeast(5)
        
        var totalR = 0f
        var totalG = 0f
        var totalB = 0f
        var sampleCount = 0
        
        // Sample from all four edges
        val regions = listOf(
            Rect(0, 0, width, edgeHeight),                    // Top
            Rect(0, height - edgeHeight, width, height),      // Bottom
            Rect(0, edgeHeight, edgeWidth, height - edgeHeight),  // Left
            Rect(width - edgeWidth, edgeHeight, width, height - edgeHeight)  // Right
        )
        
        for (region in regions) {
            for (y in region.top until region.bottom step 2) {
                for (x in region.left until region.right step 2) {
                    if (x >= 0 && x < width && y >= 0 && y < height) {
                        val pixel = bitmap.getPixel(x, y)
                        totalR += Color.red(pixel) / 255f
                        totalG += Color.green(pixel) / 255f
                        totalB += Color.blue(pixel) / 255f
                        sampleCount++
                    }
                }
            }
        }
        
        if (sampleCount == 0) return DetectedColor.NONE
        
        val avgR = totalR / sampleCount
        val avgG = totalG / sampleCount
        val avgB = totalB / sampleCount
        
        // Calculate color properties
        val maxChannel = maxOf(avgR, avgG, avgB)
        val minChannel = minOf(avgR, avgG, avgB)
        val saturation = if (maxChannel > 0) (maxChannel - minChannel) / maxChannel else 0f
        val brightness = (avgR + avgG + avgB) / 3f
        
        // Determine if this is a significant color flash
        if (saturation < SATURATION_THRESHOLD || brightness < BRIGHTNESS_THRESHOLD) {
            return DetectedColor.NONE
        }
        
        // Calculate intensity based on how dominant the color is
        val intensity = saturation * brightness
        
        // Calculate confidence based on how pure the color is
        val colorPurity = when {
            avgG > avgR && avgG > avgB -> (avgG - maxOf(avgR, avgB)) / avgG  // Green dominant
            avgR > avgG && avgR > avgB -> (avgR - maxOf(avgG, avgB)) / avgR  // Red dominant
            avgB > avgR && avgB > avgG -> (avgB - maxOf(avgR, avgG)) / avgB  // Blue dominant
            else -> 0f
        }
        val confidence = (colorPurity * saturation).coerceIn(0f, 1f)
        
        return DetectedColor(
            r = avgR,
            g = avgG,
            b = avgB,
            intensity = intensity,
            confidence = confidence,
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * Update the detected color - CONTINUOUS mode for rapid sequential flashes.
     * No rate limiting - every color update is processed immediately.
     */
    private fun updateDetectedColor(newColor: DetectedColor) {
        val current = currentColor.get()
        
        // For continuous color flashes, always update if significant
        // The exponential smoothing in getCurrentColor() handles transitions
        if (newColor.isSignificant()) {
            previousColor.set(current)
            currentColor.set(newColor)
            
            // Log only on significant color changes to avoid spam
            val colorDiff = abs(newColor.r - current.r) + abs(newColor.g - current.g) + abs(newColor.b - current.b)
            if (colorDiff > 0.2f) {
                Log.d(TAG, "Color flash: R=${String.format("%.2f", newColor.r)}, " +
                          "G=${String.format("%.2f", newColor.g)}, B=${String.format("%.2f", newColor.b)}")
            }
        } else if (current.isSignificant()) {
            // Transition from color to no-color
            previousColor.set(current)
            currentColor.set(newColor)
        }
    }
    
    /**
     * Hook into View drawing to detect screen colors.
     * Should be called from XposedInit.
     * 
     * OPTIMIZED: Multiple hook points for fastest possible detection
     */
    fun hookViewDrawing(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook View.draw(Canvas) to capture what's being drawn
            XposedHelpers.findAndHookMethod(
                View::class.java,
                "draw",
                Canvas::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.thisObject as View
                        
                        // Only analyze top-level views (DecorView or large views)
                        if (isTopLevelView(view)) {
                            getInstance().analyzeView(view)
                        }
                    }
                }
            )
            Log.d(TAG, "Hooked View.draw() for color detection")
            
            // Hook WebView specifically for better coverage
            try {
                val webViewClass = XposedHelpers.findClass("android.webkit.WebView", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    webViewClass,
                    "onDraw",
                    Canvas::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as View
                            getInstance().analyzeView(view)
                        }
                    }
                )
                Log.d(TAG, "Hooked WebView.onDraw() for color detection")
            } catch (e: Exception) {
                Log.w(TAG, "Could not hook WebView.onDraw(): ${e.message}")
            }
            
            // Hook SurfaceView for direct surface rendering (faster path)
            try {
                val surfaceViewClass = XposedHelpers.findClass("android.view.SurfaceView", lpparam.classLoader)
                XposedHelpers.findAndHookMethod(
                    surfaceViewClass,
                    "draw",
                    Canvas::class.java,
                    object : XC_MethodHook() {
                        override fun afterHookedMethod(param: MethodHookParam) {
                            val view = param.thisObject as View
                            getInstance().analyzeView(view)
                        }
                    }
                )
                Log.d(TAG, "Hooked SurfaceView.draw() for color detection")
            } catch (e: Exception) {
                Log.w(TAG, "Could not hook SurfaceView.draw(): ${e.message}")
            }
            
            // Hook Canvas.drawColor - direct detection of full-screen color fills
            try {
                XposedHelpers.findAndHookMethod(
                    Canvas::class.java,
                    "drawColor",
                    Int::class.javaPrimitiveType,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val color = param.args[0] as Int
                            getInstance().detectDirectColor(color)
                        }
                    }
                )
                Log.d(TAG, "Hooked Canvas.drawColor() for FAST color detection")
            } catch (e: Exception) {
                Log.w(TAG, "Could not hook Canvas.drawColor(): ${e.message}")
            }
            
            // Hook Canvas.drawRect with Paint - common for color overlays
            try {
                XposedHelpers.findAndHookMethod(
                    Canvas::class.java,
                    "drawRect",
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    Float::class.javaPrimitiveType,
                    android.graphics.Paint::class.java,
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            val canvas = param.thisObject as Canvas
                            val left = param.args[0] as Float
                            val top = param.args[1] as Float
                            val right = param.args[2] as Float
                            val bottom = param.args[3] as Float
                            val paint = param.args[4] as android.graphics.Paint
                            
                            // Check if this is a large rect (potential color flash overlay)
                            val rectWidth = right - left
                            val rectHeight = bottom - top
                            val canvasWidth = canvas.width.toFloat()
                            val canvasHeight = canvas.height.toFloat()
                            
                            if (canvasWidth > 0 && canvasHeight > 0) {
                                val coverage = (rectWidth * rectHeight) / (canvasWidth * canvasHeight)
                                if (coverage > 0.3f) {  // Covers >30% of screen
                                    getInstance().detectDirectColor(paint.color)
                                }
                            }
                        }
                    }
                )
                Log.d(TAG, "Hooked Canvas.drawRect() for color overlay detection")
            } catch (e: Exception) {
                Log.w(TAG, "Could not hook Canvas.drawRect(): ${e.message}")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hook view drawing", e)
        }
    }
    
    /**
     * FAST PATH: Direct color detection from Canvas operations.
     * No bitmap sampling needed - instant detection!
     * NO RATE LIMITING - processes every color for continuous flash sequences.
     */
    fun detectDirectColor(color: Int) {
        val alpha = Color.alpha(color)
        if (alpha < 100) return  // Ignore transparent colors
        
        val r = Color.red(color) / 255f
        val g = Color.green(color) / 255f
        val b = Color.blue(color) / 255f
        
        // Calculate saturation
        val maxChannel = maxOf(r, g, b)
        val minChannel = minOf(r, g, b)
        val saturation = if (maxChannel > 0) (maxChannel - minChannel) / maxChannel else 0f
        val brightness = (r + g + b) / 3f
        
        // Only process saturated colors (likely color flash)
        if (saturation < SATURATION_THRESHOLD || brightness < BRIGHTNESS_THRESHOLD) {
            return
        }
        
        // Calculate intensity - boost for primary colors (green, red, blue)
        val isPrimaryColor = (r > 0.7f && g < 0.4f && b < 0.4f) ||  // Red
                            (g > 0.7f && r < 0.4f && b < 0.4f) ||  // Green
                            (b > 0.7f && r < 0.4f && g < 0.4f)     // Blue
        val intensityBoost = if (isPrimaryColor) 1.3f else 1.0f
        val intensity = (saturation * brightness * intensityBoost).coerceIn(0f, 1f)
        
        // Calculate confidence
        val colorPurity = when {
            g > r && g > b -> (g - maxOf(r, b)) / g
            r > g && r > b -> (r - maxOf(g, b)) / r
            b > r && b > g -> (b - maxOf(r, g)) / b
            else -> 0f
        }
        val confidence = (colorPurity * saturation).coerceIn(0f, 1f)
        
        if (confidence > 0.25f) {  // Lower threshold for faster response
            val newColor = DetectedColor(r, g, b, intensity, confidence, System.currentTimeMillis())
            updateDetectedColor(newColor)
        }
    }
    
    /**
     * Check if this is a top-level view worth analyzing.
     */
    private fun isTopLevelView(view: View): Boolean {
        // Check if it's a DecorView or similar top-level container
        val className = view.javaClass.name
        if (className.contains("DecorView") || className.contains("ContentFrameLayout")) {
            return true
        }
        
        // Check if it's a large view (likely full-screen)
        val parent = view.parent
        if (parent is ViewGroup) {
            val parentWidth = parent.width
            val parentHeight = parent.height
            if (parentWidth > 0 && parentHeight > 0) {
                val coverageRatio = (view.width.toFloat() * view.height) / (parentWidth * parentHeight)
                return coverageRatio > 0.8f  // Covers >80% of parent
            }
        }
        
        return false
    }
    
    /**
     * Alternative: Direct bitmap analysis for when View hooking doesn't work.
     * Can be called with a captured screenshot.
     */
    fun analyzeBitmap(bitmap: Bitmap): DetectedColor {
        return analyzeEdgeColors(bitmap)
    }
    
    /**
     * Force update with a specific color (for testing or manual override).
     */
    fun forceColor(r: Float, g: Float, b: Float, intensity: Float) {
        val newColor = DetectedColor(r, g, b, intensity, 1.0f, System.currentTimeMillis())
        previousColor.set(currentColor.get())
        currentColor.set(newColor)
        transitionStartTime = System.currentTimeMillis()
        Log.d(TAG, "Forced color: R=$r, G=$g, B=$b, intensity=$intensity")
    }
    
    /**
     * Clear the current detected color.
     */
    fun clearColor() {
        previousColor.set(currentColor.get())
        currentColor.set(DetectedColor.NONE)
        transitionStartTime = System.currentTimeMillis()
    }
}

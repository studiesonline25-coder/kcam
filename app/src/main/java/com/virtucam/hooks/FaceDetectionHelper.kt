package com.virtucam.hooks

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.params.Face
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.atomic.AtomicReference

/**
 * ML Kit-based face detection for generating realistic STATISTICS_FACES metadata.
 * Runs asynchronously to avoid blocking the render thread.
 */
object FaceDetectionHelper {
    private const val TAG = "VirtuCam_FaceDetect"
    
    // ML Kit face detector with performance mode (fast detection)
    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)  // Detect faces that are at least 15% of image
            .build()
        FaceDetection.getClient(options)
    }
    
    // Cached face array (updated asynchronously)
    private val cachedFaces = AtomicReference<Array<Face>>(emptyArray())
    
    // Last detection timestamp to throttle processing
    @Volatile private var lastDetectionMs = 0L
    private const val DETECTION_INTERVAL_MS = 200L  // Detect faces every 200ms
    
    /**
     * Process a frame for face detection (async, non-blocking).
     * Updates cachedFaces when detection completes.
     */
    fun processFrameAsync(bitmap: Bitmap, imageWidth: Int, imageHeight: Int) {
        val now = System.currentTimeMillis()
        if (now - lastDetectionMs < DETECTION_INTERVAL_MS) return
        lastDetectionMs = now
        
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            detector.process(image)
                .addOnSuccessListener { mlFaces ->
                    // Convert ML Kit faces to Camera2 Face objects
                    val camera2Faces = mlFaces.map { mlFace ->
                        val bounds = mlFace.boundingBox
                        
                        // Convert pixel coordinates to normalized [-1000, 1000] range
                        // Camera2 uses active array coordinates, but we'll use normalized coords
                        val left = ((bounds.left.toFloat() / imageWidth) * 2000 - 1000).toInt()
                        val top = ((bounds.top.toFloat() / imageHeight) * 2000 - 1000).toInt()
                        val right = ((bounds.right.toFloat() / imageWidth) * 2000 - 1000).toInt()
                        val bottom = ((bounds.bottom.toFloat() / imageHeight) * 2000 - 1000).toInt()
                        
                        val rect = Rect(left, top, right, bottom)
                        
                        // Create Camera2 Face object
                        // Score: 1-100 (ML Kit confidence is 0-1, so multiply by 100)
                        val score = (mlFace.trackingId?.let { 100 } ?: 75).coerceIn(1, 100)
                        
                        createCamera2Face(rect, score)
                    }.toTypedArray()
                    
                    cachedFaces.set(camera2Faces)
                    
                    if (CameraHook.enableDiagnosticLogs) {
                        Log.d(TAG, "Detected ${camera2Faces.size} face(s)")
                    }
                }
                .addOnFailureListener { e ->
                    if (CameraHook.enableDiagnosticLogs) {
                        Log.w(TAG, "Face detection failed: ${e.message}")
                    }
                }
        } catch (e: Exception) {
            if (CameraHook.enableDiagnosticLogs) {
                Log.e(TAG, "Face detection error", e)
            }
        }
    }
    
    /**
     * Get the most recently detected faces (non-blocking).
     * Returns empty array if no faces have been detected yet.
     */
    fun getCachedFaces(): Array<Face> {
        return cachedFaces.get()
    }
    
    /**
     * Create a Camera2 Face object using reflection (since Face constructor is hidden).
     * Falls back to empty array if reflection fails.
     */
    private fun createCamera2Face(bounds: Rect, score: Int): Face {
        return try {
            // Use Face constructor: Face(Rect bounds, int score)
            val constructor = Face::class.java.getDeclaredConstructor(
                Rect::class.java,
                Int::class.javaPrimitiveType
            )
            constructor.isAccessible = true
            constructor.newInstance(bounds, score)
        } catch (e: Exception) {
            // Fallback: Try alternative constructor with more parameters
            try {
                // Face(Rect bounds, int score, int id, Point leftEye, Point rightEye, Point mouth)
                val constructor = Face::class.java.declaredConstructors.firstOrNull {
                    it.parameterTypes.size >= 2
                }
                constructor?.isAccessible = true
                constructor?.newInstance(bounds, score) as? Face
                    ?: throw IllegalStateException("No suitable Face constructor found")
            } catch (e2: Exception) {
                if (CameraHook.enableDiagnosticLogs) {
                    Log.e(TAG, "Failed to create Face object via reflection", e2)
                }
                // Return a minimal face with just bounds (last resort)
                createFaceViaBuilder(bounds, score)
            }
        }
    }
    
    /**
     * Last resort: Create Face using Builder pattern if available (Android 14+)
     */
    private fun createFaceViaBuilder(bounds: Rect, score: Int): Face {
        return try {
            val builderClass = Class.forName("android.hardware.camera2.params.Face\$Builder")
            val constructor = builderClass.getDeclaredConstructor()
            constructor.isAccessible = true
            val builder = constructor.newInstance()
            
            // Set bounds
            val setBoundsMethod = builderClass.getDeclaredMethod("setBounds", Rect::class.java)
            setBoundsMethod.isAccessible = true
            setBoundsMethod.invoke(builder, bounds)
            
            // Set score
            val setScoreMethod = builderClass.getDeclaredMethod("setScore", Int::class.javaPrimitiveType)
            setScoreMethod.isAccessible = true
            setScoreMethod.invoke(builder, score)
            
            // Build
            val buildMethod = builderClass.getDeclaredMethod("build")
            buildMethod.isAccessible = true
            buildMethod.invoke(builder) as Face
        } catch (e: Exception) {
            if (CameraHook.enableDiagnosticLogs) {
                Log.e(TAG, "Face.Builder not available, using minimal Face", e)
            }
            // Ultimate fallback: return a face with just bounds and score
            createMinimalFace(bounds, score)
        }
    }
    
    /**
     * Absolute last resort: Create a minimal Face object
     */
    private fun createMinimalFace(bounds: Rect, score: Int): Face {
        // This will use the simplest available constructor
        val constructor = Face::class.java.declaredConstructors.minByOrNull { it.parameterTypes.size }
            ?: throw IllegalStateException("No Face constructor available")
        
        constructor.isAccessible = true
        
        return when (constructor.parameterTypes.size) {
            2 -> constructor.newInstance(bounds, score) as Face
            else -> {
                // Fill remaining parameters with nulls/defaults
                val params = arrayOfNulls<Any?>(constructor.parameterTypes.size)
                params[0] = bounds
                if (params.size > 1) params[1] = score
                constructor.newInstance(*params) as Face
            }
        }
    }
    
    /**
     * Cleanup resources when no longer needed
     */
    fun cleanup() {
        try {
            detector.close()
        } catch (e: Exception) {
            // Ignore cleanup errors
        }
    }
}

package com.virtucam.detection

import android.graphics.Bitmap
import android.graphics.Rect
import android.hardware.camera2.params.Face
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlin.math.abs

/**
 * Face Detection Consistency Checker
 * Validates that STATISTICS_FACES metadata matches actual video content.
 */
class FaceConsistencyChecker {
    
    data class FrameData(
        val frameNumber: Long,
        val bitmap: Bitmap,
        val metadataFaces: Array<Face>?
    )
    
    data class AnalysisResult(
        val score: Int,  // 0-100, higher = more suspicious (0 = perfect match)
        val facesDetectedByML: Int,
        val facesInMetadata: Int,
        val mismatchFrames: List<Long>,
        val anomalies: List<String>,
        val confidence: Int,
        val details: Map<String, Any>
    )
    
    private val frames = mutableListOf<FrameData>()
    private val detector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.15f)
            .build()
        FaceDetection.getClient(options)
    }
    
    fun feedFrame(frame: FrameData) {
        frames.add(frame)
    }
    
    fun analyze(): AnalysisResult {
        if (frames.isEmpty()) {
            return AnalysisResult(
                score = 0,
                facesDetectedByML = 0,
                facesInMetadata = 0,
                mismatchFrames = emptyList(),
                anomalies = listOf("No frames provided"),
                confidence = 0,
                details = emptyMap()
            )
        }
        
        val anomalies = mutableListOf<String>()
        val details = mutableMapOf<String, Any>()
        val mismatchFrames = mutableListOf<Long>()
        
        var totalMlFaces = 0
        var totalMetadataFaces = 0
        var countMismatches = 0
        var boundsDeviationSum = 0.0
        var processedFrames = 0
        
        // Analyze each frame
        frames.forEach { frame ->
            try {
                val image = InputImage.fromBitmap(frame.bitmap, 0)
                val mlFacesTask = detector.process(image)
                
                // Wait for ML Kit (synchronous for analysis)
                val mlFaces = try {
                    com.google.android.gms.tasks.Tasks.await(mlFacesTask)
                } catch (e: Exception) {
                    emptyList()
                }
                
                val mlFaceCount = mlFaces.size
                val metadataFaceCount = frame.metadataFaces?.size ?: 0
                
                totalMlFaces += mlFaceCount
                totalMetadataFaces += metadataFaceCount
                
                // Check for count mismatch
                if (mlFaceCount != metadataFaceCount) {
                    countMismatches++
                    mismatchFrames.add(frame.frameNumber)
                }
                
                // Check bounds deviation (if both have faces)
                if (mlFaceCount > 0 && metadataFaceCount > 0 && mlFaceCount == metadataFaceCount) {
                    // Compare bounds of first face (simplified)
                    val mlBounds = mlFaces.first().boundingBox
                    val metadataBounds = frame.metadataFaces?.first()?.bounds
                    
                    if (metadataBounds != null) {
                        val deviation = calculateBoundsDeviation(mlBounds, metadataBounds, frame.bitmap.width, frame.bitmap.height)
                        boundsDeviationSum += deviation
                    }
                }
                
                processedFrames++
            } catch (e: Exception) {
                // Skip frame on error
            }
        }
        
        // Calculate statistics
        val avgMlFaces = if (processedFrames > 0) totalMlFaces.toDouble() / processedFrames else 0.0
        val avgMetadataFaces = if (processedFrames > 0) totalMetadataFaces.toDouble() / processedFrames else 0.0
        val mismatchRate = if (processedFrames > 0) countMismatches.toDouble() / processedFrames else 0.0
        val avgBoundsDeviation = if (processedFrames > 0) boundsDeviationSum / processedFrames else 0.0
        
        details["avgMlFaces"] = avgMlFaces
        details["avgMetadataFaces"] = avgMetadataFaces
        details["mismatchRate"] = mismatchRate
        details["avgBoundsDeviation"] = avgBoundsDeviation
        details["processedFrames"] = processedFrames
        
        var score = 0
        
        // Scoring logic
        
        // 1. Count mismatch (most critical)
        if (mismatchRate > 0.5) {  // More than 50% of frames have mismatched face counts
            anomalies.add("Face count mismatch in ${(mismatchRate * 100).toInt()}% of frames")
            score += 40
        } else if (mismatchRate > 0.2) {  // More than 20%
            anomalies.add("Face count mismatch in ${(mismatchRate * 100).toInt()}% of frames")
            score += 25
        }
        
        // 2. Zero faces in metadata (critical if ML detects faces)
        if (avgMetadataFaces == 0.0 && avgMlFaces > 0.5) {
            anomalies.add("Metadata reports 0 faces but ML detects ${avgMlFaces.toInt()} face(s)")
            score += 35
        }
        
        // 3. Bounds deviation (if faces are present)
        if (avgBoundsDeviation > 0.3) {  // >30% deviation
            anomalies.add("Face bounds deviate by ${(avgBoundsDeviation * 100).toInt()}% from ML detection")
            score += 20
        } else if (avgBoundsDeviation > 0.2) {  // >20% deviation
            anomalies.add("Face bounds deviate by ${(avgBoundsDeviation * 100).toInt()}% from ML detection")
            score += 10
        }
        
        // 4. Suspiciously perfect match (all frames have exactly same face count)
        val metadataFaceCounts = frames.mapNotNull { it.metadataFaces?.size }.distinct()
        if (metadataFaceCounts.size == 1 && processedFrames > 50) {
            anomalies.add("Metadata reports exactly ${metadataFaceCounts.first()} face(s) in all ${processedFrames} frames (too consistent)")
            score += 15
        }
        
        // Confidence calculation
        val confidence = when {
            processedFrames < 10 -> 30
            processedFrames < 50 -> 60
            processedFrames < 100 -> 80
            else -> 95
        }
        
        return AnalysisResult(
            score = score.coerceIn(0, 100),
            facesDetectedByML = avgMlFaces.toInt(),
            facesInMetadata = avgMetadataFaces.toInt(),
            mismatchFrames = mismatchFrames.take(10),  // Limit to first 10 for brevity
            anomalies = anomalies,
            confidence = confidence,
            details = details
        )
    }
    
    /**
     * Calculate deviation between ML-detected bounds and metadata bounds
     * Returns value 0.0-1.0 (0 = perfect match, 1 = completely different)
     */
    private fun calculateBoundsDeviation(mlBounds: Rect, metadataBounds: Rect, imageWidth: Int, imageHeight: Int): Double {
        // Convert metadata bounds from normalized [-1000, 1000] to pixel coordinates
        val metaLeft = ((metadataBounds.left + 1000) / 2000.0 * imageWidth).toInt()
        val metaTop = ((metadataBounds.top + 1000) / 2000.0 * imageHeight).toInt()
        val metaRight = ((metadataBounds.right + 1000) / 2000.0 * imageWidth).toInt()
        val metaBottom = ((metadataBounds.bottom + 1000) / 2000.0 * imageHeight).toInt()
        
        // Calculate center point deviation
        val mlCenterX = (mlBounds.left + mlBounds.right) / 2.0
        val mlCenterY = (mlBounds.top + mlBounds.bottom) / 2.0
        val metaCenterX = (metaLeft + metaRight) / 2.0
        val metaCenterY = (metaTop + metaBottom) / 2.0
        
        val centerDeviationX = abs(mlCenterX - metaCenterX) / imageWidth
        val centerDeviationY = abs(mlCenterY - metaCenterY) / imageHeight
        
        // Calculate size deviation
        val mlWidth = mlBounds.width().toDouble()
        val mlHeight = mlBounds.height().toDouble()
        val metaWidth = (metaRight - metaLeft).toDouble()
        val metaHeight = (metaBottom - metaTop).toDouble()
        
        val widthDeviation = abs(mlWidth - metaWidth) / imageWidth
        val heightDeviation = abs(mlHeight - metaHeight) / imageHeight
        
        // Average all deviations
        return (centerDeviationX + centerDeviationY + widthDeviation + heightDeviation) / 4.0
    }
    
    fun reset() {
        frames.clear()
    }
    
    fun cleanup() {
        try {
            detector.close()
        } catch (e: Exception) {
            // Ignore
        }
    }
}

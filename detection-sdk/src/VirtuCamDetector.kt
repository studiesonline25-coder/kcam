package com.virtucam.detection

import android.graphics.Bitmap
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.Face
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main VirtuCam Detection SDK API
 * Aggregates results from all detection modules and generates reports.
 */
class VirtuCamDetector private constructor(
    private val enableMetadataAnalysis: Boolean,
    private val enableFaceConsistency: Boolean,
    private val enableTimingAnalysis: Boolean,
    private val frameCount: Int
) {
    
    private val metadataAnalyzer = if (enableMetadataAnalysis) MetadataAnalyzer() else null
    private val faceChecker = if (enableFaceConsistency) FaceConsistencyChecker() else null
    
    private var framesFed = 0
    
    data class DetectionResult(
        val spoofingDetected: Boolean,
        val overallScore: Int,  // 0-100, higher = more likely spoofing
        val confidence: Int,
        val vulnerabilities: List<Vulnerability>,
        val moduleResults: Map<String, ModuleResult>
    )
    
    data class Vulnerability(
        val module: String,
        val description: String,
        val score: Int,
        val severity: Severity
    )
    
    enum class Severity {
        CRITICAL,  // 70-100
        HIGH,      // 50-69
        MEDIUM,    // 30-49
        LOW        // 0-29
    }
    
    data class ModuleResult(
        val score: Int,
        val confidence: Int,
        val anomalies: List<String>,
        val details: Map<String, Any>
    )
    
    /**
     * Feed a camera frame with metadata
     */
    fun feedFrame(
        bitmap: Bitmap,
        exposureTime: Long,
        iso: Int,
        aeState: Int,
        timestamp: Long,
        metadataFaces: Array<Face>? = null,
        focusDistance: Float? = null,
        aperture: Float? = null,
        focalLength: Float? = null
    ) {
        if (framesFed >= frameCount) return
        
        // Feed to metadata analyzer
        metadataAnalyzer?.feedFrame(
            MetadataAnalyzer.MetadataFrame(
                frameNumber = framesFed.toLong(),
                timestamp = timestamp,
                exposureTime = exposureTime,
                iso = iso,
                aeState = aeState,
                focusDistance = focusDistance,
                aperture = aperture,
                focalLength = focalLength
            )
        )
        
        // Feed to face consistency checker
        faceChecker?.feedFrame(
            FaceConsistencyChecker.FrameData(
                frameNumber = framesFed.toLong(),
                bitmap = bitmap,
                metadataFaces = metadataFaces
            )
        )
        
        framesFed++
    }
    
    /**
     * Analyze all collected frames and generate detection result
     */
    fun analyze(): DetectionResult {
        val moduleResults = mutableMapOf<String, ModuleResult>()
        val vulnerabilities = mutableListOf<Vulnerability>()
        
        var totalScore = 0
        var totalConfidence = 0
        var moduleCount = 0
        
        // Analyze metadata
        metadataAnalyzer?.let { analyzer ->
            val result = analyzer.analyze()
            moduleResults["metadata"] = ModuleResult(
                score = result.score,
                confidence = result.confidence,
                anomalies = result.anomalies,
                details = result.details
            )
            
            totalScore += result.score
            totalConfidence += result.confidence
            moduleCount++
            
            // Extract vulnerabilities
            result.anomalies.forEach { anomaly ->
                vulnerabilities.add(
                    Vulnerability(
                        module = "Metadata Analysis",
                        description = anomaly,
                        score = result.score,
                        severity = getSeverity(result.score)
                    )
                )
            }
        }
        
        // Analyze face consistency
        faceChecker?.let { checker ->
            val result = checker.analyze()
            moduleResults["faceConsistency"] = ModuleResult(
                score = result.score,
                confidence = result.confidence,
                anomalies = result.anomalies,
                details = result.details
            )
            
            totalScore += result.score
            totalConfidence += result.confidence
            moduleCount++
            
            // Extract vulnerabilities
            result.anomalies.forEach { anomaly ->
                vulnerabilities.add(
                    Vulnerability(
                        module = "Face Consistency",
                        description = anomaly,
                        score = result.score,
                        severity = getSeverity(result.score)
                    )
                )
            }
        }
        
        // Calculate overall score (weighted average)
        val overallScore = if (moduleCount > 0) totalScore / moduleCount else 0
        val overallConfidence = if (moduleCount > 0) totalConfidence / moduleCount else 0
        
        // Spoofing detected if overall score > 50 (threshold)
        val spoofingDetected = overallScore > 50
        
        return DetectionResult(
            spoofingDetected = spoofingDetected,
            overallScore = overallScore,
            confidence = overallConfidence,
            vulnerabilities = vulnerabilities.sortedByDescending { it.score },
            moduleResults = moduleResults
        )
    }
    
    /**
     * Generate JSON report
     */
    fun generateJsonReport(result: DetectionResult): String {
        val json = JSONObject()
        
        // Metadata
        json.put("timestamp", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()))
        json.put("spoofingDetected", result.spoofingDetected)
        json.put("overallScore", result.overallScore)
        json.put("confidence", result.confidence)
        json.put("frameCount", framesFed)
        
        // Module results
        val modules = JSONObject()
        result.moduleResults.forEach { (name, moduleResult) ->
            val moduleJson = JSONObject()
            moduleJson.put("score", moduleResult.score)
            moduleJson.put("confidence", moduleResult.confidence)
            
            val anomaliesArray = JSONArray()
            moduleResult.anomalies.forEach { anomaliesArray.put(it) }
            moduleJson.put("anomalies", anomaliesArray)
            
            val detailsJson = JSONObject()
            moduleResult.details.forEach { (key, value) ->
                detailsJson.put(key, value)
            }
            moduleJson.put("details", detailsJson)
            
            modules.put(name, moduleJson)
        }
        json.put("modules", modules)
        
        // Vulnerabilities
        val vulnsArray = JSONArray()
        result.vulnerabilities.forEach { vuln ->
            val vulnJson = JSONObject()
            vulnJson.put("module", vuln.module)
            vulnJson.put("description", vuln.description)
            vulnJson.put("score", vuln.score)
            vulnJson.put("severity", vuln.severity.name)
            vulnsArray.put(vulnJson)
        }
        json.put("vulnerabilities", vulnsArray)
        
        // Recommendations
        val recommendations = generateRecommendations(result)
        val recsArray = JSONArray()
        recommendations.forEach { recsArray.put(it) }
        json.put("recommendations", recsArray)
        
        return json.toString(2)  // Pretty print with 2-space indent
    }
    
    /**
     * Generate HTML report
     */
    fun generateHtmlReport(result: DetectionResult): String {
        val html = StringBuilder()
        
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>VirtuCam Detection Report</title>
                <style>
                    body { font-family: Arial, sans-serif; margin: 20px; background: #f5f5f5; }
                    .container { max-width: 1200px; margin: 0 auto; background: white; padding: 30px; border-radius: 8px; box-shadow: 0 2px 4px rgba(0,0,0,0.1); }
                    h1 { color: #333; border-bottom: 3px solid #4CAF50; padding-bottom: 10px; }
                    h2 { color: #555; margin-top: 30px; }
                    .summary { background: #f9f9f9; padding: 20px; border-radius: 5px; margin: 20px 0; }
                    .score { font-size: 48px; font-weight: bold; color: ${if (result.spoofingDetected) "#f44336" else "#4CAF50"}; }
                    .status { font-size: 24px; color: ${if (result.spoofingDetected) "#f44336" else "#4CAF50"}; font-weight: bold; }
                    .vulnerability { background: #fff3cd; border-left: 4px solid #ffc107; padding: 15px; margin: 10px 0; border-radius: 3px; }
                    .vulnerability.critical { background: #f8d7da; border-left-color: #dc3545; }
                    .vulnerability.high { background: #fff3cd; border-left-color: #ffc107; }
                    .vulnerability.medium { background: #d1ecf1; border-left-color: #17a2b8; }
                    .vulnerability.low { background: #d4edda; border-left-color: #28a745; }
                    .module { background: #f9f9f9; padding: 15px; margin: 15px 0; border-radius: 5px; }
                    .anomaly { background: #fff; padding: 10px; margin: 5px 0; border-left: 3px solid #ff9800; }
                    .recommendation { background: #e8f5e9; padding: 15px; margin: 10px 0; border-left: 4px solid #4CAF50; border-radius: 3px; }
                    table { width: 100%; border-collapse: collapse; margin: 20px 0; }
                    th, td { padding: 12px; text-align: left; border-bottom: 1px solid #ddd; }
                    th { background: #4CAF50; color: white; }
                    .progress-bar { width: 100%; height: 30px; background: #e0e0e0; border-radius: 15px; overflow: hidden; }
                    .progress-fill { height: 100%; background: linear-gradient(90deg, #4CAF50, #FFC107, #f44336); transition: width 0.3s; }
                </style>
            </head>
            <body>
                <div class="container">
                    <h1>🔍 VirtuCam Detection Report</h1>
                    
                    <div class="summary">
                        <div class="status">${if (result.spoofingDetected) "⚠️ SPOOFING DETECTED" else "✅ NO SPOOFING DETECTED"}</div>
                        <div class="score">${result.overallScore}/100</div>
                        <p>Confidence: ${result.confidence}%</p>
                        <p>Frames Analyzed: $framesFed</p>
                        <p>Timestamp: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())}</p>
                        
                        <div class="progress-bar">
                            <div class="progress-fill" style="width: ${result.overallScore}%"></div>
                        </div>
                    </div>
                    
                    <h2>📊 Module Results</h2>
        """.trimIndent())
        
        result.moduleResults.forEach { (name, moduleResult) ->
            html.append("""
                <div class="module">
                    <h3>$name</h3>
                    <p><strong>Score:</strong> ${moduleResult.score}/100 | <strong>Confidence:</strong> ${moduleResult.confidence}%</p>
                    
                    ${if (moduleResult.anomalies.isNotEmpty()) {
                        "<h4>Anomalies Detected:</h4>" + moduleResult.anomalies.joinToString("") { 
                            "<div class='anomaly'>$it</div>" 
                        }
                    } else {
                        "<p style='color: #4CAF50;'>✅ No anomalies detected</p>"
                    }}
                    
                    <details>
                        <summary>Technical Details</summary>
                        <table>
                            <tr><th>Metric</th><th>Value</th></tr>
                            ${moduleResult.details.entries.joinToString("") { (key, value) ->
                                "<tr><td>$key</td><td>$value</td></tr>"
                            }}
                        </table>
                    </details>
                </div>
            """.trimIndent())
        }
        
        html.append("""
            <h2>🚨 Vulnerabilities</h2>
        """.trimIndent())
        
        if (result.vulnerabilities.isEmpty()) {
            html.append("<p style='color: #4CAF50;'>✅ No vulnerabilities detected</p>")
        } else {
            result.vulnerabilities.forEach { vuln ->
                html.append("""
                    <div class="vulnerability ${vuln.severity.name.lowercase()}">
                        <strong>[${vuln.severity.name}]</strong> ${vuln.module}: ${vuln.description}
                        <br><small>Score: ${vuln.score}/100</small>
                    </div>
                """.trimIndent())
            }
        }
        
        html.append("""
            <h2>💡 Recommendations</h2>
        """.trimIndent())
        
        val recommendations = generateRecommendations(result)
        if (recommendations.isEmpty()) {
            html.append("<p style='color: #4CAF50;'>✅ No recommendations - system appears legitimate</p>")
        } else {
            recommendations.forEach { rec ->
                html.append("""
                    <div class="recommendation">$rec</div>
                """.trimIndent())
            }
        }
        
        html.append("""
                </div>
            </body>
            </html>
        """.trimIndent())
        
        return html.toString()
    }
    
    private fun getSeverity(score: Int): Severity {
        return when {
            score >= 70 -> Severity.CRITICAL
            score >= 50 -> Severity.HIGH
            score >= 30 -> Severity.MEDIUM
            else -> Severity.LOW
        }
    }
    
    private fun generateRecommendations(result: DetectionResult): List<String> {
        val recommendations = mutableListOf<String>()
        
        result.vulnerabilities.forEach { vuln ->
            when {
                vuln.description.contains("periodic pattern", ignoreCase = true) -> {
                    recommendations.add("Replace sine wave metadata with Perlin noise + CMOS sensor model")
                }
                vuln.description.contains("0 faces", ignoreCase = true) -> {
                    recommendations.add("Implement STATISTICS_FACES injection using ML Kit face detection")
                }
                vuln.description.contains("too consistent", ignoreCase = true) -> {
                    recommendations.add("Add realistic variance to metadata using multi-octave Perlin noise")
                }
                vuln.description.contains("Fixed.*interval", ignoreCase = true) -> {
                    recommendations.add("Randomize timing intervals (JPEG generation, frame delivery)")
                }
                vuln.description.contains("Face count mismatch", ignoreCase = true) -> {
                    recommendations.add("Ensure face detection runs on actual video frames, not synthetic data")
                }
            }
        }
        
        return recommendations.distinct()
    }
    
    fun reset() {
        metadataAnalyzer?.reset()
        faceChecker?.reset()
        framesFed = 0
    }
    
    fun cleanup() {
        faceChecker?.cleanup()
    }
    
    class Builder {
        private var enableMetadataAnalysis = true
        private var enableFaceConsistency = true
        private var enableTimingAnalysis = true
        private var frameCount = 300
        
        fun enableMetadataAnalysis(enable: Boolean) = apply { this.enableMetadataAnalysis = enable }
        fun enableFaceConsistency(enable: Boolean) = apply { this.enableFaceConsistency = enable }
        fun enableTimingAnalysis(enable: Boolean) = apply { this.enableTimingAnalysis = enable }
        fun setFrameCount(count: Int) = apply { this.frameCount = count }
        
        fun build() = VirtuCamDetector(
            enableMetadataAnalysis = enableMetadataAnalysis,
            enableFaceConsistency = enableFaceConsistency,
            enableTimingAnalysis = enableTimingAnalysis,
            frameCount = frameCount
        )
    }
}

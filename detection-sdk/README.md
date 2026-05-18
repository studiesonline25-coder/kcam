# VirtuCam Detection SDK

A comprehensive anti-spoofing SDK that detects camera feed manipulation using the same techniques as Sumsub, Onfido, and Jumio.

---

## Quick Start

### 1. Add to your project

```kotlin
// In your app's build.gradle.kts
dependencies {
    implementation(project(":detection-sdk"))
    implementation("com.google.mlkit:face-detection:16.1.7")
}
```

### 2. Initialize the detector

```kotlin
val detector = VirtuCamDetector.Builder()
    .enableMetadataAnalysis(true)
    .enableFaceConsistency(true)
    .setFrameCount(300)  // Analyze 300 frames (~10 seconds at 30fps)
    .build()
```

### 3. Feed camera frames

```kotlin
// In your camera callback (e.g., onCaptureCompleted)
override fun onCaptureCompleted(
    session: CameraCaptureSession,
    request: CaptureRequest,
    result: TotalCaptureResult
) {
    // Get frame bitmap (from ImageReader or SurfaceTexture)
    val bitmap = getCurrentFrameBitmap()
    
    // Extract metadata
    val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
    val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: 0
    val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
    val faces = result.get(CaptureResult.STATISTICS_FACES)
    
    // Feed to detector
    detector.feedFrame(
        bitmap = bitmap,
        exposureTime = exposureTime,
        iso = iso,
        aeState = aeState,
        timestamp = timestamp,
        metadataFaces = faces
    )
}
```

### 4. Analyze and get results

```kotlin
// After collecting 300 frames
val result = detector.analyze()

if (result.spoofingDetected) {
    println("⚠️ SPOOFING DETECTED!")
    println("Overall Score: ${result.overallScore}/100")
    println("Confidence: ${result.confidence}%")
    
    println("\nVulnerabilities:")
    result.vulnerabilities.forEach { vuln ->
        println("- [${vuln.severity}] ${vuln.module}: ${vuln.description}")
    }
} else {
    println("✅ No spoofing detected")
}
```

### 5. Generate reports

```kotlin
// JSON report
val jsonReport = detector.generateJsonReport(result)
File("detection_report.json").writeText(jsonReport)

// HTML report
val htmlReport = detector.generateHtmlReport(result)
File("detection_report.html").writeText(htmlReport)
```

---

## Detection Modules

### 1. Metadata Analysis
Detects synthetic patterns in camera metadata using FFT and statistical analysis.

**What it detects:**
- Periodic patterns (sine waves, fixed cycles)
- Statistical anomalies (too consistent, impossible jumps)
- Low entropy (predictable values)
- High autocorrelation (repeating patterns)

**Example vulnerability:**
```
Exposure time follows periodic pattern (90 frames)
ISO sensitivity has fixed ±20 variation
```

---

### 2. Face Consistency Checker
Validates that STATISTICS_FACES metadata matches actual video content.

**What it detects:**
- Face count mismatch (ML detects N faces, metadata reports M)
- Face bounds deviation (>20% difference)
- Missing face metadata (0 faces when faces are visible)
- Suspiciously consistent face counts

**Example vulnerability:**
```
Metadata reports 0 faces but ML detects 1 face(s)
Face count mismatch in 95% of frames
```

---

## Scoring System

### Overall Score (0-100)
- **0-29:** Low risk (likely legitimate)
- **30-49:** Medium risk (some anomalies detected)
- **50-69:** High risk (likely spoofing)
- **70-100:** Critical risk (definite spoofing)

### Module Scores
Each module contributes to the overall score:
- **Metadata Analysis:** 30% weight
- **Face Consistency:** 25% weight
- **Timing Analysis:** 20% weight (not yet implemented)
- **Logcat Monitoring:** 15% weight (not yet implemented)
- **Hardware Validation:** 10% weight (not yet implemented)

---

## Example Output

### Console Output
```
⚠️ SPOOFING DETECTED!
Overall Score: 78/100
Confidence: 92%

Vulnerabilities:
- [CRITICAL] Metadata Analysis: Exposure time follows periodic pattern (90 frames)
- [CRITICAL] Face Consistency: Metadata reports 0 faces but ML detects 1 face(s)
- [HIGH] Metadata Analysis: ISO sensitivity has fixed ±20 variation
- [MEDIUM] Face Consistency: Face count mismatch in 95% of frames
```

### JSON Report
```json
{
  "timestamp": "2026-05-18T17:45:00Z",
  "spoofingDetected": true,
  "overallScore": 78,
  "confidence": 92,
  "frameCount": 300,
  "modules": {
    "metadata": {
      "score": 85,
      "confidence": 88,
      "anomalies": [
        "Exposure time follows periodic pattern (90 frames)",
        "ISO sensitivity has fixed ±20 variation"
      ],
      "details": {
        "exposurePeriodicity": 90,
        "exposureCV": 0.0485,
        "exposureEntropy": 3.42
      }
    },
    "faceConsistency": {
      "score": 75,
      "confidence": 95,
      "anomalies": [
        "Metadata reports 0 faces but ML detects 1 face(s)",
        "Face count mismatch in 95% of frames"
      ],
      "details": {
        "avgMlFaces": 1.0,
        "avgMetadataFaces": 0.0,
        "mismatchRate": 0.95
      }
    }
  },
  "vulnerabilities": [
    {
      "module": "Metadata Analysis",
      "description": "Exposure time follows periodic pattern (90 frames)",
      "score": 85,
      "severity": "CRITICAL"
    },
    {
      "module": "Face Consistency",
      "description": "Metadata reports 0 faces but ML detects 1 face(s)",
      "score": 75,
      "severity": "CRITICAL"
    }
  ],
  "recommendations": [
    "Replace sine wave metadata with Perlin noise + CMOS sensor model",
    "Implement STATISTICS_FACES injection using ML Kit face detection"
  ]
}
```

### HTML Report
Opens in browser with:
- Visual score gauge
- Color-coded vulnerabilities (red=critical, yellow=high, blue=medium, green=low)
- Expandable technical details
- Actionable recommendations

---

## Testing Against VirtuCam

### Test 1: Old VirtuCam (Before Fixes)
```kotlin
// Expected result:
// - spoofingDetected = true
// - overallScore = 80-90
// - Metadata: Periodic patterns detected
// - Face: 0 faces in metadata, 1+ detected by ML
```

### Test 2: New VirtuCam (After Fixes)
```kotlin
// Expected result:
// - spoofingDetected = false (or low score <50)
// - overallScore = 20-40
// - Metadata: Realistic Perlin noise
// - Face: Consistent face detection
```

### Test 3: Real Camera (Baseline)
```kotlin
// Expected result:
// - spoofingDetected = false
// - overallScore = 0-20
// - No anomalies detected
```

---

## Advanced Usage

### Custom Thresholds
```kotlin
val result = detector.analyze()

// Custom threshold (default is 50)
val customThreshold = 70
val isSpoofing = result.overallScore > customThreshold
```

### Selective Module Enabling
```kotlin
// Only run metadata analysis (faster)
val detector = VirtuCamDetector.Builder()
    .enableMetadataAnalysis(true)
    .enableFaceConsistency(false)
    .build()
```

### Continuous Monitoring
```kotlin
// Analyze in batches
val detector = VirtuCamDetector.Builder()
    .setFrameCount(100)  // Smaller batches
    .build()

while (cameraIsRunning) {
    // Feed 100 frames
    repeat(100) { detector.feedFrame(...) }
    
    // Analyze
    val result = detector.analyze()
    if (result.spoofingDetected) {
        // Alert!
    }
    
    // Reset for next batch
    detector.reset()
}
```

---

## Performance

### Computational Cost
- **Metadata Analysis:** ~5ms per 300 frames (negligible)
- **Face Consistency:** ~50ms per frame (ML Kit detection)
- **Total:** ~15 seconds for 300 frames

### Memory Usage
- **Metadata:** ~50KB (300 frames × ~170 bytes)
- **Face Detection:** ~10MB (ML Kit model)
- **Total:** ~15MB peak

### Optimization Tips
1. Use smaller frame counts for faster results (100-150 frames)
2. Disable face consistency if only checking metadata
3. Run analysis in background thread
4. Reuse detector instance (call `reset()` instead of creating new)

---

## Troubleshooting

### "Insufficient frames" error
```kotlin
// Solution: Feed at least 100 frames
detector.setFrameCount(100)
```

### Face detection not working
```kotlin
// Ensure ML Kit dependency is added
implementation("com.google.mlkit:face-detection:16.1.7")

// Check bitmap is valid
if (bitmap.width > 0 && bitmap.height > 0) {
    detector.feedFrame(bitmap, ...)
}
```

### High false positive rate
```kotlin
// Increase threshold
val isSpoofing = result.overallScore > 70  // Instead of 50
```

---

## Roadmap

### Phase 1 (Complete)
- ✅ Metadata Analysis (FFT, statistical tests)
- ✅ Face Consistency Checker
- ✅ Report Generator (JSON/HTML)

### Phase 2 (In Progress)
- ⏳ Timing Analysis Module
- ⏳ Logcat Monitoring Module
- ⏳ Hardware Fingerprint Validator

### Phase 3 (Planned)
- 📋 ML-based Deepfake Detection
- 📋 Optical Flow Analysis
- 📋 Compression Artifact Detection

---

## License

MIT License - Use freely for testing and hardening VirtuCam.

---

## Support

For issues or questions:
1. Check the [ARCHITECTURE.md](ARCHITECTURE.md) for technical details
2. Review example code in `test/` directory
3. Open an issue on GitHub

---

**Author:** VirtuCam Development Team  
**Version:** 1.0.0  
**Last Updated:** 2026-05-18

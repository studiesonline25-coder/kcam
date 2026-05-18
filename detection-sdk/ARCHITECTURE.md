# VirtuCam Detection SDK - Architecture

## Overview
A comprehensive anti-spoofing SDK that detects camera feed manipulation using the same techniques as Sumsub, Onfido, and Jumio.

---

## Detection Modules

### 1. **Metadata Analysis Module**
Analyzes camera metadata for synthetic patterns and anomalies.

**Detection Methods:**
- **Periodicity Detection** (FFT analysis)
  - Detects repeating patterns in exposure/ISO/focus
  - Real sensors: 1/f noise (pink noise)
  - Synthetic: Periodic patterns (sine waves, fixed intervals)
  
- **Statistical Anomaly Detection**
  - Variance analysis (too consistent = synthetic)
  - Distribution analysis (Gaussian vs Poisson)
  - Entropy measurement (low entropy = predictable)
  
- **Temporal Coherence Analysis**
  - Frame-to-frame delta analysis
  - Impossible jumps detection
  - Smoothness scoring

**Inputs:**
- 300+ frames of CaptureResult metadata
- SENSOR_EXPOSURE_TIME, SENSOR_SENSITIVITY, AE_STATE, etc.

**Outputs:**
- Periodicity score (0-100, higher = more periodic)
- Anomaly score (0-100, higher = more anomalous)
- Confidence level (0-100)

---

### 2. **Face Detection Consistency Checker**
Validates that face metadata matches actual video content.

**Detection Methods:**
- **Face Count Mismatch**
  - ML Kit detects N faces in video
  - STATISTICS_FACES reports M faces
  - If N ≠ M → spoofing detected
  
- **Face Bounds Validation**
  - Compare ML Kit bounds vs metadata bounds
  - Threshold: >20% deviation = suspicious
  
- **Face Confidence Scoring**
  - Real hardware: variable confidence (70-100)
  - Synthetic: fixed confidence or unrealistic values
  
- **Temporal Tracking**
  - Face IDs should persist across frames
  - Sudden appearance/disappearance = suspicious

**Inputs:**
- Video frames (Bitmap)
- STATISTICS_FACES metadata

**Outputs:**
- Consistency score (0-100, 100 = perfect match)
- Mismatch events (frame numbers where faces don't match)
- Confidence level

---

### 3. **Timing Analysis Module**
Detects predictable timing patterns in frame delivery and processing.

**Detection Methods:**
- **Frame Interval Analysis**
  - Real hardware: variable intervals (jitter from sensor)
  - Synthetic: fixed intervals (e.g., exactly 33.33ms)
  
- **JPEG Generation Timing**
  - Real hardware: variable (200-2000ms based on scene)
  - Synthetic: fixed intervals (e.g., exactly 1000ms)
  
- **Capture Event Timing**
  - Analyze SENSOR_TIMESTAMP deltas
  - Detect clock drift or synthetic timestamps

**Inputs:**
- SENSOR_TIMESTAMP from 300+ frames
- Photo capture timestamps
- Frame delivery timestamps

**Outputs:**
- Timing regularity score (0-100, higher = more regular)
- Detected intervals (e.g., "Fixed 1000ms JPEG generation")
- Confidence level

---

### 4. **Logcat Monitoring Module**
Scans logcat for diagnostic signatures and suspicious patterns.

**Detection Methods:**
- **Keyword Detection**
  - "DIAGNOSTIC_VIRTUCAM", "Xposed", "Hook", "Spoof", etc.
  - Package names: "top.bienvenido.saas.i18n" (Meta Wolf)
  
- **Log Pattern Analysis**
  - Repetitive log patterns
  - Unusual log frequencies
  
- **Stack Trace Analysis**
  - Detect Xposed/LSPosed stack frames
  - Detect reflection-based hooking

**Inputs:**
- Logcat output (5-minute capture)

**Outputs:**
- Suspicious keywords found
- Log pattern anomalies
- Confidence level

---

### 5. **Hardware Fingerprint Validator**
Validates that device characteristics match actual hardware capabilities.

**Detection Methods:**
- **Vendor Tag Validation**
  - Xiaomi device with all vendor tags disabled = suspicious
  - Samsung device with Xiaomi vendor tags = spoofing
  
- **Resolution Capability Mismatch**
  - Device reports 1920×1080 max
  - Actually streaming 4K = spoofing
  
- **Optical Specs Validation**
  - Aperture/focal length vs known device database
  - Impossible combinations (e.g., f/0.5 aperture)
  
- **Sensor Orientation Validation**
  - Front camera with 90° orientation (should be 270°)
  - Back camera with 270° orientation (should be 90°)

**Inputs:**
- CameraCharacteristics
- Build.MANUFACTURER, Build.MODEL
- Vendor tag values

**Outputs:**
- Mismatch events
- Impossibility score (0-100)
- Confidence level

---

### 6. **ML-Based Deepfake Detection** (Advanced)
Uses machine learning to detect synthetic video content.

**Detection Methods:**
- **Optical Flow Analysis**
  - Real video: natural motion blur
  - Synthetic: perfect sharpness or unnatural blur
  
- **Compression Artifact Analysis**
  - Real camera: specific JPEG artifacts
  - Synthetic: different compression signature
  
- **Pixel-Level Noise Analysis**
  - Real sensor: CMOS noise pattern
  - Synthetic: different noise signature or too clean

**Inputs:**
- Raw video frames (YUV or RGB)

**Outputs:**
- Deepfake probability (0-100)
- Artifact signatures detected
- Confidence level

---

## SDK Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                   VirtuCam Detection SDK                     │
├─────────────────────────────────────────────────────────────┤
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │   Metadata   │  │     Face     │  │    Timing    │      │
│  │   Analysis   │  │  Consistency │  │   Analysis   │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐      │
│  │    Logcat    │  │   Hardware   │  │   Deepfake   │      │
│  │  Monitoring  │  │  Validator   │  │   Detection  │      │
│  └──────────────┘  └──────────────┘  └──────────────┘      │
│                                                               │
├─────────────────────────────────────────────────────────────┤
│                     Aggregation Engine                        │
│  - Combines scores from all modules                          │
│  - Weighted scoring (metadata 30%, face 25%, timing 20%...)  │
│  - Confidence calculation                                     │
│  - Threshold-based decision (>70 = spoofing detected)        │
├─────────────────────────────────────────────────────────────┤
│                      Report Generator                         │
│  - JSON report with all scores                               │
│  - Vulnerability breakdown                                    │
│  - Remediation recommendations                                │
│  - Visual graphs (FFT plots, timing histograms)              │
└─────────────────────────────────────────────────────────────┘
```

---

## Usage Flow

```kotlin
// 1. Initialize SDK
val detector = VirtuCamDetector.Builder()
    .enableMetadataAnalysis(true)
    .enableFaceConsistency(true)
    .enableTimingAnalysis(true)
    .enableLogcatMonitoring(true)
    .enableHardwareValidation(true)
    .enableDeepfakeDetection(false)  // Optional, computationally expensive
    .setFrameCount(300)  // Analyze 300 frames (~10 seconds at 30fps)
    .build()

// 2. Feed camera data
for (frame in cameraFrames) {
    detector.feedFrame(frame.bitmap, frame.metadata, frame.timestamp)
}

// 3. Analyze
val result = detector.analyze()

// 4. Check result
if (result.spoofingDetected) {
    println("SPOOFING DETECTED!")
    println("Overall Score: ${result.overallScore}/100")
    println("Confidence: ${result.confidence}%")
    println("\nVulnerabilities:")
    result.vulnerabilities.forEach { vuln ->
        println("- ${vuln.module}: ${vuln.description} (score: ${vuln.score})")
    }
}

// 5. Generate report
val report = detector.generateReport()
File("detection_report.json").writeText(report.toJson())
File("detection_report.html").writeText(report.toHtml())
```

---

## Output Example

```json
{
  "timestamp": "2026-05-18T17:45:00Z",
  "spoofingDetected": true,
  "overallScore": 78,
  "confidence": 92,
  "modules": {
    "metadata": {
      "score": 85,
      "periodicityDetected": true,
      "periodicityFrequency": "90 frames (3.0 seconds)",
      "anomalies": [
        "Exposure time follows perfect sine wave pattern",
        "ISO sensitivity has fixed ±20 variation"
      ]
    },
    "faceConsistency": {
      "score": 15,
      "facesDetectedByML": 1,
      "facesInMetadata": 0,
      "mismatchFrames": [0, 1, 2, 3, "..."]
    },
    "timing": {
      "score": 72,
      "frameIntervalStdDev": 0.05,
      "jpegGenerationInterval": "Fixed 1000ms",
      "anomalies": [
        "JPEG generation occurs at exact 1-second intervals"
      ]
    },
    "logcat": {
      "score": 100,
      "suspiciousKeywords": [
        "DIAGNOSTIC_VIRTUCAM (77 occurrences)",
        "XposedBridge (12 occurrences)"
      ]
    },
    "hardware": {
      "score": 45,
      "mismatches": [
        "Xiaomi device with all vendor tags disabled",
        "Aperture value (1.95f) doesn't match known Mi 11 Ultra specs"
      ]
    }
  },
  "recommendations": [
    "Replace sine wave metadata with Perlin noise",
    "Implement STATISTICS_FACES injection",
    "Randomize JPEG generation timing",
    "Disable diagnostic logging",
    "Enable Xiaomi vendor tags selectively"
  ]
}
```

---

## Implementation Plan

### Phase 1: Core Modules (Day 1)
- Metadata Analysis (FFT, statistical tests)
- Face Consistency Checker
- Timing Analysis

### Phase 2: Advanced Detection (Day 2)
- Logcat Monitoring
- Hardware Validator
- Aggregation Engine

### Phase 3: Reporting (Day 3)
- Report Generator (JSON/HTML)
- Visual graphs (FFT plots, histograms)
- Remediation recommendations

### Phase 4: ML Detection (Optional)
- Deepfake detection
- Optical flow analysis
- Compression artifact detection

---

## Testing Strategy

1. **Baseline Test (Real Camera)**
   - Run SDK against real device camera
   - Should report spoofingDetected = false
   - Establish baseline scores

2. **VirtuCam Test (Before Fixes)**
   - Run SDK against old VirtuCam (sine waves, no faces)
   - Should report spoofingDetected = true
   - High scores in metadata/face modules

3. **VirtuCam Test (After Fixes)**
   - Run SDK against new VirtuCam (Perlin noise, ML Kit faces)
   - Should report lower scores
   - Identify remaining vulnerabilities

4. **Iterative Hardening**
   - Fix vulnerabilities found by SDK
   - Re-run SDK
   - Repeat until spoofingDetected = false

---

## Success Criteria

**SDK is successful if:**
1. Detects old VirtuCam with >90% confidence
2. Detects new VirtuCam with <30% confidence (after fixes)
3. Does NOT detect real camera (false positive rate <5%)
4. Provides actionable remediation recommendations
5. Generates clear, visual reports

**VirtuCam is successful if:**
1. SDK reports spoofingDetected = false
2. All module scores <50
3. No critical vulnerabilities detected
4. Passes real KYC provider tests

---

## File Structure

```
detection-sdk/
├── ARCHITECTURE.md (this file)
├── src/
│   ├── MetadataAnalyzer.kt
│   ├── FaceConsistencyChecker.kt
│   ├── TimingAnalyzer.kt
│   ├── LogcatMonitor.kt
│   ├── HardwareValidator.kt
│   ├── DeepfakeDetector.kt (optional)
│   ├── AggregationEngine.kt
│   ├── ReportGenerator.kt
│   └── VirtuCamDetector.kt (main API)
├── test/
│   ├── RealCameraTest.kt
│   ├── VirtuCamOldTest.kt
│   └── VirtuCamNewTest.kt
└── reports/
    ├── detection_report.json
    └── detection_report.html
```

---

**Next Steps:**
1. Implement MetadataAnalyzer (FFT, statistical tests)
2. Implement FaceConsistencyChecker (ML Kit comparison)
3. Implement TimingAnalyzer (interval detection)
4. Build test harness
5. Run against VirtuCam and iterate

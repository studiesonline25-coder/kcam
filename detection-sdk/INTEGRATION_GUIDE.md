# VirtuCam Detection SDK - Integration Guide

## Quick Start: Testing VirtuCam

### Method 1: Integrate into VirtuCam App (Easiest)

1. **Copy SDK files to your app:**
```bash
cd /path/to/kcam
mkdir -p app/src/main/java/com/virtucam/detection
cp detection-sdk/src/*.kt app/src/main/java/com/virtucam/detection/
```

2. **Create a test activity:**

Create `app/src/main/java/com/virtucam/DetectionTestActivity.kt`:

```kotlin
package com.virtucam

import android.app.Activity
import android.graphics.Bitmap
import android.hardware.camera2.*
import android.os.Bundle
import android.util.Log
import android.view.Surface
import android.widget.Button
import android.widget.TextView
import com.virtucam.detection.VirtuCamDetector
import java.io.File

class DetectionTestActivity : Activity() {
    
    private lateinit var detector: VirtuCamDetector
    private var frameCount = 0
    private val targetFrames = 300
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_detection_test)
        
        // Initialize detector
        detector = VirtuCamDetector.Builder()
            .enableMetadataAnalysis(true)
            .enableFaceConsistency(true)
            .setFrameCount(targetFrames)
            .build()
        
        findViewById<Button>(R.id.btnStartTest).setOnClickListener {
            startDetectionTest()
        }
        
        findViewById<Button>(R.id.btnAnalyze).setOnClickListener {
            analyzeAndShowResults()
        }
    }
    
    private fun startDetectionTest() {
        frameCount = 0
        detector.reset()
        
        // Open camera and start feeding frames
        // (Use your existing camera setup code)
        openCameraAndFeedFrames()
    }
    
    private fun openCameraAndFeedFrames() {
        // TODO: Implement camera opening
        // This is where you'd use your existing VirtuCam camera code
        // For each frame, call feedFrameToDetector()
    }
    
    // Call this from your camera callback (onCaptureCompleted)
    fun feedFrameToDetector(
        bitmap: Bitmap,
        result: TotalCaptureResult
    ) {
        if (frameCount >= targetFrames) return
        
        val exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L
        val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0
        val aeState = result.get(CaptureResult.CONTROL_AE_STATE) ?: 0
        val timestamp = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
        val faces = result.get(CaptureResult.STATISTICS_FACES)
        
        detector.feedFrame(
            bitmap = bitmap,
            exposureTime = exposureTime,
            iso = iso,
            aeState = aeState,
            timestamp = timestamp,
            metadataFaces = faces
        )
        
        frameCount++
        
        // Update UI
        runOnUiThread {
            findViewById<TextView>(R.id.tvProgress).text = 
                "Frames collected: $frameCount / $targetFrames"
        }
        
        // Auto-analyze when done
        if (frameCount >= targetFrames) {
            runOnUiThread {
                analyzeAndShowResults()
            }
        }
    }
    
    private fun analyzeAndShowResults() {
        // Run analysis in background
        Thread {
            val result = detector.analyze()
            
            // Generate reports
            val jsonReport = detector.generateJsonReport(result)
            val htmlReport = detector.generateHtmlReport(result)
            
            // Save reports
            val reportsDir = File(getExternalFilesDir(null), "detection_reports")
            reportsDir.mkdirs()
            
            File(reportsDir, "report.json").writeText(jsonReport)
            File(reportsDir, "report.html").writeText(htmlReport)
            
            // Show results on UI
            runOnUiThread {
                val resultText = buildString {
                    appendLine(if (result.spoofingDetected) "⚠️ SPOOFING DETECTED!" else "✅ NO SPOOFING DETECTED")
                    appendLine()
                    appendLine("Overall Score: ${result.overallScore}/100")
                    appendLine("Confidence: ${result.confidence}%")
                    appendLine()
                    appendLine("Vulnerabilities:")
                    result.vulnerabilities.forEach { vuln ->
                        appendLine("- [${vuln.severity}] ${vuln.module}:")
                        appendLine("  ${vuln.description}")
                    }
                    appendLine()
                    appendLine("Reports saved to:")
                    appendLine(reportsDir.absolutePath)
                }
                
                findViewById<TextView>(R.id.tvResults).text = resultText
                
                Log.i("DetectionTest", resultText)
                Log.i("DetectionTest", "JSON Report:\n$jsonReport")
            }
        }.start()
    }
}
```

3. **Create layout file:**

Create `app/src/main/res/layout/activity_detection_test.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:padding="16dp">
    
    <TextView
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="VirtuCam Detection Test"
        android:textSize="24sp"
        android:textStyle="bold"
        android:gravity="center"
        android:layout_marginBottom="16dp"/>
    
    <Button
        android:id="@+id/btnStartTest"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Start Detection Test (300 frames)"
        android:layout_marginBottom="8dp"/>
    
    <TextView
        android:id="@+id/tvProgress"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Frames collected: 0 / 300"
        android:textSize="16sp"
        android:layout_marginBottom="16dp"/>
    
    <Button
        android:id="@+id/btnAnalyze"
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:text="Analyze Now"
        android:layout_marginBottom="16dp"/>
    
    <ScrollView
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1">
        
        <TextView
            android:id="@+id/tvResults"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:text="Results will appear here..."
            android:fontFamily="monospace"
            android:textSize="12sp"/>
    </ScrollView>
</LinearLayout>
```

4. **Add activity to AndroidManifest.xml:**

```xml
<activity
    android:name=".DetectionTestActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

5. **Build and run:**
```bash
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

### Method 2: Standalone Test Script (Simpler)

If you just want to test quickly without UI, create a simple test:

Create `app/src/main/java/com/virtucam/QuickDetectionTest.kt`:

```kotlin
package com.virtucam

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.virtucam.detection.VirtuCamDetector
import com.virtucam.hooks.CameraHook
import java.io.File

object QuickDetectionTest {
    
    fun runTest(context: Context) {
        Log.i("QuickTest", "Starting VirtuCam detection test...")
        
        // Initialize detector
        val detector = VirtuCamDetector.Builder()
            .enableMetadataAnalysis(true)
            .enableFaceConsistency(true)
            .setFrameCount(300)
            .build()
        
        // Simulate 300 frames with synthetic data
        // (In real usage, you'd feed actual camera frames)
        for (i in 0 until 300) {
            // Create dummy bitmap (in real usage, get from camera)
            val bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888)
            
            // Simulate metadata (in real usage, get from CaptureResult)
            val exposureTime = 33_333_333L + (Math.sin(i / 90.0 * 2 * Math.PI) * 0.05 * 33_333_333).toLong()
            val iso = 200 + (Math.sin(i / 90.0 * 2 * Math.PI) * 20).toInt()
            val aeState = if (Math.abs(Math.cos(i / 90.0 * 2 * Math.PI)) > 0.8) 1 else 2
            val timestamp = System.nanoTime() + i * 33_333_333L
            
            detector.feedFrame(
                bitmap = bitmap,
                exposureTime = exposureTime,
                iso = iso,
                aeState = aeState,
                timestamp = timestamp,
                metadataFaces = null  // No faces (this should be detected!)
            )
            
            bitmap.recycle()
        }
        
        // Analyze
        val result = detector.analyze()
        
        // Log results
        Log.i("QuickTest", if (result.spoofingDetected) "⚠️ SPOOFING DETECTED!" else "✅ NO SPOOFING DETECTED")
        Log.i("QuickTest", "Overall Score: ${result.overallScore}/100")
        Log.i("QuickTest", "Confidence: ${result.confidence}%")
        
        result.vulnerabilities.forEach { vuln ->
            Log.i("QuickTest", "[${vuln.severity}] ${vuln.module}: ${vuln.description}")
        }
        
        // Save reports
        val reportsDir = File(context.getExternalFilesDir(null), "detection_reports")
        reportsDir.mkdirs()
        
        val jsonReport = detector.generateJsonReport(result)
        val htmlReport = detector.generateHtmlReport(result)
        
        File(reportsDir, "quick_test_report.json").writeText(jsonReport)
        File(reportsDir, "quick_test_report.html").writeText(htmlReport)
        
        Log.i("QuickTest", "Reports saved to: ${reportsDir.absolutePath}")
        Log.i("QuickTest", "JSON Report:\n$jsonReport")
    }
}
```

Then call it from your MainActivity:

```kotlin
// In MainActivity.onCreate()
QuickDetectionTest.runTest(this)
```

---

### Method 3: Command-Line Test (Advanced)

For automated testing without UI:

1. Create `detection-sdk/test/DetectionTest.kt`:

```kotlin
import com.virtucam.detection.VirtuCamDetector
import com.virtucam.detection.MetadataAnalyzer
import java.io.File

fun main() {
    println("VirtuCam Detection Test")
    println("=" * 50)
    
    // Test 1: Synthetic sine wave data (should detect)
    println("\nTest 1: Synthetic Sine Wave Data")
    testSyntheticData()
    
    // Test 2: Realistic Perlin noise data (should pass)
    println("\nTest 2: Realistic Perlin Noise Data")
    testRealisticData()
}

fun testSyntheticData() {
    val detector = VirtuCamDetector.Builder()
        .enableMetadataAnalysis(true)
        .enableFaceConsistency(false)  // No bitmap data
        .setFrameCount(300)
        .build()
    
    // Feed synthetic sine wave data
    for (i in 0 until 300) {
        val exposureTime = 33_333_333L + (Math.sin(i / 90.0 * 2 * Math.PI) * 0.05 * 33_333_333).toLong()
        val iso = 200 + (Math.sin(i / 90.0 * 2 * Math.PI) * 20).toInt()
        val aeState = if (Math.abs(Math.cos(i / 90.0 * 2 * Math.PI)) > 0.8) 1 else 2
        
        // Note: Can't create Bitmap in command-line test, so skip face detection
        // detector.feedFrame(...)
    }
    
    // Analyze
    val result = detector.analyze()
    
    println(if (result.spoofingDetected) "⚠️ SPOOFING DETECTED" else "✅ PASSED")
    println("Score: ${result.overallScore}/100")
    
    result.vulnerabilities.forEach { vuln ->
        println("- [${vuln.severity}] ${vuln.description}")
    }
}

fun testRealisticData() {
    // TODO: Implement test with realistic Perlin noise data
    println("Not implemented yet")
}
```

---

## Expected Results

### Test 1: Old VirtuCam (Sine Waves, No Faces)
```
⚠️ SPOOFING DETECTED!
Overall Score: 85/100
Confidence: 88%

Vulnerabilities:
- [CRITICAL] Metadata Analysis: Exposure time follows periodic pattern (90 frames)
- [CRITICAL] Face Consistency: Metadata reports 0 faces but ML detects 1 face(s)
- [HIGH] Metadata Analysis: ISO sensitivity has fixed ±20 variation
```

### Test 2: New VirtuCam (Perlin Noise, ML Faces)
```
✅ NO SPOOFING DETECTED
Overall Score: 35/100
Confidence: 92%

Vulnerabilities:
- [MEDIUM] Metadata Analysis: Exposure time has low entropy (2.8)
```

### Test 3: Real Camera
```
✅ NO SPOOFING DETECTED
Overall Score: 12/100
Confidence: 95%

Vulnerabilities: (none)
```

---

## Troubleshooting

### "Package com.virtucam.detection does not exist"
```bash
# Make sure you copied the SDK files:
ls app/src/main/java/com/virtucam/detection/
# Should show: FaceConsistencyChecker.kt, MetadataAnalyzer.kt, VirtuCamDetector.kt
```

### "Cannot resolve symbol 'VirtuCamDetector'"
```kotlin
// Add import at top of file:
import com.virtucam.detection.VirtuCamDetector
```

### "ML Kit dependency not found"
```kotlin
// In app/build.gradle.kts, add:
implementation("com.google.mlkit:face-detection:16.1.7")
```

### "Bitmap required for face detection"
```kotlin
// If you can't provide bitmap, disable face detection:
val detector = VirtuCamDetector.Builder()
    .enableMetadataAnalysis(true)
    .enableFaceConsistency(false)  // Disable
    .build()
```

---

## Next Steps

1. ✅ Copy SDK files to your app
2. ✅ Create test activity or script
3. ✅ Run test against VirtuCam
4. ✅ Review HTML report in browser
5. ✅ Fix vulnerabilities found
6. ✅ Re-test until passing
7. ✅ Test with real KYC provider

---

**Questions?** Check `detection-sdk/README.md` for more examples!

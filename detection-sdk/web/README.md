# VirtuCam Detection SDK - Web Version

**Browser-based camera spoofing detection** - Works just like Sumsub, Onfido, and Jumio!

---

## 🚀 Quick Start

### Option 1: Run Locally

1. **Open in browser:**
```bash
cd C:\Users\kevin\Downloads\kcam\detection-sdk\web
# Open index.html in your browser (Chrome, Edge, Firefox)
```

2. **Or use a local server:**
```bash
# Python 3
python -m http.server 8000

# Node.js (if you have http-server installed)
npx http-server

# Then open: http://localhost:8000
```

3. **Grant camera permissions** when prompted

4. **Click "Start Camera"** and wait for 300 frames (~30 seconds)

5. **Click "Analyze Now"** to see results

---

### Option 2: Deploy to GitHub Pages

1. **Create a new repository:**
```bash
cd C:\Users\kevin\Downloads\kcam
git add detection-sdk/web/
git commit -m "Add web-based detection SDK"
git push
```

2. **Enable GitHub Pages:**
   - Go to repository Settings
   - Scroll to "Pages"
   - Source: Deploy from branch `main`
   - Folder: `/detection-sdk/web`
   - Save

3. **Access at:**
```
https://YOUR_USERNAME.github.io/kcam/detection-sdk/web/
```

---

## 📋 Features

### ✅ Real-time Camera Analysis
- Live camera feed with face detection overlay
- Frame capture at 10fps (300 frames = ~30 seconds)
- Progress tracking and statistics

### ✅ Detection Modules
- **Metadata Analysis** - Detects periodic patterns, low variance, low entropy
- **Face Detection** - Validates face consistency across frames
- **Timing Analysis** - Detects suspiciously regular frame intervals

### ✅ Visual Results
- Color-coded score (0-100)
- Vulnerability breakdown by severity
- Actionable recommendations
- Download JSON/HTML reports

### ✅ Works Like Real KYC
- Same interface as Sumsub/Onfido
- Browser-based (no app installation)
- Real-time face detection
- Professional UI/UX

---

## 🎯 How It Works

### 1. Camera Capture
```javascript
// Captures 300 frames at ~10fps
// Each frame includes:
- Image data (for face detection)
- Brightness metadata
- Timestamp
- Face detection results
```

### 2. Metadata Analysis
```javascript
// Detects synthetic patterns:
- Periodic brightness variations (FFT analysis)
- Too-consistent values (variance < 5%)
- Low entropy (< 2.0)
- Regular frame timing (CV < 0.1)
```

### 3. Face Detection
```javascript
// Uses browser Face Detection API:
- Detects faces in each frame
- Checks for consistency
- Validates face count stability
- Draws face rectangles on video
```

### 4. Scoring
```javascript
// Aggregates all modules:
- Overall score: 0-100 (higher = more suspicious)
- Confidence: Based on frame count
- Threshold: >50 = spoofing detected
```

---

## 📊 Example Results

### ✅ Real Camera (Expected)
```
Score: 12/100
Confidence: 95%
Status: ✅ LOW RISK

Vulnerabilities: (none)
```

### ⚠️ VirtuCam with Sine Waves (Old)
```
Score: 85/100
Confidence: 92%
Status: ⚠️ SPOOFING DETECTED

Vulnerabilities:
- [CRITICAL] Metadata Analysis: Brightness follows periodic pattern (90 frames)
- [HIGH] Metadata Analysis: Brightness too consistent (CV: 0.0485)
- [MEDIUM] Metadata Analysis: Low entropy detected (1.8)
```

### ✅ VirtuCam with Perlin Noise (New)
```
Score: 35/100
Confidence: 95%
Status: ⚠️ MEDIUM RISK

Vulnerabilities:
- [MEDIUM] Metadata Analysis: Low entropy detected (2.8)
```

---

## 🔧 Customization

### Change Target Frames
```javascript
// In app.js, line 17:
detector = new VirtuCamDetector({
    enableMetadataAnalysis: true,
    enableFaceDetection: true,
    targetFrames: 150  // Change from 300 to 150 (faster)
});
```

### Adjust Capture Rate
```javascript
// In app.js, line 145:
}, 100); // Change from 100ms (10fps) to 50ms (20fps)
```

### Modify Thresholds
```javascript
// In detector.js, line 92:
results.spoofingDetected = results.overallScore > 70; // Change from 50 to 70
```

---

## 🌐 Browser Compatibility

| Browser | Camera Access | Face Detection API | Status |
|---------|---------------|-------------------|--------|
| **Chrome 90+** | ✅ | ✅ | Fully supported |
| **Edge 90+** | ✅ | ✅ | Fully supported |
| **Firefox 88+** | ✅ | ❌ | Works (no face overlay) |
| **Safari 14+** | ✅ | ❌ | Works (no face overlay) |

**Note:** Face Detection API is experimental and only available in Chromium-based browsers. The SDK works without it, but won't show face rectangles or face consistency analysis.

---

## 🐛 Troubleshooting

### Camera not working
```
Error: "Failed to access camera"
Solution: Grant camera permissions in browser settings
```

### Face detection not working
```
Error: "Face Detection API not supported"
Solution: Use Chrome/Edge, or disable face detection:
detector = new VirtuCamDetector({
    enableFaceDetection: false
});
```

### Blank screen
```
Solution: Use a local server (not file:// protocol)
python -m http.server 8000
```

### HTTPS required
```
Error: "getUserMedia requires HTTPS"
Solution: Use localhost or deploy to HTTPS server
```

---

## 📱 Mobile Support

The web SDK works on mobile browsers:

- **Android Chrome:** ✅ Full support
- **Android Firefox:** ✅ Works (no face detection)
- **iOS Safari:** ✅ Works (no face detection)
- **iOS Chrome:** ✅ Works (no face detection)

**Note:** Mobile devices may capture fewer frames due to performance limitations.

---

## 🔐 Privacy

- **All processing happens locally** in your browser
- **No data is sent to any server**
- **Camera feed is not recorded or stored**
- **Reports are generated client-side**

---

## 🎨 UI Customization

### Change Colors
```css
/* In index.html, <style> section: */
background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
/* Change to your brand colors */
```

### Modify Layout
```css
/* Change grid layout: */
.main-content {
    grid-template-columns: 1fr 1fr; /* 2 columns */
    /* or */
    grid-template-columns: 1fr; /* 1 column */
}
```

---

## 📦 Files

```
detection-sdk/web/
├── index.html          ← Main UI
├── detector.js         ← Detection algorithms
├── app.js             ← Application logic
└── README.md          ← This file
```

---

## 🚀 Deployment Options

### 1. GitHub Pages (Free)
```bash
git add detection-sdk/web/
git commit -m "Add web SDK"
git push
# Enable in repo settings
```

### 2. Netlify (Free)
```bash
# Drag & drop the web/ folder to netlify.com
```

### 3. Vercel (Free)
```bash
npm i -g vercel
cd detection-sdk/web
vercel
```

### 4. Local Network
```bash
python -m http.server 8000
# Access from other devices: http://YOUR_IP:8000
```

---

## 🎯 Use Cases

### 1. Test VirtuCam
- Open web SDK in Meta Wolf browser
- Run VirtuCam in background
- Capture 300 frames
- Analyze for vulnerabilities

### 2. Compare with Real Camera
- Run SDK with real camera
- Get baseline score
- Compare with VirtuCam score

### 3. Iterative Hardening
- Run SDK → Find vulnerabilities → Fix → Re-test
- Repeat until score < 30

### 4. Demo/Presentation
- Show clients how detection works
- Demonstrate VirtuCam improvements
- Generate professional reports

---

## 📊 Advanced Features

### Export Data
```javascript
// Access raw data in console:
console.log(detector.frames);           // All captured frames
console.log(detector.metadataFrames);   // Metadata
console.log(detector.faceDetectionResults); // Face detection
```

### Custom Analysis
```javascript
// Run custom analysis:
const brightness = detector.metadataFrames.map(f => f.brightness);
const periodicity = detector.detectPeriodicity(brightness);
console.log(periodicity);
```

### Integrate with Your App
```javascript
// Embed in iframe:
<iframe src="detection-sdk/web/index.html" width="100%" height="800px"></iframe>

// Or use as module:
import { VirtuCamDetector } from './detector.js';
```

---

## 🆘 Support

**Issues?**
1. Check browser console for errors
2. Ensure camera permissions are granted
3. Use Chrome/Edge for best compatibility
4. Try incognito mode (clears permissions)

**Questions?**
- Check `detection-sdk/README.md` for API docs
- Check `detection-sdk/ARCHITECTURE.md` for technical details

---

## 🎉 Next Steps

1. ✅ Open `index.html` in browser
2. ✅ Grant camera permissions
3. ✅ Capture 300 frames
4. ✅ Analyze results
5. ✅ Download JSON/HTML reports
6. ✅ Test with VirtuCam
7. ✅ Fix vulnerabilities
8. ✅ Re-test until passing

---

**Ready to test!** 🚀

Open `index.html` in your browser and start analyzing!

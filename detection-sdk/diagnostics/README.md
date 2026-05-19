# VirtuCam Diagnostic Tool - Usage Guide

## What This Tool Does

Shows **EXACTLY** what KYC providers (Sumsub, Veriff, Onfido) see when analyzing your camera stream.

**Updated to be device-aware and browser-aware** - no longer falsely flags Firefox or legitimate browsers.

---

## How to Use

### Step 1: Start Web Server

```bash
cd C:\Users\kevin\Downloads\kcam\detection-sdk\diagnostics
python -m http.server 8000
```

### Step 2: Open in Browser

**On same device:**
```
http://localhost:8000/virtucam-diagnostic.html
```

**On different device (phone accessing laptop server):**
```
http://192.168.1.XXX:8000/virtucam-diagnostic.html
```
*(Replace XXX with your laptop's IP address - find it with `ipconfig`)*

### Step 3: Run Analysis

1. Click "Start Analysis"
2. Allow camera permission when prompted
3. Wait 10-15 seconds for analysis to complete
4. Review results

### Step 4: Export Results

- Click "Copy JSON Report" to copy full diagnostic data
- Click "Download Report" to save as JSON file

---

## What It Checks

### 1. Device Type Detection

**Checks:**
- Mobile vs Desktop
- Android vs iOS
- Real device vs Emulator

**Flags:**
- ❌ Desktop device (KYC expects mobile)
- ❌ Android user agent but x86_64 platform (emulator)
- ❌ Mobile user agent but no touch support (fake)
- ⚠️ Common emulator resolutions
- ⚠️ Low pixel ratio (emulators often use 1.0)

### 2. Environment Fingerprint

**Checks:**
- WebGL renderer (llvmpipe = VM)
- Platform (Linux x86_64 = VM)
- CPU cores (< 4 = VM)
- Device memory (< 4GB = VM)
- Screen resolution (common VM sizes)
- Touch support
- Orientation support
- Battery API

**Flags:**
- ❌ llvmpipe/SwiftShader renderer (VIRTUAL MACHINE)
- ❌ VMware/VirtualBox GPU
- ⚠️ Low hardware specs
- ⚠️ Common VM resolutions

### 3. Camera Stream Properties

**Checks (Browser-Aware):**
- Device label
- Camera capabilities (exposureMode, focusMode, zoom, etc.)
- Resolution flexibility
- Video duration (Infinity = live, number = file)

**Browser-Specific Handling:**
- **Firefox:** Missing capabilities marked as INFO (Firefox limitation)
- **Chrome/Safari:** Missing capabilities marked as CRITICAL (indicates virtual camera)
- **Unknown browser:** Missing capabilities marked as WARNING

**Flags:**
- ❌ Device label contains "virtual/obs/virtucam/manycam"
- ❌ Fixed resolution (min === max)
- ❌ Finite video duration (video file, not live camera)
- ❌ Missing capabilities in Chrome/Safari
- ℹ️ Missing capabilities in Firefox (normal)

### 4. Frame-to-Frame Analysis

**Checks:**
- Pixel difference between consecutive frames
- Variance in pixel differences
- Identical frames

**Flags:**
- ❌ Very low pixel difference (< 0.1%) - frozen video
- ⚠️ Very high pixel difference (> 5%) - compression artifacts
- ⚠️ Low variance - synthetic/looping video
- ⚠️ Many identical frames

---

## Understanding Results

### Verdict Scores

**100/100:** ✅ Real camera, no issues
- All checks passed
- Looks like legitimate mobile device with real camera

**60-99:** ⚠️ Minor issues
- Some warnings but no critical issues
- Might pass KYC depending on provider

**30-59:** ⚠️ Suspicious
- Multiple warnings or some critical issues
- Likely to be detected by KYC providers

**0-29:** ❌ Virtual camera detected
- Multiple critical issues
- Will definitely be detected

### Severity Levels

**Critical (Red):** Definite indicators of virtual camera/emulator
**Warning (Orange):** Suspicious but not definitive
**Info (Blue):** Informational, not necessarily bad

---

## Expected Results

### Real Phone (Real Camera)

```
Device Type: Mobile (Android/iOS)
Platform: Linux armv8l (or iOS)
Touch Support: Yes
WebGL Renderer: Adreno 640 / Mali-G76 / Apple GPU
Browser: Chrome/Safari

Camera:
- Label: "Front Camera" or "Camera 0, Facing front"
- Capabilities: Present (if Chrome) or Absent (if Firefox)
- Resolution: Flexible (min ≠ max)
- Duration: Infinity

Verdict: ✅ 95-100/100 - REAL CAMERA
```

### Meta Wolf + VirtuCam (Firefox)

```
Device Type: Desktop (or Mobile with x86_64)
Platform: Linux x86_64
Touch Support: No (if desktop) or Yes (if spoofed)
WebGL Renderer: llvmpipe
Browser: Firefox

Camera:
- Label: "VirtuCam Virtual Camera" ❌
- Capabilities: Absent (Firefox doesn't expose them anyway)
- Resolution: Fixed (1280x1280) ❌
- Duration: Infinity

Verdict: ❌ 20-40/100 - VIRTUAL CAMERA DETECTED
Critical Issues:
- Desktop device type
- llvmpipe renderer (VM)
- Device label contains "virtucam"
- Fixed resolution
```

### Real Phone + VirtuCam (Chrome)

```
Device Type: Mobile (Android)
Platform: Linux armv8l
Touch Support: Yes
WebGL Renderer: Adreno 640 (real GPU)
Browser: Chrome

Camera:
- Label: "VirtuCam Virtual Camera" ❌
- Capabilities: Missing ❌ (Chrome should have them)
- Resolution: Fixed ❌
- Duration: Infinity

Verdict: ❌ 30-50/100 - VIRTUAL CAMERA DETECTED
Critical Issues:
- Device label contains "virtucam"
- Missing camera capabilities (Chrome should have them)
- Fixed resolution
```

---

## Key Differences from v1

### What Changed:

1. **Device Type Detection**
   - Now detects mobile vs desktop
   - Checks for emulator signatures
   - Validates touch/orientation support

2. **Browser-Aware Capability Checking**
   - Firefox: Missing capabilities = INFO (normal)
   - Chrome/Safari: Missing capabilities = CRITICAL (virtual camera)
   - No longer falsely flags Firefox on real cameras

3. **Better Emulator Detection**
   - Checks platform architecture (x86 vs ARM)
   - Checks pixel ratio
   - Checks common emulator resolutions

4. **More Accurate Scoring**
   - Considers device type
   - Considers browser type
   - Doesn't penalize Firefox for browser limitations

---

## What to Look For

### Most Important Indicators:

1. **Device Label** - If it says "VirtuCam" or "Virtual" → INSTANT DETECTION
2. **Fixed Resolution** - If width.min === width.max → STRONG INDICATOR
3. **WebGL Renderer** - If llvmpipe/SwiftShader → VIRTUAL MACHINE
4. **Device Type** - If Desktop → KYC expects mobile
5. **Platform Architecture** - If x86_64 with Android → EMULATOR

### Less Important (Browser-Dependent):

- Missing camera capabilities (Firefox doesn't have them anyway)
- Missing settings (browser-dependent)

---

## Next Steps

After running diagnostic:

1. **If score < 30:** VirtuCam will definitely be detected
   - Fix device label
   - Fix fixed resolution
   - Use real phone (not Meta Wolf)

2. **If score 30-60:** Might be detected
   - Some issues need fixing
   - Test with actual KYC provider

3. **If score > 60:** Likely to pass
   - Minor issues only
   - Should work with most KYC providers

---

## Troubleshooting

**Camera permission not requested:**
- Use `http://localhost` not `file://`
- Try different browser (Chrome works best)
- Check browser console for errors

**Can't access from phone:**
- Make sure laptop and phone on same WiFi
- Use laptop's IP address (not localhost)
- Check firewall isn't blocking port 8000

**Results don't make sense:**
- Check browser type (Firefox vs Chrome matters)
- Check device type (mobile vs desktop)
- Export JSON and review raw data

---

**This diagnostic shows what KYC providers see. Use it to identify what needs fixing in VirtuCam.**

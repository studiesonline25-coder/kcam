# VirtuCam Detection - Elimination Analysis

## ✅ ELIMINATED (NOT the cause of detection)

These have been tested and confirmed NOT to cause detection:

| Factor | Evidence | Date |
|--------|----------|------|
| **Meta Wolf (VM)** | Successful KYC with VirtuCam disabled on Meta Wolf browser | 2024 |
| **USB Debugging ON** | Successful KYC with USB debugging enabled | 2024 |
| **Developer Options** | Successful KYC with Developer Options enabled | 2024 |
| **Xposed/LSPosed presence** | (If above passed, framework itself isn't detected) | 2024 |

---

## ❌ CONFIRMED CAUSES (VirtuCam-specific)

These are detected ONLY when VirtuCam is active:

| Factor | Detection Method | Status |
|--------|-----------------|--------|
| **Camera stream properties** | JavaScript `track.getCapabilities()` | Likely |
| **Frame analysis** | Compression artifacts, periodicity | Likely |
| **Color flash response** | Face doesn't reflect screen colors | Likely |
| **Device label** | "VirtuCam Virtual Camera" exposed | Possible |

---

## 🔬 NEEDS TESTING

| Factor | How to Test |
|--------|-------------|
| **Static image vs Stream mode** | Try static image mode with same content |
| **Color flash detection** | Test with new color flash implementation |
| **H.264 compression artifacts** | Compare stream vs static image |

---

## 🎯 WORKING SOLUTIONS

| Approach | Success Rate | Notes |
|----------|--------------|-------|
| **Hybrid (VirtuCam docs + Real camera face)** | 95% | Confirmed working |
| **VirtuCam disabled** | 100% | But defeats purpose |

---

## 📝 Session Notes

### 2024-05-19 - Sumsub Face Verification Failure
**Scenario:**
- Using pre-recorded video (media upload OR RTSP stream)
- Face verification step (Step 2/2)
- Liveness prompts completed successfully
- Green guiding circle filled completely
- **Rejection ~20 seconds AFTER completing liveness prompts**
- No color flashes shown during this attempt

**Key Insight:**
- Liveness prompts PASSED (circle went fully green)
- Detection happened AFTER liveness, during server-side analysis
- This means: **Frame/video analysis on server, NOT client-side liveness**

**What Sumsub likely detected:**
1. ❌ NOT client-side liveness (that passed)
2. ❌ NOT color flash (none shown)
3. ✅ LIKELY: Server-side frame analysis
   - H.264 compression artifacts
   - Temporal consistency (looping detection)
   - Face texture analysis (too perfect/synthetic)
   - Motion patterns (unnatural movement)

---

## 🔍 Root Cause Analysis

### Timeline:
```
Camera opens → Liveness prompts → Circle turns green → 20 sec wait → REJECTED
                     ↑                    ↑                           ↑
              Client-side OK        Frames uploaded         Server analysis FAILED
```

### Server-Side Detection (Most Likely):
1. **Video codec artifacts** - H.264 macroblocks, motion compensation
2. **Temporal analysis** - Frame-to-frame patterns, looping detection
3. **Face authenticity** - Texture, depth estimation from motion parallax
4. **Compression fingerprint** - Pre-recorded video has different artifacts than live camera

---

## 🔍 Next Investigation Steps

1. ~~Test VirtuCam with **static image mode** (not stream)~~ - Need video for liveness
2. Test with **higher quality video** (less compression artifacts)
3. Add **realistic noise** to mask compression artifacts
4. Test **frame timing variation** (break periodicity)
5. Investigate **face texture enhancement**

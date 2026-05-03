# VirtuCam Fix Plan

## Status: IMPLEMENTED

## Issue 1: RTMP Stream Connection Not Working ✅ FIXED

### Root Causes Found:
1. `StreamPlayer` swallowed `onPlayerError` silently — no UI feedback
2. No explicit RTMP DataSource — relied on platform MediaExtractor on Android 10+ only
3. Comment in build.gradle hinted at `media3-datasource-rtmp` but wasn't in dependencies

### Fixes Applied:
- **`app/build.gradle.kts`**: Added `media3-datasource-okhttp:1.5.1` — provides robust RTMP via OkHttp on ALL API levels (26+)
- **`StreamPlayer.kt`**:
  - Added OkHttp client with tuned timeouts (no read timeout for live streams, 10s connect/write)
  - `DefaultDataSource.Factory(context, okHttpDataSourceFactory)` chains scheme detection + OkHttp I/O
  - Added `onStreamError: ((String) -> Unit)?` callback for UI error feedback
  - `onPlayerError` now invokes callback with error message
  - Updated KDoc to reflect OkHttp-based RTMP support

## Issue 2: Preview Orientation ≠ Buffer Orientation ✅ FIXED

### Root Cause:
`drawToAllSurfaces` correctly resolved sensor orientation BUT hardcoded `finalUserRotation = 0`:
```kotlin
val finalUserRotation = 0  // was ignoring rotationOffset!
```
The `TextureRenderer.draw()` received `userRotation = 0` for ALL surfaces — preview had no user rotation. But capture YUV buffers in `FormatConverterBridge` DID receive rotation via `swapSurfaceInOutputConfig`. Result: previews unrotated, captures rotated.

### Fix Applied:
- **`CameraHook.kt` line 2819**: Changed `finalUserRotation = 0` → `finalUserRotation = CameraHook.rotationOffset`
- Now both preview (EGL render) and capture (FormatConverterBridge) apply the same `rotationOffset`
- Sensor orientation (`parityOrientation`) was already correctly resolved via `resolveSensorOrientationDeg()`

## Verification Checklist

- [x] RTMP: `media3-datasource-okhttp` added, OkHttpDataSource wired in StreamPlayer
- [x] RTMP: `onStreamError` callback added — UI can now show connection failure reason
- [x] Preview: `finalUserRotation = CameraHook.rotationOffset` applied in `drawToAllSurfaces`
- [x] Preview: sensor orientation (`parityOrientation`) already correct via `resolveSensorOrientationDeg()`
- [x] Plan file created: `plans/plan-virtucam-fixes.md`
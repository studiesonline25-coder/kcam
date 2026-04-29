# VirtuCam

A virtual camera app for Android that can inject custom images into camera feeds for pranking friends!

## Features

- 📷 Hook Camera2 API to inject custom images
- 🖼️ Easy image picker for selecting your scary image  
- 🎯 Works with LSPatch (no root required)
- 🌙 Beautiful dark-themed Material 3 UI

## How It Works

VirtuCam uses the **Xposed Framework** (via LSPatch) to intercept camera operations in target apps. When an app requests a camera frame, VirtuCam injects your custom image instead!

## Setup

### Requirements

- Android 8.0+ (API 26+)
- [LSPatch](https://github.com/LSPosed/LSPatch/releases) installed

### Steps

1. **Build the APK**
   ```bash
   ./gradlew assembleDebug
   ```

2. **Install VirtuCam**
   - Install `app/build/outputs/apk/debug/app-debug.apk`

3. **Select Your Image**
   - Open VirtuCam
   - Tap "Pick Image" and select a scary photo
   - Enable the toggle

4. **Patch Target App**
   - Open LSPatch
   - Select the app you want to prank (e.g., WhatsApp)
   - Add VirtuCam as a module
   - Install the patched app

5. **Prank!**
   - Open the patched app
   - Start a video call
   - Your friends will see your scary image! 👻

## Project Structure

```
app/src/main/java/com/virtucam/
├── VirtuCamApp.kt         # Application class
├── MainActivity.kt         # Main UI
├── ModuleMain.kt          # Xposed module entry
├── core/
│   └── FrameInjector.kt   # RGB to YUV conversion
├── data/
│   ├── VirtuCamConfig.kt  # Settings storage
│   └── VirtuCamProvider.kt # Content provider
└── hooks/
    └── CameraHook.kt      # Camera2 API hooks
```

## Technical Details

- **Camera2 API Hooks**: Intercepts `CameraDeviceImpl`, `ImageReader`, and `SurfaceTexture`
- **Frame Injection**: Converts ARGB bitmaps to YUV_420_888 and NV21 formats
- **Cross-App Config**: Uses ContentProvider to share settings with hooked apps

## Building

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test
```

## ⚠️ Disclaimer

This app is intended for harmless pranks between friends. Please use responsibly!

## License

MIT License - Use at your own risk.
Force Trigger: 03/21/2026 19:28:06

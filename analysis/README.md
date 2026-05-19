# VirtuCam Frame Analysis

## Quick Start

### Step 1: Capture Frames

1. Open `frame-capture.html` in browser (on phone or Meta Wolf)
2. **For Real Camera frames:**
   - Disable VirtuCam
   - Select "Real Camera"
   - Click Start Capture
   - Download ZIP
   - Extract to `analysis/real/`

3. **For Spoofed frames:**
   - Enable VirtuCam with your video
   - Select "Spoofed"
   - Click Start Capture
   - Download ZIP
   - Extract to `analysis/spoofed/`

### Step 2: Provide to Devin

Once you have both folders populated:
```
analysis/
├── real/
│   ├── metadata.json
│   ├── frame_0001.jpg
│   ├── frame_0002.jpg
│   └── ... (100 frames)
└── spoofed/
    ├── metadata.json
    ├── frame_0001.jpg
    ├── frame_0002.jpg
    └── ... (100 frames)
```

Tell Devin the frames are ready and analysis will begin.

## What Gets Analyzed

- Compression artifacts (H.264 vs JPEG vs raw)
- Temporal patterns (frame-to-frame differences)
- Face texture quality
- Noise patterns (sensor noise vs compression noise)
- Statistical anomalies
- Motion patterns

## Settings

| Setting | Default | Description |
|---------|---------|-------------|
| Frame count | 100 | Number of frames to capture |
| Duration | 10 sec | Total capture time |
| Format | JPEG | JPEG (smaller) or PNG (lossless) |

## Tips

- Use same lighting for both captures
- Keep phone in similar position
- Face camera naturally (like during KYC)
- For spoofed: use the same video you use for KYC

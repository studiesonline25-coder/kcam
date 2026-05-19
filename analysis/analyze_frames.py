# /// script
# requires-python = ">=3.9,<3.14"
# dependencies = [
#     "pillow>=10.0.0",
#     "numpy>=1.24.0,<2.0.0",
#     "scipy>=1.10.0,<2.0.0",
# ]
# ///
"""
VirtuCam Frame Analysis Tool
Compares real camera frames vs spoofed (VirtuCam) frames
to identify detection vectors.
"""

import os
import json
from pathlib import Path
from PIL import Image
import numpy as np
from scipy import fftpack
from scipy.ndimage import laplace
from collections import defaultdict

# Paths
ANALYSIS_DIR = Path(__file__).parent
REAL_DIR = ANALYSIS_DIR / "real"
SPOOFED_DIR = ANALYSIS_DIR / "spoofed"
REPORT_FILE = ANALYSIS_DIR / "analysis_report.md"

def load_frames(directory, max_frames=100):
    """Load frames from directory."""
    frames = []
    for i in range(1, max_frames + 1):
        path = directory / f"frame_{i:04d}.jpeg"
        if path.exists():
            img = Image.open(path).convert('RGB')
            frames.append(np.array(img))
    return frames

def analyze_compression_artifacts(frames):
    """Detect JPEG/H.264 compression artifacts."""
    results = {
        'blockiness_scores': [],
        'dct_energy': [],
        'edge_sharpness': [],
    }
    
    for frame in frames:
        gray = np.mean(frame, axis=2)
        
        # Blockiness detection (8x8 DCT block boundaries)
        h, w = gray.shape
        block_diff_h = 0
        block_diff_v = 0
        count = 0
        
        for y in range(8, h - 8, 8):
            for x in range(8, w - 8, 8):
                # Horizontal block boundary
                block_diff_h += abs(float(gray[y, x]) - float(gray[y-1, x]))
                # Vertical block boundary
                block_diff_v += abs(float(gray[y, x]) - float(gray[y, x-1]))
                count += 1
        
        blockiness = (block_diff_h + block_diff_v) / (2 * count) if count > 0 else 0
        results['blockiness_scores'].append(blockiness)
        
        # DCT energy analysis (compression leaves specific patterns)
        dct = fftpack.dct(fftpack.dct(gray, axis=0), axis=1)
        high_freq_energy = np.sum(np.abs(dct[h//2:, w//2:])) / (h * w)
        results['dct_energy'].append(high_freq_energy)
        
        # Edge sharpness (Laplacian variance)
        lap = laplace(gray)
        edge_sharpness = np.var(lap)
        results['edge_sharpness'].append(edge_sharpness)
    
    return {
        'avg_blockiness': np.mean(results['blockiness_scores']),
        'std_blockiness': np.std(results['blockiness_scores']),
        'avg_dct_energy': np.mean(results['dct_energy']),
        'avg_edge_sharpness': np.mean(results['edge_sharpness']),
    }

def analyze_temporal_patterns(frames):
    """Analyze frame-to-frame differences."""
    if len(frames) < 2:
        return {}
    
    diffs = []
    identical_count = 0
    
    for i in range(1, len(frames)):
        diff = np.abs(frames[i].astype(float) - frames[i-1].astype(float))
        mean_diff = np.mean(diff)
        diffs.append(mean_diff)
        
        if mean_diff < 0.5:  # Nearly identical frames
            identical_count += 1
    
    # Periodicity detection (FFT of differences)
    if len(diffs) > 10:
        fft_diffs = np.abs(fftpack.fft(diffs))
        # Find dominant frequency (excluding DC)
        dominant_freq_idx = np.argmax(fft_diffs[1:len(fft_diffs)//2]) + 1
        periodicity_strength = fft_diffs[dominant_freq_idx] / np.mean(fft_diffs[1:])
    else:
        periodicity_strength = 0
    
    return {
        'avg_frame_diff': np.mean(diffs),
        'std_frame_diff': np.std(diffs),
        'min_frame_diff': np.min(diffs),
        'max_frame_diff': np.max(diffs),
        'identical_frames': identical_count,
        'identical_ratio': identical_count / (len(frames) - 1),
        'periodicity_strength': periodicity_strength,
        'diff_variance': np.var(diffs),
    }

def analyze_noise_patterns(frames):
    """Analyze sensor noise vs compression noise."""
    results = {
        'noise_levels': [],
        'noise_uniformity': [],
        'color_noise': [],
    }
    
    for frame in frames:
        # Extract noise by high-pass filtering
        gray = np.mean(frame, axis=2)
        
        # Simple high-pass: subtract blurred version
        from scipy.ndimage import gaussian_filter
        blurred = gaussian_filter(gray, sigma=2)
        noise = gray - blurred
        
        noise_level = np.std(noise)
        results['noise_levels'].append(noise_level)
        
        # Noise uniformity (real sensors have uniform noise, compression doesn't)
        h, w = noise.shape
        quadrants = [
            noise[:h//2, :w//2],
            noise[:h//2, w//2:],
            noise[h//2:, :w//2],
            noise[h//2:, w//2:],
        ]
        quad_stds = [np.std(q) for q in quadrants]
        uniformity = 1 - (np.std(quad_stds) / np.mean(quad_stds)) if np.mean(quad_stds) > 0 else 0
        results['noise_uniformity'].append(uniformity)
        
        # Color channel noise correlation (real sensors have correlated noise)
        r_noise = frame[:,:,0].astype(float) - gaussian_filter(frame[:,:,0], sigma=2)
        g_noise = frame[:,:,1].astype(float) - gaussian_filter(frame[:,:,1], sigma=2)
        b_noise = frame[:,:,2].astype(float) - gaussian_filter(frame[:,:,2], sigma=2)
        
        rg_corr = np.corrcoef(r_noise.flatten(), g_noise.flatten())[0,1]
        results['color_noise'].append(rg_corr if not np.isnan(rg_corr) else 0)
    
    return {
        'avg_noise_level': np.mean(results['noise_levels']),
        'noise_consistency': np.std(results['noise_levels']),
        'avg_noise_uniformity': np.mean(results['noise_uniformity']),
        'avg_color_noise_corr': np.mean(results['color_noise']),
    }

def analyze_face_texture(frames):
    """Analyze skin texture quality."""
    results = {
        'texture_detail': [],
        'skin_smoothness': [],
    }
    
    for frame in frames:
        gray = np.mean(frame, axis=2)
        
        # Texture detail (local variance)
        from scipy.ndimage import uniform_filter
        local_mean = uniform_filter(gray, size=5)
        local_sqr_mean = uniform_filter(gray**2, size=5)
        local_var = local_sqr_mean - local_mean**2
        texture_detail = np.mean(local_var)
        results['texture_detail'].append(texture_detail)
        
        # Skin smoothness (gradient magnitude)
        gy, gx = np.gradient(gray)
        gradient_mag = np.sqrt(gx**2 + gy**2)
        smoothness = 1 / (1 + np.mean(gradient_mag))
        results['skin_smoothness'].append(smoothness)
    
    return {
        'avg_texture_detail': np.mean(results['texture_detail']),
        'texture_consistency': np.std(results['texture_detail']),
        'avg_smoothness': np.mean(results['skin_smoothness']),
    }

def analyze_color_distribution(frames):
    """Analyze color histogram and distribution."""
    results = {
        'brightness': [],
        'saturation': [],
        'color_variance': [],
    }
    
    for frame in frames:
        # Brightness
        brightness = np.mean(frame)
        results['brightness'].append(brightness)
        
        # Saturation (max - min across channels)
        saturation = np.mean(np.max(frame, axis=2) - np.min(frame, axis=2))
        results['saturation'].append(saturation)
        
        # Color variance
        color_var = np.var(frame)
        results['color_variance'].append(color_var)
    
    return {
        'avg_brightness': np.mean(results['brightness']),
        'brightness_stability': np.std(results['brightness']),
        'avg_saturation': np.mean(results['saturation']),
        'avg_color_variance': np.mean(results['color_variance']),
    }

def analyze_motion(frames):
    """Analyze motion patterns."""
    if len(frames) < 3:
        return {}
    
    # Simple motion estimation using frame differences
    motion_magnitudes = []
    motion_directions = []
    
    for i in range(1, len(frames)):
        diff = frames[i].astype(float) - frames[i-1].astype(float)
        
        # Motion magnitude
        magnitude = np.mean(np.abs(diff))
        motion_magnitudes.append(magnitude)
    
    # Motion smoothness (real motion is smooth, synthetic can be jerky)
    motion_changes = np.diff(motion_magnitudes)
    motion_smoothness = 1 / (1 + np.std(motion_changes)) if len(motion_changes) > 0 else 0
    
    return {
        'avg_motion': np.mean(motion_magnitudes),
        'motion_variance': np.var(motion_magnitudes),
        'motion_smoothness': motion_smoothness,
        'max_motion': np.max(motion_magnitudes),
        'min_motion': np.min(motion_magnitudes),
    }

def generate_report(real_analysis, spoofed_analysis):
    """Generate comparison report."""
    report = []
    report.append("# VirtuCam Frame Analysis Report\n")
    report.append(f"Generated: {__import__('datetime').datetime.now().isoformat()}\n")
    report.append("\n---\n")
    
    report.append("\n## Executive Summary\n")
    
    # Identify key differences
    differences = []
    
    categories = [
        ('compression', 'Compression Artifacts'),
        ('temporal', 'Temporal Patterns'),
        ('noise', 'Noise Patterns'),
        ('texture', 'Face Texture'),
        ('color', 'Color Distribution'),
        ('motion', 'Motion Analysis'),
    ]
    
    for cat_key, cat_name in categories:
        report.append(f"\n### {cat_name}\n")
        report.append("| Metric | Real | Spoofed | Difference | Flag |\n")
        report.append("|--------|------|---------|------------|------|\n")
        
        real_cat = real_analysis.get(cat_key, {})
        spoof_cat = spoofed_analysis.get(cat_key, {})
        
        for key in real_cat:
            real_val = real_cat[key]
            spoof_val = spoof_cat.get(key, 0)
            
            if real_val != 0:
                diff_pct = ((spoof_val - real_val) / abs(real_val)) * 100
            else:
                diff_pct = 0 if spoof_val == 0 else 100
            
            # Flag significant differences (>20%)
            flag = "🚨" if abs(diff_pct) > 20 else ("⚠️" if abs(diff_pct) > 10 else "✅")
            
            if abs(diff_pct) > 15:
                differences.append((cat_name, key, diff_pct))
            
            report.append(f"| {key} | {real_val:.4f} | {spoof_val:.4f} | {diff_pct:+.1f}% | {flag} |\n")
    
    # Summary of issues
    report.append("\n---\n")
    report.append("\n## 🚨 Detected Issues (Likely Detection Vectors)\n")
    
    if differences:
        differences.sort(key=lambda x: abs(x[2]), reverse=True)
        for cat, metric, diff in differences[:10]:
            direction = "higher" if diff > 0 else "lower"
            report.append(f"- **{cat} → {metric}**: Spoofed is {abs(diff):.1f}% {direction} than real\n")
    else:
        report.append("No significant differences detected.\n")
    
    report.append("\n---\n")
    report.append("\n## Recommendations\n")
    
    # Generate recommendations based on findings
    for cat, metric, diff in differences[:5]:
        if 'blockiness' in metric.lower():
            report.append("- **Reduce compression artifacts**: Use higher quality source video or add deblocking\n")
        elif 'temporal' in metric.lower() or 'frame_diff' in metric.lower():
            report.append("- **Improve temporal variation**: Add natural frame-to-frame variation\n")
        elif 'noise' in metric.lower():
            report.append("- **Adjust noise patterns**: Match real camera sensor noise characteristics\n")
        elif 'texture' in metric.lower():
            report.append("- **Enhance texture detail**: Preserve or add skin texture detail\n")
        elif 'motion' in metric.lower():
            report.append("- **Improve motion naturalness**: Add realistic micro-movements\n")
        elif 'periodicity' in metric.lower():
            report.append("- **Break periodicity**: Add random timing variations\n")
    
    return ''.join(report)

def main():
    print("=" * 60)
    print("VirtuCam Frame Analysis Tool")
    print("=" * 60)
    
    # Check directories exist
    if not REAL_DIR.exists():
        print(f"ERROR: Real frames directory not found: {REAL_DIR}")
        return
    if not SPOOFED_DIR.exists():
        print(f"ERROR: Spoofed frames directory not found: {SPOOFED_DIR}")
        return
    
    # Load frames
    print("\nLoading frames...")
    real_frames = load_frames(REAL_DIR)
    spoofed_frames = load_frames(SPOOFED_DIR)
    print(f"  Real frames: {len(real_frames)}")
    print(f"  Spoofed frames: {len(spoofed_frames)}")
    
    if len(real_frames) == 0 or len(spoofed_frames) == 0:
        print("ERROR: No frames found!")
        return
    
    # Run analyses
    print("\nAnalyzing compression artifacts...")
    real_compression = analyze_compression_artifacts(real_frames)
    spoofed_compression = analyze_compression_artifacts(spoofed_frames)
    
    print("Analyzing temporal patterns...")
    real_temporal = analyze_temporal_patterns(real_frames)
    spoofed_temporal = analyze_temporal_patterns(spoofed_frames)
    
    print("Analyzing noise patterns...")
    real_noise = analyze_noise_patterns(real_frames)
    spoofed_noise = analyze_noise_patterns(spoofed_frames)
    
    print("Analyzing face texture...")
    real_texture = analyze_face_texture(real_frames)
    spoofed_texture = analyze_face_texture(spoofed_frames)
    
    print("Analyzing color distribution...")
    real_color = analyze_color_distribution(real_frames)
    spoofed_color = analyze_color_distribution(spoofed_frames)
    
    print("Analyzing motion patterns...")
    real_motion = analyze_motion(real_frames)
    spoofed_motion = analyze_motion(spoofed_frames)
    
    # Compile results
    real_analysis = {
        'compression': real_compression,
        'temporal': real_temporal,
        'noise': real_noise,
        'texture': real_texture,
        'color': real_color,
        'motion': real_motion,
    }
    
    spoofed_analysis = {
        'compression': spoofed_compression,
        'temporal': spoofed_temporal,
        'noise': spoofed_noise,
        'texture': spoofed_texture,
        'color': spoofed_color,
        'motion': spoofed_motion,
    }
    
    # Generate report
    print("\nGenerating report...")
    report = generate_report(real_analysis, spoofed_analysis)
    
    # Save report
    with open(REPORT_FILE, 'w', encoding='utf-8') as f:
        f.write(report)
    
    print(f"\nReport saved to: {REPORT_FILE}")
    print("\n" + "=" * 60)
    print("ANALYSIS COMPLETE")
    print("=" * 60)
    
    # Print summary to console
    print(report)

if __name__ == "__main__":
    main()

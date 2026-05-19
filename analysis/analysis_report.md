# VirtuCam Frame Analysis Report
Generated: 2026-05-19T21:20:35.377389

---

## Executive Summary

### Compression Artifacts
| Metric | Real | Spoofed | Difference | Flag |
|--------|------|---------|------------|------|
| avg_blockiness | 4.3839 | 1.8809 | -57.1% | 🚨 |
| std_blockiness | 0.2200 | 0.0440 | -80.0% | 🚨 |
| avg_dct_energy | 497.7588 | 105.5923 | -78.8% | 🚨 |
| avg_edge_sharpness | 146.8736 | 9.8167 | -93.3% | 🚨 |

### Temporal Patterns
| Metric | Real | Spoofed | Difference | Flag |
|--------|------|---------|------------|------|
| avg_frame_diff | 6.0333 | 2.7706 | -54.1% | 🚨 |
| std_frame_diff | 1.7949 | 2.8503 | +58.8% | 🚨 |
| min_frame_diff | 0.0000 | 0.0000 | +0.0% | ✅ |
| max_frame_diff | 10.8365 | 19.5121 | +80.1% | 🚨 |
| identical_frames | 3.0000 | 34.0000 | +1033.3% | 🚨 |
| identical_ratio | 0.0303 | 0.3434 | +1033.3% | 🚨 |
| periodicity_strength | 2.7531 | 2.3797 | -13.6% | ⚠️ |
| diff_variance | 3.2218 | 8.1241 | +152.2% | 🚨 |

### Noise Patterns
| Metric | Real | Spoofed | Difference | Flag |
|--------|------|---------|------------|------|
| avg_noise_level | 4.7218 | 2.5104 | -46.8% | 🚨 |
| noise_consistency | 0.5895 | 0.0524 | -91.1% | 🚨 |
| avg_noise_uniformity | 0.8398 | 0.8673 | +3.3% | ✅ |
| avg_color_noise_corr | 0.9841 | 0.9159 | -6.9% | ✅ |

### Face Texture
| Metric | Real | Spoofed | Difference | Flag |
|--------|------|---------|------------|------|
| avg_texture_detail | 42.8426 | 22.6160 | -47.2% | 🚨 |
| texture_consistency | 9.0081 | 0.6155 | -93.2% | 🚨 |
| avg_smoothness | 0.1935 | 0.3205 | +65.6% | 🚨 |

### Color Distribution
| Metric | Real | Spoofed | Difference | Flag |
|--------|------|---------|------------|------|
| avg_brightness | 87.0462 | 97.2137 | +11.7% | ⚠️ |
| brightness_stability | 2.4929 | 1.0015 | -59.8% | 🚨 |
| avg_saturation | 14.6398 | 19.2922 | +31.8% | 🚨 |
| avg_color_variance | 2105.5725 | 5019.3770 | +138.4% | 🚨 |

### Motion Analysis
| Metric | Real | Spoofed | Difference | Flag |
|--------|------|---------|------------|------|
| avg_motion | 6.0333 | 2.7706 | -54.1% | 🚨 |
| motion_variance | 3.2218 | 8.1241 | +152.2% | 🚨 |
| motion_smoothness | 0.3199 | 0.1996 | -37.6% | 🚨 |
| max_motion | 10.8365 | 19.5121 | +80.1% | 🚨 |
| min_motion | 0.0000 | 0.0000 | +0.0% | ✅ |

---

## 🚨 Detected Issues (Likely Detection Vectors)
- **Temporal Patterns → identical_frames**: Spoofed is 1033.3% higher than real
- **Temporal Patterns → identical_ratio**: Spoofed is 1033.3% higher than real
- **Temporal Patterns → diff_variance**: Spoofed is 152.2% higher than real
- **Motion Analysis → motion_variance**: Spoofed is 152.2% higher than real
- **Color Distribution → avg_color_variance**: Spoofed is 138.4% higher than real
- **Compression Artifacts → avg_edge_sharpness**: Spoofed is 93.3% lower than real
- **Face Texture → texture_consistency**: Spoofed is 93.2% lower than real
- **Noise Patterns → noise_consistency**: Spoofed is 91.1% lower than real
- **Temporal Patterns → max_frame_diff**: Spoofed is 80.1% higher than real
- **Motion Analysis → max_motion**: Spoofed is 80.1% higher than real

---

## Recommendations
- **Improve motion naturalness**: Add realistic micro-movements

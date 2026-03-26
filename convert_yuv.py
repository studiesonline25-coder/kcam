import PIL.Image as Image
import numpy as np
import os

def convert_yuv_to_png(raw_path, width, height, output_path):
    print(f"Opening {raw_path} ({width}x{height})")
    with open(raw_path, 'rb') as f:
        data = f.read()
    
    y_size = width * height
    uv_size = y_size // 2
    
    # Pad or trunc to match exact size
    expected = y_size + uv_size
    if len(data) < expected:
        data = data + b'\x00' * (expected - len(data))
    else:
        data = data[:expected]

    # Split into Y and interleaved UV (NV21/NV12 style usually for Android)
    y_data = np.frombuffer(data[:y_size], dtype=np.uint8).reshape((height, width))
    uv_data = np.frombuffer(data[y_size:], dtype=np.uint8).reshape((height // 2, width))
    
    # Construct YUV Image
    # Up-sample UV (naive nearest-neighbor for debugging)
    u_plane = np.zeros((height, width), dtype=np.uint8)
    v_plane = np.zeros((height, width), dtype=np.uint8)
    
    # NV21: [V, U, V, U...] or NV12: [U, V, U, V...]
    # I'll try NV21 first (common on Xiaomi)
    v_plane_half = uv_data[:, 0::2]
    u_plane_half = uv_data[:, 1::2]
    
    for row in range(height):
        for col in range(width):
            u_plane[row, col] = u_plane_half[row//2, col//2]
            v_plane[row, col] = v_plane_half[row//2, col//2]
            
    # YUV to RGB Conversion (Standard BT.601)
    # yuv = np.stack([y_data, u_plane, v_plane], axis=-1)
    
    r = y_data + 1.402 * (v_plane.astype(float) - 128)
    g = y_data - 0.344136 * (u_plane.astype(float) - 128) - 0.714136 * (v_plane.astype(float) - 128)
    b = y_data + 1.772 * (u_plane.astype(float) - 128)
    
    rgb = np.stack([r, g, b], axis=-1).clip(0, 255).astype(np.uint8)
    
    img = Image.fromarray(rgb)
    img.save(output_path)
    print(f"Saved to {output_path}")

if __name__ == "__main__":
    raw_file = r"c:\Users\kevin\Downloads\secproject\capture_yuv_1774477310453.raw"
    if os.path.exists(raw_file):
        convert_yuv_to_png(raw_file, 1280, 960, r"c:\Users\kevin\Downloads\secproject\debug_preview.png")
    else:
        print("File not found")

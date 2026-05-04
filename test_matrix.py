import numpy as np

def translate(x, y):
    return np.array([[1, 0, 0, x],
                     [0, 1, 0, y],
                     [0, 0, 1, 0],
                     [0, 0, 0, 1]])

def scale(x, y):
    return np.array([[x, 0, 0, 0],
                     [0, y, 0, 0],
                     [0, 0, 1, 0],
                     [0, 0, 0, 1]])

def rotate(deg):
    rad = np.radians(deg)
    c, s = np.cos(rad), np.sin(rad)
    return np.array([[c, -s, 0, 0],
                     [s,  c, 0, 0],
                     [0,  0, 1, 0],
                     [0,  0, 0, 1]])

def test_pipeline(exif_rot, total_rot):
    print(f"--- EXIF: {exif_rot}, totalRotation: {total_rot} ---")
    
    # stMatrix applies EXIF rotation.
    # If EXIF is 90, stMatrix rotates by 90 (or -90 depending on implementation).
    # Android usually applies -90 or 90 to UVs. Let's assume it rotates the image by EXIF.
    # Assume native camera uses Landscape sensor, head at Left (-X)
    head_raw = np.array([-1, 0, 0, 1])
    
    st = rotate(exif_rot)
    head_st = st @ head_raw
    
    # Model transformation (simplified)
    model = np.eye(4)
    model = model @ scale(1.777, 1.0)
    model = model @ rotate(total_rot)
    
    head_model = model @ head_st
    print(f"Head in Model Space: ({head_model[0]:.2f}, {head_model[1]:.2f})")
    
    # Native camera typically rotates 90 degrees CCW
    cam_rot = rotate(90)
    head_screen = cam_rot @ head_model
    print(f"Head on Screen: ({head_screen[0]:.2f}, {head_screen[1]:.2f})")

test_pipeline(90, 90)
test_pipeline(90, 180)
test_pipeline(0, 180)
test_pipeline(0, 90)

import re
import os

def find_metadata_in_binary(file_path):
    print(f"Searching for metadata in {file_path}...")
    with open(file_path, 'rb') as f:
        data = f.read()
    
    # Search for JSON-like strings
    json_strings = re.findall(b'(\{[^{}]{10,}\})', data)
    for i, js in enumerate(json_strings):
        try:
            print(f"Found JSON string: {js.decode('utf-8')[:200]}...")
        except:
            pass
            
    # Search for common keys in ASCII
    keys = [b'yaw', b'pitch', b'roll', b'score', b'face', b'eye', b'landmark', b'fps', b'time']
    for key in keys:
        if key in data:
            idx = data.find(key)
            print(f"Found key '{key.decode()}' at offset {idx}. Surrounding: {data[max(0, idx-20):idx+50]}")

if __name__ == "__main__":
    payload_dir = 'liveness_payloads'
    if os.path.exists(payload_dir):
        for f in os.listdir(payload_dir):
            if f.endswith('.txt'):
                find_metadata_in_binary(os.path.join(payload_dir, f))

import json
import base64
import os

def extract_wasm(har_path):
    print(f"Loading {har_path}...")
    with open(har_path, 'r', encoding='utf-8') as f:
        data = json.load(f)
    
    entries = data['log']['entries']
    found = False
    for i, entry in enumerate(entries):
        url = entry['request']['url']
        if '.wasm' in url.lower():
            found = True
            filename = url.split('/')[-1].split('?')[0]
            if not filename.endswith('.wasm'):
                filename = f"module_{i}.wasm"
            
            print(f"Found WASM: {url}")
            content = entry['response'].get('content', {})
            text = content.get('text')
            
            if text:
                encoding = content.get('encoding')
                try:
                    if encoding == 'base64':
                        raw_data = base64.b64decode(text)
                    else:
                        raw_data = text.encode('utf-8')
                    
                    with open(filename, 'wb') as out:
                        out.write(raw_data)
                    print(f"Successfully saved to {filename}")
                except Exception as e:
                    print(f"Failed to decode/save {filename}: {e}")
            else:
                print(f"No content found for {url} (Check if it was loaded from cache)")
    
    if not found:
        print("No .wasm files found in HAR.")

if __name__ == "__main__":
    extract_wasm('raenest.har')

import json
import base64
import os

def extract_wasm_from_har(har_path, output_path):
    print(f"Extracting WASM from {har_path}...")
    with open(har_path, 'r', encoding='utf-8') as f:
        har_data = json.load(f)
    
    entries = har_data.get('log', {}).get('entries', [])
    for entry in entries:
        url = entry.get('request', {}).get('url', '')
        if 'trusted_media' in url and url.endswith('.wasm'):
            print(f"Found WASM: {url}")
            content = entry.get('response', {}).get('content', {})
            text = content.get('text', '')
            
            if text:
                if content.get('encoding') == 'base64':
                    wasm_bytes = base64.b64decode(text)
                else:
                    wasm_bytes = text.encode('utf-8')
                
                with open(output_path, 'wb') as out:
                    out.write(wasm_bytes)
                print(f"Saved to {output_path} ({len(wasm_bytes)} bytes)")
                return True
    
    print("WASM not found in HAR.")
    return False

if __name__ == "__main__":
    har_path = r"C:\Users\kevin\Downloads\kcam\in.sumsub.com_Archive [26-05-12 21-52-35].har"
    output_wasm = r"c:\Users\kevin\Downloads\kcam\scratch\trusted_media.wasm"
    extract_wasm_from_har(har_path, output_wasm)

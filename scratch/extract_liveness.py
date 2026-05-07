import json
import base64
import os

def extract_liveness_payloads(file_path):
    print(f"Extracting liveness payloads from {file_path}...")
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            har_data = json.load(f)
    except Exception as e:
        print(f"Error loading JSON: {e}")
        return

    entries = har_data.get('log', {}).get('entries', [])
    count = 0
    
    os.makedirs('liveness_payloads', exist_ok=True)
    
    for entry in entries:
        request = entry.get('request', {})
        url = request.get('url', '')
        
        if '/sdk/liveness/' in url and '/multipart' in url:
            count += 1
            print(f"Found liveness payload {count}: {url}")
            
            # Extract post data
            post_data = request.get('postData', {})
            params = post_data.get('params', [])
            text = post_data.get('text', '')
            
            filename = f"liveness_payloads/payload_{count}.txt"
            with open(filename, 'w', encoding='utf-8') as out:
                out.write(f"URL: {url}\n")
                out.write(f"Method: {request.get('method')}\n")
                out.write("-" * 20 + "\n")
                
                if params:
                    for p in params:
                        name = p.get('name', 'unknown')
                        value = p.get('value', '')
                        out.write(f"PARAM [{name}]: {value[:1000]}...\n")
                elif text:
                    out.write(f"TEXT PAYLOAD:\n{text[:5000]}\n")
            
            # Look for JSON metadata inside the text/params
            if text:
                # Try to find JSON-like blobs
                import re
                json_blobs = re.findall(r'(\{.*?\})', text, re.DOTALL)
                for i, blob in enumerate(json_blobs):
                    try:
                        data = json.loads(blob)
                        with open(f"liveness_payloads/payload_{count}_meta_{i}.json", 'w') as jout:
                            json.dump(data, jout, indent=2)
                    except:
                        pass

    print(f"Extracted {count} liveness payloads to liveness_payloads/ directory.")

if __name__ == "__main__":
    extract_liveness_payloads(r"c:\Users\kevin\Downloads\kcam\raenest.har")

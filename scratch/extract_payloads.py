import json
import base64
import os
import re

def extract_multipart_payloads(har_path, output_dir):
    print(f"Extracting multipart payloads from {har_path}...")
    if not os.path.exists(output_dir):
        os.makedirs(output_dir)

    with open(har_path, 'r', encoding='utf-8') as f:
        har_data = json.load(f)
    
    entries = har_data.get('log', {}).get('entries', [])
    count = 0
    
    for entry in entries:
        request = entry.get('request', {})
        url = request.get('url', '')
        
        if '/multipart' in url:
            count += 1
            print(f"Processing multipart request {count}: {url}")
            
            post_data = request.get('postData', {})
            text = post_data.get('text', '')
            
            if not text:
                continue
                
            # Extract boundary
            mime_type = post_data.get('mimeType', '')
            boundary_match = re.search(r'boundary=(.+)', mime_type)
            if not boundary_match:
                # Try to guess from text
                boundary_match = re.search(r'^--([a-zA-Z0-9]+)', text)
                
            if boundary_match:
                boundary = boundary_match.group(1).strip()
                parts = text.split(f"--{boundary}")
                
                for i, part in enumerate(parts):
                    if not part.strip() or part.strip() == "--":
                        continue
                        
                    # Split headers and body
                    header_body = part.split("\r\n\r\n", 1)
                    if len(header_body) < 2:
                        continue
                        
                    headers = header_body[0]
                    body = header_body[1].rstrip("\r\n--")
                    
                    # Find name/filename
                    name_match = re.search(r'name="([^"]+)"', headers)
                    filename_match = re.search(r'filename="([^"]+)"', headers)
                    
                    name = name_match.group(1) if name_match else f"part_{i}"
                    ext = "bin"
                    if filename_match:
                        filename = filename_match.group(1)
                        if '.' in filename:
                            ext = filename.split('.')[-1]
                    
                    # Check if body is binary or text
                    is_likely_json = False
                    if body.strip().startswith('{') or body.strip().startswith('['):
                        is_likely_json = True
                        ext = "json"
                    
                    filename = f"req_{count}_{name}.{ext}"
                    filepath = os.path.join(output_dir, filename)
                    
                    # If it's binary data (containing non-ascii), we might need to handle it carefully
                    # But HAR 'text' field is usually already decoded from binary if it was multipart
                    with open(filepath, 'wb') as out:
                        # HAR 'text' for binary data can be tricky. 
                        # Sometimes it's raw, sometimes it's escaped.
                        # We'll write it as utf-8 and hope for the best, or check for encoding.
                        out.write(body.encode('utf-8', errors='ignore'))
                    
                    print(f"  Extracted part: {filename} ({len(body)} bytes)")

    print(f"Extraction complete. Files saved in {output_dir}")

if __name__ == "__main__":
    har_path = r"C:\Users\kevin\Downloads\kcam\in.sumsub.com_Archive [26-05-12 21-52-35].har"
    output_dir = r"c:\Users\kevin\Downloads\kcam\scratch\extracted_payloads"
    extract_multipart_payloads(har_path, output_dir)

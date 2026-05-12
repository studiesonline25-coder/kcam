import re

def extract_strings(file_path, min_len=4):
    with open(file_path, 'rb') as f:
        content = f.read()
    
    # Simple regex to find ASCII strings
    pattern = rb'[ -~]{' + bytes(str(min_len), 'utf-8') + rb',}'
    strings = re.findall(pattern, content)
    
    return [s.decode('ascii', errors='ignore') for s in strings]

def analyze_wasm_strings(wasm_path):
    print(f"Analyzing strings in {wasm_path}...")
    all_strings = extract_strings(wasm_path)
    
    # Filter for interesting keywords
    keywords = [
        'camera', 'video', 'media', 'stream', 'track', 'facing', 'device', 
        'navigator', 'capabilities', 'settings', 'enumerate', 'constraints',
        'width', 'height', 'frameRate', 'canvas', 'gl', 'webgl', 'context',
        'synthetic', 'virtual', 'injector', 'obs', 'manycam', 'virtucam',
        'detection', 'liveness', 'face', 'mask', 'texture', 'pixel', 'noise',
        'gyro', 'accel', 'sensor', 'orientation'
    ]
    
    findings = []
    for s in all_strings:
        if any(kw.lower() in s.lower() for kw in keywords):
            findings.append(s)
            
    # Also look for function names that look like imports
    imports = [s for s in all_strings if '__wbg' in s or '__wbindgen' in s]
    
    with open("wasm_strings_report.txt", "w", encoding='utf-8') as out:
        out.write("--- INTERESTING STRINGS ---\n")
        for f in sorted(list(set(findings))):
            out.write(f + "\n")
        
        out.write("\n--- WASM/JS BINDINGS ---\n")
        for i in sorted(list(set(imports))):
            out.write(i + "\n")

    print(f"Found {len(findings)} interesting strings. Report saved to wasm_strings_report.txt")

if __name__ == "__main__":
    analyze_wasm_strings(r"c:\Users\kevin\Downloads\kcam\scratch\trusted_media.wasm")

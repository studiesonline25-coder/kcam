import json
import base64
import sys

# Ensure stdout handles UTF-8 correctly on Windows
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

def analyze_har_deep(file_path):
    print(f"Deep analyzing {file_path}...")
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            har_data = json.load(f)
    except Exception as e:
        print(f"Error loading JSON: {e}")
        return

    entries = har_data.get('log', {}).get('entries', [])
    findings = []
    
    findings.append("# Deep HAR Analysis Findings")
    
    keywords = ['gyro', 'accel', 'sensor', 'device', 'camera', 'fps', 'frame', 'track', 'facing', 'level', 'liveness', 'synthetic', 'noise']
    
    for entry in entries:
        request = entry.get('request', {})
        url = request.get('url', '')
        
        post_data = request.get('postData', {})
        text = post_data.get('text', '')
        
        match_found = False
        if text:
            for kw in keywords:
                if kw.lower() in text.lower():
                    match_found = True
                    break
        
        if match_found:
            finding = f"### [MATCH] {url}\n"
            finding += f"**Method**: {request.get('method')}\n\n"
            try:
                data = json.loads(text)
                finding += "```json\n" + json.dumps(data, indent=2)[:5000] + "\n```\n"
            except:
                finding += f"**Raw Snippet (Cleaned)**: {text.encode('ascii', 'ignore').decode('ascii')[:1000]}\n"
            findings.append(finding)

    # Status/Rejection Triggers
    findings.append("## Status & Rejection Triggers")
    for entry in reversed(entries):
        response = entry.get('response', {})
        content = response.get('content', {})
        text = content.get('text', '')
        
        if text and ('rejection' in text.lower() or 'reason' in text.lower() or 'fail' in text.lower() or '9103' in text):
            url = entry.get('request', {}).get('url', '')
            finding = f"### Trigger at {url}\n"
            finding += f"**Status**: {response.get('status')}\n\n"
            finding += f"**Body**: {text[:2000]}\n"
            findings.append(finding)

    with open("har_analysis_results.md", "w", encoding='utf-8') as out:
        out.write("\n\n".join(findings))
    
    print("Done. Results saved to har_analysis_results.md")

if __name__ == "__main__":
    analyze_har_deep(r"c:\Users\kevin\Downloads\kcam\raenest.har")

import json
import os
import sys

# Ensure stdout handles UTF-8 correctly
if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

def analyze_sumsub_har(file_path):
    print(f"Analyzing SumSub HAR: {file_path}")
    if not os.path.exists(file_path):
        print("File not found.")
        return

    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            har_data = json.load(f)
    except Exception as e:
        print(f"Error loading JSON: {e}")
        return

    entries = har_data.get('log', {}).get('entries', [])
    print(f"Found {len(entries)} entries.")

    findings = []
    findings.append("# SumSub HAR Analysis Report")
    
    # Interesting SumSub endpoints and keywords
    interesting_keywords = ['liveness', 'check', 'status', 'config', 'applicant', 'rejection', 'synthetic', 'face', 'video', 'biometric', 'error']
    
    for entry in entries:
        request = entry.get('request', {})
        response = entry.get('response', {})
        url = request.get('url', '')
        
        # Filter for SumSub or relevant data
        is_relevant = any(kw in url.lower() for kw in ['sumsub', 'liveness', 'id-check'])
        
        if is_relevant:
            method = request.get('method')
            status = response.get('status')
            
            finding = f"## [{method}] {url}\n"
            finding += f"**Status**: {status}\n\n"
            
            # Extract request body
            post_data = request.get('postData', {})
            req_text = post_data.get('text', '')
            if req_text:
                try:
                    req_json = json.loads(req_text)
                    finding += "### Request Body (Truncated)\n```json\n" + json.dumps(req_json, indent=2)[:2000] + "\n```\n"
                except:
                    finding += f"### Request Body (Raw Snippet)\n{req_text[:500]}\n"
            
            # Extract response body
            content = response.get('content', {})
            res_text = content.get('text', '')
            if res_text:
                try:
                    # Handle possible base64
                    if content.get('encoding') == 'base64':
                        import base64
                        res_text = base64.b64decode(res_text).decode('utf-8', errors='ignore')
                    
                    res_json = json.loads(res_text)
                    finding += "### Response Body\n```json\n" + json.dumps(res_json, indent=2)[:2000] + "\n```\n"
                    
                    # Look for rejection or synthetic signals
                    low_text = res_text.lower()
                    if 'synthetic' in low_text or 'rejection' in low_text or 'fraud' in low_text or 'fail' in low_text:
                        finding += "### [!!! ALERT !!!] POTENTIAL DETECTION SIGNAL FOUND\n"
                        # Print some context around the alert
                        idx = low_text.find('synthetic')
                        if idx == -1: idx = low_text.find('rejection')
                        start = max(0, idx - 50)
                        end = min(len(res_text), idx + 200)
                        finding += f"> ...{res_text[start:end]}...\n"
                        
                except:
                    finding += "### Response Body (Raw Snippet)\n" + res_text[:500] + "\n"
            
            findings.append(finding)

    output_file = "sumsub_analysis_results.md"
    with open(output_file, "w", encoding='utf-8') as out:
        out.write("\n\n".join(findings))
    
    print(f"Analysis complete. Results saved to {output_file}")

if __name__ == "__main__":
    har_path = r"C:\Users\kevin\Downloads\kcam\in.sumsub.com_Archive [26-05-12 21-52-35].har"
    analyze_sumsub_har(har_path)

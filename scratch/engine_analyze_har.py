import json
import base64
import sys

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

def analyze_har_deep(file_path):
    print(f"Deep analyzing {file_path} for specific engines...")
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            har_data = json.load(f)
    except Exception as e:
        print(f"Error loading JSON: {e}")
        return

    entries = har_data.get('log', {}).get('entries', [])
    findings = []
    
    findings.append("# Deep Engine Analysis Findings")
    
    # Domains we found in CSP
    engine_domains = ['sumsub.com', 'veriff.com', 'veriff.me', 'smileidentity.com']
    
    for entry in entries:
        request = entry.get('request', {})
        url = request.get('url', '')
        
        is_engine_call = any(domain in url for domain in engine_domains)
        
        if is_engine_call:
            method = request.get('method')
            status = entry.get('response', {}).get('status')
            
            finding = f"### [{method}] {url}\n"
            finding += f"**Status**: {status}\n\n"
            
            # Request Headers
            headers = {h['name']: h['value'] for h in request.get('headers', [])}
            finding += "**Request Headers Snippet**:\n"
            interesting_headers = ['User-Agent', 'Content-Type', 'X-Applicant-Id', 'Authorization']
            for h in interesting_headers:
                if h in headers:
                    finding += f"- {h}: {headers[h]}\n"
            
            # Post Data
            post_data = request.get('postData', {})
            text = post_data.get('text', '')
            if text:
                finding += "\n**Payload**:\n"
                try:
                    data = json.loads(text)
                    # Look for sensor data in the JSON
                    finding += "```json\n" + json.dumps(data, indent=2)[:5000] + "\n```\n"
                except:
                    finding += f"Raw (Cleaned): {text.encode('ascii', 'ignore').decode('ascii')[:1000]}\n"
            
            # Response Data
            response_content = entry.get('response', {}).get('content', {})
            resp_text = response_content.get('text', '')
            if resp_text:
                finding += "\n**Response Snippet**:\n"
                finding += f"{resp_text[:1000]}\n"
            
            findings.append(finding)

    with open("engine_analysis_results.md", "w", encoding='utf-8') as out:
        out.write("\n\n".join(findings))
    
    print("Done. Results saved to engine_analysis_results.md")

if __name__ == "__main__":
    analyze_har_deep(r"c:\Users\kevin\Downloads\kcam\raenest.har")

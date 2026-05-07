import json
import base64
import sys

if sys.stdout.encoding != 'utf-8':
    sys.stdout.reconfigure(encoding='utf-8')

def analyze_har_deep(file_path):
    print(f"Deep analyzing {file_path} for all Sumsub/Veriff calls...")
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            har_data = json.load(f)
    except Exception as e:
        print(f"Error loading JSON: {e}")
        return

    entries = har_data.get('log', {}).get('entries', [])
    findings = []
    
    engine_domains = ['sumsub.com', 'veriff.com', 'veriff.me', 'smileidentity.com']
    
    for entry in entries:
        request = entry.get('request', {})
        url = request.get('url', '')
        
        if any(domain in url for domain in engine_domains):
            method = request.get('method')
            response = entry.get('response', {})
            status = response.get('status')
            
            finding = f"## [{method}] {url}\n"
            finding += f"**Status**: {status}\n"
            
            # Post Data
            post_data = request.get('postData', {})
            text = post_data.get('text', '')
            if text:
                finding += "\n### Request Payload\n"
                try:
                    data = json.loads(text)
                    finding += "```json\n" + json.dumps(data, indent=2)[:10000] + "\n```\n"
                except:
                    finding += f"Raw (Cleaned): {text.encode('ascii', 'ignore').decode('ascii')[:2000]}\n"
            
            # Response Data
            content = response.get('content', {})
            resp_text = content.get('text', '')
            if resp_text:
                finding += "\n### Response\n"
                try:
                    data = json.loads(resp_text)
                    finding += "```json\n" + json.dumps(data, indent=2)[:5000] + "\n```\n"
                except:
                    finding += f"Raw: {resp_text[:1000]}\n"
            
            findings.append(finding)

    with open("full_engine_analysis.md", "w", encoding='utf-8') as out:
        out.write("\n\n".join(findings))
    
    print(f"Done. Analyzed {len(findings)} engine calls.")

if __name__ == "__main__":
    analyze_har_deep(r"c:\Users\kevin\Downloads\kcam\raenest.har")

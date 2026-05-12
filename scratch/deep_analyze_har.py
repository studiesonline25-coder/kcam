import json
import os

def deep_analyze_har(har_path):
    print(f"Deep Analysis of {har_path}...")
    
    with open(har_path, 'r', encoding='utf-8') as f:
        har_data = json.load(f)
    
    entries = har_data.get('log', {}).get('entries', [])
    
    keywords = ["face", "synthetic", "liveness", "quality", "reject", "fail", "reason", "error", "warning", "suspicious", "bot", "virtual", "camera"]
    
    results = []
    
    for entry in entries:
        request = entry.get('request', {})
        response = entry.get('response', {})
        url = request.get('url', '')
        
        # 1. Search Request Headers/PostData
        post_data = request.get('postData', {})
        req_text = post_data.get('text', '')
        
        # 2. Search Response Headers/Content
        res_content = response.get('content', {})
        res_text = res_content.get('text', '')
        
        found_in_req = any(k in req_text.lower() for k in keywords)
        found_in_res = any(k in res_text.lower() for k in keywords)
        
        if found_in_req or found_in_res:
            res_json = None
            if res_text and (res_text.strip().startswith('{') or res_text.strip().startswith('[')):
                try:
                    res_json = json.loads(res_text)
                except:
                    pass
            
            results.append({
                "url": url,
                "method": request.get('method'),
                "status": response.get('status'),
                "found_in": "request" if found_in_req else "response",
                "response_preview": res_text[:200] if res_text else None,
                "response_json": res_json
            })

    # Output results
    with open(r"c:\Users\kevin\Downloads\kcam\scratch\har_deep_scan.json", 'w') as out:
        json.dump(results, out, indent=2)
    
    print(f"Deep scan complete. Found {len(results)} suspicious entries.")
    print("Top suspicious URLs:")
    for r in results[:10]:
        print(f" - [{r['status']}] {r['url'][:100]}")

if __name__ == "__main__":
    har_path = r"C:\Users\kevin\Downloads\kcam\in.sumsub.com_Archive [26-05-12 21-52-35].har"
    deep_analyze_har(har_path)

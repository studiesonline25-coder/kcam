import json

def analyze_har(file_path):
    with open(file_path, 'r', encoding='utf-8') as f:
        har_data = json.load(f)
    
    entries = har_data.get('log', {}).get('entries', [])
    print(f"Found {len(entries)} entries.")
    
    # Reverse search for the final status
    print("\n--- FINAL SESSION VERDICT SEARCH ---")
    for entry in reversed(entries):
        request = entry.get('request', {})
        response = entry.get('response', {})
        url = request.get('url', '')
        
        # Look for status/review/applicant calls at the end
        if 'status' in url or 'review' in url or 'applicant' in url or 'profile' in url:
            method = request.get('method', '')
            status = response.get('status', 0)
            print(f"\nURL: {url}")
            print(f"Method: {method}, Status: {status}")
            
            content = response.get('content', {})
            text = content.get('text', '')
            if text:
                print(f"Response Body (Short): {text[:500]}")
                if 'synthetic' in text.lower() or 'reject' in text.lower() or 'fail' in text.lower():
                    print("!!! ALERT: FOUND KEYWORD IN RESPONSE !!!")

if __name__ == "__main__":
    analyze_har("C:\Users\kevin\Downloads\kcam\raenest.har")

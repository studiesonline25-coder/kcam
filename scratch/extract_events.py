import json

def extract_track_events(har_path):
    with open(har_path, 'r', encoding='utf-8') as f:
        har_data = json.load(f)
    
    entries = har_data.get('log', {}).get('entries', [])
    
    for entry in entries:
        url = entry.get('request', {}).get('url', '')
        if "trackEvents" in url:
            print(f"\n--- Events sent to {url} ---")
            post_data = entry.get('request', {}).get('postData', {})
            text = post_data.get('text', '')
            if text:
                try:
                    payload = json.loads(text)
                    print(json.dumps(payload, indent=2))
                except:
                    print(text)

if __name__ == "__main__":
    har_path = r"C:\Users\kevin\Downloads\kcam\in.sumsub.com_Archive [26-05-12 21-52-35].har"
    extract_track_events(har_path)

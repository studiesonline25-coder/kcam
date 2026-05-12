import json

def analyze_event_sequence(har_path):
    with open(har_path, 'r', encoding='utf-8') as f:
        har_data = json.load(f)
    
    entries = har_data.get('log', {}).get('entries', [])
    
    all_events = []
    for entry in entries:
        url = entry.get('request', {}).get('url', '')
        if "trackEvents" in url:
            post_data = entry.get('request', {}).get('postData', {})
            text = post_data.get('text', '')
            if text:
                try:
                    batch = json.loads(text)
                    if isinstance(batch, list):
                        all_events.extend(batch)
                except:
                    pass

    print(f"Total events captured: {len(all_events)}")
    
    # Print the sequence of events
    for i, e in enumerate(all_events):
        activity = e.get('activity', 'unknown')
        payload = e.get('payload', {})
        event_data = payload.get('event', {})
        
        # Log interesting activities
        if "liveness" in activity.lower() or "error" in activity.lower() or "answer" in str(event_data).lower():
            print(f"[{i}] Activity: {activity}")
            if event_data:
                print(f"    Event: {json.dumps(event_data, indent=2)}")
            if payload.get('objectName'):
                print(f"    Object: {payload.get('objectName')}")

if __name__ == "__main__":
    har_path = r"C:\Users\kevin\Downloads\kcam\in.sumsub.com_Archive [26-05-12 21-52-35].har"
    analyze_event_sequence(har_path)

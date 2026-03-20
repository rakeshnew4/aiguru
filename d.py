import requests
import json

url = "http://108.181.187.227:8003/chat-stream"

payload = {
    "text": "Explain photosynthesis step by step in one line"
}

with requests.post(url, json=payload, stream=True) as response:
    print("Status:", response.status_code)
    print("\nStreaming response:\n")

    # ✅ iter_lines() reads line-by-line as data arrives, not all at once
    for line in response.iter_lines():
        if line:
            decoded = line.decode("utf-8")

            # SSE lines look like: "data: {...}"
            if decoded.startswith("data:"):
                json_str = decoded[len("data:"):].strip()
                data = json.loads(json_str)

                if data.get("done"):
                    print("\n[Stream complete]")
                    break

                print(data.get("text", ""), end="", flush=True)
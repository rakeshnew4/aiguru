# LiteLLM Fix Guide — Get it Working

## Current Problem

- LiteLLM proxy is running but **no models are configured** in it
- FastAPI is sending requests for `model="cheaper"` but LiteLLM doesn't have that model
- Result: **500 Internal Server Error** from LiteLLM

## Root Cause

The `litellm_config.yaml` I created had models pointing to `api_base: http://localhost:8005` (the proxy itself), which creates a recursive loop. I've now fixed the config to be empty and rely on models from the PostgreSQL database.

## Quick Fix (5 minutes)

### Step 1: Restart LiteLLM with Fixed Config

```bash
cd /home/administrator/mywork/Work_Space/aiguru/server/litellm
docker-compose down
docker-compose up -d
```

Wait for it to start (watch the logs):
```bash
docker-compose logs -f
```

You should see:
```
LiteLLM: Proxy initialized with Config, Set models:
    (empty or whatever models you already added)
```

### Step 2: Add a Model (CRITICAL)

Go to **http://localhost:8006** (LiteLLM admin UI)

1. **Login** with master key (from `.env`):
   - Default: `sk-1234567890abcdefghijklmnopqrstuvwxyz`

2. **Add a Model** (click "+ Add Model"):
   - **Model Name:** `gemini-2.0-flash` 
   - **Provider:** Google Gemini
   - **Model ID:** `gemini-2.0-flash`
   - **API Key:** Your actual Gemini API key from [Google Cloud Console](https://ai.google.dev/tutorials/setup)

3. **Create Model Groups** to map tier names:
   - Click "+" under **Model Aliases/Groups**
   - Group name: `cheaper` → Select `gemini-2.0-flash`
   - Group name: `power` → Select `gemini-2.0-flash` (or a better model if you have one)
   - Group name: `faster` → Select `gemini-2.0-flash`

### Step 3: Verify Models in Database

```bash
# Check what LiteLLM sees
curl -s -H "Authorization: Bearer sk-1234567890abcdefghijklmnopqrstuvwxyz" \
  http://localhost:8005/models | python3 -m json.tool
```

Should show:
```json
{
  "data": [
    {
      "model_name": "cheaper",
      "id": "..."
    },
    {
      "model_name": "faster", 
      "id": "..."
    },
    {
      "model_name": "power",
      "id": "..."
    }
  ]
}
```

### Step 4: Test LiteLLM Directly

```python
import requests
import json

# Test a direct call to LiteLLM
response = requests.post(
    'http://localhost:8005/v1/chat/completions',
    headers={
        'Authorization': 'Bearer sk-1234567890abcdefghijklmnopqrstuvwxyz',
        'Content-Type': 'application/json'
    },
    json={
        'model': 'cheaper',  # Must exist!
        'messages': [{'role': 'user', 'content': 'Say hello'}],
        'temperature': 0.7,
        'max_tokens': 100
    },
    timeout=30
)

print(f"Status: {response.status_code}")
if response.status_code == 200:
    result = response.json()
    print(f"✅ SUCCESS: {result['choices'][0]['message']['content']}")
else:
    print(f"❌ ERROR: {response.text[:500]}")
```

### Step 5: Restart FastAPI Server

```bash
cd /home/administrator/mywork/Work_Space/aiguru/server
# Kill the existing server process (Ctrl+C if running in terminal)
python3 app/main.py
```

### Step 6: Test Chat Endpoint

```bash
curl -X POST http://localhost:8000/chat/chat-stream \
  -H "Content-Type: application/json" \
  -d '{
    "question": "What is 2+2?",
    "page_id": "test_page",
    "mode": "normal",
    "user_id": "test_user"
  }'
```

Should stream responses like:
```
data: {"text":"2 + 2 = 4",...}\n\n
data: {"done":true,...}\n\n
```

---

## How LiteLLM Works (Correctly)

```
┌─────────────────────┐
│   FastAPI Server    │
│  /v1/chat/complete  │
│   model="cheaper"   │
└──────────┬──────────┘
           │ HTTP Request
           ▼
┌─────────────────────────────────────┐
│   LiteLLM Proxy (localhost:8005)    │
│  • Looks up "cheaper" in database   │
│  • Finds a real provider endpoint   │
│  • Makes actual API call (NOT SELF) │
└──────────┬────────────────────────────┘
           │ API call to REAL provider
           ▼
    [ Gemini / OpenAI / Claude ]
        (with real API key)
```

**KEY:** LiteLLM should NEVER call itself. It must have real provider configs.

---

## Troubleshooting

### Error: "Connection error.. Received Model Group=faster"

**Cause:** The model group `faster` exists but has no valid endpoint configured.

**Fix:**
- Go to admin UI (http://localhost:8006)
- Check that the model assigned to `faster` has:
  - A valid provider (Gemini, OpenAI, etc.)
  - A valid API key
  - The correct model name for that provider

### Error: "Model Group not found"

**Cause:** FastAPI is asking for a model group (cheaper/power/faster) that doesn't exist in LiteLLM.

**Fix:**
- Create the model group in admin UI
- Or change the request to use a model name that exists

### I don't have any API keys

**Options:**
1. **Free:** Get a Gemini API key at https://ai.google.dev/ (free tier available)
2. **OpenAI:** Get an API key at https://platform.openai.com/api/keys (paid)
3. **Claude:** Get an API key at https://console.anthropic.com (paid)
4. **Groq:** Get an API key at https://console.groq.com/keys (free tier!)

I recommend **Groq** for free testing — they have free API access.

---

## Setup Checklist

- [ ] LiteLLM container restarted with fixed config
- [ ] At least one model added in admin UI (with valid API key)
- [ ] Model groups created: `cheaper`, `power`, `faster`
- [ ] Models verified available via `/models` endpoint
- [ ] Test call to LiteLLM `/v1/chat/completions` works
- [ ] FastAPI server restarted
- [ ] Chat endpoint tested and working

Once all checkboxes are done, your chat API should work! 🎉

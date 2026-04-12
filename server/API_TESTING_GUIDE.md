# API Testing Guide

Quick reference for testing the server APIs locally.

## Quick Start

### 1. Start the Server
```bash
cd /home/administrator/mywork/Work_Space/aiguru/server
source myenv/bin/activate
uvicorn app.main:app --port 8000 --host 0.0.0.0
```

### 2. Run Tests (Choose One)

#### Option A: Test with Mock Auth (Basic - No Firebase Setup Required)
```bash
python test_api_client.py
```
✅ **Best for:** Quick testing, no Firebase credentials needed
- Tests 4 APIs (2 public, 2 protected)
- Uses mock tokens for protected endpoints
- Shows what auth failures look like

#### Option B: Test with Real Firebase Auth (Production-Like)
```bash
python test_api_with_real_auth.py
```
✅ **Best for:** Production testing, verify real Firebase integration
- Creates real Firebase ID tokens
- Tests with actual authentication
- Requires: Firebase Admin SDK, Service Account file

#### Option C: Manual Testing with curl
```bash
bash test_api_with_curl.sh
```
✅ **Best for:** Quick manual tests, debugging specific endpoints

---

## Test Coverage

### Test File: `test_api_client.py`
**What it tests:** 4 APIs with mock authentication

| Test | Endpoint | Method | Auth | Purpose |
|------|----------|--------|------|---------|
| 1 | `/library/subjects` | GET | None | Fetch all subjects (public) |
| 2 | `/library/chapters` | GET | None | Fetch chapters for a subject (public) |
| 3 | `/users/register` | POST | Firebase | Register a new user |
| 4 | `/chat/chat-stream` | POST | Firebase | Send chat message |

**Output:**
```
✅ Successfully retrieved 5 subjects
✅ User registered successfully
❌ SECURITY TEST: Authorization failed (expected with mock token)
```

---

### Test File: `test_api_with_real_auth.py`
**What it tests:** Same 4 APIs but with real Firebase authentication

**Features:**
- Initializes Firebase Admin SDK
- Creates custom Firebase ID tokens
- Tests protected endpoints with real auth
- Shows streaming response handling

**Required Setup:**
```bash
# 1. Install Firebase Admin SDK
pip install firebase-admin

# 2. Download service account from Firebase Console
# Or set environment variable:
export FIREBASE_SERVICE_ACCOUNT="/path/to/serviceAccountKey.json"
```

---

### Test File: `test_api_with_curl.sh`
**What it tests:** 6 APIs with various auth methods

**Includes:**
- Health check
- Public endpoints (subjects, chapters)
- Protected endpoints (register, chat)
- Admin endpoint (HTTP Basic Auth)

**Usage:**
```bash
bash test_api_with_curl.sh

# Or run individual commands:
curl -X GET http://localhost:8000/health

# With auth header:
curl -X GET http://localhost:8000/users/register \
  -H "Authorization: Bearer TOKEN_HERE"
```

---

## Testing Protected Endpoints

### How to Get a Firebase Token

#### Method 1: Using Android App
1. Open the app and sign in
2. Open DevTools Network tab
3. Look at any API request
4. Copy the `Authorization: Bearer <token>` value

#### Method 2: Using Firebase Web SDK
```javascript
// In browser console
firebase.auth().currentUser.getIdToken().then(token => {
    console.log(token);
});
```

#### Method 3: Using Firebase Admin SDK
```python
import firebase_admin
from firebase_admin import credentials, auth as fb_auth

creds = credentials.Certificate("serviceAccountKey.json")
firebase_admin.initialize_app(creds)

token = fb_auth.create_custom_token("user-123")
print(token)
```

#### Method 4: Using `test_api_with_real_auth.py`
```python
# Automatically creates tokens if Admin SDK is set up
python test_api_with_real_auth.py
```

---

## Common Test Scenarios

### Scenario 1: Verify Auth is Working
```bash
python test_api_client.py | grep "SECURITY TEST"
```
Expected output: Either `401 Unauthorized` (good) or successful response with real token

### Scenario 2: Test Chat Streaming
```python
# Add to test_api_client.py or use real auth script
url = "http://localhost:8000/chat/chat-stream"
response = requests.post(url, json={
    "user_id": "test-user",
    "prompt": "Hello AI",
    "mode": "chat"
}, headers={"Authorization": f"Bearer {TOKEN}"}, stream=True)

# Read streaming response (Server-Sent Events format)
for line in response.iter_lines():
    if line.startswith(b'data: '):
        print(json.loads(line[6:]))
```

### Scenario 3: Test with Different User IDs
```bash
# Edit test_api_client.py or test_api_with_real_auth.py
TEST_USER_ID = "your-specific-user-id"
python test_api_with_real_auth.py
```

### Scenario 4: Test Admin Endpoints
```bash
curl -X GET http://localhost:8000/admin/api/stats \
  -u admin:admin123

# Change password in .env:
# ADMIN_USERNAME=admin
# ADMIN_PASSWORD=your_secure_password
```

---

## Understanding Test Output

### ✅ Success Cases
```
✅ Successfully retrieved 5 subjects
✅ User registered successfully
✅ Chat message sent successfully
```

### ❌ Error Cases

**401 Unauthorized**
```
❌ Authorization failed (expected with mock token on prod)
This is correct behavior - mock tokens should not work in production
```
→ **Action:** Use real Firebase token from Android app

**Connection Refused**
```
❌ Server is not responding: [Errno 111] Connection refused
```
→ **Action:** Start server with `uvicorn app.main:app --port 8000`

**404 Not Found**
```
❌ Got status 404: /undefined_endpoint
```
→ **Action:** Check endpoint URL is correct

**500 Internal Server Error**
```
❌ Got status 500: Failed to generate response
```
→ **Action:** Check server logs for detailed error

---

## Production Checklist

Before deploying to production:

- [ ] All tests pass with real Firebase tokens
- [ ] Auth is properly enforced on protected endpoints
- [ ] Rate limiting is configured (if needed)
- [ ] CORS origins are whitelisted
- [ ] Admin credentials are changed from defaults
- [ ] All API keys (Gemini, Groq, etc.) are secure
- [ ] Webhook signatures are verified
- [ ] Database backups are configured
- [ ] Error logging is enabled
- [ ] Performance metrics are monitored

---

## Troubleshooting

### Issue: "Module not found" error
```bash
pip install requests firebase-admin
```

### Issue: Connection refused
```bash
# Check if server is running
netstat -an | grep 8000

# Or use lsof
lsof -i :8000

# Kill existing process if needed
fuser -k 8000/tcp

# Restart server
uvicorn app.main:app --port 8000
```

### Issue: Firebase token verification fails
```bash
# Check Firebase config
cat server/firebase_serviceaccount.json | head -5

# Verify environment variables
echo $FIREBASE_PROJECT_ID
echo $FIREBASE_SERVICE_ACCOUNT
```

### Issue: Timeout on chat endpoint
- Add `--log-level debug` to uvicorn for more info
- Check if LiteLLM service is running
- Verify API keys (Gemini, Groq, etc.)

---

## Next Steps

1. **For Development:**
   - Use `test_api_client.py` for quick iteration
   - Test specific endpoints as you build

2. **For CI/CD:**
   - Add tests to GitHub Actions
   - Run `test_api_with_real_auth.py` in test pipeline
   - Generate test report: `python test_api_client.py > report.txt`

3. **For Android App:**
   - Get Firebase token from app
   - Pass in `Authorization: Bearer <token>` header
   - Handle 401 responses (token expired)

4. **For Monitoring:**
   - Set up API monitoring with status page
   - Track response times
   - Alert on auth failures

---

## API Endpoints Reference

```
Public Endpoints (no auth):
  GET    /health
  GET    /library/subjects
  GET    /library/chapters

Protected Endpoints (Firebase token required):
  POST   /users/register
  POST   /chat/chat-stream
  POST   /payments/create-order
  POST   /payments/verify
  POST   /quiz/generate
  POST   /quiz/evaluate-answer
  POST   /quiz/submit
  GET    /quiz/stats
  POST   /analyze-image/analyze-image
  GET    /library/selected-chapters
  GET    /library/progress
  POST   /library/select-chapters

Webhooks (HMAC signature required):
  POST   /payments/webhook (Razorpay)

Admin Endpoints (HTTP Basic Auth):
  GET    /admin/api/stats
  GET    /admin/api/users
  GET    /admin/api/users/{uid}
  PUT    /admin/api/users/{uid}
  DELETE /admin/api/users/{uid}
  ... (more admin endpoints)
```

---

**Questions?** Check the code comments in test files for more details.

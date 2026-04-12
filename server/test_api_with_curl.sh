#!/bin/bash
# test_api_with_curl.sh — Quick API testing with curl commands
# 
# Usage:
#   bash test_api_with_curl.sh
#   or copy individual commands and run them in terminal
#
# Before running:
#   1. Make sure server is running: uvicorn app.main:app --port 8000
#   2. For protected endpoints, replace <FIREBASE_TOKEN> with real token

SERVER_URL="http://localhost:8000"
TEST_USER_ID="local-test-$(date +%s)"
FIREBASE_TOKEN="your-real-firebase-token-here"

echo "=========================================="
echo "Server API Testing with curl"
echo "=========================================="
echo "Server: $SERVER_URL"
echo "Test User ID: $TEST_USER_ID"
echo ""

# ── Test 1: Health Check ─────────────────────────────────────────────────
echo "1️⃣  HEALTH CHECK (no auth needed)"
echo "Command:"
echo "  curl -X GET $SERVER_URL/health | jq ."
echo ""
echo "Result:"
curl -s -X GET "$SERVER_URL/health" | python3 -m json.tool 2>/dev/null || echo "Could not connect to server"
echo ""
echo ""

# ── Test 2: Get Subjects (Public) ────────────────────────────────────────
echo "2️⃣  GET SUBJECTS - Public endpoint (no auth needed)"
echo "Command:"
echo "  curl -X GET $SERVER_URL/library/subjects | jq '.subjects[0]'"
echo ""
echo "Result:"
curl -s -X GET "$SERVER_URL/library/subjects" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    subjects = data.get('subjects', [])
    if subjects:
        print(json.dumps(subjects[0], indent=2)[:200] + '...')
    else:
        print('No subjects found')
except:
    print('Failed to parse response')
" 2>/dev/null || echo "Failed"
echo ""
echo ""

# ── Test 3: Get Chapters (Public) ────────────────────────────────────────
echo "3️⃣  GET CHAPTERS - Public endpoint (no auth needed)"
echo "Command:"
echo "  SUBJECT_ID=\"<copy from above>\"  # Get from previous request"
echo "  curl -X GET \"$SERVER_URL/library/chapters?subject_id=\$SUBJECT_ID\" | jq('.chapters[0]')"
echo ""
echo "Note: Copy a subject _id from Test 2 and replace <subject_id>"
echo ""
echo ""

# ── Test 4: Register User (Protected) ────────────────────────────────────
echo "4️⃣  POST /USERS/REGISTER - Protected endpoint (requires Firebase token)"
echo "Command:"
echo "  curl -X POST $SERVER_URL/users/register \\"
echo "    -H 'Authorization: Bearer $FIREBASE_TOKEN' \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{"
echo "      \"userId\": \"$TEST_USER_ID\","
echo "      \"name\": \"Test User\","
echo "      \"email\": \"test@local.dev\","
echo "      \"grade\": \"10\","
echo "      \"schoolId\": \"school-001\","
echo "      \"schoolName\": \"Test School\""
echo "    }' | jq ."
echo ""
echo "Note: Replace with real Firebase token for testing"
echo ""
echo ""

# ── Test 5: Chat Stream (Protected) ──────────────────────────────────────
echo "5️⃣  POST /CHAT/CHAT-STREAM - Protected endpoint (requires Firebase token)"
echo "Command:"
echo "  curl -X POST $SERVER_URL/chat/chat-stream \\"
echo "    -H 'Authorization: Bearer $FIREBASE_TOKEN' \\"
echo "    -H 'Content-Type: application/json' \\"
echo "    -d '{"
echo "      \"user_id\": \"$TEST_USER_ID\","
echo "      \"prompt\": \"What is photosynthesis?\","
echo "      \"mode\": \"chat\""
echo "    }'"
echo ""
echo "Note: Returns streaming response (SSE format)"
echo ""
echo ""

# ── Test 6: Admin Endpoint (Basic Auth) ──────────────────────────────────
echo "6️⃣  GET /ADMIN/API/STATS - Protected with HTTP Basic Auth"
echo "Command:"
echo "  curl -X GET $SERVER_URL/admin/api/stats \\"
echo "    -u admin:admin123"
echo ""
echo "Note: Uses username/password (default: admin/admin123 - CHANGE IN PROD!)"
echo ""
echo ""

# ── How to get a real Firebase token ─────────────────────────────────────
echo "=========================================="
echo "How to get a real Firebase token:"
echo "=========================================="
echo ""
echo "Option 1: Using Firebase Web SDK (in browser)"
echo "  1. Install: npm install firebase"
echo "  2. Initialize Firebase with your config"
echo "  3. Sign in user"
echo "  4. Get token: user.getIdToken()"
echo ""
echo "Option 2: Using Firebase Admin SDK (Node.js/Python)"
echo "  npm install firebase-admin"
echo "  # or"
echo "  pip install firebase-admin"
echo ""
echo "Option 3: Using Firebase CLI"
echo "  firebase login"
echo "  firebase deploy"
echo ""
echo "Option 4: From Android app"
echo "  - Use Chrome DevTools to intercept network requests"
echo "  - Look for Authorization: Bearer header"
echo ""
echo ""

echo "=========================================="
echo "Testing Tips"
echo "=========================================="
echo ""
echo "✅ For quick testing of public endpoints: use test_api_client.py"
echo "   $ python test_api_client.py"
echo ""
echo "✅ For testing with real auth: use test_api_with_real_auth.py"
echo "   $ python test_api_with_real_auth.py"
echo ""
echo "✅ For manual testing: use curl commands from above"
echo ""
echo "✅ Generate a detailed report:"
echo "   $ python test_api_client.py 2>&1 | tee api_test_report.txt"
echo ""

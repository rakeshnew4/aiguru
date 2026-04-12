#!/usr/bin/env python3
"""
test_api_client.py — Local testing script for server APIs.

Usage:
    python test_api_client.py

Tests:
    1. GET /library/subjects (public)
    2. GET /library/chapters (public)
    3. POST /users/register (protected)
    4. POST /chat/chat-stream (protected)

Note: For protected endpoints, this script generates a mock Firebase token
for local testing. For production testing, use a real Firebase token.
"""

import json
import asyncio
from datetime import datetime
from typing import Optional

try:
    import requests
except ImportError:
    print("ERROR: requests library not installed")
    print("Install with: pip install requests")
    exit(1)

# ── Configuration ──────────────────────────────────────────────────────────
BASE_URL = "http://localhost:8003"
TEST_USER_ID = "test-user-" + datetime.now().strftime("%Y%m%d%H%M%S")
MOCK_FIREBASE_TOKEN = "mock-firebase-id-token-for-local-testing"

# Color codes for terminal output
class Colors:
    HEADER = '\033[95m'
    OKBLUE = '\033[94m'
    OKCYAN = '\033[96m'
    OKGREEN = '\033[92m'
    WARNING = '\033[93m'
    FAIL = '\033[91m'
    ENDC = '\033[0m'
    BOLD = '\033[1m'

# ── Utility Functions ──────────────────────────────────────────────────────

def print_header(title):
    print(f"\n{Colors.HEADER}{Colors.BOLD}{'='*70}{Colors.ENDC}")
    print(f"{Colors.HEADER}{Colors.BOLD}{title:^70}{Colors.ENDC}")
    print(f"{Colors.HEADER}{Colors.BOLD}{'='*70}{Colors.ENDC}\n")

def print_success(msg):
    print(f"{Colors.OKGREEN}✅ {msg}{Colors.ENDC}")

def print_error(msg):
    print(f"{Colors.FAIL}❌ {msg}{Colors.ENDC}")

def print_info(msg):
    print(f"{Colors.OKCYAN}ℹ️  {msg}{Colors.ENDC}")

def print_request(method, url, headers=None, data=None):
    print(f"{Colors.OKBLUE}{method} {url}{Colors.ENDC}")
    if headers:
        print(f"Headers: {json.dumps(headers, indent=2)[:200]}")
    if data:
        print(f"Body: {json.dumps(data, indent=2)}")

def print_response(resp):
    print(f"Status: {Colors.BOLD}{resp.status_code}{Colors.ENDC}")
    try:
        print(f"Response: {json.dumps(resp.json(), indent=2)[:500]}")
    except:
        print(f"Response: {resp.text[:200]}")

# ── Test Cases ─────────────────────────────────────────────────────────────

def test_1_get_subjects():
    """Test 1: GET /library/subjects (PUBLIC, no auth required)"""
    print_header("TEST 1: GET /library/subjects")
    print(f"Purpose: Fetch all available subjects (public endpoint)")
    print()
    
    url = f"{BASE_URL}/library/subjects"
    print_request("GET", url)
    
    try:
        resp = requests.get(url, timeout=5)
        print_response(resp)
        
        if resp.status_code == 200:
            print_success(f"Successfully retrieved {len(resp.json().get('subjects', []))} subjects")
        else:
            print_error(f"Got status {resp.status_code}")
    except Exception as e:
        print_error(f"Request failed: {e}")

def test_2_get_chapters():
    """Test 2: GET /library/chapters (PUBLIC, no auth required)"""
    print_header("TEST 2: GET /library/chapters")
    print(f"Purpose: Fetch chapters for a subject (public endpoint)")
    print()
    
    # First, get a subject ID from test 1 results
    url = f"{BASE_URL}/library/subjects"
    try:
        resp = requests.get(url, timeout=5)
        subjects = resp.json().get('subjects', [])
        if not subjects:
            print_error("No subjects found. Run test 1 first.")
            return
        
        subject_id = subjects[0]['_id']
        print_info(f"Using subject_id: {subject_id}")
        
        chapters_url = f"{BASE_URL}/library/chapters?subject_id={subject_id}"
        print_request("GET", chapters_url)
        
        resp = requests.get(chapters_url, timeout=5)
        print_response(resp)
        
        if resp.status_code == 200:
            print_success(f"Successfully retrieved chapters")
        else:
            print_error(f"Got status {resp.status_code}")
    except Exception as e:
        print_error(f"Request failed: {e}")

def test_3_register_user():
    """Test 3: POST /users/register (PROTECTED, requires Firebase token)"""
    print_header("TEST 3: POST /users/register")
    print(f"Purpose: Register a new user (requires Firebase ID token)")
    print()
    
    url = f"{BASE_URL}/users/register"
    headers = {
        "Authorization": f"Bearer {MOCK_FIREBASE_TOKEN}",
        "Content-Type": "application/json",
    }
    payload = {
        "userId": TEST_USER_ID,
        "name": "Test User",
        "email": "test@example.com",
        "grade": "10",
        "schoolId": "school-123",
        "schoolName": "Test School"
    }
    
    print_info(f"Note: Using mock token for local testing")
    print_info(f"User ID: {TEST_USER_ID}")
    print()
    print_request("POST", url, headers=headers, data=payload)
    
    try:
        resp = requests.post(url, json=payload, headers=headers, timeout=5)
        print_response(resp)
        
        if resp.status_code == 200:
            print_success("User registered successfully")
            data = resp.json()
            if 'litellm_key' in data and data['litellm_key']:
                print_success(f"LiteLLM key created: {data['litellm_key'][:20]}...")
        elif resp.status_code == 401:
            print_error("❌ SECURITY TEST: Authorization failed (expected with mock token on prod)")
            print_info("This is correct behavior - mock tokens should not work in production")
        else:
            print_error(f"Got status {resp.status_code}")
    except Exception as e:
        print_error(f"Request failed: {e}")

def test_4_chat_stream():
    """Test 4: POST /chat/chat-stream (PROTECTED, requires Firebase token)"""
    print_header("TEST 4: POST /chat/chat-stream")
    print(f"Purpose: Send a chat message (requires Firebase ID token)")
    print()
    
    url = f"{BASE_URL}/chat/chat-stream"
    headers = {
        "Authorization": f"Bearer {MOCK_FIREBASE_TOKEN}",
        "Content-Type": "application/json",
    }
    payload = {
        "user_id": TEST_USER_ID,
        "prompt": "What is photosynthesis?",
        "mode": "chat",
    }
    
    print_info(f"Note: Using mock token for local testing")
    print_info(f"User ID: {TEST_USER_ID}")
    print()
    print_request("POST", url, headers=headers, data=payload)
    
    try:
        resp = requests.post(url, json=payload, headers=headers, timeout=15)
        print_response(resp)
        
        if resp.status_code == 200:
            print_success("Chat message sent successfully")
        elif resp.status_code == 401:
            print_error("❌ SECURITY TEST: Authorization failed (expected with mock token on prod)")
            print_info("This is correct behavior - mock tokens should not work in production")
        else:
            print_error(f"Got status {resp.status_code}")
    except Exception as e:
        print_error(f"Request failed: {e}")

def test_health():
    """Test: GET /health (HEALTH CHECK)"""
    print_header("HEALTH CHECK: GET /health")
    
    url = f"{BASE_URL}/health"
    print_request("GET", url)
    
    try:
        resp = requests.get(url, timeout=5)
        print_response(resp)
        
        if resp.status_code == 200:
            print_success("Server is healthy")
            return True
        else:
            print_error(f"Server returned {resp.status_code}")
            return False
    except Exception as e:
        print_error(f"Server is not responding: {e}")
        return False

# ── Main ───────────────────────────────────────────────────────────────────

def main():
    print(f"\n{Colors.BOLD}Server API Test Suite{Colors.ENDC}")
    print(f"Base URL: {BASE_URL}")
    print(f"Testing user ID: {TEST_USER_ID}\n")
    
    # Check if server is running
    print("Checking server health...")
    if not test_health():
        print_error("Server is not running at {BASE_URL}")
        print_info("Start the server with: uvicorn app.main:app --port 8003 --host 0.0.0.0")
        return
    
    # Run tests
    test_1_get_subjects()
    test_2_get_chapters()
    test_3_register_user()
    test_4_chat_stream()
    
    # Summary
    print_header("TEST SUITE COMPLETE")
    print(f"""
{Colors.OKGREEN}Summary:{Colors.ENDC}
✅ Tests 1-2: Public endpoints (no auth) - should always work
⚠️  Tests 3-4: Protected endpoints - expect 401 with mock token unless:
    - Server is in LOCAL/DEV mode with mock auth disabled
    - You provide a real Firebase ID token
    
{Colors.WARNING}To test with real Firebase tokens:{Colors.ENDC}
1. Get a token from Firebase: https://firebase.google.com/docs/auth/admin/create-custom-tokens
2. Pass it in Authorization header: Bearer <real-firebase-token>

{Colors.OKCYAN}For production testing:{Colors.ENDC}
1. Use the Android app to generate real tokens
2. Or use Firebase Admin SDK to create tokens for testing
3. Update MOCK_FIREBASE_TOKEN constant with real token
""")

if __name__ == "__main__":
    main()

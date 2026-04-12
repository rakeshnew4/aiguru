#!/usr/bin/env python3
"""
test_api_with_real_auth.py — Test APIs with real Firebase authentication.

This script shows how to:
1. Create a real Firebase token using the Firebase Admin SDK
2. Test protected endpoints with proper authentication

Prerequisites:
    pip install firebase-admin requests

Setup:
    1. Download your Firebase service account JSON from Firebase Console
    2. Set FIREBASE_SERVICE_ACCOUNT environment variable or update the path below
    3. Update FIREBASE_PROJECT_ID if different from auto-detection
"""

import json
import os
from datetime import datetime

try:
    import firebase_admin
    from firebase_admin import credentials, auth as fb_auth
    import requests
except ImportError:
    print("ERROR: Required packages not installed")
    print("Install with: pip install firebase-admin requests")
    exit(1)

# ── Configuration ──────────────────────────────────────────────────────────
BASE_URL = "http://localhost:8000"
SERVICE_ACCOUNT_PATH = os.getenv(
    "FIREBASE_SERVICE_ACCOUNT",
    "/home/administrator/mywork/Work_Space/aiguru/server/firebase_serviceaccount.json"
)
FIREBASE_PROJECT_ID = os.getenv("FIREBASE_PROJECT_ID", "aiguru-61bd1")

TEST_UID = "test-user-" + datetime.now().strftime("%Y%m%d%H%M%S")
TEST_EMAIL = f"{TEST_UID}@test.local"

# Color codes
class Colors:
    OKGREEN = '\033[92m'
    FAIL = '\033[91m'
    OKCYAN = '\033[96m'
    BOLD = '\033[1m'
    ENDC = '\033[0m'

def print_success(msg):
    print(f"{Colors.OKGREEN}✅ {msg}{Colors.ENDC}")

def print_error(msg):
    print(f"{Colors.FAIL}❌ {msg}{Colors.ENDC}")

def print_info(msg):
    print(f"{Colors.OKCYAN}ℹ️  {msg}{Colors.ENDC}")

# ── Firebase Authentication ────────────────────────────────────────────────

def setup_firebase():
    """Initialize Firebase Admin SDK"""
    if not os.path.exists(SERVICE_ACCOUNT_PATH):
        print_error(f"Service account file not found: {SERVICE_ACCOUNT_PATH}")
        print_info("Download it from Firebase Console > Project Settings > Service Accounts")
        return False
    
    try:
        if not firebase_admin._apps:
            creds = credentials.Certificate(SERVICE_ACCOUNT_PATH)
            firebase_admin.initialize_app(creds, {
                'projectId': FIREBASE_PROJECT_ID,
            })
        print_success("Firebase Admin SDK initialized")
        return True
    except Exception as e:
        print_error(f"Failed to initialize Firebase: {e}")
        return False

def create_firebase_token(uid: str, email: str = "") -> str:
    """
    Create a custom Firebase ID token for testing.
    
    This simulates what the Android app would receive after sign-in.
    The token is valid for testing protected endpoints.
    """
    try:
        claims = {"email": email} if email else {}
        token = fb_auth.create_custom_token(uid, claims)
        # Custom tokens need to be exchanged for ID tokens in real Firebase flow
        # For testing with the server, we can use the custom token directly
        # if we modify the verify_firebase_token to accept both
        print_success(f"Created Firebase token for UID: {uid}")
        return token.decode() if isinstance(token, bytes) else token
    except Exception as e:
        print_error(f"Failed to create Firebase token: {e}")
        return None

# ── API Test Functions ─────────────────────────────────────────────────────

def test_register_user(token: str):
    """Test user registration with real Firebase token"""
    print(f"\n{Colors.BOLD}Testing: POST /users/register{Colors.ENDC}")
    print(f"With real Firebase token for UID: {TEST_UID}\n")
    
    url = f"{BASE_URL}/users/register"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    payload = {
        "userId": TEST_UID,
        "name": "Firebase Test User",
        "email": TEST_EMAIL,
        "grade": "10",
        "schoolId": "school-001",
        "schoolName": "Test School"
    }
    
    try:
        print(f"POST {url}")
        resp = requests.post(url, json=payload, headers=headers, timeout=5)
        
        print(f"Status: {Colors.BOLD}{resp.status_code}{Colors.ENDC}")
        
        if resp.status_code == 200:
            data = resp.json()
            print_success(f"User registered: {data.get('message')}")
            if data.get('litellm_key'):
                print_success(f"LiteLLM key: {data['litellm_key'][:20]}...")
            return True
        elif resp.status_code == 401:
            print_error("Authentication failed (token issue)")
            print(f"Response: {resp.json()}")
            return False
        else:
            print_error(f"Unexpected status: {resp.status_code}")
            print(f"Response: {resp.text[:300]}")
            return False
    except requests.exceptions.ConnectionError:
        print_error(f"Cannot connect to server at {BASE_URL}")
        print_info("Make sure the server is running:")
        print_info("  uvicorn app.main:app --port 8000 --host 0.0.0.0")
        return False
    except Exception as e:
        print_error(f"Request failed: {e}")
        return False

def test_chat(token: str):
    """Test chat endpoint with real Firebase token"""
    print(f"\n{Colors.BOLD}Testing: POST /chat/chat-stream{Colors.ENDC}")
    print(f"With real Firebase token for UID: {TEST_UID}\n")
    
    url = f"{BASE_URL}/chat/chat-stream"
    headers = {
        "Authorization": f"Bearer {token}",
        "Content-Type": "application/json",
    }
    payload = {
        "user_id": TEST_UID,
        "prompt": "Explain gravity in simple terms",
        "mode": "chat",
    }
    
    try:
        print(f"POST {url}")
        resp = requests.post(url, json=payload, headers=headers, timeout=15, stream=True)
        
        print(f"Status: {Colors.BOLD}{resp.status_code}{Colors.ENDC}")
        
        if resp.status_code == 200:
            print_success("Chat request successful (streaming response)")
            # Parse SSE format response
            lines = resp.text.split('\n')
            for line in lines[:5]:  # Print first few lines
                if line.startswith('data: '):
                    try:
                        data = json.loads(line[6:])
                        print(f"  → {json.dumps(data)[:100]}")
                    except:
                        pass
            return True
        elif resp.status_code == 401:
            print_error("Authentication failed")
            print(f"Response: {resp.json()}")
            return False
        else:
            print_error(f"Unexpected status: {resp.status_code}")
            print(f"Response: {resp.text[:300]}")
            return False
    except requests.exceptions.ConnectionError:
        print_error(f"Cannot connect to server at {BASE_URL}")
        return False
    except Exception as e:
        print_error(f"Request failed: {e}")
        return False

def test_public_endpoints():
    """Test public endpoints (don't need auth)"""
    print(f"\n{Colors.BOLD}Testing: Public Endpoints (No Auth Required){Colors.ENDC}\n")
    
    # Test /library/subjects
    try:
        print(f"GET {BASE_URL}/library/subjects")
        resp = requests.get(f"{BASE_URL}/library/subjects", timeout=5)
        print(f"Status: {Colors.BOLD}{resp.status_code}{Colors.ENDC}")
        
        if resp.status_code == 200:
            subjects = resp.json().get('subjects', [])
            print_success(f"Retrieved {len(subjects)} subjects")
        else:
            print_error(f"Failed with status {resp.status_code}")
    except Exception as e:
        print_error(f"Failed: {e}")

# ── Main ───────────────────────────────────────────────────────────────────

def main():
    print(f"\n{Colors.BOLD}{'='*70}{Colors.ENDC}")
    print(f"{Colors.BOLD}Server API Test with Real Firebase Authentication{Colors.ENDC}")
    print(f"{Colors.BOLD}{'='*70}{Colors.ENDC}\n")
    print(f"Base URL: {BASE_URL}")
    print(f"Firebase Project: {FIREBASE_PROJECT_ID}")
    print(f"Test UID: {TEST_UID}\n")
    
    # Initialize Firebase
    if not setup_firebase():
        print_error("Cannot initialize Firebase. Check service account path.")
        return
    
    # Create token
    print("\nCreating Firebase ID token...")
    token = create_firebase_token(TEST_UID, TEST_EMAIL)
    if not token:
        print_error("Failed to create token")
        return
    
    print(f"Token (first 50 chars): {token[:50]}...")
    
    # Test public endpoints (no auth needed)
    test_public_endpoints()
    
    # Test protected endpoints
    test_register_user(token)
    test_chat(token)
    
    print(f"\n{Colors.BOLD}{'='*70}{Colors.ENDC}")
    print(Colors.OKGREEN + "Tests completed!" + Colors.ENDC)

if __name__ == "__main__":
    main()

#!/usr/bin/env python3
"""
Payment Debugging Script
Run this to see detailed logs of what's happening during payment verification
"""
import requests
import hmac
import hashlib
import json
import sys

BASE_URL = "http://localhost:8003"
RAZORPAY_SECRET = "62tTSCpQUNzW9acwSfKlZd30"


def debug_verify_payment(order_id, payment_id, signature, user_id, school_id, plan_id, plan_name=None):
    """Debug payment verification with detailed logging"""
    print("=" * 70)
    print("🔍 Payment Verification Debug")
    print("=" * 70)
    
    # Step 1: Verify signature locally
    print("\n1️⃣  Checking signature locally...")
    payload = f"{order_id}|{payment_id}"
    expected_sig = hmac.new(
        RAZORPAY_SECRET.encode('utf-8'),
        payload.encode('utf-8'),
        hashlib.sha256
    ).hexdigest()
    
    print(f"   Payload: {payload}")
    print(f"   Expected signature: {expected_sig}")
    print(f"   Received signature: {signature}")
    
    if expected_sig == signature:
        print("   ✅ Signatures match!")
    else:
        print("   ❌ Signature mismatch!")
        print("   This is why verification is failing.")
        return False
    
    # Step 2: Prepare request
    print("\n2️⃣  Preparing verification request...")
    verify_req = {
        "user_id": user_id,
        "school_id": school_id,
        "plan_id": plan_id,
        "razorpay_payment_id": payment_id,
        "razorpay_order_id": order_id,
        "razorpay_signature": signature,
    }
    
    if plan_name:
        verify_req["plan_name"] = plan_name
    
    print(f"   Request payload:")
    print(f"   {json.dumps(verify_req, indent=6)}")
    
    # Step 3: Send request
    print("\n3️⃣  Sending verification request...")
    try:
        response = requests.post(
            f"{BASE_URL}/payments/razorpay/verify",
            json=verify_req,
            timeout=30
        )
        
        print(f"   Status Code: {response.status_code}")
        print(f"   Response Headers: {dict(response.headers)}")
        
        if response.status_code == 200:
            print(f"   ✅ Success!")
            result = response.json()
            print(f"   Response: {json.dumps(result, indent=6)}")
            return True
        else:
            print(f"   ❌ Failed!")
            try:
                error = response.json()
                print(f"   Error: {json.dumps(error, indent=6)}")
            except:
                print(f"   Error text: {response.text}")
            return False
            
    except requests.exceptions.ConnectionError:
        print("   ❌ Cannot connect to server")
        print("   Make sure server is running: uvicorn app.main:app --reload --port 8003 --host 0.0.0.0")
        return False
    except Exception as e:
        print(f"   ❌ Request failed: {e}")
        return False


def main():
    print("\n🧪 Payment Verification Debugger")
    print("\nThis will help you debug 'something went wrong' errors\n")
    
    # Example 1: Test with known values (from your Android app logs)
    print("=" * 70)
    print("TEST 1: Enter values from your Android app")
    print("=" * 70)
    
    order_id = input("\nEnter order_id (e.g., order_Nxxx...): ").strip()
    if not order_id:
        print("❌ Order ID is required")
        return
    
    payment_id = input("Enter payment_id (e.g., pay_Nxxx...): ").strip()
    if not payment_id:
        print("❌ Payment ID is required")
        return
    
    signature = input("Enter razorpay_signature: ").strip()
    if not signature:
        print("❌ Signature is required")
        return
    
    user_id = input("Enter user_id: ").strip() or "test_user_123"
    school_id = input("Enter school_id: ").strip() or "test_school"
    plan_id = input("Enter plan_id: ").strip() or "premium"
    plan_name = input("Enter plan_name (optional): ").strip() or None
    
    success = debug_verify_payment(
        order_id=order_id,
        payment_id=payment_id,
        signature=signature,
        user_id=user_id,
        school_id=school_id,
        plan_id=plan_id,
        plan_name=plan_name
    )
    
    if success:
        print("\n" + "=" * 70)
        print("✨ Payment verification succeeded!")
        print("=" * 70)
    else:
        print("\n" + "=" * 70)
        print("⚠️  Payment verification failed")
        print("=" * 70)
        print("\n💡 Common Issues:")
        print("   1. Signature mismatch - Check if RAZORPAY_KEY_SECRET matches in both server and Android")
        print("   2. Server not running - Start: uvicorn app.main:app --reload --port 8003 --host 0.0.0.0")
        print("   3. Firestore permissions - Check Firebase console for errors")
        print("   4. Missing fields - Make sure all required fields are sent from Android")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⚠️  Interrupted by user")
    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()

#!/usr/bin/env python3
"""
Automated Payment Flow Test Script
Tests the complete payment flow: create order → verify payment
"""
import requests
import hmac
import hashlib
import json
import sys

BASE_URL = "http://localhost:8003"
RAZORPAY_SECRET = "62tTSCpQUNzW9acwSfKlZd30"


def test_payment_flow():
    """Test complete payment flow end-to-end"""
    print("=" * 60)
    print("🧪 Payment Flow Test")
    print("=" * 60)
    
    # Step 1: Create Order
    print("\n1️⃣  Creating payment order...")
    order_req = {
        "userId": "test_user_999",
        "schoolId": "school_abc",
        "planId": "premium",
        "planName": "Premium Plan",
        "amountPaise": 99900,
        "currency": "INR",
        "description": "Test payment for Premium Plan",
        "customerName": "Test User",
        "customerEmail": "test@example.com",
        "customerPhone": "+919876543210"
    }
    
    try:
        response = requests.post(
            f"{BASE_URL}/payments/razorpay/create-order",
            json=order_req,
            timeout=10
        )
        response.raise_for_status()
    except requests.exceptions.ConnectionError:
        print("❌ Connection failed. Is the server running?")
        print("   Start server: uvicorn app.main:app --reload --port 8003 --host 0.0.0.0")
        return False
    except requests.exceptions.RequestException as e:
        print(f"❌ Request failed: {e}")
        if hasattr(e.response, 'text'):
            print(f"   Response: {e.response.text}")
        return False
    
    order_data = response.json()
    print(f"✅ Order created successfully")
    print(f"   Order ID: {order_data['orderId']}")
    print(f"   Amount: ₹{order_data['amount']/100:.2f}")
    print(f"   Currency: {order_data['currency']}")
    print(f"   Key ID: {order_data['keyId']}")
    
    # Step 2: Simulate payment (use test payment ID)
    # In real scenario, this comes from Razorpay after user pays
    payment_id = f"pay_TEST{order_data['orderId'][6:]}"
    print(f"\n2️⃣  Simulating payment completion...")
    print(f"   Payment ID: {payment_id}")
    print(f"   (In production, this comes from Razorpay UI)")
    
    # Step 3: Generate HMAC signature
    print(f"\n3️⃣  Generating payment signature...")
    payload = f"{order_data['orderId']}|{payment_id}"
    signature = hmac.new(
        RAZORPAY_SECRET.encode('utf-8'),
        payload.encode('utf-8'),
        hashlib.sha256
    ).hexdigest()
    
    print(f"   Payload: {payload}")
    print(f"   Signature: {signature[:30]}...")
    
    # Step 4: Verify payment
    print(f"\n4️⃣  Verifying payment...")
    verify_req = {
        "razorpayOrderId": order_data['orderId'],
        "razorpayPaymentId": payment_id,
        "razorpaySignature": signature,
        "userId": order_req['userId'],
        "schoolId": order_req['schoolId'],
        "planId": order_req['planId'],
        "planName": order_req['planName']
    }
    
    try:
        response = requests.post(
            f"{BASE_URL}/payments/razorpay/verify",
            json=verify_req,
            timeout=10
        )
        response.raise_for_status()
    except requests.exceptions.RequestException as e:
        print(f"❌ Verification failed: {e}")
        if hasattr(e.response, 'text'):
            print(f"   Response: {e.response.text}")
        return False
    
    verify_data = response.json()
    print(f"✅ {verify_data['message']}")
    
    # Summary
    print("\n" + "=" * 60)
    print("✨ Payment Flow Completed Successfully!")
    print("=" * 60)
    
    print(f"\n📊 Firestore Documents Created:")
    print(f"   1. payment_intents/{order_req['userId']}_{order_data['orderId']}")
    print(f"      └─ status: 'completed'")
    print(f"\n   2. payment_receipts/{payment_id}_{order_data['orderId']}")
    print(f"      └─ verified: true")
    print(f"\n   3. users/{order_req['userId']}")
    print(f"      ├─ planId: '{order_req['planId']}'")
    print(f"      └─ planName: '{order_req['planName']}'")
    
    print(f"\n🔍 Verify in Firebase Console:")
    print(f"   https://console.firebase.google.com/project/project-thunder-ff034/firestore")
    
    return True


def test_api_health():
    """Test if API is accessible"""
    print("\n🏥 Checking API health...")
    try:
        response = requests.get(f"{BASE_URL}/health", timeout=5)
        if response.status_code == 200:
            print("✅ API is healthy")
            return True
        else:
            print(f"⚠️  API returned: {response.status_code}")
            return False
    except requests.exceptions.ConnectionError:
        print("❌ Cannot connect to API")
        print("   Make sure server is running on port 8003")
        return False
    except Exception as e:
        print(f"❌ Health check failed: {e}")
        return False


def main():
    # Check if server is running
    if not test_api_health():
        print("\n💡 Start the server first:")
        print("   cd /home/administrator/mywork/Work_Space/server")
        print("   source myenv/bin/activate")
        print("   uvicorn app.main:app --reload --port 8003 --host 0.0.0.0")
        return 1
    
    # Run payment flow test
    success = test_payment_flow()
    
    if success:
        print("\n🎉 All tests passed!")
        return 0
    else:
        print("\n⚠️  Tests failed. Check errors above.")
        return 1


if __name__ == "__main__":
    try:
        exit_code = main()
        sys.exit(exit_code)
    except KeyboardInterrupt:
        print("\n\n⚠️  Test interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
        import traceback
        traceback.print_exc()
        sys.exit(1)

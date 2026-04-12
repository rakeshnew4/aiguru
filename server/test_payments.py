#!/usr/bin/env python3
"""
Test script for payments.py functionality
Tests Firebase, Razorpay configuration, and endpoint readiness
"""

import sys
sys.path.insert(0, '.')

def test_firebase():
    """Test Firebase Admin SDK initialization"""
    print("🔍 Testing Firebase Configuration...")
    try:
        import firebase_admin
        from firebase_admin import credentials, firestore
        from app.core.config import settings
        
        # Check service account file exists
        import os
        if not os.path.exists(settings.FIREBASE_SERVICE_ACCOUNT):
            print(f"❌ Firebase service account file not found: {settings.FIREBASE_SERVICE_ACCOUNT}")
            return False
        
        # Initialize Firebase
        if not firebase_admin._apps:
            cred = credentials.Certificate(settings.FIREBASE_SERVICE_ACCOUNT)
            firebase_admin.initialize_app(cred)
        
        # Test Firestore connection
        db = firestore.client()
        
        print(f"✅ Firebase initialized successfully")
        print(f"   Service Account: {settings.FIREBASE_SERVICE_ACCOUNT}")
        print(f"   Database: {db._database}")
        return True
        
    except Exception as e:
        print(f"❌ Firebase test failed: {e}")
        return False


def test_razorpay():
    """Test Razorpay configuration"""
    print("\n🔍 Testing Razorpay Configuration...")
    try:
        from app.api.payments import get_razorpay_client
        from app.core.config import settings
        
        if not settings.RAZORPAY_KEY_ID or not settings.RAZORPAY_KEY_SECRET:
            print("❌ Razorpay credentials not configured in .env")
            return False
        
        client = get_razorpay_client()
        print(f"✅ Razorpay client created successfully")
        print(f"   Key ID: {settings.RAZORPAY_KEY_ID}")
        print(f"   Environment: {'Test' if 'test' in settings.RAZORPAY_KEY_ID else 'Live'}")
        return True
        
    except Exception as e:
        print(f"❌ Razorpay test failed: {e}")
        return False


def test_endpoints():
    """Test payments endpoints are registered"""
    print("\n🔍 Testing Payment Endpoints...")
    try:
        from app.main import app
        
        payment_routes = []
        for route in app.routes:
            if hasattr(route, 'path') and 'payment' in route.path.lower():
                methods = ', '.join(route.methods) if hasattr(route, 'methods') else 'N/A'
                payment_routes.append((methods, route.path))
        
        if not payment_routes:
            print("❌ No payment routes registered")
            return False
        
        print(f"✅ {len(payment_routes)} payment endpoints registered:")
        for methods, path in sorted(payment_routes):
            print(f"   {methods:8} {path}")
        
        return True
        
    except Exception as e:
        print(f"❌ Endpoints test failed: {e}")
        return False


def test_models():
    """Test Pydantic models"""
    print("\n🔍 Testing Payment Models...")
    try:
        from app.api.payments import (
            CreateOrderRequest,
            CreateOrderResponse,
            VerifyPaymentRequest,
            VerifyPaymentResponse
        )
        
        # Test CreateOrderRequest
        order_req = CreateOrderRequest(
            userId="test_user",
            schoolId="test_school",
            planId="premium",
            planName="Premium Plan",
            amountPaise=99900,  # ₹999
        )
        
        print(f"✅ Payment models validated successfully")
        print(f"   CreateOrderRequest: {len(CreateOrderRequest.model_fields)} fields")
        print(f"   CreateOrderResponse: {len(CreateOrderResponse.model_fields)} fields")
        print(f"   VerifyPaymentRequest: {len(VerifyPaymentRequest.model_fields)} fields")
        print(f"   VerifyPaymentResponse: {len(VerifyPaymentResponse.model_fields)} fields")
        return True
        
    except Exception as e:
        print(f"❌ Models test failed: {e}")
        return False


def main():
    print("=" * 60)
    print("Payment System Configuration Test")
    print("=" * 60)
    
    results = {
        "Firebase": test_firebase(),
        "Razorpay": test_razorpay(),
        "Endpoints": test_endpoints(),
        "Models": test_models(),
    }
    
    print("\n" + "=" * 60)
    print("Test Summary")
    print("=" * 60)
    
    for test_name, passed in results.items():
        status = "✅ PASS" if passed else "❌ FAIL"
        print(f"{test_name:15} {status}")
    
    all_passed = all(results.values())
    print("=" * 60)
    
    if all_passed:
        print("\n🎉 All tests passed! Payment system is ready to use.")
        print("\n📝 Next Steps:")
        print("   1. Start server: uvicorn app.main:app --reload --port 8003 --host 0.0.0.0")
        print("   2. Test from Android app with user subscription flow")
        print("   3. Monitor logs for payment transactions")
        return 0
    else:
        print("\n⚠️  Some tests failed. Please fix the issues above.")
        return 1


if __name__ == "__main__":
    exit(main())

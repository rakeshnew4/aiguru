# Payment System Testing Guide

## Quick Start

### 1. Start the Server
```bash
cd /home/administrator/mywork/Work_Space/server
source myenv/bin/activate
uvicorn app.main:app --reload --port 8003 --host 0.0.0.0
```

Server will be available at: `http://localhost:8003`

---

## Testing Payment Flow

### Step 1: Create Payment Order

**Endpoint:** `POST /payments/razorpay/create-order`

```bash
curl -X POST http://localhost:8003/payments/razorpay/create-order \
  -H "Content-Type: application/json" \
  -d '{
    "userId": "test_user_123",
    "schoolId": "school_456",
    "planId": "premium",
    "planName": "Premium Plan",
    "amountPaise": 99900,
    "currency": "INR",
    "description": "Premium subscription",
    "customerName": "Test User",
    "customerEmail": "test@example.com",
    "customerPhone": "+919876543210"
  }'
```

**Expected Response:**
```json
{
  "orderId": "order_Nxxx...",
  "amount": 99900,
  "currency": "INR",
  "keyId": "rzp_test_SWYJxX0vJpdv4i"
}
```

**What Happens:**
- ✅ Razorpay order created
- ✅ Firestore `payment_intents/{userId}_{orderId}` document created with status "pending"

---

### Step 2: Simulate Payment (Test Mode)

Since you're in **test mode** (`rzp_test_...`), you can use Razorpay's test card:

**Test Card Details:**
- Card Number: `4111 1111 1111 1111`
- CVV: Any 3 digits (e.g., `123`)
- Expiry: Any future date (e.g., `12/25`)
- Name: Any name

**OR** simulate directly with test credentials to get `razorpay_payment_id`.

---

### Step 3: Verify Payment

**Endpoint:** `POST /payments/razorpay/verify`

For testing, you need to generate a valid signature. Here's a test script:

```python
import hmac
import hashlib

# From Step 1 response
order_id = "order_Nxxx..."
# From Razorpay payment (or use test ID)
payment_id = "pay_Nxxx..."

# Your Razorpay secret
secret = "62tTSCpQUNzW9acwSfKlZd30"

# Generate signature
payload = f"{order_id}|{payment_id}"
signature = hmac.new(
    secret.encode('utf-8'),
    payload.encode('utf-8'),
    hashlib.sha256
).hexdigest()

print(f"Signature: {signature}")
```

**Then verify:**
```bash
curl -X POST http://localhost:8003/payments/razorpay/verify \
  -H "Content-Type: application/json" \
  -d '{
    "razorpayOrderId": "order_Nxxx...",
    "razorpayPaymentId": "pay_Nxxx...",
    "razorpaySignature": "generated_signature_here",
    "userId": "test_user_123",
    "schoolId": "school_456",
    "planId": "premium",
    "planName": "Premium Plan"
  }'
```

**Expected Response:**
```json
{
  "success": true,
  "message": "Payment verified successfully"
}
```

**What Happens:**
- ✅ Signature validated
- ✅ Firestore `payment_receipts/{paymentId}_{orderId}` created
- ✅ Firestore `users/{userId}` updated with `planId: "premium"`, `planName: "Premium Plan"`
- ✅ Firestore `payment_intents/{userId}_{orderId}` marked as "completed"

---

## Automated Testing Script

Save this as `test_payment_flow.py`:

```python
#!/usr/bin/env python3
import requests
import hmac
import hashlib
import json

BASE_URL = "http://localhost:8003"
RAZORPAY_SECRET = "62tTSCpQUNzW9acwSfKlZd30"

def test_payment_flow():
    print("🧪 Testing Payment Flow...\n")
    
    # Step 1: Create Order
    print("1️⃣  Creating payment order...")
    order_req = {
        "userId": "test_user_999",
        "schoolId": "school_abc",
        "planId": "premium",
        "planName": "Premium Plan",
        "amountPaise": 99900,
        "customerEmail": "test@example.com"
    }
    
    response = requests.post(f"{BASE_URL}/payments/razorpay/create-order", json=order_req)
    
    if response.status_code != 200:
        print(f"❌ Failed: {response.status_code} - {response.text}")
        return
    
    order_data = response.json()
    print(f"✅ Order created: {order_data['orderId']}")
    print(f"   Amount: ₹{order_data['amount']/100:.2f}")
    
    # Step 2: Simulate payment (use test payment ID)
    payment_id = f"pay_TEST{order_data['orderId'][6:]}"
    print(f"\n2️⃣  Simulating payment: {payment_id}")
    
    # Step 3: Generate signature
    payload = f"{order_data['orderId']}|{payment_id}"
    signature = hmac.new(
        RAZORPAY_SECRET.encode('utf-8'),
        payload.encode('utf-8'),
        hashlib.sha256
    ).hexdigest()
    
    print(f"   Signature: {signature[:20]}...")
    
    # Step 4: Verify payment
    print(f"\n3️⃣  Verifying payment...")
    verify_req = {
        "razorpayOrderId": order_data['orderId'],
        "razorpayPaymentId": payment_id,
        "razorpaySignature": signature,
        "userId": order_req['userId'],
        "schoolId": order_req['schoolId'],
        "planId": order_req['planId'],
        "planName": order_req['planName']
    }
    
    response = requests.post(f"{BASE_URL}/payments/razorpay/verify", json=verify_req)
    
    if response.status_code != 200:
        print(f"❌ Verification failed: {response.status_code} - {response.text}")
        return
    
    verify_data = response.json()
    print(f"✅ {verify_data['message']}")
    
    print("\n✨ Payment flow completed successfully!")
    print(f"\n📊 Check Firestore:")
    print(f"   - users/{order_req['userId']} → planId should be 'premium'")
    print(f"   - payment_receipts/{payment_id}_{order_data['orderId']}")
    print(f"   - payment_intents/{order_req['userId']}_{order_data['orderId']}")

if __name__ == "__main__":
    try:
        test_payment_flow()
    except Exception as e:
        print(f"\n❌ Error: {e}")
        import traceback
        traceback.print_exc()
```

**Run it:**
```bash
python3 test_payment_flow.py
```

---

## Testing from Android App

### Configure Android App

In your Android app's payment flow:

```kotlin
// 1. Create order
val orderRequest = CreateOrderRequest(
    userId = currentUserId,
    schoolId = currentSchoolId,
    planId = "premium",
    planName = "Premium Plan",
    amountPaise = 99900  // ₹999
)

// Call your backend
val response = apiService.createOrder(orderRequest)

// 2. Open Razorpay checkout
val checkout = Checkout()
checkout.setKeyID(response.keyId)

val options = JSONObject()
options.put("order_id", response.orderId)
options.put("amount", response.amount)
options.put("currency", response.currency)
options.put("name", "Your App Name")

checkout.open(activity, options)

// 3. In payment success callback
override fun onPaymentSuccess(razorpayPaymentId: String) {
    val verifyRequest = VerifyPaymentRequest(
        razorpayOrderId = orderId,
        razorpayPaymentId = razorpayPaymentId,
        razorpaySignature = razorpaySignature,
        userId = currentUserId,
        schoolId = currentSchoolId,
        planId = "premium",
        planName = "Premium Plan"
    )
    
    apiService.verifyPayment(verifyRequest)
}
```

---

## Verify in Firebase Console

### 1. Go to Firebase Console
`https://console.firebase.google.com/project/project-thunder-ff034`

### 2. Navigate to Firestore Database

### 3. Check Collections

**✅ `payment_intents`:**
```
payment_intents/
  └── test_user_123_order_Nxxx...
      ├── userId: "test_user_123"
      ├── planId: "premium"
      ├── status: "completed"
      └── amountPaise: 99900
```

**✅ `payment_receipts`:**
```
payment_receipts/
  └── pay_Nxxx..._order_Nxxx...
      ├── verified: true
      ├── userId: "test_user_123"
      ├── planId: "premium"
      └── amountPaise: 99900
```

**✅ `users`:**
```
users/
  └── test_user_123
      ├── planId: "premium"
      ├── planName: "Premium Plan"
      └── updatedAt: 1743667200000
```

---

## Testing Webhook (Optional)

**Endpoint:** `POST /payments/razorpay/webhook`

To test webhooks locally, use **ngrok**:

```bash
# Install ngrok (if not installed)
# Download from: https://ngrok.com/download

# Start ngrok tunnel
ngrok http 8003

# You'll get a URL like: https://abc123.ngrok.io
```

**Configure in Razorpay Dashboard:**
1. Go to: https://dashboard.razorpay.com/app/webhooks
2. Add webhook URL: `https://abc123.ngrok.io/payments/razorpay/webhook`
3. Select events: `payment.captured`, `payment.failed`
4. Copy the webhook secret and update `.env`:
   ```env
   RAZORPAY_WEBHOOK_SECRET=whsec_xxxxx
   ```

---

## Troubleshooting

### Issue: "Firestore not configured"
**Fix:** Check `.env` has correct path:
```env
FIREBASE_SERVICE_ACCOUNT=./firebase_serviceaccount.json
```

### Issue: "Invalid payment signature"
**Fix:** Ensure you're using the correct `RAZORPAY_KEY_SECRET` from `.env`

### Issue: "Razorpay credentials not configured"
**Fix:** Check `.env` has:
```env
RAZORPAY_KEY_ID=rzp_test_SWYJxX0vJpdv4i
RAZORPAY_KEY_SECRET=62tTSCpQUNzW9acwSfKlZd30
```

### Issue: Server won't start
**Run:**
```bash
cd /home/administrator/mywork/Work_Space/server
source myenv/bin/activate
python3 test_payments.py  # Verify configuration
```

---

## Monitor Logs

**Watch server logs:**
```bash
# In the terminal where server is running
# You'll see:
# INFO: Payment order created: order_Nxxx...
# INFO: Payment verified successfully
```

**Check specific errors:**
```bash
tail -f server.log | grep payment
```

---

## Test Checklist

- [ ] Server starts without errors
- [ ] `test_payments.py` passes all tests
- [ ] Create order endpoint returns valid `orderId`
- [ ] Firestore `payment_intents` document created
- [ ] Verify endpoint accepts correct signature
- [ ] Firestore `users/{userId}` updated with plan
- [ ] Firestore `payment_receipts` document created
- [ ] Android app can create orders
- [ ] Android app can complete payments
- [ ] User plan appears in app after payment

---

## Production Checklist

Before going live:

- [ ] Replace test keys with live keys in `.env`:
  ```env
  RAZORPAY_KEY_ID=rzp_live_xxxxx
  RAZORPAY_KEY_SECRET=xxxxx
  ```
- [ ] Set up webhook URL (not localhost)
- [ ] Configure `RAZORPAY_WEBHOOK_SECRET`
- [ ] Test with small real payment
- [ ] Verify Firebase security rules
- [ ] Set up payment monitoring/alerts
- [ ] Add error tracking (Sentry, etc.)

---

## Quick Test Commands

```bash
# 1. Verify configuration
python3 test_payments.py

# 2. Start server
uvicorn app.main:app --reload --port 8003 --host 0.0.0.0

# 3. Test payment flow
python3 test_payment_flow.py

# 4. Check health
curl http://localhost:8003/health

# 5. View routes
curl http://localhost:8003/docs
```

---

## API Documentation

Once server is running, visit:
- **Swagger UI:** http://localhost:8003/docs
- **ReDoc:** http://localhost:8003/redoc

You can test all endpoints directly from the browser!

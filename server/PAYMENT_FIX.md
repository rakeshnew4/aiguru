# Fixing "Something Went Wrong" Payment Error

## What Was Fixed

### 1. **Missing Field in Request Model**
**Problem:** The `VerifyPaymentRequest` model was missing the `plan_name` field, causing an `AttributeError` when the code tried to access it.

**Fixed:** Added optional `plan_name` field:
```python
class VerifyPaymentRequest(BaseModel):
    user_id: str
    school_id: str
    plan_id: str
    plan_name: Optional[str] = None  # ← ADDED THIS
    razorpay_payment_id: str
    razorpay_order_id: str
    razorpay_signature: str
```

### 2. **Poor Error Handling**
**Problem:** Exceptions were not being caught or logged, so you only got a generic 500 error with "something went wrong".

**Fixed:** Added comprehensive try-catch block with detailed logging:
```python
try:
    # ... verification logic ...
    logger.info("Payment verification started...")
    logger.info("Signature verified successfully...")
    logger.info("Payment receipt created...")
    logger.info("User plan activated...")
    return VerifyPaymentResponse(verified=True, message="Payment verified successfully")
except HTTPException:
    raise  # Re-raise HTTP exceptions (signature mismatch, etc.)
except Exception as e:
    logger.exception(f"Payment verification failed: {e}")
    raise HTTPException(status_code=500, detail=f"Payment verification failed: {str(e)}")
```

### 3. **Added Detailed Logging**
Now you'll see exactly what's happening at each step:
- Signature verification
- Firestore lookups
- Payment receipt creation
- User plan activation

---

## How to Debug

### **Option 1: Check Server Logs**

When you run the payment from Android, watch the server terminal:

```bash
# You should see logs like:
INFO: Payment verification started for order=order_Nxxx..., payment=pay_Nxxx..., user=user_123
INFO: Signature verified successfully for order=order_Nxxx...
INFO: Retrieved plan_name from payment intent: Premium Plan
INFO: Fetched payment details from Razorpay: status=captured
INFO: Payment receipt created: pay_Nxxx..._order_Nxxx...
INFO: User plan activated: user=user_123, plan=premium
INFO: Payment verification completed successfully for user=user_123, plan=premium
```

**If you see an error, it will now show the exact problem!**

### **Option 2: Use Debug Script**

Run the interactive debugger:

```bash
cd /home/administrator/mywork/Work_Space/server
source myenv/bin/activate
python3 debug_payment.py
```

Then paste the values from your Android app's error logs:
- `order_id`
- `payment_id`
- `razorpay_signature`
- `user_id`, `school_id`, `plan_id`

The script will:
1. ✅ Verify the signature locally
2. ✅ Show you the exact request being sent
3. ✅ Display the server's response or error

---

## Common Issues & Fixes

### ❌ **Issue 1: Signature Mismatch**

**Error in logs:**
```
ERROR: Signature mismatch — order=order_Nxxx..., expected=abc123..., received=xyz789...
```

**Cause:** The Razorpay secret key doesn't match between server and Android app.

**Fix:**
1. Check `.env` on server:
   ```env
   RAZORPAY_KEY_SECRET=62tTSCpQUNzW9acwSfKlZd30
   ```

2. Check Android app's Razorpay initialization:
   ```kotlin
   // Make sure this matches your .env
   val razorpayKeySecret = "62tTSCpQUNzW9acwSfKlZd30"
   ```

### ❌ **Issue 2: Firestore Permission Denied**

**Error in logs:**
```
ERROR: Permission denied on Firestore
```

**Fix:**
1. Go to Firebase Console → Firestore → Rules
2. Update rules for testing:
   ```javascript
   rules_version = '2';
   service cloud.firestore {
     match /databases/{database}/documents {
       // Allow all reads/writes (for testing only!)
       match /{document=**} {
         allow read, write: if true;
       }
     }
   }
   ```
   **⚠️ For production, use proper authentication rules!**

### ❌ **Issue 3: Missing Fields from Android**

**Error in logs:**
```
ERROR: Field 'user_id' required but not provided
```

**Fix:** Update your Android app's verify request:

```kotlin
val verifyRequest = VerifyPaymentRequest(
    userId = currentUserId,           // ← Make sure all these
    schoolId = currentSchoolId,       // ← are being sent
    planId = selectedPlan.id,
    planName = selectedPlan.name,     // ← Optional but recommended
    razorpayPaymentId = razorpayPaymentId,
    razorpayOrderId = orderId,
    razorpaySignature = signature
)
```

### ❌ **Issue 4: Server Not Running**

**Error in Android:**
```
Connection refused
```

**Fix:**
```bash
cd /home/administrator/mywork/Work_Space/server
source myenv/bin/activate
uvicorn app.main:app --reload --port 8003 --host 0.0.0.0
```

### ❌ **Issue 5: Wrong Field Names (camelCase vs snake_case)**

**Problem:** Android sends `userId` but server expects `user_id`.

**Already Fixed:** Pydantic automatically converts between camelCase and snake_case! Just make sure your Android model uses the correct field names:

```kotlin
// Android model (uses camelCase - will auto-convert to snake_case)
@SerializedName("user_id")   // ← This tells Gson to use snake_case
val userId: String

// OR use the alias directly
@JsonProperty("user_id")     // ← Jackson converts to snake_case
val userId: String
```

---

## Testing After Fix

### **1. Restart Server**
```bash
# Stop current server (Ctrl+C)
uvicorn app.main:app --reload --port 8003 --host 0.0.0.0
```

### **2. Test from Android**
Try the payment flow again. Now you'll see:
- Detailed logs in the server terminal
- Specific error messages instead of generic "something went wrong"

### **3. Check Firestore**
After successful payment, verify in Firebase Console:
```
users/
  └── {userId}
      ├── planId: "premium"
      └── planName: "Premium Plan"

payment_receipts/
  └── pay_xxx_order_xxx
      ├── verified: true
      └── userId: "{userId}"
```

---

## What to Check Right Now

1. **Restart your server** to apply the fixes
2. **Try a payment** from Android app
3. **Watch the server logs** - you'll now see exactly what's happening
4. **If it still fails**, copy the error from server logs and paste it here

The error message will now be specific instead of generic!

---

## Quick Test

```bash
# Test the verify endpoint is working
curl -X POST http://localhost:8003/payments/razorpay/verify \
  -H "Content-Type: application/json" \
  -d '{
    "user_id": "test_user",
    "school_id": "test_school",
    "plan_id": "premium",
    "plan_name": "Premium Plan",
    "razorpay_payment_id": "pay_test123",
    "razorpay_order_id": "order_test123",
    "razorpay_signature": "invalid_sig"
  }'

# You should get a clear error:
# {"detail": "Invalid payment signature"}
# (not "something went wrong")
```

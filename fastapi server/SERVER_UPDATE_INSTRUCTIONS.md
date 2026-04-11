# FastAPI Server — Update Instructions

Apply the changes below to your production server files.
Two new files (`services/user_service.py`, `api/users.py`) have been added to
this directory and must be uploaded.

---

## New Files to Upload

| File | Purpose |
|------|---------|
| `services/user_service.py` | Writes to `users_table/{uid}` — user creation, plan activation, token tracking |
| `api/users.py` | `POST /users/register` endpoint |

---

## Changes to Existing Files

### 1. `api/chat.py`

**Step A — Add import** (after the other `from app.services` imports):
```python
from app.services import user_service
```

**Step B — Add `user_id` field to `ChatRequest`** (after `user_plan`):
```python
class ChatRequest(BaseModel):
    question: str
    page_id: str
    student_level: Optional[int] = 5
    history: List[str] = Field(default_factory=list)
    images: List[str] = Field(default_factory=list)
    image_base64: Optional[str] = None
    image_data: Optional[Dict[str, Any]] = None
    mode: Optional[str] = "normal"
    language: Optional[str] = "en-US"
    language_tag: Optional[str] = "en-US"
    user_plan: Optional[str] = "premium"
    # ADD THIS ↓
    user_id: Optional[str] = None   # Firebase Auth UID for token tracking
```

**Step C — Record tokens after the done frame** (in the `if tokens:` block):

Replace the existing done frame:
```python
            # 7) Done frame
            tokens = result.get("tokens", {})
            if tokens:
                yield (
                    "data: "
                    + json.dumps(
                        {
                            "done": True,
                            "suggest_blackboard": suggest_bb,
                            "inputTokens": tokens.get("inputTokens", 0),
                            "outputTokens": tokens.get("outputTokens", 0),
                            "totalTokens": tokens.get("totalTokens", 0),
                        }
                    )
                    + "\n\n"
                )
            else:
                yield f"data: {json.dumps({'done': True, 'suggest_blackboard': suggest_bb})}\n\n"
```

With this:
```python
            # 7) Done frame
            tokens = result.get("tokens", {})
            if tokens:
                in_t  = tokens.get("inputTokens", 0)
                out_t = tokens.get("outputTokens", 0)
                tot_t = tokens.get("totalTokens", 0)
                yield (
                    "data: "
                    + json.dumps(
                        {
                            "done": True,
                            "suggest_blackboard": suggest_bb,
                            "inputTokens": in_t,
                            "outputTokens": out_t,
                            "totalTokens": tot_t,
                        }
                    )
                    + "\n\n"
                )
                # 8) Fire-and-forget: update token counters in Firestore
                if req.user_id:
                    asyncio.get_event_loop().run_in_executor(
                        None,
                        user_service.record_tokens,
                        req.user_id, in_t, out_t, tot_t,
                    )
            else:
                yield f"data: {json.dumps({'done': True, 'suggest_blackboard': suggest_bb})}\n\n"
```

---

### 2. `api/payments.py`

**Step A — Add import** (at top of file, after other imports):
```python
from app.services import user_service
```

**Step B — Fix `verify_payment()` Step 5** — replace the `users` collection write:

Find this block (step 5 inside `verify_payment()`):
```python
    # 5. Firestore: activate plan — merge=True preserves name/grade/etc.
    # Collection: users/{userId}  ← Android FirestoreManager reads this
    db.collection("users").document(req.user_id).set(
        {
            "planId": req.plan_id,
            "planName": plan_name,
            "updatedAt": now,
        },
        merge=True,
    )
```

Replace it with:
```python
    # 5. Firestore: activate plan in users_table (server-managed collection)
    plan_start_date = now
    plan_expiry_date = (
        now + req.validity_days * 86_400_000   # ms
        if getattr(req, "validity_days", 0) > 0 else 0
    )
    user_service.activate_plan(
        uid=req.user_id,
        plan_id=req.plan_id,
        plan_name=plan_name,
        plan_start_date=plan_start_date,
        plan_expiry_date=plan_expiry_date,
    )
```

Also add `validity_days` to `VerifyPaymentRequest` if not already present:
```python
class VerifyPaymentRequest(BaseModel):
    user_id: str
    school_id: str
    plan_id: str
    razorpay_payment_id: str
    razorpay_order_id: str
    razorpay_signature: str
    validity_days: int = 0   # ADD THIS — sent by Android PaymentApiClient
```

**Step C — Fix `_handle_payment_captured()` webhook reconcile** — replace the `users` collection write:

Find:
```python
        # Activate plan (idempotent merge)
        db.collection("users").document(user_id).set(
            {"planId": plan_id, "planName": plan_name, "updatedAt": now},
            merge=True,
        )
```

Replace with:
```python
        # Activate plan in users_table (idempotent — activate_plan uses merge=True)
        user_service.activate_plan(
            uid=user_id,
            plan_id=plan_id,
            plan_name=plan_name,
            plan_start_date=now,
            plan_expiry_date=0,   # webhook doesn't know validity_days; admin can set later
        )
```

---

### 3. `main.py`

**Step A — Add import** (after the other `from app.api` imports):
```python
from app.api.users import router as users_router
```

**Step B — Register the router** (after the existing `app.include_router(...)` calls):
```python
app.include_router(users_router)
```

---

## Where the Android App Calls These

### User registration
Call `POST /users/register` once after Firebase Auth sign-in if `users_table/{uid}` doesn't exist:

```kotlin
// In AuthRepository or HomeActivity after successful login:
val response = serverApi.registerUser(
    RegisterRequest(
        userId = firebaseUser.uid,
        name   = firebaseUser.displayName ?: "",
        email  = firebaseUser.email ?: "",
        grade  = userGradeFromPrefs,
        schoolId   = schoolId,
        schoolName = schoolName,
    )
)
```

### Token tracking
Happens automatically on the server — no Android changes needed once `user_id` is added to the chat request body:
```kotlin
// In AiClient / ChatRequest to server — add user_id field:
"user_id": firebaseUser.uid,
```

### Plan activation
Happens automatically after payment verification via `POST /payments/razorpay/verify` — no extra call needed.

---

## Field Reference — `users_table/{uid}`

| Field | Who writes | When |
|-------|-----------|------|
| `planId`, `planName` | `activate_plan()` | After payment |
| `plan_start_date`, `plan_expiry_date` | `activate_plan()` | After payment |
| `plan_daily_chat_limit`, `plan_daily_bb_limit` | `activate_plan()` (from `plans/{planId}`) | After payment |
| `plan_tts_enabled`, `plan_ai_tts_enabled`, `plan_blackboard_enabled`, `plan_image_enabled` | `activate_plan()` | After payment |
| `chat_questions_today`, `bb_sessions_today`, `questions_updated_at` | Android client | Each question asked |
| `tokens_today`, `input_tokens_today`, `output_tokens_today` | `record_tokens()` | After each AI response |
| `tokens_this_month`, `input_tokens_this_month`, `output_tokens_this_month` | `record_tokens()` | After each AI response |
| `tokens_updated_at` | `record_tokens()` | After each AI response |

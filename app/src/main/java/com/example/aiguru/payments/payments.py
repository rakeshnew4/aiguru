# payments/razorpay_payments.py
import hashlib
import hmac
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Optional

import razorpay
from fastapi import APIRouter, Depends, HTTPException, Request
from pydantic import BaseModel

from app.core.config import settings   # adjust to your project

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/payments/razorpay", tags=["payments"])


# ── Firebase / Firestore ─────────────────────────────────────────────────────────
try:
    import firebase_admin
    from firebase_admin import credentials, firestore

    if not firebase_admin._apps:
        cred = credentials.Certificate(settings.FIREBASE_SERVICE_ACCOUNT)
        firebase_admin.initialize_app(cred)

    _db = firestore.client()
    _firestore_ok = True
except Exception as _e:
    logger.warning(f"Firestore not configured: {_e}")
    _db = None
    _firestore_ok = False


def get_db():
    if not _firestore_ok or _db is None:
        raise HTTPException(status_code=500, detail="Firestore not configured")
    return _db


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


# ── Razorpay client ──────────────────────────────────────────────────────────────
def get_razorpay_client() -> razorpay.Client:
    if not getattr(settings, "RAZORPAY_KEY_ID", None) or \
       not getattr(settings, "RAZORPAY_KEY_SECRET", None):
        raise HTTPException(status_code=500, detail="Razorpay credentials not configured")
    return razorpay.Client(auth=(settings.RAZORPAY_KEY_ID, settings.RAZORPAY_KEY_SECRET))


# ── Pydantic models ──────────────────────────────────────────────────────────────
# class CreateOrderRequest(BaseModel):
#     user_id: str
#     school_id: str
#     plan_id: str
#     plan_name: str
#     amount_inr: int          # ₹1 = 100 paise
#     currency: str = "INR"
#     description: Optional[str] = None
#     customer_name: Optional[str] = None
#     customer_email: Optional[str] = None
#     customer_phone: Optional[str] = None


class CreateOrderResponse(BaseModel):
    order_id: str
    amount: int
    currency: str
    key_id: str
    prefill_name: str = ""
    prefill_email: str = ""
    prefill_contact: str = ""



class VerifyPaymentResponse(BaseModel):
    verified: bool
    message: str

class CreateOrderRequest(BaseModel):
    user_id: str
    school_id: str
    plan_id: str
    plan_name: str            # e.g. "Basic", "Premium"
    amountInr: int            # rupees — multiply ×100 for Razorpay
    currency: str = "INR"
    # User identity — forwarded to Razorpay prefill and stored for audit
    customer_name: Optional[str] = None
    customer_email: Optional[str] = None
    customer_phone: Optional[str] = None

class VerifyPaymentRequest(BaseModel):
    user_id: str
    school_id: str
    plan_id: str
    razorpay_payment_id: str
    razorpay_order_id: str
    razorpay_signature: str
    # Number of days the plan remains active after purchase (0 = no expiry).
    validity_days: int = 0

# ── Endpoints ────────────────────────────────────────────────────────────────────
@router.post("/create-order", response_model=CreateOrderResponse)
async def create_order(
    req: CreateOrderRequest,
    client: razorpay.Client = Depends(get_razorpay_client),
    db=Depends(get_db),
):
    if req.amountInr <= 0:
        raise HTTPException(status_code=400, detail="Amount must be greater than 0")

    try:
        order = client.order.create({
            "amount": req.amountInr * 100,  # Convert INR to paise

            "currency": req.currency,
            "notes": {
                "user_id": req.user_id,
                "school_id": req.school_id,
                "plan_id": req.plan_id,
                "plan_name": req.plan_name,   # readable name for webhook reconcile
                "customer_name": req.customer_name or "",
                "customer_email": req.customer_email or "",
                "customer_phone": req.customer_phone or "",
            },
        })
    except Exception as e:
        logger.error(f"Razorpay create_order failed: {e}")
        raise HTTPException(status_code=502, detail="Failed to create payment order")

    order_id: str = order["id"]
    now = _now_ms()

    # ── Firestore: record payment intent ─────────────────────────────────────────
    # Collection: payment_intents/{user_id}_{order_id}
    db.collection("payment_intents").document(f"{req.user_id}_{order_id}").set({
        "user_id": req.user_id,
        "school_id": req.school_id,
        "plan_id": req.plan_id,
        "plan_name": req.plan_name,   # stored for verify lookup
        "order_id": order_id,
        "amount_inr": req.amountInr,
        "currency": req.currency,
        "status": "pending",
        "customer_name": req.customer_name or "",
        "customer_email": req.customer_email or "",
        "customer_phone": req.customer_phone or "",
        "created_at": now,
        "updated_at": now,
    })

    return CreateOrderResponse(
        order_id=order_id,
        amount=req.amountInr * 100,   # return paise — Razorpay SDK expects paise
        currency=req.currency,
        key_id=settings.RAZORPAY_KEY_ID,
        prefill_name=req.customer_name or "",
        prefill_email=req.customer_email or "",
        prefill_contact=req.customer_phone or "",
    )


@router.post("/verify", response_model=VerifyPaymentResponse)
async def verify_payment(
    req: VerifyPaymentRequest,
    client: razorpay.Client = Depends(get_razorpay_client),
    db=Depends(get_db),
):
    # 1. Verify HMAC-SHA256 signature (snake_case field names from model)
    payload_str = f"{req.razorpay_order_id}|{req.razorpay_payment_id}"
    expected_sig = hmac.new(
        settings.RAZORPAY_KEY_SECRET.encode("utf-8"),
        payload_str.encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()

    if not hmac.compare_digest(expected_sig, req.razorpay_signature):
        logger.warning(f"Signature mismatch — order={req.razorpay_order_id}")
        raise HTTPException(status_code=400, detail="Invalid payment signature")

    now = _now_ms()

    # 2. Look up plan_name from the payment intent stored during create-order
    plan_name = req.plan_id  # fallback
    try:
        intent_snap = db.collection("payment_intents").document(
            f"{req.user_id}_{req.razorpay_order_id}"
        ).get()
        if intent_snap.exists:
            plan_name = intent_snap.to_dict().get("plan_name", req.plan_id)
    except Exception:
        pass

    # 3. Fetch live payment details from Razorpay for audit
    payment_data: dict = {}
    try:
        payment_data = client.payment.fetch(req.razorpay_payment_id)
    except Exception as e:
        logger.warning(f"Could not fetch payment details: {e}")

    # 4. Firestore: write verified receipt
    # Collection: payment_receipts/{paymentId}_{orderId}
    receipt_doc_id = f"{req.razorpay_payment_id}_{req.razorpay_order_id}"
    db.collection("payment_receipts").document(receipt_doc_id).set({
        "verified": True,
        "userId": req.user_id,
        "schoolId": req.school_id,
        "planId": req.plan_id,
        "planName": plan_name,
        "razorpayPaymentId": req.razorpay_payment_id,
        "razorpayOrderId": req.razorpay_order_id,
        "razorpaySignature": req.razorpay_signature,
        "paymentStatus": payment_data.get("status", "captured"),
        "amountPaise": payment_data.get("amount", 0),
        "currency": payment_data.get("currency", "INR"),
        "method": payment_data.get("method", ""),
        "email": payment_data.get("email", ""),
        "contact": payment_data.get("contact", ""),
        "createdAt": now,
        "updatedAt": now,
    })

    # 5. Firestore: activate plan — merge=True preserves name/grade/etc.
    # Collection: users/{userId}  ← Android FirestoreManager reads this
    plan_start_date = now
    plan_expiry_date = (
        now + req.validity_days * 86_400_000  # ms
        if req.validity_days > 0 else 0
    )
    db.collection("users").document(req.user_id).set(
        {
            "planId": req.plan_id,
            "planName": plan_name,
            "plan_start_date": plan_start_date,
            "plan_expiry_date": plan_expiry_date,
            "updatedAt": now,
        },
        merge=True,
    )

    # 6. Firestore: mark intent completed
    db.collection("payment_intents").document(
        f"{req.user_id}_{req.razorpay_order_id}"
    ).set(
        {"status": "completed", "paymentId": req.razorpay_payment_id, "updatedAt": now},
        merge=True,
    )

    return VerifyPaymentResponse(verified=True, message="Payment verified successfully")


@router.post("/webhook")
async def razorpay_webhook(request: Request, db=Depends(get_db)):
    """Receives async Razorpay events; stores them for audit and reconciles captures."""
    body = await request.body()
    received_sig = request.headers.get("X-Razorpay-Signature", "")

    # Validate webhook secret if configured
    webhook_secret = getattr(settings, "RAZORPAY_WEBHOOK_SECRET", "")
    if webhook_secret:
        expected_sig = hmac.new(
            webhook_secret.encode("utf-8"),
            body,
            hashlib.sha256,
        ).hexdigest()
        if not hmac.compare_digest(expected_sig, received_sig):
            raise HTTPException(status_code=400, detail="Invalid webhook signature")

    try:
        payload = json.loads(body)
    except Exception:
        payload = {}

    # Firestore: store raw event for audit
    # Collection: payment_webhooks/{uuid}
    db.collection("payment_webhooks").document(str(uuid.uuid4())).set({
        "event": payload.get("event", "unknown"),
        "payload": payload,
        "receivedAt": _now_ms(),
    })

    # Reconcile captured payments in case /verify was missed
    if payload.get("event") == "payment.captured":
        _handle_payment_captured(payload, db)

    return {"status": "ok"}


def _handle_payment_captured(payload: dict, db) -> None:
    """Idempotent reconcile: activates plan if /verify hasn't already done it."""
    try:
        entity = payload.get("payload", {}).get("payment", {}).get("entity", {})
        payment_id: str = entity.get("id", "")
        order_id: str = entity.get("order_id", "")
        notes: dict = entity.get("notes", {})
        user_id: str = notes.get("user_id", "")
        plan_id: str = notes.get("plan_id", "")
        plan_name: str = notes.get("plan_name", "")

        if not (user_id and payment_id and order_id and plan_id):
            return

        now = _now_ms()

        # Only act if the intent isn't already marked completed
        intent_ref = db.collection("payment_intents").document(f"{user_id}_{order_id}")
        snap = intent_ref.get()
        if snap.exists and snap.to_dict().get("status") == "completed":
            return  # already handled by /verify

        # Activate plan (idempotent merge)
        db.collection("users").document(user_id).set(
            {"planId": plan_id, "planName": plan_name, "updatedAt": now},
            merge=True,
        )
        intent_ref.set(
            {"status": "completed", "paymentId": payment_id, "updatedAt": now},
            merge=True,
        )
        logger.info(f"Webhook reconciled plan={plan_id} for user={user_id}")
    except Exception as e:
        logger.error(f"Webhook reconcile error: {e}")
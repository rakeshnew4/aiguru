# payments/razorpay_payments.py
import hashlib
import hmac
import json
import logging
import uuid
from datetime import datetime, timezone
from typing import Optional

import razorpay
from fastapi import APIRouter, Depends, HTTPException, Request, status
from pydantic import BaseModel

from app.core.config import settings
from app.core.auth import require_auth, AuthUser
from app.core.firebase_auth import get_firestore_db
from app.services import user_service

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/payments/razorpay", tags=["payments"])


def get_db():
    """Return the shared Firestore client (raises 500 if not configured)."""
    try:
        return get_firestore_db()
    except RuntimeError as exc:
        raise HTTPException(status_code=500, detail=str(exc))


def _now_ms() -> int:
    return int(datetime.now(timezone.utc).timestamp() * 1000)


# ── Razorpay client ──────────────────────────────────────────────────────────────
def get_razorpay_client() -> razorpay.Client:
    key_id = getattr(settings, "RAZORPAY_KEY_ID", "") or ""
    key_secret = getattr(settings, "RAZORPAY_KEY_SECRET", "") or ""
    if not key_id or not key_secret:
        logger.error(
            "Razorpay credentials not configured: "
            "RAZORPAY_KEY_ID=%r, RAZORPAY_KEY_SECRET=%s",
            key_id or "(empty)",
            "(set)" if key_secret else "(empty)",
        )
        raise HTTPException(status_code=500, detail="Razorpay credentials not configured")
    # Log key_id prefix/suffix so you can verify which key is active without exposing it
    masked_key = f"{key_id[:8]}...{key_id[-4:]}" if len(key_id) > 12 else key_id[:4] + "..."
    logger.info("Razorpay client created with key_id=%s", masked_key)
    return razorpay.Client(auth=(key_id, key_secret))


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
    validity_days: int = 0   # sent by Android PaymentApiClient

# ── Endpoints ────────────────────────────────────────────────────────────────────
@router.post("/create-order", response_model=CreateOrderResponse)
async def create_order(
    req: CreateOrderRequest,
    auth: AuthUser = Depends(require_auth),
    client: razorpay.Client = Depends(get_razorpay_client),
    db=Depends(get_db),
):
    # The authenticated user can only create orders for themselves.
    if auth.uid != req.user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only create payment orders for your own account.",
        )
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
    auth: AuthUser = Depends(require_auth),
    client: razorpay.Client = Depends(get_razorpay_client),
    db=Depends(get_db),
):
    # Prevent one user from verifying another user's payment.
    if auth.uid != req.user_id:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="You can only verify payments for your own account.",
        )
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

    # 5. Either grant credits (top-up) OR activate plan (subscription) based on plan_id prefix.
    if req.plan_id.startswith("topup_"):
        # Credit top-up: look up the pack from credit_topups/{plan_id} and grant credits.
        # No plan activation, no expiry date — credits sit in user_credits/{uid}.balance.
        try:
            pack_snap = db.collection("credit_topups").document(req.plan_id).get()
            if pack_snap.exists:
                pack = pack_snap.to_dict() or {}
                grant = int(pack.get("credits", 0)) + int(pack.get("bonus_credits", 0))
                if grant > 0:
                    user_service.grant_topup_credits(
                        uid=req.user_id,
                        amount=grant,
                        pack_id=req.plan_id,
                        pack_name=pack.get("name", req.plan_id),
                    )
            else:
                logger.warning("verify_payment: topup pack %s not found", req.plan_id)
        except Exception as exc:
            logger.error("verify_payment: topup grant failed: %s", exc)
    else:
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

        # Activate plan in users_table (idempotent — activate_plan uses merge=True)
        user_service.activate_plan(
            uid=user_id,
            plan_id=plan_id,
            plan_name=plan_name,
            plan_start_date=now,
            plan_expiry_date=0,   # webhook doesn't know validity_days; admin can set later
        )
        intent_ref.set(
            {"status": "completed", "paymentId": payment_id, "updatedAt": now},
            merge=True,
        )
        logger.info(f"Webhook reconciled plan={plan_id} for user={user_id}")
    except Exception as e:
        logger.error(f"Webhook reconcile error: {e}")
"""
Credits API

Endpoints for querying and spending user credits.
Credits are earned by completing daily questions, plan bonuses, referrals, etc.

Collections:
  user_credits/{uid}           – { balance, lifetime_earned, last_updated }
  credit_transactions          – append-only ledger
"""

from __future__ import annotations

import time
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.core.auth import require_auth, AuthUser
from app.core.firebase_auth import get_firestore_db
from app.core.logger import get_logger
from google.cloud.firestore import Increment

logger = get_logger(__name__)
router = APIRouter(prefix="/credits", tags=["credits"])


def _now_ms() -> int:
    return int(time.time() * 1000)


# ── Models ────────────────────────────────────────────────────────────────────

class SpendCreditsRequest(BaseModel):
    amount: int
    reason: str
    source_id: Optional[str] = ""


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.get("/topup-packs")
async def get_topup_packs(auth: AuthUser = Depends(require_auth)):
    """
    Return active credit top-up packs for purchase.
    These are bought via the existing /payments/razorpay flow with plan_id="topup_<packId>".
    """
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")
    try:
        docs = (
            db.collection("credit_topups")
            .where("active", "==", True)
            .get()
        )
        packs = sorted(
            [{"id": d.id, **(d.to_dict() or {})} for d in docs],
            key=lambda p: p.get("price_inr", 0),
        )
        return {"packs": packs}
    except Exception as exc:
        logger.warning("get_topup_packs: %s", exc)
        raise HTTPException(500, "Failed to fetch top-up packs")


@router.get("/balance")
async def get_balance(auth: AuthUser = Depends(require_auth)):
    """Return current credit balance and lifetime earned for the user."""
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    try:
        doc = db.collection("user_credits").document(auth.uid).get()
        if not doc.exists:
            return {"balance": 0, "lifetime_earned": 0}
        data = doc.to_dict() or {}
        return {
            "balance": data.get("balance", 0),
            "lifetime_earned": data.get("lifetime_earned", 0),
        }
    except Exception as exc:
        logger.warning("get_balance uid=%s: %s", auth.uid, exc)
        raise HTTPException(500, "Failed to fetch balance")


@router.get("/transactions")
async def get_transactions(
    limit: int = 20,
    auth: AuthUser = Depends(require_auth),
):
    """Return recent credit transactions for the user, newest first."""
    if limit > 100:
        limit = 100
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    try:
        docs = (
            db.collection("credit_transactions")
            .where("uid", "==", auth.uid)
            .order_by("created_at", direction="DESCENDING")
            .limit(limit)
            .get()
        )
        return {
            "transactions": [
                {"id": d.id, **{k: v for k, v in (d.to_dict() or {}).items() if k != "uid"}}
                for d in docs
            ]
        }
    except Exception as exc:
        logger.warning("get_transactions uid=%s: %s", auth.uid, exc)
        raise HTTPException(500, "Failed to fetch transactions")


@router.post("/spend")
async def spend_credits(
    req: SpendCreditsRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Deduct credits from user balance. Fails if balance would go negative.
    Used for premium features (e.g. unlocking extra BB sessions, hints).
    """
    if req.amount <= 0:
        raise HTTPException(400, "amount must be positive")

    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    uid = auth.uid
    credits_ref = db.collection("user_credits").document(uid)

    try:
        doc = credits_ref.get()
        current = (doc.to_dict() or {}).get("balance", 0) if doc.exists else 0
    except Exception as exc:
        raise HTTPException(500, f"Failed to read balance: {exc}")

    if current < req.amount:
        raise HTTPException(402, f"Insufficient credits: have {current}, need {req.amount}")

    now = _now_ms()
    try:
        credits_ref.set(
            {"balance": Increment(-req.amount), "last_updated": now},
            merge=True,
        )
        db.collection("credit_transactions").document().set({
            "uid": uid,
            "amount": -req.amount,
            "type": "spend",
            "source_id": req.source_id or "",
            "description": req.reason,
            "created_at": now,
        })
    except Exception as exc:
        logger.warning("spend_credits uid=%s: %s", uid, exc)
        raise HTTPException(500, "Failed to spend credits")

    return {
        "ok": True,
        "debited": req.amount,
        "balance_after": current - req.amount,
    }


@router.get("/quota-status")
async def quota_status(auth: AuthUser = Depends(require_auth)):
    """
    Single call that returns everything the home screen needs to display quota.
    Also proactively resets free_bb_remaining / free_chat_remaining in Firestore
    when a UTC day rollover is detected (so the first app open of the day is accurate
    even before any session starts).

    Response fields:
      free_bb_remaining   : BB sessions left today (authoritative remaining count)
      free_bb_limit       : daily BB session allowance (0 = unlimited)
      free_chat_remaining : chat questions left today
      free_chat_limit     : daily chat question allowance (0 = unlimited)
      credit_balance      : current credit balance (1 credit = 100 tokens)
      is_new_day          : whether quota counters just reset (UTC rollover)
    """
    from datetime import datetime, timezone as _tz
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    uid = auth.uid
    try:
        user_ref = db.collection("users_table").document(uid)
        credit_ref = db.collection("user_credits").document(uid)
        user_doc, credit_doc = user_ref.get(), credit_ref.get()
    except Exception as exc:
        logger.warning("quota_status uid=%s: %s", uid, exc)
        raise HTTPException(500, "Failed to fetch quota status")

    user_data   = user_doc.to_dict()   if user_doc.exists   else {}
    credit_data = credit_doc.to_dict() if credit_doc.exists else {}

    bb_limit   = int(user_data.get("plan_daily_bb_limit")   or 2)
    chat_limit = int(user_data.get("plan_daily_chat_limit") or 12)
    balance    = int(credit_data.get("balance") or 0)

    # Add active referral bonus to BB limit
    _now = _now_ms()
    bonus_expiry = int(user_data.get("referral_bb_bonus_expiry_at") or 0)
    if bonus_expiry > _now:
        bb_limit += int(user_data.get("referral_bb_bonus_per_day") or 0)

    # Detect UTC day rollover
    questions_updated_at = int(user_data.get("questions_updated_at") or 0)
    is_new_day = False
    if questions_updated_at > 0:
        try:
            last_day = datetime.fromtimestamp(questions_updated_at / 1000, tz=_tz.utc).date()
            is_new_day = last_day < datetime.now(tz=_tz.utc).date()
        except Exception:
            pass

    if is_new_day:
        # Proactively reset remaining counts so the app sees fresh values immediately
        try:
            user_ref.update({
                "free_bb_remaining":   bb_limit,
                "free_chat_remaining": chat_limit,
                "bb_sessions_today":   0,
                "chat_questions_today": 0,
                "questions_updated_at": _now_ms(),
            })
        except Exception as exc:
            logger.warning("quota_status: day-reset write failed uid=%s: %s", uid, exc)
        bb_remaining   = bb_limit
        chat_remaining = chat_limit
    else:
        # Read explicit remaining fields; fall back to computing from used counter
        raw_bb   = user_data.get("free_bb_remaining")
        raw_chat = user_data.get("free_chat_remaining")
        bb_today   = int(user_data.get("bb_sessions_today")   or 0)
        chat_today = int(user_data.get("chat_questions_today") or 0)
        bb_remaining   = int(raw_bb)   if raw_bb   is not None else max(0, bb_limit   - bb_today)
        chat_remaining = int(raw_chat) if raw_chat is not None else max(0, chat_limit - chat_today)

    return {
        "free_bb_remaining":   bb_remaining,
        "free_bb_limit":       bb_limit,
        "free_chat_remaining": chat_remaining,
        "free_chat_limit":     chat_limit,
        "credit_balance":      balance,
        "is_new_day":          is_new_day,
    }

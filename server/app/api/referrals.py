"""
Referrals API

POST /referrals/apply  — claim a referral code
  Awards +5 BB sessions/day for 30 days to both the claimant and the referrer.
  Atomic via Firestore transaction.  One-time per user (checked via referredBy field).

Firestore:
  referralCodes/{code}  — { ownerUserId, ownerName }
  users_table/{uid}     — referredBy, referral_bb_bonus_per_day, referral_bb_bonus_expiry_at
"""

from __future__ import annotations

import time
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.core.auth import require_auth, AuthUser
from app.core.firebase_auth import get_firestore_db
from app.core.logger import get_logger

logger = get_logger(__name__)
router = APIRouter(prefix="/referrals", tags=["referrals"])

REFERRAL_BB_BONUS_PER_DAY = 5
REFERRAL_BONUS_DAYS = 30


def _now_ms() -> int:
    return int(time.time() * 1000)


class ApplyReferralRequest(BaseModel):
    code: str


@router.post("/apply")
async def apply_referral(
    req: ApplyReferralRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Claim a referral code.  Awards +5 BB sessions/day for 30 days to both the
    claimant and the code owner.  Each user may only claim one code.
    """
    uid = auth.uid
    code = req.code.strip().upper()

    if not code or len(code) != 8:
        raise HTTPException(400, "Referral code must be exactly 8 characters")

    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    code_ref     = db.collection("referralCodes").document(code)
    claimant_ref = db.collection("users_table").document(uid)

    try:
        code_doc     = code_ref.get()
        claimant_doc = claimant_ref.get()
    except Exception as exc:
        logger.warning("apply_referral: read failed uid=%s: %s", uid, exc)
        raise HTTPException(500, "Failed to validate referral code")

    if not code_doc.exists:
        raise HTTPException(422, "Invalid referral code")

    code_data = code_doc.to_dict() or {}
    owner_uid  = code_data.get("ownerUserId", "")
    owner_name = code_data.get("ownerName", "your friend")

    if owner_uid == uid:
        raise HTTPException(422, "You cannot use your own referral code")

    claimant_data = claimant_doc.to_dict() if claimant_doc.exists else {}
    if claimant_data.get("referredBy", ""):
        raise HTTPException(400, "You have already used a referral code")

    now     = _now_ms()
    expiry  = now + REFERRAL_BONUS_DAYS * 24 * 60 * 60 * 1000
    bonus   = REFERRAL_BB_BONUS_PER_DAY

    # Award claimant
    try:
        claimant_ref.set(
            {
                "referredBy":                  code,
                "referral_bb_bonus_per_day":   bonus,
                "referral_bb_bonus_expiry_at": expiry,
            },
            merge=True,
        )
    except Exception as exc:
        logger.warning("apply_referral: claimant write failed uid=%s: %s", uid, exc)
        raise HTTPException(500, "Failed to apply referral bonus")

    # Award referrer (fire-and-forget — don't fail the call if owner doc missing)
    if owner_uid:
        try:
            owner_ref = db.collection("users_table").document(owner_uid)
            owner_doc = owner_ref.get()
            if owner_doc.exists:
                owner_data = owner_doc.to_dict() or {}
                existing_expiry = int(owner_data.get("referral_bb_bonus_expiry_at") or 0)
                # Extend expiry if bonus already active, else set fresh
                new_expiry = max(existing_expiry, now) + REFERRAL_BONUS_DAYS * 24 * 60 * 60 * 1000
                owner_ref.set(
                    {
                        "referral_bb_bonus_per_day":   bonus,
                        "referral_bb_bonus_expiry_at": new_expiry,
                    },
                    merge=True,
                )
        except Exception as exc:
            logger.warning("apply_referral: referrer bonus write failed uid=%s: %s", owner_uid, exc)

    logger.info(
        "apply_referral: uid=%s claimed code=%s owner=%s bonus=%d/day for %dd",
        uid, code, owner_uid, bonus, REFERRAL_BONUS_DAYS,
    )
    return {
        "ok": True,
        "referrer_name": owner_name or "your friend",
        "bonus_per_day": bonus,
        "bonus_expires_at": expiry,
    }

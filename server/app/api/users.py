"""
api/users.py — User registration and quota endpoints.

POST /users/register
  Idempotent: safe to call on every login.
  Creates users_table/{uid} with free-plan defaults if it doesn't exist yet.
  Also creates a LiteLLM API key for per-user usage tracking if LiteLLM is enabled.

GET /users/quota
  Returns the current daily quota usage and limits for the authenticated user.
  Android calls this after each response to refresh the quota display without
  writing to Firestore directly.
"""

from __future__ import annotations

import asyncio
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.services import user_service, litellm_service
from app.core.logger import get_logger
from app.core.config import settings
from app.core.auth import require_auth, AuthUser

logger = get_logger(__name__)
router = APIRouter(prefix="/users", tags=["users"])


class RegisterRequest(BaseModel):
    userId: str
    name: Optional[str] = ""
    email: Optional[str] = ""
    grade: Optional[str] = ""
    schoolId: Optional[str] = ""
    schoolName: Optional[str] = ""


class RegisterResponse(BaseModel):
    success: bool
    message: str
    litellm_key: Optional[str] = None  # New user's LiteLLM API key


@router.post("/register", response_model=RegisterResponse)
async def register_user(req: RegisterRequest, auth: AuthUser = Depends(require_auth)):
    """
    Create users_table/{uid} on first sign-up + LiteLLM API key for usage tracking.

    Safe to call on every login — if the Firestore document already exists this is a no-op.
    The Android app should call this once after Firebase Auth sign-in completes.
    
    On success, returns the user's LiteLLM API key (if LiteLLM is enabled).
    Android app should store this key and pass it in chat requests for usage tracking.
    """
    if not req.userId or req.userId == "guest_user":
        raise HTTPException(status_code=400, detail="Valid userId is required")

    # The authenticated token UID must match the userId being registered.
    # This prevents one user from creating/overwriting another user's document.
    # Skip this check in development mode (AUTH_REQUIRED=False)
    if settings.AUTH_REQUIRED and auth.uid != req.userId:
        raise HTTPException(
            status_code=403,
            detail="Token UID does not match the userId in the request.",
        )

    # Create user in Firestore (sync via executor)
    loop = asyncio.get_event_loop()
    try:
        is_new_user = await loop.run_in_executor(
            None,
            user_service.create_user_if_missing,
            req.userId,
            req.name or "",
            req.email or "",
            req.grade or "",
            req.schoolId or "",
            req.schoolName or "",
        )
    except Exception as exc:
        logger.error("register_user: failed to create Firestore user uid=%s: %s", req.userId, exc)
        raise HTTPException(status_code=500, detail="Failed to register user in Firestore")

    # For brand-new users: copy onboarding sample BB sessions (fire-and-forget)
    if is_new_user:
        loop.run_in_executor(None, user_service.copy_samples_to_user, req.userId)

    # Create LiteLLM API key if enabled
    litellm_key = None
    if settings.USE_LITELLM_PROXY:
        try:
            litellm_key = await litellm_service.create_user_api_key(
                req.userId,
                {"name": req.name, "email": req.email, "grade": req.grade}
            )
            if litellm_key:
                logger.info("register_user: created LiteLLM key for uid=%s", req.userId)
                # Store key in Firestore so get_or_create_litellm_key finds it without an extra API call
                try:
                    db_store = user_service._get_db()
                    if db_store:
                        db_store.collection("users_table").document(req.userId).set(
                            {"litellm_key": litellm_key}, merge=True
                        )
                except Exception as store_exc:
                    logger.warning("register_user: could not persist litellm_key uid=%s: %s", req.userId, store_exc)
            else:
                logger.warning("register_user: failed to create LiteLLM key for uid=%s", req.userId)
        except Exception as exc:
            logger.error("register_user: LiteLLM key creation failed for uid=%s: %s", req.userId, exc)
            # Continue gracefully — Firestore user still created

    # Log registration / login event (fire-and-forget)
    loop.run_in_executor(
        None,
        user_service.log_activity,
        "register",
        req.userId,
        req.name or "",
        req.email or "",
        {"grade": req.grade or "", "school": req.schoolName or ""},
    )

    return RegisterResponse(
        success=True,
        message="User registered",
        litellm_key=litellm_key
    )


class QuotaResponse(BaseModel):
    chat_questions_today: int
    bb_sessions_today: int
    plan_daily_chat_limit: int
    plan_daily_bb_limit: int
    chat_questions_left: int
    bb_sessions_left: int
    questions_updated_at: int
    plan_id: str
    plan_name: str
    plan_expiry_date: int


@router.get("/quota", response_model=QuotaResponse)
async def get_user_quota(auth: AuthUser = Depends(require_auth)):
    """
    Return the caller's live daily quota counters and plan limits.

    Android uses this after each AI response to refresh the quota strip display,
    replacing direct Firestore reads for quota state (all writes are server-side).
    The server is the sole authority on chat_questions_today / bb_sessions_today.
    """
    uid = auth.uid
    if not uid or uid == "guest_user":
        raise HTTPException(status_code=400, detail="Valid authenticated user required")

    loop = asyncio.get_event_loop()

    def _read_quota():
        from datetime import datetime, timezone
        db = user_service._get_db()
        if db is None:
            return None
        ref = db.collection("users_table").document(uid)
        doc = ref.get()
        if not doc.exists:
            return None
        data = doc.to_dict() or {}

        questions_updated_at = int(data.get("questions_updated_at") or 0)
        is_new_day = False
        if questions_updated_at > 0:
            try:
                import time as _time
                last_day = datetime.fromtimestamp(questions_updated_at / 1000, tz=timezone.utc).date()
                today = datetime.now(tz=timezone.utc).date()
                is_new_day = last_day < today
            except Exception:
                pass

        chat_today = 0 if is_new_day else int(data.get("chat_questions_today") or 0)
        bb_today   = 0 if is_new_day else int(data.get("bb_sessions_today") or 0)

        plan_id   = (data.get("planId") or "free").strip() or "free"
        plan_name = data.get("planName") or "Free"
        plan_expiry = int(data.get("plan_expiry_date") or 0)

        # Read limits from the user doc (written by server on plan activation)
        chat_limit = int(data.get("plan_daily_chat_limit") or 12)
        bb_limit   = int(data.get("plan_daily_bb_limit") or 2)

        # Revert to free defaults if plan expired
        import time as _t
        if plan_expiry > 0 and plan_expiry < int(_t.time() * 1000):
            chat_limit = 12
            bb_limit   = 2

        chat_left = max(0, chat_limit - chat_today) if chat_limit > 0 else -1
        bb_left   = max(0, bb_limit   - bb_today)   if bb_limit   > 0 else -1

        return {
            "chat_questions_today": chat_today,
            "bb_sessions_today": bb_today,
            "plan_daily_chat_limit": chat_limit,
            "plan_daily_bb_limit": bb_limit,
            "chat_questions_left": chat_left,
            "bb_sessions_left": bb_left,
            "questions_updated_at": questions_updated_at,
            "plan_id": plan_id,
            "plan_name": plan_name,
            "plan_expiry_date": plan_expiry,
        }

    result = await loop.run_in_executor(None, _read_quota)
    if result is None:
        raise HTTPException(status_code=404, detail="User quota data not found")
    return QuotaResponse(**result)


# ─────────────────────────────────────────────────────────────────────────────
# GET /users/quota/status  — Enhanced quota status (no consumption)
# ─────────────────────────────────────────────────────────────────────────────

class QuotaStatusResponse(BaseModel):
    # Free daily remaining
    free_chat_remaining: int
    free_bb_remaining: int
    free_tts_chars_remaining: int

    # Purchased credit balance
    credit_balance: int

    # Per-type mode: "free" | "ai_credit" | "blocked"
    chat_mode: str
    bb_mode: str
    tts_mode: str

    # UI helpers
    using_ai_credits_for_tts: bool   # show banner in BB / chat
    plan_id: str
    plan_name: str
    plan_expiry_date: int
    maintenance_mode: bool
    maintenance_message: str


@router.get("/quota/status", response_model=QuotaStatusResponse)
async def get_quota_status(auth: AuthUser = Depends(require_auth)):
    """
    Returns live quota status for ALL credit types — no deduction performed.

    Priority model:
      1. free daily quota (free_chat_remaining / free_bb_remaining / TTS chars)
      2. AI credits (user_credits balance; 1 credit = 100 tokens)
      3. blocked

    Android calls this before every AI request to determine which mode will be
    used, so it can show the appropriate UI (e.g. "Using AI Credits for TTS").
    No caching on client side — always server-authoritative.
    """
    uid = auth.uid
    if not uid or uid == "guest_user":
        raise HTTPException(status_code=400, detail="Valid authenticated user required")

    loop = asyncio.get_event_loop()

    def _read_status():
        from datetime import datetime, timezone
        import time as _time

        db = user_service._get_db()
        if db is None:
            return None

        # Read user doc and credit doc in parallel via separate gets
        ref = db.collection("users_table").document(uid)
        credit_ref = db.collection("user_credits").document(uid)

        doc = ref.get()
        credit_doc = credit_ref.get()

        if not doc.exists:
            return None

        data = doc.to_dict() or {}
        now_ms = int(_time.time() * 1000)

        # ── Detect UTC day rollover ────────────────────────────────────────
        questions_updated_at = int(data.get("questions_updated_at") or 0)
        is_new_day = False
        if questions_updated_at > 0:
            try:
                last_day = datetime.fromtimestamp(questions_updated_at / 1000, tz=timezone.utc).date()
                today = datetime.now(tz=timezone.utc).date()
                is_new_day = last_day < today
            except Exception:
                pass

        # ── Plan / expiry ──────────────────────────────────────────────────
        plan_id     = (data.get("planId") or "free").strip() or "free"
        plan_name   = data.get("planName") or "Free"
        plan_expiry = int(data.get("plan_expiry_date") or 0)
        plan_expired = plan_expiry > 0 and plan_expiry < now_ms

        # Revert to free-tier limits if plan expired
        if plan_expired:
            chat_limit = 12
            bb_limit   = 2
        else:
            chat_limit = int(data.get("plan_daily_chat_limit") or 12)
            bb_limit   = int(data.get("plan_daily_bb_limit") or 2)

        # ── Free remaining ─────────────────────────────────────────────────
        if is_new_day:
            free_chat = chat_limit
            free_bb   = bb_limit
        else:
            raw_chat = data.get("free_chat_remaining")
            raw_bb   = data.get("free_bb_remaining")
            used_chat = int(data.get("chat_questions_today") or 0)
            used_bb   = int(data.get("bb_sessions_today") or 0)
            # backfill for old accounts without the *_remaining fields
            free_chat = int(raw_chat) if raw_chat is not None else max(0, chat_limit - used_chat)
            free_bb   = int(raw_bb)   if raw_bb   is not None else max(0, bb_limit   - used_bb)

        # ── TTS free chars ─────────────────────────────────────────────────
        tts_updated_at = int(data.get("tts_updated_at") or 0)
        tts_new_day = False
        if tts_updated_at > 0:
            try:
                last_tts_day = datetime.fromtimestamp(tts_updated_at / 1000, tz=timezone.utc).date()
                tts_new_day = last_tts_day < datetime.now(tz=timezone.utc).date()
            except Exception:
                pass

        FREE_TTS_CHARS_DAILY = 2000   # Free plan daily TTS chars
        tts_chars_today = 0 if tts_new_day else int(data.get("tts_chars_today") or 0)
        free_tts_remaining = max(0, FREE_TTS_CHARS_DAILY - tts_chars_today)

        # ── Credit balance ─────────────────────────────────────────────────
        credit_balance = 0
        try:
            if credit_doc.exists:
                credit_balance = int((credit_doc.to_dict() or {}).get("balance", 0))
        except Exception:
            pass

        # ── Compute mode per type ──────────────────────────────────────────
        def _mode(free_left: int) -> str:
            if free_left > 0:
                return "free"
            return "ai_credit" if credit_balance > 0 else "blocked"

        chat_mode = _mode(free_chat)
        bb_mode   = _mode(free_bb)
        tts_mode  = _mode(free_tts_remaining)

        # ── Admin maintenance ──────────────────────────────────────────────
        maintenance = False
        maintenance_msg = ""
        try:
            admin_doc = db.collection("admin_config").document("global").get()
            if admin_doc.exists:
                admin_data = admin_doc.to_dict() or {}
                maintenance = bool(admin_data.get("maintenance_mode", False))
                maintenance_msg = str(admin_data.get("maintenance_message", ""))
        except Exception:
            pass

        return {
            "free_chat_remaining":      max(0, free_chat),
            "free_bb_remaining":        max(0, free_bb),
            "free_tts_chars_remaining": free_tts_remaining,
            "credit_balance":           credit_balance,
            "chat_mode":                chat_mode,
            "bb_mode":                  bb_mode,
            "tts_mode":                 tts_mode,
            "using_ai_credits_for_tts": tts_mode == "ai_credit",
            "plan_id":                  plan_id,
            "plan_name":                plan_name,
            "plan_expiry_date":         plan_expiry,
            "maintenance_mode":         maintenance,
            "maintenance_message":      maintenance_msg,
        }

    result = await loop.run_in_executor(None, _read_status)
    if result is None:
        raise HTTPException(status_code=404, detail="User not found")
    return QuotaStatusResponse(**result)

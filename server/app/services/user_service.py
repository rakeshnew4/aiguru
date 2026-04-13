"""
User lifecycle service – manages users_table/{uid} in Firestore.

Three public functions:
  create_user_if_missing()  – call on first sign-up / login
  activate_plan()           – call after payment verification
  record_tokens()           – call after each AI response

All functions are synchronous (firebase-admin SDK) and can be safely
run inside asyncio via loop.run_in_executor(None, fn, *args).

Firestore collection: users_table/{uid}
  Written exclusively by this server (service account).
  Android client may only read, and may update the three daily-quota
  counter fields (chat_questions_today, bb_sessions_today, questions_updated_at).
"""

from __future__ import annotations

import time
from datetime import datetime, timezone

from google.cloud.firestore import Increment

from app.core.logger import get_logger

logger = get_logger(__name__)


# ── Firestore client ──────────────────────────────────────────────────────────
# Reuses the firebase_admin app initialised by payments.py (or main.py).
# firebase_admin.initialize_app() must have been called before any function
# here is invoked.

def _get_db():
    try:
        import firebase_admin  # noqa: PLC0415
        from firebase_admin import firestore as admin_fs  # noqa: PLC0415
        if not firebase_admin._apps:
            raise RuntimeError("Firebase Admin not initialised")
        return admin_fs.client()
    except Exception as exc:
        logger.error("user_service: Firestore unavailable: %s", exc)
        return None


def _now_ms() -> int:
    return int(time.time() * 1000)


# ── Internal helpers ──────────────────────────────────────────────────────────

def _lookup_plan_limits(db, plan_id: str) -> dict:
    """Return the 'limits' sub-dict from plans/{plan_id}, or {} on failure."""
    try:
        doc = db.collection("plans").document(plan_id).get()
        if doc.exists:
            return (doc.to_dict() or {}).get("limits", {})
    except Exception as exc:
        logger.warning("_lookup_plan_limits: could not fetch plan %s: %s", plan_id, exc)
    return {}


# ── Public API ────────────────────────────────────────────────────────────────

def create_user_if_missing(
    uid: str,
    name: str = "",
    email: str = "",
    grade: str = "",
    school_id: str = "",
    school_name: str = "",
) -> None:
    """
    Create users_table/{uid} with free-plan defaults on first sign-up.
    Idempotent – no-op if the document already exists.

    Call this immediately after Firebase Auth registration completes.
    """
    if not uid or uid == "guest_user":
        return

    db = _get_db()
    if db is None:
        return

    ref = db.collection("users_table").document(uid)
    if ref.get().exists:
        logger.info("create_user_if_missing: users_table/%s already exists, skipping", uid)
        return

    now = _now_ms()
    identity = {
        "userId": uid,
        "name": name,
        "email": email,
        "grade": grade,
        "schoolId": school_id,
        "schoolName": school_name,
        "created_at": now,
        "updated_at": now,
    }

    ref.set({
        **identity,
        # ── Plan (free defaults) ─────────────────────────────────────────────
        "planId": "free",
        "planName": "Free",
        "plan_start_date": now,
        "plan_expiry_date": 0,          # 0 = never expires
        "plan_daily_chat_limit": 12,
        "plan_daily_bb_limit": 2,
        "plan_tts_enabled": True,
        "plan_ai_tts_enabled": False,
        "plan_blackboard_enabled": True,
        "plan_image_enabled": False,
        # ── Daily question counters (Android client increments these) ─────────
        "chat_questions_today": 0,
        "bb_sessions_today": 0,
        "questions_updated_at": now,
        # ── Token counters (server increments these) ─────────────────────────
        "tokens_today": 0,
        "input_tokens_today": 0,
        "output_tokens_today": 0,
        "tokens_this_month": 0,
        "input_tokens_this_month": 0,
        "output_tokens_this_month": 0,
        "tokens_updated_at": now,
        # ── Referral ─────────────────────────────────────────────────────────
        "referredBy": "",
        "bonus_questions_today": 0,
    })
    logger.info("create_user_if_missing: created users_table/%s", uid)

    # Mirror identity fields to /users/{uid} so it exists for conversation
    # subcollections and any legacy reads.
    db.collection("users").document(uid).set(identity, merge=True)
    logger.info("create_user_if_missing: mirrored identity to users/%s", uid)


def activate_plan(
    uid: str,
    plan_id: str,
    plan_name: str,
    plan_start_date: int,
    plan_expiry_date: int,
) -> None:
    """
    Write plan activation fields to users_table/{uid} after payment verification.

    Looks up plans/{plan_id} in Firestore to get feature limits automatically.
    Resets daily question counters so the user gets a fresh daily quota immediately.
    Safe to call multiple times (merge=True keeps all other fields intact).
    """
    if not uid:
        return

    db = _get_db()
    if db is None:
        return

    limits = _lookup_plan_limits(db, plan_id)
    now = _now_ms()

    db.collection("users_table").document(uid).set({
        # ── Plan identity ─────────────────────────────────────────────────────
        "planId": plan_id,
        "planName": plan_name,
        "plan_start_date": plan_start_date,
        "plan_expiry_date": plan_expiry_date,
        # ── Flat limit fields (read by Android UserMetadata) ──────────────────
        # 0 = unlimited.  Falls back to admin_config/global defaults if 0.
        "plan_daily_chat_limit": limits.get("daily_chat_questions", 0),
        "plan_daily_bb_limit": limits.get("daily_bb_sessions", 0),
        "plan_tts_enabled": limits.get("tts_enabled", True),
        "plan_ai_tts_enabled": limits.get("ai_tts_enabled", False),
        "plan_blackboard_enabled": limits.get("blackboard_enabled", True),
        "plan_image_enabled": limits.get("image_upload_enabled", False),
        # ── Reset daily question counters on plan change ──────────────────────
        "chat_questions_today": 0,
        "bb_sessions_today": 0,
        "questions_updated_at": now,
        "updated_at": now,
    }, merge=True)
    logger.info(
        "activate_plan: uid=%s plan_id=%s expiry=%d limits=%s",
        uid, plan_id, plan_expiry_date, limits,
    )


def record_tokens(
    uid: str,
    input_tokens: int,
    output_tokens: int,
    total_tokens: int,
) -> None:
    """
    Increment token usage counters in users_table/{uid} after each AI response.

    Handles UTC-day rollover: if tokens_updated_at is from a previous day the
    daily counters are reset to the current LLM call's values rather than
    being incremented.  Monthly counters always increment.
    """
    if not uid or uid == "guest_user" or total_tokens <= 0:
        return

    db = _get_db()
    if db is None:
        return

    now = _now_ms()
    ref = db.collection("users_table").document(uid)

    # Determine whether we need a day rollover
    needs_day_reset = False
    try:
        doc = ref.get()
        if doc.exists:
            data = doc.to_dict() or {}
            last_ms = data.get("tokens_updated_at", 0)
            if last_ms > 0:
                last_day = datetime.fromtimestamp(last_ms / 1000, tz=timezone.utc).date()
                today = datetime.now(tz=timezone.utc).date()
                needs_day_reset = last_day < today
    except Exception as exc:
        logger.warning("record_tokens: read failed for uid=%s: %s", uid, exc)

    if needs_day_reset:
        # New UTC day — replace today's counters, accumulate monthly
        ref.set({
            "tokens_today": total_tokens,
            "input_tokens_today": input_tokens,
            "output_tokens_today": output_tokens,
            "tokens_this_month": Increment(total_tokens),
            "input_tokens_this_month": Increment(input_tokens),
            "output_tokens_this_month": Increment(output_tokens),
            "tokens_updated_at": now,
            "updated_at": now,
        }, merge=True)
    else:
        # Same day — increment both daily and monthly
        ref.set({
            "tokens_today": Increment(total_tokens),
            "input_tokens_today": Increment(input_tokens),
            "output_tokens_today": Increment(output_tokens),
            "tokens_this_month": Increment(total_tokens),
            "input_tokens_this_month": Increment(input_tokens),
            "output_tokens_this_month": Increment(output_tokens),
            "tokens_updated_at": now,
            "updated_at": now,
        }, merge=True)

    logger.debug(
        "record_tokens: uid=%s in=%d out=%d total=%d day_reset=%s",
        uid, input_tokens, output_tokens, total_tokens, needs_day_reset,
    )


# ── Activity Logging ──────────────────────────────────────────────────────────

def log_activity(
    event_type: str,
    uid: str = "",
    name: str = "",
    email: str = "",
    extra: dict = None,
) -> None:
    """
    Write a single activity log entry to Firestore collection 'activity_logs'.

    Entries are auto-ID documents with:
      event_type : str  — e.g. "login", "chat", "register", "quiz", "payment"
      uid        : str  — Firebase UID (may be empty for anonymous events)
      name       : str  — Display name (best-effort, may be empty)
      email      : str  — Email (best-effort, may be empty)
      timestamp  : int  — Unix millis
      ts_iso     : str  — ISO-8601 human-readable UTC timestamp
      ...extra fields passed in the extra dict

    Always fire-and-forget (never raises).
    """
    try:
        db = _get_db()
        if db is None:
            return
        now = _now_ms()
        doc = {
            "event_type": event_type,
            "uid": uid or "",
            "name": name or "",
            "email": email or "",
            "timestamp": now,
            "ts_iso": datetime.now(tz=timezone.utc).strftime("%Y-%m-%d %H:%M:%S UTC"),
        }
        if extra:
            doc.update(extra)
        db.collection("activity_logs").add(doc)
        logger.debug("log_activity: event=%s uid=%s", event_type, uid)
    except Exception as exc:
        logger.warning("log_activity: failed to write event=%s: %s", event_type, exc)

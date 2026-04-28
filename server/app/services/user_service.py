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
) -> bool:
    """
    Create users_table/{uid} with free-plan defaults on first sign-up.
    Idempotent – no-op if the document already exists.

    Returns True if a new user was created, False if already existed.
    """
    if not uid or uid == "guest_user":
        return False

    db = _get_db()
    if db is None:
        return False

    ref = db.collection("users_table").document(uid)
    if ref.get().exists:
        logger.info("create_user_if_missing: users_table/%s already exists, skipping", uid)
        return False

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
        # ── Free session remaining counts (server manages; resets daily) ──────
        # These are the authoritative "how many free sessions left today" fields.
        # Decremented on each free session; reset to daily limit at UTC midnight.
        "free_bb_remaining": 2,
        "free_chat_remaining": 12,
        # ── Token counters (server increments these) ─────────────────────────
        "tokens_today": 0,
        "input_tokens_today": 0,
        "output_tokens_today": 0,
        "tokens_this_month": 0,
        "input_tokens_this_month": 0,
        "output_tokens_this_month": 0,
        "tokens_lifetime": 0,
        "tokens_updated_at": now,
        # ── TTS counters ──────────────────────────────────────────────────────
        "tts_chars_today": 0,
        "tts_chars_this_month": 0,
        "tts_chars_lifetime": 0,
        "tts_updated_at": now,
        # ── Referral ─────────────────────────────────────────────────────────
        "referredBy": "",
        "bonus_questions_today": 0,
    })
    logger.info("create_user_if_missing: created users_table/%s", uid)

    # Initialize credit balance doc
    init_user_credits(db, uid)

    # Mirror identity fields to /users/{uid} so it exists for conversation
    # subcollections and any legacy reads.
    db.collection("users").document(uid).set(identity, merge=True)
    logger.info("create_user_if_missing: mirrored identity to users/%s", uid)
    return True


def copy_samples_to_user(uid: str) -> None:
    """
    Copy global bb_samples to the new user's saved_bb_sessions_flat subcollection.
    Called once on first registration (fire-and-forget).
    """
    if not uid or uid == "guest_user":
        return
    db = _get_db()
    if db is None:
        return
    try:
        samples_ref = db.collection("bb_samples")
        samples = samples_ref.stream()
        batch = db.batch()
        count = 0
        for doc in samples:
            data = doc.to_dict()
            if not data:
                continue
            target = (
                db.collection("users").document(uid)
                .collection("saved_bb_sessions_flat").document(doc.id)
            )
            batch.set(target, {**data, "is_sample": True, "uid": uid})
            count += 1
            if count >= 10:  # safety cap
                break
        if count:
            batch.commit()
            logger.info("copy_samples_to_user: copied %d samples to uid=%s", count, uid)
    except Exception as exc:
        logger.warning("copy_samples_to_user: failed for uid=%s: %s", uid, exc)


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

    # Award activation credits from plan definition
    activation_credits = int(limits.get("credits_on_activation", 0))
    if activation_credits > 0:
        _award_activation_credits(db, uid, activation_credits, plan_id, plan_name)

    logger.info(
        "activate_plan: uid=%s plan_id=%s expiry=%d limits=%s credits=%d",
        uid, plan_id, plan_expiry_date, limits, activation_credits,
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
        # New UTC day — replace daily counters, accumulate monthly + lifetime
        ref.set({
            "tokens_today": total_tokens,
            "input_tokens_today": input_tokens,
            "output_tokens_today": output_tokens,
            "tokens_this_month": Increment(total_tokens),
            "input_tokens_this_month": Increment(input_tokens),
            "output_tokens_this_month": Increment(output_tokens),
            "tokens_lifetime": Increment(total_tokens),
            "tokens_updated_at": now,
            "updated_at": now,
        }, merge=True)
    else:
        # Same day — increment daily, monthly, and lifetime
        ref.set({
            "tokens_today": Increment(total_tokens),
            "input_tokens_today": Increment(input_tokens),
            "output_tokens_today": Increment(output_tokens),
            "tokens_this_month": Increment(total_tokens),
            "input_tokens_this_month": Increment(input_tokens),
            "output_tokens_this_month": Increment(output_tokens),
            "tokens_lifetime": Increment(total_tokens),
            "tokens_updated_at": now,
            "updated_at": now,
        }, merge=True)

    logger.debug(
        "record_tokens: uid=%s in=%d out=%d total=%d day_reset=%s",
        uid, input_tokens, output_tokens, total_tokens, needs_day_reset,
    )

    # Charge 1 credit per 100 tokens consumed (fire-and-forget)
    credits_charged = total_tokens // 100
    if credits_charged > 0:
        _charge_credits_from_usage(db, uid, credits_charged, "token_usage", total_tokens)


def record_tts_chars(uid: str, char_count: int) -> None:
    """
    Track TTS character usage and award 1 credit per 100 chars synthesized.
    Mirrors the day-rollover logic of record_tokens().
    """
    if not uid or uid == "guest_user" or char_count <= 0:
        return

    db = _get_db()
    if db is None:
        return

    now = _now_ms()
    ref = db.collection("users_table").document(uid)

    needs_day_reset = False
    try:
        doc = ref.get()
        if doc.exists:
            data = doc.to_dict() or {}
            last_ms = data.get("tts_updated_at", 0)
            if last_ms > 0:
                last_day = datetime.fromtimestamp(last_ms / 1000, tz=timezone.utc).date()
                today_date = datetime.now(tz=timezone.utc).date()
                needs_day_reset = last_day < today_date
    except Exception as exc:
        logger.warning("record_tts_chars: read failed uid=%s: %s", uid, exc)

    if needs_day_reset:
        ref.set({
            "tts_chars_today": char_count,
            "tts_chars_this_month": Increment(char_count),
            "tts_chars_lifetime": Increment(char_count),
            "tts_updated_at": now,
        }, merge=True)
    else:
        ref.set({
            "tts_chars_today": Increment(char_count),
            "tts_chars_this_month": Increment(char_count),
            "tts_chars_lifetime": Increment(char_count),
            "tts_updated_at": now,
        }, merge=True)

    credits_charged = char_count // 100
    if credits_charged > 0:
        _charge_credits_from_usage(db, uid, credits_charged, "tts_usage", char_count)

    logger.debug("record_tts_chars: uid=%s chars=%d day_reset=%s", uid, char_count, needs_day_reset)


def _charge_credits_from_usage(db, uid: str, amount: int, usage_type: str, units: int) -> None:
    """
    Deduct credits from the user's balance to pay for AI usage.
    Atomic. Balance can go negative — Android UI is responsible for warnings/upsell.
    Credits are the per-token / per-char cost of LLM and TTS calls.
    """
    if amount <= 0:
        return
    try:
        now = _now_ms()
        # Deduct from balance (negative Increment) but DO NOT decrement lifetime_earned.
        db.collection("user_credits").document(uid).set(
            {"balance": Increment(-amount), "last_updated": now},
            merge=True,
        )
        unit_label = "tokens" if usage_type == "token_usage" else "chars"
        db.collection("credit_transactions").document().set({
            "uid": uid,
            "amount": -amount,
            "type": usage_type,
            "source_id": "",
            "description": f"-{amount} credits ({units} {unit_label})",
            "created_at": now,
        })
    except Exception as exc:
        logger.warning("_charge_credits_from_usage uid=%s type=%s: %s", uid, usage_type, exc)


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


# ── Server-side quota gate ────────────────────────────────────────────────────

def check_and_record_quota(uid: str, request_type: str) -> tuple[bool, str, bool]:
    """
    Authoritative server-side quota gate.

    Uses `free_bb_remaining` / `free_chat_remaining` as the explicit remaining-count
    source of truth.  These fields reset to the daily limit on UTC day rollover.

    Returns:
        (allowed: bool, reason: str, credit_mode: bool)
        credit_mode=False — within free daily allowance (no credit deduction).
        credit_mode=True  — free quota exhausted, session runs on paid credits.

    Fails open (returns True, "", False) on infrastructure errors.
    """
    if not uid or uid == "guest_user":
        return True, "", False

    db = _get_db()
    if db is None:
        logger.warning("check_and_record_quota: Firestore unavailable — failing open uid=%s", uid)
        return True, "", False

    try:
        ref = db.collection("users_table").document(uid)
        doc = ref.get()
        if not doc.exists:
            logger.warning("check_and_record_quota: no users_table doc for uid=%s — failing open", uid)
            return True, "", False

        data = doc.to_dict() or {}
        is_bb = request_type == "blackboard"
        label       = "Blackboard" if is_bb else "Chat"
        rem_field   = "free_bb_remaining"   if is_bb else "free_chat_remaining"
        used_field  = "bb_sessions_today"   if is_bb else "chat_questions_today"
        limit_field = "plan_daily_bb_limit" if is_bb else "plan_daily_chat_limit"

        raw_limit = data.get(limit_field)
        limit = int(raw_limit) if raw_limit is not None else (2 if is_bb else 12)

        # Enforce free-tier limits if plan expired
        plan_expiry = int(data.get("plan_expiry_date") or 0)
        if plan_expiry > 0 and plan_expiry < _now_ms():
            limit = 2 if is_bb else 12
            logger.info("check_and_record_quota: plan expired for uid=%s — reverting to free limits", uid)

        # Detect UTC day rollover
        questions_updated_at = int(data.get("questions_updated_at") or 0)
        is_new_day = False
        if questions_updated_at > 0:
            try:
                last_day = datetime.fromtimestamp(questions_updated_at / 1000, tz=timezone.utc).date()
                today = datetime.now(tz=timezone.utc).date()
                is_new_day = last_day < today
            except (OSError, OverflowError, ValueError) as exc:
                logger.warning("check_and_record_quota: timestamp parse error: %s", exc)

        now = _now_ms()

        if is_new_day:
            # New UTC day — reset remaining to limit, consume 1 for this session
            new_remaining = max(0, limit - 1)
            ref.update({
                rem_field:  new_remaining,
                used_field: 1,
                "questions_updated_at": now,
            })
            logger.info(
                "check_and_record_quota: NEW_DAY FREE uid=%s type=%s reset limit=%d remaining→%d",
                uid, request_type, limit, new_remaining,
            )
            return True, "", False  # free session

        # Same day — read remaining directly
        raw_remaining = data.get(rem_field)
        if raw_remaining is None:
            # Old account without the field — backfill from used counter
            used = int(data.get(used_field) or 0)
            raw_remaining = max(0, limit - used)

        remaining = int(raw_remaining)

        if remaining > 0:
            # Free session — decrement remaining, increment used counter
            ref.update({
                rem_field:  Increment(-1),
                used_field: Increment(1),
                "questions_updated_at": now,
            })
            logger.debug(
                "check_and_record_quota: FREE uid=%s type=%s remaining=%d→%d",
                uid, request_type, remaining, remaining - 1,
            )
            return True, "", False  # free session, credit_mode=False

        # remaining == 0 — check credit fallback
        credit_balance = 0
        try:
            credit_doc = db.collection("user_credits").document(uid).get()
            if credit_doc.exists:
                credit_balance = int((credit_doc.to_dict() or {}).get("balance", 0))
        except Exception as exc:
            logger.warning("check_and_record_quota: credit read failed uid=%s: %s", uid, exc)

        if credit_balance > 0:
            logger.info(
                "check_and_record_quota: CREDIT_MODE uid=%s type=%s remaining=0 balance=%d",
                uid, request_type, credit_balance,
            )
            return True, "", True  # credit_mode=True

        logger.info(
            "check_and_record_quota: BLOCKED uid=%s type=%s remaining=0 credits=0",
            uid, request_type,
        )
        return False, (
            f"You've used all your free daily {label} sessions and have no credits left. "
            f"Add credits or come back tomorrow to continue."
        ), False

    except Exception as exc:
        logger.error("check_and_record_quota: unexpected error uid=%s: %s", uid, exc)
        return True, "", False  # Fail open


# ── Credits helper (used by activate_plan) ────────────────────────────────────

def _award_activation_credits(db, uid: str, amount: int, plan_id: str, plan_name: str) -> None:
    """Award plan activation credits atomically."""
    try:
        now = _now_ms()
        db.collection("user_credits").document(uid).set(
            {
                "balance": Increment(amount),
                "lifetime_earned": Increment(amount),
                "last_updated": now,
            },
            merge=True,
        )
        db.collection("credit_transactions").document().set({
            "uid": uid,
            "amount": amount,
            "type": "plan_activation",
            "source_id": plan_id,
            "description": f"Plan activated: {plan_name}",
            "created_at": now,
        })
        logger.info("_award_activation_credits: uid=%s amount=%d plan=%s", uid, amount, plan_id)
    except Exception as exc:
        logger.warning("_award_activation_credits uid=%s: %s", uid, exc)


def grant_topup_credits(uid: str, amount: int, pack_id: str, pack_name: str) -> None:
    """
    Grant credits to a user from a paid top-up pack.
    Atomic: increments user_credits balance + lifetime_earned, and logs the transaction.
    Called from payments.py::verify_payment when plan_id starts with "topup_".
    """
    if not uid or amount <= 0:
        return
    db = _get_db()
    if db is None:
        return
    try:
        now = _now_ms()
        db.collection("user_credits").document(uid).set(
            {
                "balance": Increment(amount),
                "lifetime_earned": Increment(amount),
                "last_updated": now,
            },
            merge=True,
        )
        db.collection("credit_transactions").document().set({
            "uid": uid,
            "amount": amount,
            "type": "topup_purchase",
            "source_id": pack_id,
            "description": f"Top-up: {pack_name}",
            "created_at": now,
        })
        logger.info("grant_topup_credits: uid=%s amount=%d pack=%s", uid, amount, pack_id)
    except Exception as exc:
        logger.warning("grant_topup_credits uid=%s pack=%s: %s", uid, pack_id, exc)


_STARTER_CREDITS = 1000   # welcome bonus for every new user


def init_user_credits(db, uid: str) -> None:
    """Create user_credits/{uid} with starter balance on first registration."""
    try:
        ref = db.collection("user_credits").document(uid)
        if not ref.get().exists:
            ref.set({
                "balance": _STARTER_CREDITS,
                "lifetime_earned": _STARTER_CREDITS,
                "last_updated": _now_ms(),
            })
            # Log the welcome grant as a transaction
            db.collection("credit_transactions").add({
                "uid": uid,
                "amount": _STARTER_CREDITS,
                "type": "welcome_grant",
                "source_id": "registration",
                "description": f"Welcome bonus ({_STARTER_CREDITS} credits)",
                "created_at": _now_ms(),
            })
    except Exception as exc:
        logger.warning("init_user_credits uid=%s: %s", uid, exc)

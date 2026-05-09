"""
Admin Portal API — HTTP Basic Auth protected endpoints for managing
Firestore collections, model configs, users, plans, schools, and payments.

Credentials come from env vars:
  ADMIN_USERNAME  (default: admin)
  ADMIN_PASSWORD  (default: changeme  — CHANGE THIS IN PRODUCTION)
"""

from __future__ import annotations

import os
import secrets
import time as _time
import uuid
from datetime import datetime, timedelta, timezone
from typing import Any, Dict, List, Optional

from fastapi import APIRouter, Depends, HTTPException, Query
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from google.cloud import firestore
from google.oauth2 import service_account

from app.core.config import settings
from app.core.logger import get_logger

logger = get_logger(__name__)
router = APIRouter(prefix="/admin/api", tags=["admin"])
security = HTTPBasic()

# ── Auth ──────────────────────────────────────────────────────────────────────

_ADMIN_USER = os.getenv("ADMIN_USERNAME", "admin")
_ADMIN_PASS = os.getenv("ADMIN_PASSWORD", "admin123")


def _require_admin(credentials: HTTPBasicCredentials = Depends(security)):
    ok_user = secrets.compare_digest(credentials.username.encode(), _ADMIN_USER.encode())
    ok_pass = secrets.compare_digest(credentials.password.encode(), _ADMIN_PASS.encode())
    if not (ok_user and ok_pass):
        raise HTTPException(
            status_code=401,
            detail="Invalid admin credentials",
            headers={"WWW-Authenticate": "Basic"},
        )
    return credentials.username


# ── Firestore client (sync, for admin ops) ────────────────────────────────────

_db_sync: Optional[firestore.Client] = None


def _get_db() -> firestore.Client:
    global _db_sync
    if _db_sync is None:
        sa_path = settings.FIREBASE_SERVICE_ACCOUNT or os.path.join(
            os.path.dirname(__file__), "..", "firebase_serviceaccount.json"
        )
        creds = service_account.Credentials.from_service_account_file(
            sa_path,
            scopes=["https://www.googleapis.com/auth/cloud-platform"],
        )
        _db_sync = firestore.Client(credentials=creds)
        logger.info("Admin Firestore sync client initialised")
    return _db_sync


def _doc_to_dict(doc: firestore.DocumentSnapshot) -> Dict[str, Any]:
    data = doc.to_dict() or {}
    data["_id"] = doc.id
    return data


def _collection_list(collection: str, limit: int = 200) -> List[Dict]:
    db = _get_db()
    docs = db.collection(collection).limit(limit).stream()
    return [_doc_to_dict(d) for d in docs]


def _count_collection(col: str) -> int:
    """Use Firestore count() aggregation (1 read) instead of streaming all docs."""
    db = _get_db()
    try:
        result = db.collection(col).count().get()
        return result[0][0].value
    except Exception:
        return len(list(db.collection(col).limit(2000).stream()))


def _to_epoch_s(v: Any) -> float:
    """Normalise various timestamp formats to epoch seconds (float)."""
    if not v:
        return 0.0
    if isinstance(v, (int, float)):
        return float(v) / 1000 if v > 1e10 else float(v)
    if hasattr(v, "timestamp"):  # datetime / Firestore DatetimeWithNanoseconds
        return v.timestamp()
    return 0.0


# Simple in-memory caches to avoid hammering Firestore on every page load
_stats_cache: Dict[str, Any] = {}
_analytics_cache: Dict[str, Any] = {}
_CACHE_TTL = 300  # 5 minutes


# ── Dashboard ─────────────────────────────────────────────────────────────────

@router.get("/stats")
def admin_stats(_: str = Depends(_require_admin)):
    """Count-only stats using Firestore aggregation queries (cheap: 1 read/collection)."""
    now = _time.time()
    if _stats_cache.get("ts", 0) + _CACHE_TTL > now:
        return _stats_cache["data"]

    data = {
        "users":          _count_collection("users"),
        "subjects":       _count_collection("subjects"),
        "chapters":       _count_collection("chapters"),
        "plans":          _count_collection("plans"),
        "payments":       _count_collection("payment_receipts"),
        "quizzes":        _count_collection("quizzes"),
        "schools":        _count_collection("schools"),
        "referral_codes": _count_collection("referralCodes"),
    }
    _stats_cache.update({"ts": now, "data": data})
    return data


@router.get("/analytics")
async def admin_analytics(_: str = Depends(_require_admin)):
    """
    Rich analytics in a single endpoint — 2 Firestore fetches + LiteLLM API.
    Cached for 5 minutes.
    """
    now = _time.time()
    if _analytics_cache.get("ts", 0) + _CACHE_TTL > now:
        return _analytics_cache["data"]

    db = _get_db()
    today = datetime.now(timezone.utc)
    today_start = _to_epoch_s(int(datetime(today.year, today.month, today.day, tzinfo=timezone.utc).timestamp()))
    week_ago    = _to_epoch_s(int((today - timedelta(days=7)).timestamp()))
    month_start = _to_epoch_s(int(datetime(today.year, today.month, 1, tzinfo=timezone.utc).timestamp()))

    # ── Single-pass user analysis ──────────────────────────────────────────────
    users = [d.to_dict() or {} for d in db.collection("users").limit(500).stream()]
    plan_dist:  Dict[str, int] = {}
    grade_dist: Dict[str, int] = {}
    active_today = new_week = new_month = 0

    for u in users:
        plan  = u.get("planId") or u.get("plan_id") or u.get("plan") or "free"
        plan_dist[plan] = plan_dist.get(plan, 0) + 1

        grade = str(u.get("grade") or u.get("class_grade") or "?")
        grade_dist[grade] = grade_dist.get(grade, 0) + 1

        upd_ts = _to_epoch_s(u.get("questions_updated_at"))
        if upd_ts >= today_start and upd_ts > 0:
            active_today += 1

        ca_ts = _to_epoch_s(u.get("created_at") or u.get("createdAt"))
        if ca_ts > 0:
            if ca_ts >= week_ago:
                new_week += 1
            if ca_ts >= month_start:
                new_month += 1

    # ── Payment revenue ────────────────────────────────────────────────────────
    payments = [d.to_dict() or {} for d in db.collection("payment_receipts").limit(200).stream()]
    total_rev = month_rev = 0.0
    for p in payments:
        amt = 0.0
        for field in ("amount", "amount_rupees", "amount_inr", "price"):
            raw = p.get(field)
            if raw:
                try:
                    amt = float(raw)
                    break
                except (TypeError, ValueError):
                    pass
        total_rev += amt
        pt = _to_epoch_s(p.get("paid_at") or p.get("created_at") or p.get("timestamp"))
        if pt >= month_start and pt > 0:
            month_rev += amt

    # ── LiteLLM costs ──────────────────────────────────────────────────────────
    llm_raw: Any = {}
    try:
        from app.services import litellm_service as _lls
        llm_raw = await _lls.get_all_usage_stats() or {}
    except Exception:
        pass

    # LiteLLM /user/list returns a list of user objects; normalise to uid→stats dict.
    # Also handles {"users": [...]} or {"data": [...]} wrapper shapes.
    total_cost = float((llm_raw.get("total_cost_all_users") if isinstance(llm_raw, dict) else None) or 0)
    raw_users: Any = (
        llm_raw if isinstance(llm_raw, list)
        else llm_raw.get("users") or llm_raw.get("data") or {}
        if isinstance(llm_raw, dict) else {}
    )
    user_costs: Dict = {}
    if isinstance(raw_users, list):
        for item in raw_users:
            uid = item.get("user_id") or item.get("id") or ""
            if not uid:
                continue
            cost = float(item.get("spend") or item.get("total_cost") or 0)
            reqs = int(item.get("total_requests_made") or item.get("total_requests") or 0)
            user_costs[uid] = {"total_cost": cost, "total_requests": reqs}
        if not total_cost:
            total_cost = sum(v["total_cost"] for v in user_costs.values())
    elif isinstance(raw_users, dict):
        user_costs = raw_users
        if not total_cost:
            total_cost = sum(float(v.get("total_cost") or v.get("spend") or 0) for v in user_costs.values())
    top_spenders = sorted(
        [{"uid": uid,
          "cost": float(v.get("total_cost") or 0),
          "requests": int(v.get("total_requests") or 0)}
         for uid, v in user_costs.items()],
        key=lambda x: x["cost"],
        reverse=True,
    )[:15]
    total_users = len(users)

    data = {
        "users": {
            "total":          total_users,
            "new_this_week":  new_week,
            "new_this_month": new_month,
            "active_today":   active_today,
            "plan_distribution":  dict(sorted(plan_dist.items(),  key=lambda x: x[1], reverse=True)),
            "grade_distribution": dict(sorted(grade_dist.items(), key=lambda x: x[1], reverse=True)),
        },
        "revenue": {
            "total":         round(total_rev, 2),
            "this_month":    round(month_rev, 2),
            "payment_count": len(payments),
        },
        "llm": {
            "total_cost_usd":        round(total_cost, 4),
            "avg_cost_per_user_usd": round(total_cost / max(total_users, 1), 4),
            "users_with_usage":      len(user_costs),
            "top_spenders":          top_spenders,
        },
    }
    _analytics_cache.update({"ts": now, "data": data})
    return data


# ── Users ─────────────────────────────────────────────────────────────────────

@router.get("/users")
def list_users(limit: int = Query(100, le=500), _: str = Depends(_require_admin)):
    db = _get_db()
    docs = db.collection("users").limit(limit).stream()
    return [_doc_to_dict(d) for d in docs]


@router.get("/users/{uid}")
def get_user(uid: str, _: str = Depends(_require_admin)):
    db = _get_db()
    doc = db.collection("users").document(uid).get()
    if not doc.exists:
        raise HTTPException(status_code=404, detail="User not found")
    data = _doc_to_dict(doc)
    # fetch chapter_progress subcollection
    progress_docs = db.collection("users").document(uid).collection("chapter_progress").stream()
    data["chapter_progress"] = [_doc_to_dict(p) for p in progress_docs]
    return data


@router.put("/users/{uid}")
def update_user(uid: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("users").document(uid).set(payload, merge=True)
    return {"ok": True}


@router.delete("/users/{uid}")
def delete_user(uid: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("users").document(uid).delete()
    return {"ok": True}


# ── Subjects ──────────────────────────────────────────────────────────────────

@router.get("/subjects")
def list_subjects(_: str = Depends(_require_admin)):
    return _collection_list("subjects", 500)


@router.post("/subjects")
def create_subject(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    doc_id = payload.pop("_id", None) or str(uuid.uuid4())
    db = _get_db()
    db.collection("subjects").document(doc_id).set(payload)
    return {"id": doc_id, "ok": True}


@router.put("/subjects/{subject_id}")
def update_subject(subject_id: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("subjects").document(subject_id).set(payload, merge=True)
    return {"ok": True}


@router.delete("/subjects/{subject_id}")
def delete_subject(subject_id: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("subjects").document(subject_id).delete()
    return {"ok": True}


# ── Chapters ──────────────────────────────────────────────────────────────────

@router.get("/chapters")
def list_chapters(
    subject_id: Optional[str] = Query(None),
    limit: int = Query(500, le=2000),
    _: str = Depends(_require_admin),
):
    db = _get_db()
    ref = db.collection("chapters")
    query = ref.where("subject_id", "==", subject_id) if subject_id else ref
    docs = query.limit(limit).stream()
    return [_doc_to_dict(d) for d in docs]


@router.post("/chapters")
def create_chapter(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    doc_id = payload.pop("_id", None) or str(uuid.uuid4())
    db = _get_db()
    db.collection("chapters").document(doc_id).set(payload)
    return {"id": doc_id, "ok": True}


@router.put("/chapters/{chapter_id}")
def update_chapter(chapter_id: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("chapters").document(chapter_id).set(payload, merge=True)
    return {"ok": True}


@router.delete("/chapters/{chapter_id}")
def delete_chapter(chapter_id: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("chapters").document(chapter_id).delete()
    return {"ok": True}


# ── Plans ─────────────────────────────────────────────────────────────────────

@router.get("/plans")
def list_plans(_: str = Depends(_require_admin)):
    return _collection_list("plans", 100)


@router.post("/plans")
def create_plan(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    doc_id = payload.pop("_id", None) or str(uuid.uuid4())
    db = _get_db()
    db.collection("plans").document(doc_id).set(payload)
    return {"id": doc_id, "ok": True}


@router.put("/plans/{plan_id}")
def update_plan(plan_id: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("plans").document(plan_id).set(payload, merge=True)
    return {"ok": True}


@router.delete("/plans/{plan_id}")
def delete_plan(plan_id: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("plans").document(plan_id).delete()
    return {"ok": True}


# ── Schools ───────────────────────────────────────────────────────────────────

@router.get("/schools")
def list_schools(_: str = Depends(_require_admin)):
    return _collection_list("schools", 200)


@router.post("/schools")
def create_school(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    doc_id = payload.pop("_id", None) or str(uuid.uuid4())
    db = _get_db()
    db.collection("schools").document(doc_id).set(payload)
    return {"id": doc_id, "ok": True}


@router.put("/schools/{school_id}")
def update_school(school_id: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("schools").document(school_id).set(payload, merge=True)
    return {"ok": True}


@router.delete("/schools/{school_id}")
def delete_school(school_id: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("schools").document(school_id).delete()
    return {"ok": True}


# ── Model Configuration ───────────────────────────────────────────────────────

@router.get("/model-config")
def get_model_config(_: str = Depends(_require_admin)):
    """Returns both the live app settings (from env) and the Firestore admin_config."""
    live_tiers = {
        "power": {
            "provider": settings.POWER_PROVIDER,
            "model_id": settings.POWER_MODEL_ID,
            "temperature": settings.POWER_TEMPERATURE,
            "max_tokens": settings.POWER_MAX_TOKENS,
        },
        "cheaper": {
            "provider": settings.CHEAPER_PROVIDER,
            "model_id": settings.CHEAPER_MODEL_ID,
            "temperature": settings.CHEAPER_TEMPERATURE,
            "max_tokens": settings.CHEAPER_MAX_TOKENS,
        },
        "faster": {
            "provider": settings.FASTER_PROVIDER,
            "model_id": settings.FASTER_MODEL_ID,
            "temperature": settings.FASTER_TEMPERATURE,
            "max_tokens": settings.FASTER_MAX_TOKENS,
        },
    }
    db = _get_db()
    doc = db.collection("admin_config").document("global").get()
    firestore_config = _doc_to_dict(doc) if doc.exists else {}
    return {"live_env_tiers": live_tiers, "firestore_admin_config": firestore_config}


@router.put("/model-config")
def update_model_config(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    """Update the Firestore admin_config/global document."""
    payload.pop("_id", None)
    db = _get_db()
    db.collection("admin_config").document("global").set(payload, merge=True)
    return {"ok": True}


# ── Payments ──────────────────────────────────────────────────────────────────

@router.get("/payments/intents")
def list_payment_intents(limit: int = Query(100, le=500), _: str = Depends(_require_admin)):
    db = _get_db()
    docs = db.collection("payment_intents").order_by(
        "created_at", direction=firestore.Query.DESCENDING
    ).limit(limit).stream()
    return [_doc_to_dict(d) for d in docs]


@router.get("/payments/receipts")
def list_payment_receipts(limit: int = Query(100, le=500), _: str = Depends(_require_admin)):
    db = _get_db()
    docs = db.collection("payment_receipts").limit(limit).stream()
    return [_doc_to_dict(d) for d in docs]


@router.get("/payments/webhooks")
def list_payment_webhooks(limit: int = Query(50, le=200), _: str = Depends(_require_admin)):
    db = _get_db()
    docs = db.collection("payment_webhooks").limit(limit).stream()
    return [_doc_to_dict(d) for d in docs]


# ── App Config ────────────────────────────────────────────────────────────────

@router.get("/app-config")
def get_app_config(_: str = Depends(_require_admin)):
    db = _get_db()
    doc = db.collection("updates").document("app_config").get()
    return _doc_to_dict(doc) if doc.exists else {}


@router.put("/app-config")
def update_app_config(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("updates").document("app_config").set(payload, merge=True)
    return {"ok": True}


# ── Offers ────────────────────────────────────────────────────────────────────

@router.get("/offers")
def list_offers(_: str = Depends(_require_admin)):
    return _collection_list("app_offers", 100)


@router.post("/offers")
def create_offer(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    doc_id = payload.pop("_id", None) or str(uuid.uuid4())
    db = _get_db()
    db.collection("app_offers").document(doc_id).set(payload)
    return {"id": doc_id, "ok": True}


@router.put("/offers/{offer_id}")
def update_offer(offer_id: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("app_offers").document(offer_id).set(payload, merge=True)
    return {"ok": True}


@router.delete("/offers/{offer_id}")
def delete_offer(offer_id: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("app_offers").document(offer_id).delete()
    return {"ok": True}


# ── BB Samples (Onboarding) ───────────────────────────────────────────────────

@router.get("/bb-samples")
def list_bb_samples(_: str = Depends(_require_admin)):
    """List all global onboarding BB sample lessons."""
    return _collection_list("bb_samples", 50)


@router.post("/bb-samples")
def create_bb_sample(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    """
    Create or replace a BB sample lesson.
    Required fields: id (doc key), title, subject, chapter, steps_json (JSON string).
    """
    import time as _time
    doc_id = payload.pop("id", None) or payload.pop("_id", None)
    if not doc_id:
        raise HTTPException(status_code=400, detail="Field 'id' is required as the document key")
    if not payload.get("steps_json"):
        raise HTTPException(status_code=400, detail="Field 'steps_json' (JSON string) is required")
    payload.setdefault("is_sample", True)
    payload.setdefault("lang", "en-US")
    payload.setdefault("created_at", int(_time.time() * 1000))
    db = _get_db()
    db.collection("bb_samples").document(doc_id).set(payload)
    return {"id": doc_id, "ok": True}


@router.put("/bb-samples/{sample_id}")
def update_bb_sample(sample_id: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    """Update fields on an existing BB sample."""
    payload.pop("_id", None)
    db = _get_db()
    db.collection("bb_samples").document(sample_id).set(payload, merge=True)
    return {"ok": True}


@router.delete("/bb-samples/{sample_id}")
def delete_bb_sample(sample_id: str, _: str = Depends(_require_admin)):
    """Delete a BB sample lesson."""
    db = _get_db()
    db.collection("bb_samples").document(sample_id).delete()
    return {"ok": True}


# ── Notifications ─────────────────────────────────────────────────────────────

@router.get("/notifications")
def list_notifications(_: str = Depends(_require_admin)):
    return _collection_list("notifications", 200)


@router.post("/notifications")
def create_notification(payload: Dict[str, Any], _: str = Depends(_require_admin)):
    doc_id = payload.pop("_id", None) or str(uuid.uuid4())
    db = _get_db()
    db.collection("notifications").document(doc_id).set(payload)
    return {"id": doc_id, "ok": True}


@router.put("/notifications/{notif_id}")
def update_notification(notif_id: str, payload: Dict[str, Any], _: str = Depends(_require_admin)):
    payload.pop("_id", None)
    db = _get_db()
    db.collection("notifications").document(notif_id).set(payload, merge=True)
    return {"ok": True}


@router.delete("/notifications/{notif_id}")
def delete_notification(notif_id: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("notifications").document(notif_id).delete()
    return {"ok": True}


# ── Referral Codes ────────────────────────────────────────────────────────────

@router.get("/referral-codes")
def list_referral_codes(limit: int = Query(200, le=1000), _: str = Depends(_require_admin)):
    db = _get_db()
    docs = db.collection("referralCodes").limit(limit).stream()
    return [_doc_to_dict(d) for d in docs]


@router.delete("/referral-codes/{code}")
def delete_referral_code(code: str, _: str = Depends(_require_admin)):
    db = _get_db()
    db.collection("referralCodes").document(code).delete()
    return {"ok": True}


# ── LiteLLM API Key Management ────────────────────────────────────────────────
# Per-user API key management and usage monitoring via LiteLLM proxy

from app.services import litellm_service
from fastapi import BackgroundTasks


@router.post("/litellm/keys/create")
async def create_litellm_key(
    user_id: str = Query(...),
    name: Optional[str] = None,
    _: str = Depends(_require_admin)
):
    """
    Create a new LiteLLM API key for a user.
    
    Args:
        user_id: Firebase user ID or unique identifier
        name: Optional user name for metadata
    
    Returns:
        {"key": "sk-user-abc123...", "user_id": "...", "created_at": "2024-12-20T..."}
    """
    metadata = {"name": name} if name else {}
    key = await litellm_service.create_user_api_key(user_id, metadata)
    if not key:
        raise HTTPException(status_code=500, detail="Failed to create LiteLLM key")
    return {"key": key, "user_id": user_id, "created_at": "just now"}


@router.get("/litellm/keys/{user_id}")
async def get_user_keys(user_id: str, _: str = Depends(_require_admin)):
    """
    List all LiteLLM API keys for a user.
    
    Args:
        user_id: User ID
    
    Returns:
        {"keys": [{"key": "sk-...", "created_at": "...", "is_valid": true}, ...]}
    """
    keys = await litellm_service.get_user_api_keys(user_id)
    return {"user_id": user_id, "keys": keys}


@router.delete("/litellm/keys/revoke")
async def revoke_litellm_key(key: str = Query(...), _: str = Depends(_require_admin)):
    """
    Revoke a LiteLLM API key (disable it).
    
    Args:
        key: LiteLLM API key to revoke
    
    Returns:
        {"ok": true}
    """
    success = await litellm_service.revoke_api_key(key)
    if not success:
        raise HTTPException(status_code=500, detail="Failed to revoke key")
    return {"ok": True}


@router.get("/litellm/usage/{user_id}")
async def get_user_usage(user_id: str, _: str = Depends(_require_admin)):
    """
    Get usage statistics for a user (tokens, cost, requests).
    
    Args:
        user_id: User ID
    
    Returns:
        {
            "user_id": "...",
            "total_requests": 250,
            "total_input_tokens": 125000,
            "total_output_tokens": 45000,
            "total_cost": 12.50,
            "daily_usage": {"2024-12-20": {"tokens": 5000, "cost": 0.50}, ...},
            "models_used": ["power", "cheaper"],
            "last_request_at": "2024-12-20T15:30:00Z"
        }
    """
    stats = await litellm_service.get_user_usage_stats(user_id)
    if not stats:
        return {
            "user_id": user_id,
            "total_requests": 0,
            "total_cost": 0.0,
            "error": "User has no LiteLLM key or no usage yet"
        }
    return stats


@router.get("/litellm/usage/all")
async def get_all_usage(limit: int = Query(100, le=1000), _: str = Depends(_require_admin)):
    """
    Get usage statistics for all users (aggregated).
    
    Returns:
        {
            "users": {
                "user_1": {"total_cost": 12.50, "total_requests": 250},
                "user_2": {"total_cost": 25.00, "total_requests": 500},
                ...
            },
            "total_cost_all_users": 37.50
        }
    """
    all_stats = await litellm_service.get_all_usage_stats()
    if not all_stats:
        return {"users": {}, "total_cost_all_users": 0.0, "error": "Failed to fetch stats"}
    return all_stats


@router.get("/litellm/health")
async def litellm_health(_: str = Depends(_require_admin)):
    """
    Check if LiteLLM proxy is running and healthy.
    
    Returns:
        {"status": "healthy", "proxy_url": "http://localhost:8005"}
        or
        {"status": "unhealthy", "proxy_url": "http://localhost:8005"}
    """
    healthy = await litellm_service.health_check()
    return {
        "status": "healthy" if healthy else "unhealthy",
        "proxy_url": settings.LITELLM_PROXY_URL,
        "admin_url": settings.LITELLM_ADMIN_URL
    }


# ── Activity Logs ─────────────────────────────────────────────────────────────

@router.get("/activity-logs")
def get_activity_logs(
    limit: int = Query(100, le=500),
    event_type: Optional[str] = Query(None, description="Filter by event type: chat, register, quiz_generate, login"),
    uid: Optional[str] = Query(None, description="Filter by user UID"),
    _: str = Depends(_require_admin),
):
    """Return recent activity log entries, newest first."""
    db = _get_db()
    ref = db.collection("activity_logs")
    try:
        if uid:
            # where-only, no order_by — avoids composite-index requirement
            docs = ref.where("uid", "==", uid).limit(limit).stream()
        elif event_type:
            docs = ref.where("event_type", "==", event_type).limit(limit).stream()
        else:
            docs = ref.order_by("timestamp", direction=firestore.Query.DESCENDING).limit(limit).stream()
        return [_doc_to_dict(d) for d in docs]
    except Exception as exc:
        logger.warning("activity_logs read failed: %s", exc)
        return []


@router.get("/activity-logs/stats")
def get_activity_stats(_: str = Depends(_require_admin)):
    """Return count totals per event type over the last 500 entries."""
    db = _get_db()
    counts: Dict[str, int] = {}
    recent: List[Dict] = []
    try:
        docs = db.collection("activity_logs").order_by(
            "timestamp", direction=firestore.Query.DESCENDING
        ).limit(500).stream()
        for doc in docs:
            data = _doc_to_dict(doc)
            event = data.get("event_type", "unknown")
            counts[event] = counts.get(event, 0) + 1
            if len(recent) < 10:
                recent.append(data)
    except Exception as exc:
        logger.warning("activity_logs/stats read failed: %s", exc)
    return {"counts": counts, "recent": recent, "total": sum(counts.values())}


# ── Generic collection viewer (read-only safety hatch) ────────────────────────

ALLOWED_COLLECTIONS = {
    "users", "subjects", "chapters", "plans", "schools",
    "quizzes", "payment_intents", "payment_receipts", "payment_webhooks",
    "app_offers", "updates", "notifications", "admin_config", "referralCodes",
    "activity_logs",
}


@router.get("/collection/{name}")
def raw_collection(
    name: str,
    limit: int = Query(100, le=500),
    _: str = Depends(_require_admin),
):
    if name not in ALLOWED_COLLECTIONS:
        raise HTTPException(status_code=400, detail="Collection not allowed")
    return _collection_list(name, limit)

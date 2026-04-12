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
import uuid
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


# ── Dashboard ─────────────────────────────────────────────────────────────────

@router.get("/stats")
def admin_stats(_: str = Depends(_require_admin)):
    db = _get_db()
    users_count = len(list(db.collection("users").limit(500).stream()))
    subjects_count = len(list(db.collection("subjects").limit(500).stream()))
    chapters_count = len(list(db.collection("chapters").limit(1000).stream()))
    plans_count = len(list(db.collection("plans").limit(100).stream()))
    payments_count = len(list(db.collection("payment_receipts").limit(500).stream()))
    quizzes_count = len(list(db.collection("quizzes").limit(500).stream()))
    schools_count = len(list(db.collection("schools").limit(100).stream()))
    referrals_count = len(list(db.collection("referralCodes").limit(500).stream()))
    return {
        "users": users_count,
        "subjects": subjects_count,
        "chapters": chapters_count,
        "plans": plans_count,
        "payments": payments_count,
        "quizzes": quizzes_count,
        "schools": schools_count,
        "referral_codes": referrals_count,
    }


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


# ── Generic collection viewer (read-only safety hatch) ────────────────────────

ALLOWED_COLLECTIONS = {
    "users", "subjects", "chapters", "plans", "schools",
    "quizzes", "payment_intents", "payment_receipts", "payment_webhooks",
    "app_offers", "updates", "notifications", "admin_config", "referralCodes",
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

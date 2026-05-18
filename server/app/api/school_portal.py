"""
School Admin Portal API
========================
Principals/school-admins log in here (separate from the super-admin portal).
Auth is a school-scoped JWT signed with SCHOOL_PORTAL_JWT_SECRET.

Routes (all prefixed /school-portal):
  POST /login                     — validate school_id + admin_pin → JWT
  GET  /dashboard                 — class stats for this school
  GET  /students/export.csv       — CSV download
  GET  /school                    — school info (name, branding)
  PUT  /school/branding           — update primary_color / logo_url
"""

from __future__ import annotations

import csv
import hashlib
import io
import os
import time
from typing import Any, Dict, Optional

import jwt  # PyJWT
from fastapi import APIRouter, Depends, HTTPException, status
from fastapi.responses import StreamingResponse
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer
from pydantic import BaseModel

from app.core.firebase_auth import get_firestore_db
from app.core.logger import get_logger

logger = get_logger(__name__)
router = APIRouter(prefix="/school-portal", tags=["school-portal"])

_bearer_scheme = HTTPBearer(auto_error=False)

# ── JWT helpers ───────────────────────────────────────────────────────────────

_JWT_SECRET = os.getenv("SCHOOL_PORTAL_JWT_SECRET", "school-portal-dev-secret-change-in-prod")
_JWT_ALG    = "HS256"
_JWT_TTL    = 8 * 3600   # 8 hours


def _make_token(school_id: str) -> str:
    payload = {
        "school_id": school_id,
        "iat": int(time.time()),
        "exp": int(time.time()) + _JWT_TTL,
    }
    return jwt.encode(payload, _JWT_SECRET, algorithm=_JWT_ALG)


def _decode_token(token: str) -> Dict[str, Any]:
    try:
        return jwt.decode(token, _JWT_SECRET, algorithms=[_JWT_ALG])
    except jwt.ExpiredSignatureError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Token expired")
    except jwt.InvalidTokenError:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")


def _require_school_auth(
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer_scheme),
) -> str:
    """Dependency: returns school_id from a valid school-portal JWT."""
    if credentials is None or not credentials.credentials:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Authorization header missing",
            headers={"WWW-Authenticate": "Bearer"},
        )
    payload = _decode_token(credentials.credentials)
    school_id = payload.get("school_id")
    if not school_id:
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token payload")
    return school_id


# ── PIN hashing ───────────────────────────────────────────────────────────────

def _hash_pin(pin: str) -> str:
    """SHA-256 of the raw PIN string.  Stored as 'admin_pin_hash' in schools/{id}."""
    return hashlib.sha256(pin.encode()).hexdigest()


# ── Models ────────────────────────────────────────────────────────────────────

class LoginRequest(BaseModel):
    school_id: str
    admin_pin: str      # 4–8 digit PIN (plaintext, compared against stored hash)


class BrandingUpdateRequest(BaseModel):
    school_name: Optional[str] = None
    primary_color: Optional[str] = None   # hex e.g. "#1A1A2E"
    logo_url: Optional[str] = None


# ── Helpers ───────────────────────────────────────────────────────────────────

_SEVEN_DAYS_MS = 7 * 24 * 3600 * 1000
_THIRTY_DAYS_MS = 30 * 24 * 3600 * 1000


def _ms_to_relative_label(last_active_ms: int) -> str:
    """Returns a human-readable staleness string."""
    if last_active_ms == 0:
        return "Never"
    diff_ms = int(time.time() * 1000) - last_active_ms
    days = diff_ms // (24 * 3600 * 1000)
    if days == 0:
        return "Today"
    if days == 1:
        return "Yesterday"
    if days <= 30:
        return f"{days}d ago"
    return f"{days // 30}mo ago"


def _activity_status(last_active_ms: int) -> str:
    """green / yellow / red for student activity badge."""
    if last_active_ms == 0:
        return "inactive"
    diff_days = (int(time.time() * 1000) - last_active_ms) // (24 * 3600 * 1000)
    if diff_days <= 3:
        return "active"
    if diff_days <= 7:
        return "idle"
    return "inactive"


def _fetch_school_doc(db, school_id: str) -> Dict[str, Any]:
    doc = db.collection("schools").document(school_id).get()
    if not doc.exists:
        raise HTTPException(status_code=404, detail="School not found")
    return doc.to_dict() or {}


def _fetch_class_students(db, school_id: str) -> list[Dict[str, Any]]:
    """Query students_stats by school_id (single-field filter, no composite index needed)."""
    snap = db.collection("students_stats").where("school_id", "==", school_id).get()
    students = []
    now_ms = int(time.time() * 1000)
    for doc in snap:
        d = doc.to_dict() or {}
        answered = int(d.get("total_quizzes_answered") or 0)
        correct  = int(d.get("total_quizzes_correct") or 0)
        quiz_pct = round(correct * 100 / answered) if answered > 0 else -1
        last_ms  = int(d.get("last_active_at") or 0)
        students.append({
            "uid":            d.get("user_id", ""),
            "name":           d.get("display_name", ""),
            "grade":          d.get("grade", ""),
            "section":        d.get("section", ""),
            "bb_sessions":    int(d.get("total_bb_sessions") or 0),
            "chat_sessions":  int(d.get("total_messages") or 0),
            "quiz_pct":       quiz_pct,
            "streak_days":    int(d.get("streak_days") or 0),
            "last_active_ms": last_ms,
            "last_active_label": _ms_to_relative_label(last_ms),
            "status":         _activity_status(last_ms),
        })
    students.sort(key=lambda s: s["name"].lower())
    return students


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.post("/login")
def school_portal_login(req: LoginRequest):
    """
    Validate school_id + admin_pin and return a signed JWT.
    PIN is stored in schools/{id}.admin_pin_hash as SHA-256 of the raw PIN.
    If admin_pin_hash is absent the school hasn't been set up yet → 403.
    """
    if not req.school_id or not req.admin_pin:
        raise HTTPException(status_code=400, detail="school_id and admin_pin are required")

    db = get_firestore_db()
    if db is None:
        raise HTTPException(status_code=503, detail="Database unavailable")

    school = _fetch_school_doc(db, req.school_id)
    stored_hash = school.get("admin_pin_hash")
    if not stored_hash:
        raise HTTPException(
            status_code=403,
            detail="This school has no portal PIN set up. Contact AIGuru support."
        )

    submitted_hash = _hash_pin(req.admin_pin)
    if submitted_hash != stored_hash:
        # Log attempt (without the PIN) for audit trail
        logger.warning("Failed portal login for school=%s", req.school_id)
        raise HTTPException(status_code=401, detail="Incorrect PIN")

    token = _make_token(req.school_id)
    logger.info("Portal login success: school=%s", req.school_id)
    return {
        "token": token,
        "school_name": school.get("name", req.school_id),
        "school_id": req.school_id,
    }


@router.get("/school")
def get_school_info(school_id: str = Depends(_require_school_auth)):
    """Return basic school info + branding for the portal UI."""
    db = get_firestore_db()
    if db is None:
        raise HTTPException(status_code=503, detail="Database unavailable")
    school = _fetch_school_doc(db, school_id)
    # Strip sensitive fields before returning
    school.pop("admin_pin_hash", None)
    school.pop("students", None)   # large sub-collections not needed
    return school


def _build_grade_breakdown(students: list, now_ms: int) -> list:
    """Class Activity Snapshot grouped by grade — no individual ranking."""
    from collections import defaultdict
    grade_map: Dict[str, list] = defaultdict(list)
    for s in students:
        grade_map[s.get("grade", "—")].append(s)
    breakdown = []
    for grade in sorted(grade_map.keys()):
        g = grade_map[grade]
        active = sum(1 for s in g if s["last_active_ms"] > now_ms - _SEVEN_DAYS_MS)
        total_bb = sum(s["bb_sessions"] for s in g)
        avg_bb = round(total_bb / len(g), 1) if g else 0
        breakdown.append({
            "grade":    grade,
            "enrolled": len(g),
            "active_7d": active,
            "avg_bb":   avg_bb,
        })
    return breakdown


@router.get("/dashboard")
def get_dashboard(school_id: str = Depends(_require_school_auth)):
    """
    Returns aggregated class stats for the dashboard.
    Reads students_stats collection filtered by school_id.
    """
    db = get_firestore_db()
    if db is None:
        raise HTTPException(status_code=503, detail="Database unavailable")

    school = _fetch_school_doc(db, school_id)
    students = _fetch_class_students(db, school_id)

    now_ms = int(time.time() * 1000)
    active_7d  = sum(1 for s in students if s["last_active_ms"] > now_ms - _SEVEN_DAYS_MS)
    active_30d = sum(1 for s in students if s["last_active_ms"] > now_ms - _THIRTY_DAYS_MS)
    total_bb   = sum(s["bb_sessions"] for s in students)
    avg_bb     = round(total_bb / len(students), 1) if students else 0

    # Top subject by total bb_sessions across all students
    subject_totals: Dict[str, int] = {}
    for s in students:
        # subject breakdown not in the flat stats — we aggregate from what we have
        pass
    top_subject = max(subject_totals, key=subject_totals.get) if subject_totals else "—"

    # Daily active count for last 7 days (date buckets)
    import datetime
    today = datetime.date.today()
    daily_active = []
    for i in range(6, -1, -1):
        d = today - datetime.timedelta(days=i)
        d_start_ms = int(datetime.datetime.combine(d, datetime.time.min).timestamp() * 1000)
        d_end_ms   = int(datetime.datetime.combine(d, datetime.time.max).timestamp() * 1000)
        count = sum(
            1 for s in students
            if d_start_ms <= s["last_active_ms"] <= d_end_ms
        )
        daily_active.append({"day": d.strftime("%a"), "count": count})

    return {
        "school_id":    school_id,
        "school_name":  school.get("name", school_id),
        "primary_color": school.get("primary_color") or school.get("branding", {}).get("primary_color") or "#1A1A2E",
        "logo_url":     school.get("logo_url") or school.get("branding", {}).get("logo_url") or "",
        "total_enrolled": len(students),
        "active_last_7d":  active_7d,
        "active_last_30d": active_30d,
        "avg_bb_sessions": avg_bb,
        "top_subject":     top_subject,
        "daily_active":    daily_active,
        "students":        students,
        "grade_breakdown": _build_grade_breakdown(students, now_ms),
        "generated_at":    int(time.time()),
    }


@router.get("/students/export.csv")
def export_students_csv(school_id: str = Depends(_require_school_auth)):
    """Download student stats as a CSV file."""
    db = get_firestore_db()
    if db is None:
        raise HTTPException(status_code=503, detail="Database unavailable")

    students = _fetch_class_students(db, school_id)

    output = io.StringIO()
    writer = csv.writer(output)
    writer.writerow([
        "Name", "Grade", "Section",
        "BB Sessions", "Chat Sessions", "Quiz %",
        "Streak Days", "Last Active", "Status"
    ])
    for s in students:
        writer.writerow([
            s["name"], s["grade"], s["section"],
            s["bb_sessions"], s["chat_sessions"],
            f"{s['quiz_pct']}%" if s["quiz_pct"] >= 0 else "—",
            s["streak_days"],
            s["last_active_label"],
            s["status"],
        ])

    output.seek(0)
    return StreamingResponse(
        iter([output.getvalue()]),
        media_type="text/csv",
        headers={"Content-Disposition": f'attachment; filename="class_report_{school_id}.csv"'},
    )


@router.put("/school/branding")
def update_school_branding(
    req: BrandingUpdateRequest,
    school_id: str = Depends(_require_school_auth),
):
    """Let a principal update their school's branding (colors, logo URL, name)."""
    db = get_firestore_db()
    if db is None:
        raise HTTPException(status_code=503, detail="Database unavailable")

    updates: Dict[str, Any] = {}
    if req.school_name:
        updates["name"] = req.school_name
    if req.primary_color:
        # Basic validation — must be a 7-char hex color
        c = req.primary_color.strip()
        if len(c) == 7 and c.startswith("#") and all(ch in "0123456789ABCDEFabcdef" for ch in c[1:]):
            updates["primary_color"] = c
            updates["branding.primary_color"] = c
        else:
            raise HTTPException(status_code=400, detail="primary_color must be a 7-char hex color like #1A1A2E")
    if req.logo_url:
        # Only allow HTTPS URLs
        if not req.logo_url.startswith("https://"):
            raise HTTPException(status_code=400, detail="logo_url must start with https://")
        updates["logo_url"] = req.logo_url
        updates["branding.logo_url"] = req.logo_url

    if not updates:
        raise HTTPException(status_code=400, detail="No fields to update")

    db.collection("schools").document(school_id).set(updates, merge=True)
    logger.info("Branding updated for school=%s: %s", school_id, list(updates.keys()))
    return {"ok": True, "updated_fields": list(updates.keys())}

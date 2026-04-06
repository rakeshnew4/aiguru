"""
Library Service – Firestore CRUD for subjects, chapters, and user selections.
"""

from __future__ import annotations

import os
from typing import List, Optional

from google.cloud import firestore
from google.oauth2 import service_account

from app.core.config import settings
from app.core.logger import get_logger
from app.models.library import Chapter, ChapterProgress, Subject

logger = get_logger(__name__)

# ── Firestore client (lazy singleton) ─────────────────────────────────────────

_db: Optional[firestore.AsyncClient] = None


def _get_db() -> firestore.AsyncClient:
    global _db
    if _db is None:
        sa_path = settings.FIREBASE_SERVICE_ACCOUNT or os.path.join(
            os.path.dirname(__file__), "..", "firebase_serviceaccount.json"
        )
        creds = service_account.Credentials.from_service_account_file(
            sa_path,
            scopes=["https://www.googleapis.com/auth/cloud-platform"],
        )
        _db = firestore.AsyncClient(credentials=creds)
        logger.info("Firestore AsyncClient initialised")
    return _db


# ── Subjects ──────────────────────────────────────────────────────────────────

async def get_all_subjects() -> List[Subject]:
    """Return every document from the *subjects* collection."""
    db = _get_db()
    docs = db.collection("subjects").stream()
    subjects: List[Subject] = []
    async for doc in docs:
        data = doc.to_dict()
        data["id"] = doc.id
        subjects.append(Subject(**data))
    return subjects


# ── Chapters ──────────────────────────────────────────────────────────────────

async def get_chapters_by_subject(subject_id: str) -> List[Chapter]:
    """Return all chapters that belong to *subject_id*, ordered by `order` field."""
    db = _get_db()
    query = (
        db.collection("chapters")
        .where("subject_id", "==", subject_id)
        .order_by("order")
    )
    chapters: List[Chapter] = []
    async for doc in query.stream():
        data = doc.to_dict()
        data["id"] = doc.id
        chapters.append(Chapter(**data))
    return chapters


# ── User-level chapter selection ──────────────────────────────────────────────

async def save_selected_chapters(user_id: str, chapter_ids: List[str]) -> None:
    """Persist a user's selected chapters in Firestore."""
    db = _get_db()
    await db.collection("users").document(user_id).set(
        {"selected_chapter_ids": chapter_ids},
        merge=True,
    )
    logger.info("Saved %d selected chapters for user %s", len(chapter_ids), user_id)


async def get_selected_chapters(user_id: str) -> List[str]:
    """Retrieve a user's previously selected chapter IDs."""
    db = _get_db()
    doc = await db.collection("users").document(user_id).get()
    if not doc.exists:
        return []
    return doc.to_dict().get("selected_chapter_ids", [])


# ── Progress ──────────────────────────────────────────────────────────────────

async def get_chapter_progress(user_id: str) -> List[ChapterProgress]:
    """Return per-chapter progress for a user."""
    db = _get_db()
    col = db.collection("users").document(user_id).collection("chapter_progress")
    progress: List[ChapterProgress] = []
    async for doc in col.stream():
        data = doc.to_dict()
        data["chapter_id"] = doc.id
        progress.append(ChapterProgress(**data))
    return progress


async def update_chapter_progress(
    user_id: str,
    chapter_id: str,
    subject_id: str,
    correct: int,
    total: int,
) -> None:
    """Upsert chapter-level accuracy and question counts for a user."""
    db = _get_db()
    accuracy = round((correct / total) * 100, 2) if total else 0.0
    await (
        db.collection("users")
        .document(user_id)
        .collection("chapter_progress")
        .document(chapter_id)
        .set(
            {
                "subject_id": subject_id,
                "completed_questions": firestore.Increment(total),
                "total_questions": firestore.Increment(total),
                "accuracy": accuracy,
                "last_accessed_at": firestore.SERVER_TIMESTAMP,
            },
            merge=True,
        )
    )

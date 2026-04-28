"""
context_service.py — Semantic NCERT chapter context for the chat pipeline.

get_context(page_id, question) is called by chat.py before every LLM call.

page_id format: "safeId(subjectName)__safeId(chapterName)"
   e.g.  "Science__Electricity"  or  "Mathematics__Real_Numbers"

Resolution order:
  1. Resolve page_id → chapter_id via Firestore chapter index (in-memory cache)
  2. Query ES ncert_chunks for top-k chunks most relevant to the question
  3. Return formatted context block (grade + chapter header + relevant text)
  4. Graceful fallback: if ES unavailable, return just the chapter header
"""

from __future__ import annotations

import re
import threading
from typing import Optional

from app.core.logger import get_logger

logger = get_logger(__name__)

# ── In-process chapter index ──────────────────────────────────────────────────
# Maps normalised key → {id, subject_id, title, grade, topic_tags}
_index_lock:    threading.Lock     = threading.Lock()
_chapter_index: Optional[dict]     = None


def _normalise(text: str) -> str:
    """Lower, collapse non-alphanumeric to spaces — matches Android safeId logic."""
    return re.sub(r"[^a-z0-9]+", " ", text.strip().lower()).strip()


def _subject_grade(subject_id: str):
    parts = subject_id.rsplit("_", 1)
    return (parts[0], parts[1]) if len(parts) == 2 else (subject_id, "")


def _build_index() -> dict:
    try:
        from app.core.firebase_auth import get_firestore_db
        db = get_firestore_db()
        if db is None:
            return {}
        docs  = list(db.collection("chapters").stream())
        index = {}
        for doc in docs:
            data       = doc.to_dict() or {}
            subject_id = data.get("subject_id", "")
            title      = data.get("title", "")
            if not subject_id or not title:
                continue
            subj_slug, grade = _subject_grade(subject_id)
            for subj_key in {
                _normalise(subj_slug),
                _normalise(f"{subj_slug} {grade}"),
                _normalise(subject_id.replace("_", " ")),
            }:
                key = f"{subj_key}__{_normalise(title)}"
                index[key] = {
                    "id":         doc.id,
                    "subject_id": subject_id,
                    "title":      title,
                    "grade":      grade,
                    "topic_tags": data.get("topic_tags", ""),
                }
        logger.info("context_service: chapter index built — %d entries", len(index))
        return index
    except Exception as exc:
        logger.warning("context_service: index build failed: %s", exc)
        return {}


def _get_index() -> dict:
    global _chapter_index
    if _chapter_index is None:
        with _index_lock:
            if _chapter_index is None:
                _chapter_index = _build_index()
    return _chapter_index


def _lookup_chapter(page_id: str) -> Optional[dict]:
    if not page_id:
        return None
    parts = page_id.split("__", 1)
    if len(parts) < 2:
        return None
    subj_norm = _normalise(parts[0])
    chap_norm = _normalise(parts[1])
    direct    = f"{subj_norm}__{chap_norm}"
    idx       = _get_index()
    if direct in idx:
        return idx[direct]
    # Fuzzy: match by chapter name if subject prefix matches
    for key, val in idx.items():
        k_parts = key.split("__", 1)
        if len(k_parts) == 2 and k_parts[1] == chap_norm:
            if k_parts[0].startswith(subj_norm[:5]):
                return val
    return None


# ── ES retrieval ───────────────────────────────────────────────────────────────
def _retrieve_from_es(question: str, chapter_id: str) -> Optional[str]:
    """Call ncert_extractor.retriever — returns None if ES unavailable."""
    try:
        from ncert_extractor.retriever import retrieve_context_sync
        return retrieve_context_sync(question, chapter_id)
    except Exception as exc:
        logger.debug("context_service: ES retrieval skipped: %s", exc)
        return None


# ── Public API ────────────────────────────────────────────────────────────────
def get_context(page_id: str, question: str = "") -> str:
    """
    Return NCERT chapter context for the given page_id and student question.

    Injected into the system prompt CONTEXT block in chat.py.
    Returns "" for BB-internal page_ids (planner/intent/chunk).
    """
    if not page_id or page_id in ("", "bb_chat", "blackboard__intent", "blackboard__chunk"):
        return ""

    chapter = _lookup_chapter(page_id)
    if not chapter:
        logger.info("context_service: no chapter matched for page_id=%s", page_id)
        return ""

    chapter_id   = chapter["id"]
    grade        = chapter["grade"].replace("th","").replace("st","").replace("nd","").replace("rd","")
    title        = chapter["title"]
    topic_tags   = chapter.get("topic_tags", "")
    subject_id   = chapter["subject_id"]
    subject_name = re.sub(r"_\d+\w*$", "", subject_id).replace("_", " ").title()

    # Build header (always present)
    header_lines = [
        f"Subject: {subject_name} | Grade: {grade} (NCERT CBSE curriculum)",
        f"Chapter: {title}",
    ]
    if topic_tags:
        tags_clean = str(topic_tags).strip("[]").replace("'","").replace('"',"")
        header_lines.append(f"Key topics: {tags_clean}")
    header = "\n".join(header_lines)

    # Semantic chunk retrieval from ES
    ncert_text = None
    if question:
        ncert_text = _retrieve_from_es(question, chapter_id)

    if ncert_text:
        context = f"{header}\n\nRelevant textbook content:\n{ncert_text}"
    else:
        context = header

    logger.info(
        "context_service: page_id=%s → %s (grade %s) | es_context=%s",
        page_id, title, grade, bool(ncert_text),
    )
    return context


def invalidate_index() -> None:
    """Force chapter index rebuild — call after seeding new chapter data."""
    global _chapter_index
    with _index_lock:
        _chapter_index = None

"""
Library API – subjects, chapters, and chapter selection endpoints.
"""

from __future__ import annotations

from fastapi import APIRouter, HTTPException, Query

from app.core.logger import get_logger
from app.models.library import (
    ChaptersResponse,
    SelectChaptersRequest,
    SelectChaptersResponse,
    SubjectsResponse,
)
from app.services import library_service

logger = get_logger(__name__)
router = APIRouter(prefix="/library", tags=["library"])


@router.get("/subjects", response_model=SubjectsResponse)
async def list_subjects():
    """Return all available subjects."""
    try:
        subjects = await library_service.get_all_subjects()
        return SubjectsResponse(subjects=subjects)
    except Exception as exc:
        logger.exception("Failed to list subjects: %s", exc)
        raise HTTPException(status_code=500, detail="Could not fetch subjects.")


@router.get("/chapters", response_model=ChaptersResponse)
async def list_chapters(subject_id: str = Query(..., description="Firestore subject document ID")):
    """Return all chapters for a given subject, ordered by chapter index."""
    try:
        chapters = await library_service.get_chapters_by_subject(subject_id)
        return ChaptersResponse(subject_id=subject_id, chapters=chapters)
    except Exception as exc:
        logger.exception("Failed to list chapters: %s", exc)
        raise HTTPException(status_code=500, detail="Could not fetch chapters.")


@router.post("/select-chapters", response_model=SelectChaptersResponse)
async def select_chapters(req: SelectChaptersRequest):
    """Save the user's selected chapter IDs to Firestore."""
    try:
        await library_service.save_selected_chapters(req.user_id, req.chapter_ids)
        return SelectChaptersResponse(
            user_id=req.user_id,
            selected_chapter_ids=req.chapter_ids,
        )
    except Exception as exc:
        logger.exception("Failed to save selected chapters: %s", exc)
        raise HTTPException(status_code=500, detail="Could not save chapter selection.")


@router.get("/selected-chapters")
async def get_selected_chapters(user_id: str = Query(...)):
    """Retrieve a user's previously saved chapter selection."""
    try:
        ids = await library_service.get_selected_chapters(user_id)
        return {"user_id": user_id, "selected_chapter_ids": ids}
    except Exception as exc:
        logger.exception("Failed to retrieve selected chapters: %s", exc)
        raise HTTPException(status_code=500, detail="Could not retrieve chapter selection.")


@router.get("/progress")
async def get_progress(user_id: str = Query(...)):
    """Return per-chapter progress for a user."""
    try:
        progress = await library_service.get_chapter_progress(user_id)
        return {"user_id": user_id, "progress": [p.model_dump() for p in progress]}
    except Exception as exc:
        logger.exception("Failed to retrieve progress: %s", exc)
        raise HTTPException(status_code=500, detail="Could not retrieve progress.")

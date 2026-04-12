"""
Pydantic models for the Library system (Subjects, Chapters, Selection).
"""

from __future__ import annotations

from typing import List, Optional
from pydantic import BaseModel, Field


# ── Firestore document shapes ─────────────────────────────────────────────────

class Subject(BaseModel):
    id: str
    name: str
    icon_url: Optional[str] = None
    grade: Optional[str] = None        # e.g. "9th", "10th"
    description: Optional[str] = None
    chapter_count: int = 0


class Chapter(BaseModel):
    id: str
    subject_id: str
    title: str
    order: int = 0                     # display order within subject
    description: Optional[str] = None
    topic_tags: List[str] = Field(default_factory=list)
    estimated_minutes: Optional[int] = None


class ChapterProgress(BaseModel):
    chapter_id: str
    subject_id: str
    completed_questions: int = 0
    total_questions: int = 0
    accuracy: float = 0.0              # 0–100 %
    last_accessed_at: Optional[str] = None  # ISO-8601


# ── Request / Response shapes ─────────────────────────────────────────────────

class SelectChaptersRequest(BaseModel):
    user_id: str
    chapter_ids: List[str] = Field(..., min_length=1)


class SelectChaptersResponse(BaseModel):
    user_id: str
    selected_chapter_ids: List[str]
    message: str = "Chapters saved successfully."


class SubjectsResponse(BaseModel):
    subjects: List[Subject]


class ChaptersResponse(BaseModel):
    subject_id: str
    chapters: List[Chapter]

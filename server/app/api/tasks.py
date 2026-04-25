"""
Student-Teacher Tasks API

Teacher assigns tasks to students (or whole school); students view and complete them.
Tasks live in school_tasks/{taskId}.

Collections:
  school_tasks/{taskId}   – task definition assigned by teacher
  task_submissions/{taskId}/submissions/{uid} – student submission/completion record
"""

from __future__ import annotations

import time
import uuid
from typing import Optional

from fastapi import APIRouter, Depends, HTTPException
from pydantic import BaseModel

from app.core.auth import require_auth, AuthUser
from app.core.firebase_auth import get_firestore_db
from app.core.logger import get_logger

logger = get_logger(__name__)
router = APIRouter(prefix="/tasks", tags=["tasks"])


def _now_ms() -> int:
    return int(time.time() * 1000)


# ── Models ────────────────────────────────────────────────────────────────────

class CreateTaskRequest(BaseModel):
    title: str
    description: str
    subject: str
    topic: str = ""
    due_date_ms: int = 0
    school_id: str
    target_grade: str = ""   # empty = all grades
    credits_reward: int = 10


class SubmitTaskRequest(BaseModel):
    task_id: str
    answer: str = ""
    notes: str = ""


# ── Endpoints ─────────────────────────────────────────────────────────────────

@router.post("/assign")
async def assign_task(
    req: CreateTaskRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Teacher creates a task assigned to students in their school.
    The caller must have schoolId matching req.school_id (or be admin).
    """
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    task_id = str(uuid.uuid4())[:12]
    now = _now_ms()
    doc = {
        "taskId": task_id,
        "title": req.title,
        "description": req.description,
        "subject": req.subject,
        "topic": req.topic,
        "due_date_ms": req.due_date_ms,
        "school_id": req.school_id,
        "target_grade": req.target_grade,
        "credits_reward": max(0, req.credits_reward),
        "assigned_by": auth.uid,
        "created_at": now,
        "status": "active",
    }

    try:
        db.collection("school_tasks").document(task_id).set(doc)
    except Exception as exc:
        logger.warning("assign_task uid=%s: %s", auth.uid, exc)
        raise HTTPException(500, "Failed to create task")

    return {"task_id": task_id, "ok": True}


@router.get("/student")
async def get_student_tasks(
    school_id: str,
    grade: str = "",
    auth: AuthUser = Depends(require_auth),
):
    """
    Return active tasks for a student in the given school/grade.
    Also annotates each task with the student's submission status.
    """
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    uid = auth.uid
    now = _now_ms()

    try:
        query = (
            db.collection("school_tasks")
            .where("school_id", "==", school_id)
            .where("status", "==", "active")
            .order_by("created_at", direction="DESCENDING")
            .limit(50)
        )
        docs = query.get()
    except Exception as exc:
        logger.warning("get_student_tasks uid=%s: %s", uid, exc)
        raise HTTPException(500, "Failed to fetch tasks")

    # Filter by grade client-side (avoids composite index requirement)
    tasks = []
    for d in docs:
        data = d.to_dict() or {}
        tg = data.get("target_grade", "")
        if tg and grade and tg != grade:
            continue
        # Check for expiry
        due = data.get("due_date_ms", 0)
        if due and due < now:
            continue
        tasks.append({"id": d.id, **data})

    if not tasks:
        return {"tasks": []}

    # Batch-fetch submissions for this student
    task_ids = [t["id"] for t in tasks]
    submitted = set()
    try:
        for task_id in task_ids:
            sub = (
                db.collection("task_submissions")
                .document(task_id)
                .collection("submissions")
                .document(uid)
                .get()
            )
            if sub.exists:
                submitted.add(task_id)
    except Exception:
        pass

    for t in tasks:
        t["completed"] = t["id"] in submitted

    return {"tasks": tasks}


@router.post("/submit")
async def submit_task(
    req: SubmitTaskRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Student submits/completes a task.
    Idempotent — re-submission updates the answer but does not re-award credits.
    """
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    uid = auth.uid
    task_ref = db.collection("school_tasks").document(req.task_id)

    try:
        task_doc = task_ref.get()
    except Exception as exc:
        raise HTTPException(500, f"Failed to load task: {exc}")

    if not task_doc.exists:
        raise HTTPException(404, "Task not found")

    task_data = task_doc.to_dict() or {}
    sub_ref = (
        db.collection("task_submissions")
        .document(req.task_id)
        .collection("submissions")
        .document(uid)
    )

    already_done = False
    try:
        sub_doc = sub_ref.get()
        already_done = sub_doc.exists
    except Exception:
        pass

    now = _now_ms()
    sub_ref.set({
        "uid": uid,
        "task_id": req.task_id,
        "answer": req.answer,
        "notes": req.notes,
        "submitted_at": now,
        "status": "submitted",
    }, merge=True)

    if already_done:
        return {"ok": True, "credited": False, "message": "Already submitted"}

    # Award credits on first submission
    credits = int(task_data.get("credits_reward", 0))
    if credits > 0:
        _award_credits(
            db=db,
            uid=uid,
            amount=credits,
            tx_type="task_completion",
            source_id=req.task_id,
            description=f"Task: {task_data.get('title', '')[:60]}",
        )

    return {
        "ok": True,
        "credited": credits > 0,
        "credits_earned": credits,
        "message": f"+{credits} credits!" if credits > 0 else "Submitted!",
    }


@router.get("/teacher")
async def get_teacher_tasks(
    school_id: str,
    auth: AuthUser = Depends(require_auth),
):
    """
    Return tasks created by this teacher with submission counts.
    """
    db = get_firestore_db()
    if db is None:
        raise HTTPException(503, "Database unavailable")

    try:
        docs = (
            db.collection("school_tasks")
            .where("school_id", "==", school_id)
            .where("assigned_by", "==", auth.uid)
            .order_by("created_at", direction="DESCENDING")
            .limit(50)
            .get()
        )
    except Exception as exc:
        raise HTTPException(500, f"Failed to fetch tasks: {exc}")

    tasks = []
    for d in docs:
        data = d.to_dict() or {}
        # Count submissions
        try:
            subs = (
                db.collection("task_submissions")
                .document(d.id)
                .collection("submissions")
                .stream()
            )
            submission_count = sum(1 for _ in subs)
        except Exception:
            submission_count = 0
        tasks.append({"id": d.id, **data, "submission_count": submission_count})

    return {"tasks": tasks}


# ── Shared credit helper (mirrors daily_questions.py) ─────────────────────────

def _award_credits(
    db,
    uid: str,
    amount: int,
    tx_type: str,
    source_id: str = "",
    description: str = "",
) -> None:
    if amount <= 0:
        return
    from google.cloud.firestore import Increment
    try:
        db.collection("user_credits").document(uid).set(
            {
                "balance": Increment(amount),
                "lifetime_earned": Increment(amount),
                "last_updated": _now_ms(),
            },
            merge=True,
        )
        db.collection("credit_transactions").document().set(
            {
                "uid": uid,
                "amount": amount,
                "type": tx_type,
                "source_id": source_id,
                "description": description,
                "created_at": _now_ms(),
            }
        )
    except Exception as exc:
        logger.warning("_award_credits uid=%s: %s", uid, exc)

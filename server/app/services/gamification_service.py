"""
Gamification Service – manages score, streak, and accuracy in Firestore.
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Optional
from google.cloud import firestore

from app.core.logger import get_logger
from app.models.quiz import UserStats
from app.services.library_service import _get_db

logger = get_logger(__name__)


# ── Public helpers ────────────────────────────────────────────────────────────

async def get_user_stats(user_id: str) -> UserStats:
    """Fetch the gamification snapshot for a user (returns defaults if missing)."""
    db = _get_db()
    doc = await db.collection("users").document(user_id).get()
    if not doc.exists:
        return UserStats(user_id=user_id)
    data = doc.to_dict()
    return UserStats(
        user_id=user_id,
        total_score=data.get("total_score", 0),
        quizzes_attempted=data.get("quizzes_attempted", 0),
        accuracy=data.get("accuracy", 0.0),
        streak=data.get("streak", 0),
        last_quiz_date=data.get("last_quiz_date"),
    )


async def record_attempt(
    user_id: str,
    score_delta: int,
    correct_count: int,
    total_questions: int,
) -> UserStats:
    """
    Update total_score, quizzes_attempted, accuracy and streak for *user_id*.
    Returns the refreshed UserStats.
    """
    db = _get_db()
    today_str = date.today().isoformat()

    # Read current stats
    stats = await get_user_stats(user_id)

    # Recalculate running accuracy
    prev_correct = round(stats.accuracy / 100 * stats.quizzes_attempted * 10)  # approximate
    new_correct_total = prev_correct + correct_count
    new_q_total = stats.quizzes_attempted * 10 + total_questions  # approximate denominator
    new_accuracy = round((new_correct_total / new_q_total) * 100, 2) if new_q_total else 0.0

    # Streak logic
    new_streak = _compute_streak(stats.last_quiz_date, today_str, stats.streak)

    updates = {
        "total_score": firestore.Increment(score_delta),
        "quizzes_attempted": firestore.Increment(1),
        "accuracy": new_accuracy,
        "streak": new_streak,
        "last_quiz_date": today_str,
    }
    await db.collection("users").document(user_id).set(updates, merge=True)
    logger.info("Updated gamification for user=%s score_delta=%d", user_id, score_delta)
    return await get_user_stats(user_id)


def _compute_streak(last_date_str: Optional[str], today_str: str, current_streak: int) -> int:
    """
    Increment streak if last quiz was yesterday, reset if gap > 1 day,
    keep same if already played today.
    """
    if not last_date_str:
        return 1
    try:
        last = date.fromisoformat(last_date_str)
        today = date.fromisoformat(today_str)
        delta = (today - last).days
        if delta == 0:
            return current_streak          # already played today
        if delta == 1:
            return current_streak + 1     # consecutive day
        return 1                          # streak broken
    except ValueError:
        return 1

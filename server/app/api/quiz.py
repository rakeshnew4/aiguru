"""
Quiz API – quiz generation, answer submission, evaluation and user stats.
"""

from __future__ import annotations

import uuid
from datetime import datetime, timezone
from typing import List, Union

from fastapi import APIRouter, HTTPException, Query

from app.core.logger import get_logger
from app.models.quiz import (
    AttemptResult,
    EvaluateAnswerRequest,
    EvaluateAnswerResponse,
    GenerateQuizRequest,
    GenerateQuizResponse,
    QuestionResult,
    QuestionType,
    QuizMode,
    SubmitQuizRequest,
    UserStats,
)
from app.services import evaluation_service, gamification_service, quiz_service
from app.services.library_service import _get_db, update_chapter_progress
from google.cloud import firestore

logger = get_logger(__name__)
router = APIRouter(prefix="/quiz", tags=["quiz"])


# ── Generate ──────────────────────────────────────────────────────────────────

@router.post("/generate", response_model=GenerateQuizResponse)
async def generate_quiz(req: GenerateQuizRequest):
    """
    Generate a new quiz using the LLM and persist it in Firestore.
    Returned quiz_id is used when submitting answers.
    """
    try:
        quiz = await quiz_service.generate_quiz(
            subject=req.subject,
            chapter_id=req.chapter_id,
            chapter_title=req.chapter_title,
            difficulty=req.difficulty,
            mode=req.mode,
            question_types=req.question_types,
            count=req.count,
        )
    except RuntimeError as exc:
        logger.error("Quiz generation failed: %s", exc)
        raise HTTPException(status_code=502, detail=str(exc))
    except Exception as exc:
        logger.exception("Unexpected error during quiz generation: %s", exc)
        raise HTTPException(status_code=500, detail="Quiz generation failed.")

    # Persist quiz document — non-fatal: a Firestore/network failure must not
    # prevent the user from receiving the generated quiz.
    try:
        db = _get_db()
        await db.collection("quizzes").document(quiz.id).set(quiz.model_dump())
        logger.info("Quiz %s persisted to Firestore", quiz.id)
    except Exception as exc:
        logger.warning("Quiz %s could not be saved to Firestore (non-fatal): %s", quiz.id, exc)

    return GenerateQuizResponse(quiz_id=quiz.id, quiz=quiz)


# ── Evaluate short answer (standalone endpoint) ───────────────────────────────

@router.post("/evaluate-answer", response_model=EvaluateAnswerResponse)
async def evaluate_answer(req: EvaluateAnswerRequest):
    """Evaluate a single short-answer response using the LLM."""
    try:
        return await evaluation_service.evaluate_short_answer(
            question=req.question,
            user_answer=req.user_answer,
            expected_keywords=req.expected_keywords,
            sample_answer=req.sample_answer,
        )
    except Exception as exc:
        logger.exception("Evaluation error: %s", exc)
        raise HTTPException(status_code=500, detail="Evaluation failed.")


# ── Submit quiz ───────────────────────────────────────────────────────────────

@router.post("/submit", response_model=AttemptResult)
async def submit_quiz(req: SubmitQuizRequest):
    """
    Grade a quiz submission:
    - MCQ / fill-blank: rule-based
    - Short answer: LLM evaluation
    Then update Firestore attempt + gamification stats.
    """
    db = _get_db()

    # Load quiz from Firestore
    quiz_doc = await db.collection("quizzes").document(req.quiz_id).get()
    if not quiz_doc.exists:
        raise HTTPException(status_code=404, detail="Quiz not found.")

    quiz_data = quiz_doc.to_dict()
    questions_by_id = {q["id"]: q for q in quiz_data.get("questions", [])}

    question_results: List[QuestionResult] = []
    total_score = 0

    for answer_item in req.answers:
        q = questions_by_id.get(answer_item.question_id)
        if not q:
            logger.warning("Unknown question_id %s in submission", answer_item.question_id)
            continue

        q_type = answer_item.question_type
        user_ans = answer_item.user_answer
        correct_ans: Union[str, List[str]] = q.get("correct_answer", q.get("correct_answers", ""))

        is_correct = False
        feedback = ""
        improved = ""

        if q_type == QuestionType.MCQ:
            is_correct = evaluation_service.check_mcq(str(user_ans), str(correct_ans))
            if not is_correct:
                feedback = q.get("explanation", "Review this concept again.")

        elif q_type == QuestionType.FILL_BLANK_TYPED:
            user_list = user_ans if isinstance(user_ans, list) else [str(user_ans)]
            correct_list = correct_ans if isinstance(correct_ans, list) else [str(correct_ans)]
            is_correct = evaluation_service.check_fill_blank_typed(user_list, correct_list)
            if not is_correct:
                feedback = q.get("explanation", "Check your spelling and try again.")

        elif q_type == QuestionType.FILL_BLANK_DRAG:
            user_list = user_ans if isinstance(user_ans, list) else [str(user_ans)]
            correct_list = correct_ans if isinstance(correct_ans, list) else [str(correct_ans)]
            is_correct = evaluation_service.check_fill_blank_drag(user_list, correct_list)
            if not is_correct:
                feedback = q.get("explanation", "Review the concept and try again.")

        elif q_type == QuestionType.SHORT_ANSWER:
            eval_result = await evaluation_service.evaluate_short_answer(
                question=q["question"],
                user_answer=str(user_ans),
                expected_keywords=q.get("expected_keywords", []),
                sample_answer=q.get("sample_answer", ""),
            )
            is_correct = eval_result.score >= 2  # score 2–3 = correct
            feedback = eval_result.feedback
            improved = eval_result.improved_answer

        # Score delta
        if is_correct:
            delta = 1
        elif req.mode == QuizMode.CHALLENGE:
            delta = -1
        else:
            delta = 0

        total_score += delta
        question_results.append(
            QuestionResult(
                question_id=answer_item.question_id,
                question_type=q_type,
                user_answer=user_ans,
                correct_answer=correct_ans,
                is_correct=is_correct,
                score_delta=delta,
                feedback=feedback,
                improved_answer=improved,
            )
        )

    correct_count = sum(1 for r in question_results if r.is_correct)
    wrong_count = len(question_results) - correct_count
    accuracy = round((correct_count / len(question_results)) * 100, 2) if question_results else 0.0

    # AI insights: collect topics from chapter
    chapter_title = quiz_data.get("chapter_title", "")
    strong = [chapter_title] if accuracy >= 70 else []
    weak = [chapter_title] if accuracy < 50 else []

    attempt_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc).isoformat()

    attempt = AttemptResult(
        attempt_id=attempt_id,
        user_id=req.user_id,
        quiz_id=req.quiz_id,
        mode=req.mode,
        difficulty=req.difficulty,
        question_results=question_results,
        total_score=total_score,
        correct_count=correct_count,
        wrong_count=wrong_count,
        accuracy=accuracy,
        time_taken_seconds=req.time_taken_seconds,
        created_at=now,
        strong_topics=strong,
        weak_topics=weak,
    )

    # Persist attempt
    await db.collection("attempts").document(attempt_id).set(attempt.model_dump())

    # Update gamification
    await gamification_service.record_attempt(
        user_id=req.user_id,
        score_delta=total_score,
        correct_count=correct_count,
        total_questions=len(question_results),
    )

    # Update chapter progress
    await update_chapter_progress(
        user_id=req.user_id,
        chapter_id=quiz_data.get("chapter_id", ""),
        subject_id=quiz_data.get("subject", ""),
        correct=correct_count,
        total=len(question_results),
    )

    return attempt


# ── User stats ────────────────────────────────────────────────────────────────

@router.get("/stats", response_model=UserStats)
async def get_user_stats(user_id: str = Query(...)):
    """Return gamification snapshot for a user."""
    try:
        return await gamification_service.get_user_stats(user_id)
    except Exception as exc:
        logger.exception("Failed to get user stats: %s", exc)
        raise HTTPException(status_code=500, detail="Could not fetch user stats.")


@router.get("/attempts")
async def get_user_attempts(
    user_id: str = Query(...),
    limit: int = Query(default=10, le=50),
):
    """Return the latest quiz attempts for a user."""
    try:
        db = _get_db()
        query = (
            db.collection("attempts")
            .where("user_id", "==", user_id)
            .order_by("created_at", direction=firestore.Query.DESCENDING)
            .limit(limit)
        )
        attempts = []
        async for doc in query.stream():
            attempts.append(doc.to_dict())
        return {"user_id": user_id, "attempts": attempts}
    except Exception as exc:
        logger.exception("Failed to get attempts: %s", exc)
        raise HTTPException(status_code=500, detail="Could not fetch attempts.")

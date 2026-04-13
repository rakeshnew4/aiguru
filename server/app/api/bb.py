"""
bb.py — Blackboard interactive quiz grading endpoint.

POST /bb/grade
    Evaluates a student's open-ended answer against a model answer using LLM.
    Returns { correct, score, feedback } so the Android client can show instant
    personalised feedback without the student seeing the model answer first.
"""
import json
import logging
import re
from typing import List

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from app.core.auth import require_auth, AuthUser
from app.services.llm_service import generate_response

logger = logging.getLogger(__name__)
router = APIRouter(prefix="/bb", tags=["blackboard"])


class GradeRequest(BaseModel):
    question: str
    student_answer: str
    model_answer: str
    keywords: List[str] = []


class GradeResponse(BaseModel):
    correct: bool
    score: int          # 0–100
    feedback: str       # 1-sentence encouragement or correction


@router.post("/grade", response_model=GradeResponse)
async def grade_answer(
    req: GradeRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Uses an LLM to evaluate the student's answer and return structured feedback.
    Falls back to keyword-matching if the LLM call fails.
    """
    if not req.student_answer.strip():
        return GradeResponse(correct=False, score=0, feedback="No answer given.")

    keywords_hint = (
        f"\nKey concepts that should appear: {', '.join(req.keywords)}"
        if req.keywords else ""
    )
    prompt = f"""You are an encouraging teacher grading a student's short answer.

Question: {req.question}
Model answer: {req.model_answer}{keywords_hint}
Student's answer: {req.student_answer}

Evaluate strictly but kindly. Return ONLY valid JSON (no extra text, no code fences):
{{"correct": true/false, "score": 0-100, "feedback": "one encouraging sentence, max 20 words"}}

Rules:
- correct=true  if the student captured the main idea (>=70% accuracy)
- correct=false if key concepts are missing or the answer is wrong
- score: 0-100 proportional to accuracy
- feedback: brief, warm, specific — mention what was right or what was missed"""

    try:
        result = generate_response(prompt=prompt, tier="faster")
        response_text = result.get("text", "")
        match = re.search(r'\{.*\}', response_text, re.DOTALL)
        if match:
            data = json.loads(match.group())
            return GradeResponse(
                correct  = bool(data.get("correct", False)),
                score    = max(0, min(100, int(data.get("score", 0)))),
                feedback = str(data.get("feedback", "")).strip()[:200],
            )
    except Exception as e:
        logger.warning("LLM grading failed, falling back to keyword check: %s", e)

    return _keyword_grade(req.student_answer, req.model_answer, req.keywords)


def _keyword_grade(answer: str, model_answer: str, keywords: List[str]) -> GradeResponse:
    lower = answer.lower()
    if not keywords:
        correct = len(answer.split()) >= 4
        return GradeResponse(
            correct  = correct,
            score    = 60 if correct else 10,
            feedback = "Answer noted — verify with your teacher." if correct else "Please write a complete answer.",
        )
    matched = sum(1 for kw in keywords if kw.lower() in lower)
    ratio   = matched / len(keywords)
    if ratio >= 0.75:
        return GradeResponse(correct=True,  score=85, feedback="Great answer! You got the key points. 🎉")
    if ratio >= 0.40:
        return GradeResponse(correct=False, score=45, feedback=f"Partially right. Key idea: {model_answer[:80]}")
    return GradeResponse(correct=False, score=10, feedback=f"Not quite. The answer is: {model_answer[:80]}")



class GradeRequest(BaseModel):
    question: str
    student_answer: str
    model_answer: str
    keywords: List[str] = []


class GradeResponse(BaseModel):
    correct: bool
    score: int          # 0–100
    feedback: str       # 1-sentence encouragement or correction


@router.post("/grade", response_model=GradeResponse)
async def grade_answer(
    req: GradeRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Uses an LLM to evaluate the student's answer and return structured feedback.
    Falls back to keyword-matching if the LLM call fails.
    """
    if not req.student_answer.strip():
        return GradeResponse(correct=False, score=0, feedback="No answer given.")

    # Build a tight grading prompt
    keywords_hint = (
        f"\nKey concepts that should appear: {', '.join(req.keywords)}"
        if req.keywords else ""
    )
    prompt = f"""You are an encouraging teacher grading a student's short answer.

Question: {req.question}
Model answer: {req.model_answer}{keywords_hint}
Student's answer: {req.student_answer}

Evaluate strictly but kindly. Return ONLY valid JSON (no extra text, no code fences):
{{"correct": true/false, "score": 0-100, "feedback": "one encouraging sentence, max 20 words"}}

Rules:
- correct=true  if the student captured the main idea (>=70% accuracy)
- correct=false if key concepts are missing or the answer is wrong
- score: 0-100 proportional to accuracy
- feedback: brief, warm, specific — mention what was right or what was missed
"""

    try:
        response_text = await call_llm(
            prompt=prompt,
            system="You are a concise grading assistant. Return only valid JSON.",
            tier="faster",          # use fast/cheap model for grading
            max_tokens=120,
            temperature=0.3,
        )
        # Parse JSON from response
        import json, re
        match = re.search(r'\{.*\}', response_text, re.DOTALL)
        if match:
            data = json.loads(match.group())
            return GradeResponse(
                correct  = bool(data.get("correct", False)),
                score    = max(0, min(100, int(data.get("score", 0)))),
                feedback = str(data.get("feedback", "")).strip()[:200],
            )
    except Exception as e:
        logger.warning("LLM grading failed, falling back to keyword check: %s", e)

    # ── Keyword fallback ──────────────────────────────────────────────────────
    return _keyword_grade(req.student_answer, req.model_answer, req.keywords)


def _keyword_grade(answer: str, model_answer: str, keywords: List[str]) -> GradeResponse:
    lower   = answer.lower()
    if not keywords:
        correct = len(answer.split()) >= 4
        return GradeResponse(
            correct  = correct,
            score    = 60 if correct else 10,
            feedback = "Answer noted — verify with your teacher." if correct else "Please write a complete answer.",
        )
    matched = sum(1 for kw in keywords if kw.lower() in lower)
    ratio   = matched / len(keywords)
    if ratio >= 0.75:
        return GradeResponse(correct=True,  score=85, feedback="Great answer! You got the key points. 🎉")
    if ratio >= 0.40:
        return GradeResponse(correct=False, score=45, feedback=f"Partially right. Key idea: {model_answer[:80]}")
    return         GradeResponse(correct=False, score=10, feedback=f"Not quite. The answer is: {model_answer[:80]}")

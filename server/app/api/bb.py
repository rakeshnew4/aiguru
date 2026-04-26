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

import asyncio

from app.core.auth import require_auth, AuthUser
from app.services.llm_service import generate_response
from app.services import user_service

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

    prompt = (
        f"Grade this student's answer. Return ONLY valid JSON, nothing else.\n\n"
        f"Question: {req.question}\n"
        f"Model answer: {req.model_answer}\n"
        + (f"Key concepts: {', '.join(req.keywords)}\n" if req.keywords else "")
        + f"Student's answer: {req.student_answer}\n\n"
        'Return: {{"correct":true/false,"score":0-100,"feedback":"one warm sentence ≤20 words"}}\n'
        "correct=true if student captured main idea (≥70%). score proportional to accuracy."
    )

    try:
        result = generate_response(prompt=prompt, tier="faster", call_name="bb_grading")
        response_text = result.get("text", "")
        # Track tokens from grading call
        tokens = result.get("tokens", {})
        if tokens:
            asyncio.get_event_loop().run_in_executor(
                None, user_service.record_tokens,
                auth.uid,
                tokens.get("inputTokens", 0),
                tokens.get("outputTokens", 0),
                tokens.get("totalTokens", 0),
            )
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

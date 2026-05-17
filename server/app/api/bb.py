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
        result = generate_response(prompt=prompt, tier="faster", call_name="bb_grading", uid=auth.uid)
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


class DoubtRequest(BaseModel):
    question: str
    speech_context: str = ""    # recent lesson TTS text for context
    step_title: str = ""
    lesson_topic: str = ""
    student_level: int = 7
    language_tag: str = "en-US"


class DoubtResponse(BaseModel):
    answer: str
    answer_speech: str   # plain text, no markdown — safe for TTS
    follow_up: str       # short re-engagement phrase (≤10 words)


@router.post("/doubt_solve", response_model=DoubtResponse)
async def solve_doubt(
    req: DoubtRequest,
    auth: AuthUser = Depends(require_auth),
):
    """
    Answers a student's spoken doubt during a Blackboard lesson.
    Returns structured JSON so the Android client can display + speak the answer.
    """
    context_snippet = req.speech_context[:2000] if req.speech_context else ""

    # Language instruction: detect the student's language from the question and respond in kind.
    # Support mixed scripts: Hinglish (Hindi+English), Tenglish (Telugu+English), etc.
    lang_note = (
        "Detect the language of the student's question. "
        "If it is Hindi or a mix of Hindi and English (Hinglish), reply in Hinglish (natural mix of Hindi and English). "
        "If it is Telugu or a mix of Telugu and English (Tenglish), reply in Tenglish. "
        "If it is Tamil mixed with English, reply in that mix. "
        "For any other Indian language mixed with English, reply in that natural mix. "
        "If the question is in pure English, reply in English. "
        "Keep the answer conversational, like a friendly teacher."
    )

    prompt = (
        f"A student (grade {req.student_level}) is watching a lesson. "
        + (f"The lesson content so far: \"{context_snippet}\". " if context_snippet else "")
        + f"Student's doubt: \"{req.question}\"\n\n"
        f"{lang_note}\n"
        f"Answer in 2-3 clear sentences. "
        'Return ONLY valid JSON: {{"answer":"...","answer_speech":"...","follow_up":"..."}}\n'
        '"answer" may use markdown. '
        '"answer_speech" must be plain text with no markdown or symbols, suitable for TTS. '
        '"follow_up" is 1 short sentence (max 10 words).'
    )

    try:
        result = generate_response(prompt=prompt, tier="faster", call_name="bb_doubt", uid=auth.uid)
        text   = result.get("text", "")
        tokens = result.get("tokens", {})
        if tokens:
            asyncio.get_event_loop().run_in_executor(
                None, user_service.record_tokens,
                auth.uid,
                tokens.get("inputTokens", 0),
                tokens.get("outputTokens", 0),
                tokens.get("totalTokens", 0),
            )
        m = re.search(r'\{.*\}', text, re.DOTALL)
        if m:
            d = json.loads(m.group())
            return DoubtResponse(
                answer       = str(d.get("answer", text)).strip(),
                answer_speech= str(d.get("answer_speech", d.get("answer", text))).strip(),
                follow_up    = str(d.get("follow_up", "Ready to continue?")).strip(),
            )
    except Exception as e:
        logger.warning("doubt_solve LLM failed: %s", e)

    return DoubtResponse(
        answer       = "Sorry, I couldn't process that right now. Let's continue the lesson.",
        answer_speech= "Sorry, I could not process that right now. Let us continue.",
        follow_up    = "Ready to continue?",
    )


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

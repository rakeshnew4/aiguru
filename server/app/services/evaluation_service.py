"""
Evaluation Service – LLM-powered scoring for short-answer questions,
plus rule-based grading for MCQ and fill-in-the-blank.
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import re
from difflib import SequenceMatcher
from functools import partial
from typing import List

from app.core.logger import get_logger
from app.models.quiz import EvalResult, EvaluateAnswerResponse
from app.services.llm_service import generate_response
from app.services import cache_service
# from app.services.prompt_service import EVAL_SYSTEM_PROMPT

logger = get_logger(__name__)


# ── Fuzzy matching helper (for typed fill-in-the-blank) ───────────────────────
EVAL_SYSTEM_PROMPT = """You are a helpful and precise assistant for evaluating student short-answer responses."""
def _fuzzy_match(user: str, expected: str, threshold: float = 0.80) -> bool:
    """Return True if user's answer is close enough to the expected answer."""
    ratio = SequenceMatcher(None, user.strip().lower(), expected.strip().lower()).ratio()
    return ratio >= threshold


def check_fill_blank_typed(user_answers: List[str], correct_answers: List[str]) -> bool:
    """Grade a fill-in-the-blank typed response using fuzzy matching."""
    if len(user_answers) != len(correct_answers):
        return False
    return all(_fuzzy_match(u, c) for u, c in zip(user_answers, correct_answers))


def check_fill_blank_drag(user_answers: List[str], correct_answers: List[str]) -> bool:
    """Grade a drag-and-drop response (exact match per slot)."""
    if len(user_answers) != len(correct_answers):
        return False
    return all(u.strip().lower() == c.strip().lower() for u, c in zip(user_answers, correct_answers))


def check_mcq(user_answer: str, correct_answer: str) -> bool:
    return user_answer.strip().lower() == correct_answer.strip().lower()


# ── Short-answer LLM evaluation ───────────────────────────────────────────────

def _build_eval_prompt(
    question: str,
    user_answer: str,
    expected_keywords: List[str],
    sample_answer: str,
) -> str:
    keywords_str = ", ".join(f'"{k}"' for k in expected_keywords)
    return f"""Evaluate the following student answer.

Question: {question}

Student Answer: {user_answer}

Expected Keywords (at least some should appear): [{keywords_str}]

Sample Correct Answer: {sample_answer}

Scoring rubric:
- 3: Fully correct — covers all key concepts clearly
- 2: Mostly correct — minor gaps or imprecision
- 1: Partially correct — some key concepts present but significant gaps
- 0: Incorrect or completely off-topic

Return ONLY this JSON (no extra text):
{{
  "score": <0|1|2|3>,
  "result": "<correct|partial|incorrect>",
  "feedback": "<1-2 sentence encouraging feedback>",
  "improved_answer": "<ideal model answer in 1-3 sentences>"
}}"""


async def evaluate_short_answer(
    question: str,
    user_answer: str,
    expected_keywords: List[str],
    sample_answer: str,
) -> EvaluateAnswerResponse:
    """
    Use the LLM to score a student's short-answer response.
    Falls back to a keyword-count heuristic if LLM fails.
    """
    # ── Fast path: heuristic for obvious correct / incorrect cases ────────────
    answer_lower = user_answer.lower()
    hits = sum(1 for kw in expected_keywords if kw.lower() in answer_lower)
    ratio = hits / max(len(expected_keywords), 1)

    if ratio >= 0.85:
        return EvaluateAnswerResponse(
            score=3,
            result=EvalResult.CORRECT,
            feedback="Excellent! You've covered all the key concepts perfectly.",
            improved_answer=sample_answer,
        )
    if ratio < 0.10 and len(user_answer.strip()) < 30:
        return EvaluateAnswerResponse(
            score=0,
            result=EvalResult.INCORRECT,
            feedback="Review the key concepts from this chapter and try again!",
            improved_answer=sample_answer,
        )

    # ── Redis cache: same question + same answer = same score ─────────────────
    cache_key = hashlib.md5(f"{question}::{user_answer}".encode()).hexdigest()
    cached = cache_service.get_cache(page_id="eval_short", question=cache_key)
    if cached:
        try:
            return EvaluateAnswerResponse(**cached)
        except Exception:
            pass  # stale or malformed cache entry — fall through to LLM

    prompt = _build_eval_prompt(
        question, user_answer, expected_keywords, sample_answer
    )

    loop = asyncio.get_event_loop()
    for attempt in range(3):
        try:
            result = await loop.run_in_executor(
                None,
                partial(generate_response, prompt, [], "cheaper", system_prompt=EVAL_SYSTEM_PROMPT),
            )
            raw: str = result["text"]
            cleaned = re.sub(r"```(?:json)?", "", raw).strip().rstrip("`").strip()
            data = json.loads(cleaned)
            response = EvaluateAnswerResponse(
                score=int(data["score"]),
                result=EvalResult(data["result"]),
                feedback=data["feedback"],
                improved_answer=data["improved_answer"],
            )
            # Write to Redis for future identical (question, answer) pairs
            try:
                cache_service.set_cache(
                    page_id="eval_short",
                    question=cache_key,
                    value=response.model_dump(),
                )
            except Exception:
                pass  # cache write failure is non-fatal
            return response
        except (json.JSONDecodeError, KeyError, ValueError) as exc:
            logger.warning("Evaluation parse attempt %d failed: %s", attempt + 1, exc)

    # Heuristic fallback (LLM failed all 3 attempts)
    answer_lower = user_answer.lower()
    hits = sum(1 for kw in expected_keywords if kw.lower() in answer_lower)
    ratio = hits / max(len(expected_keywords), 1)
    if ratio >= 0.8:
        score, result = 3, EvalResult.CORRECT
    elif ratio >= 0.5:
        score, result = 2, EvalResult.PARTIAL
    elif ratio >= 0.2:
        score, result = 1, EvalResult.PARTIAL
    else:
        score, result = 0, EvalResult.INCORRECT

    return EvaluateAnswerResponse(
        score=score,
        result=result,
        feedback="Keep practising! Review the key concepts from this chapter.",
        improved_answer=sample_answer,
    )

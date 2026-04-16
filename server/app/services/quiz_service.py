"""
Quiz Generation Service – uses the configured LLM to produce structured quiz JSON.
"""

from __future__ import annotations

import asyncio
import json
import re
import uuid
from functools import partial
from typing import Any, Dict, List

from app.core.logger import get_logger
from app.models.quiz import (
    AnyQuestion,
    Difficulty,
    FillBlankDragQuestion,
    FillBlankTypedQuestion,
    MCQQuestion,
    Quiz,
    QuizMode,
    QuestionType,
    ShortAnswerQuestion,
)
from app.services.llm_service import generate_response
from app.services import cache_service

logger = get_logger(__name__)


# ── Prompt builder ─────────────────────────────────────────────────────────────

def _build_prompt(
    subject: str,
    chapter_title: str,
    difficulty: Difficulty,
    question_types: List[QuestionType],
    count: int,
    context_text: str = "",
) -> str:
    type_descriptions = {
        QuestionType.MCQ: (
            'type="mcq", question, options (array of 2–4 strings), '
            'correct_answer (one of the options), explanation'
        ),
        QuestionType.FILL_BLANK_TYPED: (
            'type="fill_blank_typed", question (use ___ for blanks), '
            'correct_answers (array of 1–2 word strings), hints (optional array), explanation'
        ),
        QuestionType.FILL_BLANK_DRAG: (
            'type="fill_blank_drag", question (use ___ for blanks), '
            'blanks_count (1–3), correct_answers (ordered array), '
            'draggable_options (3–5 strings including distractors), explanation'
        ),
        QuestionType.SHORT_ANSWER: (
            'type="short_answer", question, expected_keywords (array of key concepts), '
            'sample_answer (1–3 sentences), explanation'
        ),
    }
    types_instructions = "\n".join(
        f"- {type_descriptions[qt]}"
        for qt in question_types
        if qt in type_descriptions
    )

    context_section = ""
    if context_text.strip():
        context_section = f"""

Base your questions PRIMARILY on the following conversation/content excerpts:
---
{context_text.strip()[:8000]}
---
"""

    return f"""You are an expert educational content creator for school students.

Generate exactly {count} quiz questions for:
- Subject: {subject}
- Chapter: {chapter_title}
- Difficulty: {difficulty.value}{context_section}

Question types to include (mix them proportionally):
{types_instructions}

Rules:
1. Each question MUST have a unique "id" field (e.g. "q1", "q2", …).
2. Questions must be appropriate for the {difficulty.value} difficulty level.
3. Explanations should be 1–2 sentences, educational, and encouraging.
4. For MCQ, wrong options should be plausible (not obviously wrong).
5. For fill-in-the-blank, blanks should test meaningful vocabulary or concepts.
6. For short-answer, expected_keywords should be the key curriculum concepts.

Return ONLY valid JSON in this exact format (no markdown, no extra text):
{{
  "questions": [
    {{ ...question object... }},
    ...
  ]
}}"""


# ── JSON parser ────────────────────────────────────────────────────────────────

def _extract_json(raw: str) -> Dict[str, Any]:
    """Strip markdown code fences and parse JSON."""
    cleaned = re.sub(r"```(?:json)?", "", raw).strip().rstrip("`").strip()
    return json.loads(cleaned)


def _parse_question(raw: Dict[str, Any], idx: int) -> AnyQuestion:
    """Convert a raw dict from the LLM into the correct typed question model."""
    q_type = raw.get("type", "mcq")
    raw.setdefault("id", f"q{idx + 1}")

    if q_type == QuestionType.MCQ:
        return MCQQuestion(**raw)
    if q_type == QuestionType.FILL_BLANK_TYPED:
        return FillBlankTypedQuestion(**raw)
    if q_type == QuestionType.FILL_BLANK_DRAG:
        return FillBlankDragQuestion(**raw)
    if q_type == QuestionType.SHORT_ANSWER:
        return ShortAnswerQuestion(**raw)

    # Unknown type → fall back to MCQ structure if possible
    logger.warning("Unknown question type '%s'; falling back to MCQ", q_type)
    raw["type"] = "mcq"
    raw.setdefault("options", [])
    raw.setdefault("correct_answer", "")
    raw.setdefault("explanation", "")
    return MCQQuestion(**raw)


# ── Public API ────────────────────────────────────────────────────────────────

async def generate_quiz(
    subject: str,
    chapter_id: str,
    chapter_title: str,
    difficulty: Difficulty,
    mode: QuizMode,
    question_types: List[QuestionType],
    count: int,
    context_text: str = "",
) -> Quiz:
    """
    Call the LLM to produce a structured quiz and return a validated Quiz object.
    Results are Redis-cached by (chapter_id, difficulty, question_types, count)
    so repeated identical requests skip the LLM call entirely.
    Retries up to 2 times on parse failure before raising.
    """
    # Build a cache key using the request fingerprint
    cache_key_question = f"{chapter_id}:{difficulty.value}:{','.join(sorted(t.value for t in question_types))}:{count}"
    cached = cache_service.get_cache(page_id=chapter_id, question=cache_key_question)
    if cached:
        logger.info("Quiz cache HIT for chapter=%s", chapter_id)
        try:
            questions = [_parse_question(q, i) for i, q in enumerate(cached["questions"])]
            return Quiz(
                id=str(uuid.uuid4()),
                subject=subject,
                chapter_id=chapter_id,
                chapter_title=chapter_title,
                difficulty=difficulty,
                mode=mode,
                question_count=len(questions),
                questions=questions,
            )
        except Exception as cache_exc:
            logger.warning("Failed to deserialise cached quiz: %s", cache_exc)

    prompt = _build_prompt(subject, chapter_title, difficulty, question_types, count, context_text)
    system_prefix = (
        "You are a quiz generator. Always respond with valid JSON only. "
        "No prose, no markdown, just raw JSON.\n\n"
    )
    full_prompt = system_prefix + prompt

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            loop = asyncio.get_event_loop()
            result = await loop.run_in_executor(
                None,
                partial(generate_response, full_prompt, [], "cheaper"),
            )
            raw_text: str = result["text"]
            parsed = _extract_json(raw_text)
            questions: List[AnyQuestion] = [
                _parse_question(q, i)
                for i, q in enumerate(parsed["questions"])
            ]
            quiz_id = str(uuid.uuid4())

            # Cache the raw question list (not the full Quiz with a fresh id)
            cache_service.set_cache(
                page_id=chapter_id,
                question=cache_key_question,
                value={"questions": parsed["questions"]},
            )

            return Quiz(
                id=quiz_id,
                subject=subject,
                chapter_id=chapter_id,
                chapter_title=chapter_title,
                difficulty=difficulty,
                mode=mode,
                question_count=len(questions),
                questions=questions,
            )
        except (json.JSONDecodeError, KeyError, ValueError) as exc:
            last_error = exc
            logger.warning("Quiz parse attempt %d failed: %s", attempt + 1, exc)

    raise RuntimeError(
        f"Failed to generate a valid quiz after 3 attempts: {last_error}"
    )

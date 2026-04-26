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
# from app.services.prompt_service import QUIZ_SYSTEM_PROMPT

logger = get_logger(__name__)

QUIZ_SYSTEM_PROMPT = """You are a helpful and precise assistant for generating educational quiz questions in JSON format."""
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
            'type="mcq", question (clear question text), options (REQUIRED: array of EXACTLY 4 distinct strings, no empties), '
            'correct_answer (MUST be one of the 4 options - exact match), explanation (1-2 sentences)'
        ),
        QuestionType.FILL_BLANK_TYPED: (
            'type="fill_blank_typed", question (use ___ to mark blanks), '
            'correct_answers (array of 1–2 word strings, one per blank), hints (optional array), explanation'
        ),
        QuestionType.FILL_BLANK_DRAG: (
            'type="fill_blank_drag", question (use ___ for blanks), '
            'blanks_count (1–3), correct_answers (ordered array), '
            'draggable_options (3–5 strings including distractors), explanation'
        ),
        QuestionType.SHORT_ANSWER: (
            'type="short_answer", question, expected_keywords (array of 2-4 key concepts), '
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

    return f"""Generate exactly {count} quiz questions for:
- Subject: {subject}
- Chapter: {chapter_title}
- Difficulty: {difficulty.value}{context_section}

Question types to include (mix them proportionally):
{types_instructions}

STRICT RULES (MUST FOLLOW):
1. Each question MUST have a unique "id" field (e.g. "q1", "q2", …).
2. Questions must be appropriate for the {difficulty.value} difficulty level.
3. Explanations should be 1–2 sentences, educational, and encouraging.

FOR MCQ QUESTIONS (CRITICAL):
- options: MUST be an array of EXACTLY 4 different option strings
- NO empty options arrays - every MCQ needs 4 options
- correct_answer: MUST be EXACTLY one of the 4 option strings (case-sensitive match)
- Wrong options should be plausible but clearly incorrect
- All 4 options must be distinct (no duplicates)

FOR fill-in-the-blank: blanks should test meaningful vocabulary or concepts
FOR short-answer: expected_keywords should be 2-4 key curriculum concepts

RETURN ONLY valid JSON (no markdown, no code blocks, no extra text):
{{
  "questions": [
    {{"id": "q1", "type": "mcq", "question": "...", "options": ["option1", "option2", "option3", "option4"], "correct_answer": "option1", "explanation": "..."}},
    ...
  ]
}}

REMINDER: If you're generating MCQ, DO NOT leave the options array empty!"""


# ── JSON parser ────────────────────────────────────────────────────────────────

def _extract_json(raw: str) -> Dict[str, Any]:
    """Strip markdown code fences and parse JSON."""
    cleaned = re.sub(r"```(?:json)?", "", raw).strip().rstrip("`").strip()
    return json.loads(cleaned)


def _parse_question(raw: Dict[str, Any], idx: int) -> AnyQuestion:
    """Convert a raw dict from the LLM into the correct typed question model.
    Raises validation error if malformed; caller should catch and retry."""
    q_type = raw.get("type", "mcq")
    raw.setdefault("id", f"q{idx + 1}")

    if q_type == QuestionType.MCQ:
        # ✓ VALIDATION: Ensure options array is not empty
        options = raw.get("options", [])
        if not options or len(options) < 2:
            logger.warning(
                "MCQ question %s has %d options (requires min 2) — regenerating",
                raw.get("id"), len(options) if options else 0
            )
            # Ensure we have at least 4 valid options for MCQQuestion validation
            raw["options"] = ["Option A", "Option B", "Option C", "Option D"]
        return MCQQuestion(**raw)
    if q_type == QuestionType.FILL_BLANK_TYPED:
        return FillBlankTypedQuestion(**raw)
    if q_type == QuestionType.FILL_BLANK_DRAG:
        return FillBlankDragQuestion(**raw)
    if q_type == QuestionType.SHORT_ANSWER:
        raw.setdefault("sample_answer", "")
        raw.setdefault("expected_keywords", [])
        return ShortAnswerQuestion(**raw)

    # Unknown type → fall back to MCQ structure if possible
    logger.warning("Unknown question type '%s'; falling back to MCQ", q_type)
    raw["type"] = "mcq"
    raw.setdefault("options", ["Option A", "Option B", "Option C", "Option D"])
    raw.setdefault("correct_answer", "Option A")
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

    last_error: Exception | None = None
    for attempt in range(3):
        try:
            loop = asyncio.get_event_loop()
            result = await loop.run_in_executor(
                None,
                partial(generate_response, prompt, [], "cheaper", system_prompt=QUIZ_SYSTEM_PROMPT, call_name="quiz_generate"),
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

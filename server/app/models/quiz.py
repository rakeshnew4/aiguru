"""
Pydantic models for the Quiz system.
Covers quiz structure, question types, submissions and results.
"""

from __future__ import annotations

from enum import Enum
from typing import Any, Dict, List, Literal, Optional, Union
from pydantic import BaseModel, Field


# ── Enumerations ──────────────────────────────────────────────────────────────

class QuizMode(str, Enum):
    NORMAL = "normal"        # +1 correct, 0 wrong
    CHALLENGE = "challenge"  # +1 correct, -1 wrong


class Difficulty(str, Enum):
    EASY = "easy"
    MEDIUM = "medium"
    HARD = "hard"


class QuestionType(str, Enum):
    MCQ = "mcq"
    FILL_BLANK_TYPED = "fill_blank_typed"
    FILL_BLANK_DRAG = "fill_blank_drag"
    SHORT_ANSWER = "short_answer"


class EvalResult(str, Enum):
    CORRECT = "correct"
    PARTIAL = "partial"
    INCORRECT = "incorrect"


# ── Question shapes ───────────────────────────────────────────────────────────

class MCQQuestion(BaseModel):
    type: Literal["mcq"] = "mcq"
    id: str
    question: str
    options: List[str] = Field(..., min_length=2, max_length=4)
    correct_answer: str                    # exact string from options
    explanation: str = ""


class FillBlankTypedQuestion(BaseModel):
    type: Literal["fill_blank_typed"] = "fill_blank_typed"
    id: str
    question: str                          # use ___ to mark blanks
    correct_answers: List[str]             # 1–2 words per blank
    hints: List[str] = Field(default_factory=list)
    explanation: str = ""


class FillBlankDragQuestion(BaseModel):
    type: Literal["fill_blank_drag"] = "fill_blank_drag"
    id: str
    question: str                          # use ___ to mark blanks
    blanks_count: int = Field(..., ge=1, le=3)
    correct_answers: List[str]             # in order of blanks
    draggable_options: List[str] = Field(..., min_length=3, max_length=5)
    explanation: str = ""


class ShortAnswerQuestion(BaseModel):
    type: Literal["short_answer"] = "short_answer"
    id: str
    question: str
    expected_keywords: List[str]           # key concepts to look for
    sample_answer: str                     # reference answer for LLM eval
    explanation: str = ""


# Union type used in quiz documents
AnyQuestion = Union[
    MCQQuestion,
    FillBlankTypedQuestion,
    FillBlankDragQuestion,
    ShortAnswerQuestion,
]


# ── Quiz document ─────────────────────────────────────────────────────────────

class Quiz(BaseModel):
    id: str
    subject: str
    chapter_id: str
    chapter_title: str
    difficulty: Difficulty
    mode: QuizMode
    question_count: int
    questions: List[AnyQuestion]
    created_at: Optional[str] = None       # ISO-8601


# ── API Request / Response shapes ─────────────────────────────────────────────

class GenerateQuizRequest(BaseModel):
    user_id: str
    subject: str
    chapter_id: str
    chapter_title: str
    difficulty: Difficulty = Difficulty.MEDIUM
    mode: QuizMode = QuizMode.NORMAL
    question_types: List[QuestionType] = Field(
        default_factory=lambda: [QuestionType.MCQ]
    )
    count: int = Field(default=10, ge=1, le=20)
    # Optional: when provided, the LLM uses this text as the primary source
    # for question generation instead of the chapter title alone.
    # Used by teachers generating quizzes from selected chat messages.
    context_text: str = Field(default="", max_length=12000)


class GenerateQuizResponse(BaseModel):
    quiz_id: str
    quiz: Quiz


# ── Answer submission ─────────────────────────────────────────────────────────

class AnswerItem(BaseModel):
    question_id: str
    question_type: QuestionType
    user_answer: Union[str, List[str]]     # string for MCQ/typed; list for drag/short


class SubmitQuizRequest(BaseModel):
    user_id: str
    quiz_id: str
    mode: QuizMode
    difficulty: Difficulty
    answers: List[AnswerItem]
    time_taken_seconds: int = 0


# ── Short-answer evaluation ───────────────────────────────────────────────────

class EvaluateAnswerRequest(BaseModel):
    question: str
    user_answer: str
    expected_keywords: List[str]
    sample_answer: str


class EvaluateAnswerResponse(BaseModel):
    score: int = Field(..., ge=0, le=3)    # 0 = incorrect … 3 = fully correct
    result: EvalResult
    feedback: str
    improved_answer: str


# ── Per-question result ───────────────────────────────────────────────────────

class QuestionResult(BaseModel):
    question_id: str
    question_type: QuestionType
    user_answer: Union[str, List[str]]
    correct_answer: Union[str, List[str]]
    is_correct: bool
    score_delta: int                       # +1 or 0 / -1 depending on mode
    feedback: str = ""
    improved_answer: str = ""


# ── Attempt / Result document ─────────────────────────────────────────────────

class AttemptResult(BaseModel):
    attempt_id: str
    user_id: str
    quiz_id: str
    mode: QuizMode
    difficulty: Difficulty
    question_results: List[QuestionResult]
    # Aggregates
    total_score: int
    correct_count: int
    wrong_count: int
    accuracy: float                        # 0–100 %
    time_taken_seconds: int
    created_at: str                        # ISO-8601
    # AI insights
    strong_topics: List[str] = Field(default_factory=list)
    weak_topics: List[str] = Field(default_factory=list)


# ── User gamification snapshot ────────────────────────────────────────────────

class UserStats(BaseModel):
    user_id: str
    total_score: int = 0
    quizzes_attempted: int = 0
    accuracy: float = 0.0
    streak: int = 0
    last_quiz_date: Optional[str] = None  # ISO-8601 date string

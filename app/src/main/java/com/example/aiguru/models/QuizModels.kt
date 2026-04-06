package com.example.aiguru.models

import org.json.JSONArray
import org.json.JSONObject

// ── Enums ─────────────────────────────────────────────────────────────────────

enum class Difficulty(val label: String, val value: String) {
    EASY("Easy", "easy"),
    MEDIUM("Medium", "medium"),
    HARD("Hard", "hard")
}

enum class QuestionType(val label: String, val value: String) {
    MCQ("Multiple Choice", "mcq"),
    FILL_BLANK_TYPED("Fill in the Blank", "fill_blank_typed"),
    SHORT_ANSWER("Short Answer", "short_answer")
}

// ── Question sealed class ──────────────────────────────────────────────────────

sealed class QuizQuestion {
    abstract val id: String
    abstract val type: String
    abstract val question: String
    abstract val explanation: String

    data class MCQ(
        override val id: String,
        override val question: String,
        override val explanation: String,
        val options: List<String>,
        val correctAnswer: String
    ) : QuizQuestion() {
        override val type = "mcq"
    }

    data class FillBlankTyped(
        override val id: String,
        override val question: String,       // contains ___ for blanks
        override val explanation: String,
        val correctAnswers: List<String>,
        val hints: List<String> = emptyList()
    ) : QuizQuestion() {
        override val type = "fill_blank_typed"
    }

    data class ShortAnswer(
        override val id: String,
        override val question: String,
        override val explanation: String,
        val expectedKeywords: List<String>,
        val sampleAnswer: String
    ) : QuizQuestion() {
        override val type = "short_answer"
    }
}

// ── Quiz container ─────────────────────────────────────────────────────────────

data class Quiz(
    val id: String,
    val subject: String,
    val chapterId: String,
    val chapterTitle: String,
    val difficulty: String,
    val questions: List<QuizQuestion>
) {
    companion object {
        fun fromJson(json: JSONObject): Quiz {
            val questionsJson = json.getJSONArray("questions")
            val questions = mutableListOf<QuizQuestion>()
            for (i in 0 until questionsJson.length()) {
                parseQuestion(questionsJson.getJSONObject(i))?.let { questions.add(it) }
            }
            return Quiz(
                id          = json.optString("id", ""),
                subject     = json.optString("subject", ""),
                chapterId   = json.optString("chapter_id", ""),
                chapterTitle = json.optString("chapter_title", ""),
                difficulty  = json.optString("difficulty", "easy"),
                questions   = questions
            )
        }

        private fun parseQuestion(obj: JSONObject): QuizQuestion? {
            val id = obj.optString("id", "")
            val type = obj.optString("type", "mcq")
            val question = obj.optString("question", "")
            val explanation = obj.optString("explanation", "")

            return when (type) {
                "mcq" -> {
                    val opts = obj.optJSONArray("options")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList()
                    QuizQuestion.MCQ(id, question, explanation, opts,
                        obj.optString("correct_answer", ""))
                }
                "fill_blank_typed" -> {
                    val answers = obj.optJSONArray("correct_answers")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList()
                    val hints = obj.optJSONArray("hints")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList()
                    QuizQuestion.FillBlankTyped(id, question, explanation, answers, hints)
                }
                "short_answer" -> {
                    val keywords = obj.optJSONArray("expected_keywords")
                        ?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                        ?: emptyList()
                    QuizQuestion.ShortAnswer(id, question, explanation, keywords,
                        obj.optString("sample_answer", ""))
                }
                else -> null
            }
        }
    }
}

// ── Per-answer record ──────────────────────────────────────────────────────────

data class QuizAnswer(
    val questionId: String,
    val questionType: String,
    val userAnswer: String,          // for MCQ and short answer
    val userAnswerList: List<String> = emptyList(), // for fill_blank_typed
    var isCorrect: Boolean = false,
    var score: Int = 0,              // 0-3 for short answer
    var feedback: String = ""
)

// ── Result ─────────────────────────────────────────────────────────────────────

data class QuizResult(
    val quiz: Quiz,
    val answers: List<QuizAnswer>,
    val correctCount: Int,
    val totalCount: Int,
    val scorePercent: Int,
    val timeTakenSeconds: Long
) {
    val incorrectCount: Int get() = totalCount - correctCount
}

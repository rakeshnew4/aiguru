package com.aiguruapp.student.quiz

import android.util.Log
import com.aiguruapp.student.auth.TokenManager
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.http.HttpClientManager
import com.aiguruapp.student.models.Quiz
import com.aiguruapp.student.models.QuizAnswer
import com.aiguruapp.student.models.QuizQuestion
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OkHttp3-based client for the FastAPI quiz & library endpoints.
 * All methods are blocking — call from Dispatchers.IO / lifecycleScope.
 */
class QuizApiClient(
    private val baseUrl: String = AdminConfigRepository.effectiveServerUrl()
) {
    companion object {
        private const val TAG = "QuizApiClient"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    // Use singleton HTTP client (connection pooling + reuse)
    private val http = HttpClientManager.standardClient

    /** Adds Firebase ID token as Bearer header; call from a background thread. */
    private fun Request.Builder.addFirebaseAuth(): Request.Builder {
        val header = TokenManager.buildAuthHeader()
        if (header != null) header("Authorization", header)
        return this
    }

    // ── Quiz generation ────────────────────────────────────────────────────────

    /**
     * POST /quiz/generate → Quiz
     * @throws Exception on network or parse failure
     */
    fun generateQuiz(
        subject: String,
        chapterId: String,
        chapterTitle: String,
        difficulty: String,
        questionTypes: List<String>,
        count: Int,
        userId: String,
        contextText: String = ""
    ): Quiz {
        val typesArray = JSONArray().apply { questionTypes.forEach { put(it) } }
        val body = JSONObject().apply {
            put("subject", subject)
            put("chapter_id", chapterId)
            put("chapter_title", chapterTitle)
            put("difficulty", difficulty)
            put("question_types", typesArray)
            put("count", count)
            put("user_id", userId)
            if (contextText.isNotBlank()) put("context_text", contextText)
        }.toString().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url("$baseUrl/quiz/generate")
            .addFirebaseAuth()
            .post(body)
            .build()

        val response = http.newCall(request).execute()
        val raw = response.body?.string() ?: throw Exception("Empty response from server")
        if (!response.isSuccessful) {
            Log.e(TAG, "generateQuiz HTTP ${response.code}: $raw")
            throw Exception("Server error ${response.code}: $raw")
        }
        // The endpoint returns { quiz_id: "...", quiz: { id, questions, ... } }
        val responseJson = JSONObject(raw)
        val quizJson = responseJson.getJSONObject("quiz")
        return Quiz.fromJson(quizJson)
    }

    // ── Short-answer evaluation ────────────────────────────────────────────────

    /**
     * POST /quiz/evaluate-answer → { score: 0-3, result: ..., feedback: ... }
     */
    fun evaluateShortAnswer(
        question: String,
        userAnswer: String,
        expectedKeywords: List<String>,
        sampleAnswer: String
    ): Triple<Int, String, String> {  // score, result, feedback
        val keywordsArray = JSONArray().apply { expectedKeywords.forEach { put(it) } }
        val body = JSONObject().apply {
            put("question", question)
            put("user_answer", userAnswer)
            put("expected_keywords", keywordsArray)
            put("sample_answer", sampleAnswer)
        }.toString().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url("$baseUrl/quiz/evaluate-answer")
            .addFirebaseAuth()
            .post(body)
            .build()

        val response = http.newCall(request).execute()
        val raw = response.body?.string() ?: return Triple(0, "wrong", "Could not evaluate.")
        if (!response.isSuccessful) {
            Log.e(TAG, "evaluateAnswer HTTP ${response.code}: $raw")
            return Triple(0, "wrong", "Evaluation failed.")
        }
        val json = JSONObject(raw)
        return Triple(
            json.optInt("score", 0),
            json.optString("result", "wrong"),
            json.optString("feedback", "")
        )
    }

    // ── Submit quiz ────────────────────────────────────────────────────────────

    /**
     * POST /quiz/submit — persists attempt + updates gamification stats.
     */
    fun submitQuiz(
        userId: String,
        quizId: String,
        chapterId: String,
        answers: List<QuizAnswer>,
        timeTakenSeconds: Long
    ) {
        val answersArray = JSONArray()
        for (a in answers) {
            answersArray.put(JSONObject().apply {
                put("question_id", a.questionId)
                put("question_type", a.questionType)
                put("user_answer", a.userAnswer)
                put("is_correct", a.isCorrect)
                put("score", a.score)
            })
        }
        val body = JSONObject().apply {
            put("user_id", userId)
            put("quiz_id", quizId)
            put("chapter_id", chapterId)
            put("answers", answersArray)
            put("time_taken_seconds", timeTakenSeconds)
        }.toString().toRequestBody(JSON_MEDIA)

        val request = Request.Builder()
            .url("$baseUrl/quiz/submit")
            .addFirebaseAuth()
            .post(body)
            .build()

        try {
            val response = http.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(TAG, "submitQuiz HTTP ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "submitQuiz failed silently: ${e.message}")
            // Non-fatal — result screen still shows even if submit fails
        }
    }

    // ── Grading helpers (client-side, instant) ─────────────────────────────────

    /**
     * Grade MCQ immediately on the client — no network call needed.
     */
    fun gradeMcq(userAnswer: String, correctAnswer: String): Boolean =
        userAnswer.trim().equals(correctAnswer.trim(), ignoreCase = true)

    /**
     * Grade fill-blank with simple fuzzy matching (Levenshtein similarity ≥ 80%).
     */
    fun gradeFillBlank(userAnswers: List<String>, correctAnswers: List<String>): Boolean {
        if (userAnswers.size != correctAnswers.size) return false
        return userAnswers.zip(correctAnswers).all { (u, c) ->
            similarityRatio(u.trim().lowercase(), c.trim().lowercase()) >= 0.80
        }
    }

    private fun similarityRatio(a: String, b: String): Double {
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length).toDouble()
        if (maxLen == 0.0) return 1.0
        return (maxLen - levenshtein(a, b)) / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                else 1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
        return dp[a.length][b.length]
    }
}

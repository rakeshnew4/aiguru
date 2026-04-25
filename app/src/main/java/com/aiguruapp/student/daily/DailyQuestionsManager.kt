package com.aiguruapp.student.daily

import android.content.Context
import android.content.SharedPreferences
import com.aiguruapp.student.auth.TokenManager
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.http.HttpClientManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class DailyQuestion(
    val id: String,
    val question: String,
    val subject: String,
    val topic: String,
    val difficulty: Int,
    val hook: String,
    val status: String,
    val creditsReward: Int,
    val date: String,
)

/**
 * Thin HTTP client for /daily-questions/* endpoints.
 * All network calls are blocking — run on a background thread or in a coroutine's IO dispatcher.
 */
object DailyQuestionsManager {

    private const val PREF_NAME = "daily_questions_cache"
    private const val KEY_DATE = "cache_date"
    private const val KEY_DATA = "cache_data"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // ── Feed ─────────────────────────────────────────────────────────────────

    fun fetchFeed(context: Context): List<DailyQuestion> {
        val token = TokenManager.buildAuthHeader() ?: return emptyList()
        val url = "${AdminConfigRepository.effectiveServerUrl()}/daily-questions/feed"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", token)
            .build()

        return try {
            val resp = HttpClientManager.standardClient.newCall(request).execute()
            val body = resp.body?.string() ?: return emptyList()
            if (!resp.isSuccessful) return emptyList()
            val json = JSONObject(body)
            val arr = json.optJSONArray("questions") ?: return emptyList()
            val today = json.optString("date", "")
            val result = parseQuestions(arr, today)
            saveCache(context, today, body)
            result
        } catch (e: Exception) {
            loadCache(context)
        }
    }

    // ── Complete ─────────────────────────────────────────────────────────────

    fun completeQuestion(questionId: String, bbSessionId: String = ""): Boolean {
        val token = TokenManager.buildAuthHeader() ?: return false
        val url = "${AdminConfigRepository.effectiveServerUrl()}/daily-questions/complete"

        val body = JSONObject().apply {
            put("question_id", questionId)
            put("bb_session_id", bbSessionId)
        }.toString().toRequestBody(JSON_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", token)
            .build()

        return try {
            val resp = HttpClientManager.standardClient.newCall(request).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ── Interests ─────────────────────────────────────────────────────────────

    fun recordInterest(subject: String, topics: List<String>): Boolean {
        val token = TokenManager.buildAuthHeader() ?: return false
        val url = "${AdminConfigRepository.effectiveServerUrl()}/daily-questions/interests"

        val body = JSONObject().apply {
            put("subject", subject)
            put("topics", JSONArray(topics))
        }.toString().toRequestBody(JSON_TYPE)

        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", token)
            .build()

        return try {
            val resp = HttpClientManager.standardClient.newCall(request).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ── Credits balance ───────────────────────────────────────────────────────

    fun fetchCreditBalance(): Int {
        val token = TokenManager.buildAuthHeader() ?: return 0
        val url = "${AdminConfigRepository.effectiveServerUrl()}/credits/balance"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", token)
            .build()

        return try {
            val resp = HttpClientManager.standardClient.newCall(request).execute()
            val body = resp.body?.string() ?: return 0
            if (!resp.isSuccessful) return 0
            JSONObject(body).optInt("balance", 0)
        } catch (e: Exception) {
            0
        }
    }

    // ── Cache helpers ─────────────────────────────────────────────────────────

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    private fun saveCache(context: Context, date: String, raw: String) {
        prefs(context).edit().putString(KEY_DATE, date).putString(KEY_DATA, raw).apply()
    }

    private fun loadCache(context: Context): List<DailyQuestion> {
        val p = prefs(context)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        if (p.getString(KEY_DATE, "") != today) return emptyList()
        val raw = p.getString(KEY_DATA, null) ?: return emptyList()
        return try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("questions") ?: return emptyList()
            parseQuestions(arr, today)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun parseQuestions(arr: JSONArray, today: String): List<DailyQuestion> {
        val list = mutableListOf<DailyQuestion>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            list.add(
                DailyQuestion(
                    id = o.optString("id"),
                    question = o.optString("question"),
                    subject = o.optString("subject"),
                    topic = o.optString("topic"),
                    difficulty = o.optInt("difficulty", 1),
                    hook = o.optString("hook"),
                    status = o.optString("status", "pending"),
                    creditsReward = o.optInt("credits_reward", 5),
                    date = o.optString("date", today),
                )
            )
        }
        return list
    }
}

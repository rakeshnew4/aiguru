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
 * Combined quota status — one call replaces separate balance + session-count fetches.
 * @property freeBbLeft   Free BB sessions remaining today (0 = exhausted, use credits)
 * @property freeBbLimit  Daily BB session allowance from plan (0 = unlimited)
 * @property freeChatLeft Free chat questions remaining today
 * @property freeChatLimit Daily chat question allowance from plan (0 = unlimited)
 * @property creditBalance Current chat/LLM credit balance (100 tokens = 1 credit)
 * @property ttsCredits   Current TTS credit balance (1 char = 1 credit)
 */
data class QuotaStatus(
    val freeBbLeft: Int,
    val freeBbLimit: Int,
    val freeChatLeft: Int,
    val freeChatLimit: Int,
    val creditBalance: Int,
    val ttsCredits: Int = 0,
)

/**
 * Credit top-up pack — purchased via the Razorpay flow with planId="topup_<id>"
 * to grant additional credits on top of the user's plan allowance.
 */
data class TopupPack(
    val id: String,            // e.g. "topup_500"
    val name: String,          // "500 Credits"
    val credits: Int,
    val bonusCredits: Int,
    val priceInr: Int,
    val description: String,
    val popular: Boolean,
) {
    val totalCredits: Int get() = credits + bonusCredits
}

/**
 * Thin HTTP client for /daily-questions/ endpoints.
 * All network calls are blocking — run on a background thread or in a coroutine's IO dispatcher.
 */
object DailyQuestionsManager {

    private const val PREF_NAME = "daily_questions_cache"
    private const val KEY_DATE = "cache_date"
    private const val KEY_DATA = "cache_data"
    private const val KEY_LAST_INTEREST_DATE = "last_interest_date"

    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()

    // ── Feed ─────────────────────────────────────────────────────────────────

    fun fetchFeed(context: Context): List<DailyQuestion> {
        // Return today's cached questions immediately — no network call needed.
        // Cache is populated on the first fetch of the day and invalidated at midnight.
        val cached = loadCache(context)
        if (cached.isNotEmpty()) return cached

        // Cache miss (new day or first launch) — fetch from server.
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
            emptyList()
        }
    }

    /**
     * Mark a question as completed in the local cache so the UI reflects the
     * correct status even before the next server fetch.
     */
    fun markCachedCompleted(context: Context, questionId: String) {
        val p = prefs(context)
        val raw = p.getString(KEY_DATA, null) ?: return
        try {
            val json = JSONObject(raw)
            val arr = json.optJSONArray("questions") ?: return
            for (i in 0 until arr.length()) {
                val q = arr.optJSONObject(i) ?: continue
                if (q.optString("id") == questionId) {
                    q.put("status", "completed")
                    break
                }
            }
            p.edit().putString(KEY_DATA, json.toString()).apply()
        } catch (e: Exception) { /* ignore */ }
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

    /**
     * Record the student's subject/topic interest after a BB session.
     * Rate-limited to one server call per day — subsequent calls return true silently.
     */
    fun recordInterest(context: Context, subject: String, topics: List<String>): Boolean {
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val p = prefs(context)
        if (p.getString(KEY_LAST_INTEREST_DATE, "") == today) return true  // already sent today

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
            if (resp.isSuccessful) {
                p.edit().putString(KEY_LAST_INTEREST_DATE, today).apply()
            }
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    // ── Top-up packs ──────────────────────────────────────────────────────────

    fun fetchTopupPacks(): List<TopupPack> {
        val token = TokenManager.buildAuthHeader() ?: return emptyList()
        val url = "${AdminConfigRepository.effectiveServerUrl()}/credits/topup-packs"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", token)
            .build()

        return try {
            val resp = HttpClientManager.standardClient.newCall(request).execute()
            val body = resp.body?.string() ?: return emptyList()
            if (!resp.isSuccessful) return emptyList()
            val arr = JSONObject(body).optJSONArray("packs") ?: return emptyList()
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                TopupPack(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    credits = o.optInt("credits"),
                    bonusCredits = o.optInt("bonus_credits"),
                    priceInr = o.optInt("price_inr"),
                    description = o.optString("description"),
                    popular = o.optBoolean("popular", false),
                )
            }
        } catch (e: Exception) {
            emptyList()
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

    /**
     * Fetch combined quota status (free sessions remaining + credit balance) in one call.
     * Returns null on network error — callers should fall back to locally-cached data.
     */
    fun fetchQuotaStatus(): QuotaStatus? {
        val token = TokenManager.buildAuthHeader() ?: return null
        val url = "${AdminConfigRepository.effectiveServerUrl()}/credits/quota-status"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Authorization", token)
            .build()

        return try {
            val resp = HttpClientManager.standardClient.newCall(request).execute()
            val body = resp.body?.string() ?: return null
            if (!resp.isSuccessful) return null
            val j = JSONObject(body)
            // free_bb_remaining / free_chat_remaining are the authoritative remaining counts.
            // Server resets them at UTC midnight or on first app open of the day.
            val bbRemaining   = j.optInt("free_bb_remaining",   0)
            val bbLimit       = j.optInt("free_bb_limit",       2)
            val chatRemaining = j.optInt("free_chat_remaining", 0)
            val chatLimit     = j.optInt("free_chat_limit",    12)
            val balance       = j.optInt("credit_balance",      0)
            val ttsBalance    = j.optInt("tts_credit_balance",  0)
            QuotaStatus(
                freeBbLeft    = bbRemaining,
                freeBbLimit   = bbLimit,
                freeChatLeft  = chatRemaining,
                freeChatLimit = chatLimit,
                creditBalance = balance,
                ttsCredits    = ttsBalance,
            )
        } catch (e: Exception) {
            null
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

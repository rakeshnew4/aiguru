package com.aiguruapp.student.config

import android.util.Log
import com.aiguruapp.student.auth.TokenManager
import com.aiguruapp.student.http.HttpClientManager
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * QuotaManager — server-only quota enforcement.
 *
 * Every check hits the server directly; there is NO local cache.
 * The server is the single source of truth.
 *
 * Credit priority (matches server logic in check_and_record_quota):
 *   1. Free daily quota (free_chat_remaining / free_bb_remaining / TTS chars)
 *   2. AI credits (user_credits balance)
 *   3. Blocked — show upgrade prompt
 *
 * Usage:
 *   QuotaManager.fetchStatus { status ->
 *       when (status.chatMode) {
 *           QuotaManager.Mode.FREE      -> proceedWithFreeQuota()
 *           QuotaManager.Mode.AI_CREDIT -> proceedAndShowCreditBanner()
 *           QuotaManager.Mode.BLOCKED   -> showUpgradeDialog(status.blockReason)
 *       }
 *   }
 */
object QuotaManager {

    private const val TAG = "QuotaManager"

    // ── Credit types ──────────────────────────────────────────────────────────
    enum class CreditType { CHAT, LESSON, TTS }

    // ── Modes (what pool will the next request consume?) ─────────────────────
    enum class Mode {
        FREE,       // within daily free allowance
        AI_CREDIT,  // free quota exhausted — will consume purchased credits
        BLOCKED     // both free and credits exhausted
    }

    // ── Full quota status snapshot ────────────────────────────────────────────
    data class QuotaStatus(
        // Free daily remaining
        val freeChatRemaining: Int,
        val freeBbRemaining: Int,
        val freeTtsCharsRemaining: Int,

        // Purchased credit balance (1 credit ≈ 100 tokens)
        val creditBalance: Int,

        // Per-type mode
        val chatMode: Mode,
        val bbMode: Mode,
        val ttsMode: Mode,

        // UI helpers
        /** True when TTS is running on paid credits — show info banner */
        val usingAiCreditsForTts: Boolean,

        // Plan info
        val planId: String,
        val planName: String,
        val planExpiryDate: Long,

        // Global maintenance
        val maintenanceMode: Boolean,
        val maintenanceMessage: String,

        // Error (null = success)
        val error: String? = null
    ) {
        val isError: Boolean get() = error != null

        /** Human-readable block reason for the given credit type */
        fun blockReason(type: CreditType): String = when (type) {
            CreditType.CHAT   -> "You've used all your free daily questions and have no AI credits. Add credits or come back tomorrow!"
            CreditType.LESSON -> "You've used all your free daily lessons and have no AI credits. Add credits or come back tomorrow!"
            CreditType.TTS    -> "You've used all your free TTS and have no AI credits for voice. Add credits to continue."
        }

        /** True if the given type is available (free or credit mode) */
        fun isAllowed(type: CreditType): Boolean = when (type) {
            CreditType.CHAT   -> chatMode != Mode.BLOCKED
            CreditType.LESSON -> bbMode   != Mode.BLOCKED
            CreditType.TTS    -> ttsMode  != Mode.BLOCKED
        }

        fun modeFor(type: CreditType): Mode = when (type) {
            CreditType.CHAT   -> chatMode
            CreditType.LESSON -> bbMode
            CreditType.TTS    -> ttsMode
        }
    }

    // ── Check result (simple gate) ────────────────────────────────────────────
    data class CheckResult(
        val allowed: Boolean,
        val mode: Mode,
        val upgradeMessage: String = ""
    )

    // ── HTTP client (short timeout — this is a pre-flight check) ─────────────
    private val http = HttpClientManager.standardClient

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch the full quota status from the server.
     * Runs on a background thread; callbacks fire on the calling thread pool.
     * Call from Dispatchers.IO.
     *
     * @param onResult  Called with the QuotaStatus (check [QuotaStatus.isError])
     * @param onError   Called on network/auth failure with an error message
     */
    fun fetchStatus(
        onResult: (QuotaStatus) -> Unit,
        onError: (String) -> Unit = {}
    ) {
        val serverUrl = AdminConfigRepository.effectiveServerUrl().trimEnd('/')
        val authHeader = TokenManager.buildAuthHeader()

        if (authHeader == null) {
            onError("Not authenticated")
            return
        }

        val request = Request.Builder()
            .url("$serverUrl/users/quota/status")
            .get()
            .header("Authorization", authHeader)
            .build()

        try {
            http.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    Log.w(TAG, "fetchStatus HTTP ${response.code}: $body")
                    onError("Server returned ${response.code}")
                    return
                }

                val status = parseStatus(body)
                onResult(status)
            }
        } catch (e: IOException) {
            Log.w(TAG, "fetchStatus network error: ${e.message}")
            onError("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "fetchStatus unexpected error", e)
            onError("Unexpected error: ${e.message}")
        }
    }

    /**
     * Simple check-and-proceed gate.
     * Fetches server status and immediately calls [onAllowed] or [onBlocked].
     *
     * @param type       Which credit type to check
     * @param onAllowed  Called with (mode) so the caller knows if credits are being used
     * @param onBlocked  Called with a human-readable upgrade message
     * @param onError    Called when the server check itself fails (e.g. offline); defaults to allowing
     */
    fun check(
        type: CreditType,
        onAllowed: (mode: Mode) -> Unit,
        onBlocked: (message: String) -> Unit,
        onError: (message: String) -> Unit = { onAllowed(Mode.FREE) }   // fail-open
    ) {
        fetchStatus(
            onResult = { status ->
                if (status.isError) {
                    onError(status.error ?: "Quota check failed")
                    return@fetchStatus
                }
                if (status.maintenanceMode) {
                    onBlocked(status.maintenanceMessage.ifBlank { "App is under maintenance. Please try again soon." })
                    return@fetchStatus
                }
                val mode = status.modeFor(type)
                if (mode == Mode.BLOCKED) {
                    onBlocked(status.blockReason(type))
                } else {
                    onAllowed(mode)
                }
            },
            onError = onError
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Parsing
    // ─────────────────────────────────────────────────────────────────────────

    private fun parseStatus(json: String): QuotaStatus {
        return try {
            val j = JSONObject(json)
            QuotaStatus(
                freeChatRemaining      = j.optInt("free_chat_remaining", 0),
                freeBbRemaining        = j.optInt("free_bb_remaining", 0),
                freeTtsCharsRemaining  = j.optInt("free_tts_chars_remaining", 0),
                creditBalance          = j.optInt("credit_balance", 0),
                chatMode               = parseMode(j.optString("chat_mode", "free")),
                bbMode                 = parseMode(j.optString("bb_mode", "free")),
                ttsMode                = parseMode(j.optString("tts_mode", "free")),
                usingAiCreditsForTts   = j.optBoolean("using_ai_credits_for_tts", false),
                planId                 = j.optString("plan_id", "free"),
                planName               = j.optString("plan_name", "Free"),
                planExpiryDate         = j.optLong("plan_expiry_date", 0L),
                maintenanceMode        = j.optBoolean("maintenance_mode", false),
                maintenanceMessage     = j.optString("maintenance_message", "")
            )
        } catch (e: Exception) {
            Log.e(TAG, "parseStatus failed: ${e.message}")
            QuotaStatus(
                freeChatRemaining = 0, freeBbRemaining = 0, freeTtsCharsRemaining = 0,
                creditBalance = 0,
                chatMode = Mode.FREE, bbMode = Mode.FREE, ttsMode = Mode.FREE,
                usingAiCreditsForTts = false,
                planId = "free", planName = "Free", planExpiryDate = 0L,
                maintenanceMode = false, maintenanceMessage = "",
                error = "Parse error: ${e.message}"
            )
        }
    }

    private fun parseMode(raw: String): Mode = when (raw.lowercase()) {
        "ai_credit" -> Mode.AI_CREDIT
        "blocked"   -> Mode.BLOCKED
        else        -> Mode.FREE
    }
}

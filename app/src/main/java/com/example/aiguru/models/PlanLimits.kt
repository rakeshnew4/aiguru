package com.example.aiguru.models

import com.google.firebase.firestore.PropertyName

/**
 * Effective limits applied to a user for one billing period.
 *
 * These values are resolved in priority order:
 *   1. users/{uid}.planLimits  (admin-set per-user override — highest priority)
 *   2. admin_config/plans/{planId}  (plan-level defaults)
 *   3. admin_config/global.defaultLimits  (fallback for any unset field)
 *
 * All Int fields of 0 mean "unlimited".
 */
data class PlanLimits(

    // ── Token budget ─────────────────────────────────────────────────────────
    /** Max total tokens the user may consume in a single calendar day. 0 = unlimited. */
    @field:PropertyName("daily_token_limit")
    val dailyTokenLimit: Int = 10_0000,

    /** Max total tokens across all sessions in a calendar month. 0 = unlimited. */
    @field:PropertyName("monthly_token_limit")
    val monthlyTokenLimit: Int = 200_000,

    // ── Conversation window ───────────────────────────────────────────────────
    /** Max messages kept in the LLM context window per session. */
    @field:PropertyName("context_window_messages")
    val contextWindowMessages: Int = 20,

    /** Max characters sent as context per request (truncate oldest messages first). */
    @field:PropertyName("context_window_chars")
    val contextWindowChars: Int = 8_000,

    // ── Feature access flags ─────────────────────────────────────────────────
    @field:PropertyName("image_upload_enabled")
    val imageUploadEnabled: Boolean = true,

    @field:PropertyName("voice_mode_enabled")
    val voiceModeEnabled: Boolean = true,

    @field:PropertyName("pdf_enabled")
    val pdfEnabled: Boolean = true,

    @field:PropertyName("flashcards_enabled")
    val flashcardsEnabled: Boolean = true,

    @field:PropertyName("conversation_summary_enabled")
    val conversationSummaryEnabled: Boolean = false,

    @field:PropertyName("blackboard_enabled")
    val blackboardEnabled: Boolean = true,

    // ── Quality / model routing ───────────────────────────────────────────────
    /**
     * Model tier key that maps to a server-side model name.
     * e.g. "standard" → gemini-flash, "advanced" → gemini-pro
     * Sent as a hint to the server; server validates it.
     */
    @field:PropertyName("model_tier")
    val modelTier: String = "standard",

    // ── Rate limiting ─────────────────────────────────────────────────────────
    /** Max messages per hour. 0 = unlimited. */
    @field:PropertyName("messages_per_hour")
    val messagesPerHour: Int = 30,

    /** Max concurrent active sessions (devices). 0 = unlimited. */
    @field:PropertyName("max_sessions")
    val maxSessions: Int = 1
)

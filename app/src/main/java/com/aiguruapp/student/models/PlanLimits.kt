package com.aiguruapp.student.models

import com.google.firebase.firestore.IgnoreExtraProperties
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
@IgnoreExtraProperties
data class PlanLimits(

    // ── Token budget ─────────────────────────────────────────────────────────
    /** Max total tokens the user may consume in a single calendar day. 0 = unlimited. */
    @field:PropertyName("daily_token_limit")
    var dailyTokenLimit: Int = 10_0000,

    /** Max total tokens across all sessions in a calendar month. 0 = unlimited. */
    @field:PropertyName("monthly_token_limit")
    var monthlyTokenLimit: Int = 200_000,

    // ── Conversation window ───────────────────────────────────────────────────
    /** Max messages kept in the LLM context window per session. */
    @field:PropertyName("context_window_messages")
    var contextWindowMessages: Int = 20,

    /** Max characters sent as context per request (truncate oldest messages first). */
    @field:PropertyName("context_window_chars")
    var contextWindowChars: Int = 8_000,

    // ── Feature access flags ─────────────────────────────────────────────────
    @field:PropertyName("image_upload_enabled")
    var imageUploadEnabled: Boolean = true,

    @field:PropertyName("voice_mode_enabled")
    var voiceModeEnabled: Boolean = true,

    @field:PropertyName("pdf_enabled")
    var pdfEnabled: Boolean = true,

    @field:PropertyName("flashcards_enabled")
    var flashcardsEnabled: Boolean = true,

    @field:PropertyName("conversation_summary_enabled")
    var conversationSummaryEnabled: Boolean = false,

    @field:PropertyName("blackboard_enabled")
    var blackboardEnabled: Boolean = true,

    // ── Quality / model routing ───────────────────────────────────────────────
    /**
     * Model tier key that maps to a server-side model name.
     * e.g. "standard" → gemini-flash, "advanced" → gemini-pro
     * Sent as a hint to the server; server validates it.
     */
    @field:PropertyName("model_tier")
    var modelTier: String = "standard",

    // ── Rate limiting ─────────────────────────────────────────────────────────
    /** Max messages per hour. 0 = unlimited. */
    @field:PropertyName("messages_per_hour")
    var messagesPerHour: Int = 30,

    /** Max concurrent active sessions (devices). 0 = unlimited. */
    @field:PropertyName("max_sessions")
    var maxSessions: Int = 1,

    // ── Question quotas ──────────────────────────────────────────────────────
    /** Max chat questions per UTC calendar day. 0 = unlimited. */
    @field:PropertyName("daily_chat_questions")
    var dailyChatQuestions: Int = 20,

    /** Max Visual Blackboard sessions per UTC calendar day. 0 = unlimited. */
    @field:PropertyName("daily_bb_sessions")
    var dailyBlackboardSessions: Int = 3,

    // ── TTS access ────────────────────────────────────────────────────────────
    /** Android built-in TTS read-aloud (available on free plan). */
    @field:PropertyName("tts_enabled")
    var ttsEnabled: Boolean = true,

    /** AI-powered server-side TTS synthesis. */
    @field:PropertyName("ai_tts_enabled")
    var aiTtsEnabled: Boolean = true,

    /** Daily AI TTS character quota. 0 = disabled. */
    @field:PropertyName("ai_tts_quota_chars")
    var aiTtsQuotaChars: Int = 0
)

package com.aiguruapp.student.models

import com.google.firebase.firestore.PropertyName

/**
 * Global admin configuration loaded from Firestore at:
 *   admin_config/global
 *
 * Changes here take effect on next app launch (cached for [cacheMaxAgeMs]).
 * Admins can push this document to change behaviour for all users instantly.
 */
data class AdminConfig(

    // ── Server / model routing ────────────────────────────────────────────────
    /**
     * Base URL for the chat server. Loaded from Firestore admin_config/global.
     * Default is a sensible fallback for first app launch before Firestore loads.
     * Firestore value will override this after the first fetch.
     */
    @field:PropertyName("server_url")
    val serverUrl: String = "http://108.181.187.227:8003",

    /** Optional API key for the server. Empty = no auth header. */
    @field:PropertyName("server_api_key")
    val serverApiKey: String = "",

    /**
     * Razorpay publishable key (rzp_live_xxx or rzp_test_xxx).
     * Loaded from Firestore so it can be rotated without an app update.
     * The server's create-order response also returns the key — this is the fallback.
     */
    @field:PropertyName("razorpay_key_id")
    val razorpayKeyId: String = "",

    /**
     * Map of model tier → actual model name string sent to the server.
     * e.g. { "standard": "gemini-2.0-flash", "advanced": "gemini-2.5-pro" }
     */
    @field:PropertyName("model_tiers")
    val modelTiers: Map<String, String> = mapOf(
        "standard" to "gemini-2.0-flash",
        "advanced" to "gemini-2.5-pro"
    ),

    // ── Global safety caps (override any plan value if lower) ────────────────
    @field:PropertyName("global_daily_token_hard_cap")
    val globalDailyTokenHardCap: Int = 50_000,

    @field:PropertyName("global_monthly_token_hard_cap")
    val globalMonthlyTokenHardCap: Int = 500_000,

    // ── Maintenance / kill-switch ─────────────────────────────────────────────
    /** If true, all users see a maintenance message and no AI requests are sent. */
    @field:PropertyName("maintenance_mode")
    val maintenanceMode: Boolean = false,

    @field:PropertyName("maintenance_message")
    val maintenanceMessage: String = "Service is under maintenance. Please try again later.",

    // ── Default limits (applied when user has no plan or plan doc missing) ────
    @field:PropertyName("default_limits")
    val defaultLimits: PlanLimits = PlanLimits(),

    // ── Feature flags (global on/off regardless of plan) ─────────────────────
    @field:PropertyName("gemini_live_enabled")
    val geminiLiveEnabled: Boolean = true,

    @field:PropertyName("flashcards_globally_enabled")
    val flashcardsGloballyEnabled: Boolean = true,

    /** How many milliseconds to cache this config in-memory before re-fetching. */
    @field:PropertyName("cache_max_age_ms")
    val cacheMaxAgeMs: Long = 60 * 60 * 1000L,  // 1 hour

    // ── AI TTS credentials (never bundle in APK — fetched from Firestore) ─────
    /** Which TTS provider to use. One of: "google", "elevenlabs", "openai", "self_hosted" */
    @field:PropertyName("tts_provider")
    val ttsProvider: String = "android",

    @field:PropertyName("tts_google_api_key")
    val ttsGoogleApiKey: String = "",

    @field:PropertyName("tts_elevenlabs_api_key")
    val ttsElevenLabsApiKey: String = "",

    @field:PropertyName("tts_openai_api_key")
    val ttsOpenAiApiKey: String = "",

    /** Used when ttsProvider == "self_hosted". Defaults to ttsServerUrl if blank. */
    @field:PropertyName("tts_server_url")
    val ttsServerUrl: String = "",

    /**
     * Firebase UID used for all guest (unauthenticated) users.
     * Loaded from admin_config/global.guest_id.
     * Defaults to the shared guest account UID.
     */
    @field:PropertyName("guest_id")
    val guestId: String = "BujsVJE2cMX6wU7Jg3acMUlRChm1"
)

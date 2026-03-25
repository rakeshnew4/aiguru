package com.example.aiguru.models

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
    /** Base URL for the chat server. Overrides any locally stored URL. */
    @field:PropertyName("server_url")
    val serverUrl: String = "http://108.181.187.227:8003",

    /** Optional API key for the server. Empty = no auth header. */
    @field:PropertyName("server_api_key")
    val serverApiKey: String = "",

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
    val cacheMaxAgeMs: Long = 60 * 60 * 1000L  // 1 hour
)

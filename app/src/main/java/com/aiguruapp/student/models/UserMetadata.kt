package com.aiguruapp.student.models

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

/**
 * User metadata stored in Firestore at users/{userId}
 */
@IgnoreExtraProperties
data class UserMetadata(
    // NOTE: @get:PropertyName annotates the GETTER method, which is what Firebase's
    // serializer calls when writing to Firestore. This is resilient even if ProGuard
    // renames the getter from getUserId() to getA(), because the annotation travels
    // with the method and Firebase reads it to determine the Firestore field name.
    @get:PropertyName("userId")
    val userId: String = "",

    @get:PropertyName("name")
    val name: String = "",

    @get:PropertyName("email")
    val email: String? = null,

    @get:PropertyName("schoolId")
    val schoolId: String = "",

    @get:PropertyName("schoolName")
    val schoolName: String = "",

    @get:PropertyName("grade")
    val grade: String = "",  // "8th", "9th", etc.

    // ── Subscription / plan ──────────────────────────────────────
    @get:PropertyName("planId")
    val planId: String = "free",

    @get:PropertyName("planName")
    val planName: String = "Free",

    /** Epoch-ms when the current paid plan was activated (0 = never subscribed). */
    @field:PropertyName("plan_start_date")
    val planStartDate: Long = 0L,

    /**
     * Epoch-ms when the current plan expires.
     * 0 = no expiry (free tier or a lifetime plan).
     * When `System.currentTimeMillis() > planExpiryDate`, the plan is treated as expired.
     */
    @field:PropertyName("plan_expiry_date")
    val planExpiryDate: Long = 0L,

    /**
     * Per-user limit overrides set by admins.
     * null = use the plan-level limits from admin_config/plans/{planId}.
     * Any non-null field in PlanLimits takes precedence over the plan defaults.
     */
    @field:PropertyName("plan_limits")
    val planLimits: PlanLimits? = null,

    // ── Token usage counters (incremented server-side via FieldValue.increment) ───
    /** Total tokens consumed today (UTC calendar day). */
    @field:PropertyName("tokens_today")
    val tokensToday: Int = 0,

    /** Input (prompt) tokens consumed today. */
    @field:PropertyName("input_tokens_today")
    val inputTokensToday: Int = 0,

    /** Output (completion) tokens consumed today. */
    @field:PropertyName("output_tokens_today")
    val outputTokensToday: Int = 0,

    /** Total tokens consumed this calendar month. */
    @field:PropertyName("tokens_this_month")
    val tokensThisMonth: Int = 0,

    /** Input (prompt) tokens consumed this month. */
    @field:PropertyName("input_tokens_this_month")
    val inputTokensThisMonth: Int = 0,

    /** Output (completion) tokens consumed this month. */
    @field:PropertyName("output_tokens_this_month")
    val outputTokensThisMonth: Int = 0,

    /** Epoch-ms of last token counter update — used to detect day/month rollover. */
    @field:PropertyName("tokens_updated_at")
    val tokensUpdatedAt: Long = 0L,

    // ── Question counters (reset each UTC calendar day) ─────────────────────
    /** Chat questions asked today (UTC). */
    @field:PropertyName("chat_questions_today")
    val chatQuestionsToday: Int = 0,

    /** Blackboard sessions started today (UTC). */
    @field:PropertyName("bb_sessions_today")
    val bbSessionsToday: Int = 0,

    /** Epoch-ms of last question counter update — used to detect day rollover. */
    @field:PropertyName("questions_updated_at")
    val questionsUpdatedAt: Long = 0L,

    // ── Model config ────────────────────────────────────────────
    @field:PropertyName("model_config")
    val modelConfig: ModelConfig? = null,

    // ── Referral ─────────────────────────────────────────────────
    /** The referral code this user claimed (blank = never referred). */
    @field:PropertyName("referredBy")
    val referredBy: String = "",

    /**
     * Extra daily questions granted via referral.
     * Incremented by [ReferralManager] for both referrer and new user.
     * Persisted in Firestore as bonus_questions_today.
     */
    @field:PropertyName("bonus_questions_today")
    val bonusQuestionsToday: Int = 0,

    // ── Stored plan quotas (written to Firestore when plan is activated) ─────
    /**
     * Daily chat question limit from the active plan.
     * Written by FirestoreManager.updateUserPlan(). 0 = fall back to admin config.
     */
    @field:PropertyName("plan_daily_chat_limit")
    val planDailyChatLimit: Int = 0,

    /** Daily blackboard session limit from the active plan. 0 = fall back to admin config. */
    @field:PropertyName("plan_daily_bb_limit")
    val planDailyBbLimit: Int = 0,

    /** Whether Android TTS read-aloud is enabled for the user's plan. */
    @field:PropertyName("plan_tts_enabled")
    val planTtsEnabled: Boolean = true,

    /** Whether AI-powered TTS synthesis is enabled for the user's plan. */
    @field:PropertyName("plan_ai_tts_enabled")
    val planAiTtsEnabled: Boolean = false,

    /** Whether the visual Blackboard mode is enabled for the user's plan. */
    @field:PropertyName("plan_blackboard_enabled")
    val planBlackboardEnabled: Boolean = true,

    /** Whether image/PDF upload is enabled for the user's plan. */
    @field:PropertyName("plan_image_enabled")
    val planImageEnabled: Boolean = true,

    @field:PropertyName("created_at")
    val createdAt: Long = 0L,

    @field:PropertyName("updated_at")
    val updatedAt: Long = 0L
)


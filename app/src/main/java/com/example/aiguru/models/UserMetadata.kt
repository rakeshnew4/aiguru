package com.example.aiguru.models

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

/**
 * User metadata stored in Firestore at users/{userId}
 */
@IgnoreExtraProperties
data class UserMetadata(
    val userId: String = "",

    val name: String = "",

    val email: String? = null,

    val schoolId: String = "",

    val schoolName: String = "",

    val grade: String = "",  // "8th", "9th", etc.

    // ── Subscription / plan ──────────────────────────────────────
    val planId: String = "free",

    val planName: String = "Free",

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

    /** Total tokens consumed this calendar month. */
    @field:PropertyName("tokens_this_month")
    val tokensThisMonth: Int = 0,

    /** Epoch-ms of last token counter update — used to detect day/month rollover. */
    @field:PropertyName("tokens_updated_at")
    val tokensUpdatedAt: Long = 0L,

    // ── Model config ────────────────────────────────────────────
    @field:PropertyName("model_config")
    val modelConfig: ModelConfig? = null,

    @field:PropertyName("created_at")
    val createdAt: Long = 0L,

    @field:PropertyName("updated_at")
    val updatedAt: Long = 0L
)


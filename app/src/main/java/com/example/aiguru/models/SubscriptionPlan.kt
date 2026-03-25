package com.example.aiguru.models

import com.google.firebase.firestore.PropertyName

/**
 * Single subscription plan definition.
 * Stored at: admin_config/plans/{planId}
 *
 * planId examples: "free", "student_basic", "student_pro", "school_unlimited"
 */
data class SubscriptionPlan(
    val planId: String = "free",

    /** Display name shown in the UI. */
    val displayName: String = "Free",

    /** Short tagline shown under the plan name. */
    val tagline: String = "Get started for free",

    /** Price string to display (purely informational, billing handled server-side). */
    val priceDisplay: String = "₹0/month",

    /** Whether this plan is publicly available for self-service sign-up. */
    val isPublic: Boolean = true,

    /** Order for display in the plans screen (lower = shown first). */
    val displayOrder: Int = 0,

    /** Highlight colour hex (for the plan card). */
    val accentColor: String = "#1565C0",

    /** The effective limits for this plan. */
    val limits: PlanLimits = PlanLimits(),

    /** Feature bullet points displayed in the upgrade screen. */
    val features: List<String> = emptyList()
)

package com.aiguruapp.student.models

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A subscription plan fetched from Firestore collection: app_plans/{planId}
 *
 * Firestore document structure (create manually):
 *   name:          "Basic"           (string)
 *   badge:         "Popular"         (string — shown as chip above plan card)
 *   price_inr:     99                (number — 0 for free)
 *   duration:      "1 Month"         (string — display label)
 *   validity_days: 30                (number — 0 = never expires, e.g. for free plan)
 *   features:      ["Feature A", …] (array of strings)
 *   display_order: 1                 (number — 0 = shown first)
 *   is_active:     true              (boolean — false hides from app)
 *   accent_color:  "#0891B2"         (string hex — card accent / highlight color)
 */
@IgnoreExtraProperties
data class FirestorePlan(
    /** Document ID in app_plans collection (e.g. "plan_free", "plan_basic"). */
    val id: String = "",

    val name: String = "",

    /** Badge text shown on the plan card (e.g. "Popular", "Best Value"). */
    val badge: String = "",

    @field:PropertyName("price_inr")
    val priceInr: Int = 0,

    /** Human-readable validity label displayed on the card (e.g. "1 Month"). */
    val duration: String = "",

    /**
     * Number of calendar days the plan stays active after purchase.
     * 0 = no expiry (free / lifetime plans).
     */
    @field:PropertyName("validity_days")
    val validityDays: Int = 30,

    val features: List<String> = emptyList(),

    @field:PropertyName("display_order")
    val displayOrder: Int = 0,

    @field:PropertyName("is_active")
    val isActive: Boolean = true,

    /** Hex color for the plan card accent (button / badge / border). */
    @field:PropertyName("accent_color")
    val accentColor: String = "#1565C0"
) {
    val isFree: Boolean get() = priceInr == 0
    val displayPrice: String get() = if (isFree) "FREE" else "₹$priceInr"

    /** Human-readable expiry date string given an epoch-ms start ("27 Apr 2026"). */
    fun expiryLabel(startMs: Long): String {
        if (validityDays <= 0 || startMs <= 0L) return "No expiry"
        val expiryMs = startMs + validityDays.toLong() * 86_400_000L
        return SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(Date(expiryMs))
    }

    /** Epoch-ms expiry calculated from [startMs]. 0L if plan never expires. */
    fun computeExpiryMs(startMs: Long): Long =
        if (validityDays <= 0) 0L else startMs + validityDays.toLong() * 86_400_000L
}

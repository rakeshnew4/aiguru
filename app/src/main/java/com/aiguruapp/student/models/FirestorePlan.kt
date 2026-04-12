package com.aiguruapp.student.models

import com.google.firebase.firestore.IgnoreExtraProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A subscription plan fetched from Firestore collection: plans/{planId}
 *
 * Firestore document structure — all keys are camelCase to match Kotlin property names:
 *   name:          "Basic"
 *   badge:         "Popular"
 *   priceInr:      99                (0 for free plans)
 *   duration:      "1 Month"
 *   validityDays:  30                (0 = never expires)
 *   features:      ["Feature A", …]
 *   displayOrder:  1
 *   isActive:      true
 *   accentColor:   "#0891B2"
 */
@IgnoreExtraProperties
data class FirestorePlan(
    /** Document ID in plans collection (e.g. "free", "basic"). */
    val id: String = "",

    val name: String = "",

    /** Badge text shown on the plan card (e.g. "Popular", "Best Value"). */
    val badge: String = "",

    val priceInr: Int = 0,

    /** Human-readable validity label displayed on the card (e.g. "1 Month"). */
    val duration: String = "",

    /**
     * Number of calendar days the plan stays active after purchase.
     * 0 = no expiry (free / lifetime plans).
     */
    val validityDays: Int = 30,

    val features: List<String> = emptyList(),

    val displayOrder: Int = 0,

    val isActive: Boolean = true,

    /** Hex color for the plan card accent (button / badge / border). */
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

package com.aiguruapp.student.models

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

/**
 * An offer / announcement card fetched from Firestore collection: app_offers/{offerId}
 *
 * Firestore document structure (create manually):
 *   title:            "🎓 New Chapters Added!"   (string — bold headline)
 *   subtitle:         "Maths & Science — Class 9" (string — smaller line below)
 *   emoji:            "📚"                        (string — large icon on right)
 *   background_color: "#1A1A2E"                   (string hex — card background)
 *   display_order:    0                            (number — 0 shown first)
 *   is_active:        true                         (boolean — false hides from app)
 */
@IgnoreExtraProperties
data class FirestoreOffer(
    /** Document ID in app_offers collection. */
    val id: String = "",

    val title: String = "",

    val subtitle: String = "",

    /** Emoji or short text shown as the large icon on the right side of the card. */
    val emoji: String = "🎓",

    @field:PropertyName("background_color")
    val backgroundColor: String = "#1A1A2E",

    @field:PropertyName("display_order")
    val displayOrder: Int = 0,

    @field:PropertyName("is_active")
    val isActive: Boolean = true
)

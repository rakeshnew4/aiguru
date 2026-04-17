package com.aiguruapp.student.firestore

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source

/**
 * A single smart suggestion card shown on the home screen.
 *
 * [type]            : "bb_intro" | "tip" | "offer"
 * [targetCondition] : filter key evaluated against the user's live stats
 *    "all"          → always shown
 *    "bb_not_used"  → shown while total_bb_sessions == 0
 *    "new_user"     → shown while total_messages < 5
 *    "streak_3"     → shown when streak_days >= 3
 *    "high_activity"→ shown when total_messages >= 20
 * [bbMessage]       : pre-loaded prompt sent to BlackboardActivity as EXTRA_MESSAGE
 *                     (empty for tip/offer types — those don't open BB directly)
 */
data class SmartCard(
    val id: String = "",
    val type: String = "",
    val emoji: String = "",
    val title: String = "",
    val subtitle: String = "",
    val ctaLabel: String = "Try it →",
    val cardColor: String = "#1565C0",
    val subject: String = "",
    val chapter: String = "",
    val bbMessage: String = "",
    val targetCondition: String = "all",
    val priority: Int = 5,
    val active: Boolean = true
)

object HomeSmartContentLoader {

    private const val COLLECTION = "home_smart_content"
    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    /**
     * Load active smart cards, filter by user stats, return sorted by priority.
     *
     * Uses Firestore local cache so this is fast even offline.
     * The server will refresh the cache silently in the background.
     */
    fun loadForUser(
        totalMessages: Long,
        totalBbSessions: Long,
        streakDays: Long,
        onSuccess: (List<SmartCard>) -> Unit,
        onFailure: () -> Unit = {}
    ) {
        db.collection(COLLECTION)
            .whereEqualTo("active", true)
            .get(Source.DEFAULT)
            .addOnSuccessListener { snap ->
                val allCards = snap.documents.mapNotNull { doc ->
                    try {
                        SmartCard(
                            id              = doc.id,
                            type            = doc.getString("type") ?: "",
                            emoji           = doc.getString("emoji") ?: "",
                            title           = doc.getString("title") ?: "",
                            subtitle        = doc.getString("subtitle") ?: "",
                            ctaLabel        = doc.getString("cta_label") ?: "Try it →",
                            cardColor       = doc.getString("card_color") ?: "#1565C0",
                            subject         = doc.getString("subject") ?: "",
                            chapter         = doc.getString("chapter") ?: "",
                            bbMessage       = doc.getString("bb_message") ?: "",
                            targetCondition = doc.getString("target_condition") ?: "all",
                            priority        = (doc.getLong("priority") ?: 5L).toInt(),
                            active          = doc.getBoolean("active") ?: true
                        )
                    } catch (_: Exception) { null }
                }

                val visible = allCards.filter { card ->
                    when (card.targetCondition) {
                        "all"           -> true
                        "bb_not_used"   -> totalBbSessions == 0L
                        "new_user"      -> totalMessages < 5L
                        "streak_3"      -> streakDays >= 3L
                        "high_activity" -> totalMessages >= 20L
                        else            -> true
                    }
                }.sortedBy { it.priority }

                onSuccess(visible)
            }
            .addOnFailureListener { onFailure() }
    }
}

package com.example.aiguru.config

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Manages referral code generation, registration, and claiming.
 *
 * Firestore structure:
 *   referralCodes/{code}  →  { ownerUserId: String, ownerName: String }
 *   users/{userId}        →  { referredBy: String }   (set on first-time claim)
 */
object ReferralManager {

    /** Extra chat questions awarded to both referrer and new user per day. */
    const val REFERRED_BONUS = 5

    private val ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // unambiguous chars
    private val db get() = FirebaseFirestore.getInstance()

    // ── Code generation ────────────────────────────────────────────────────────

    /**
     * Returns a deterministic 8-character referral code for [userId].
     * Also ensures the code is registered in Firestore (fire-and-forget).
     */
    fun codeForUser(userId: String): String {
        val code = generateCode(userId)
        registerCode(userId, code)
        return code
    }

    private fun generateCode(userId: String): String {
        // Deterministic: hash userId into an 8-char code from ALPHABET
        var hash = userId.fold(5381L) { acc, c -> acc * 33 + c.code }
        if (hash < 0) hash = -hash
        return buildString {
            repeat(8) {
                append(ALPHABET[(hash % ALPHABET.length).toInt()])
                hash /= ALPHABET.length
                // Mix in more entropy for subsequent chars
                hash = hash * 31 + it
                if (hash < 0) hash = -hash
            }
        }
    }

    /** Writes the code → ownerUserId mapping to Firestore if it doesn't exist yet. */
    private fun registerCode(userId: String, code: String) {
        val ref = db.collection("referralCodes").document(code)
        ref.get().addOnSuccessListener { snap ->
            if (!snap.exists()) {
                // Resolve owner's display name from their user document
                db.collection("users").document(userId).get()
                    .addOnSuccessListener { userSnap ->
                        val ownerName = userSnap.getString("name").orEmpty()
                        ref.set(
                            mapOf("ownerUserId" to userId, "ownerName" to ownerName),
                            SetOptions.merge()
                        )
                    }
                    .addOnFailureListener {
                        ref.set(
                            mapOf("ownerUserId" to userId, "ownerName" to ""),
                            SetOptions.merge()
                        )
                    }
            }
        }
    }

    // ── Claiming ───────────────────────────────────────────────────────────────

    /**
     * Claims a referral [code] on behalf of [claimantUserId].
     *
     * @param onSuccess        Called with the referrer's display name.
     * @param onAlreadyClaimed Called when this user has already used a referral code.
     * @param onInvalid        Called when the code doesn't exist or the user tries to use their own code.
     * @param onError          Called on unexpected Firestore errors.
     */
    fun claimReferralCode(
        claimantUserId: String,
        code: String,
        onSuccess: (referrerName: String) -> Unit,
        onAlreadyClaimed: () -> Unit,
        onInvalid: () -> Unit,
        onError: () -> Unit
    ) {
        val upperCode = code.trim().uppercase()

        // 1. Look up the code
        db.collection("referralCodes").document(upperCode).get()
            .addOnFailureListener { onError() }
            .addOnSuccessListener { codeSnap ->
                if (!codeSnap.exists()) {
                    onInvalid()
                    return@addOnSuccessListener
                }

                val ownerUserId = codeSnap.getString("ownerUserId").orEmpty()
                val ownerName = codeSnap.getString("ownerName").orEmpty()

                // 2. Prevent self-referral
                if (ownerUserId == claimantUserId) {
                    onInvalid()
                    return@addOnSuccessListener
                }

                // 3. Check if claimant has already used a referral code
                db.collection("users").document(claimantUserId).get()
                    .addOnFailureListener { onError() }
                    .addOnSuccessListener { claimantSnap ->
                        val alreadyClaimed = claimantSnap.getString("referredBy")?.isNotBlank() == true
                        if (alreadyClaimed) {
                            onAlreadyClaimed()
                            return@addOnSuccessListener
                        }

                        // 4. Mark claimant as referred and grant bonus to both users
                        val batch = db.batch()

                        val claimantRef = db.collection("users").document(claimantUserId)
                        batch.update(claimantRef, mapOf(
                            "referredBy" to upperCode,
                            "bonus_questions_today" to FieldValue.increment(REFERRED_BONUS.toLong()),
                            "updatedAt" to System.currentTimeMillis()
                        ))

                        if (ownerUserId.isNotBlank()) {
                            val ownerRef = db.collection("users").document(ownerUserId)
                            batch.update(ownerRef, mapOf(
                                "bonus_questions_today" to FieldValue.increment(REFERRED_BONUS.toLong()),
                                "updatedAt" to System.currentTimeMillis()
                            ))
                        }

                        batch.commit()
                            .addOnSuccessListener { onSuccess(ownerName.ifBlank { "your friend" }) }
                            .addOnFailureListener { onError() }
                    }
            }
    }
}

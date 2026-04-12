package com.aiguruapp.student.config

import android.util.Log
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
        ref.get()
            .addOnFailureListener { e ->
                Log.e("ReferralManager", "registerCode: read failed for $code", e)
            }
            .addOnSuccessListener { snap ->
            if (!snap.exists()) {
                // Resolve owner's display name from their user document
                db.collection("users").document(userId).get()
                    .addOnSuccessListener { userSnap ->
                        val ownerName = userSnap.getString("name").orEmpty()
                        ref.set(
                            mapOf("ownerUserId" to userId, "ownerName" to ownerName),
                            SetOptions.merge()
                        ).addOnFailureListener { e ->
                            Log.e("ReferralManager", "registerCode: write failed for $code", e)
                        }
                    }
                    .addOnFailureListener {
                        ref.set(
                            mapOf("ownerUserId" to userId, "ownerName" to ""),
                            SetOptions.merge()
                        ).addOnFailureListener { e ->
                            Log.e("ReferralManager", "registerCode: fallback write failed for $code", e)
                        }
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

        val codeRef      = db.collection("referralCodes").document(upperCode)
        val claimantRef  = db.collection("users").document(claimantUserId)

        // Use a Firestore transaction so look-up and write are atomic —
        // prevents two users claiming the same code simultaneously.
        // Transaction returns a sentinel String for validation errors, or the
        // owner's display name on success.  We avoid throwing custom exceptions
        // because Firestore may wrap them, making e.message matching unreliable.
        db.runTransaction { txn ->
            val codeSnap     = txn.get(codeRef)
            val claimantSnap = txn.get(claimantRef)

            // 1. Code must exist
            if (!codeSnap.exists()) return@runTransaction "INVALID"

            val ownerUserId = codeSnap.getString("ownerUserId").orEmpty()
            val ownerName   = codeSnap.getString("ownerName").orEmpty()

            // 2. Prevent self-referral
            if (ownerUserId == claimantUserId) return@runTransaction "INVALID"

            // 3. Prevent double-claiming
            val alreadyClaimed = claimantSnap.getString("referredBy")?.isNotBlank() == true
            if (alreadyClaimed) return@runTransaction "ALREADY_CLAIMED"

            // 4. Apply bonus to claimant — use set/merge so the write succeeds even
            //    if the document is missing fields or was created without bonus_questions_today.
            txn.set(claimantRef, mapOf(
                "referredBy"            to upperCode,
                "bonus_questions_today" to FieldValue.increment(REFERRED_BONUS.toLong()),
                "updatedAt"             to System.currentTimeMillis()
            ), SetOptions.merge())

            // 5. Apply bonus to referrer
            if (ownerUserId.isNotBlank()) {
                val ownerRef = db.collection("users").document(ownerUserId)
                txn.set(ownerRef, mapOf(
                    "bonus_questions_today" to FieldValue.increment(REFERRED_BONUS.toLong()),
                    "updatedAt"             to System.currentTimeMillis()
                ), SetOptions.merge())
            }

            ownerName.ifBlank { "your friend" }   // returned as transaction result
        }
        .addOnSuccessListener { result ->
            when (result as? String) {
                "INVALID"        -> onInvalid()
                "ALREADY_CLAIMED" -> onAlreadyClaimed()
                else             -> onSuccess(result as? String ?: "your friend")
            }
        }
        .addOnFailureListener { e ->
            Log.e("ReferralManager", "claimReferralCode failed: ${e.message}", e)
            onError()
        }
    }
}

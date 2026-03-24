package com.example.aiguru.firestore

import com.example.aiguru.models.UserMetadata
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

/**
 * Centralized Firestore operations for user metadata.
 */
object FirestoreManager {
    
    private val db = FirebaseFirestore.getInstance()
    
    /**
     * Save or update user metadata document.
     */
    fun saveUserMetadata(
        metadata: UserMetadata,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        val userId = metadata.userId
        if (userId.isBlank() || userId == "guest_user") {
            onFailure(null); return
        }
        val now = System.currentTimeMillis()
        val docData = metadata.copy(
            updatedAt = now,
            createdAt = if (metadata.createdAt == 0L) now else metadata.createdAt
        )
        
        db.collection("users").document(userId)
            .set(docData, SetOptions.merge())
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }
    
    /**
     * Get user metadata.
     */
    fun getUserMetadata(
        userId: String,
        onSuccess: (UserMetadata?) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("users").document(userId)
            .get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    onSuccess(doc.toObject(UserMetadata::class.java))
                } else {
                    onSuccess(null)
                }
            }
            .addOnFailureListener { onFailure(it) }
    }
    
    /**
     * Update specific field.
     */
    fun updateUserField(
        userId: String,
        field: String,
        value: Any,
        onSuccess: () -> Unit = {},
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection("users").document(userId)
            .update(field, value)
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure(it) }
    }
}


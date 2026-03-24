package com.example.aiguru.models

import com.google.firebase.firestore.PropertyName

/**
 * User metadata stored in Firestore at users/{userId}
 */
data class UserMetadata(
    val userId: String = "",
    
    val name: String = "",
    
    val email: String? = null,
    
    val schoolId: String = "",
    
    val schoolName: String = "",
    
    val grade: String = "",  // "8th", "9th", etc.
    
    val planId: String = "",
    
    val planName: String = "",
    
    @PropertyName("model_config")
    val modelConfig: ModelConfig? = null,
    
    @PropertyName("created_at")
    val createdAt: Long = 0L,
    
    @PropertyName("updated_at")
    val updatedAt: Long = 0L
)


package com.aiguruapp.student.models

import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName

/**
 * A broadcast notification shown inside the app, stored at:
 *   notifications/{notificationId}
 *
 * Firestore document fields:
 *   title       – headline text
 *   body        – detail message
 *   type        – "info" | "promo" | "update" | "alert"
 *   target      – "all" | "plan:<planId>" | "uid:<userId>"
 *   is_active   – false hides the notification
 *   created_at  – ISO-8601 string for sorting
 *   display_order – lower = shown first
 *   action_url  – optional deep-link or external URL
 */
@IgnoreExtraProperties
data class AppNotification(
    val id: String = "",
    val title: String = "",
    val body: String = "",
    val type: String = "info",
    val target: String = "all",

    @field:PropertyName("is_active")
    val isActive: Boolean = true,

    @field:PropertyName("created_at")
    val createdAt: String = "",

    @field:PropertyName("display_order")
    val displayOrder: Int = 0,

    @field:PropertyName("action_url")
    val actionUrl: String = "",
)

package com.aiguruapp.student.models

/**
 * Represents the Firestore document at: updates/app_config
 *
 * Firestore document shape (all fields optional — safe defaults applied):
 * {
 *   "min_version_code":    <Long>   // Force-update threshold — users below this MUST update
 *   "latest_version_code": <Long>   // Soft-update threshold — prompt users below this
 *   "latest_version_name": <String> // Display name, e.g. "2.1.0"
 *   "update_url":          <String> // Play Store or direct APK link
 *   "update_message":      <String> // Custom headline for update dialogs
 *   "release_notes":       <String> // What's new (shown in optional-update dialog)
 *   "is_maintenance":      <Boolean>// True → block all access, show maintenance screen
 *   "maintenance_message": <String> // Shown on maintenance screen
 *   "is_active":           <Boolean>// Global kill-switch; false treats as maintenance
 *   "support_contact":     <String> // Email / link shown on maintenance screen
 * }
 */
data class AppUpdateConfig(
    val minVersionCode: Long = 0L,
    val latestVersionCode: Long = 0L,
    val latestVersionName: String = "",
    val updateUrl: String = "https://play.google.com/store/apps/details?id=com.aiguruapp.student",
    val updateMessage: String = "A new version of AI Guru is available with improvements and bug fixes.",
    val releaseNotes: String = "",
    val isMaintenance: Boolean = false,
    val maintenanceMessage: String = "We're working on improvements to make AI Guru better for you. Please check back soon.",
    val isActive: Boolean = true,
    val supportContact: String = ""
)

package com.aiguruapp.student.config

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.view.View
import com.aiguruapp.student.models.PlanLimits
import com.aiguruapp.student.utils.SessionManager

/**
 * Central access-control gate.
 *
 * Every feature/page has a [Feature] entry.
 * Call [canAccess] to check whether the current user (role + plan) may use it.
 * Call [requireAccess] in an Activity's onCreate to auto-block unauthorised users.
 *
 * ## Roles (derived from SessionManager in priority order):
 *   GUEST          → isGuestMode
 *   TEACHER        → isTeacher
 *   STUDENT_SCHOOL → authenticated + has a real schoolId
 *   STUDENT        → authenticated, no school yet
 *
 * ## Plan limits are resolved from [AdminConfigRepository] (cached, refreshed on launch).
 *
 * ## Admin control:
 *   Feature flags live in plans/{planId}/limits in Firestore.
 *   Toggle any flag in the Firebase console; the app picks it up on next launch.
 *   Page-access rules are documented in app_config/page_access in Firestore
 *   (seeded by seed_roles_and_features.py — for documentation/override purposes).
 */
object AccessGate {

    // ─────────────────────────────────────────────────────────────────────────
    // Roles
    // ─────────────────────────────────────────────────────────────────────────

    enum class Role { GUEST, STUDENT, STUDENT_SCHOOL, TEACHER }

    fun currentRole(context: Context): Role {
        if (SessionManager.isGuestMode(context)) return Role.GUEST
        if (SessionManager.isTeacher(context))   return Role.TEACHER
        val schoolId = SessionManager.getSchoolId(context)
        return if (schoolId.isNotBlank() && schoolId != "guest") Role.STUDENT_SCHOOL
               else Role.STUDENT
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Features
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Each Feature encodes:
     *  - [pageKey]      : stable Firestore key (also stored in app_config/page_access)
     *  - [displayName]  : human-readable label for dialogs
     *  - [check]        : (role, planLimits) → Boolean
     */
    enum class Feature(
        val pageKey: String,
        val displayName: String,
        val check: (Role, PlanLimits) -> Boolean
    ) {
        // ── Teacher-only pages ────────────────────────────────────────────────
        TEACHER_DASHBOARD(
            "teacher_dashboard", "Teacher Dashboard",
            { role, _ -> role == Role.TEACHER }
        ),
        TEACHER_TASKS(
            "teacher_tasks", "Teacher Tasks",
            { role, _ -> role == Role.TEACHER }
        ),
        TEACHER_QUIZ_VALIDATION(
            "teacher_quiz_validation", "Quiz Validation",
            { role, _ -> role == Role.TEACHER }
        ),
        TEACHER_CHAT_REVIEW(
            "teacher_chat_review", "Chat Review",
            { role, _ -> role == Role.TEACHER }
        ),
        TEACHER_SAVED_CONTENT(
            "teacher_saved_content", "Saved Content",
            { role, _ -> role == Role.TEACHER }
        ),

        // ── School-student + teacher pages (no guest, no unregistered student) ─
        TASKS(
            "tasks", "My Tasks",
            { role, _ -> role == Role.STUDENT_SCHOOL || role == Role.TEACHER }
        ),

        // ── Authenticated + plan-gated pages (no guest) ───────────────────────
        PROGRESS_DASHBOARD(
            "progress_dashboard", "Progress Dashboard",
            { role, limits -> role != Role.GUEST && limits.progressDashboardEnabled }
        ),
        BLACKBOARD(
            "blackboard", "Visual Blackboard",
            // Allow guests to SEE the button — sign-in prompt fires in HomeActivity when they tap
            { _, limits -> limits.blackboardEnabled }
        ),
        LIBRARY(
            "library", "Chapter Library",
            { role, limits -> role != Role.GUEST && limits.libraryEnabled }
        ),
        QUIZ(
            "quiz", "Practice Quiz",
            { role, limits -> role != Role.GUEST && limits.quizEnabled }
        ),
        REVISION(
            "revision", "Revision Mode",
            { role, limits -> role != Role.GUEST && limits.revisionEnabled }
        ),
        NCERT_VIEWER(
            "ncert_viewer", "NCERT Viewer",
            { role, limits -> role != Role.GUEST && limits.ncertViewerEnabled }
        ),
        AI_VOICE(
            "ai_voice", "AI Voice (TTS)",
            { role, limits -> role != Role.GUEST && limits.aiTtsEnabled }
        ),
        VOICE_MODE(
            "voice_mode", "Voice Mode",
            { role, limits -> role != Role.GUEST && limits.voiceModeEnabled }
        ),
        PDF_UPLOAD(
            "pdf_upload", "PDF Upload",
            { role, limits -> role != Role.GUEST && limits.pdfEnabled }
        ),
        FLASHCARDS(
            "flashcards", "Flashcards",
            { role, limits -> role != Role.GUEST && limits.flashcardsEnabled }
        ),
        USER_PROFILE(
            "user_profile", "Profile",
            { role, _ -> role != Role.GUEST }
        ),

        // ── Always visible — any signed-in user can join or change their school ───
        JOIN_SCHOOL(
            "join_school", "Join / Change School",
            { _, _ -> true }
        ),

        // ── Available to everyone ─────────────────────────────────────────────
        SUBSCRIPTION_PLANS(
            "subscription_plans", "Subscription Plans",
            { _, _ -> true }
        ),
        CHAT(
            "chat", "AI Chat",
            { _, _ -> true }
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns true if the current user may access [feature]. */
    fun canAccess(context: Context, feature: Feature): Boolean {
        val role   = currentRole(context)
        val planId = SessionManager.getPlanId(context)
        val limits = AdminConfigRepository.resolveEffectiveLimits(planId)
        return feature.check(role, limits)
    }

    /**
     * Show a friendly "Access Restricted" dialog and finish [activity] if access is denied.
     * Returns **true** if access is allowed (safe to continue in onCreate).
     * Returns **false** if access was denied (activity will be finished — return immediately).
     */
    fun requireAccess(activity: Activity, feature: Feature): Boolean {
        if (canAccess(activity, feature)) return true
        val role = currentRole(activity)
        val message = buildDeniedMessage(role, feature)
        AlertDialog.Builder(activity)
            .setTitle("Access Restricted")
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton("Go Back") { _, _ -> activity.finish() }
            .show()
        return false
    }

    /**
     * Set a [View]'s visibility based on whether the current user can access [feature].
     * Useful for hiding nav items in drawers / bottom bars.
     */
    fun applyVisibility(context: Context, view: View?, feature: Feature) {
        view?.visibility = if (canAccess(context, feature)) View.VISIBLE else View.GONE
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun buildDeniedMessage(role: Role, feature: Feature): String = when {
        role == Role.GUEST ->
            "Please sign in to access ${feature.displayName}."
        feature in setOf(
            Feature.TEACHER_DASHBOARD,
            Feature.TEACHER_TASKS,
            Feature.TEACHER_QUIZ_VALIDATION,
            Feature.TEACHER_CHAT_REVIEW,
            Feature.TEACHER_SAVED_CONTENT
        ) -> "This section is only available to registered teachers."
        feature == Feature.TASKS ->
            "Tasks are available once you join a school. Use 'Join a School' from the menu."
        else ->
            "${feature.displayName} is not included in your current plan. " +
            "Upgrade to unlock it."
    }
}

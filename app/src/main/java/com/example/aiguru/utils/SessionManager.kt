package com.example.aiguru.utils

import android.content.Context
import android.content.SharedPreferences

import com.example.aiguru.models.UserMetadata
import com.google.firebase.auth.FirebaseAuth

/**
 * Manages student login session using SharedPreferences.
 * Stores school selection, student ID, name, and subscribed plan.
 */
object SessionManager {

    private const val PREF_NAME = "aiguru_session"
    private const val KEY_LOGGED_IN = "is_logged_in"
    private const val KEY_SCHOOL_ID = "school_id"
    private const val KEY_SCHOOL_NAME = "school_name"
    private const val KEY_STUDENT_ID = "student_id"
    private const val KEY_STUDENT_NAME = "student_name"
    private const val KEY_PLAN_ID = "plan_id"
    private const val KEY_PLAN_NAME = "plan_name"
    private const val KEY_LOGIN_TIME = "login_time"
    private const val KEY_GRADE = "grade"
    private const val KEY_SIGNUP_COMPLETE = "signup_complete"
    private const val KEY_FIREBASE_UID = "firebase_uid"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    // ── Login / Logout ────────────────────────────────────────────────────────

    fun login(
        context: Context,
        schoolId: String,
        schoolName: String,
        studentId: String,
        studentName: String
    ) {
        prefs(context).edit().apply {
            putBoolean(KEY_LOGGED_IN, true)
            putString(KEY_SCHOOL_ID, schoolId)
            putString(KEY_SCHOOL_NAME, schoolName)
            putString(KEY_STUDENT_ID, studentId)
            putString(KEY_STUDENT_NAME, studentName.ifBlank { "Student" })
            putLong(KEY_LOGIN_TIME, System.currentTimeMillis())
            // Clear any previous plan on new login
            putString(KEY_PLAN_ID, "")
            putString(KEY_PLAN_NAME, "")
            apply()
        }
    }

    fun logout(context: Context) {
        prefs(context).edit().clear().apply()
    }

    // ── Session state helpers ─────────────────────────────────────────────────

    fun isLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LOGGED_IN, false)

    fun hasSubscription(context: Context): Boolean =
        prefs(context).getString(KEY_PLAN_ID, "").isNullOrBlank().not()

    fun isSignupComplete(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SIGNUP_COMPLETE, false)

    // ── Getters ───────────────────────────────────────────────────────────────

    fun getSchoolId(context: Context): String =
        prefs(context).getString(KEY_SCHOOL_ID, "") ?: ""

    fun getSchoolName(context: Context): String =
        prefs(context).getString(KEY_SCHOOL_NAME, "") ?: ""

    fun getStudentId(context: Context): String =
        prefs(context).getString(KEY_STUDENT_ID, "") ?: ""

    fun getStudentName(context: Context): String =
        prefs(context).getString(KEY_STUDENT_NAME, "Student") ?: "Student"

    fun getPlanId(context: Context): String =
        prefs(context).getString(KEY_PLAN_ID, "") ?: ""

    fun getPlanName(context: Context): String =
        prefs(context).getString(KEY_PLAN_NAME, "") ?: ""

    // ── Subscription ─────────────────────────────────────────────────────────

    fun savePlan(context: Context, planId: String, planName: String) {
        prefs(context).edit().apply {
            putString(KEY_PLAN_ID, planId)
            putString(KEY_PLAN_NAME, planName)
            apply()
        }
    }

    fun saveGrade(context: Context, grade: String) {
        prefs(context).edit().putString(KEY_GRADE, grade).apply()
    }

    fun getGrade(context: Context): String = prefs(context).getString(KEY_GRADE, "") ?: ""

    // ── Firebase UID ──────────────────────────────────────────────────────────

    fun saveFirebaseUid(context: Context, uid: String) {
        prefs(context).edit().putString(KEY_FIREBASE_UID, uid).apply()
    }

    fun getFirebaseUid(context: Context): String =
        prefs(context).getString(KEY_FIREBASE_UID, "") ?: ""

    // ── Firestore document ID ─────────────────────────────────────────────────

    fun getFirestoreUserId(context: Context): String {
        // Firebase Auth users use their UID as the Firestore document ID
        val firebaseUid = getFirebaseUid(context)
        if (firebaseUid.isNotBlank()) return firebaseUid
        // School-only users fall back to schoolId_studentId
        val schoolId = getSchoolId(context)
        val studentId = getStudentId(context)
        return if (schoolId.isNotBlank() && studentId.isNotBlank())
            "${schoolId}_${studentId}".replace("@", "_at_").replace(".", "_")
        else
            "guest_user"
    }

    /**
     * Build UserMetadata from current session data.
     */
    fun buildUserMetadata(context: Context): UserMetadata {
        val prefs = this.prefs(context)
        val userId = getFirestoreUserId(context)
        val firebaseUser = FirebaseAuth.getInstance().currentUser
        val email = firebaseUser?.email
            ?: getStudentId(context).takeIf { it.contains("@") }
        val now = System.currentTimeMillis()
        // Store creation time once
        if (prefs.getLong("metadata_created", 0L) == 0L) {
            prefs.edit().putLong("metadata_created", now).apply()
        }
        return UserMetadata(
            userId    = userId,
            name      = getStudentName(context),
            email     = email,
            schoolId  = getSchoolId(context),
            schoolName = getSchoolName(context),
            grade     = getGrade(context),
            planId    = getPlanId(context),
            planName  = getPlanName(context),
            createdAt = prefs.getLong("metadata_created", now),
            updatedAt = now
        )
    }

    fun completeSignup(context: Context) {
        prefs(context).edit().putBoolean(KEY_SIGNUP_COMPLETE, true).apply()
    }
}

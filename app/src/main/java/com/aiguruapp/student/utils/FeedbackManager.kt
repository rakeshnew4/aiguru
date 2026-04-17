package com.aiguruapp.student.utils

import android.app.Activity
import android.content.Context
import android.util.Log
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.aiguruapp.student.BuildConfig
import com.aiguruapp.student.R
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Manages the one-time (and periodic) user feedback bottom sheet.
 *
 * Feedback is written to the top-level  app_feedback  Firestore collection.
 * The sheet is shown at most once every [SHOW_INTERVAL_MS] (default 90 days).
 * Call [markBbSessionDone] from BlackboardActivity before finish(); it sets a
 * SharedPreferences flag that [showIfNeeded] consumes in HomeActivity.onResume().
 */
object FeedbackManager {

    private const val TAG = "FeedbackManager"
    private const val PREF_NAME   = "aiguru_feedback"
    private const val KEY_PENDING = "bb_session_feedback_pending"   // set by BlackboardActivity
    private const val KEY_LAST_SHOWN_MS = "feedback_last_shown_ms"  // epoch-ms of last display
    private const val COLLECTION  = "app_feedback"

    /** Re-show the feedback sheet after this many milliseconds (~90 days). */
    private const val SHOW_INTERVAL_MS = 90L * 24 * 60 * 60 * 1000

    // ── Called by BlackboardActivity ──────────────────────────────────────────

    /**
     * Mark that a BB session just finished.  HomeActivity.onResume() will pick
     * this up and show the feedback sheet if the cooldown has passed.
     */
    fun markBbSessionDone(context: Context) {
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_PENDING, true).apply()
    }

    // ── Called directly (e.g. "Give Feedback" button tap) ────────────────────

    /**
     * Unconditionally show the feedback sheet (ignores cooldown).
     * Use for explicit button taps.
     */
    fun showNow(activity: Activity) {
        show(activity, source = "home_button")
    }

    // ── Called by HomeActivity.onResume() ─────────────────────────────────────

    /**
     * Show the feedback bottom sheet if:
     *  • A BB session was recently completed ([KEY_PENDING] = true), AND
     *  • The sheet hasn't been shown within [SHOW_INTERVAL_MS].
     *
     * This is safe to call every time onResume fires — it is a no-op when
     * nothing is pending.
     */
    fun showIfNeeded(activity: Activity) {
        val prefs = activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_PENDING, false)) return

        // Clear the pending flag immediately so onResume won't re-trigger.
        prefs.edit().putBoolean(KEY_PENDING, false).apply()

        val lastShownMs = prefs.getLong(KEY_LAST_SHOWN_MS, 0L)
        if (System.currentTimeMillis() - lastShownMs < SHOW_INTERVAL_MS) return

        show(activity, source = "after_bb_session")
    }

    // ── Bottom-sheet logic ────────────────────────────────────────────────────

    private fun show(activity: Activity, source: String) {
        if (activity.isFinishing || activity.isDestroyed) return

        val sheet = BottomSheetDialog(activity)
        val view  = activity.layoutInflater.inflate(R.layout.bottom_sheet_feedback, null)
        sheet.setContentView(view)
        sheet.setCanceledOnTouchOutside(true)

        // Star references
        val stars = listOf<TextView>(
            view.findViewById(R.id.star1),
            view.findViewById(R.id.star2),
            view.findViewById(R.id.star3),
            view.findViewById(R.id.star4),
            view.findViewById(R.id.star5)
        )
        var selectedRating = 0

        fun updateStars(rating: Int) {
            selectedRating = rating
            stars.forEachIndexed { index, tv ->
                tv.text = if (index < rating) "★" else "☆"
            }
        }

        stars.forEachIndexed { index, tv ->
            tv.setOnClickListener { updateStars(index + 1) }
        }

        val feedbackInput = view.findViewById<TextInputEditText>(R.id.feedbackText)
        val submitBtn     = view.findViewById<MaterialButton>(R.id.btnSubmitFeedback)
        val skipBtn       = view.findViewById<MaterialButton>(R.id.btnSkipFeedback)

        submitBtn.setOnClickListener {
            submitBtn.isEnabled = false
            val text = feedbackInput.text?.toString()?.trim() ?: ""
            saveFeedback(
                context  = activity,
                rating   = selectedRating,
                text     = text,
                source   = source,
                onDone   = {
                    Toast.makeText(activity, "Thank you for your feedback! 🙏", Toast.LENGTH_SHORT).show()
                    sheet.dismiss()
                }
            )
            // Record show time so we don't prompt again for SHOW_INTERVAL_MS
            activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_SHOWN_MS, System.currentTimeMillis()).apply()
        }

        skipBtn.setOnClickListener {
            // Record show time even on skip (respect the cooldown)
            activity.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .edit().putLong(KEY_LAST_SHOWN_MS, System.currentTimeMillis()).apply()
            sheet.dismiss()
        }

        sheet.show()
    }

    // ── Firestore write ───────────────────────────────────────────────────────

    private fun saveFeedback(
        context: Context,
        rating: Int,
        text: String,
        source: String,
        onDone: () -> Unit
    ) {
        val uid        = SessionManager.getFirestoreUserId(context)
        val deviceId   = SessionManager.getDeviceId(context)
        val name       = SessionManager.getStudentName(context)
        val appVersion = BuildConfig.VERSION_NAME

        val doc = mapOf(
            "uid"         to uid,
            "deviceId"    to deviceId,
            "displayName" to name,
            "rating"      to rating,
            "feedbackText" to text,
            "source"      to source,
            "appVersion"  to appVersion,
            "timestamp"   to FieldValue.serverTimestamp()
        )

        FirebaseFirestore.getInstance()
            .collection(COLLECTION)
            .add(doc)
            .addOnSuccessListener { onDone() }
            .addOnFailureListener { e ->
                Log.w(TAG, "Failed to save feedback", e)
                onDone()   // dismiss anyway — don't block the user
            }
    }
}

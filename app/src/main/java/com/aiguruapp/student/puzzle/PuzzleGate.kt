package com.aiguruapp.student.puzzle

import android.content.Context
import android.content.SharedPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.time.LocalDate

/**
 * Tracks puzzle unlock state and daily play time.
 *
 * Unlock condition : student completed ≥ 2 BB sessions today (read from Firestore).
 * Play time cap    : 15 minutes per day, stored in SharedPreferences with date key.
 */
object PuzzleGate {

    private const val PREF        = "puzzle_gate"
    private const val KEY_DATE    = "play_date"   // "YYYY-MM-DD"
    private const val KEY_MS      = "play_ms"     // cumulative play ms today
    val MAX_PLAY_MS               = 15 * 60 * 1000L

    // ── Play time ─────────────────────────────────────────────────────────────

    fun playMsRemaining(ctx: Context): Long {
        val prefs = prefs(ctx)
        ensureDayReset(prefs)
        return maxOf(0L, MAX_PLAY_MS - prefs.getLong(KEY_MS, 0L))
    }

    fun recordPlayMs(ctx: Context, ms: Long) {
        if (ms <= 0L) return
        val prefs = prefs(ctx)
        ensureDayReset(prefs)
        prefs.edit().putLong(KEY_MS, prefs.getLong(KEY_MS, 0L) + ms).apply()
    }

    // ── BB session gate (async — Firestore read) ──────────────────────────────

    /**
     * Checks whether the student has completed ≥ 2 BB sessions today.
     * [onResult] is called on the Firestore callback thread.
     */
    fun checkUnlocked(onResult: (unlocked: Boolean, bbToday: Int) -> Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrBlank()) { onResult(false, 0); return }

        FirebaseFirestore.getInstance()
            .collection("users_table").document(uid)
            .get()
            .addOnSuccessListener { doc ->
                val bbToday = doc.getLong("bb_sessions_today")?.toInt() ?: 0
                onResult(bbToday >= 0, bbToday)
            }
            .addOnFailureListener { onResult(false, 0) }
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun ensureDayReset(prefs: SharedPreferences) {
        val today = LocalDate.now().toString()
        if (prefs.getString(KEY_DATE, "") != today) {
            prefs.edit()
                .putString(KEY_DATE, today)
                .putLong(KEY_MS, 0L)
                .apply()
        }
    }
}

package com.aiguruapp.student.firestore

import android.content.Context
import android.util.Log
import com.aiguruapp.student.models.StudentStats
import com.aiguruapp.student.models.SubjectStats
import com.aiguruapp.student.models.ChapterStats
import com.aiguruapp.student.utils.SessionManager
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Manages reads and writes to the  students_stats/{userId}  Firestore collection.
 *
 * All writes use SetOptions.merge() + FieldValue.increment() so they are safe
 * to call concurrently and offline — Firestore queues them.
 */
object StudentStatsManager {

    private const val TAG = "StudentStatsManager"
    private const val COLLECTION = "students_stats"

    private val db: FirebaseFirestore get() = FirebaseFirestore.getInstance()

    // ── Safe key helpers ───────────────────────────────────────────────────────

    private fun key(s: String): String =
        s.lowercase().trim()
         .replace(Regex("[^a-z0-9_]"), "_")
         .replace(Regex("_+"), "_")
         .take(60)
         .trimEnd('_')
         .ifBlank { "general" }

    private fun subjectKey(subject: String)  = key(subject)
    private fun chapterKey(chapter: String)  = key(chapter)

    // ── Ensure base document exists (idempotent) ───────────────────────────────

    fun ensureProfile(
        context: Context,
        userId: String
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        val today = currentDateStr()
        val base = mapOf(
            "user_id"      to userId,
            "school_id"    to SessionManager.getSchoolId(context),
            "grade"        to SessionManager.getGrade(context),
            "display_name" to SessionManager.getStudentName(context)
        )
        db.collection(COLLECTION).document(userId)
            .set(base, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "ensureProfile failed: ${it.message}") }
    }

    // ── Record a chat message sent ─────────────────────────────────────────────

    fun recordMessage(
        userId: String,
        subject: String,
        chapter: String,
        context: Context? = null
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        val now  = System.currentTimeMillis()
        val sk   = subjectKey(subject)
        val ck   = chapterKey(chapter)
        val updates = mutableMapOf<String, Any>(
            "total_messages"                         to FieldValue.increment(1),
            "last_active_at"                         to now,
            "subjects.$sk.subject_name"              to subject,
            "subjects.$sk.messages"                  to FieldValue.increment(1),
            "subjects.$sk.last_active_at"            to now,
            "subjects.$sk.chapters.$ck.chapter_name" to chapter,
            "subjects.$sk.chapters.$ck.messages"     to FieldValue.increment(1),
            "subjects.$sk.chapters.$ck.last_active_at" to now
        )
        if (context != null) {
            updates["school_id"]    = SessionManager.getSchoolId(context)
            updates["grade"]        = SessionManager.getGrade(context)
            updates["display_name"] = SessionManager.getStudentName(context)
        }
        updateStreakAndWrite(userId, updates)
    }

    // ── Record a Blackboard session started ───────────────────────────────────

    fun recordBbSession(
        userId: String,
        subject: String,
        chapter: String,
        context: Context? = null
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        val now = System.currentTimeMillis()
        val sk  = subjectKey(subject)
        val ck  = chapterKey(chapter)
        val updates = mutableMapOf<String, Any>(
            "total_bb_sessions"                      to FieldValue.increment(1),
            "last_active_at"                         to now,
            "subjects.$sk.subject_name"              to subject,
            "subjects.$sk.bb_sessions"               to FieldValue.increment(1),
            "subjects.$sk.last_active_at"            to now,
            "subjects.$sk.chapters.$ck.chapter_name" to chapter,
            "subjects.$sk.chapters.$ck.bb_sessions"  to FieldValue.increment(1),
            "subjects.$sk.chapters.$ck.last_active_at" to now
        )
        if (context != null) {
            updates["school_id"]    = SessionManager.getSchoolId(context)
            updates["grade"]        = SessionManager.getGrade(context)
            updates["display_name"] = SessionManager.getStudentName(context)
        }
        updateStreakAndWrite(userId, updates)
    }

    // ── Record a quiz answer (BB interactive quiz or chapter quiz) ─────────────

    fun recordQuiz(
        userId: String,
        subject: String,
        chapter: String,
        answered: Int,
        correct: Int,
        context: Context? = null
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        if (answered <= 0) return
        val now = System.currentTimeMillis()
        val sk  = subjectKey(subject)
        val ck  = chapterKey(chapter)
        val updates = mutableMapOf<String, Any>(
            "total_quizzes_answered"                        to FieldValue.increment(answered.toLong()),
            "total_quizzes_correct"                         to FieldValue.increment(correct.toLong()),
            "last_active_at"                                to now,
            "subjects.$sk.subject_name"                     to subject,
            "subjects.$sk.quizzes_answered"                 to FieldValue.increment(answered.toLong()),
            "subjects.$sk.quizzes_correct"                  to FieldValue.increment(correct.toLong()),
            "subjects.$sk.last_active_at"                   to now,
            "subjects.$sk.chapters.$ck.chapter_name"        to chapter,
            "subjects.$sk.chapters.$ck.quizzes_answered"    to FieldValue.increment(answered.toLong()),
            "subjects.$sk.chapters.$ck.quizzes_correct"     to FieldValue.increment(correct.toLong()),
            "subjects.$sk.chapters.$ck.last_active_at"      to now
        )
        if (context != null) {
            updates["school_id"]    = SessionManager.getSchoolId(context)
            updates["grade"]        = SessionManager.getGrade(context)
            updates["display_name"] = SessionManager.getStudentName(context)
        }
        updateStreakAndWrite(userId, updates)
    }

    // ── Record app time spent (session duration) ───────────────────────────────

    fun recordAppTime(
        userId: String,
        subject: String,
        chapter: String,
        durationMs: Long,
        context: Context? = null
    ) {
        if (userId.isBlank() || userId == "guest_user") return
        if (durationMs <= 0) return
        val now = System.currentTimeMillis()
        val sk  = subjectKey(subject)
        val ck  = chapterKey(chapter)
        val updates = mutableMapOf<String, Any>(
            "total_app_time_ms"                          to FieldValue.increment(durationMs),
            "last_active_at"                             to now,
            "subjects.$sk.subject_name"                  to subject,
            "subjects.$sk.app_time_ms"                   to FieldValue.increment(durationMs),
            "subjects.$sk.last_active_at"                to now,
            "subjects.$sk.chapters.$ck.chapter_name"     to chapter,
            "subjects.$sk.chapters.$ck.app_time_ms"      to FieldValue.increment(durationMs),
            "subjects.$sk.chapters.$ck.last_active_at"   to now
        )
        if (context != null) {
            updates["school_id"]    = SessionManager.getSchoolId(context)
            updates["grade"]        = SessionManager.getGrade(context)
            updates["display_name"] = SessionManager.getStudentName(context)
        }
        db.collection(COLLECTION).document(userId)
            .set(updates, SetOptions.merge())
            .addOnFailureListener { Log.w(TAG, "recordAppTime failed: ${it.message}") }
    }

    // ── Fetch a single student's full stats ────────────────────────────────────

    fun fetchStudentStats(
        userId: String,
        onSuccess: (StudentStats?) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        db.collection(COLLECTION).document(userId)
            .get(Source.SERVER)
            .addOnSuccessListener { doc ->
                if (!doc.exists()) { onSuccess(null); return@addOnSuccessListener }
                try {
                    val stats = parseStats(doc.data ?: emptyMap())
                    onSuccess(stats)
                } catch (e: Exception) {
                    Log.e(TAG, "fetchStudentStats parse error: ${e.message}")
                    onFailure(e)
                }
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Fetch all students in a school (for teacher view) ─────────────────────

    fun fetchSchoolStats(
        schoolId: String,
        grade: String = "",          // empty = all grades
        onSuccess: (List<StudentStats>) -> Unit,
        onFailure: (Exception?) -> Unit = {}
    ) {
        var query = db.collection(COLLECTION)
            .whereEqualTo("school_id", schoolId)
        if (grade.isNotBlank()) query = query.whereEqualTo("grade", grade)

        query.get(Source.SERVER)
            .addOnSuccessListener { snap ->
                val list = snap.documents.mapNotNull { doc ->
                    try { parseStats(doc.data ?: emptyMap()) } catch (_: Exception) { null }
                }.sortedBy { it.displayName }
                onSuccess(list)
            }
            .addOnFailureListener { onFailure(it) }
    }

    // ── Internal helpers ───────────────────────────────────────────────────────

    /** Attach streak update to any write batch. */
    private fun updateStreakAndWrite(userId: String, updates: MutableMap<String, Any>) {
        val today = currentDateStr()
        updates["last_active_date"] = today
        db.collection(COLLECTION).document(userId)
            .set(updates, SetOptions.merge())
            .addOnSuccessListener {
                // After writing, compute and store streak (non-critical — fire and forget)
                computeAndSaveStreak(userId, today)
            }
            .addOnFailureListener { Log.w(TAG, "stats write failed: ${it.message}") }
    }

    private fun computeAndSaveStreak(userId: String, today: String) {
        db.collection(COLLECTION).document(userId)
            .get(Source.DEFAULT)
            .addOnSuccessListener { doc ->
                val lastDate = doc.getString("last_active_date") ?: ""
                val currentStreak = doc.getLong("streak_days")?.toInt() ?: 0
                val newStreak = when {
                    lastDate == today -> currentStreak              // same day — no change
                    isYesterday(lastDate, today) -> currentStreak + 1
                    else -> 1                                       // gap — reset
                }
                if (newStreak != currentStreak) {
                    doc.reference.update("streak_days", newStreak)
                        .addOnFailureListener { Log.w(TAG, "streak update failed") }
                }
            }
    }

    private fun isYesterday(dateStr: String, today: String): Boolean {
        return try {
            val fmt  = DateTimeFormatter.ISO_LOCAL_DATE
            val d    = LocalDate.parse(dateStr, fmt)
            val todayDate = LocalDate.parse(today, fmt)
            d.plusDays(1) == todayDate
        } catch (_: Exception) { false }
    }

    private fun currentDateStr(): String =
        LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)

    /**
     * Manually parse the Firestore document map into [StudentStats].
     * We do this manually because Firestore cannot auto-deserialize deeply nested
     * generic maps mixed with primitives without POJO annotations on every type,
     * and the `subjects` field is a `Map<String, Map<String, Any>>`.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseStats(data: Map<String, Any>): StudentStats {
        val subjectsRaw = data["subjects"] as? Map<String, Any> ?: emptyMap()
        val subjects = subjectsRaw.mapValues { (_, sv) ->
            val sm = sv as? Map<String, Any> ?: emptyMap()
            val chaptersRaw = sm["chapters"] as? Map<String, Any> ?: emptyMap()
            val chapters = chaptersRaw.mapValues { (_, cv) ->
                val cm = cv as? Map<String, Any> ?: emptyMap()
                ChapterStats(
                    chapterName     = cm["chapter_name"] as? String ?: "",
                    messages        = (cm["messages"] as? Number)?.toInt() ?: 0,
                    bbSessions      = (cm["bb_sessions"] as? Number)?.toInt() ?: 0,
                    appTimeMs       = (cm["app_time_ms"] as? Number)?.toLong() ?: 0L,
                    quizzesAnswered = (cm["quizzes_answered"] as? Number)?.toInt() ?: 0,
                    quizzesCorrect  = (cm["quizzes_correct"] as? Number)?.toInt() ?: 0,
                    lastActiveAt    = (cm["last_active_at"] as? Number)?.toLong() ?: 0L
                )
            }
            SubjectStats(
                subjectName     = sm["subject_name"] as? String ?: "",
                messages        = (sm["messages"] as? Number)?.toInt() ?: 0,
                bbSessions      = (sm["bb_sessions"] as? Number)?.toInt() ?: 0,
                appTimeMs       = (sm["app_time_ms"] as? Number)?.toLong() ?: 0L,
                quizzesAnswered = (sm["quizzes_answered"] as? Number)?.toInt() ?: 0,
                quizzesCorrect  = (sm["quizzes_correct"] as? Number)?.toInt() ?: 0,
                lastActiveAt    = (sm["last_active_at"] as? Number)?.toLong() ?: 0L,
                chapters        = chapters
            )
        }
        return StudentStats(
            userId              = data["user_id"] as? String ?: "",
            displayName         = data["display_name"] as? String ?: "",
            schoolId            = data["school_id"] as? String ?: "",
            grade               = data["grade"] as? String ?: "",
            lastActiveAt        = (data["last_active_at"] as? Number)?.toLong() ?: 0L,
            totalAppTimeMs      = (data["total_app_time_ms"] as? Number)?.toLong() ?: 0L,
            totalMessages       = (data["total_messages"] as? Number)?.toInt() ?: 0,
            totalBbSessions     = (data["total_bb_sessions"] as? Number)?.toInt() ?: 0,
            totalQuizzesAnswered= (data["total_quizzes_answered"] as? Number)?.toInt() ?: 0,
            totalQuizzesCorrect = (data["total_quizzes_correct"] as? Number)?.toInt() ?: 0,
            streakDays          = (data["streak_days"] as? Number)?.toInt() ?: 0,
            lastActiveDate      = data["last_active_date"] as? String ?: "",
            subjects            = subjects
        )
    }
}

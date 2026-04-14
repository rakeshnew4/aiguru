package com.aiguruapp.student.models

import com.google.firebase.firestore.PropertyName

/**
 * Top-level document stored at  students_stats/{userId}
 *
 * Overall counters are incremented atomically via FieldValue.increment().
 * Subject/chapter stats are stored as a flat nested map to avoid sub-collections:
 *   subjects -> { "maths" -> SubjectStats }
 *
 * Flat map is fine for typical school usage (≤20 subjects × ≤30 chapters).
 */
data class StudentStats(
    @field:PropertyName("user_id")           val userId: String        = "",
    @field:PropertyName("display_name")      val displayName: String   = "",
    @field:PropertyName("school_id")         val schoolId: String      = "",
    @field:PropertyName("grade")             val grade: String         = "",
    @field:PropertyName("last_active_at")    val lastActiveAt: Long    = 0L,

    // ── Overall counters (lifetime) ────────────────────────────────────────────
    @field:PropertyName("total_app_time_ms") val totalAppTimeMs: Long  = 0L,
    @field:PropertyName("total_messages")    val totalMessages: Int    = 0,
    @field:PropertyName("total_bb_sessions") val totalBbSessions: Int  = 0,
    @field:PropertyName("total_quizzes_answered") val totalQuizzesAnswered: Int = 0,
    @field:PropertyName("total_quizzes_correct")  val totalQuizzesCorrect: Int  = 0,
    @field:PropertyName("streak_days")       val streakDays: Int       = 0,
    @field:PropertyName("last_active_date")  val lastActiveDate: String = "", // "YYYY-MM-DD"

    // ── Per-subject map { subjectKey -> SubjectStats } ─────────────────────────
    // Firestore deserializes nested maps. Each SubjectStats also contains
    // a chapters map inside it.
    @field:PropertyName("subjects")          val subjects: Map<String, SubjectStats> = emptyMap()
) {
    /** Quiz accuracy 0–100, or -1 if no quizzes attempted. */
    val quizAccuracy: Int get() =
        if (totalQuizzesAnswered == 0) -1
        else (totalQuizzesCorrect * 100 / totalQuizzesAnswered)

    /** Total app time formatted as "Xh Ym" */
    val appTimeFormatted: String get() {
        val totalMins = totalAppTimeMs / 60_000
        val hours = totalMins / 60
        val mins  = totalMins % 60
        return when {
            hours > 0 -> "${hours}h ${mins}m"
            mins  > 0 -> "${mins}m"
            else      -> "<1m"
        }
    }
}

data class SubjectStats(
    @field:PropertyName("subject_name")      val subjectName: String  = "",
    @field:PropertyName("messages")          val messages: Int        = 0,
    @field:PropertyName("bb_sessions")       val bbSessions: Int      = 0,
    @field:PropertyName("app_time_ms")       val appTimeMs: Long      = 0L,
    @field:PropertyName("quizzes_answered")  val quizzesAnswered: Int = 0,
    @field:PropertyName("quizzes_correct")   val quizzesCorrect: Int  = 0,
    @field:PropertyName("last_active_at")    val lastActiveAt: Long   = 0L,

    // ── Per-chapter map { chapterKey -> ChapterStats } ─────────────────────────
    @field:PropertyName("chapters")          val chapters: Map<String, ChapterStats> = emptyMap()
) {
    val quizAccuracy: Int get() =
        if (quizzesAnswered == 0) -1
        else (quizzesCorrect * 100 / quizzesAnswered)

    val masteryScore: Int get() {
        // Weighted: quiz accuracy 60% + engagement signals 40%
        val qScore = if (quizzesAnswered == 0) 0
                     else (quizzesCorrect * 100 / quizzesAnswered)
        val engScore = minOf(100, messages * 2 + bbSessions * 10 + (appTimeMs / 60_000).toInt())
        return (qScore * 0.6 + engScore * 0.4).toInt().coerceIn(0, 100)
    }
}

data class ChapterStats(
    @field:PropertyName("chapter_name")      val chapterName: String  = "",
    @field:PropertyName("messages")          val messages: Int        = 0,
    @field:PropertyName("bb_sessions")       val bbSessions: Int      = 0,
    @field:PropertyName("app_time_ms")       val appTimeMs: Long      = 0L,
    @field:PropertyName("quizzes_answered")  val quizzesAnswered: Int = 0,
    @field:PropertyName("quizzes_correct")   val quizzesCorrect: Int  = 0,
    @field:PropertyName("last_active_at")    val lastActiveAt: Long   = 0L
) {
    val quizAccuracy: Int get() =
        if (quizzesAnswered == 0) -1
        else (quizzesCorrect * 100 / quizzesAnswered)

    val masteryScore: Int get() {
        val qScore = if (quizzesAnswered == 0) 0
                     else (quizzesCorrect * 100 / quizzesAnswered)
        val engScore = minOf(100, messages * 3 + bbSessions * 12 + (appTimeMs / 60_000).toInt())
        return (qScore * 0.6 + engScore * 0.4).toInt().coerceIn(0, 100)
    }
}

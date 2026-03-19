package com.example.aiguru.utils

import android.content.Context
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.UUID

/**
 * Tracks a single study session for a chapter.
 * Create one instance per screen visit; call endSession() in onStop().
 */
class ChapterMetricsTracker(
    private val subjectName: String,
    private val chapterName: String
) {

    enum class EventType {
        PAGE_VIEWED, QUIZ_REQUESTED, EXPLAIN_USED, SUMMARIZE_USED,
        FORMULA_USED, PRACTICE_USED, VOICE_INPUT, IMAGE_UPLOADED,
        NOTES_SAVED, FLASHCARD_GENERATED, REAL_TEACHER_USED
    }

    private val db = FirebaseFirestore.getInstance()
    private val sessionId = UUID.randomUUID().toString()
    private val startTime = System.currentTimeMillis()

    // In-memory counters — flushed to Firestore only on endSession()
    private val pagesViewed = mutableSetOf<Int>()
    private var messageCount = 0
    private var quizCount = 0
    private var explainCount = 0
    private var summarizeCount = 0
    private var formulaCount = 0
    private var practiceCount = 0
    private var voiceCount = 0
    private var imageCount = 0
    private var notesCount = 0
    private var flashcardCount = 0
    private var realTeacherCount = 0

    private var sessionEnded = false

    // ── Firestore path helper ─────────────────────────────────────────────────

    private fun metricsRef(ctx: Context) =
        db.collection("users").document(SessionManager.getFirestoreUserId(ctx))
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("metrics")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Record a learning event. Thread-safe (all writes are to in-memory fields).
     * Only increments counters; Firestore write happens in endSession().
     */
    fun recordEvent(type: EventType, detail: String = "") {
        messageCount++
        when (type) {
            EventType.QUIZ_REQUESTED -> quizCount++
            EventType.EXPLAIN_USED -> explainCount++
            EventType.SUMMARIZE_USED -> summarizeCount++
            EventType.FORMULA_USED -> formulaCount++
            EventType.PRACTICE_USED -> practiceCount++
            EventType.VOICE_INPUT -> voiceCount++
            EventType.IMAGE_UPLOADED -> imageCount++
            EventType.NOTES_SAVED -> notesCount++
            EventType.FLASHCARD_GENERATED -> flashcardCount++
            EventType.REAL_TEACHER_USED -> realTeacherCount++
            EventType.PAGE_VIEWED -> { /* handled by recordPageViewed */ }
        }
    }

    /** Track which PDF/image pages the student viewed. */
    fun recordPageViewed(pageNumber: Int) {
        pagesViewed.add(pageNumber)
    }

    /**
     * Flush session data to Firestore + update rolling summary + recalculate mastery.
     * Safe to call multiple times — only executes once.
     *
     * @param ctx      Android context for SessionManager
     * @param totalPages Total page count of the chapter (0 if unknown)
     */
    fun endSession(ctx: Context, totalPages: Int = 0) {
        if (sessionEnded) return
        sessionEnded = true

        val endTime = System.currentTimeMillis()
        val durationSeconds = ((endTime - startTime) / 1000).toInt()
        val metricsBase = metricsRef(ctx)

        // 1. Write session doc
        val sessionData = hashMapOf(
            "startTime" to startTime,
            "endTime" to endTime,
            "durationSeconds" to durationSeconds,
            "pagesViewed" to pagesViewed.toList(),
            "messageCount" to messageCount,
            "voiceCount" to voiceCount,
            "imageCount" to imageCount
        )
        metricsBase.document("sessions")
            .collection("log")
            .document(sessionId)
            .set(sessionData)

        // 2. Update summary doc with FieldValue.increment + page merge via transaction
        val summaryRef = metricsBase.document("summary")
        db.runTransaction { tx ->
            val snap = tx.get(summaryRef)

            // Merge existing pagesViewed set
            @Suppress("UNCHECKED_CAST")
            val existingPages = (snap.get("pagesViewed") as? List<Long>)
                ?.map { it.toInt() }?.toMutableSet() ?: mutableSetOf()
            existingPages.addAll(pagesViewed)

            val existingTime = snap.getLong("totalTimeSeconds") ?: 0L
            val existingSessions = snap.getLong("sessionCount") ?: 0L
            val existingMessages = snap.getLong("messageCount") ?: 0L
            val existingVoice = snap.getLong("voiceCount") ?: 0L
            val existingImages = snap.getLong("imageCount") ?: 0L
            val existingQuiz = snap.getLong("quizAttempts") ?: 0L
            val existingExplain = snap.getLong("explainCount") ?: 0L
            val existingSummarize = snap.getLong("summarizeCount") ?: 0L
            val existingNotes = snap.getLong("notesCount") ?: 0L
            val existingFlash = snap.getLong("flashcardCount") ?: 0L
            val existingRealTeacher = snap.getLong("realTeacherCount") ?: 0L
            val existingTotalPages = snap.getLong("totalPages")?.toInt()
                ?: if (totalPages > 0) totalPages else 0
            val resolvedTotalPages = if (totalPages > existingTotalPages) totalPages else existingTotalPages

            val newSummary = hashMapOf<String, Any>(
                "totalTimeSeconds" to existingTime + durationSeconds,
                "sessionCount" to existingSessions + 1,
                "messageCount" to existingMessages + messageCount,
                "voiceCount" to existingVoice + voiceCount,
                "imageCount" to existingImages + imageCount,
                "quizAttempts" to existingQuiz + quizCount,
                "explainCount" to existingExplain + explainCount,
                "summarizeCount" to existingSummarize + summarizeCount,
                "notesCount" to existingNotes + notesCount,
                "flashcardCount" to existingFlash + flashcardCount,
                "realTeacherCount" to existingRealTeacher + realTeacherCount,
                "pagesViewed" to existingPages.toList(),
                "totalPages" to resolvedTotalPages,
                "lastAccessed" to endTime
            )

            // Compute mastery score from updated summary
            newSummary["masteryScore"] = MasteryCalculator.calculate(newSummary, resolvedTotalPages)

            tx.set(summaryRef, newSummary, SetOptions.merge())
        }
    }
}

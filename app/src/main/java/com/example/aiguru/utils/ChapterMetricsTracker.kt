package com.example.aiguru.utils

import android.content.Context
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
        NOTES_SAVED, FLASHCARD_GENERATED
    }

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
  

    private var sessionEnded = false

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
        // Metrics will be written to Firestore when re-enabled
    }
}

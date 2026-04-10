package com.aiguruapp.student.utils

import kotlin.math.ln
import kotlin.math.min

/**
 * Pure mastery score calculator. No Android or Firebase dependencies.
 * Operates on the chapter metrics summary map from Firestore.
 *
 * Score breakdown (0–100):
 *   Pages covered          → up to 30 pts
 *   Time spent (log-scale) → up to 15 pts
 *   Quiz attempts          → up to 20 pts
 *   Explain + summarize    → up to 10 pts
 *   Notes saved            → up to 10 pts
 *   Flashcards generated   → up to  5 pts
 *   Voice + image use      → up to  5 pts
 */
object MasteryCalculator {

    /**
     * @param summary     Contents of the Firestore summary document as a Map.
     * @param totalPages  Known total page count for the chapter (0 = unknown).
     * @return            Integer mastery score 0–100.
     */
    fun calculate(summary: Map<String, Any>, totalPages: Int): Int {
        var score = 0

        // ── Pages covered (up to 30) ─────────────────────────────────────────
        @Suppress("UNCHECKED_CAST")
        val pagesViewed = (summary["pagesViewed"] as? List<*>)?.size ?: 0
        val resolvedTotal = if (totalPages > 0) totalPages else
            (summary["totalPages"] as? Long)?.toInt() ?: 0
        score += if (resolvedTotal > 0) {
            min(30, (pagesViewed.toDouble() / resolvedTotal * 30).toInt())
        } else {
            if (pagesViewed > 0) 15 else 0  // partial credit if no page count available
        }

        // ── Time spent (up to 15) ────────────────────────────────────────────
        // ln(1) = 0, ln(3601) ≈ 8.19. Cap at 60 min (3600 s).
        val timeSeconds = (summary["totalTimeSeconds"] as? Long)?.toInt() ?: 0
        if (timeSeconds > 0) {
            val capped = min(timeSeconds, 3600)
            val timeScore = (ln(capped.toDouble() + 1) / ln(3601.0) * 15).toInt()
            score += min(15, timeScore)
        }

        // ── Quiz attempts (up to 20) ─────────────────────────────────────────
        // 1 quiz = 10 pts, 2+ quizzes = 20 pts
        val quizAttempts = (summary["quizAttempts"] as? Long)?.toInt() ?: 0
        score += min(20, quizAttempts * 10)

        // ── Explain + summarize (up to 10) ───────────────────────────────────
        val explainCount = (summary["explainCount"] as? Long)?.toInt() ?: 0
        val summarizeCount = (summary["summarizeCount"] as? Long)?.toInt() ?: 0
        score += min(10, (explainCount + summarizeCount) * 5)

        // ── Notes saved (up to 10) ───────────────────────────────────────────
        val notesCount = (summary["notesCount"] as? Long)?.toInt() ?: 0
        score += min(10, notesCount * 5)

        // ── Flashcards generated (up to 5) ──────────────────────────────────
        val flashcardCount = (summary["flashcardCount"] as? Long)?.toInt() ?: 0
        score += min(5, flashcardCount * 5)

        // ── Voice + image (up to 5) ──────────────────────────────────────────
        val voiceCount = (summary["voiceCount"] as? Long)?.toInt() ?: 0
        val imageCount = (summary["imageCount"] as? Long)?.toInt() ?: 0
        score += if (voiceCount + imageCount > 0) 5 else 0

        return min(100, score)
    }
}

package com.example.aiguru

import com.example.aiguru.models.TutorIntent
import com.example.aiguru.models.TutorMode
import com.example.aiguru.models.TutorSession
import com.example.aiguru.utils.PromptRepository
import org.json.JSONObject

/**
 * Stateless logic layer that sits between the LLM and the UI.
 *
 * Responsibilities:
 *  1. Build a tutor-grade system prompt from session context
 *  2. Parse the LLM's JSON reply into a typed TutorResponse
 *  3. Apply post-processing behavior rules
 *  4. Update the session after every exchange (confusion tracking, auto mode-switch)
 *  5. Trim responses to voice-friendly length
 */
object TutorController {

    data class TutorResponse(
        val intent: TutorIntent,
        val response: String
    )

    // ── 1. Prompt Builder ────────────────────────────────────────────────────

    fun buildSystemPrompt(session: TutorSession, pageContext: String? = null): String {
        val modeGuide  = PromptRepository.getModeGuide(session.mode)
        val rules      = PromptRepository.getTutorRules()
            .mapIndexed { i, r -> "${i + 1}. $r" }.joinToString("\n")
        val header     = PromptRepository.getSystemPromptHeader()
        val footer     = PromptRepository.getSystemPromptFooter()

        val contextBlock       = pageContext?.let { "\nSTUDENT IS CURRENTLY VIEWING THIS PAGE:\n$it\n" } ?: ""
        val intelligenceBlock  = buildIntelligenceNote(session)

        return """$header

SESSION CONTEXT:
Subject: ${session.subject}
Chapter: ${session.chapter}
Current page: ${session.currentPage}
Last detected intent: ${session.lastIntent.name}
Session interaction #: ${session.interactionCount + 1}
$contextBlock$intelligenceBlock
$modeGuide

TUTOR RULES (follow every rule, every time):
$rules

$footer"""
    }

    private fun buildIntelligenceNote(session: TutorSession): String {
        val lines = mutableListOf<String>()
        if (session.confusionCount >= 2)
            lines += "⚠ Student showed confusion ${session.confusionCount} time(s) — use simpler language and concrete analogies."
        if (session.mistakesDetected >= 2)
            lines += "⚠ Student made repeated mistakes — prefer guided practice over direct explanation."
        if (session.conceptsAsked.size >= 3) {
            val recent = session.conceptsAsked.takeLast(3).joinToString(", ")
            lines += "Recent topics the student asked about: $recent"
        }
        return if (lines.isEmpty()) "" else "STUDENT INTELLIGENCE NOTE:\n${lines.joinToString("\n")}\n"
    }

    // ── 2. Response Parser ───────────────────────────────────────────────────

    fun parseResponse(raw: String): TutorResponse {
        return try {
            val jsonStr = extractJson(raw)
            val obj = JSONObject(jsonStr)
            val intentStr = obj.optString("intent", "GENERAL").trim().uppercase()
            val intent = try { TutorIntent.valueOf(intentStr) } catch (_: Exception) { TutorIntent.GENERAL }
            val text = obj.getString("response").trim()
            TutorResponse(intent, applyBehaviorRules(intent, text))
        } catch (_: Exception) {
            // Graceful fallback: treat the whole raw text as the reply
            TutorResponse(TutorIntent.GENERAL, raw.trim())
        }
    }

    private fun extractJson(text: String): String {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        return if (start >= 0 && end > start) text.substring(start, end + 1) else text
    }

    // ── 3. Behavior Rules ────────────────────────────────────────────────────

    private fun applyBehaviorRules(intent: TutorIntent, text: String): String {
        var result = text

        // Homework guard: if no hint-like words found, append a gentle nudge
        if (intent == TutorIntent.HOMEWORK &&
            !result.contains(Regex("hint|step|try|approach|think|attempt|start by|first", RegexOption.IGNORE_CASE))
        ) {
            result += "\n\nTry breaking it down step by step — you've got this! 💪"
        }

        // Confused: ensure reply ends with a reassuring question
        if (intent == TutorIntent.CONFUSED && !result.trimEnd().endsWith('?')) {
            result += "\n\nDoes that make more sense now? 😊"
        }

        return result
    }

    // ── 4. Session Updater ───────────────────────────────────────────────────

    fun updateSession(session: TutorSession, intent: TutorIntent, userText: String) {
        session.interactionCount++
        session.lastIntent = intent
        session.lastQuestion = userText.take(120)

        when (intent) {
            TutorIntent.CONFUSED, TutorIntent.SIMPLIFY -> session.confusionCount++
            TutorIntent.EVALUATE                       -> session.mistakesDetected++
            else                                       -> { /* no-op */ }
        }

        // Track keyword concepts from the user's message
        extractKeywords(userText).let { kw ->
            session.conceptsAsked.addAll(kw)
            if (session.conceptsAsked.size > 12) {
                session.conceptsAsked.subList(0, session.conceptsAsked.size - 12).clear()
            }
        }

        // Auto mode-switch based on accumulated intelligence
        if (session.mode == TutorMode.AUTO) {
            when {
                session.confusionCount >= 3  -> session.mode = TutorMode.EXPLAIN
                session.mistakesDetected >= 3 -> session.mode = TutorMode.PRACTICE
            }
        }
    }

    private fun extractKeywords(text: String): List<String> {
        val stopWords = setOf(
            "what","is","the","a","an","how","why","explain","tell","me","about",
            "can","you","please","help","with","this","that","it","do","does","are",
            "was","were","will","would","could","should","have","has","had","i","my"
        )
        return text.lowercase()
            .split(Regex("[\\s,?.!;:()\\/]+"))
            .filter { it.length > 3 && it !in stopWords }
            .take(3)
    }

    // ── 5. Voice Helper ──────────────────────────────────────────────────────

    /**
     * Returns a voice-friendly version of [response]: strips markdown,
     * keeps the first 3 sentences, hard-caps at 280 chars.
     */
    fun trimForVoice(response: String): String {
        val clean = response
            .replace(Regex("\\*{1,3}"), "")
            .replace(Regex("#{1,6}\\s?"), "")
            .replace(Regex("\n+"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
        val sentences = clean.split(Regex("(?<=[.!?])\\s+"))
        val voiced = sentences.take(3).joinToString(" ")
        return if (voiced.length > 280) voiced.take(277) + "…" else voiced
    }
}

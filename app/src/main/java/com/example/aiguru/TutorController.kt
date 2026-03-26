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
        val chapterContextBlock = buildChapterContextBlock(session)
        val intelligenceBlock  = buildIntelligenceNote(session)

        return """$header

SESSION CONTEXT:
Subject: ${session.subject}
Chapter: ${session.chapter}
Current page: ${session.currentPage}
Last detected intent: ${session.lastIntent.name}
Session interaction #: ${session.interactionCount + 1}
$contextBlock$chapterContextBlock$intelligenceBlock
$modeGuide

TUTOR RULES (follow every rule, every time):
$rules

$footer"""
    }

    private fun buildChapterContextBlock(session: TutorSession): String {
        val lines = mutableListOf<String>()
        if (session.chapterSummary.isNotBlank()) {
            lines += "Chapter summary: ${session.chapterSummary.trim()}"
        }
        if (session.latestPageContext.isNotBlank()) {
            lines += "Latest page transcript:\n${session.latestPageContext.trim()}"
        }
        if (lines.isEmpty()) return ""
        return "CHAPTER CONTENT CONTEXT:\n${lines.joinToString("\n\n")}\n"
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
     * Converts a markdown AI response into clean, natural speech text.
     *
     * Handles: code blocks, tables, headers, bold/italic, bullets, numbered lists,
     * inline code, math symbols, scientific notation, common Unicode symbols.
     * Unlike trimForVoice(), this does NOT truncate — returns the full readable text
     * so the [▶ Speak] button reads the complete message.
     *
     * For voice-loop auto-speak (where we want brevity), use [prepareSpeechText]
     * then take sentences as needed.
     */
    fun prepareSpeechText(markdown: String): String {
        return markdown
            // Code blocks → spoken cue so listener knows it was omitted
            .replace(Regex("```[\\s\\S]*?```"), " Code block omitted. ")

            // Tables → skip entirely (unreadable when spoken)
            .replace(Regex("(?m)^\\|.*\\|\\s*$"), "")
            .replace(Regex("(?m)^[-|: ]+$"), "")

            // Links → keep link text only
            .replace(Regex("\\[(.*?)\\]\\(.*?\\)"), "$1")

            // Headers → strip # prefix, add natural pause via period
            .replace(Regex("#{1,6}\\s+(.*)"), "$1.")

            // Bold / italic / underline
            .replace(Regex("\\*\\*\\*(.*?)\\*\\*\\*"), "$1")
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1")
            .replace(Regex("(?<!\\*)\\*(?!\\*)(.*?)(?<!\\*)\\*(?!\\*)"), "$1")
            .replace(Regex("__(.*?)__"), "$1")
            .replace(Regex("(?<!_)_(?!_)(.*?)(?<!_)_(?!_)"), "$1")

            // Strikethrough
            .replace(Regex("~~(.*?)~~"), "$1")

            // Numbered lists → keep as natural sentences
            .replace(Regex("(?m)^\\s*\\d+\\.\\s+"), ". ")

            // Bullet points → add brief pause
            .replace(Regex("(?m)^\\s*[-*•]\\s+"), ". ")

            // Inline code → remove backticks, keep content
            .replace(Regex("`([^`]+)`"), "$1")

            // Math / science symbols → spoken equivalents
            .replace(Regex("\\$\\$.*?\\$\\$"), " math expression ")
            .replace("\u00b2", " squared")
            .replace("\u00b3", " cubed")
            .replace("\u221a", " root of ")
            .replace("\u03b1", "alpha").replace("\u03b2", "beta")
            .replace("\u03b3", "gamma").replace("\u03b4", "delta")
            .replace("\u03c0", "pi").replace("\u03b8", "theta")
            .replace("\u03bb", "lambda").replace("\u03a3", "sigma")
            .replace("\u2206", "delta ").replace("\u221e", "infinity")
            .replace("\u2248", " approximately ").replace("\u2260", " not equal to ")
            .replace("\u2264", " less than or equal to ").replace("\u2265", " greater than or equal to ")
            .replace("\u00b1", " plus or minus ").replace("\u00d7", " times ")
            .replace("\u00f7", " divided by ").replace("%", " percent")
            .replace("\u2192", " leads to ")
            .replace("\u2190", " from ")
            .replace("\u2194", " corresponds to ")
            .replace("\u21d2", " therefore ")
            .replace("\u2234", " therefore ")

            // Scientific exponents: e.g. 1.5e10, 6.022E23
            .replace(Regex("([0-9.]+)[eE]([+-]?[0-9]+)")) { m ->
                "${m.groupValues[1]} times 10 to the power ${m.groupValues[2]}"
            }

            // Superscript ^2 / ^{n+1}
            .replace(Regex("\\^\\{([^}]+)\\}"), " to the power $1")
            .replace(Regex("\\^([0-9a-zA-Z+\\-]+)"), " to the power $1")

            // Subscript _{2} or H_2
            .replace(Regex("_\\{([^}]+)\\}"), " sub $1")
            .replace(Regex("(?<=[A-Za-z])_([0-9]+)"), " $1")

            // Remaining markdown symbols
            .replace(Regex("[#*`_~|]"), "")

            // Normalize whitespace and blank lines
            .replace(Regex("\n{3,}"), "\n\n")
            .replace(Regex("[ \t]+"), " ")
            .lines()
            .joinToString(" ") { it.trim() }
            .replace(Regex("\\s{2,}"), " ")
            .replace(Regex("(\\.\\s*){2,}"), ". ")
            .trim()
            // Remove emojis (covers most emoji ranges)
            .replace(
                Regex("[\\p{So}\\p{Cn}]"), ""
            )
    }

    /**
     * Keeps only the first [sentenceCount] sentences from [prepareSpeechText] output.
     * Use for voice-loop auto-speaking where brevity matters.
     */
    fun prepareSpeechTextBrief(markdown: String, sentenceCount: Int = 3): String {
        val full = prepareSpeechText(markdown)
        val sentences = full.split(Regex("(?<=[.!?])\\s+"))
        return sentences.take(sentenceCount).joinToString(" ")
    }
}

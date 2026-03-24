package com.example.aiguru.chat

import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Generates a concise summary of a chat session and persists it to Firestore at:
 *   conversations/{conversationId}.summary
 *
 * Called from ChatActivity.onStop() when there are enough messages to summarize.
 */
object ConversationSummarizer {

    private const val MIN_MESSAGES_TO_SUMMARIZE = 4

    /**
     * Build a plain-text transcript from the message list (last 30 messages max
     * to stay within token limits), then stream it through the AiClient.
     * The result is saved to Firestore as the conversation summary.
     */
    suspend fun summarize(
        messages: List<Message>,
        subject: String,
        chapter: String,
        userId: String,
        aiClient: AiClient
    ) {
        if (messages.size < MIN_MESSAGES_TO_SUMMARIZE) return

        val transcript = messages.takeLast(30).joinToString("\n") { msg ->
            val role = if (msg.isUser) "Student" else "Tutor"
            "$role: ${msg.content.take(300)}"
        }

        val systemPrompt = """You are a concise academic summarizer.
Given the following tutoring conversation transcript for the subject "$subject", chapter "$chapter",
write a SHORT summary (3–5 sentences) covering:
- The main topic(s) the student asked about
- Key concepts explained
- Any confusion or difficulty the student had
- How the tutor resolved it (if applicable)

Be factual and brief. Output plain text only — no markdown, no bullet points."""

        val accumulated = StringBuilder()
        withContext(Dispatchers.IO) {
            try {
                aiClient.streamText(
                    systemPrompt = systemPrompt,
                    userText     = "Transcript:\n$transcript",
                    onToken      = { token -> accumulated.append(token) },
                    onDone       = { _, _, _ ->
                        val summary = accumulated.toString().trim()
                        if (summary.isNotBlank()) {
                            FirestoreManager.updateConversationSummary(
                                userId  = userId,
                                subject = subject,
                                chapter = chapter,
                                summary = summary
                            )
                        }
                    },
                    onError      = { /* silently ignore summary errors */ }
                )
            } catch (_: Exception) { /* don't crash if summarization fails */ }
        }
    }
}

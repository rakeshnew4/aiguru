package com.aiguruapp.student.chat

import android.content.Context
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.Message

/**
 * Handles loading and persisting chat messages for one subject+chapter session.
 *
 * Firestore schema:
 *   conversations/{userId}__{subject}__{chapter}/
 *     - userId, subject, chapter, createdAt, lastMessage, summary
 *     messages/{messageId}
 *       - role: "user" | "model"
 *       - text: String
 *       - timestamp: Long
 *       - tokens: Int? (optional)
 *
 * Local offline mirror:
 *   filesDir/offline_chat/{userId}__{subject}__{chapter}.jsonl
 *   Each line is a JSON object with role, text, timestamp, messageId.
 */
class ChatHistoryRepository(
    private val userId:   String,
    private val subject:  String,
    private val chapter:  String,
    private val context:  Context? = null
) {
    // ── Local file helpers ─────────────────────────────────────────────────────

    private fun localFile(): java.io.File? {
        val ctx = context ?: return null
        val dir = java.io.File(ctx.filesDir, "offline_chat")
        dir.mkdirs()
        val name = "${userId}__${subject}__${chapter}".replace("/", "_").take(200)
        return java.io.File(dir, "$name.jsonl")
    }

    private fun appendLocalLine(role: String, text: String, messageId: String, timestamp: Long) {
        try {
            val obj = org.json.JSONObject().apply {
                put("role", role)
                put("text", text)
                put("messageId", messageId)
                put("timestamp", timestamp)
            }
            localFile()?.appendText(obj.toString() + "\n")
        } catch (_: Exception) {}
    }

    private fun readLocalMessages(): List<Message>? {
        try {
            val file = localFile() ?: return null
            if (!file.exists()) return null
            return file.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                try {
                    val obj = org.json.JSONObject(line)
                    val role = obj.optString("role", "user")
                    val id   = obj.optString("messageId")
                    if (id.isBlank()) return@mapNotNull null
                    Message(
                        id        = id,
                        content   = obj.optString("text"),
                        isUser    = role == "user",
                        timestamp = obj.optLong("timestamp", 0L)
                    )
                } catch (_: Exception) { null }
            }.takeIf { it.isNotEmpty() }
        } catch (_: Exception) { return null }
    }

    // ── Public API ─────────────────────────────────────────────────────────────
    fun loadHistory(
        onMessages: (List<Message>) -> Unit,
        onEmpty:    () -> Unit
    ) {
        FirestoreManager.loadMessages(userId, subject, chapter,
            onSuccess = { list ->
                if (list.isEmpty()) {
                    // Try local offline cache before giving up
                    val local = readLocalMessages()
                    if (!local.isNullOrEmpty()) onMessages(local) else onEmpty()
                } else {
                    val messages = list.mapNotNull { map ->
                        try {
                            val role = map["role"] as? String ?: "user"
                            // Prefer the explicit messageId field (written since the last migration).
                            // Fall back to the Firestore doc ID (_docId injected by loadMessages).
                            // Never generate a random UUID — messageId must be stable and
                            // match the blackboard cache key.
                            val id = (map["messageId"] as? String)
                                ?: (map["_docId"] as? String)
                                ?: return@mapNotNull null
                            val storedImageUrl = map["imageUrl"] as? String
                            Message(
                                id          = id,
                                content     = map["text"] as? String ?: "",
                                isUser      = role == "user",
                                timestamp   = (map["timestamp"] as? Long) ?: 0L,
                                imageUrl    = storedImageUrl,
                                messageType = if (storedImageUrl != null) Message.MessageType.IMAGE
                                              else Message.MessageType.TEXT,
                                transcription = map["transcription"] as? String ?: "",
                                extraSummary  = map["extraSummary"] as? String ?: ""
                            )
                        } catch (_: Exception) { null }
                    }
                    if (messages.isEmpty()) onEmpty() else onMessages(messages)
                }
            },
            onFailure = {
                // Offline or Firestore error — serve from local cache
                val local = readLocalMessages()
                if (!local.isNullOrEmpty()) onMessages(local) else onEmpty()
            }
        )
    }

    fun saveMessage(message: Message, tokens: Int? = null, inputTokens: Int? = null, outputTokens: Int? = null) {
        val role = if (message.isUser) "user" else "model"
        if (message.id.isBlank()) return
        // Always write to local offline file first — works even without connectivity
        appendLocalLine(role, message.content, message.id, message.timestamp)
        FirestoreManager.saveMessage(
            userId       = userId,
            subject      = subject,
            chapter      = chapter,
            messageId    = message.id,
            text         = message.content,
            role         = role,
            timestamp    = message.timestamp,
            tokens       = tokens,
            inputTokens  = inputTokens,
            outputTokens = outputTokens,
            imageUrl     = message.imageUrl,
            transcription = message.transcription,
            extraSummary  = message.extraSummary
        )
    }

    fun clearHistory(onSuccess: () -> Unit = {}, onFailure: (Exception?) -> Unit = {}) {
        FirestoreManager.deleteAllMessages(userId, subject, chapter, onSuccess, onFailure)
    }
}


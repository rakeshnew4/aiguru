package com.example.aiguru.chat

import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.Message
import java.util.UUID

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
 */
class ChatHistoryRepository(
    private val userId:  String,
    private val subject: String,
    private val chapter: String
) {
    fun loadHistory(
        onMessages: (List<Message>) -> Unit,
        onEmpty:    () -> Unit
    ) {
        FirestoreManager.loadMessages(userId, subject, chapter,
            onSuccess = { list ->
                if (list.isEmpty()) {
                    onEmpty()
                } else {
                    val messages = list.mapNotNull { map ->
                        try {
                            val role = map["role"] as? String ?: "user"
                            Message(
                                id        = (map["messageId"] as? String) ?: UUID.randomUUID().toString(),
                                content   = map["text"] as? String ?: "",
                                isUser    = role == "user",
                                timestamp = (map["timestamp"] as? Long) ?: 0L
                            )
                        } catch (_: Exception) { null }
                    }
                    if (messages.isEmpty()) onEmpty() else onMessages(messages)
                }
            },
            onFailure = { onEmpty() }
        )
    }

    fun saveMessage(message: Message, tokens: Int? = null) {
        val role = if (message.isUser) "user" else "model"
        FirestoreManager.saveMessage(
            userId    = userId,
            subject   = subject,
            chapter   = chapter,
            messageId = message.id.ifBlank { UUID.randomUUID().toString() },
            text      = message.content,
            role      = role,
            timestamp = message.timestamp,
            tokens    = tokens
        )
    }
}


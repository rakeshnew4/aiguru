package com.example.aiguru.chat

import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.Message

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
                                              else Message.MessageType.TEXT
                            )
                        } catch (_: Exception) { null }
                    }
                    if (messages.isEmpty()) onEmpty() else onMessages(messages)
                }
            },
            onFailure = { onEmpty() }
        )
    }

    fun saveMessage(message: Message, tokens: Int? = null, inputTokens: Int? = null, outputTokens: Int? = null) {
        val role = if (message.isUser) "user" else "model"
        // messageId must always be the real message ID — never generate a new UUID here.
        // If message.id is blank the message wasn't given an ID at creation time,
        // which is a caller bug; skip saving rather than creating an orphaned doc.
        if (message.id.isBlank()) return
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
            imageUrl     = message.imageUrl
        )
    }

    fun clearHistory(onSuccess: () -> Unit = {}, onFailure: (Exception?) -> Unit = {}) {
        FirestoreManager.deleteAllMessages(userId, subject, chapter, onSuccess, onFailure)
    }
}


package com.example.aiguru.chat

import com.example.aiguru.models.Message
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Handles loading and persisting chat messages for one subject+chapter session.
 * Firestore path: users/{userId}/chats/{subject}_{chapter}/messages/
 */
class ChatHistoryRepository(
    private val db:      FirebaseFirestore,
    private val userId:  String,
    private val subject: String,
    private val chapter: String
) {

    private fun messagesRef() = db
        .collection("users").document(userId)
        .collection("chats").document("${subject}_${chapter}")
        .collection("messages")

    /**
     * Fetches the last 50 messages ordered by timestamp.
     * [onMessages] is called on the Firestore thread (main) with the list when history exists.
     * [onEmpty]    is called when there are no messages yet (show welcome screen).
     */
    fun loadHistory(
        onMessages: (List<Message>) -> Unit,
        onEmpty:    () -> Unit
    ) {
        messagesRef()
            .orderBy("timestamp")
            .limit(50)
            .get()
            .addOnSuccessListener { snapshot ->
                val messages = snapshot.documents.mapNotNull { doc ->
                    try {
                        Message(
                            id          = doc.id,
                            content     = doc.getString("content") ?: "",
                            isUser      = doc.getBoolean("isUser") ?: true,
                            timestamp   = doc.getLong("timestamp") ?: System.currentTimeMillis(),
                            messageType = Message.MessageType.valueOf(
                                doc.getString("messageType") ?: "TEXT"
                            )
                        )
                    } catch (_: Exception) { null }
                }
                if (messages.isEmpty()) onEmpty() else onMessages(messages)
            }
            .addOnFailureListener { onEmpty() }
    }

    /** Fire-and-forget save. Ignores failures silently (non-critical). */
    fun saveMessage(message: Message) {
        messagesRef()
            .document(message.id)
            .set(mapOf(
                "id"          to message.id,
                "content"     to message.content,
                "isUser"      to message.isUser,
                "timestamp"   to message.timestamp,
                "messageType" to message.messageType.name
            ))
    }
}

package com.example.aiguru.chat

import com.example.aiguru.models.Message

/**
 * Handles loading and persisting chat messages for one subject+chapter session.
 * Firestore sync is disabled — will be re-enabled later.
 * Currently always starts a fresh session (no history persistence).
 */
class ChatHistoryRepository(
    private val userId:  String,
    private val subject: String,
    private val chapter: String
) {
    /** Always reports empty — history persistence requires Firestore (disabled for now). */
    fun loadHistory(
        onMessages: (List<Message>) -> Unit,
        onEmpty:    () -> Unit
    ) {
        onEmpty()
    }

    /** No-op — message persistence disabled without Firestore. */
    fun saveMessage(message: Message) {
        // Will save to Firestore when re-enabled
    }
}

package com.aiguruapp.student.models

import java.io.Serializable

/**
 * Data class representing a chat message
 */
data class Message(
    val id: String = "",
    val content: String = "",
    val isUser: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val imageUrl: String? = null,
    val imageBase64: String? = null,
    val voiceUrl: String? = null,
    val pdfUrl: String? = null,
    val messageType: MessageType = MessageType.TEXT, // TEXT, IMAGE, VOICE, PDF
    /** Full transcription of any attached image/PDF — stored for LLM context, not shown in UI */
    val transcription: String = "",
    /** Extra details/summary field from LLM JSON — stored for LLM context */
    val extraSummary: String = ""
) : Serializable {
    enum class MessageType {
        TEXT, IMAGE, VOICE, PDF, MIXED
    }
}

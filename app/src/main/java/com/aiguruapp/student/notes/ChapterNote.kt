package com.aiguruapp.student.notes

import org.json.JSONObject

/**
 * A single note saved by the student for a chapter.
 *
 * type = "ai"    – saved from an AI chat message
 * type = "image" – cropped from a photo/scan
 * type = "text"  – typed by the student directly
 */
data class ChapterNote(
    val id: String,
    val type: String,
    val content: String,              // AI text / user typed text
    val userAnnotation: String = "",  // student's own additions
    val imageUri: String? = null,     // file:// or content:// path of saved crop
    val category: String = "General",
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("type", type)
        put("content", content)
        put("userAnnotation", userAnnotation)
        imageUri?.let { put("imageUri", it) }
        put("category", category)
        put("timestamp", timestamp)
    }

    companion object {
        fun fromJson(obj: JSONObject) = ChapterNote(
            id             = obj.getString("id"),
            type           = obj.optString("type", "text"),
            content        = obj.optString("content"),
            userAnnotation = obj.optString("userAnnotation"),
            imageUri       = obj.optString("imageUri").takeIf { it.isNotBlank() },
            category       = obj.optString("category", "General"),
            timestamp      = obj.optLong("timestamp", System.currentTimeMillis())
        )
    }
}

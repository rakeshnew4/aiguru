package com.example.aiguru.chat

import android.content.Context

/**
 * Handles saving and loading AI-generated notes for one subject+chapter.
 * Stored locally in SharedPreferences. Firestore sync will be added later.
 */
class NotesRepository(
    private val context: Context,
    private val userId:  String,
    private val subject: String,
    private val chapter: String
) {
    private fun prefs() = context.getSharedPreferences("notes_prefs", Context.MODE_PRIVATE)
    private fun key(type: String) = "notes_${userId}_${subject}_${chapter}_$type"
    private val prefix get() = "notes_${userId}_${subject}_${chapter}_"

    fun save(
        content:   String,
        type:      String,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {}
    ) {
        try {
            prefs().edit().putString(key(type), content).apply()
            onSuccess()
        } catch (_: Exception) {
            onFailure()
        }
    }

    fun loadAll(
        onResult:  (String) -> Unit,
        onEmpty:   () -> Unit,
        onFailure: () -> Unit
    ) {
        try {
            val matching = prefs().all.entries
                .filter { it.key.startsWith(prefix) }
                .sortedBy { entry ->
                    val type = entry.key.removePrefix(prefix)
                    when (type) { "chapter" -> "0"; "exercises" -> "z"; else -> type }
                }
            if (matching.isEmpty()) { onEmpty(); return }
            val sb = StringBuilder()
            matching.forEach { entry ->
                val type    = entry.key.removePrefix(prefix)
                val content = entry.value as? String ?: return@forEach
                if (content.isBlank()) return@forEach
                val heading = when {
                    type == "chapter"        -> "📖 Chapter Notes"
                    type.startsWith("page_") -> "📄 Page ${type.removePrefix("page_")} Notes"
                    type == "exercises"      -> "✏️ Exercise Notes"
                    else                     -> "📋 $type"
                }
                sb.append("$heading\n\n$content\n\n──────────────\n\n")
            }
            val text = sb.toString().trim()
            if (text.isEmpty()) onEmpty() else onResult(text)
        } catch (_: Exception) {
            onFailure()
        }
    }
}

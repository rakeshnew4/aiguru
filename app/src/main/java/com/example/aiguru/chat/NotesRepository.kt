package com.example.aiguru.chat

import com.google.firebase.firestore.FirebaseFirestore

/**
 * Handles saving and loading AI-generated notes for one subject+chapter.
 * Firestore path: users/{userId}/subjects/{subject}/chapters/{chapter}/notes/{type}
 */
class NotesRepository(
    private val db:      FirebaseFirestore,
    private val userId:  String,
    private val subject: String,
    private val chapter: String
) {

    private fun notesRef() = db
        .collection("users").document(userId)
        .collection("subjects").document(subject)
        .collection("chapters").document(chapter)
        .collection("notes")

    /** Saves [content] under the given [type] document (e.g. "chapter", "page_1"). */
    fun save(
        content:   String,
        type:      String,
        onSuccess: () -> Unit = {},
        onFailure: () -> Unit = {}
    ) {
        notesRef().document(type)
            .set(mapOf(
                "content"   to content,
                "type"      to type,
                "updatedAt" to System.currentTimeMillis()
            ))
            .addOnSuccessListener { onSuccess() }
            .addOnFailureListener { onFailure() }
    }

    /**
     * Loads all saved notes and concatenates them into a single formatted string.
     * [onResult]  — combined notes text (non-empty).
     * [onEmpty]   — no notes saved yet.
     * [onFailure] — Firestore error.
     */
    fun loadAll(
        onResult:  (String) -> Unit,
        onEmpty:   () -> Unit,
        onFailure: () -> Unit
    ) {
        notesRef().get()
            .addOnSuccessListener { snapshot ->
                if (snapshot.isEmpty) { onEmpty(); return@addOnSuccessListener }

                val sorted = snapshot.documents.sortedBy { doc ->
                    when (doc.id) { "chapter" -> "0"; "exercises" -> "z"; else -> doc.id }
                }
                val sb = StringBuilder()
                sorted.forEach { doc ->
                    val type    = doc.getString("type") ?: doc.id
                    val content = doc.getString("content") ?: return@forEach
                    if (content.isBlank()) return@forEach
                    val heading = when {
                        type == "chapter"         -> "📖 Chapter Notes"
                        type.startsWith("page_")  -> "📄 Page ${type.removePrefix("page_")} Notes"
                        type == "exercises"        -> "✏️ Exercise Notes"
                        else                       -> "📋 $type"
                    }
                    sb.append("$heading\n\n$content\n\n──────────────\n\n")
                }
                val text = sb.toString().trim()
                if (text.isEmpty()) onEmpty() else onResult(text)
            }
            .addOnFailureListener { onFailure() }
    }
}

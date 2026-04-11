package com.aiguruapp.student.notes

import android.content.Context
import org.json.JSONArray

/**
 * Stores and retrieves per-chapter notes in SharedPreferences as a JSON array.
 * Separate from the legacy NotesRepository (AI-generated notes).
 */
class ChapterNotesRepository(
    private val context: Context,
    private val userId: String,
    private val subject: String,
    private val chapter: String
) {
    private fun prefs() = context.getSharedPreferences("chapter_notes_v2", Context.MODE_PRIVATE)
    private val notesKey get() = "n_${userId}_${subject}_${chapter}"
    private val catsKey  get() = "c_${userId}_${subject}_${chapter}"

    fun loadNotes(): List<ChapterNote> {
        val raw = prefs().getString(notesKey, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { ChapterNote.fromJson(arr.getJSONObject(i)) }.getOrNull()
            }.sortedByDescending { it.timestamp }
        } catch (_: Exception) { emptyList() }
    }

    fun saveNote(note: ChapterNote) {
        val notes = loadNotes().toMutableList()
        val idx = notes.indexOfFirst { it.id == note.id }
        if (idx >= 0) notes[idx] = note else notes.add(0, note)
        persist(notes)
    }

    fun deleteNote(id: String) {
        persist(loadNotes().filter { it.id != id })
    }

    fun getCategories(): List<String> {
        val raw = prefs().getString(catsKey, null)
        if (raw.isNullOrBlank()) return listOf("General")
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }.ifEmpty { listOf("General") }
        } catch (_: Exception) { listOf("General") }
    }

    fun addCategory(name: String): List<String> {
        val cats = getCategories().toMutableList()
        if (!cats.contains(name)) {
            cats.add(name)
            prefs().edit().putString(catsKey, JSONArray(cats).toString()).apply()
        }
        return cats
    }

    private fun persist(notes: List<ChapterNote>) {
        val arr = JSONArray(notes.map { it.toJson() })
        prefs().edit().putString(notesKey, arr.toString()).apply()
    }
}

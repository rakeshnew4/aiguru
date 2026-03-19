package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.ChapterAdapter
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore

class SubjectActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var chaptersRecyclerView: RecyclerView
    private val chaptersListData = mutableListOf<String>()
    private lateinit var chapterAdapter: ChapterAdapter
    private lateinit var subjectName: String

    // Simple data holder for a scanned library book
    private data class LibItem(val title: String, val assetPath: String, val pdfId: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject)

        db = FirebaseFirestore.getInstance()
        subjectName = intent.getStringExtra("subjectName") ?: "Subject"

        findViewById<TextView>(R.id.subjectTitle).text = subjectName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        setupRecyclerView()
        loadChapters()

        findViewById<MaterialButton>(R.id.addChapterButton).setOnClickListener {
            showAddChapterOptions()
        }
    }

    private fun setupRecyclerView() {
        chaptersRecyclerView = findViewById(R.id.chaptersRecyclerView)
        chapterAdapter = ChapterAdapter(
            chapters = chaptersListData,
            onItemClick = { chapter ->
                startActivity(
                    Intent(this, ChapterActivity::class.java)
                        .putExtra("subjectName", subjectName)
                        .putExtra("chapterName", chapter)
                )
            },
            onItemLongClick = { chapter -> showDeleteChapterDialog(chapter) }
        )
        chaptersRecyclerView.layoutManager = LinearLayoutManager(this)
        chaptersRecyclerView.adapter = chapterAdapter
    }

    private fun loadChapters() {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters")
            .get()
            .addOnSuccessListener { documents ->
                chaptersListData.clear()
                for (doc in documents) {
                    val name = doc.getString("name") ?: ""
                    if (name.isNotBlank()) chaptersListData.add(name)
                }
                chapterAdapter.notifyDataSetChanged()
            }
    }

    // ─── Add chapter options ───────────────────────────────────────────────────

    private fun showAddChapterOptions() {
        val options = arrayOf("📝 Type chapter name", "📚 Pick from Library")
        AlertDialog.Builder(this)
            .setTitle("➕ Add Chapter to $subjectName")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showManualChapterDialog()
                    1 -> showLibraryPickerDialog()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showManualChapterDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Chapter 1 - Introduction"
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("📖 Add Chapter")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addManualChapter(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLibraryPickerDialog() {
        val books = scanLibraryBooks()
        if (books.isEmpty()) {
            Toast.makeText(this, "No library PDFs found.", Toast.LENGTH_SHORT).show()
            return
        }
        val labels = books.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("📚 Pick from Library")
            .setItems(labels) { _, idx -> addChapterFromLibrary(books[idx]) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /** Scans assets/library and returns a flat list of all PDF books. */
    private fun scanLibraryBooks(): List<LibItem> {
        val result = mutableListOf<LibItem>()
        try {
            for (grade in (assets.list("library") ?: emptyArray()).sorted()) {
                for (subject in (assets.list("library/$grade") ?: emptyArray()).sorted()) {
                    for (file in (assets.list("library/$grade/$subject") ?: emptyArray())
                        .filter { it.endsWith(".pdf", ignoreCase = true) }.sorted()) {
                        val title = file.removeSuffix(".pdf")
                        val pdfId = "${grade}_${subject}_$title".replace(" ", "_")
                        val assetPath = "library/$grade/$subject/$file"
                        val label = "$title  ($subject · $grade)"
                        result.add(LibItem(title, assetPath, pdfId, label))
                    }
                }
            }
        } catch (_: Exception) { }
        return result
    }

    // ─── Chapter persistence ───────────────────────────────────────────────────

    private fun addManualChapter(name: String) {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(name)
            .set(hashMapOf("name" to name, "order" to chaptersListData.size))
            .addOnSuccessListener {
                chaptersListData.add(name)
                chapterAdapter.notifyItemInserted(chaptersListData.size - 1)
            }
    }

    private fun addChapterFromLibrary(book: LibItem) {
        val userId = SessionManager.getFirestoreUserId(this)
        // Ensure the subject doc exists
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .set(hashMapOf("name" to subjectName), com.google.firebase.firestore.SetOptions.merge())
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(book.title)
            .set(hashMapOf(
                "name"         to book.title,
                "order"        to System.currentTimeMillis(),
                "isPdf"        to true,
                "pdfAssetPath" to book.assetPath,
                "pdfId"        to book.pdfId,
                "pageCount"    to 0
            ))
            .addOnSuccessListener {
                if (!chaptersListData.contains(book.title)) {
                    chaptersListData.add(book.title)
                    chapterAdapter.notifyItemInserted(chaptersListData.size - 1)
                }
                Toast.makeText(this, "\"${book.title}\" added ✓", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to add chapter. Try again.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun showDeleteChapterDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Chapter")
            .setMessage("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ -> deleteChapter(name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteChapter(name: String) {
        val userId = SessionManager.getFirestoreUserId(this)
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(name)
            .delete()
            .addOnSuccessListener {
                val idx = chaptersListData.indexOf(name)
                if (idx >= 0) {
                    chaptersListData.removeAt(idx)
                    chapterAdapter.notifyItemRemoved(idx)
                }
            }
    }
}
package com.example.aiguru

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.LibraryAdapter
import com.example.aiguru.models.LibraryBook
import com.google.firebase.firestore.FirebaseFirestore

class LibraryActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView
    private val books = mutableListOf<LibraryBook>()
    private lateinit var adapter: LibraryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        db = FirebaseFirestore.getInstance()

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        emptyText = findViewById(R.id.emptyText)
        recyclerView = findViewById(R.id.libraryRecyclerView)
        adapter = LibraryAdapter(books) { book -> showAddToSubjectDialog(book) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        scanLibraryAssets()
    }
    private fun scanLibraryAssets() {
        books.clear()
        try {
            val grades = assets.list("library") ?: emptyArray()
            for (grade in grades) {
                val subjects = assets.list("library/$grade") ?: continue
                for (subject in subjects) {
                    val files = assets.list("library/$grade/$subject") ?: continue
                    for (file in files) {
                        if (file.endsWith(".pdf", ignoreCase = true)) {
                            val title = file.removeSuffix(".pdf")
                            val pdfId = "${grade}_${subject}_$title".replace(" ", "_")
                            books.add(
                                LibraryBook(
                                    title = title,
                                    grade = grade,
                                    subject = subject,
                                    assetPath = "library/$grade/$subject/$file",
                                    pdfId = pdfId
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // assets/library folder doesn't exist yet — show empty state
        }

        adapter.notifyDataSetChanged()
        recyclerView.visibility = if (books.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (books.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun showAddToSubjectDialog(book: LibraryBook) {
        db.collection("users").document("testuser123")
            .collection("subjects")
            .get()
            .addOnSuccessListener { docs ->
                val subjects = docs.mapNotNull { it.getString("name") }.toTypedArray()
                if (subjects.isEmpty()) {
                    Toast.makeText(this, "No subjects yet — add a subject first!", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                AlertDialog.Builder(this)
                    .setTitle("➕ Add \"${book.title}\" to…")
                    .setItems(subjects) { _, idx ->
                        addBookAsChapter(book, subjects[idx])
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn't load subjects. Try again.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun addBookAsChapter(book: LibraryBook, subjectName: String) {
        val chapterData = hashMapOf(
            "name" to book.title,
            "order" to System.currentTimeMillis(),
            "isPdf" to true,
            "pdfAssetPath" to book.assetPath,
            "pdfId" to book.pdfId,
            "pageCount" to 0  // updated automatically when first opened
        )
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(book.title)
            .set(chapterData)
            .addOnSuccessListener {
                Toast.makeText(
                    this,
                    "\"${book.title}\" added to $subjectName ✓",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to add chapter. Try again.", Toast.LENGTH_SHORT).show()
            }
    }
}

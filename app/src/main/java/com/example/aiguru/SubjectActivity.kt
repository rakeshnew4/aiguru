package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.ChapterAdapter
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore

class SubjectActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var chaptersRecyclerView: RecyclerView
    private val chaptersListData = mutableListOf<String>()
    private lateinit var chapterAdapter: ChapterAdapter
    private lateinit var subjectName: String

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
            showAddChapterDialog()
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
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters")
            .get()
            .addOnSuccessListener { documents ->
                chaptersListData.clear()
                for (doc in documents) {
                    chaptersListData.add(doc.getString("name") ?: "")
                }
                chapterAdapter.notifyDataSetChanged()
            }
    }

    private fun showAddChapterDialog() {
        val input = EditText(this).apply {
            hint = "e.g. Chapter 1 - Introduction"
            setPadding(40, 24, 40, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("📖 Add Chapter")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addChapter(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteChapterDialog(name: String) {
        AlertDialog.Builder(this)
            .setTitle("Delete Chapter")
            .setMessage("Delete \"$name\"?")
            .setPositiveButton("Delete") { _, _ -> deleteChapter(name) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addChapter(name: String) {
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(name)
            .set(hashMapOf("name" to name, "order" to chaptersListData.size))
            .addOnSuccessListener {
                chaptersListData.add(name)
                chapterAdapter.notifyItemInserted(chaptersListData.size - 1)
            }
    }

    private fun deleteChapter(name: String) {
        db.collection("users").document("testuser123")
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
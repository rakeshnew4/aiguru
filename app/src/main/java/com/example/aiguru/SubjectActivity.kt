package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.firestore.FirebaseFirestore

class SubjectActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var chaptersList: ListView
    private val chaptersListData = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var subjectName: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject)

        db = FirebaseFirestore.getInstance()

        subjectName = intent.getStringExtra("subjectName") ?: "Subject"

        findViewById<TextView>(R.id.subjectTitle).text = subjectName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        chaptersList = findViewById(R.id.chaptersList)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, chaptersListData)
        chaptersList.adapter = adapter

        loadChapters()

        findViewById<Button>(R.id.addChapterButton).setOnClickListener {
            showAddChapterDialog()
        }

        chaptersList.setOnItemClickListener { _, _, position, _ ->
            val chapter = chaptersListData[position]
            val intent = Intent(this, ChapterActivity::class.java)
            intent.putExtra("subjectName", subjectName)
            intent.putExtra("chapterName", chapter)
            startActivity(intent)
        }

        chaptersList.setOnItemLongClickListener { _, _, position, _ ->
            val chapter = chaptersListData[position]
            AlertDialog.Builder(this)
                .setTitle("Delete Chapter")
                .setMessage("Delete $chapter?")
                .setPositiveButton("Delete") { _, _ ->
                    deleteChapter(chapter)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
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
                adapter.notifyDataSetChanged()
            }
    }

    private fun showAddChapterDialog() {
        val input = EditText(this)
        input.hint = "e.g. Chapter 1, Photosynthesis"
        AlertDialog.Builder(this)
            .setTitle("Add Chapter")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) addChapter(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addChapter(name: String) {
        val chapter = hashMapOf("name" to name)
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(name)
            .set(chapter)
            .addOnSuccessListener {
                chaptersListData.add(name)
                adapter.notifyDataSetChanged()
            }
    }

    private fun deleteChapter(name: String) {
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(name)
            .delete()
            .addOnSuccessListener {
                chaptersListData.remove(name)
                adapter.notifyDataSetChanged()
            }
    }
}
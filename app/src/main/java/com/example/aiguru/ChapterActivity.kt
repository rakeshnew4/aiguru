package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.*

class ChapterActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var db: FirebaseFirestore
    private lateinit var pagesList: ListView
    private val pagesListData = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private val PICK_IMAGE_REQUEST = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter)

        auth = FirebaseAuth.getInstance()
        db = FirebaseFirestore.getInstance()

        subjectName = intent.getStringExtra("subjectName") ?: "Subject"
        chapterName = intent.getStringExtra("chapterName") ?: "Chapter"

        findViewById<TextView>(R.id.chapterTitle).text = chapterName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        pagesList = findViewById(R.id.pagesList)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, pagesListData)
        pagesList.adapter = adapter

        loadPages()

        findViewById<Button>(R.id.uploadImageButton).setOnClickListener {
            pickImage()
        }

        findViewById<Button>(R.id.askAIButton).setOnClickListener {
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("subjectName", subjectName)
            intent.putExtra("chapterName", chapterName)
            startActivity(intent)
        }

        pagesList.setOnItemClickListener { _, _, position, _ ->
            val imagePath = pagesListData[position]
            val intent = Intent(this, ChatActivity::class.java)
            intent.putExtra("subjectName", subjectName)
            intent.putExtra("chapterName", chapterName)
            intent.putExtra("imagePath", imagePath)
            startActivity(intent)
        }

        pagesList.setOnItemLongClickListener { _, _, position, _ ->
            AlertDialog.Builder(this)
                .setTitle("Delete Page")
                .setMessage("Delete this page?")
                .setPositiveButton("Delete") { _, _ ->
                    deletePage(position)
                }
                .setNegativeButton("Cancel", null)
                .show()
            true
        }
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, PICK_IMAGE_REQUEST)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == PICK_IMAGE_REQUEST && resultCode == RESULT_OK) {
            val imageUri = data?.data
            imageUri?.let { savePage(it.toString()) }
        }
    }

    private fun savePage(imagePath: String) {
        val userId = "testuser123" // Using hardcoded user ID for no-login mode
        val timestamp = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())
        val page = hashMapOf(
            "path" to imagePath,
            "timestamp" to timestamp
        )
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("pages")
            .add(page)
            .addOnSuccessListener {
                pagesListData.add("Page uploaded - $timestamp")
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "Page saved!", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadPages() {
        val userId = "testuser123" // Using hardcoded user ID for no-login mode
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("pages")
            .get()
            .addOnSuccessListener { documents ->
                pagesListData.clear()
                for (doc in documents) {
                    val timestamp = doc.getString("timestamp") ?: ""
                    pagesListData.add("Page - $timestamp")
                }
                adapter.notifyDataSetChanged()
            }
    }

    private fun deletePage(position: Int) {
        val userId = "testuser123" // Using hardcoded user ID for no-login mode
        db.collection("users").document(userId)
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("pages")
            .get()
            .addOnSuccessListener { documents ->
                documents.documents[position].reference.delete()
                    .addOnSuccessListener {
                        pagesListData.removeAt(position)
                        adapter.notifyDataSetChanged()
                    }
            }
    }
}
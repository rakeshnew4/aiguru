package com.example.aiguru

import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.aiguru.utils.PdfPageManager
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*


class ChapterActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var pagesList: ListView
    private val pagesListData = mutableListOf<String>()
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private var cameraImageUri: Uri? = null
    private val CAMERA_PERMISSION_CODE = 201

    private val pickImageLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { savePage(it.toString()) }
        }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { success ->
            if (success && cameraImageUri != null) savePage(cameraImageUri.toString())
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chapter)

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
            showImageSourceDialog()
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

    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add Textbook Page")
            .setItems(arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> pickImageLauncher.launch("image/*")
                }
            }
            .show()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
            return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_Page_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraImageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraImageUri?.let { cameraLauncher.launch(it) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE &&
            grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            openCamera()
        }
    }

    private fun pickImage() {
        pickImageLauncher.launch("image/*")
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
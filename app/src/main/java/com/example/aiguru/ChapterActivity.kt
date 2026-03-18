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

    // PDF chapter state
    private var isPdfChapter = false
    private var pdfAssetPath = ""
    private var pdfId = ""
    private lateinit var pdfPageManager: PdfPageManager

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
        pdfPageManager = PdfPageManager(this)

        subjectName = intent.getStringExtra("subjectName") ?: "Subject"
        chapterName = intent.getStringExtra("chapterName") ?: "Chapter"

        findViewById<TextView>(R.id.chapterTitle).text = chapterName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        pagesList = findViewById(R.id.pagesList)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, pagesListData)
        pagesList.adapter = adapter

        // Long-click delete (image chapters only)
        pagesList.setOnItemLongClickListener { _, _, position, _ ->
            if (!isPdfChapter) {
                AlertDialog.Builder(this)
                    .setTitle("Delete Page")
                    .setMessage("Delete this page?")
                    .setPositiveButton("Delete") { _, _ -> deletePage(position) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            true
        }

        // Check Firestore to determine if this chapter is a PDF chapter
        loadChapterType()
    }

    // ─── Chapter type detection ───────────────────────────────────────────────

    private fun loadChapterType() {
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .get()
            .addOnSuccessListener { doc ->
                isPdfChapter = doc.getBoolean("isPdf") ?: false
                if (isPdfChapter) {
                    pdfAssetPath = doc.getString("pdfAssetPath") ?: ""
                    pdfId = doc.getString("pdfId") ?: ""
                    setupPdfChapter()
                } else {
                    setupImageChapter()
                }
            }
            .addOnFailureListener { setupImageChapter() }
    }

    // ─── PDF chapter ──────────────────────────────────────────────────────────

    private fun setupPdfChapter() {
        // Hide upload button — not applicable for PDF chapters
        findViewById<Button>(R.id.uploadImageButton).visibility = View.GONE

        // Update Ask AI button label
        findViewById<Button>(R.id.askAIButton).apply {
            text = "💬 Ask AI about this Chapter"
            setOnClickListener {
                startActivity(
                    Intent(this@ChapterActivity, ChatActivity::class.java)
                        .putExtra("subjectName", subjectName)
                        .putExtra("chapterName", chapterName)
                )
            }
        }

        pagesListData.clear()
        pagesListData.add("⏳ Loading PDF pages…")
        adapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = pdfPageManager.getPageCount(pdfId, pdfAssetPath)

                // Persist page count back to Firestore if it was 0
                db.collection("users").document("testuser123")
                    .collection("subjects").document(subjectName)
                    .collection("chapters").document(chapterName)
                    .update("pageCount", count)

                withContext(Dispatchers.Main) {
                    pagesListData.clear()
                    for (i in 1..count) {
                        pagesListData.add("📄  Page $i")
                    }
                    adapter.notifyDataSetChanged()

                    // Tapping a page renders it and opens ChatActivity with that page pre-attached
                    pagesList.setOnItemClickListener { _, _, position, _ ->
                        openPdfPageInChat(position)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pagesListData.clear()
                    pagesListData.add("⚠️ Failed to load PDF: ${e.message}")
                    adapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun openPdfPageInChat(pageIndex: Int) {
        val pageNum = pageIndex + 1
        Toast.makeText(this, "Rendering page $pageNum…", Toast.LENGTH_SHORT).show()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pageFile = pdfPageManager.getPage(pdfId, pdfAssetPath, pageIndex)
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent(this@ChapterActivity, ChatActivity::class.java)
                            .putExtra("subjectName", subjectName)
                            .putExtra("chapterName", chapterName)
                            .putExtra("pdfPageFilePath", pageFile.absolutePath)
                            .putExtra("pdfPageNumber", pageNum)
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@ChapterActivity,
                        "Render failed: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    // ─── Image chapter (existing behaviour) ───────────────────────────────────

    private fun setupImageChapter() {
        findViewById<Button>(R.id.uploadImageButton).setOnClickListener {
            showImageSourceDialog()
        }

        findViewById<Button>(R.id.askAIButton).setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra("subjectName", subjectName)
                    .putExtra("chapterName", chapterName)
            )
        }

        pagesList.setOnItemClickListener { _, _, position, _ ->
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra("subjectName", subjectName)
                    .putExtra("chapterName", chapterName)
                    .putExtra("imagePath", pagesListData[position])
            )
        }

        loadPages()
    }

    // ─── Image helpers ────────────────────────────────────────────────────────

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

    private fun savePage(imagePath: String) {
        val timestamp = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault()).format(Date())
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("pages")
            .add(hashMapOf("path" to imagePath, "timestamp" to timestamp))
            .addOnSuccessListener {
                pagesListData.add("Page uploaded - $timestamp")
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "Page saved!", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadPages() {
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("pages")
            .get()
            .addOnSuccessListener { documents ->
                pagesListData.clear()
                for (doc in documents) {
                    pagesListData.add("Page - ${doc.getString("timestamp") ?: ""}")
                }
                adapter.notifyDataSetChanged()
            }
    }

    private fun deletePage(position: Int) {
        db.collection("users").document("testuser123")
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

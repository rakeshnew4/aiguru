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
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.adapters.PageListAdapter
import com.example.aiguru.utils.PdfPageManager
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ChapterActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var pagesRecyclerView: RecyclerView
    private val pagesListData = mutableListOf<String>()
    private lateinit var pageListAdapter: PageListAdapter
    private lateinit var subjectName: String
    private lateinit var chapterName: String
    private var cameraImageUri: Uri? = null
    private val CAMERA_PERMISSION_CODE = 201

    // PDF chapter state
    private var isPdfChapter = false
    private var pdfAssetPath = ""
    private var pdfId = ""
    private var pdfPageCount = 0
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

        // Notes button — always visible in header
        findViewById<MaterialButton>(R.id.notesButton).setOnClickListener {
            showNotesOptions()
        }

        // Set up RecyclerView with PageListAdapter
        pagesRecyclerView = findViewById(R.id.pagesList)
        pageListAdapter = PageListAdapter(
            pages = pagesListData,
            onView = { position -> onViewPage(position) },
            onAsk  = { position -> onAskPage(position) }
        )
        pagesRecyclerView.layoutManager = LinearLayoutManager(this)
        pagesRecyclerView.adapter = pageListAdapter

        loadChapterType()
    }

    // ─── Notes generation ─────────────────────────────────────────────────────

    private fun showNotesOptions() {
        AlertDialog.Builder(this)
            .setTitle("📝 Chapter Notes")
            .setItems(arrayOf(
                "Generate notes for this chapter",
                "View saved notes"
            )) { _, which ->
                when (which) {
                    0 -> generateNotes()
                    1 -> viewSavedNotes()
                }
            }
            .show()
    }

    private fun generateNotes() {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra("subjectName", subjectName)
                .putExtra("chapterName", chapterName)
                .putExtra("autoPrompt",
                    "Create comprehensive study notes for \"$chapterName\" with:\n" +
                    "• Key concepts and definitions\n" +
                    "• Important facts to remember\n" +
                    "• Summary of main points\n" +
                    "• Any formulas or rules to know\n\n" +
                    "Format clearly with ## headings and bullet points."
                )
        )
    }

    private fun viewSavedNotes() {
        db.collection("users").document("testuser123")
            .collection("chats").document("${subjectName}_${chapterName}")
            .collection("notes").document("saved")
            .get()
            .addOnSuccessListener { doc ->
                val notes = doc.getString("content")
                if (notes.isNullOrBlank()) {
                    Toast.makeText(this, "No saved notes yet — generate some first!", Toast.LENGTH_SHORT).show()
                } else {
                    AlertDialog.Builder(this)
                        .setTitle("📝 Notes: $chapterName")
                        .setMessage(notes)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
            .addOnFailureListener {
                Toast.makeText(this, "No saved notes yet.", Toast.LENGTH_SHORT).show()
            }
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
        findViewById<Button>(R.id.uploadImageButton).visibility = View.GONE
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
        pageListAdapter.notifyDataSetChanged()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val count = pdfPageManager.getPageCount(pdfId, pdfAssetPath)
                pdfPageCount = count

                db.collection("users").document("testuser123")
                    .collection("subjects").document(subjectName)
                    .collection("chapters").document(chapterName)
                    .update("pageCount", count)

                withContext(Dispatchers.Main) {
                    pagesListData.clear()
                    for (i in 1..count) pagesListData.add("📄  Page $i")
                    pageListAdapter.notifyDataSetChanged()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    pagesListData.clear()
                    pagesListData.add("⚠️ Failed to load PDF: ${e.message}")
                    pageListAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    private fun onViewPage(position: Int) {
        // Pre-render this page (and let PageViewerActivity handle rest)
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                pdfPageManager.getPage(pdfId, pdfAssetPath, position)
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent(this@ChapterActivity, PageViewerActivity::class.java)
                            .putExtra("subjectName", subjectName)
                            .putExtra("chapterName", chapterName)
                            .putExtra("pdfId", pdfId)
                            .putExtra("pdfAssetPath", pdfAssetPath)
                            .putExtra("pageCount", pdfPageCount)
                            .putExtra("startPage", position)
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChapterActivity, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun onAskPage(position: Int) {
        Toast.makeText(this, "Rendering page ${position + 1}…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val pageFile = pdfPageManager.getPage(pdfId, pdfAssetPath, position)
                withContext(Dispatchers.Main) {
                    startActivity(
                        Intent(this@ChapterActivity, ChatActivity::class.java)
                            .putExtra("subjectName", subjectName)
                            .putExtra("chapterName", chapterName)
                            .putExtra("pdfPageFilePath", pageFile.absolutePath)
                            .putExtra("pdfPageNumber", position + 1)
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@ChapterActivity, "Render failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // ─── Image chapter (plain photo uploads) ──────────────────────────────────

    private fun setupImageChapter() {
        findViewById<Button>(R.id.uploadImageButton).setOnClickListener { showImageSourceDialog() }
        findViewById<Button>(R.id.askAIButton).setOnClickListener {
            startActivity(
                Intent(this, ChatActivity::class.java)
                    .putExtra("subjectName", subjectName)
                    .putExtra("chapterName", chapterName)
            )
        }
        // For image chapters the View button opens the image in ChatActivity;
        // Ask does the same — both open ChatActivity with the image path
        loadImagePages()
    }

    private fun showImageSourceDialog() {
        AlertDialog.Builder(this)
            .setTitle("Add Textbook Page")
            .setItems(arrayOf("📷  Take Photo", "🖼️  Choose from Gallery")) { _, which ->
                when (which) {
                    0 -> openCamera()
                    1 -> pickImageLauncher.launch("image/*")
                }
            }.show()
    }

    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            ); return
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.TITLE, "AI_Guru_Page_${System.currentTimeMillis()}")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        }
        cameraImageUri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        cameraImageUri?.let { cameraLauncher.launch(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == CAMERA_PERMISSION_CODE && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED)
            openCamera()
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
                pageListAdapter.notifyItemInserted(pagesListData.size - 1)
                Toast.makeText(this, "Page saved!", Toast.LENGTH_SHORT).show()
            }
    }

    private fun loadImagePages() {
        // Rebuild the PageListAdapter callbacks for image chapter
        pageListAdapter = PageListAdapter(
            pages = pagesListData,
            onView = { position ->
                startActivity(
                    Intent(this, ChatActivity::class.java)
                        .putExtra("subjectName", subjectName)
                        .putExtra("chapterName", chapterName)
                        .putExtra("imagePath", pagesListData[position])
                )
            },
            onAsk = { position ->
                startActivity(
                    Intent(this, ChatActivity::class.java)
                        .putExtra("subjectName", subjectName)
                        .putExtra("chapterName", chapterName)
                        .putExtra("imagePath", pagesListData[position])
                )
            }
        )
        pagesRecyclerView.adapter = pageListAdapter

        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(chapterName)
            .collection("pages")
            .get()
            .addOnSuccessListener { documents ->
                pagesListData.clear()
                for (doc in documents) pagesListData.add("Page - ${doc.getString("timestamp") ?: ""}")
                pageListAdapter.notifyDataSetChanged()
            }
    }
}

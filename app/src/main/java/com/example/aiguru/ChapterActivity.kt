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
import com.example.aiguru.utils.ChapterMetricsTracker
import com.example.aiguru.utils.PdfPageManager
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class ChapterActivity : AppCompatActivity() {

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
    private lateinit var metricsTracker: ChapterMetricsTracker

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

        pdfPageManager = PdfPageManager(this)

        subjectName = intent.getStringExtra("subjectName") ?: "Subject"
        chapterName = intent.getStringExtra("chapterName") ?: "Chapter"

        metricsTracker = ChapterMetricsTracker(subjectName, chapterName)

        findViewById<TextView>(R.id.chapterTitle).text = chapterName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        // Notes button — always visible in header
        findViewById<MaterialButton>(R.id.notesButton).setOnClickListener {
            showNotesOptions()
        }

        // Real Teacher button
//        findViewById<MaterialButton>(R.id.realTeacherChapterButton).setOnClickListener {
//            startActivity(
//                Intent(this, RealTeacherActivity::class.java)
//                    .putExtra("subjectName", subjectName)
//                    .putExtra("chapterName", chapterName)
//            )
//        }

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
        loadMasteryScore()
    }

    override fun onStop() {
        super.onStop()
        metricsTracker.endSession(this, pdfPageCount)
    }

    private fun loadMasteryScore() {
        // Mastery data will be loaded from Firestore when re-enabled
        findViewById<View>(R.id.masteryCard).visibility = View.GONE
    }

    // ─── Notes generation ─────────────────────────────────────────────────────

    private fun showNotesOptions() {
        val options = mutableListOf(
            "📖 Generate Chapter Notes",
            "✏️ Generate Exercise Notes",
            "📋 View Saved Notes"
        )
        if (isPdfChapter && pdfPageCount > 0) {
            options.add(1, "📄 Generate Page-wise Notes")
        }
        AlertDialog.Builder(this)
            .setTitle("📝 Notes for $chapterName")
            .setItems(options.toTypedArray()) { _, which ->
                when (options[which]) {
                    "📖 Generate Chapter Notes"  -> generateChapterNotes()
                    "📄 Generate Page-wise Notes" -> showPageWiseNotesPicker()
                    "✏️ Generate Exercise Notes"  -> generateExerciseNotes()
                    "📋 View Saved Notes"          -> viewSavedNotes()
                }
            }
            .show()
    }

    private fun generateChapterNotes() {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra("subjectName", subjectName)
                .putExtra("chapterName", chapterName)
                .putExtra("saveNotesType", "chapter")
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

    private fun showPageWiseNotesPicker() {
        val pageOptions = (1..pdfPageCount).map { "Page $it" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Select Page for Notes")
            .setItems(pageOptions) { _, idx -> generateNotesForPage(idx + 1) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun generateNotesForPage(pageNum: Int) {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra("subjectName", subjectName)
                .putExtra("chapterName", chapterName)
                .putExtra("saveNotesType", "page_$pageNum")
                .putExtra("autoPrompt",
                    "Create detailed study notes for Page $pageNum of \"$chapterName\":\n" +
                    "• Key concepts and definitions on this page\n" +
                    "• Any formulas, rules, or special points\n" +
                    "• Summary of content on this page\n\n" +
                    "Use ## Page $pageNum Notes as the heading and format with bullet points."
                )
        )
    }

    private fun generateExerciseNotes() {
        startActivity(
            Intent(this, ChatActivity::class.java)
                .putExtra("subjectName", subjectName)
                .putExtra("chapterName", chapterName)
                .putExtra("saveNotesType", "exercises")
                .putExtra("autoPrompt",
                    "For the exercises in \"$chapterName\":\n" +
                    "• List the types of exercises/problems in this chapter\n" +
                    "• Provide step-by-step problem-solving strategies\n" +
                    "• Show worked examples for typical questions\n" +
                    "• Highlight common mistakes to avoid\n\n" +
                    "Use ## Exercise Notes as the heading."
                )
        )
    }

    private fun viewSavedNotes() {
        val userId = SessionManager.getFirestoreUserId(this)
        val notesRepo = com.example.aiguru.chat.NotesRepository(this, userId, subjectName, chapterName)
        notesRepo.loadAll(
            onResult  = { text ->
                AlertDialog.Builder(this)
                    .setTitle("📋 Notes: $chapterName")
                    .setMessage(text)
                    .setPositiveButton("OK", null)
                    .show()
            },
            onEmpty   = { Toast.makeText(this, "No saved notes yet — generate some from the chat!", Toast.LENGTH_SHORT).show() },
            onFailure = { Toast.makeText(this, "Couldn't load notes. Try again.", Toast.LENGTH_SHORT).show() }
        )
    }

    // ─── Chapter type detection ───────────────────────────────────────────────

    private fun loadChapterType() {
        val meta = getSharedPreferences("chapters_prefs", MODE_PRIVATE)
            .getString("meta_${subjectName}_${chapterName}", null)
        if (meta != null) {
            try {
                val json = org.json.JSONObject(meta)
                isPdfChapter = json.optBoolean("isPdf", false)
                if (isPdfChapter) {
                    pdfAssetPath = json.optString("pdfAssetPath", "")
                    pdfId        = json.optString("pdfId", "")
                    setupPdfChapter()
                    return
                }
            } catch (_: Exception) { }
        }
        setupImageChapter()
    }

    // ─── PDF chapter ──────────────────────────────────────────────────────────

    private fun setupPdfChapter() {
        if (pdfAssetPath.isBlank() || pdfId.isBlank()) {
            pagesListData.clear()
            pagesListData.add("⚠️ PDF data missing. Re-add this chapter from the Library.")
            pageListAdapter.notifyDataSetChanged()
            return
        }

        findViewById<MaterialButton>(R.id.uploadImageButton).apply {
            visibility = View.VISIBLE
            setOnClickListener { showImageSourceDialog() }
        }
        findViewById<MaterialButton>(R.id.askAIButton).apply {
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
                // pageCount will be synced to Firestore when re-enabled

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
        metricsTracker.recordPageViewed(position + 1)
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
        metricsTracker.recordPageViewed(position + 1)
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
        findViewById<MaterialButton>(R.id.uploadImageButton).setOnClickListener { showImageSourceDialog() }
        findViewById<MaterialButton>(R.id.askAIButton).setOnClickListener {
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
        val prefs = getSharedPreferences("chapters_prefs", MODE_PRIVATE)
        val key = "imgpages_${subjectName}_${chapterName}"
        val existing = prefs.getString(key, "[]") ?: "[]"
        val arr = try { org.json.JSONArray(existing) } catch (_: Exception) { org.json.JSONArray() }
        arr.put(org.json.JSONObject().apply {
            put("path", imagePath)
            put("timestamp", timestamp)
        })
        prefs.edit().putString(key, arr.toString()).apply()
        pagesListData.add("Page uploaded - $timestamp")
        pageListAdapter.notifyItemInserted(pagesListData.size - 1)
        Toast.makeText(this, "Page saved!", Toast.LENGTH_SHORT).show()
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

        val key = "imgpages_${subjectName}_${chapterName}"
        val raw = getSharedPreferences("chapters_prefs", MODE_PRIVATE).getString(key, "[]") ?: "[]"
        pagesListData.clear()
        try {
            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                pagesListData.add("Page - ${arr.getJSONObject(i).optString("timestamp", "")}")
            }
        } catch (_: Exception) { }
        pageListAdapter.notifyDataSetChanged()
    }
}

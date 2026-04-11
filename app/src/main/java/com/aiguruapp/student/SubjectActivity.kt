package com.aiguruapp.student

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
import com.aiguruapp.student.adapters.ChapterAdapter
import com.aiguruapp.student.adapters.ChapterItem
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import org.json.JSONObject
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class SubjectActivity : BaseActivity() {

    private lateinit var chaptersRecyclerView: RecyclerView
    private val chaptersListData = mutableListOf<ChapterItem>()
    private lateinit var chapterAdapter: ChapterAdapter
    private lateinit var subjectName: String
    private lateinit var subjectId: String    // Firestore subject_id (e.g. "math_9th")
    private lateinit var userId: String

    // ncertUrlMap: chapter order (1-based) → direct PDF URL from Firestore
    private val ncertUrlMap = mutableMapOf<Int, String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject)

        subjectName = intent.getStringExtra("subjectName") ?: "Subject"
        subjectId   = intent.getStringExtra("subjectId") ?: ""
        userId = SessionManager.getFirestoreUserId(this)

        findViewById<TextView>(R.id.subjectTitle).text = subjectName
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        setupRecyclerView()
        loadChapters()
        fetchNcertUrls()      // async — updates buttons as data arrives

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.subjectSwipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary), getColor(R.color.colorSecondary))
        swipeRefresh.setOnRefreshListener {
            loadChapters()
            swipeRefresh.isRefreshing = false
        }

        findViewById<MaterialButton>(R.id.addChapterButton).setOnClickListener {
            showManualChapterDialog()
        }
    }

    private fun setupRecyclerView() {
        chaptersRecyclerView = findViewById(R.id.chaptersRecyclerView)
        chapterAdapter = ChapterAdapter(
            chapters = chaptersListData,
            onItemClick = { item ->
                startActivity(
                    Intent(this, ChapterActivity::class.java)
                        .putExtra("subjectName", subjectName)
                        .putExtra("chapterName", item.name)
                )
            },
            onItemLongClick = { item -> showDeleteChapterDialog(item.name) },
            onNcertClick = { item ->
                startActivity(
                    Intent(this, NcertViewerActivity::class.java)
                        .putExtra(NcertViewerActivity.EXTRA_TITLE, item.name)
                        .putExtra(NcertViewerActivity.EXTRA_URL, item.ncertPdfUrl)
                )
            }
        )
        chaptersRecyclerView.layoutManager = LinearLayoutManager(this)
        chaptersRecyclerView.adapter = chapterAdapter
    }

    private fun loadChapters() {
        chaptersListData.clear()
        chaptersListData.addAll(loadChaptersLocally().mapIndexed { idx, name ->
            ChapterItem(name = name, ncertPdfUrl = ncertUrlMap[idx + 1])
        })
        chapterAdapter.notifyDataSetChanged()
    }

    /**
     * Fetches ncert_pdf_url for every chapter in this subject from Firestore's
     * root `chapters/` collection (seeded by ncert_scraper.py).
     * Runs in parallel with the UI — quietly updates cards when data arrives.
     */
    private fun fetchNcertUrls() {
        if (subjectId.isEmpty()) return
        FirebaseFirestore.getInstance()
            .collection("chapters")
            .whereEqualTo("subject_id", subjectId)
            .get(Source.CACHE)                       // prefer cache for speed
            .addOnSuccessListener { snap ->
                var changed = false
                for (doc in snap.documents) {
                    val url = doc.getString("ncert_pdf_url") ?: continue
                    val order = (doc.getLong("order") ?: 0L).toInt()
                    if (url.isNotEmpty() && order > 0) {
                        ncertUrlMap[order] = url
                        changed = true
                    }
                }
                if (changed) refreshNcertButtons()
            }
            .addOnFailureListener {
                // Fall back to network if cache missed
                FirebaseFirestore.getInstance()
                    .collection("chapters")
                    .whereEqualTo("subject_id", subjectId)
                    .get(Source.SERVER)
                    .addOnSuccessListener { snap ->
                        for (doc in snap.documents) {
                            val url = doc.getString("ncert_pdf_url") ?: continue
                            val order = (doc.getLong("order") ?: 0L).toInt()
                            if (url.isNotEmpty() && order > 0) ncertUrlMap[order] = url
                        }
                        refreshNcertButtons()
                    }
            }
    }

    /** Re-maps NCERT URLs into existing list items and notifies adapter. */
    private fun refreshNcertButtons() {
        chaptersListData.forEachIndexed { idx, item ->
            val url = ncertUrlMap[idx + 1]
            if (item.ncertPdfUrl != url) {
                chaptersListData[idx] = item.copy(ncertPdfUrl = url)
            }
        }
        chapterAdapter.notifyDataSetChanged()
    }

    // ── Local SharedPreferences helpers ──────────────────────────────────

    private fun chaptersPrefs() = getSharedPreferences("chapters_prefs", MODE_PRIVATE)

    private fun loadChaptersLocally(): MutableList<String> {
        val raw = chaptersPrefs().getString("chapters_$subjectName", "") ?: ""
        return if (raw.isEmpty()) mutableListOf()
               else raw.split("||||").filter { it.isNotEmpty() }.toMutableList()
    }

    private fun saveChaptersLocally(chapters: List<String>) {
        chaptersPrefs().edit().putString("chapters_$subjectName", chapters.joinToString("||||")).apply()
    }

    private fun saveChapterMeta(name: String, isPdf: Boolean, pdfAssetPath: String = "", pdfId: String = "") {
        chaptersPrefs().edit().putString(
            "meta_${subjectName}_$name",
            JSONObject().apply {
                put("isPdf", isPdf)
                put("pdfAssetPath", pdfAssetPath)
                put("pdfId", pdfId)
            }.toString()
        ).apply()
    }

    private fun saveChapterMetaNcert(name: String, ncertUrl: String) {
        chaptersPrefs().edit().putString(
            "meta_${subjectName}_$name",
            JSONObject().apply {
                put("isPdf", false)
                put("isNcert", true)
                put("ncertUrl", ncertUrl)
            }.toString()
        ).apply()
    }

    private fun deleteChapterMeta(name: String) {
        chaptersPrefs().edit().remove("meta_${subjectName}_$name").apply()
    }

    // ─── Add chapter ──────────────────────────────────────────────────────────

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

    // ─── Chapter persistence ───────────────────────────────────────────────────

    private fun addManualChapter(name: String) {
        val chapters = loadChaptersLocally()
        if (!chapters.contains(name)) chapters.add(name)
        saveChaptersLocally(chapters)
        saveChapterMeta(name, isPdf = false)
        FirestoreManager.saveChapter(userId, subjectName, name, isPdf = false)
        chaptersListData.clear()
        chaptersListData.addAll(chapters.mapIndexed { idx, n ->
            ChapterItem(name = n, ncertPdfUrl = ncertUrlMap[idx + 1])
        })
        chapterAdapter.notifyDataSetChanged()
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
        val chapters = loadChaptersLocally()
        chapters.remove(name)
        saveChaptersLocally(chapters)
        deleteChapterMeta(name)
        FirestoreManager.deleteChapter(userId, subjectName, name)
        val idx = chaptersListData.indexOfFirst { it.name == name }
        if (idx >= 0) {
            chaptersListData.removeAt(idx)
            chapterAdapter.notifyItemRemoved(idx)
        }
    }
}
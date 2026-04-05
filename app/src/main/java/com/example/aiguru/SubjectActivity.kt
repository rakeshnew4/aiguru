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
import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

class SubjectActivity : BaseActivity() {

    private lateinit var chaptersRecyclerView: RecyclerView
    private val chaptersListData = mutableListOf<String>()
    private lateinit var chapterAdapter: ChapterAdapter
    private lateinit var subjectName: String
    private lateinit var userId: String

    // Simple data holder for a scanned library book
    private data class LibItem(val title: String, val assetPath: String, val pdfId: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_subject)

        subjectName = intent.getStringExtra("subjectName") ?: "Subject"
        userId = SessionManager.getFirestoreUserId(this)

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
                    Intent(this, ChatActivity::class.java)
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
        chaptersListData.clear()
        chaptersListData.addAll(loadChaptersLocally())
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

    private fun deleteChapterMeta(name: String) {
        chaptersPrefs().edit().remove("meta_${subjectName}_$name").apply()
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

    // ── Class → Subject → Book 3-level picker ──────────────────────────

    private fun showLibraryPickerDialog() {
        val tree = buildLibraryTree()
        if (tree.isEmpty()) {
            Toast.makeText(this, "No library PDFs found.", Toast.LENGTH_SHORT).show()
            return
        }
        val grades = tree.keys.sorted().toTypedArray()
        val gradeLabels = grades.map { g ->
            val count = tree[g]?.values?.sumOf { it.size } ?: 0
            "🎓 ${formatGradeLabel(g)}  ($count chapters)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📚 Pick Class")
            .setItems(gradeLabels) { _, gi ->
                val grade = grades[gi]
                pickSubject(grade, tree[grade] ?: return@setItems)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun pickSubject(grade: String, subjectMap: Map<String, List<LibItem>>) {
        val subjects = subjectMap.keys.sorted().toTypedArray()
        val subjectLabels = subjects.map { s ->
            val count = subjectMap[s]?.size ?: 0
            "📚 ${s.replaceFirstChar { it.uppercaseChar() }}  ($count books)"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("📚 ${formatGradeLabel(grade)} — Pick Subject")
            .setItems(subjectLabels) { _, si ->
                val subject = subjects[si]
                pickBook(grade, subject, subjectMap[subject] ?: return@setItems)
            }
            .setNegativeButton("← Back") { _, _ -> showLibraryPickerDialog() }
            .show()
    }

    private fun pickBook(grade: String, subject: String, books: List<LibItem>) {
        val labels = books.sortedBy { it.title }.map { b ->
            "📄 ${b.title.replace("_", " ").replace("-", " ")
                .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }}"
        }.toTypedArray()
        val sorted = books.sortedBy { it.title }

        AlertDialog.Builder(this)
            .setTitle("📗 ${subject.replaceFirstChar { it.uppercaseChar() }} · ${formatGradeLabel(grade)}")
            .setItems(labels) { _, idx -> addChapterFromLibrary(sorted[idx]) }
            .setNegativeButton("← Back") { _, _ -> pickSubject(grade,
                buildLibraryTree()[grade] ?: return@setNegativeButton) }
            .show()
    }

    private fun formatGradeLabel(grade: String): String =
        grade.replaceFirstChar { it.uppercaseChar() }
            .replace("class", " Class", ignoreCase = true)
            .replace("grade", " Grade", ignoreCase = true)
            .trim()

    /** Builds grade → subject → books tree from assets/library */
    private fun buildLibraryTree(): LinkedHashMap<String, LinkedHashMap<String, MutableList<LibItem>>> {
        val tree = LinkedHashMap<String, LinkedHashMap<String, MutableList<LibItem>>>()
        try {
            for (grade in (assets.list("library") ?: emptyArray()).sorted()) {
                for (subject in (assets.list("library/$grade") ?: emptyArray()).sorted()) {
                    for (file in (assets.list("library/$grade/$subject") ?: emptyArray())
                            .filter { it.endsWith(".pdf", ignoreCase = true) }.sorted()) {
                        val title = file.removeSuffix(".pdf")
                        val pdfId = "${grade}_${subject}_$title".replace(" ", "_")
                        val assetPath = "library/$grade/$subject/$file"
                        val label = "$title ($subject · $grade)"
                        tree.getOrPut(grade) { linkedMapOf() }
                            .getOrPut(subject) { mutableListOf() }
                            .add(LibItem(title, assetPath, pdfId, label))
                    }
                }
            }
        } catch (_: Exception) { }
        return tree
    }

    // ─── Chapter persistence ───────────────────────────────────────────────────

    private fun addManualChapter(name: String) {
        val chapters = loadChaptersLocally()
        if (!chapters.contains(name)) chapters.add(name)
        saveChaptersLocally(chapters)
        saveChapterMeta(name, isPdf = false)
        FirestoreManager.saveChapter(userId, subjectName, name, isPdf = false)
        chaptersListData.clear()
        chaptersListData.addAll(chapters)
        chapterAdapter.notifyDataSetChanged()
    }

    private fun addChapterFromLibrary(book: LibItem) {
        val chapters = loadChaptersLocally()
        if (!chapters.contains(book.title)) chapters.add(book.title)
        saveChaptersLocally(chapters)
        saveChapterMeta(book.title, isPdf = true, pdfAssetPath = book.assetPath, pdfId = book.pdfId)
        FirestoreManager.saveChapter(userId, subjectName, book.title, isPdf = true, pdfAssetPath = book.assetPath)
        chaptersListData.clear()
        chaptersListData.addAll(chapters)
        chapterAdapter.notifyDataSetChanged()
        Toast.makeText(this, "\"${book.title}\" added ✓", Toast.LENGTH_SHORT).show()
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
        val idx = chaptersListData.indexOf(name)
        if (idx >= 0) {
            chaptersListData.removeAt(idx)
            chapterAdapter.notifyItemRemoved(idx)
        }
    }
}
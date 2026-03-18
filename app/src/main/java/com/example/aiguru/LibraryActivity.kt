package com.example.aiguru

import android.app.AlertDialog
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.aiguru.models.LibraryBook
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore

class LibraryActivity : AppCompatActivity() {

    private lateinit var db: FirebaseFirestore
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyText: TextView

    // Full tree: grade → subject → books
    private val tree = linkedMapOf<String, LinkedHashMap<String, MutableList<LibraryBook>>>()

    // Expanded state sets
    private val expandedGrades   = mutableSetOf<String>()
    private val expandedSubjects = mutableSetOf<String>() // key = "grade::subject"

    // Flat visible list rebuilt on every expand/collapse
    private val visibleItems = mutableListOf<LibraryItem>()
    private lateinit var adapter: LibraryTreeAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        db = FirebaseFirestore.getInstance()
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        emptyText = findViewById(R.id.emptyText)
        recyclerView = findViewById(R.id.libraryRecyclerView)
        adapter = LibraryTreeAdapter(visibleItems,
            onGradeClick    = { grade   -> toggleGrade(grade) },
            onSubjectClick  = { grade, subject -> toggleSubject(grade, subject) },
            onAddClick      = { book    -> showAddToSubjectDialog(book) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        scanLibraryAssets()
    }

    // ─── Asset scanning ───────────────────────────────────────────────────────

    private fun scanLibraryAssets() {
        tree.clear()
        try {
            for (grade in (assets.list("library") ?: emptyArray()).sorted()) {
                for (subject in (assets.list("library/$grade") ?: emptyArray()).sorted()) {
                    for (file in (assets.list("library/$grade/$subject") ?: emptyArray())
                            .filter { it.endsWith(".pdf", ignoreCase = true) }) {
                        val title = file.removeSuffix(".pdf")
                        val pdfId = "${grade}_${subject}_$title".replace(" ", "_")
                        tree.getOrPut(grade) { linkedMapOf() }
                            .getOrPut(subject) { mutableListOf() }
                            .add(LibraryBook(title, grade, subject, "library/$grade/$subject/$file", pdfId))
                    }
                }
            }
        } catch (_: Exception) { }

        rebuildVisibleItems()
    }

    // ─── Expand / collapse ────────────────────────────────────────────────────

    private fun toggleGrade(grade: String) {
        if (grade in expandedGrades) expandedGrades.remove(grade) else expandedGrades.add(grade)
        rebuildVisibleItems()
    }

    private fun toggleSubject(grade: String, subject: String) {
        val key = "$grade::$subject"
        if (key in expandedSubjects) expandedSubjects.remove(key) else expandedSubjects.add(key)
        rebuildVisibleItems()
    }

    private fun rebuildVisibleItems() {
        visibleItems.clear()
        for ((grade, subjectMap) in tree) {
            val gradeExpanded = grade in expandedGrades
            visibleItems.add(LibraryItem.GradeHeader(grade, gradeExpanded))
            if (gradeExpanded) {
                for ((subject, books) in subjectMap) {
                    val key = "$grade::$subject"
                    val subjectExpanded = key in expandedSubjects
                    visibleItems.add(LibraryItem.SubjectHeader(grade, subject, subjectExpanded))
                    if (subjectExpanded) {
                        books.forEach { visibleItems.add(LibraryItem.Book(it)) }
                    }
                }
            }
        }
        adapter.notifyDataSetChanged()
        val isEmpty = tree.isEmpty()
        recyclerView.visibility = if (isEmpty) View.GONE  else View.VISIBLE
        emptyText.visibility    = if (isEmpty) View.VISIBLE else View.GONE
    }

    // ─── Add to Subject dialog ────────────────────────────────────────────────

    private fun showAddToSubjectDialog(book: LibraryBook) {
        db.collection("users").document("testuser123")
            .collection("subjects").get()
            .addOnSuccessListener { docs ->
                val subjects = docs.mapNotNull { it.getString("name") }.toTypedArray()
                if (subjects.isEmpty()) {
                    Toast.makeText(this, "No subjects yet — add a subject first!", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                AlertDialog.Builder(this)
                    .setTitle("➕ Add \"${book.title}\" to…")
                    .setItems(subjects) { _, idx -> addBookAsChapter(book, subjects[idx]) }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Couldn't load subjects. Try again.", Toast.LENGTH_SHORT).show()
            }
    }

    private fun addBookAsChapter(book: LibraryBook, subjectName: String) {
        db.collection("users").document("testuser123")
            .collection("subjects").document(subjectName)
            .collection("chapters").document(book.title)
            .set(hashMapOf(
                "name"         to book.title,
                "order"        to System.currentTimeMillis(),
                "isPdf"        to true,
                "pdfAssetPath" to book.assetPath,
                "pdfId"        to book.pdfId,
                "pageCount"    to 0
            ))
            .addOnSuccessListener {
                Toast.makeText(this, "\"${book.title}\" added to $subjectName ✓", Toast.LENGTH_SHORT).show()
            }
            .addOnFailureListener {
                Toast.makeText(this, "Failed to add chapter. Try again.", Toast.LENGTH_SHORT).show()
            }
    }

    // ─── Data types ───────────────────────────────────────────────────────────

    sealed class LibraryItem {
        data class GradeHeader  (val grade: String, val expanded: Boolean) : LibraryItem()
        data class SubjectHeader(val grade: String, val subject: String, val expanded: Boolean) : LibraryItem()
        data class Book         (val book: LibraryBook) : LibraryItem()
    }

    // ─── Tree adapter ─────────────────────────────────────────────────────────

    inner class LibraryTreeAdapter(
        private val data: List<LibraryItem>,
        private val onGradeClick:   (String) -> Unit,
        private val onSubjectClick: (String, String) -> Unit,
        private val onAddClick:     (LibraryBook) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_GRADE   = 0
        private val TYPE_SUBJECT = 1
        private val TYPE_BOOK    = 2
        private val dp get() = resources.displayMetrics.density

        override fun getItemViewType(p: Int) = when (data[p]) {
            is LibraryItem.GradeHeader   -> TYPE_GRADE
            is LibraryItem.SubjectHeader -> TYPE_SUBJECT
            is LibraryItem.Book          -> TYPE_BOOK
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            when (viewType) {
                TYPE_GRADE -> object : RecyclerView.ViewHolder(
                    LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (14*dp).toInt())
                        setBackgroundColor(0xFF1A237E.toInt())
                    }
                ) {}
                TYPE_SUBJECT -> object : RecyclerView.ViewHolder(
                    LinearLayout(this@LibraryActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setPadding((28*dp).toInt(), (12*dp).toInt(), (16*dp).toInt(), (12*dp).toInt())
                        setBackgroundColor(0xFF3949AB.toInt())
                    }
                ) {}
                else -> BookVH(parent)
            }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = data[position]) {
                is LibraryItem.GradeHeader -> {
                    val row = holder.itemView as LinearLayout
                    row.removeAllViews()
                    val arrow = if (item.expanded) "▼" else "▶"
                    val label = item.grade.replaceFirstChar { it.uppercaseChar() }
                        .replace("class", " Class", true).replace("grade", " Grade", true).trim()
                    row.addView(TextView(this@LibraryActivity).apply {
                        text = "$arrow 🎓 $label"
                        textSize = 16f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    val count = tree[item.grade]?.size ?: 0
                    row.addView(TextView(this@LibraryActivity).apply {
                        text = "$count subject${if (count != 1) "s" else ""}"
                        textSize = 12f
                        setTextColor(0xFFB3C5FF.toInt())
                    })
                    row.setOnClickListener { onGradeClick(item.grade) }
                }
                is LibraryItem.SubjectHeader -> {
                    val row = holder.itemView as LinearLayout
                    row.removeAllViews()
                    val arrow = if (item.expanded) "▼" else "▶"
                    val label = item.subject.replaceFirstChar { it.uppercaseChar() }
                    row.addView(TextView(this@LibraryActivity).apply {
                        text = "  $arrow 📚 $label"
                        textSize = 14f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(0xFFFFFFFF.toInt())
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    val count = tree[item.grade]?.get(item.subject)?.size ?: 0
                    row.addView(TextView(this@LibraryActivity).apply {
                        text = "$count chapter${if (count != 1) "s" else ""}"
                        textSize = 12f
                        setTextColor(0xFFCFD8DC.toInt())
                    })
                    row.setOnClickListener { onSubjectClick(item.grade, item.subject) }
                }
                is LibraryItem.Book -> (holder as BookVH).bind(item.book)
            }
        }

        override fun getItemCount() = data.size

        inner class BookVH(parent: ViewGroup) : RecyclerView.ViewHolder(
            LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((40*parent.resources.displayMetrics.density).toInt(),
                           (10*parent.resources.displayMetrics.density).toInt(),
                           (12*parent.resources.displayMetrics.density).toInt(),
                           (10*parent.resources.displayMetrics.density).toInt())
                setBackgroundColor(0xFFF5F5F5.toInt())
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        ) {
            private val root get() = itemView as LinearLayout

            fun bind(book: LibraryBook) {
                root.removeAllViews()
                root.addView(TextView(this@LibraryActivity).apply {
                    text = "📄"; textSize = 20f
                    setPadding(0, 0, (10*dp).toInt(), 0)
                })
                root.addView(TextView(this@LibraryActivity).apply {
                    text = book.title.replace("_", " ").replace("-", " ")
                        .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                    textSize = 14f
                    setTextColor(0xFF212121.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })
                root.addView(MaterialButton(this@LibraryActivity).apply {
                    text = "+ Add"
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT, (36*dp).toInt()
                    )
                    setOnClickListener { onAddClick(book) }
                })
            }
        }
    }
}


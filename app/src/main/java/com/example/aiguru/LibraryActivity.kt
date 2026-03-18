package com.example.aiguru

import android.app.AlertDialog
import android.os.Bundle
import android.view.Gravity
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

    /** Flat list of items — either a HEADER (grade/subject) or a BOOK. */
    private val items = mutableListOf<LibraryItem>()
    private lateinit var groupedAdapter: GroupedLibraryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_library)

        db = FirebaseFirestore.getInstance()

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        emptyText = findViewById(R.id.emptyText)
        recyclerView = findViewById(R.id.libraryRecyclerView)
        groupedAdapter = GroupedLibraryAdapter(items) { book -> showAddToSubjectDialog(book) }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = groupedAdapter

        scanLibraryAssets()
    }

    // ─── Asset scanning ───────────────────────────────────────────────────────

    private fun scanLibraryAssets() {
        items.clear()
        // Map: grade → subject → listOf(books)
        val tree = linkedMapOf<String, LinkedHashMap<String, MutableList<LibraryBook>>>()
        try {
            val grades = assets.list("library") ?: emptyArray()
            for (grade in grades.sorted()) {
                val subjects = assets.list("library/$grade") ?: continue
                for (subject in subjects.sorted()) {
                    val files = assets.list("library/$grade/$subject") ?: continue
                    for (file in files.filter { it.endsWith(".pdf", ignoreCase = true) }) {
                        val title = file.removeSuffix(".pdf")
                        val pdfId = "${grade}_${subject}_$title".replace(" ", "_")
                        val book = LibraryBook(title, grade, subject, "library/$grade/$subject/$file", pdfId)
                        tree.getOrPut(grade) { linkedMapOf() }
                            .getOrPut(subject) { mutableListOf() }
                            .add(book)
                    }
                }
            }
        } catch (_: Exception) { }

        // Flatten tree into items with GRADE headers, SUBJECT sub-headers, then books
        for ((grade, subjectMap) in tree) {
            items.add(LibraryItem.GradeHeader(grade))
            for ((subject, books) in subjectMap) {
                items.add(LibraryItem.SubjectHeader(subject))
                books.forEach { items.add(LibraryItem.Book(it)) }
            }
        }

        groupedAdapter.notifyDataSetChanged()
        recyclerView.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
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
                "name" to book.title,
                "order" to System.currentTimeMillis(),
                "isPdf" to true,
                "pdfAssetPath" to book.assetPath,
                "pdfId" to book.pdfId,
                "pageCount" to 0
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
        data class GradeHeader(val grade: String) : LibraryItem()
        data class SubjectHeader(val subject: String) : LibraryItem()
        data class Book(val book: LibraryBook) : LibraryItem()
    }

    // ─── Grouped adapter (inline — no extra file needed) ─────────────────────

    inner class GroupedLibraryAdapter(
        private val data: List<LibraryItem>,
        private val onAddClick: (LibraryBook) -> Unit
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val TYPE_GRADE   = 0
        private val TYPE_SUBJECT = 1
        private val TYPE_BOOK    = 2
        private val dp get() = resources.displayMetrics.density

        override fun getItemViewType(position: Int) = when (data[position]) {
            is LibraryItem.GradeHeader   -> TYPE_GRADE
            is LibraryItem.SubjectHeader -> TYPE_SUBJECT
            is LibraryItem.Book          -> TYPE_BOOK
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return when (viewType) {
                TYPE_GRADE -> {
                    val tv = TextView(this@LibraryActivity).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setPadding((16*dp).toInt(), (14*dp).toInt(), (16*dp).toInt(), (6*dp).toInt())
                        textSize = 18f
                        setTypeface(null, android.graphics.Typeface.BOLD)
                        setTextColor(0xFF1A237E.toInt())
                        setBackgroundColor(0xFFEEF2FF.toInt())
                    }
                    object : RecyclerView.ViewHolder(tv) {}
                }
                TYPE_SUBJECT -> {
                    val tv = TextView(this@LibraryActivity).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        setPadding((24*dp).toInt(), (8*dp).toInt(), (16*dp).toInt(), (4*dp).toInt())
                        textSize = 14f
                        setTextColor(0xFF546E7A.toInt())
                        setBackgroundColor(0xFFF5F5F5.toInt())
                    }
                    object : RecyclerView.ViewHolder(tv) {}
                }
                else -> BookViewHolder(parent)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = data[position]) {
                is LibraryItem.GradeHeader -> {
                    val label = item.grade.replaceFirstChar { it.uppercaseChar() }
                        .replace("class", " Class").replace("grade", " Grade")
                    (holder.itemView as TextView).text = "🎓 $label"
                }
                is LibraryItem.SubjectHeader -> {
                    val label = item.subject.replaceFirstChar { it.uppercaseChar() }
                    (holder.itemView as TextView).text = "   📚 $label"
                }
                is LibraryItem.Book -> (holder as BookViewHolder).bind(item.book)
            }
        }

        override fun getItemCount() = data.size

        inner class BookViewHolder(parent: ViewGroup) : RecyclerView.ViewHolder(
            LinearLayout(this@LibraryActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding((32*parent.resources.displayMetrics.density).toInt(),
                           (10*parent.resources.displayMetrics.density).toInt(),
                           (12*parent.resources.displayMetrics.density).toInt(),
                           (10*parent.resources.displayMetrics.density).toInt())
                setBackgroundColor(0xFFFFFFFF.toInt())
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        ) {
            private val root get() = itemView as LinearLayout

            fun bind(book: LibraryBook) {
                root.removeAllViews()
                val dp = resources.displayMetrics.density

                val icon = TextView(this@LibraryActivity).apply {
                    text = "📄"
                    textSize = 22f
                    setPadding(0, 0, (12*dp).toInt(), 0)
                }
                root.addView(icon)

                val titleView = TextView(this@LibraryActivity).apply {
                    text = book.title.replace("_", " ").replace("-", " ")
                        .split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
                    textSize = 14f
                    setTextColor(0xFF1A237E.toInt())
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                root.addView(titleView)

                val addBtn = MaterialButton(this@LibraryActivity).apply {
                    text = "+ Add"
                    textSize = 12f
                    setPadding((10*dp).toInt(), 0, (10*dp).toInt(), 0)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        (36*dp).toInt()
                    )
                    setOnClickListener { onAddClick(book) }
                }
                root.addView(addBtn)
            }
        }
    }
}

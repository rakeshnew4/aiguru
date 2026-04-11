package com.aiguruapp.student.notes

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.net.toUri
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.R
import com.bumptech.glide.Glide
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.google.android.material.imageview.ShapeableImageView
import java.text.SimpleDateFormat
import java.util.*

/**
 * Full-screen notes viewer for a chapter.
 * - Category chips filter the list
 * - Each note card shows content, image (if any), and an editable student annotation
 * - FAB adds a new free-text note
 */
class NotesActivity : AppCompatActivity() {

    companion object {
        private const val EXTRA_SUBJECT  = "subject"
        private const val EXTRA_CHAPTER  = "chapter"
        private const val EXTRA_USER_ID  = "userId"

        fun launch(ctx: Context, subject: String, chapter: String, userId: String) {
            ctx.startActivity(Intent(ctx, NotesActivity::class.java).apply {
                putExtra(EXTRA_SUBJECT, subject)
                putExtra(EXTRA_CHAPTER, chapter)
                putExtra(EXTRA_USER_ID, userId)
            })
        }
    }

    private lateinit var repo: ChapterNotesRepository
    private lateinit var chapterName: String
    private lateinit var notesRecycler: RecyclerView
    private lateinit var categoryContainer: LinearLayout
    private lateinit var emptyStateLayout: LinearLayout

    private var activeCategory: String = "All"
    private val allNotes = mutableListOf<ChapterNote>()
    private lateinit var adapter: NoteAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_notes)

        chapterName = intent.getStringExtra(EXTRA_CHAPTER) ?: "Chapter"
        val subject = intent.getStringExtra(EXTRA_SUBJECT) ?: ""
        val userId  = intent.getStringExtra(EXTRA_USER_ID) ?: ""

        repo = ChapterNotesRepository(this, userId, subject, chapterName)

        // Toolbar
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.apply {
            title = "📋 My Notes"
            subtitle = chapterName
            setDisplayHomeAsUpEnabled(true)
        }
        toolbar.setNavigationOnClickListener { finish() }

        notesRecycler    = findViewById(R.id.notesRecyclerView)
        categoryContainer = findViewById(R.id.categoryChipsContainer)
        emptyStateLayout = findViewById(R.id.emptyStateLayout)

        adapter = NoteAdapter()
        notesRecycler.layoutManager = LinearLayoutManager(this)
        notesRecycler.adapter = adapter

        val fab = findViewById<ExtendedFloatingActionButton>(R.id.addNoteFab)
        fab.setOnClickListener { showAddTextNoteDialog() }

        loadAndRefresh()
    }

    // ── Data ──────────────────────────────────────────────────────────────────

    private fun loadAndRefresh() {
        allNotes.clear()
        allNotes.addAll(repo.loadNotes())
        rebuildCategoryChips()
        filterAndShow()
    }

    private fun filterAndShow() {
        val filtered = if (activeCategory == "All") allNotes
        else allNotes.filter { it.category == activeCategory }
        adapter.submitList(filtered)
        notesRecycler.visibility = if (filtered.isEmpty()) View.GONE else View.VISIBLE
        emptyStateLayout.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    // ── Category chips ────────────────────────────────────────────────────────

    private fun rebuildCategoryChips() {
        categoryContainer.removeAllViews()
        val cats = listOf("All") + repo.getCategories()
        cats.forEach { cat -> categoryContainer.addView(makeCategoryChip(cat)) }
        // Add "+ New" chip
        categoryContainer.addView(makeAddCategoryChip())
    }

    private fun makeCategoryChip(label: String): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = label
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            val isActive = label == activeCategory
            setTextColor(if (isActive) Color.WHITE else Color.parseColor("#1565C0"))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(if (isActive) Color.parseColor("#1A1A2E") else Color.parseColor("#E8F0FE"))
            }
            background = bg
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (8 * dp).toInt() }
            setOnClickListener {
                activeCategory = label
                rebuildCategoryChips()
                filterAndShow()
            }
        }
    }

    private fun makeAddCategoryChip(): TextView {
        val dp = resources.displayMetrics.density
        return TextView(this).apply {
            text = "＋ Category"
            textSize = 13f
            setTextColor(Color.parseColor("#1E9B6B"))
            val bg = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 20f * dp
                setColor(Color.parseColor("#E6FAF4"))
            }
            background = bg
            setPadding((14 * dp).toInt(), (6 * dp).toInt(), (14 * dp).toInt(), (6 * dp).toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setOnClickListener { showAddCategoryDialog() }
        }
    }

    // ── Dialogs ───────────────────────────────────────────────────────────────

    private fun showAddTextNoteDialog() {
        val cats = repo.getCategories()
        val input = EditText(this).apply {
            hint = "Write your note here..."
            minLines = 3
            maxLines = 8
            setPadding(32, 24, 32, 24)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        var chosenCat = cats.firstOrNull() ?: "General"

        val catRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(32, 8, 32, 8)
        }
        val catLabel = TextView(this).apply {
            text = "Category: "
            textSize = 14f
        }
        val spinner = Spinner(this).apply {
            adapter = ArrayAdapter(this@NotesActivity,
                android.R.layout.simple_spinner_item, cats).also {
                    it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                }
        }
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                chosenCat = cats[pos]
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        catRow.addView(catLabel)
        catRow.addView(spinner)

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(input)
            addView(catRow)
        }

        AlertDialog.Builder(this)
            .setTitle("✏️ New Note")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val text = input.text.toString().trim()
                if (text.isNotBlank()) {
                    repo.saveNote(ChapterNote(
                        id        = UUID.randomUUID().toString(),
                        type      = "text",
                        content   = text,
                        category  = chosenCat
                    ))
                    loadAndRefresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showAddCategoryDialog() {
        val input = EditText(this).apply {
            hint = "Category name"
            setPadding(32, 24, 32, 8)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        AlertDialog.Builder(this)
            .setTitle("New Category")
            .setView(input)
            .setPositiveButton("Add") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotBlank()) {
                    repo.addCategory(name)
                    activeCategory = name
                    loadAndRefresh()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDeleteConfirmation(note: ChapterNote) {
        AlertDialog.Builder(this)
            .setTitle("Delete Note?")
            .setMessage("This note will be permanently deleted.")
            .setPositiveButton("Delete") { _, _ ->
                repo.deleteNote(note.id)
                loadAndRefresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class NoteAdapter : RecyclerView.Adapter<NoteAdapter.NoteViewHolder>() {
        private val items = mutableListOf<ChapterNote>()

        fun submitList(list: List<ChapterNote>) {
            items.clear()
            items.addAll(list)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): NoteViewHolder {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_note_card, parent, false)
            return NoteViewHolder(v)
        }

        override fun onBindViewHolder(holder: NoteViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size

        inner class NoteViewHolder(v: View) : RecyclerView.ViewHolder(v) {
            private val typeIcon      = v.findViewById<TextView>(R.id.noteTypeIcon)
            private val categoryLabel = v.findViewById<TextView>(R.id.noteCategoryLabel)
            private val timestamp     = v.findViewById<TextView>(R.id.noteTimestamp)
            private val deleteBtn     = v.findViewById<TextView>(R.id.noteDeleteBtn)
            private val noteImage     = v.findViewById<ShapeableImageView>(R.id.noteImage)
            private val contentView   = v.findViewById<TextView>(R.id.noteContent)
            private val divider       = v.findViewById<View>(R.id.annotationDivider)
            private val annotationEdit = v.findViewById<EditText>(R.id.noteAnnotationEdit)
            private val saveBtn       = v.findViewById<MaterialButton>(R.id.saveAnnotationBtn)

            fun bind(note: ChapterNote) {
                typeIcon.text = when (note.type) {
                    "image" -> "📷"
                    "ai"    -> "💬"
                    else    -> "✏️"
                }
                categoryLabel.text = note.category

                val sdf = SimpleDateFormat("dd MMM, h:mm a", Locale.getDefault())
                timestamp.text = sdf.format(Date(note.timestamp))

                deleteBtn.setOnClickListener { showDeleteConfirmation(note) }

                // Image
                if (note.imageUri != null) {
                    noteImage.visibility = View.VISIBLE
                    Glide.with(noteImage.context).load(note.imageUri.toUri())
                        .centerCrop().into(noteImage)
                } else {
                    noteImage.visibility = View.GONE
                }

                // Content text
                if (note.content.isNotBlank()) {
                    contentView.visibility = View.VISIBLE
                    contentView.text = note.content
                    divider.visibility = View.VISIBLE
                } else {
                    contentView.visibility = View.GONE
                    divider.visibility = View.GONE
                }

                // Annotation
                annotationEdit.setText(note.userAnnotation)
                saveBtn.visibility = View.GONE

                // Show save button when user types something different
                annotationEdit.addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                        saveBtn.visibility =
                            if (s.toString() != note.userAnnotation) View.VISIBLE else View.GONE
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })

                saveBtn.setOnClickListener {
                    val updated = note.copy(userAnnotation = annotationEdit.text.toString().trim())
                    repo.saveNote(updated)
                    saveBtn.visibility = View.GONE
                    Toast.makeText(this@NotesActivity, "Saved", Toast.LENGTH_SHORT).show()
                    // Refresh data in background without full reload animation
                    val idx = allNotes.indexOfFirst { it.id == note.id }
                    if (idx >= 0) allNotes[idx] = updated
                }
            }
        }
    }
}

package com.aiguruapp.student

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.Quiz
import com.aiguruapp.student.notes.ChapterNote
import com.aiguruapp.student.notes.ChapterNotesRepository
import com.aiguruapp.student.notes.NotesActivity
import com.aiguruapp.student.quiz.QuizApiClient
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * Student-facing message selection screen.
 *
 * Students can select chat messages from a conversation and:
 *  - Save them as structured notes (via ChapterNotesRepository)
 *  - Generate a quiz from the selected content (via QuizApiClient)
 *
 * Launched from FullChatFragment's quick-actions "✂️ Select" button.
 */
class StudentChatSelectionActivity : BaseActivity() {

    companion object {
        const val EXTRA_SUBJECT   = "subject"
        const val EXTRA_CHAPTER   = "chapter"
        const val EXTRA_USER_ID   = "userId"

        fun launch(ctx: Context, subject: String, chapter: String, userId: String) {
            ctx.startActivity(Intent(ctx, StudentChatSelectionActivity::class.java).apply {
                putExtra(EXTRA_SUBJECT,  subject)
                putExtra(EXTRA_CHAPTER,  chapter)
                putExtra(EXTRA_USER_ID,  userId)
            })
        }
    }

    private lateinit var selectAllBar:      View
    private lateinit var selectAllCheckbox: CheckBox
    private lateinit var aiOnlyCheckbox:    CheckBox
    private lateinit var recyclerView:      RecyclerView
    private lateinit var saveNotesButton:   MaterialButton
    private lateinit var generateQuizButton: MaterialButton
    private lateinit var selectedCountBadge: TextView
    private lateinit var countSpinner:      Spinner
    private lateinit var cbMcq:             CheckBox
    private lateinit var cbShortAnswer:     CheckBox
    private lateinit var loadingView:       ProgressBar
    private lateinit var messagesEmptyHint: TextView

    private val allMessages = mutableListOf<Map<String, Any>>()
    private val adapter     = MessageReviewAdapter()

    private val subject  by lazy { intent.getStringExtra(EXTRA_SUBJECT)  ?: "General" }
    private val chapter  by lazy { intent.getStringExtra(EXTRA_CHAPTER)  ?: "Chapter" }
    private val userId   by lazy { intent.getStringExtra(EXTRA_USER_ID)
        ?: SessionManager.getFirestoreUserId(this) ?: "" }

    private val notesRepo by lazy { ChapterNotesRepository(this, userId, subject, chapter) }
    private val apiClient by lazy { QuizApiClient(AdminConfigRepository.effectiveServerUrl()) }
    private val dateFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_student_chat_selection)

        selectAllBar       = findViewById(R.id.selectAllBar)
        selectAllCheckbox  = findViewById(R.id.selectAllCheckbox)
        aiOnlyCheckbox     = findViewById(R.id.aiOnlyCheckbox)
        recyclerView       = findViewById(R.id.messagesList)
        saveNotesButton    = findViewById(R.id.saveNotesButton)
        generateQuizButton = findViewById(R.id.generateQuizButton)
        selectedCountBadge = findViewById(R.id.selectedCountBadge)
        countSpinner       = findViewById(R.id.countSpinner)
        cbMcq              = findViewById(R.id.cbMcq)
        cbShortAnswer      = findViewById(R.id.cbShortAnswer)
        loadingView        = findViewById(R.id.messagesLoadingBar)
        messagesEmptyHint  = findViewById(R.id.messagesEmptyHint)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<TextView>(R.id.selectionSubtitle).text = "$subject › $chapter"

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter       = adapter

        ArrayAdapter.createFromResource(this, R.array.quiz_counts, android.R.layout.simple_spinner_item)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
                countSpinner.adapter = it }

        selectAllCheckbox.setOnCheckedChangeListener { _, checked ->
            adapter.setAllSelected(checked)
            updateSelectionState()
        }
        aiOnlyCheckbox.setOnCheckedChangeListener { _, _ ->
            updateDisplayedMessages()
        }

        saveNotesButton.setOnClickListener    { onSaveNotes() }
        generateQuizButton.setOnClickListener { onGenerateQuiz() }

        loadMessages()
    }

    private fun loadMessages() {
        saveNotesButton.isEnabled    = false
        generateQuizButton.isEnabled = false
        selectAllBar.visibility      = View.GONE
        messagesEmptyHint.visibility = View.GONE
        recyclerView.visibility      = View.GONE
        loadingView.visibility       = View.VISIBLE

        FirestoreManager.loadMessages(
            userId  = userId,
            subject = subject,
            chapter = chapter,
            onSuccess = { msgs ->
                loadingView.visibility = View.GONE
                allMessages.clear()
                allMessages.addAll(msgs)
                updateDisplayedMessages()
                if (msgs.isEmpty()) {
                    messagesEmptyHint.visibility = View.VISIBLE
                } else {
                    recyclerView.visibility  = View.VISIBLE
                    selectAllBar.visibility  = View.VISIBLE
                }
            },
            onFailure = {
                loadingView.visibility = View.GONE
                Toast.makeText(this, "Failed to load messages", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun updateDisplayedMessages() {
        val showAiOnly = aiOnlyCheckbox.isChecked
        val filtered = if (showAiOnly) {
            allMessages.filter { (it["role"] as? String ?: "") == "model" }
        } else {
            allMessages.toList()
        }
        adapter.setMessages(filtered)
        updateSelectionState()
    }

    private fun updateSelectionState() {
        val count = adapter.getSelectedMessages().size
        selectedCountBadge.text = if (count > 0) "$count selected" else ""
        saveNotesButton.isEnabled    = count > 0
        generateQuizButton.isEnabled = count > 0
        if (count > 0) {
            saveNotesButton.text    = "📝 Notes ($count)"
            generateQuizButton.text = "🎯 Quiz ($count)"
        } else {
            saveNotesButton.text    = "📝 Save as Notes"
            generateQuizButton.text = "🎯 Generate Quiz"
        }
        // Sync select-all checkbox state silently
        val total = adapter.itemCount
        selectAllCheckbox.setOnCheckedChangeListener(null)
        selectAllCheckbox.isChecked = total > 0 && count == total
        selectAllCheckbox.setOnCheckedChangeListener { _, checked ->
            adapter.setAllSelected(checked)
            updateSelectionState()
        }
    }

    // ── Save as Notes ──────────────────────────────────────────────────────────

    private fun onSaveNotes() {
        val selected = adapter.getSelectedMessages()
        if (selected.isEmpty()) return

        // Build combined markdown from selected messages
        val sb = StringBuilder()
        sb.appendLine("## Chat Notes — $chapter")
        sb.appendLine()
        selected.forEach { msg ->
            val role = if ((msg["role"] as? String) == "model") "🤖 AI" else "👤 You"
            val text = (msg["text"] as? String ?: "").trim()
            if (text.isNotBlank()) {
                sb.appendLine("**$role:**")
                sb.appendLine(text)
                sb.appendLine()
            }
        }
        val content = sb.toString().trim()

        // Pick a category
        val cats = notesRepo.getCategories().toMutableList()
        if (!cats.contains("Chat Notes")) cats.add(0, "Chat Notes")
        val options = (cats + listOf("＋ New Category…")).toTypedArray()
        var chosen = "Chat Notes"

        android.app.AlertDialog.Builder(this)
            .setTitle("📌 Save to Notes")
            .setSingleChoiceItems(options, 0) { _, which ->
                chosen = if (which < cats.size) cats[which] else "__new__"
            }
            .setPositiveButton("Save") { _, _ ->
                if (chosen == "__new__") {
                    showNewCategoryInput { newCat ->
                        notesRepo.addCategory(newCat)
                        doSaveNote(content, newCat)
                    }
                } else {
                    doSaveNote(content, chosen)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showNewCategoryInput(onDone: (String) -> Unit) {
        val et = EditText(this).apply { hint = "Category name"; setPadding(40, 24, 40, 24) }
        android.app.AlertDialog.Builder(this)
            .setTitle("New Category")
            .setView(et)
            .setPositiveButton("Create") { _, _ ->
                val name = et.text.toString().trim()
                if (name.isNotBlank()) onDone(name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doSaveNote(content: String, category: String) {
        val note = ChapterNote(
            id        = UUID.randomUUID().toString(),
            type      = "ai",
            content   = content,
            category  = category,
            timestamp = System.currentTimeMillis()
        )
        notesRepo.saveNote(note)
        android.app.AlertDialog.Builder(this)
            .setTitle("✅ Notes Saved!")
            .setMessage("Saved to \"$category\" category. View your notes?")
            .setPositiveButton("View Notes") { _, _ ->
                NotesActivity.launch(this, subject, chapter, userId)
            }
            .setNegativeButton("Done") { _, _ -> finish() }
            .show()
    }

    // ── Generate Quiz ──────────────────────────────────────────────────────────

    private fun onGenerateQuiz() {
        val selected = adapter.getSelectedMessages()
        if (selected.isEmpty()) return

        val contextText = selected.joinToString("\n\n") { msg ->
            val role = if ((msg["role"] as? String) == "model") "AI" else "Student"
            val text = (msg["text"] as? String ?: "").take(600)
            "$role: $text"
        }

        val types = mutableListOf<String>()
        if (cbMcq.isChecked)         types.add("mcq")
        if (cbShortAnswer.isChecked) types.add("short_answer")
        if (types.isEmpty())         types.add("mcq")

        val count = when (countSpinner.selectedItemPosition) {
            0 -> 5; 1 -> 10; 2 -> 15; else -> 5
        }

        generateQuizButton.isEnabled = false
        generateQuizButton.text      = "⏳ Generating…"

        lifecycleScope.launch {
            try {
                val quiz: Quiz = withContext(Dispatchers.IO) {
                    apiClient.generateQuiz(
                        subject       = subject,
                        chapterId     = FirestoreManager.convId(subject, chapter),
                        chapterTitle  = chapter,
                        difficulty    = "medium",
                        questionTypes = types,
                        count         = count,
                        userId        = userId,
                        contextText   = contextText
                    )
                }
                generateQuizButton.isEnabled = true
                updateSelectionState()
                showQuizReadyDialog(quiz)
            } catch (e: Exception) {
                generateQuizButton.isEnabled = true
                updateSelectionState()
                Toast.makeText(this@StudentChatSelectionActivity,
                    "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showQuizReadyDialog(quiz: Quiz) {
        android.app.AlertDialog.Builder(this)
            .setTitle("✅ Quiz Ready — ${quiz.questions.size} questions")
            .setMessage("Your quiz from selected messages is ready!")
            .setPositiveButton("▶ Take Quiz") { _, _ ->
                startActivity(
                    Intent(this, QuizActivity::class.java)
                        .putExtra("quizJson", quiz.toTransferJson())
                        .putExtra("subjectName", subject)
                )
            }
            .setNegativeButton("Later", null)
            .show()
    }

    // ── Inner Adapter ──────────────────────────────────────────────────────────

    inner class MessageReviewAdapter : RecyclerView.Adapter<MessageReviewAdapter.VH>() {

        private val messages  = mutableListOf<Map<String, Any>>()
        private val selected  = mutableSetOf<String>()

        private fun msgId(msg: Map<String, Any>): String =
            (msg["_docId"] as? String)?.ifBlank { null }
                ?: msg["timestamp"]?.toString() ?: ""

        inner class VH(view: View) : RecyclerView.ViewHolder(view) {
            val checkbox:  CheckBox = view.findViewById(R.id.messageCheckbox)
            val roleLabel: TextView = view.findViewById(R.id.roleLabel)
            val timestamp: TextView = view.findViewById(R.id.timestampLabel)
            val text:      TextView = view.findViewById(R.id.messageText)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val v = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_chat_message_review, parent, false)
            return VH(v)
        }

        override fun getItemCount() = messages.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val msg   = messages[position]
            val role  = msg["role"] as? String ?: "user"
            val text  = msg["text"] as? String ?: ""
            val ts    = (msg["timestamp"] as? Long) ?: 0L

            holder.roleLabel.text = if (role == "model") "🤖 AI" else "👤 You"
            holder.roleLabel.setBackgroundColor(
                if (role == "model") android.graphics.Color.parseColor("#E8F0FE")
                else android.graphics.Color.parseColor("#FFF3E0")
            )
            holder.roleLabel.setTextColor(
                if (role == "model") android.graphics.Color.parseColor("#1967D2")
                else android.graphics.Color.parseColor("#E65100")
            )
            holder.text.text      = text
            holder.timestamp.text = if (ts > 0) dateFormat.format(Date(ts)) else ""

            val id = msgId(msg)
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = id in selected
            holder.checkbox.setOnCheckedChangeListener { _, checked ->
                val bindPos = holder.bindingAdapterPosition
                if (bindPos == RecyclerView.NO_POSITION) return@setOnCheckedChangeListener
                val currentId = msgId(messages[bindPos])
                if (checked) selected.add(currentId) else selected.remove(currentId)
                updateSelectionState()
            }
            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            }
        }

        fun setMessages(msgs: List<Map<String, Any>>) {
            messages.clear()
            messages.addAll(msgs)
            val visibleIds = msgs.mapTo(mutableSetOf()) { msgId(it) }
            selected.retainAll(visibleIds)
            notifyDataSetChanged()
        }

        fun setAllSelected(select: Boolean) {
            if (select) messages.forEach { msg -> msgId(msg).also { if (it.isNotBlank()) selected.add(it) } }
            else selected.clear()
            notifyDataSetChanged()
        }

        fun getSelectedMessages(): List<Map<String, Any>> =
            messages.filter { msg -> msgId(msg).let { it.isNotBlank() && it in selected } }
    }
}

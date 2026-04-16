package com.aiguruapp.student

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.Quiz
import com.aiguruapp.student.quiz.QuizApiClient
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONObject

/**
 * Teacher Chat Review screen.
 *
 * Teachers load their own conversation for a subject+chapter, review messages
 * with checkboxes, and generate a quiz from the selected content.
 * The generated quiz can be launched immediately or assigned as a task.
 */
class TeacherChatReviewActivity : BaseActivity() {

    private lateinit var subjectInput: TextInputEditText
    private lateinit var chapterInput: TextInputEditText
    private lateinit var selectAllBar: View
    private lateinit var selectAllCheckbox: CheckBox
    private lateinit var aiOnlyCheckbox: CheckBox
    private lateinit var recyclerView: RecyclerView
    private lateinit var generateQuizButton: MaterialButton
    private lateinit var selectedCountBadge: TextView
    private lateinit var countSpinner: Spinner
    private lateinit var cbMcq: CheckBox
    private lateinit var cbShortAnswer: CheckBox
    private lateinit var loadingView: android.widget.ProgressBar
    private lateinit var messagesEmptyHint: TextView

    private val allMessages = mutableListOf<Map<String, Any>>()
    private val adapter = MessageReviewAdapter()

    private val userId by lazy { SessionManager.getFirestoreUserId(this) }
    private val apiClient by lazy { QuizApiClient(AdminConfigRepository.effectiveServerUrl()) }
    private val dateFormat = SimpleDateFormat("d MMM, h:mm a", Locale.getDefault())

    private var lastGeneratedQuiz: Quiz? = null
    private var lastSubject = ""
    private var lastChapter = ""

    // Launches quiz validation screen and receives the filtered quiz back
    private val quizValidationLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val filteredJson = result.data
                ?.getStringExtra(TeacherQuizValidationActivity.RESULT_FILTERED_QUIZ) ?: return@registerForActivityResult
            val filteredQuiz = Quiz.fromJson(JSONObject(filteredJson))
            lastGeneratedQuiz = filteredQuiz
            showQuizReadyDialog(filteredQuiz, lastSubject, lastChapter)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_chat_review)

        subjectInput      = findViewById(R.id.subjectInput)
        chapterInput      = findViewById(R.id.chapterInput)
        selectAllBar      = findViewById(R.id.selectAllBar)
        selectAllCheckbox = findViewById(R.id.selectAllCheckbox)
        aiOnlyCheckbox    = findViewById(R.id.aiOnlyCheckbox)
        recyclerView      = findViewById(R.id.messagesList)
        generateQuizButton = findViewById(R.id.generateQuizButton)
        selectedCountBadge = findViewById(R.id.selectedCountBadge)
        countSpinner      = findViewById(R.id.countSpinner)
        cbMcq             = findViewById(R.id.cbMcq)
        cbShortAnswer     = findViewById(R.id.cbShortAnswer)
        loadingView       = findViewById(R.id.messagesLoadingBar)
        messagesEmptyHint = findViewById(R.id.messagesEmptyHint)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        ArrayAdapter.createFromResource(this, R.array.quiz_counts, android.R.layout.simple_spinner_item)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); countSpinner.adapter = it }

        selectAllCheckbox.setOnCheckedChangeListener { _, checked ->
            adapter.setAllSelected(checked)
            updateSelectionState()
        }
        aiOnlyCheckbox.setOnCheckedChangeListener { _, _ ->
            updateDisplayedMessages()
        }

        findViewById<MaterialButton>(R.id.loadMessagesButton).setOnClickListener {
            val subject = subjectInput.text.toString().trim()
            val chapter = chapterInput.text.toString().trim()
            if (subject.isBlank() || chapter.isBlank()) {
                Toast.makeText(this, "Enter subject and chapter", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            loadMessages(subject, chapter)
        }

        generateQuizButton.setOnClickListener { onGenerateQuiz() }
    }

    private fun loadMessages(subject: String, chapter: String) {
        generateQuizButton.isEnabled = false
        selectAllBar.visibility       = View.GONE
        messagesEmptyHint.visibility  = View.GONE
        recyclerView.visibility       = View.GONE
        loadingView.visibility        = View.VISIBLE
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
                    recyclerView.visibility = View.VISIBLE
                    selectAllBar.visibility = View.VISIBLE
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
        generateQuizButton.isEnabled = count > 0
        generateQuizButton.text = if (count > 0)
            "🎯 Generate Quiz from $count Message${if (count > 1) "s" else ""}"
        else
            "🎯 Generate Quiz from Selected Messages"
        // Sync select-all checkbox without re-triggering its own listener
        val total = adapter.itemCount
        selectAllCheckbox.setOnCheckedChangeListener(null)
        selectAllCheckbox.isChecked = total > 0 && count == total
        selectAllCheckbox.setOnCheckedChangeListener { _, checked ->
            adapter.setAllSelected(checked)
            updateSelectionState()
        }
    }

    private fun onGenerateQuiz() {
        val selected = adapter.getSelectedMessages()
        if (selected.isEmpty()) return

        val subject = subjectInput.text.toString().trim().ifBlank { "General" }
        val chapter = chapterInput.text.toString().trim().ifBlank { "General" }

        // Build context text from selected messages
        val contextText = selected.joinToString("\n\n") { msg ->
            val role = if ((msg["role"] as? String) == "model") "AI" else "Student"
            val text = (msg["text"] as? String ?: "").take(500)
            "$role: $text"
        }

        val types = mutableListOf<String>()
        if (cbMcq.isChecked) types.add("mcq")
        if (cbShortAnswer.isChecked) types.add("short_answer")
        if (types.isEmpty()) types.add("mcq")

        val count = when (countSpinner.selectedItemPosition) {
            0 -> 5; 1 -> 10; 2 -> 15; else -> 5
        }

        generateQuizButton.isEnabled = false
        generateQuizButton.text = "⏳ Generating…"

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
                lastGeneratedQuiz = quiz
                lastSubject = subject
                lastChapter = chapter
                generateQuizButton.isEnabled = true
                generateQuizButton.text = "🎯 Generate Quiz from Selected Messages"

                // Open validation screen so teacher can keep/remove questions
                quizValidationLauncher.launch(
                    Intent(this@TeacherChatReviewActivity, TeacherQuizValidationActivity::class.java)
                        .putExtra(TeacherQuizValidationActivity.EXTRA_QUIZ_JSON, quiz.toTransferJson())
                        .putExtra(TeacherQuizValidationActivity.EXTRA_SUBJECT_NAME, subject)
                        .putExtra(TeacherQuizValidationActivity.EXTRA_CHAPTER_NAME, chapter)
                )
            } catch (e: Exception) {
                generateQuizButton.isEnabled = true
                generateQuizButton.text = "🎯 Generate Quiz from Selected Messages"
                Toast.makeText(this@TeacherChatReviewActivity, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showQuizReadyDialog(quiz: Quiz, subject: String, chapter: String) {
        android.app.AlertDialog.Builder(this)
            .setTitle("✅ Quiz Ready — ${quiz.questions.size} questions")
            .setMessage("What would you like to do with this quiz?")
            .setPositiveButton("▶ Preview Quiz") { _, _ ->
                startActivity(
                    Intent(this, QuizActivity::class.java)
                        .putExtra("quizJson", quiz.toTransferJson())
                        .putExtra("subjectName", subject)
                )
            }
            .setNeutralButton("📋 Assign as Task") { _, _ ->
                startActivity(
                    Intent(this, TeacherTasksActivity::class.java)
                        .putExtra(TeacherTasksActivity.EXTRA_PREFILL_QUIZ_JSON, quiz.toTransferJson())
                        .putExtra(TeacherTasksActivity.EXTRA_PREFILL_SUBJECT, subject)
                        .putExtra(TeacherTasksActivity.EXTRA_PREFILL_CHAPTER, chapter)
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    inner class MessageReviewAdapter : RecyclerView.Adapter<MessageReviewAdapter.VH>() {

        private val messages = mutableListOf<Map<String, Any>>()
        private val selected = mutableSetOf<String>()  // stable message doc IDs

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
            val msg  = messages[position]
            val role = msg["role"] as? String ?: "user"
            val text = msg["text"] as? String ?: ""
            val ts   = (msg["timestamp"] as? Long) ?: 0L

            holder.roleLabel.text = if (role == "model") "🤖 AI" else "👤 Student"
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
            // Preserve selections for messages still visible after a filter change
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

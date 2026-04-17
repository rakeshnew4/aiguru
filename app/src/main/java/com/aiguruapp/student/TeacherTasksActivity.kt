package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.widget.NestedScrollView
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

/**
 * Teacher task management screen.
 *
 * Teachers can create tasks (BB Lesson / Quiz / Both) for a school+grade and assign them.
 * Existing tasks are shown below with a "Remove" button to deactivate them.
 *
 * Pre-fill extras (from TeacherChatReviewActivity or TeacherSavedContentActivity):
 *   EXTRA_PREFILL_QUIZ_JSON, EXTRA_PREFILL_SUBJECT, EXTRA_PREFILL_CHAPTER, EXTRA_PREFILL_BB_TOPIC
 */
class TeacherTasksActivity : BaseActivity() {

    companion object {
        const val EXTRA_PREFILL_QUIZ_JSON  = "extra_prefill_quiz_json"
        const val EXTRA_PREFILL_SUBJECT    = "extra_prefill_subject"
        const val EXTRA_PREFILL_CHAPTER    = "extra_prefill_chapter"
        const val EXTRA_PREFILL_BB_TOPIC   = "extra_prefill_bb_topic"
        /** Pre-published quiz ID from quizzes/ collection — preferred over EXTRA_PREFILL_QUIZ_JSON. */
        const val EXTRA_PREFILL_QUIZ_ID    = "extra_prefill_quiz_id"
        /** Pre-published BB lesson ID from bb_cache/ collection — preferred over EXTRA_PREFILL_BB_TOPIC. */
        const val EXTRA_PREFILL_BB_CACHE_ID = "extra_prefill_bb_cache_id"
    }

    // Form views
    private lateinit var createPanel: NestedScrollView
    private lateinit var taskTitleInput: TextInputEditText
    private lateinit var taskDescInput: TextInputEditText
    private lateinit var taskSubjectInput: TextInputEditText
    private lateinit var taskChapterInput: TextInputEditText
    private lateinit var taskSchoolInput: TextInputEditText
    private lateinit var taskGradeInput: TextInputEditText
    private lateinit var taskTypeGroup: RadioGroup
    private lateinit var bbTopicLayout: View
    private lateinit var bbTopicInput: TextInputEditText
    private lateinit var quizSection: View
    private lateinit var quizCbMcq: CheckBox
    private lateinit var quizCbShort: CheckBox
    private lateinit var quizCountSpinner: Spinner
    private lateinit var generateForTaskButton: MaterialButton
    private lateinit var quizStatusText: TextView
    private lateinit var saveTaskButton: MaterialButton

    // List
    private lateinit var tasksList: RecyclerView
    private lateinit var emptyTasksState: android.widget.TextView
    private lateinit var tasksLoadingBar: android.widget.ProgressBar
    private val tasks = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: TasksAdapter

    private val userId by lazy { SessionManager.getFirestoreUserId(this) }
    private val apiClient by lazy { QuizApiClient(AdminConfigRepository.effectiveServerUrl()) }
    private var generatedQuizJson = ""
    /** ID of the bb_cache/ lesson selected for this task (empty = use bbTopic string / legacy). */
    private var selectedBbCacheId = ""
    /** ID of the quizzes/ document selected for this task (empty = use embedded generatedQuizJson). */
    private var selectedQuizId = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_tasks)

        createPanel          = findViewById(R.id.createPanel)
        taskTitleInput       = findViewById(R.id.taskTitleInput)
        taskDescInput        = findViewById(R.id.taskDescInput)
        taskSubjectInput     = findViewById(R.id.taskSubjectInput)
        taskChapterInput     = findViewById(R.id.taskChapterInput)
        taskSchoolInput      = findViewById(R.id.taskSchoolInput)
        taskGradeInput       = findViewById(R.id.taskGradeInput)
        taskTypeGroup        = findViewById(R.id.taskTypeGroup)
        bbTopicLayout        = findViewById(R.id.bbTopicLayout)
        bbTopicInput         = findViewById(R.id.bbTopicInput)
        quizSection          = findViewById(R.id.quizSection)
        quizCbMcq            = findViewById(R.id.quizCbMcq)
        quizCbShort          = findViewById(R.id.quizCbShort)
        quizCountSpinner     = findViewById(R.id.quizCountSpinner)
        generateForTaskButton = findViewById(R.id.generateForTaskButton)
        quizStatusText       = findViewById(R.id.quizStatusText)
        saveTaskButton       = findViewById(R.id.saveTaskButton)
        tasksList            = findViewById(R.id.tasksList)
        emptyTasksState      = findViewById(R.id.emptyTasksState)
        tasksLoadingBar      = findViewById(R.id.tasksLoadingBar)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        // Pre-fill from caller
        intent.getStringExtra(EXTRA_PREFILL_SUBJECT)?.let { taskSubjectInput.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_CHAPTER)?.let { taskChapterInput.setText(it) }
        intent.getStringExtra(EXTRA_PREFILL_BB_TOPIC)?.let { bbTopicInput.setText(it) }
        // New: pre-fill by library ID (preferred over embedded JSON)
        intent.getStringExtra(EXTRA_PREFILL_BB_CACHE_ID)?.let { id ->
            selectedBbCacheId = id
            bbTopicInput.setText("📚 Lesson loaded (ID: ${id.take(8)}…)")
            bbTopicInput.isEnabled = false
        }
        intent.getStringExtra(EXTRA_PREFILL_QUIZ_ID)?.let { id ->
            selectedQuizId = id
            quizStatusText.text = "✅ Quiz loaded from library"
        }
        intent.getStringExtra(EXTRA_PREFILL_QUIZ_JSON)?.let { json ->
            if (selectedQuizId.isBlank()) {
                generatedQuizJson = json
                quizStatusText.text = "✅ Quiz ready (${countQuestions(json)} questions)"
            }
        }

        // Show create panel when pre-fill is present
        val hasBbPrefill  = intent.hasExtra(EXTRA_PREFILL_BB_TOPIC) || intent.hasExtra(EXTRA_PREFILL_BB_CACHE_ID)
        val hasQuizPrefill = intent.hasExtra(EXTRA_PREFILL_QUIZ_JSON) || intent.hasExtra(EXTRA_PREFILL_QUIZ_ID)
        if (hasBbPrefill || hasQuizPrefill) {
            createPanel.visibility = View.VISIBLE
            updateTaskTypeSections()
            if (hasQuizPrefill && !hasBbPrefill) {
                taskTypeGroup.check(R.id.rbQuiz)
                updateTaskTypeSections()
            } else if (hasBbPrefill && hasQuizPrefill) {
                taskTypeGroup.check(R.id.rbBoth)
                updateTaskTypeSections()
            }
        }

        // Quiz count spinner
        ArrayAdapter.createFromResource(this, R.array.quiz_counts, android.R.layout.simple_spinner_item)
            .also { it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); quizCountSpinner.adapter = it }

        taskTypeGroup.setOnCheckedChangeListener { _, _ -> updateTaskTypeSections() }

        // Pre-fill school/grade from session
        val sessionSchool = SessionManager.getSchoolId(this)
        val sessionGrade  = SessionManager.getGrade(this)
        if (sessionSchool.isNotBlank()) taskSchoolInput.setText(sessionSchool)
        if (sessionGrade.isNotBlank())  taskGradeInput.setText(sessionGrade)

        findViewById<MaterialButton>(R.id.createTaskButton).setOnClickListener {
            createPanel.visibility = if (createPanel.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }

        generateForTaskButton.setOnClickListener { onGenerateQuizForTask() }
        saveTaskButton.setOnClickListener { onSaveTask() }

        adapter = TasksAdapter(
            tasks        = tasks,
            onDeactivate = { task -> onDeactivateTask(task) },
            onViewReport = { task -> onViewTaskReport(task) }
        )
        tasksList.layoutManager = LinearLayoutManager(this)
        tasksList.adapter = adapter

        loadMyTasks()
    }

    private fun updateTaskTypeSections() {
        val checkedId = taskTypeGroup.checkedRadioButtonId
        bbTopicLayout.visibility = if (checkedId == R.id.rbBbLesson || checkedId == R.id.rbBoth) View.VISIBLE else View.GONE
        quizSection.visibility   = if (checkedId == R.id.rbQuiz || checkedId == R.id.rbBoth) View.VISIBLE else View.GONE
    }

    private fun onGenerateQuizForTask() {
        val subject = taskSubjectInput.text.toString().trim().ifBlank { "General" }
        val chapter = taskChapterInput.text.toString().trim().ifBlank { "General" }
        val types   = mutableListOf<String>().apply {
            if (quizCbMcq.isChecked) add("mcq")
            if (quizCbShort.isChecked) add("short_answer")
            if (isEmpty()) add("mcq")
        }
        val count = when (quizCountSpinner.selectedItemPosition) { 0 -> 5; 1 -> 10; else -> 15 }

        generateForTaskButton.isEnabled = false
        quizStatusText.text = "⏳ Generating quiz…"

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
                        userId        = userId
                    )
                }
                generatedQuizJson = quiz.toTransferJson()
                quizStatusText.text = "✅ Quiz ready (${quiz.questions.size} questions)"
            } catch (e: Exception) {
                quizStatusText.text = "❌ Failed: ${e.message}"
            } finally {
                generateForTaskButton.isEnabled = true
            }
        }
    }

    private fun onSaveTask() {
        val title   = taskTitleInput.text.toString().trim()
        val school  = taskSchoolInput.text.toString().trim()
        if (title.isBlank())  { taskTitleInput.error = "Enter a title"; return }
        if (school.isBlank()) { taskSchoolInput.error = "Enter school code"; return }

        val checkedId = taskTypeGroup.checkedRadioButtonId
        val taskType = when (checkedId) {
            R.id.rbBbLesson -> "bb_lesson"
            R.id.rbQuiz     -> "quiz"
            else            -> "both"
        }
        val bbTopic = bbTopicInput.text.toString().trim()
        val needsBb = taskType == "bb_lesson" || taskType == "both"
        val needsQuiz = taskType == "quiz" || taskType == "both"
        if (needsBb && bbTopic.isBlank() && selectedBbCacheId.isBlank()) {
            Toast.makeText(this, "Enter a Blackboard lesson topic or pick a lesson", Toast.LENGTH_SHORT).show()
            return
        }
        if (needsQuiz && generatedQuizJson.isBlank() && selectedQuizId.isBlank()) {
            Toast.makeText(this, "Generate or select quiz questions first", Toast.LENGTH_SHORT).show()
            return
        }

        saveTaskButton.isEnabled = false
        FirestoreManager.saveTask(
            taskId      = "",
            teacherId   = userId,
            schoolId    = school,
            grade       = taskGradeInput.text.toString().trim(),
            title       = title,
            description = taskDescInput.text.toString().trim(),
            taskType    = taskType,
            subject     = taskSubjectInput.text.toString().trim(),
            chapter     = taskChapterInput.text.toString().trim(),
            bbTopic     = if (selectedBbCacheId.isNotBlank()) bbTopic else bbTopic,
            quizJson    = if (selectedQuizId.isBlank()) generatedQuizJson else "",
            bbCacheId   = selectedBbCacheId,
            quizId      = selectedQuizId,
            onSuccess   = { _ ->
                saveTaskButton.isEnabled = true
                Toast.makeText(this, "✅ Task assigned!", Toast.LENGTH_SHORT).show()
                clearForm()
                createPanel.visibility = View.GONE
                loadMyTasks()
            },
            onFailure   = { e ->
                saveTaskButton.isEnabled = true
                Toast.makeText(this, "Failed: ${e?.message}", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun clearForm() {
        taskTitleInput.setText(""); taskDescInput.setText("")
        taskSubjectInput.setText(""); taskChapterInput.setText("")
        bbTopicInput.setText(""); bbTopicInput.isEnabled = true
        generatedQuizJson = ""; selectedBbCacheId = ""; selectedQuizId = ""; quizStatusText.text = ""
    }

    private fun loadMyTasks() {
        tasksLoadingBar.visibility = View.VISIBLE
        tasksList.visibility       = View.GONE
        emptyTasksState.visibility = View.GONE
        FirestoreManager.loadTasksByTeacher(
            teacherId = userId,
            onSuccess = { list ->
                tasksLoadingBar.visibility = View.GONE
                tasks.clear(); tasks.addAll(list); adapter.notifyDataSetChanged()
                if (tasks.isEmpty()) {
                    emptyTasksState.visibility = View.VISIBLE
                    tasksList.visibility       = View.GONE
                } else {
                    emptyTasksState.visibility = View.GONE
                    tasksList.visibility       = View.VISIBLE
                }
            },
            onFailure = {
                tasksLoadingBar.visibility = View.GONE
            }
        )
    }

    private fun onDeactivateTask(task: Map<String, Any>) {
        val id = task["task_id"] as? String ?: task["id"] as? String ?: return
        android.app.AlertDialog.Builder(this)
            .setTitle("Remove Task?")
            .setMessage("Students will no longer see this task.")
            .setPositiveButton("Remove") { _, _ ->
                FirestoreManager.deactivateTask(id,
                    onSuccess = { loadMyTasks() },
                    onFailure = { Toast.makeText(this, "Failed", Toast.LENGTH_SHORT).show() }
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onViewTaskReport(task: Map<String, Any>) {
        val taskId    = task["task_id"] as? String ?: task["id"] as? String ?: return
        val taskTitle = task["title"]   as? String ?: ""
        val taskType  = task["task_type"] as? String ?: ""
        startActivity(
            android.content.Intent(this, TeacherTaskReportActivity::class.java)
                .putExtra(TeacherTaskReportActivity.EXTRA_TASK_ID,    taskId)
                .putExtra(TeacherTaskReportActivity.EXTRA_TASK_TITLE, taskTitle)
                .putExtra(TeacherTaskReportActivity.EXTRA_TASK_TYPE,  taskType)
        )
    }

    private fun countQuestions(json: String): Int = try {
        org.json.JSONObject(json).optJSONArray("questions")?.length() ?: 0
    } catch (_: Exception) { 0 }

    // ── Tasks Adapter ──────────────────────────────────────────────────────────

    private class TasksAdapter(
        private val tasks: List<Map<String, Any>>,
        private val onDeactivate: (Map<String, Any>) -> Unit,
        private val onViewReport: (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<TasksAdapter.VH>() {

        private val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        private val typeColors = mapOf(
            "bb_lesson" to android.graphics.Color.parseColor("#5C35B5"),
            "quiz"      to android.graphics.Color.parseColor("#1967D2"),
            "both"      to android.graphics.Color.parseColor("#2E7D32")
        )
        private val typeLabels = mapOf(
            "bb_lesson" to "🎓 BB", "quiz" to "🧠 Quiz", "both" to "📚 Both"
        )

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge:         TextView = v.findViewById(R.id.taskTypeBadge)
            val title:         TextView = v.findViewById(R.id.taskTitle)
            val meta:          TextView = v.findViewById(R.id.taskMeta)
            val grade:         TextView = v.findViewById(R.id.taskGrade)
            val viewReportBtn: TextView = v.findViewById(R.id.viewReportBtn)
            val deactivateBtn: TextView = v.findViewById(R.id.deactivateBtn)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_teacher_task, p, false))

        override fun getItemCount() = tasks.size

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val task = tasks[pos]
            val type = task["task_type"] as? String ?: "quiz"
            holder.badge.text = typeLabels[type] ?: type
            holder.badge.setBackgroundColor(typeColors[type] ?: android.graphics.Color.GRAY)
            holder.title.text = task["title"] as? String ?: ""
            val subj = task["subject"] as? String ?: ""
            val chap = task["chapter"] as? String ?: ""
            val ts   = (task["created_at"] as? Long) ?: 0L
            holder.meta.text  = if (subj.isNotBlank()) "$subj · $chap" else ""
            val gradeVal = task["grade"] as? String ?: ""
            holder.grade.text = buildString {
                if (gradeVal.isNotBlank()) append("Grade: $gradeVal  ")
                if (ts > 0) append("• ${fmt.format(Date(ts))}")
            }
            holder.viewReportBtn.setOnClickListener { onViewReport(task) }
            holder.deactivateBtn.setOnClickListener { onDeactivate(task) }
        }
    }
}

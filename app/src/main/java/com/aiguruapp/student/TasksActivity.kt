package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton

/**
 * Student task list. Shows tasks assigned for the student's school + grade.
 * Supports BB Lesson tasks (→ BlackboardActivity) and Quiz tasks (→ QuizActivity).
 */
class TasksActivity : BaseActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyState: View
    private lateinit var emptySubtitle: android.widget.TextView
    private lateinit var loadingBar: android.widget.ProgressBar
    private lateinit var adapter: TasksAdapter

    private val tasks         = mutableListOf<Map<String, Any>>()
    private val completedIds  = mutableSetOf<String>()
    private val taskProgress = mutableMapOf<String, Map<String, Any>>()
    private val userId   by lazy { SessionManager.getFirestoreUserId(this) }
    private val schoolId by lazy { SessionManager.getSchoolId(this) }
    private val grade    by lazy { SessionManager.getGrade(this) }
    private val section  by lazy { SessionManager.getSection(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.aiguruapp.student.config.AccessGate.requireAccess(this, com.aiguruapp.student.config.AccessGate.Feature.TASKS)) return
        setContentView(R.layout.activity_tasks)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        emptyState   = findViewById(R.id.emptyState)
        emptySubtitle = findViewById(R.id.emptySubtitle)
        loadingBar   = findViewById(R.id.loadingBar)
        recyclerView = findViewById(R.id.tasksList)

        adapter = TasksAdapter(
            tasks        = tasks,
            completedIds = completedIds,
            taskProgress = taskProgress,
            onViewLesson = { task -> launchLesson(task) },
            onTakeQuiz   = { task -> launchQuiz(task) },
            onMarkDone   = { task -> markDone(task) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadData()
    }

    private fun loadData() {
        if (schoolId.isBlank()) {
            loadingBar.visibility   = View.GONE
            emptyState.visibility   = View.VISIBLE
            recyclerView.visibility = View.GONE
            emptySubtitle.text = "Join a school first to see your tasks"
            return
        }
        loadingBar.visibility   = View.VISIBLE
        emptyState.visibility   = View.GONE
        recyclerView.visibility = View.GONE
        // First load task progress, then tasks
        FirestoreManager.loadAllTaskProgress(
            userId    = userId,
            onSuccess = { progressMap ->
                taskProgress.clear()
                taskProgress.putAll(progressMap)
                completedIds.clear()
                completedIds.addAll(progressMap.keys)
                loadTasks()
            },
            onFailure = { loadTasks() }
        )
    }

    private fun loadTasks() {
        FirestoreManager.loadTasksForSchool(
            schoolId  = schoolId,
            grade     = grade,
            section   = section,
            onSuccess = { list ->
                loadingBar.visibility = View.GONE
                tasks.clear(); tasks.addAll(list); adapter.notifyDataSetChanged()
                if (tasks.isEmpty()) {
                    emptySubtitle.text  = "Your teacher hasn't assigned any tasks yet"
                    emptyState.visibility   = View.VISIBLE
                    recyclerView.visibility = View.GONE
                } else {
                    emptyState.visibility   = View.GONE
                    recyclerView.visibility = View.VISIBLE
                }
            },
            onFailure = {
                loadingBar.visibility = View.GONE
                Toast.makeText(this, "Couldn't load tasks", Toast.LENGTH_SHORT).show()
            }
        )
    }

    private fun launchLesson(task: Map<String, Any>) {
        val bbCacheId = task["bb_cache_id"] as? String ?: ""
        val topic     = task["bb_topic"]    as? String ?: ""
        val subject   = task["subject"]     as? String ?: "General"
        val chapter   = task["chapter"]     as? String ?: "General"
        val taskId    = task["task_id"]     as? String ?: task["id"] as? String ?: ""
        if (bbCacheId.isBlank() && topic.isBlank()) {
            Toast.makeText(this, "Lesson not available", Toast.LENGTH_SHORT).show(); return
        }
        startActivity(
            Intent(this, BlackboardActivity::class.java).apply {
                if (bbCacheId.isNotBlank()) {
                    // Teacher-assigned lesson: load from global bb_cache — no LLM call
                    putExtra(BlackboardActivity.EXTRA_BB_CACHE_ID, bbCacheId)
                } else {
                    // Legacy: topic string triggers LLM generation (backwards compat)
                    putExtra(BlackboardActivity.EXTRA_MESSAGE, topic)
                }
                putExtra(BlackboardActivity.EXTRA_USER_ID, userId)
                putExtra(BlackboardActivity.EXTRA_SUBJECT, subject)
                putExtra(BlackboardActivity.EXTRA_CHAPTER, chapter)
                if (taskId.isNotBlank()) putExtra(BlackboardActivity.EXTRA_TASK_ID, taskId)
            }
        )
    }

    private fun launchQuiz(task: Map<String, Any>) {
        val quizId   = task["quiz_id"]  as? String ?: ""
        val taskId   = task["task_id"]  as? String ?: task["id"] as? String ?: ""
        val subject  = task["subject"]  as? String ?: ""
        if (quizId.isNotBlank()) {
            // New flow: load quiz from global quizzes/ collection by ID
            FirestoreManager.loadQuizFromLibrary(
                quizId    = quizId,
                onSuccess = { data ->
                    val questionsJson = data["questions_json"] as? String ?: ""
                    if (questionsJson.isBlank()) {
                        Toast.makeText(this, "Quiz not available", Toast.LENGTH_SHORT).show(); return@loadQuizFromLibrary
                    }
                    startActivity(
                        Intent(this, QuizActivity::class.java)
                            .putExtra("quizJson", questionsJson)
                            .putExtra("subjectName", subject)
                            .putExtra("taskId", taskId)
                    )
                },
                onFailure = {
                    Toast.makeText(this, "Quiz not available", Toast.LENGTH_SHORT).show()
                }
            )
            return
        }
        // Legacy fallback: embedded quiz_json in task document
        val quizJson = task["quiz_json"] as? String ?: ""
        if (quizJson.isBlank()) { Toast.makeText(this, "Quiz not available", Toast.LENGTH_SHORT).show(); return }
        startActivity(
            Intent(this, QuizActivity::class.java)
                .putExtra("quizJson", quizJson)
                .putExtra("subjectName", subject)
                .putExtra("taskId", taskId)
        )
    }

    private fun markDone(task: Map<String, Any>) {
        val taskId = task["task_id"] as? String ?: task["id"] as? String ?: return
        FirestoreManager.markTaskComplete(
            userId    = userId,
            taskId    = taskId,
            onSuccess = {
                completedIds.add(taskId); adapter.notifyDataSetChanged()
            },
            onFailure = {
                Toast.makeText(this, "Could not mark done", Toast.LENGTH_SHORT).show()
            }
        )
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class TasksAdapter(
        private val tasks:        List<Map<String, Any>>,
        private val completedIds: Set<String>,
        private val taskProgress: Map<String, Map<String, Any>>,
        private val onViewLesson: (Map<String, Any>) -> Unit,
        private val onTakeQuiz:   (Map<String, Any>) -> Unit,
        private val onMarkDone:   (Map<String, Any>) -> Unit
    ) : RecyclerView.Adapter<TasksAdapter.VH>() {

        private val typeColors = mapOf(
            "bb_lesson" to android.graphics.Color.parseColor("#5C35B5"),
            "quiz"      to android.graphics.Color.parseColor("#1967D2"),
            "both"      to android.graphics.Color.parseColor("#2E7D32")
        )
        private val typeLabels = mapOf(
            "bb_lesson" to "🎓 BB", "quiz" to "🧠 Quiz", "both" to "📚 Both"
        )

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val badge:          TextView      = v.findViewById(R.id.taskTypeBadge)
            val title:          TextView      = v.findViewById(R.id.taskTitle)
            val desc:           TextView      = v.findViewById(R.id.taskDesc)
            val meta:           TextView      = v.findViewById(R.id.taskMeta)
            val completedBadge: TextView      = v.findViewById(R.id.completedBadge)
            val viewLessonBtn:  MaterialButton = v.findViewById(R.id.viewLessonBtn)
            val takeQuizBtn:    MaterialButton = v.findViewById(R.id.takeQuizBtn)
            val markDoneBtn:    MaterialButton = v.findViewById(R.id.markDoneBtn)
        }

        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_task, p, false))

        override fun getItemCount() = tasks.size

        override fun onBindViewHolder(holder: VH, pos: Int) {
            val task     = tasks[pos]
            val type     = task["task_type"] as? String ?: "quiz"
            val taskId   = task["task_id"] as? String ?: task["id"] as? String ?: ""
            val progress = taskProgress[taskId]
            val bbDone   = progress?.get("bb_completed")   == true
            val quizDone = progress?.get("quiz_completed") == true
            val done = when (type) {
                "bb_lesson" -> bbDone
                "quiz"      -> quizDone
                "both"      -> bbDone && quizDone
                else        -> completedIds.contains(taskId)
            }

            holder.badge.text = typeLabels[type] ?: type
            holder.badge.setBackgroundColor(typeColors[type] ?: android.graphics.Color.GRAY)
            holder.title.text = task["title"] as? String ?: ""

            // Show description, or BB read progress as secondary info line
            val desc        = task["description"] as? String ?: ""
            val stepsViewed = (progress?.get("bb_steps_viewed") as? Number)?.toInt() ?: 0
            val totalSteps  = (progress?.get("bb_total_steps")  as? Number)?.toInt() ?: 0
            val progressText = when {
                desc.isNotBlank()                 -> desc
                bbDone                            -> "📖 Lesson fully read"
                stepsViewed > 0 && totalSteps > 0 -> "📖 Read $stepsViewed/$totalSteps steps"
                else                              -> ""
            }
            holder.desc.text  = progressText
            holder.desc.visibility = if (progressText.isBlank()) View.GONE else View.VISIBLE

            // Meta line: subject · chapter · due date
            val subj    = task["subject"]  as? String ?: ""
            val chap    = task["chapter"]  as? String ?: ""
            val dueDate = (task["due_date"] as? Number)?.toLong() ?: 0L
            val metaParts = mutableListOf<String>()
            if (subj.isNotBlank()) metaParts.add("$subj · $chap")
            if (dueDate > 0L && !done) {
                val dayMs = 86_400_000L
                val diff  = dueDate - System.currentTimeMillis()
                metaParts.add(when {
                    diff < 0        -> "⚠️ Overdue"
                    diff < dayMs    -> "📅 Due today"
                    diff < 2*dayMs  -> "📅 Due tomorrow"
                    else            -> "📅 Due in ${(diff / dayMs).toInt()}d"
                })
            }
            holder.meta.text  = metaParts.joinToString("  •  ")

            // Completed state
            holder.completedBadge.visibility = if (done) View.VISIBLE else View.GONE
            holder.itemView.alpha = if (done) 0.6f else 1.0f

            // Show relevant action buttons
            val hasLesson = type == "bb_lesson" || type == "both"
            val hasQuiz   = (type == "quiz" || type == "both") &&
                            ((task["quiz_json"] as? String)?.isNotBlank() == true ||
                             (task["quiz_id"]   as? String)?.isNotBlank() == true)
            // For "both" tasks: quiz is locked until the BB lesson has been read
            val quizUnlocked = type != "both" || bbDone

            holder.viewLessonBtn.visibility = if (hasLesson && !bbDone)                 View.VISIBLE else View.GONE
            holder.takeQuizBtn.visibility   = if (hasQuiz && !quizDone && quizUnlocked) View.VISIBLE else View.GONE
            holder.markDoneBtn.visibility   = if (!done && (hasLesson || hasQuiz))       View.VISIBLE else View.GONE

            holder.viewLessonBtn.setOnClickListener { onViewLesson(task) }
            holder.takeQuizBtn.setOnClickListener   { onTakeQuiz(task) }
            holder.markDoneBtn.setOnClickListener   { onMarkDone(task) }
        }
    }
}

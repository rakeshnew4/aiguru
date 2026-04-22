package com.aiguruapp.student

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.firestore.FirestoreManager
import com.google.android.material.card.MaterialCardView

/**
 * Teacher view: shows per-student BB and Quiz completion status for a specific task.
 *
 * Launched from TeacherTasksActivity with:
 *   EXTRA_TASK_ID   — Firestore task document ID
 *   EXTRA_TASK_TITLE — display title for the header
 *   EXTRA_TASK_TYPE  — "bb_lesson" | "quiz" | "both"
 */
class TeacherTaskReportActivity : BaseActivity() {

    companion object {
        const val EXTRA_TASK_ID    = "extra_task_id"
        const val EXTRA_TASK_TITLE = "extra_task_title"
        const val EXTRA_TASK_TYPE  = "extra_task_type"
    }

    private lateinit var loadingBar:    ProgressBar
    private lateinit var emptyState:    View
    private lateinit var recyclerView:  RecyclerView
    private lateinit var statBbDone:    TextView
    private lateinit var statQuizDone:  TextView
    private lateinit var statBothDone:  TextView

    private val completions = mutableListOf<Map<String, Any>>()
    private lateinit var adapter: CompletionsAdapter

    private val taskId    by lazy { intent.getStringExtra(EXTRA_TASK_ID).orEmpty() }
    private val taskTitle by lazy { intent.getStringExtra(EXTRA_TASK_TITLE).orEmpty() }
    private val taskType  by lazy { intent.getStringExtra(EXTRA_TASK_TYPE).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_task_report)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        findViewById<TextView>(R.id.reportTitle).text = taskTitle.ifBlank { "Task Report" }
        val typeLabel = when (taskType) {
            "bb_lesson" -> "Blackboard lesson"
            "quiz"      -> "Quiz"
            "both"      -> "BB Lesson + Quiz"
            else        -> "Task"
        }
        findViewById<TextView>(R.id.reportSubtitle).text = typeLabel

        loadingBar   = findViewById(R.id.loadingBar)
        emptyState   = findViewById(R.id.emptyState)
        recyclerView = findViewById(R.id.completionsList)
        statBbDone   = findViewById(R.id.statBbDone)
        statQuizDone = findViewById(R.id.statQuizDone)
        statBothDone = findViewById(R.id.statBothDone)

        adapter = CompletionsAdapter(completions, taskType)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        loadCompletions()
    }

    private fun loadCompletions() {
        if (taskId.isBlank()) { showEmpty(); return }
        loadingBar.visibility = View.VISIBLE
        FirestoreManager.loadTaskCompletions(
            taskId    = taskId,
            onSuccess = { list ->
                loadingBar.visibility = View.GONE
                completions.clear()
                completions.addAll(list.sortedBy { (it["student_name"] as? String ?: "").lowercase() })
                adapter.notifyDataSetChanged()
                updateSummary()
                if (completions.isEmpty()) showEmpty() else showList()
            },
            onFailure = {
                loadingBar.visibility = View.GONE
                Toast.makeText(this, "Failed to load completions", Toast.LENGTH_SHORT).show()
                showEmpty()
            }
        )
    }

    private fun updateSummary() {
        val bbDone   = completions.count { it["bb_completed"]   == true }
        val quizDone = completions.count { it["quiz_completed"] == true }
        val bothDone = completions.count { it["bb_completed"] == true && it["quiz_completed"] == true }
        val quizScores = completions
            .filter { it["quiz_completed"] == true }
            .mapNotNull { c ->
                val s = (c["quiz_score"] as? Number)?.toInt() ?: return@mapNotNull null
                val t = (c["quiz_total"]  as? Number)?.toInt() ?: return@mapNotNull null
                if (t > 0) s * 100 / t else null
            }
        val avgScore = if (quizScores.isEmpty()) -1 else quizScores.average().toInt()
        statBbDone.text   = "$bbDone\n🏃‍ BB Read"
        statQuizDone.text = if (avgScore >= 0) "$quizDone\n🧠 Quiz\nAvg $avgScore%"
                            else "$quizDone\n🧠 Quiz Done"
        statBothDone.text = "$bothDone\n✅ Both Done"
    }

    private fun showEmpty() {
        emptyState.visibility   = View.VISIBLE
        recyclerView.visibility = View.GONE
    }

    private fun showList() {
        emptyState.visibility   = View.GONE
        recyclerView.visibility = View.VISIBLE
    }

    // ── Adapter ───────────────────────────────────────────────────────────────

    private class CompletionsAdapter(
        private val items:    List<Map<String, Any>>,
        private val taskType: String
    ) : RecyclerView.Adapter<CompletionsAdapter.VH>() {

        private val bbColor   = android.graphics.Color.parseColor("#5C35B5")
        private val quizColor = android.graphics.Color.parseColor("#1967D2")
        private val avatarColors = intArrayOf(
            android.graphics.Color.parseColor("#E91E63"),
            android.graphics.Color.parseColor("#9C27B0"),
            android.graphics.Color.parseColor("#3F51B5"),
            android.graphics.Color.parseColor("#2196F3"),
            android.graphics.Color.parseColor("#009688"),
            android.graphics.Color.parseColor("#FF5722")
        )

        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val avatar:   TextView = v.findViewById(R.id.avatarText)
            val name:     TextView = v.findViewById(R.id.studentName)
            val bbBadge:  TextView = v.findViewById(R.id.bbBadge)
            val quizBadge:TextView = v.findViewById(R.id.quizBadge)
            val score:    TextView = v.findViewById(R.id.quizScore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH =
            VH(LayoutInflater.from(parent.context).inflate(R.layout.item_task_completion, parent, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val item = items[position]
            val name = item["student_name"] as? String ?: (item["user_id"] as? String ?: "Student")
            val bbDone   = item["bb_completed"]   == true
            val quizDone = item["quiz_completed"]  == true
            val qScore   = (item["quiz_score"] as? Number)?.toInt() ?: 0
            val qTotal   = (item["quiz_total"] as? Number)?.toInt() ?: 0

            // Avatar
            holder.avatar.text = name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            holder.avatar.backgroundTintList =
                android.content.res.ColorStateList.valueOf(avatarColors[position % avatarColors.size])

            holder.name.text = name

            // BB badge — shows full read ✓, or partial progress (orange), or hidden
            val showBb  = taskType == "bb_lesson" || taskType == "both"
            val bbSteps = (item["bb_steps_viewed"] as? Number)?.toInt() ?: 0
            val bbTotal = (item["bb_total_steps"]  as? Number)?.toInt() ?: 0
            if (showBb) {
                holder.bbBadge.visibility = View.VISIBLE
                when {
                    bbDone              -> {
                        holder.bbBadge.text = "📚 Read ✓"
                        holder.bbBadge.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(bbColor)
                    }
                    bbSteps > 0 && bbTotal > 0 -> {
                        holder.bbBadge.text = "📖 $bbSteps/$bbTotal steps"
                        holder.bbBadge.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#FF9800"))
                    }
                    else -> holder.bbBadge.visibility = View.GONE
                }
            } else {
                holder.bbBadge.visibility = View.GONE
            }

            // Quiz badge
            val showQuiz = taskType == "quiz" || taskType == "both"
            holder.quizBadge.visibility = if (showQuiz && quizDone) View.VISIBLE else View.GONE
            holder.quizBadge.backgroundTintList = android.content.res.ColorStateList.valueOf(quizColor)

            // Quiz score
            if (showQuiz && quizDone && qTotal > 0) {
                holder.score.visibility = View.VISIBLE
                holder.score.text = "$qScore/$qTotal"
            } else {
                holder.score.visibility = View.GONE
            }

            // Dim if nothing done yet
            holder.itemView.alpha = if (!bbDone && !quizDone) 0.5f else 1.0f
        }
    }
}

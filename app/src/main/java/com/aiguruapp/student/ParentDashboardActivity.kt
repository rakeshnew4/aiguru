package com.aiguruapp.student

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.firestore.StudentStatsManager
import com.aiguruapp.student.models.StudentStats
import com.aiguruapp.student.utils.SessionManager

/**
 * Parent-focused dashboard for monitoring one child's progress.
 * Child identity comes from the current school-join session.
 */
class ParentDashboardActivity : BaseActivity() {

    private var isFirstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_parent_dashboard)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val childName = SessionManager.getStudentName(this).ifBlank { "Child" }
        findViewById<TextView>(R.id.dashboardSubtitle).text = "Tracking $childName"

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.dashboardSwipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary), getColor(R.color.colorSecondary))
        swipeRefresh.setOnRefreshListener {
            loadDashboard()
            swipeRefresh.isRefreshing = false
        }

        findViewById<TextView>(R.id.openTasksLink).setOnClickListener {
            startActivity(Intent(this, TasksActivity::class.java))
        }

        loadDashboard()
    }

    override fun onResume() {
        super.onResume()
        if (!isFirstLoad) loadDashboard()
        isFirstLoad = false
    }

    private fun loadDashboard() {
        val userId = SessionManager.getFirestoreUserId(this)
        if (userId.isBlank() || userId == "guest_user") {
            showEmpty("Child account is not linked yet")
            return
        }

        val hasContent = findViewById<ScrollView>(R.id.chapterScrollView).visibility == View.VISIBLE
        if (!hasContent) findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE

        StudentStatsManager.fetchStudentStats(
            userId = userId,
            onSuccess = { stats -> runOnUiThread { populateFromStats(stats) } },
            onFailure = { runOnUiThread { showEmpty("Couldn't load child progress") } }
        )

        loadTaskSummary(userId)
    }

    private fun loadTaskSummary(userId: String) {
        val schoolId = SessionManager.getSchoolId(this)
        val grade = SessionManager.getGrade(this)
        val section = SessionManager.getSection(this)

        FirestoreManager.loadAllTaskProgress(
            userId = userId,
            onSuccess = { progressMap ->
                FirestoreManager.loadTasksForSchool(
                    schoolId = schoolId,
                    grade = grade,
                    section = section,
                    onSuccess = { tasks ->
                        val totalAssigned = tasks.size
                        val done = tasks.count { task ->
                            val taskId = task["task_id"] as? String ?: task["id"] as? String ?: return@count false
                            val taskType = task["task_type"] as? String ?: "quiz"
                            val progress = progressMap[taskId]
                            val bbDone = progress?.get("bb_completed") == true
                            val quizDone = progress?.get("quiz_completed") == true
                            when (taskType) {
                                "bb_lesson" -> bbDone
                                "quiz" -> quizDone
                                "both" -> bbDone && quizDone
                                else -> bbDone || quizDone
                            }
                        }
                        val pending = (totalAssigned - done).coerceAtLeast(0)
                        runOnUiThread {
                            findViewById<TextView>(R.id.totalAssignedTasks).text = totalAssigned.toString()
                            findViewById<TextView>(R.id.completedTasks).text = done.toString()
                            findViewById<TextView>(R.id.pendingTasks).text = pending.toString()
                        }
                    },
                    onFailure = {
                        runOnUiThread {
                            findViewById<TextView>(R.id.totalAssignedTasks).text = "-"
                            findViewById<TextView>(R.id.completedTasks).text = "-"
                            findViewById<TextView>(R.id.pendingTasks).text = "-"
                        }
                    }
                )
            },
            onFailure = {
                runOnUiThread {
                    findViewById<TextView>(R.id.totalAssignedTasks).text = "-"
                    findViewById<TextView>(R.id.completedTasks).text = "-"
                    findViewById<TextView>(R.id.pendingTasks).text = "-"
                }
            }
        )
    }

    private fun populateFromStats(stats: StudentStats?) {
        if (stats == null || stats.subjects.isEmpty()) {
            showEmpty("No progress yet for this child")
            return
        }

        findViewById<TextView>(R.id.streakDays).text = stats.streakDays.toString()
        val acc = stats.quizAccuracy
        findViewById<TextView>(R.id.avgMasteryScore).text = if (acc >= 0) "$acc%" else "-"
        findViewById<TextView>(R.id.totalTimeText).text = stats.appTimeFormatted

        val container = findViewById<LinearLayout>(R.id.subjectSummaryContainer)
        container.removeAllViews()

        stats.subjects.values
            .sortedBy { it.subjectName }
            .forEach { subject ->
                val row = TextView(this).apply {
                    val subjName = subject.subjectName.ifBlank { "General" }
                    text = "• $subjName  |  🎯 ${subject.masteryScore}%  |  🧠 ${subject.quizzesAnswered} quizzes"
                    textSize = 13f
                    setTextColor(Color.parseColor("#2A2A2A"))
                    setPadding(0, dp(4), 0, dp(4))
                }
                container.addView(row)
            }

        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        findViewById<View>(R.id.emptyLayout).visibility = View.GONE
        findViewById<ScrollView>(R.id.chapterScrollView).visibility = View.VISIBLE
    }

    private fun showEmpty(message: String) {
        runOnUiThread {
            findViewById<View>(R.id.loadingLayout).visibility = View.GONE
            findViewById<ScrollView>(R.id.chapterScrollView).visibility = View.GONE
            findViewById<View>(R.id.emptyLayout).visibility = View.VISIBLE
            findViewById<TextView>(R.id.emptyText).text = message
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

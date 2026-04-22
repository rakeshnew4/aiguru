package com.aiguruapp.student

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager

/**
 * School Admin Panel
 * Shows:
 * - School name & total student count
 * - Active students today
 * - List of teachers with per-teacher student counts
 * - Task assignment stats
 */
class SchoolAdminActivity : BaseActivity() {

    private lateinit var ctx: Context
    private lateinit var act: SchoolAdminActivity

    private lateinit var tvSchoolName: TextView
    private lateinit var tvTotalStudents: TextView
    private lateinit var tvActiveTodayLabel: TextView
    private lateinit var pbLoading: ProgressBar
    private lateinit var rvTeachers: RecyclerView
    private lateinit var teachersAdapter: TeachersAdapter
    private lateinit var btnBack: Button

    private var schoolId: String = ""
    private var schoolName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_school_admin)

        ctx = this
        act = this

        schoolId = SessionManager.getSchoolId(this)
        schoolName = SessionManager.getSchoolName(this)

        tvSchoolName = findViewById(R.id.tvSchoolName)
        tvTotalStudents = findViewById(R.id.tvTotalStudents)
        tvActiveTodayLabel = findViewById(R.id.tvActiveTodayLabel)
        pbLoading = findViewById(R.id.pbLoading)
        rvTeachers = findViewById(R.id.rvTeachers)
        btnBack = findViewById(R.id.btnBack)

        teachersAdapter = TeachersAdapter(emptyList())
        rvTeachers.layoutManager = LinearLayoutManager(ctx)
        rvTeachers.adapter = teachersAdapter

        tvSchoolName.text = schoolName

        btnBack.setOnClickListener { finish() }

        // Load school stats
        loadSchoolStats()
    }

    @SuppressLint("SetTextI18n")
    private fun loadSchoolStats() {
        pbLoading.visibility = android.view.View.VISIBLE

        // Fetch engagement stats and teacher list
        FirestoreManager.getSchoolEngagementToday(
            schoolId = schoolId,
            grade = "",  // Empty = all grades
            onSuccess = { stats ->
                act.runOnUiThread {
                    val totalStudents = stats["totalStudents"] as? Int ?: 0
                    val activeToday = stats["activeToday"] as? Int ?: 0

                    tvTotalStudents.text = "Total: $totalStudents students"
                    tvActiveTodayLabel.text = "Active today: $activeToday"

                    // Load teachers + per-teacher metrics
                    loadTeacherMetrics()
                }
            },
            onFailure = { ex ->
                act.runOnUiThread {
                    pbLoading.visibility = android.view.View.GONE
                    showError("Failed to load school stats: ${ex?.message ?: "unknown"}")
                }
            }
        )
    }

    private fun loadTeacherMetrics() {
        FirestoreManager.loadSchoolTeacherMetrics(
            schoolId = schoolId,
            onSuccess = { teachers ->
                act.runOnUiThread {
                    pbLoading.visibility = android.view.View.GONE
                    teachersAdapter.updateData(teachers)
                }
            },
            onFailure = { ex ->
                act.runOnUiThread {
                    pbLoading.visibility = android.view.View.GONE
                    showError("Failed to load teachers: ${ex?.message ?: "unknown"}")
                }
            }
        )
    }

    private fun showError(msg: String) {
        android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_LONG).show()
    }

    /**
     * Simple adapter for teacher list
     */
    private inner class TeachersAdapter(
        private var teachers: List<Map<String, Any>>
    ) : RecyclerView.Adapter<TeachersAdapter.ViewHolder>() {

        fun updateData(newTeachers: List<Map<String, Any>>) {
            teachers = newTeachers
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            val view = android.widget.LinearLayout(parent.context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(16, 12, 16, 12)
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            return ViewHolder(view as android.widget.LinearLayout)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val teacher = teachers[position]
            val username = teacher["username"] as? String ?: "Unknown"
            val teacherId = teacher["teacher_id"] as? String ?: ""
            val studentCount = teacher["studentCount"] as? Int ?: 0
            val taskCount = teacher["taskCount"] as? Int ?: 0
            val completionRate = teacher["completionRate"] as? Int ?: 0
            val engagedStudents = teacher["engagedStudents"] as? Int ?: 0

            holder.bind(username, teacherId, studentCount, taskCount, completionRate, engagedStudents)
        }

        override fun getItemCount(): Int = teachers.size

        private inner class ViewHolder(private val layout: android.widget.LinearLayout) :
            RecyclerView.ViewHolder(layout) {

            fun bind(
                username: String,
                teacherId: String,
                studentCount: Int,
                taskCount: Int,
                completionRate: Int,
                engagedStudents: Int
            ) {
                layout.removeAllViews()

                val tvName = TextView(ctx).apply {
                    text = if (teacherId.isBlank()) username else "$username ($teacherId)"
                    textSize = 16f
                    setTextColor(android.graphics.Color.BLACK)
                }

                val tvLine1 = TextView(ctx).apply {
                    text = "Students: $studentCount | Tasks: $taskCount"
                    textSize = 13f
                    setTextColor(android.graphics.Color.DKGRAY)
                }

                val tvLine2 = TextView(ctx).apply {
                    text = "Engaged: $engagedStudents | Completion: $completionRate%"
                    textSize = 13f
                    setTextColor(android.graphics.Color.parseColor("#1B5E20"))
                }

                layout.addView(tvName)
                layout.addView(tvLine1)
                layout.addView(tvLine2)
            }
        }
    }
}

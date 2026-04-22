package com.aiguruapp.student

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.firestore.StudentStatsManager
import com.aiguruapp.student.models.School
import com.aiguruapp.student.models.StudentStats
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import java.util.concurrent.TimeUnit

class TeacherDashboardActivity : BaseActivity() {

    private lateinit var schoolCodeInput: MaterialAutoCompleteTextView
    private lateinit var gradeInput: MaterialAutoCompleteTextView
    private lateinit var loadingLayout: View
    private lateinit var emptyLayout: View
    private lateinit var emptyText: TextView
    private lateinit var studentListContainer: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout

    private var selectedSchool: School? = null
    private val grades = listOf("All", "6", "7", "8", "9", "10", "11", "12")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!com.aiguruapp.student.config.AccessGate.requireAccess(this, com.aiguruapp.student.config.AccessGate.Feature.TEACHER_DASHBOARD)) return
        setContentView(R.layout.activity_teacher_dashboard)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        schoolCodeInput    = findViewById(R.id.schoolCodeInput)
        gradeInput         = findViewById(R.id.gradeInput)
        loadingLayout      = findViewById(R.id.loadingLayout)
        emptyLayout        = findViewById(R.id.emptyLayout)
        emptyText          = findViewById(R.id.emptyText)
        studentListContainer = findViewById(R.id.studentListContainer)
        swipeRefresh       = findViewById(R.id.swipeRefresh)

        setupDropdowns()

        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary))
        swipeRefresh.setOnRefreshListener { loadClass(); swipeRefresh.isRefreshing = false }

        findViewById<MaterialButton>(R.id.loadClassButton).setOnClickListener { loadClass() }

        // Teacher action shortcuts
        findViewById<MaterialButton>(R.id.btnTeacherChat).setOnClickListener {
            startActivity(Intent(this, TeacherChatHostActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnChatReview).setOnClickListener {
            startActivity(Intent(this, TeacherChatReviewActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnAssignTask).setOnClickListener {
            startActivity(Intent(this, TeacherTasksActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnSavedContent).setOnClickListener {
            startActivity(Intent(this, TeacherSavedContentActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.btnSchoolAdmin).setOnClickListener {
            startActivity(Intent(this, SchoolAdminActivity::class.java))
        }

        // Load attendance summary
        loadAttendanceSummary()
    }

    private fun setupDropdowns() {
        val schools = ConfigManager.getSchools(this)
        val schoolNames = schools.map { it.name }
        val schoolAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, schoolNames)
        schoolCodeInput.setAdapter(schoolAdapter)
        schoolCodeInput.setOnItemClickListener { _, _, pos, _ ->
            selectedSchool = schools[pos]
        }

        // Pre-select from session
        val sessionSchoolId = SessionManager.getSchoolId(this)
        if (sessionSchoolId.isNotBlank()) {
            selectedSchool = schools.firstOrNull { it.id == sessionSchoolId }
            selectedSchool?.let { schoolCodeInput.setText(it.name, false) }
        }

        val gradeAdapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, grades)
        gradeInput.setAdapter(gradeAdapter)
        gradeInput.setOnItemClickListener { _, _, _, _ ->
            loadClass()   // auto-load when grade selected
        }

        val sessionGrade = SessionManager.getGrade(this)
        if (sessionGrade.isNotBlank()) gradeInput.setText(sessionGrade, false)
    }

    private fun loadClass() {
        val school = selectedSchool
        if (school == null) {
            Toast.makeText(this, "Please select a school first", Toast.LENGTH_SHORT).show()
            return
        }
        val grade = gradeInput.text?.toString()?.trim() ?: ""
        val gradeParam = if (grade == "All" || grade.isBlank()) "" else grade

        showLoading()
        StudentStatsManager.fetchSchoolStats(
            schoolId  = school.id,
            grade     = gradeParam,
            onSuccess = { students -> runOnUiThread { displayStudents(students) } },
            onFailure = { e ->
                runOnUiThread {
                    showEmpty("Failed to load data. Check connection.")
                    Toast.makeText(this, "Error: ${e?.message ?: "unknown"}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun displayStudents(students: List<StudentStats>) {
        loadingLayout.visibility = View.GONE
        studentListContainer.removeAllViews()

        if (students.isEmpty()) {
            showEmpty("No students found for this school/grade.")
            return
        }

        emptyLayout.visibility = View.GONE
        swipeRefresh.visibility = View.VISIBLE

        // Summary header
        val header = buildSummaryHeader(students)
        studentListContainer.addView(header)

        students.forEach { s ->
            studentListContainer.addView(buildStudentCard(s))
        }
    }

    private fun buildSummaryHeader(students: List<StudentStats>): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(16), dp(12), dp(16), dp(4))
            layoutParams = lp
            radius = dp(14).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.parseColor("#EDE7F6"))
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }

        val avgAccuracy = students.map { it.quizAccuracy }.filter { it >= 0 }.let { list ->
            if (list.isEmpty()) -1 else list.average().toInt()
        }
        val avgStreak = students.map { it.streakDays }.average().toInt()
        val activeCount = students.count { it.totalMessages > 0 || it.totalQuizzesAnswered > 0 }

        val text = TextView(this).apply {
            text = "👨‍🎓 ${students.size} students  •  " +
                   "⚡ ${activeCount} active  •  " +
                   (if (avgAccuracy >= 0) "🎯 Avg accuracy $avgAccuracy%  •  " else "") +
                   "🔥 Avg streak ${avgStreak}d"
            textSize = 13f
            setTextColor(Color.parseColor("#4527A0"))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        }
        inner.addView(text)
        card.addView(inner)
        return card
    }

    private fun buildStudentCard(s: StudentStats): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(16), dp(4), dp(16), dp(4))
            layoutParams = lp
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
        }

        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        // ── Header row ──────────────────────────────────────────────────────────
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }

        val nameText = TextView(this).apply {
            text = "👤 ${s.displayName.ifBlank { s.userId.take(12) }}"
            textSize = 15f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#212121"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val acc = s.quizAccuracy
        val accText = TextView(this).apply {
            text = if (acc >= 0) "🎯 $acc%" else "🎯 —"
            textSize = 13f
            setTextColor(when {
                acc >= 75 -> Color.parseColor("#2E7D32")
                acc >= 50 -> Color.parseColor("#E65100")
                acc >= 0  -> Color.parseColor("#B71C1C")
                else      -> Color.parseColor("#9E9E9E")
            })
        }

        headerRow.addView(nameText)
        headerRow.addView(accText)

        // ── Stats row ────────────────────────────────────────────────────────────
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(14), 0, dp(14), dp(10))
            layoutParams = lp
        }
        val statsText = TextView(this).apply {
            text = "🔥 ${s.streakDays}d  •  " +
                   "💬 ${s.totalMessages} msgs  •  " +
                   "🖥 ${s.totalBbSessions} BB  •  " +
                   "⏱ ${s.appTimeFormatted}"
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
        }
        statsRow.addView(statsText)

        // ── Expandable subject breakdown ──────────────────────────────────────────
        val subjectSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(14), 0, dp(14), dp(10))
            layoutParams = lp
        }

        if (s.subjects.isNotEmpty()) {
            val divider = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
                setBackgroundColor(Color.parseColor("#E0E0E0"))
            }
            subjectSection.addView(divider)

            s.subjects.values.sortedByDescending { it.masteryScore }.forEach { subj ->
                subjectSection.addView(buildSubjectRow(subj.subjectName.ifBlank{"General"}, subj.masteryScore, subj.quizAccuracy))
                subj.chapters.values.sortedByDescending { it.masteryScore }.forEach { ch ->
                    subjectSection.addView(buildChapterRow(ch.chapterName.ifBlank{"Chapter"}, ch.masteryScore))
                }
            }

            card.setOnClickListener {
                subjectSection.visibility =
                    if (subjectSection.visibility == View.GONE) View.VISIBLE else View.GONE
            }
        }

        outer.addView(headerRow)
        outer.addView(statsRow)
        outer.addView(subjectSection)
        card.addView(outer)
        return card
    }

    private fun buildSubjectRow(name: String, mastery: Int, accuracy: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        val nameV = TextView(this).apply {
            text = "📚 $name"
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#1565C0"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val scoreV = TextView(this).apply {
            text = "${mastery}%" + if (accuracy >= 0) " (${accuracy}% acc)" else ""
            textSize = 12f
            setTextColor(masteryColor(mastery))
        }
        row.addView(nameV)
        row.addView(scoreV)
        return row
    }

    private fun buildChapterRow(name: String, mastery: Int): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(3)
            lp.marginStart = dp(16)
            layoutParams = lp
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = mastery
            layoutParams = LinearLayout.LayoutParams(0, dp(5), 1f)
            val tint = masteryColor(mastery)
            progressTintList = android.content.res.ColorStateList.valueOf(tint)
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
        }
        val label = TextView(this).apply {
            text = "  $name  ${mastery}%"
            textSize = 11f
            setTextColor(Color.parseColor("#555555"))
        }
        row.addView(bar)
        row.addView(label)
        return row
    }

    private fun masteryColor(mastery: Int) = when {
        mastery >= 75 -> Color.parseColor("#2E7D32")
        mastery >= 40 -> Color.parseColor("#E65100")
        else          -> Color.parseColor("#B71C1C")
    }

    private fun loadAttendanceSummary() {
        val schoolId = SessionManager.getSchoolId(this)
        if (schoolId.isBlank()) return

        val tvAttendance = findViewById<TextView>(R.id.tvAttendanceSummary)
        tvAttendance.text = "Loading..."

        FirestoreManager.getSchoolEngagementToday(
            schoolId = schoolId,
            grade = "",  // All grades
            onSuccess = { stats ->
                runOnUiThread {
                    val activeToday = stats["activeToday"] as? Int ?: 0
                    val totalStudents = stats["totalStudents"] as? Int ?: 0
                    tvAttendance.text = "$activeToday of $totalStudents students active today"
                }
            },
            onFailure = { _ ->
                runOnUiThread {
                    tvAttendance.text = "Unable to load"
                }
            }
        )
    }

    private fun showLoading() {
        loadingLayout.visibility = View.VISIBLE
        emptyLayout.visibility   = View.GONE
        swipeRefresh.visibility  = View.GONE
    }

    private fun showEmpty(msg: String = "No students found") {
        loadingLayout.visibility = View.GONE
        emptyLayout.visibility   = View.VISIBLE
        swipeRefresh.visibility  = View.GONE
        emptyText.text           = msg
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}

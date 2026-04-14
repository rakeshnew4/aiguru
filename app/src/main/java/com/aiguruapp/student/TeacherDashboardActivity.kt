package com.aiguruapp.student

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aiguruapp.student.firestore.StudentStatsManager
import com.aiguruapp.student.models.StudentStats
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import java.util.concurrent.TimeUnit

class TeacherDashboardActivity : BaseActivity() {

    private lateinit var schoolCodeInput: TextInputEditText
    private lateinit var gradeInput: TextInputEditText
    private lateinit var loadingLayout: View
    private lateinit var emptyLayout: View
    private lateinit var emptyText: TextView
    private lateinit var studentListContainer: LinearLayout
    private lateinit var swipeRefresh: SwipeRefreshLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_dashboard)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        schoolCodeInput    = findViewById(R.id.schoolCodeInput)
        gradeInput         = findViewById(R.id.gradeInput)
        loadingLayout      = findViewById(R.id.loadingLayout)
        emptyLayout        = findViewById(R.id.emptyLayout)
        emptyText          = findViewById(R.id.emptyText)
        studentListContainer = findViewById(R.id.studentListContainer)
        swipeRefresh       = findViewById(R.id.swipeRefresh)

        // Pre-fill from session if available
        val sessionSchool = SessionManager.getSchoolId(this)
        val sessionGrade  = SessionManager.getGrade(this)
        if (sessionSchool.isNotBlank()) schoolCodeInput.setText(sessionSchool)
        if (sessionGrade.isNotBlank())  gradeInput.setText(sessionGrade)

        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary))
        swipeRefresh.setOnRefreshListener { loadClass(); swipeRefresh.isRefreshing = false }

        findViewById<MaterialButton>(R.id.loadClassButton).setOnClickListener { loadClass() }
    }

    private fun loadClass() {
        val schoolCode = schoolCodeInput.text?.toString()?.trim() ?: ""
        val grade      = gradeInput.text?.toString()?.trim() ?: ""

        if (schoolCode.isBlank()) {
            schoolCodeInput.error = "Enter school code"
            return
        }

        // Hide keyboard
        currentFocus?.let {
            (getSystemService(INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.hideSoftInputFromWindow(it.windowToken, 0)
        }

        showLoading()
        StudentStatsManager.fetchSchoolStats(
            schoolId  = schoolCode,
            grade     = grade,
            onSuccess = { students -> runOnUiThread { displayStudents(students) } },
            onFailure = { e ->
                runOnUiThread {
                    showEmpty("Failed to load data. Check school code and connection.")
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

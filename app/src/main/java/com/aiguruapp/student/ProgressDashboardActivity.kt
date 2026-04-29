package com.aiguruapp.student

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.aiguruapp.student.firestore.StudentStatsManager
import com.aiguruapp.student.models.StudentStats
import com.aiguruapp.student.utils.SessionManager
import java.util.concurrent.TimeUnit

class ProgressDashboardActivity : BaseActivity() {

    private var isFirstLoad = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress_dashboard)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val studentName = SessionManager.getStudentName(this)
        findViewById<TextView>(R.id.dashboardSubtitle).text = "$studentName's learning journey"

        loadProgress()

        val swipeRefresh = findViewById<SwipeRefreshLayout>(R.id.dashboardSwipeRefresh)
        swipeRefresh.setColorSchemeColors(getColor(R.color.colorPrimary), getColor(R.color.colorSecondary))
        swipeRefresh.setOnRefreshListener {
            loadProgress()
            swipeRefresh.isRefreshing = false
        }
    }

    override fun onResume() {
        super.onResume()
        // Reload stats on every resume so data is fresh after chatting/BB sessions
        if (!isFirstLoad) loadProgress()
        isFirstLoad = false
    }

    private fun loadProgress() {
        val userId = SessionManager.getFirestoreUserId(this)
        if (userId.isBlank() || SessionManager.isGuestMode(this)) { showEmpty(isGuest = true); return }
        // Don't show the loading spinner on background refreshes (already has content)
        val hasContent = findViewById<ScrollView>(R.id.chapterScrollView).visibility == View.VISIBLE
        if (!hasContent) findViewById<View>(R.id.loadingLayout).visibility = View.VISIBLE
        StudentStatsManager.fetchStudentStats(
            userId    = userId,
            onSuccess = { stats -> runOnUiThread { populateFromStats(stats) } },
            onFailure = { runOnUiThread { showEmpty(isGuest = false) } }
        )
    }

    private fun populateFromStats(stats: StudentStats?) {
        if (stats == null) { showEmpty(isGuest = false); return }

        // ── Top stat cards (always shown when doc exists) ───────────────────────
        findViewById<TextView>(R.id.totalChaptersCount).text = stats.streakDays.toString()
        val acc = stats.quizAccuracy
        findViewById<TextView>(R.id.avgMasteryScore).text = if (acc >= 0) "$acc%" else "—"
        findViewById<TextView>(R.id.totalTimeText).text = stats.appTimeFormatted

        if (stats.subjects.isEmpty()) {
            // Document exists but no per-subject tracking yet — show encouraging message
            showEmpty(isGuest = false, hasActivity = stats.totalMessages > 0 || stats.totalBbSessions > 0)
            return
        }

        // ── Flatten subjects → chapters → ChapterSummary ────────────────────────
        val summaries = stats.subjects.values.flatMap { subj ->
            val subjDisplay = subj.subjectName.ifBlank { "General" }
            subj.chapters.values.map { ch ->
                ChapterSummary(
                    subject          = subjDisplay,
                    chapter          = ch.chapterName.ifBlank { "Chapter" },
                    masteryScore     = ch.masteryScore,
                    totalTimeSeconds = (ch.appTimeMs / 1_000L).toInt(),
                    quizAttempts     = ch.quizzesAnswered,
                    lastAccessed     = ch.lastActiveAt
                )
            }
        }.sortedByDescending { it.lastAccessed }

        displayData(summaries)
    }

    private fun displayData(data: List<ChapterSummary>) {
        if (data.isEmpty()) { showEmpty(isGuest = false); return }

        // Group by subject, sort mastery desc within each group
        val grouped = data.groupBy { it.subject }.toSortedMap()

        val container = findViewById<LinearLayout>(R.id.chapterListContainer)
        container.removeAllViews()

        grouped.forEach { (subjectName, chapters) ->
            // Subject header
            val subjectHeader = TextView(this).apply {
                text = "📚 $subjectName"
                textSize = 15f
                setTextColor(Color.parseColor("#1565C0"))
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.setMargins(dp(16), dp(12), dp(16), dp(4))
                layoutParams = lp
            }
            container.addView(subjectHeader)

            chapters.sortedByDescending { it.masteryScore }.forEach { cs ->
                container.addView(buildChapterCard(cs))
            }
        }

        // Build weekly sparkline
        val sparklineCard = findViewById<View>(R.id.weeklySparklineCard)
        val sparklineContainer = findViewById<LinearLayout>(R.id.weeklySparklineContainer)
        if (sparklineCard != null && sparklineContainer != null) {
            buildWeeklySparkline(sparklineContainer, data)
            sparklineCard.visibility = View.VISIBLE
        }

        // Show list, hide empty/loading states
        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        findViewById<LinearLayout>(R.id.emptyLayout).visibility = View.GONE
        val scroll = findViewById<ScrollView>(R.id.chapterScrollView)
        scroll.visibility = View.VISIBLE
    }

    private fun buildWeeklySparkline(container: LinearLayout, data: List<ChapterSummary>) {
        container.removeAllViews()
        val now = System.currentTimeMillis()
        val dayMs = 86_400_000L
        val cal = java.util.Calendar.getInstance()

        val counts = IntArray(7)
        for (cs in data) {
            if (cs.lastAccessed <= 0L) continue
            val daysAgo = ((now - cs.lastAccessed) / dayMs).toInt()
            if (daysAgo in 0..6) counts[6 - daysAgo]++
        }

        val labels = Array(7) { i ->
            cal.timeInMillis = now - (6 - i) * dayMs
            when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
                java.util.Calendar.MONDAY    -> "Mon"
                java.util.Calendar.TUESDAY   -> "Tue"
                java.util.Calendar.WEDNESDAY -> "Wed"
                java.util.Calendar.THURSDAY  -> "Thu"
                java.util.Calendar.FRIDAY    -> "Fri"
                java.util.Calendar.SATURDAY  -> "Sat"
                else                         -> "Sun"
            }
        }

        val maxCount = counts.max().coerceAtLeast(1)
        for (i in 0..6) {
            val col = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f)
            }
            val barHeightDp = ((counts[i].toFloat() / maxCount) * 38).toInt().coerceAtLeast(if (counts[i] > 0) 6 else 2)
            col.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(16), dp(barHeightDp)).also { it.bottomMargin = dp(2) }
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                    cornerRadius = dp(3).toFloat()
                    setColor(Color.parseColor(if (counts[i] > 0) "#1565C0" else "#E3F2FD"))
                }
            })
            col.addView(TextView(this).apply {
                text = labels[i]
                textSize = 9f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#999999"))
            })
            container.addView(col)
        }
    }

    private fun buildChapterCard(cs: ChapterSummary): View {
        val card = com.google.android.material.card.MaterialCardView(this).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.setMargins(dp(16), dp(4), dp(16), dp(4))
            layoutParams = lp
            radius = dp(12).toFloat()
            cardElevation = dp(2).toFloat()
            setCardBackgroundColor(Color.WHITE)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(
                    Intent(this@ProgressDashboardActivity, ChatHostActivity::class.java)
                        .putExtra("subjectName", cs.subject)
                        .putExtra("chapterName", cs.chapter)
                )
            }
        }

        val inner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(12)
            setPadding(pad, pad, pad, pad)
        }

        // Chapter name + mastery badge row
        val row1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val chapterNameView = TextView(this).apply {
            text = cs.chapter
            textSize = 14f
            setTextColor(Color.parseColor("#212121"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val masteryBadge = TextView(this).apply {
            text = "${cs.masteryScore}%"
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            val color = when {
                cs.masteryScore >= 75 -> "#2E7D32"
                cs.masteryScore >= 40 -> "#E65100"
                else -> "#B71C1C"
            }
            setTextColor(Color.parseColor(color))
        }
        row1.addView(chapterNameView)
        row1.addView(masteryBadge)

        // Progress bar
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = cs.masteryScore
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6))
            lp.topMargin = dp(6)
            layoutParams = lp
            val tint = when {
                cs.masteryScore >= 75 -> "#2E7D32"
                cs.masteryScore >= 40 -> "#616161"
                else -> "#E53935"
            }
            progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor(tint))
            progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E3F2FD"))
        }

        // Stats row
        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(6)
            layoutParams = lp
        }
        val statsText = TextView(this).apply {
            val timeFmt = formatTime(cs.totalTimeSeconds)
            text = "⏱ $timeFmt  •  🎯 ${cs.quizAttempts} quiz${if (cs.quizAttempts != 1) "zes" else ""}"
            textSize = 11f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        statsRow.addView(statsText)

        inner.addView(row1)
        inner.addView(progressBar)
        inner.addView(statsRow)
        card.addView(inner)
        return card
    }

    private fun showEmpty(isGuest: Boolean = false, hasActivity: Boolean = false) {
        runOnUiThread {
            findViewById<View>(R.id.loadingLayout).visibility = View.GONE
            val emptyLayout = findViewById<LinearLayout>(R.id.emptyLayout)
            emptyLayout.visibility = View.VISIBLE
            val msg = emptyLayout.getChildAt(0) as? TextView
            msg?.text = when {
                isGuest -> "Sign in to track your progress 📊\n\nYour streaks, quiz scores and study time\nwill appear here after you create an account."
                hasActivity -> "Great start! 🎉\n\nWe're tracking your activity. Your detailed chapter progress will appear here after your next chat or lesson."
                else -> "No progress yet 📚\n\nStart chatting with AI or try a blackboard lesson.\nYour learning data will appear here!"
            }
        }
    }

    private fun formatTime(seconds: Int): String {
        val hours = TimeUnit.SECONDS.toHours(seconds.toLong())
        val mins = TimeUnit.SECONDS.toMinutes(seconds.toLong()) % 60
        return when {
            hours > 0 -> "${hours}h ${mins}m"
            mins > 0 -> "${mins}m"
            else -> "<1m"
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    data class ChapterSummary(
        val subject: String,
        val chapter: String,
        val masteryScore: Int,
        val totalTimeSeconds: Int,
        val quizAttempts: Int,
        val lastAccessed: Long
    )
}

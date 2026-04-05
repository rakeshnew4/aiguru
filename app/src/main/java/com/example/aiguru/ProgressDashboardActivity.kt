package com.example.aiguru

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
import com.example.aiguru.utils.SessionManager
import java.util.concurrent.TimeUnit

class ProgressDashboardActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_progress_dashboard)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val studentName = SessionManager.getStudentName(this)
        findViewById<TextView>(R.id.dashboardSubtitle).text = "$studentName's learning journey"

        loadProgress()
    }

    private fun loadProgress() {
        // Progress data requires Firestore — will be re-enabled later
        showEmpty()
    }

    private fun displayData(data: List<ChapterSummary>) {
        if (data.isEmpty()) { showEmpty(); return }

        // Update summary stats
        val totalTime = data.sumOf { it.totalTimeSeconds }
        val avgMastery = data.map { it.masteryScore }.average().toInt()
        findViewById<TextView>(R.id.totalChaptersCount).text = data.size.toString()
        findViewById<TextView>(R.id.avgMasteryScore).text = "$avgMastery%"
        findViewById<TextView>(R.id.totalTimeText).text = formatTime(totalTime)

        // Group by subject, sort mastery desc within each group
        val grouped = data.groupBy { it.subject }
            .toSortedMap()

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

        // Show list
        findViewById<View>(R.id.loadingLayout).visibility = View.GONE
        val scroll = findViewById<ScrollView>(R.id.chapterScrollView)
        scroll.visibility = View.VISIBLE
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
                    Intent(this@ProgressDashboardActivity, ChatActivity::class.java)
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

    private fun showEmpty() {
        runOnUiThread {
            findViewById<View>(R.id.loadingLayout).visibility = View.GONE
            findViewById<View>(R.id.emptyLayout).visibility = View.VISIBLE
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

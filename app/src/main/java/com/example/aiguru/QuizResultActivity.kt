package com.example.aiguru

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.core.content.ContextCompat
import com.example.aiguru.quiz.DonutChartView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONArray

class QuizResultActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_result)

        val correctCount  = intent.getIntExtra("correctCount", 0)
        val totalCount    = intent.getIntExtra("totalCount", 1)
        val scorePercent  = intent.getIntExtra("scorePercent", 0)
        val timeTakenSec  = intent.getLongExtra("timeTakenSec", 0L)
        val difficulty    = intent.getStringExtra("difficulty") ?: "medium"
        val chapterTitle  = intent.getStringExtra("chapterTitle") ?: ""
        val quizJson      = intent.getStringExtra("quizJson") ?: ""
        val answersJson   = intent.getStringExtra("answersJson") ?: "[]"

        // ── Header subtitle
        findViewById<TextView>(R.id.resultSubtitle).text =
            "$chapterTitle · ${difficulty.replaceFirstChar { it.uppercase() }}"

        // ── Donut chart
        val donut = findViewById<DonutChartView>(R.id.donutChart)
        donut.setScore(correctCount, totalCount)

        // ── Stats
        findViewById<TextView>(R.id.correctCountText).text = correctCount.toString()
        findViewById<TextView>(R.id.wrongCountText).text   = (totalCount - correctCount).toString()
        val minutes = timeTakenSec / 60
        val seconds = timeTakenSec % 60
        findViewById<TextView>(R.id.timeText).text =
            if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"

        // ── Encouragement message
        val encouragementCard = findViewById<com.google.android.material.card.MaterialCardView>(R.id.encouragementCard)
        val encouragementText = findViewById<TextView>(R.id.encouragementText)
        val (cardColor, message) = when {
            scorePercent >= 90 -> Pair(R.color.colorSuccessLight,
                "🌟 Excellent! Score: $scorePercent% — Outstanding performance! You've mastered this chapter.")
            scorePercent >= 70 -> Pair(R.color.colorSuccessLight,
                "👍 Good job! Score: $scorePercent% — Solid understanding. Review the incorrect ones to improve further.")
            scorePercent >= 50 -> Pair(R.color.colorWarningLight,
                "📚 Score: $scorePercent% — Keep practicing! Focus on the topics you missed.")
            else -> Pair(R.color.colorErrorLight,
                "💪 Score: $scorePercent% — Don't give up! Revisit the chapter and try again.")
        }
        encouragementCard.setCardBackgroundColor(ContextCompat.getColor(this, cardColor))
        encouragementText.text = message

        // ── Q&A breakdown
        buildBreakdown(quizJson, answersJson)

        // ── Buttons
        findViewById<MaterialButton>(R.id.homeBtn).setOnClickListener {
            startActivity(Intent(this, HomeActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
        }
        findViewById<MaterialButton>(R.id.retryBtn).setOnClickListener {
            // Go back to setup screen — finish the whole quiz stack
            finishAffinity()
        }
    }

    // ── Build per-question breakdown ──────────────────────────────────────────

    private fun buildBreakdown(quizJson: String, answersJson: String) {
        val container = findViewById<LinearLayout>(R.id.breakdownContainer)
        container.removeAllViews()

        if (quizJson.isBlank() || answersJson.isBlank()) return

        try {
            val quizObj   = org.json.JSONObject(quizJson)
            val questions = quizObj.getJSONArray("questions")
            val answersArr = JSONArray(answersJson)

            // Build answer map
            val answerMap = mutableMapOf<String, org.json.JSONObject>()
            for (i in 0 until answersArr.length()) {
                val a = answersArr.getJSONObject(i)
                answerMap[a.getString("questionId")] = a
            }

            for (i in 0 until questions.length()) {
                val q       = questions.getJSONObject(i)
                val qId     = q.optString("id", "")
                val answer  = answerMap[qId]
                val isCorrect = answer?.optBoolean("isCorrect", false) ?: false

                val card = buildQuestionCard(
                    number    = i + 1,
                    question  = q.optString("question", ""),
                    qType     = q.optString("type", "mcq"),
                    userAns   = answer?.optString("userAnswer", "—") ?: "—",
                    correctAns = extractCorrectAnswer(q),
                    explanation = q.optString("explanation", ""),
                    isCorrect = isCorrect
                )
                container.addView(card)
            }
        } catch (e: Exception) {
            val tv = TextView(this)
            tv.text = "Could not load breakdown."
            container.addView(tv)
        }
    }

    private fun extractCorrectAnswer(q: org.json.JSONObject): String = when (q.optString("type")) {
        "mcq"             -> q.optString("correct_answer", "")
        "fill_blank_typed" -> {
            val arr = q.optJSONArray("correct_answers")
            (0 until (arr?.length() ?: 0)).joinToString(", ") { arr!!.getString(it) }
        }
        "short_answer"     -> q.optString("sample_answer", "").take(120)
        else               -> ""
    }

    private fun buildQuestionCard(
        number: Int,
        question: String,
        qType: String,
        userAns: String,
        correctAns: String,
        explanation: String,
        isCorrect: Boolean
    ): MaterialCardView {
        val ctx = this
        val card = MaterialCardView(ctx).apply {
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.bottomMargin = (12 * resources.displayMetrics.density).toInt()
            layoutParams     = lp
            radius           = (12 * resources.displayMetrics.density)
            cardElevation    = (1 * resources.displayMetrics.density)
            setCardBackgroundColor(
                ContextCompat.getColor(ctx, if (isCorrect) R.color.colorSuccessLight else R.color.colorErrorLight)
            )
        }

        val inner = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt(),
                (16 * resources.displayMetrics.density).toInt(),
                (14 * resources.displayMetrics.density).toInt()
            )
        }

        // Status row
        val statusRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
        }
        val statusIcon = TextView(ctx).apply {
            text = if (isCorrect) "✓" else "✗"
            textSize = 16f
            setTextColor(ContextCompat.getColor(ctx, if (isCorrect) R.color.colorSuccess else R.color.colorError))
        }
        val qNumber = TextView(ctx).apply {
            text = "  Q$number  ·  ${typeName(qType)}"
            textSize = 12f
            setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary))
        }
        statusRow.addView(statusIcon)
        statusRow.addView(qNumber)
        inner.addView(statusRow)

        // Question
        val qText = TextView(ctx).apply {
            text = question
            textSize = 14f
            setTextColor(ContextCompat.getColor(ctx, R.color.colorTextPrimary))
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        inner.addView(qText)

        // Your answer
        val yourAns = TextView(ctx).apply {
            text = "Your answer: $userAns"
            textSize = 13f
            setTextColor(
                ContextCompat.getColor(ctx, if (isCorrect) R.color.colorSuccess else R.color.colorError)
            )
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = (8 * resources.displayMetrics.density).toInt()
            layoutParams = lp
        }
        inner.addView(yourAns)

        // Correct answer (if wrong)
        if (!isCorrect && correctAns.isNotBlank()) {
            val corrAns = TextView(ctx).apply {
                text = "Correct: $correctAns"
                textSize = 13f
                setTextColor(ContextCompat.getColor(ctx, R.color.colorSuccess))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (4 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            inner.addView(corrAns)
        }

        // Explanation
        if (explanation.isNotBlank()) {
            val expText = TextView(ctx).apply {
                text = "💡 $explanation"
                textSize = 12f
                setTextColor(ContextCompat.getColor(ctx, R.color.colorTextSecondary))
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.topMargin = (6 * resources.displayMetrics.density).toInt()
                layoutParams = lp
            }
            inner.addView(expText)
        }

        card.addView(inner)
        return card
    }

    private fun typeName(type: String) = when (type) {
        "mcq"             -> "MCQ"
        "fill_blank_typed" -> "Fill Blank"
        "short_answer"     -> "Short Answer"
        else               -> type
    }
}

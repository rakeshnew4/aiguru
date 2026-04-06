package com.example.aiguru

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.aiguru.models.Quiz
import com.example.aiguru.models.QuizAnswer
import com.example.aiguru.models.QuizQuestion
import com.example.aiguru.models.QuizResult
import com.example.aiguru.quiz.QuizApiClient
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class QuizActivity : BaseActivity() {

    private lateinit var quiz: Quiz
    private val answers = mutableListOf<QuizAnswer>()
    private var currentIndex = 0
    private var startTimeMs = 0L
    private var questionAnswered = false

    // Views
    private lateinit var questionCounter: TextView
    private lateinit var difficultyBadge: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var questionTypeBadge: TextView
    private lateinit var questionText: TextView

    // MCQ
    private lateinit var mcqContainer: LinearLayout
    private val mcqCards = arrayOfNulls<MaterialCardView>(4)
    private val mcqTexts = arrayOfNulls<TextView>(4)

    // Fill Blank
    private lateinit var fillBlankContainer: LinearLayout
    private lateinit var fillInput1: TextInputEditText
    private lateinit var fillInput2: TextInputEditText
    private lateinit var fillInput2Layout: TextInputLayout

    // Short Answer
    private lateinit var shortAnswerContainer: LinearLayout
    private lateinit var shortAnswerInput: TextInputEditText
    private lateinit var keywordsHint: TextView

    // Explanation
    private lateinit var explanationCard: MaterialCardView
    private lateinit var explanationLabel: TextView
    private lateinit var explanationText: TextView

    // Bottom bar
    private lateinit var evalSpinner: ProgressBar
    private lateinit var submitAnswerBtn: MaterialButton
    private lateinit var nextBtn: MaterialButton

    private val apiClient = QuizApiClient()

    // ── Colour helpers ─────────────────────────────────────────────────────────
    private val colorSuccess get() = getColor(R.color.colorSuccess)
    private val colorError   get() = getColor(R.color.colorError)
    private val colorSurface get() = getColor(R.color.colorSurface)
    private val colorSuccessLight get() = getColor(R.color.colorSuccessLight)
    private val colorErrorLight   get() = getColor(R.color.colorErrorLight)

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz)

        val quizJson = intent.getStringExtra("quizJson")
            ?: run { finish(); return }
        quiz = Quiz.fromJson(JSONObject(quizJson))

        startTimeMs = System.currentTimeMillis()
        bindViews()
        showQuestion(0)
    }

    // ── View binding ───────────────────────────────────────────────────────────

    private fun bindViews() {
        questionCounter   = findViewById(R.id.questionCounter)
        difficultyBadge   = findViewById(R.id.difficultyBadge)
        progressBar       = findViewById(R.id.quizProgressBar)
        questionTypeBadge = findViewById(R.id.questionTypeBadge)
        questionText      = findViewById(R.id.questionText)

        mcqContainer      = findViewById(R.id.mcqContainer)
        for (i in 0..3) {
            mcqCards[i]  = findViewById(resources.getIdentifier("mcqOpt$i", "id", packageName))
            mcqTexts[i]  = findViewById(resources.getIdentifier("mcqText$i", "id", packageName))
        }

        fillBlankContainer = findViewById(R.id.fillBlankContainer)
        fillInput1         = findViewById(R.id.fillInput1)
        fillInput2         = findViewById(R.id.fillInput2)
        fillInput2Layout   = findViewById(R.id.fillInput2Layout)

        shortAnswerContainer = findViewById(R.id.shortAnswerContainer)
        shortAnswerInput     = findViewById(R.id.shortAnswerInput)
        keywordsHint         = findViewById(R.id.keywordsHint)

        explanationCard  = findViewById(R.id.explanationCard)
        explanationLabel = findViewById(R.id.explanationLabel)
        explanationText  = findViewById(R.id.explanationText)

        evalSpinner      = findViewById(R.id.evalSpinner)
        submitAnswerBtn  = findViewById(R.id.submitAnswerBtn)
        nextBtn          = findViewById(R.id.nextBtn)

        difficultyBadge.text = quiz.difficulty.replaceFirstChar { it.uppercase() }

        submitAnswerBtn.setOnClickListener { onSubmitAnswer() }
        nextBtn.setOnClickListener {
            if (currentIndex + 1 < quiz.questions.size) {
                showQuestion(currentIndex + 1)
            } else {
                finishQuiz()
            }
        }
    }

    // ── Show question ──────────────────────────────────────────────────────────

    private fun showQuestion(index: Int) {
        currentIndex   = index
        questionAnswered = false
        val q = quiz.questions[index]
        val total = quiz.questions.size

        questionCounter.text = "Q ${index + 1} / $total"
        progressBar.progress = ((index + 1) * 100 / total)
        questionText.text     = q.question
        questionTypeBadge.text = when (q) {
            is QuizQuestion.MCQ             -> "Multiple Choice"
            is QuizQuestion.FillBlankTyped  -> "Fill in the Blank"
            is QuizQuestion.ShortAnswer     -> "Short Answer"
        }

        // Reset state
        explanationCard.visibility       = View.GONE
        submitAnswerBtn.visibility       = View.VISIBLE
        submitAnswerBtn.isEnabled        = true
        nextBtn.visibility               = View.GONE
        nextBtn.text                     = if (index + 1 < total) "Next →" else "See Results"
        fillInput1.setText("")
        fillInput2.setText("")
        shortAnswerInput.setText("")

        // Reset MCQ card colours
        for (i in 0..3) {
            mcqCards[i]?.setCardBackgroundColor(colorSurface)
            mcqCards[i]?.strokeWidth = 0
        }

        // Show the right input container
        mcqContainer.visibility          = View.GONE
        fillBlankContainer.visibility    = View.GONE
        shortAnswerContainer.visibility  = View.GONE

        when (q) {
            is QuizQuestion.MCQ -> setupMcq(q)
            is QuizQuestion.FillBlankTyped -> setupFillBlank(q)
            is QuizQuestion.ShortAnswer -> setupShortAnswer(q)
        }
    }

    // ── MCQ setup ─────────────────────────────────────────────────────────────

    private fun setupMcq(q: QuizQuestion.MCQ) {
        mcqContainer.visibility = View.VISIBLE
        for (i in 0..3) {
            val card = mcqCards[i] ?: continue
            val tv   = mcqTexts[i] ?: continue
            if (i < q.options.size) {
                card.visibility = View.VISIBLE
                tv.text = "${('A' + i)}.  ${q.options[i]}"
                card.setOnClickListener {
                    if (!questionAnswered) {
                        questionAnswered = true
                        val chosen = q.options[i]
                        val correct = apiClient.gradeMcq(chosen, q.correctAnswer)
                        revealMcq(q, i, correct)
                        recordAnswer(q, chosen, emptyList(), correct, 0)
                    }
                }
            } else {
                card.visibility = View.GONE
            }
        }
        submitAnswerBtn.visibility = View.GONE  // MCQ auto-submits on tap
    }

    private fun revealMcq(q: QuizQuestion.MCQ, selectedIdx: Int, correct: Boolean) {
        for (i in 0..3) {
            val card = mcqCards[i] ?: continue
            if (i >= q.options.size) continue
            val isCorrectOption = q.options[i].equals(q.correctAnswer, ignoreCase = true)
            when {
                isCorrectOption -> card.setCardBackgroundColor(colorSuccessLight)
                i == selectedIdx && !correct -> card.setCardBackgroundColor(colorErrorLight)
                else -> {}
            }
            card.setOnClickListener(null)
        }
        showExplanation(correct, q.explanation,
            if (!correct) "Correct answer: ${q.correctAnswer}" else null)
        showNextButton()
    }

    // ── Fill Blank setup ───────────────────────────────────────────────────────

    private fun setupFillBlank(q: QuizQuestion.FillBlankTyped) {
        fillBlankContainer.visibility = View.VISIBLE
        fillInput2Layout.visibility =
            if (q.correctAnswers.size > 1) View.VISIBLE else View.GONE
    }

    // ── Short Answer setup ─────────────────────────────────────────────────────

    private fun setupShortAnswer(q: QuizQuestion.ShortAnswer) {
        shortAnswerContainer.visibility = View.VISIBLE
        if (q.expectedKeywords.isNotEmpty()) {
            keywordsHint.text = "Hint: try to include — ${q.expectedKeywords.take(4).joinToString(", ")}"
        } else {
            keywordsHint.text = ""
        }
    }

    // ── Submit answer ──────────────────────────────────────────────────────────

    private fun onSubmitAnswer() {
        if (questionAnswered) return
        val q = quiz.questions[currentIndex]

        when (q) {
            is QuizQuestion.MCQ -> { /* handled on tap */ }

            is QuizQuestion.FillBlankTyped -> {
                val userAnswers = buildList {
                    add(fillInput1.text?.toString()?.trim() ?: "")
                    if (q.correctAnswers.size > 1)
                        add(fillInput2.text?.toString()?.trim() ?: "")
                }
                if (userAnswers.any { it.isBlank() }) {
                    Toast.makeText(this, "Please fill in all blanks", Toast.LENGTH_SHORT).show()
                    return
                }
                questionAnswered = true
                val correct = apiClient.gradeFillBlank(userAnswers, q.correctAnswers)
                showExplanation(correct, q.explanation,
                    if (!correct) "Correct answer(s): ${q.correctAnswers.joinToString(", ")}" else null)
                recordAnswer(q, userAnswers.joinToString("|"), userAnswers, correct, 0)
                showNextButton()
            }

            is QuizQuestion.ShortAnswer -> {
                val answer = shortAnswerInput.text?.toString()?.trim() ?: ""
                if (answer.isBlank()) {
                    Toast.makeText(this, "Please write your answer", Toast.LENGTH_SHORT).show()
                    return
                }
                questionAnswered = true
                evalSpinner.visibility = View.VISIBLE
                submitAnswerBtn.isEnabled = false
                lifecycleScope.launch {
                    val (score, result, feedback) = withContext(Dispatchers.IO) {
                        apiClient.evaluateShortAnswer(
                            question         = q.question,
                            userAnswer       = answer,
                            expectedKeywords = q.expectedKeywords,
                            sampleAnswer     = q.sampleAnswer
                        )
                    }
                    evalSpinner.visibility = View.GONE
                    val correct = score >= 2
                    val extraFeedback = buildString {
                        if (feedback.isNotBlank()) append(feedback)
                        if (!correct) append("\n\nSample answer: ${q.sampleAnswer}")
                    }
                    showExplanation(correct, q.explanation, extraFeedback.trim())
                    recordAnswer(q, answer, emptyList(), correct, score)
                    showNextButton()
                }
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun recordAnswer(
        q: QuizQuestion,
        userAnswer: String,
        userAnswerList: List<String>,
        isCorrect: Boolean,
        score: Int
    ) {
        answers.add(QuizAnswer(
            questionId     = q.id,
            questionType   = q.type,
            userAnswer     = userAnswer,
            userAnswerList = userAnswerList,
            isCorrect      = isCorrect,
            score          = score
        ))
    }

    private fun showExplanation(correct: Boolean, explanation: String, extra: String?) {
        explanationCard.visibility = View.VISIBLE
        if (correct) {
            explanationCard.setCardBackgroundColor(colorSuccessLight)
            explanationLabel.text      = "✓ Correct!"
            explanationLabel.setTextColor(colorSuccess)
        } else {
            explanationCard.setCardBackgroundColor(colorErrorLight)
            explanationLabel.text      = "✗ Incorrect"
            explanationLabel.setTextColor(colorError)
        }
        explanationText.text = buildString {
            append(explanation)
            if (!extra.isNullOrBlank()) {
                append("\n\n")
                append(extra)
            }
        }
    }

    private fun showNextButton() {
        submitAnswerBtn.visibility = View.GONE
        nextBtn.visibility         = View.VISIBLE
    }

    // ── Finish ─────────────────────────────────────────────────────────────────

    private fun finishQuiz() {
        // Add a dummy answer for any unanswered questions
        while (answers.size < quiz.questions.size) {
            val q = quiz.questions[answers.size]
            answers.add(QuizAnswer(q.id, q.type, "", isCorrect = false, score = 0))
        }

        val correctCount   = answers.count { it.isCorrect }
        val totalSeconds   = (System.currentTimeMillis() - startTimeMs) / 1000
        val scorePercent   = if (quiz.questions.isNotEmpty())
            (correctCount * 100 / quiz.questions.size) else 0

        val result = QuizResult(
            quiz            = quiz,
            answers         = answers,
            correctCount    = correctCount,
            totalCount      = quiz.questions.size,
            scorePercent    = scorePercent,
            timeTakenSeconds = totalSeconds
        )

        // Submit to backend (fire-and-forget)
        val userId = SessionManager.getFirestoreUserId(this)
        lifecycleScope.launch(Dispatchers.IO) {
            apiClient.submitQuiz(
                userId           = userId,
                quizId           = quiz.id,
                chapterId        = quiz.chapterId,
                answers          = answers,
                timeTakenSeconds = totalSeconds
            )
        }

        startActivity(
            Intent(this, QuizResultActivity::class.java)
                .putExtra("correctCount",   result.correctCount)
                .putExtra("totalCount",     result.totalCount)
                .putExtra("scorePercent",   result.scorePercent)
                .putExtra("timeTakenSec",   result.timeTakenSeconds)
                .putExtra("difficulty",     quiz.difficulty)
                .putExtra("chapterTitle",   quiz.chapterTitle)
                .putExtra("quizJson",       intent.getStringExtra("quizJson"))
                .putExtra("answersJson",    answersToJson())
        )
        finish()
    }

    private fun answersToJson(): String {
        val arr = org.json.JSONArray()
        for (a in answers) {
            arr.put(org.json.JSONObject().apply {
                put("questionId",   a.questionId)
                put("questionType", a.questionType)
                put("userAnswer",   a.userAnswer)
                put("isCorrect",    a.isCorrect)
                put("score",        a.score)
            })
        }
        return arr.toString()
    }
}

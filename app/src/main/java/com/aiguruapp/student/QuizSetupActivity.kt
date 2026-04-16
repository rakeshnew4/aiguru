package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.models.Quiz
import com.aiguruapp.student.quiz.QuizApiClient
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class QuizSetupActivity : BaseActivity() {

    private lateinit var subjectName: String
    private lateinit var chapterId: String
    private lateinit var chapterTitle: String

    private lateinit var difficultyGroup: RadioGroup
    private lateinit var countGroup: RadioGroup
    private lateinit var cbMcq: CheckBox
    private lateinit var cbFillBlank: CheckBox
    private lateinit var cbShortAnswer: CheckBox
    private lateinit var generateButton: MaterialButton
    private lateinit var loadingOverlay: FrameLayout

    private val apiClient by lazy { QuizApiClient(AdminConfigRepository.effectiveServerUrl()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_quiz_setup)

        subjectName  = intent.getStringExtra("subjectName")  ?: "Subject"
        chapterId    = intent.getStringExtra("chapterId")    ?: ""
        chapterTitle = intent.getStringExtra("chapterTitle") ?: "Chapter"

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        findViewById<TextView>(R.id.chapterSubtitle).text = "$subjectName · $chapterTitle"

        difficultyGroup = findViewById(R.id.difficultyGroup)
        countGroup      = findViewById(R.id.countGroup)
        cbMcq           = findViewById(R.id.cbMcq)
        cbFillBlank     = findViewById(R.id.cbFillBlank)
        cbShortAnswer   = findViewById(R.id.cbShortAnswer)
        generateButton  = findViewById(R.id.generateButton)
        loadingOverlay  = findViewById(R.id.loadingOverlay)

        generateButton.setOnClickListener { onGenerateClicked() }
    }

    private fun onGenerateClicked() {
        // Validate at least one type selected
        val types = mutableListOf<String>()
        if (cbMcq.isChecked)         types.add("mcq")
        if (cbFillBlank.isChecked)   types.add("fill_blank_typed")
        if (cbShortAnswer.isChecked) types.add("short_answer")

        if (types.isEmpty()) {
            Toast.makeText(this, "Please select at least one question type", Toast.LENGTH_SHORT).show()
            return
        }

        val difficulty = when (difficultyGroup.checkedRadioButtonId) {
            R.id.rbEasy -> "easy"
            R.id.rbHard -> "hard"
            else        -> "medium"
        }

        val count = when (countGroup.checkedRadioButtonId) {
            R.id.rb10 -> 10
            R.id.rb15 -> 15
            R.id.rb20 -> 20
            else      -> 5
        }

        val userId = SessionManager.getFirestoreUserId(this)

        setLoading(true)
        lifecycleScope.launch {
            try {
                val quiz: Quiz = withContext(Dispatchers.IO) {
                    apiClient.generateQuiz(
                        subject       = subjectName,
                        chapterId     = chapterId,
                        chapterTitle  = chapterTitle,
                        difficulty    = difficulty,
                        questionTypes = types,
                        count         = count,
                        userId        = userId
                    )
                }
                setLoading(false)
                
                // ✓ VALIDATION: Check if quiz has valid questions
                if (quiz.questions.isEmpty()) {
                    Toast.makeText(
                        this@QuizSetupActivity,
                        "Quiz generation failed: No valid questions. Please try again or select different options.",
                        Toast.LENGTH_LONG
                    ).show()
                    return@launch
                }
                
                // Serialize & pass quiz to QuizActivity
                startActivity(
                    Intent(this@QuizSetupActivity, QuizActivity::class.java)
                        .putExtra("quizJson", quiz.toTransferJson())
                        .putExtra("subjectName", subjectName)
                )
                finish()
            } catch (e: Exception) {
                setLoading(false)
                // ✓ Better error handling
                val errorMsg = when {
                    e.message?.contains("502") == true -> "Server error generating quiz. The LLM returned invalid data. Please try again."
                    e.message?.contains("timeout", ignoreCase = true) == true -> "Quiz generation took too long. Please try again."
                    e.message?.contains("Empty response") == true -> "No response from server. Check your connection and try again."
                    else -> "Failed to generate quiz: ${e.message}"
                }
                Toast.makeText(
                    this@QuizSetupActivity,
                    errorMsg,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    private fun setLoading(show: Boolean) {
        loadingOverlay.visibility = if (show) View.VISIBLE else View.GONE
        generateButton.isEnabled  = !show
    }
}



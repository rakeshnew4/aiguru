package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.aiguruapp.student.adapters.QuizValidationAdapter
import com.aiguruapp.student.models.Quiz
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * Teacher-facing quiz validation screen.
 *
 * Shows all AI-generated quiz questions with checkboxes (all checked by default).
 * Teacher can deselect questions to remove them.
 * Pressing "Confirm Quiz" returns the filtered quiz JSON to the caller via setResult.
 *
 * Intent extras (required):
 *   quizJson    (String)  — Quiz.toTransferJson() output
 *   subjectName (String)  — for display
 *   chapterName (String)  — for display
 */
class TeacherQuizValidationActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_QUIZ_JSON    = "quizJson"
        const val EXTRA_SUBJECT_NAME = "subjectName"
        const val EXTRA_CHAPTER_NAME = "chapterName"
        const val RESULT_FILTERED_QUIZ = "filteredQuizJson"
    }

    private lateinit var recyclerView: RecyclerView
    private lateinit var countLabel: TextView
    private lateinit var confirmButton: MaterialButton
    private lateinit var selectAllButton: MaterialButton

    private lateinit var originalQuiz: Quiz
    private lateinit var adapter: QuizValidationAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (!com.aiguruapp.student.config.AccessGate.requireAccess(this, com.aiguruapp.student.config.AccessGate.Feature.TEACHER_QUIZ_VALIDATION)) return
        setContentView(R.layout.activity_teacher_quiz_validation)

        recyclerView    = findViewById(R.id.quizValidationList)
        countLabel      = findViewById(R.id.selectedQuestionsCount)
        confirmButton   = findViewById(R.id.btnConfirmQuiz)
        selectAllButton = findViewById(R.id.btnSelectAllQuestions)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }

        val quizJson = intent.getStringExtra(EXTRA_QUIZ_JSON) ?: run {
            Toast.makeText(this, "No quiz data found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        val subject = intent.getStringExtra(EXTRA_SUBJECT_NAME) ?: ""
        val chapter = intent.getStringExtra(EXTRA_CHAPTER_NAME) ?: ""

        originalQuiz = Quiz.fromJson(JSONObject(quizJson))

        val subtitle = findViewById<TextView>(R.id.quizValidationSubtitle)
        val totalCount = originalQuiz.questions.size
        subtitle.text = "$subject › $chapter · $totalCount questions generated"

        adapter = QuizValidationAdapter(originalQuiz.questions) { selectedCount ->
            countLabel.text = "$selectedCount / $totalCount questions selected"
            confirmButton.isEnabled = selectedCount > 0
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        updateCount()

        selectAllButton.setOnClickListener {
            val allSelected = adapter.areAllSelected()
            adapter.setAllSelected(!allSelected)
            selectAllButton.text = if (allSelected) "Select All" else "Deselect All"
            updateCount()
        }

        confirmButton.setOnClickListener {
            val kept = adapter.getKeptQuestions()
            if (kept.isEmpty()) {
                Toast.makeText(this, "Select at least one question", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val filteredQuiz = originalQuiz.copy(questions = kept)
            val result = Intent().putExtra(RESULT_FILTERED_QUIZ, filteredQuiz.toTransferJson())
            setResult(RESULT_OK, result)
            finish()
        }
    }

    private fun updateCount() {
        val kept = adapter.getKeptQuestions().size
        val total = originalQuiz.questions.size
        countLabel.text = "$kept / $total questions selected"
        confirmButton.isEnabled = kept > 0
        selectAllButton.text = if (adapter.areAllSelected()) "Deselect All" else "Select All"
    }
}

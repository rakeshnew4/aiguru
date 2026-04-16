package com.aiguruapp.student

import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * Teacher's own chat screen.
 *
 * Wraps [FullChatFragment] with a teacher-specific header that shows the
 * current subject + chapter and lets the teacher edit their defaults via
 * a settings dialog.  Defaults are persisted to
 * users/{uid}/teacher_settings/chat_defaults so they survive re-opens.
 *
 * Intent extras (all optional — values load from Firestore if absent):
 *   subjectName  (String)  — pre-fill subject
 *   chapterName  (String)  — pre-fill chapter
 */
class TeacherChatHostActivity : AppCompatActivity() {

    private lateinit var subjectLabel: TextView
    private lateinit var chapterLabel: TextView
    private lateinit var settingsButton: ImageButton

    private val userId by lazy { SessionManager.getFirestoreUserId(this) }

    private var currentSubject = ""
    private var currentChapter = ""
    private var currentGrade   = ""
    private var currentSchool  = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_teacher_chat_host)

        subjectLabel  = findViewById(R.id.teacherChatSubjectLabel)
        chapterLabel  = findViewById(R.id.teacherChatChapterLabel)
        settingsButton = findViewById(R.id.teacherChatSettingsButton)

        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
        settingsButton.setOnClickListener { showDefaultsDialog() }

        // Load saved defaults, then maybe override from intent extras, then launch fragment
        FirestoreManager.getTeacherChatDefaults(userId) { subject, chapter, grade, schoolId ->
            currentSubject = intent.getStringExtra("subjectName")?.takeIf { it.isNotBlank() } ?: subject
            currentChapter = intent.getStringExtra("chapterName")?.takeIf { it.isNotBlank() } ?: chapter
            currentGrade   = grade
            currentSchool  = schoolId

            updateHeader()

            if (currentSubject.isBlank() || currentChapter.isBlank()) {
                // First launch — ask to set defaults before entering chat
                showDefaultsDialog(firstLaunch = true)
            } else {
                launchChatFragment()
            }
        }
    }

    private fun updateHeader() {
        val sub = currentSubject.ifBlank { "General" }
        val ch  = currentChapter.ifBlank { "Study Session" }
        subjectLabel.text = sub
        chapterLabel.text = ch
    }

    private fun launchChatFragment() {
        val sub = currentSubject.ifBlank { "General" }
        val ch  = currentChapter.ifBlank { "Study Session" }

        val existing = supportFragmentManager.findFragmentByTag("teacher_chat") as? FullChatFragment
        if (existing != null) return  // already loaded

        supportFragmentManager.beginTransaction()
            .replace(R.id.teacherChatFragmentContainer,
                FullChatFragment.newInstance(sub, ch),
                "teacher_chat")
            .commitNow()
    }

    private fun showDefaultsDialog(firstLaunch: Boolean = false) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_teacher_chat_defaults, null)
        val subjectInput = dialogView.findViewById<TextInputEditText>(R.id.defaultSubjectInput)
        val chapterInput = dialogView.findViewById<TextInputEditText>(R.id.defaultChapterInput)
        val gradeInput   = dialogView.findViewById<MaterialAutoCompleteTextView>(R.id.defaultGradeInput)

        subjectInput.setText(currentSubject)
        chapterInput.setText(currentChapter)

        // Populate grade dropdown
        val grades = listOf("6", "7", "8", "9", "10", "11", "12")
        gradeInput.setAdapter(
            android.widget.ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, grades)
        )
        if (currentGrade.isNotBlank()) gradeInput.setText(currentGrade, false)

        AlertDialog.Builder(this)
            .setTitle(if (firstLaunch) "⚙️ Set Your Teaching Context" else "⚙️ Update Teaching Context")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val sub = subjectInput.text?.toString()?.trim() ?: ""
                val ch  = chapterInput.text?.toString()?.trim() ?: ""
                val gr  = gradeInput.text?.toString()?.trim() ?: ""

                if (sub.isBlank() || ch.isBlank()) {
                    Toast.makeText(this, "Subject and chapter are required", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }

                currentSubject = sub
                currentChapter = ch
                currentGrade   = gr
                updateHeader()

                FirestoreManager.setTeacherChatDefaults(
                    userId    = userId,
                    subject   = sub,
                    chapter   = ch,
                    grade     = gr,
                    schoolId  = currentSchool
                )

                // Replace fragment with new context
                supportFragmentManager.findFragmentByTag("teacher_chat")?.let {
                    supportFragmentManager.beginTransaction().remove(it).commitNow()
                }
                launchChatFragment()
            }
            .setNegativeButton("Cancel") { _, _ ->
                if (firstLaunch) {
                    // Still launch fragment with blank defaults
                    launchChatFragment()
                }
            }
            .show()
    }
}

package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.utils.SessionManager
import com.aiguruapp.student.firestore.FirestoreManager

class SignupActivity : BaseActivity() {

    private lateinit var nameEditText: EditText
    private lateinit var gradeSpinner: Spinner
    private lateinit var langSpinner: Spinner
    private lateinit var submitButton: Button
    private lateinit var progressBar: ProgressBar

    private val grades = arrayOf("Select Grade", "General", "6th", "7th", "8th", "9th", "10th", "11th", "12th")

    // Display labels paired with BCP-47 codes
    private val langLabels = arrayOf(
        "English only",
        "Hindi + English mix",
        "Telugu + English mix",
        "Tamil + English mix",
        "Kannada + English mix",
        "Marathi + English mix",
        "Bengali + English mix",
        "Gujarati + English mix"
    )
    private val langCodes = arrayOf(
        "en-US", "hi-IN", "te-IN", "ta-IN", "kn-IN", "mr-IN", "bn-IN", "gu-IN"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        nameEditText = findViewById(R.id.etStudentName)
        gradeSpinner = findViewById(R.id.spinnerGrade)
        langSpinner  = findViewById(R.id.spinnerLang)
        submitButton = findViewById(R.id.btnSubmit)
        progressBar  = findViewById(R.id.progressBar)

        val gradeAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, grades)
        gradeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        gradeSpinner.adapter = gradeAdapter

        val langAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, langLabels)
        langAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        langSpinner.adapter = langAdapter

        // Pre-select saved preference
        val savedLang = SessionManager.getPreferredLang(this)
        val savedIdx = langCodes.indexOf(savedLang).coerceAtLeast(0)
        langSpinner.setSelection(savedIdx)

        // Pre-fill name from session if already set
        val existingName = SessionManager.getStudentName(this)
        if (existingName.isNotBlank() && existingName != "Student") {
            nameEditText.setText(existingName)
        }

        submitButton.setOnClickListener { handleSignup() }
    }

    private fun handleSignup() {
        val name  = nameEditText.text.toString().trim()
        val grade = gradeSpinner.selectedItem.toString()

        if (name.isEmpty()) {
            Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
            return
        }
        if (grade == "Select Grade") {
            Toast.makeText(this, "Please select your grade", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedLangCode = langCodes.getOrElse(langSpinner.selectedItemPosition) { "en-US" }

        // Persist name update and grade locally
        SessionManager.login(
            context     = this,
            schoolId    = SessionManager.getSchoolId(this),
            schoolName  = SessionManager.getSchoolName(this),
            studentId   = SessionManager.getStudentId(this),
            studentName = name
        )
        SessionManager.saveGrade(this, grade)
        SessionManager.savePreferredLang(this, selectedLangCode)
        SessionManager.completeSignup(this)

        // Persist to Firestore (fire-and-forget — don't block the user)
        val meta = SessionManager.buildUserMetadata(this)
        FirestoreManager.saveUserMetadata(meta, onSuccess = {
            FirestoreManager.cleanupMangledFields(meta.userId)
        })

        goHome()
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}


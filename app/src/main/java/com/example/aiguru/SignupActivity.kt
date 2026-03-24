package com.example.aiguru

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.example.aiguru.utils.SessionManager
import com.example.aiguru.firestore.FirestoreManager

class SignupActivity : AppCompatActivity() {

    private lateinit var nameEditText: EditText
    private lateinit var gradeSpinner: Spinner
    private lateinit var submitButton: Button
    private lateinit var progressBar: ProgressBar

    private val grades = arrayOf("Select Grade", "6th", "7th", "8th", "9th", "10th", "11th", "12th")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_signup)

        nameEditText = findViewById(R.id.etStudentName)
        gradeSpinner = findViewById(R.id.spinnerGrade)
        submitButton = findViewById(R.id.btnSubmit)
        progressBar = findViewById(R.id.progressBar)

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, grades)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        gradeSpinner.adapter = adapter

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

        // Persist name update and grade locally
        SessionManager.login(
            context     = this,
            schoolId    = SessionManager.getSchoolId(this),
            schoolName  = SessionManager.getSchoolName(this),
            studentId   = SessionManager.getStudentId(this),
            studentName = name
        )
        SessionManager.saveGrade(this, grade)
        SessionManager.completeSignup(this)

        // Persist to Firestore (fire-and-forget — don't block the user)
        FirestoreManager.saveUserMetadata(SessionManager.buildUserMetadata(this))

        goHome()
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}


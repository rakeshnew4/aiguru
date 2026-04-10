package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.models.School
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.auth.FirebaseAuth

class SchoolLoginActivity : BaseActivity() {

    private lateinit var schoolAutoComplete: AutoCompleteTextView
    private lateinit var studentIdInput: TextInputEditText
    private lateinit var studentNameInput: TextInputEditText
    private lateinit var loginButton: MaterialButton

    private var schools: List<School> = emptyList()
    private var selectedSchool: School? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_school_login)

        schoolAutoComplete = findViewById(R.id.schoolAutoComplete)
        studentIdInput     = findViewById(R.id.studentIdInput)
        studentNameInput   = findViewById(R.id.studentNameInput)
        loginButton        = findViewById(R.id.loginButton)

        setupSchoolDropdown()

        loginButton.setOnClickListener { handleLogin() }
    }

    private fun setupSchoolDropdown() {
        schools = ConfigManager.getSchools(this)
        val displayNames = schools.map { it.displayName }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        schoolAutoComplete.setAdapter(adapter)
        schoolAutoComplete.threshold = 1  // Show suggestions after 1 character

        schoolAutoComplete.setOnItemClickListener { _, _, position, _ ->
            // Match back to the School object by display name
            val picked = schoolAutoComplete.text.toString()
            selectedSchool = schools.find { it.displayName == picked }
        }
    }

    private fun handleLogin() {
        val schoolText = schoolAutoComplete.text.toString().trim()
        val studentId  = studentIdInput.text.toString().trim().uppercase()
        val studentName = studentNameInput.text.toString().trim()

        // Validate school selection
        if (schoolText.isEmpty() || selectedSchool == null) {
            selectedSchool = schools.find { it.displayName == schoolText }
        }
        if (selectedSchool == null && schools.isNotEmpty()) {
            // Accept any partial match as a convenience
            selectedSchool = schools.firstOrNull {
                it.name.contains(schoolText, ignoreCase = true) ||
                it.shortName.contains(schoolText, ignoreCase = true)
            }
        }
        if (selectedSchool == null) {
            Toast.makeText(this, "Please select a school from the list", Toast.LENGTH_SHORT).show()
            schoolAutoComplete.requestFocus()
            return
        }

        if (studentId.isEmpty()) {
            Toast.makeText(this, "Please enter your Student ID", Toast.LENGTH_SHORT).show()
            studentIdInput.requestFocus()
            return
        }

        val school = selectedSchool!!
        val name = studentName.ifBlank { "Student" }

        SessionManager.login(
            context     = this,
            schoolId    = school.id,
            schoolName  = school.name,
            studentId   = studentId,
            studentName = name
        )

        val navigateNext: () -> Unit = {
            val nextClass = if (SessionManager.isSignupComplete(this)) {
                HomeActivity::class.java
            } else {
                SignupActivity::class.java
            }
            startActivity(Intent(this, nextClass).apply {
                putExtra("schoolId", school.id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            })
            finish()
        }

        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            SessionManager.saveFirebaseUid(this, auth.currentUser!!.uid)
            navigateNext()
        } else {
            loginButton.isEnabled = false
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    result.user?.uid?.let { uid -> SessionManager.saveFirebaseUid(this, uid) }
                    navigateNext()
                }
                .addOnFailureListener { navigateNext() }
        }
    }
}
package com.example.aiguru

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.aiguru.models.School
import com.example.aiguru.utils.ConfigManager
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class SchoolLoginActivity : AppCompatActivity() {

    private lateinit var schoolAutoComplete: AutoCompleteTextView
    private lateinit var studentIdInput: TextInputEditText
    private lateinit var studentNameInput: TextInputEditText
    private lateinit var schoolDropdownLayout: TextInputLayout
    private lateinit var studentIdLayout: TextInputLayout
    private lateinit var loginButton: MaterialButton

    private var schools: List<School> = emptyList()
    private var selectedSchool: School? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_school_login)

        bindViews()
        loadSchoolsAndSetupDropdown()
        applyAppBranding()

        loginButton.setOnClickListener { attemptLogin() }
    }

    private fun bindViews() {
        schoolAutoComplete = findViewById(R.id.schoolAutoComplete)
        studentIdInput = findViewById(R.id.studentIdInput)
        studentNameInput = findViewById(R.id.studentNameInput)
        schoolDropdownLayout = findViewById(R.id.schoolDropdownLayout)
        studentIdLayout = findViewById(R.id.studentIdLayout)
        loginButton = findViewById(R.id.loginButton)
    }

    private fun loadSchoolsAndSetupDropdown() {
        schools = ConfigManager.getSchools(this)
        val displayNames = schools.map { "${it.name} — ${it.city}" }

        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        schoolAutoComplete.setAdapter(adapter)
        schoolAutoComplete.threshold = 1

        schoolAutoComplete.setOnItemClickListener { _, _, position, _ ->
            selectedSchool = schools[position]
            schoolDropdownLayout.error = null
            // Tint login button with school's primary color
            selectedSchool?.branding?.primaryColor?.let { hex ->
                runCatching {
                    loginButton.backgroundTintList =
                        android.content.res.ColorStateList.valueOf(Color.parseColor(hex))
                }
            }
        }

        // Also filter as user types
        schoolAutoComplete.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {
                if (selectedSchool != null && s.toString() != "${selectedSchool!!.name} — ${selectedSchool!!.city}") {
                    selectedSchool = null
                }
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun applyAppBranding() {
        val config = ConfigManager.getAppConfig(this)
        val theme = config.defaultTheme
        runCatching {
            findViewById<LinearLayout>(R.id.loginHeader)
                .setBackgroundColor(Color.parseColor(theme.primaryColor))
            loginButton.backgroundTintList =
                android.content.res.ColorStateList.valueOf(Color.parseColor(theme.buttonPrimaryColor))
        }
        findViewById<TextView>(R.id.appNameText).text = config.appName
        findViewById<TextView>(R.id.appTaglineText).text = config.tagline
    }

    private fun attemptLogin() {
        val school = selectedSchool
        val studentId = studentIdInput.text?.toString()?.trim() ?: ""
        val studentName = studentNameInput.text?.toString()?.trim() ?: ""

        // Validate
        var hasError = false
        if (school == null) {
            schoolDropdownLayout.error = "Please select your school"
            hasError = true
        } else {
            schoolDropdownLayout.error = null
        }
        if (studentId.isBlank()) {
            studentIdLayout.error = "Please enter your Student ID"
            hasError = true
        } else {
            studentIdLayout.error = null
        }
        if (hasError) return

        // Save session
        SessionManager.login(
            context = this,
            schoolId = school!!.id,
            schoolName = school.name,
            studentId = studentId,
            studentName = studentName
        )

        // Navigate to subscription screen
        startActivity(
            Intent(this, SubscriptionActivity::class.java)
                .putExtra("schoolId", school.id)
        )
        finish()
    }
}

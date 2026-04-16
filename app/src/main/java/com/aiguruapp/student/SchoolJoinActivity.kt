package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Toast
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.School
import com.aiguruapp.student.utils.SessionManager
import com.aiguruapp.student.widget.BoxSpinnerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.MaterialAutoCompleteTextView
import com.google.android.material.textfield.TextInputEditText

/**
 * School join screen — both students and teachers validate their credentials here.
 *
 * Flow:
 * 1. User selects school from dropdown (populated from ConfigManager)
 * 2. Toggles role: Student | Teacher
 * 3. Enters username + password
 * 4. App validates against schools/{id}/students/{username}
 *      or schools/{id}/teachers/{username} depending on role
 * 5. On match → save session + navigate
 *    Student  → HomeActivity
 *    Teacher  → TeacherDashboardActivity
 */
class SchoolJoinActivity : BaseActivity() {

    private lateinit var etSchoolCode: MaterialAutoCompleteTextView
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etStudentName: TextInputEditText
    private lateinit var tvBenefitsBanner: android.widget.TextView
    private lateinit var cardBenefitsBanner: View
    private lateinit var btnRoleStudent: MaterialButton
    private lateinit var btnRoleTeacher: MaterialButton
    private lateinit var btnJoin: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var progressBar: BoxSpinnerView

    private var matchedSchool: School? = null
    private var isTeacherMode = false
    private var allSchools: List<School> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_school_join)

        etSchoolCode     = findViewById(R.id.etSchoolCode)
        etUsername       = findViewById(R.id.etUsername)
        etPassword       = findViewById(R.id.etPassword)
        etStudentName    = findViewById(R.id.etStudentName)
        tvBenefitsBanner = findViewById(R.id.tvBenefitsBanner)
        cardBenefitsBanner = findViewById(R.id.cardBenefitsBanner)
        btnRoleStudent   = findViewById(R.id.btnRoleStudent)
        btnRoleTeacher   = findViewById(R.id.btnRoleTeacher)
        btnJoin          = findViewById(R.id.btnJoinSchool)
        btnSkip          = findViewById(R.id.btnSkipSchool)
        progressBar      = findViewById(R.id.schoolJoinProgress)

        setupSchoolDropdown()
        setupRoleToggle()

        btnJoin.setOnClickListener { attemptJoin() }
        btnSkip.setOnClickListener { goHome() }
    }

    private fun setupSchoolDropdown() {
        allSchools = ConfigManager.getSchools(this)
        val names = allSchools.map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, names)
        etSchoolCode.setAdapter(adapter)
        etSchoolCode.setOnItemClickListener { _, _, pos, _ ->
            matchedSchool = allSchools[pos]
            matchedSchool?.let { showBenefits(it) }
        }
    }

    private fun setupRoleToggle() {
        updateRoleUI()
        btnRoleStudent.setOnClickListener {
            isTeacherMode = false
            updateRoleUI()
        }
        btnRoleTeacher.setOnClickListener {
            isTeacherMode = true
            updateRoleUI()
        }
    }

    private fun updateRoleUI() {
        val purple = getColor(R.color.colorPrimary)
        val white  = getColor(android.R.color.white)
        if (isTeacherMode) {
            btnRoleTeacher.setBackgroundColor(purple); btnRoleTeacher.setTextColor(white)
            btnRoleStudent.setBackgroundColor(white);  btnRoleStudent.setTextColor(purple)
            btnJoin.text = "Join as Teacher"
        } else {
            btnRoleStudent.setBackgroundColor(purple); btnRoleStudent.setTextColor(white)
            btnRoleTeacher.setBackgroundColor(white);  btnRoleTeacher.setTextColor(purple)
            btnJoin.text = "Join School"
        }
    }

    private fun showBenefits(school: School) {
        val freePlan = school.plans.firstOrNull { it.isFree }
        val paidPlan = school.plans.firstOrNull { !it.isFree }
        val msg = when {
            freePlan != null -> "🎉 ${school.shortName.ifBlank { school.name }} students get FREE access!\n${freePlan.features.take(3).joinToString(" · ")}"
            paidPlan != null -> "🏫 ${school.name} plan: ₹${paidPlan.priceINR}/${paidPlan.duration} — ${paidPlan.features.take(2).joinToString(", ")}"
            else             -> "🏫 Join ${school.name} to unlock school benefits"
        }
        tvBenefitsBanner.text = msg
        cardBenefitsBanner.visibility = View.VISIBLE
    }

    private fun attemptJoin() {
        val school = matchedSchool ?: run {
            Toast.makeText(this, "Please select a school first", Toast.LENGTH_SHORT).show()
            return
        }
        val username = etUsername.text.toString().trim()
        val password = etPassword.text.toString()
        if (username.isEmpty()) {
            Toast.makeText(this, "Enter your username", Toast.LENGTH_SHORT).show()
            return
        }
        if (password.isEmpty()) {
            Toast.makeText(this, "Enter your password", Toast.LENGTH_SHORT).show()
            return
        }

        setLoading(true)

        if (isTeacherMode) {
            FirestoreManager.validateTeacher(
                schoolId  = school.id,
                username  = username,
                password  = password,
                onSuccess = { name, teacherId ->
                    runOnUiThread {
                        setLoading(false)
                        SessionManager.login(
                            context     = this,
                            schoolId    = school.id,
                            schoolName  = school.name,
                            studentId   = teacherId,
                            studentName = name
                        )
                        SessionManager.saveIsTeacher(this, true)
                        onTeacherJoinSuccess(school)
                    }
                },
                onFailure = { msg ->
                    runOnUiThread {
                        setLoading(false)
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                    }
                }
            )
        } else {
            // Student validation — existing Firestore flow
            com.google.firebase.firestore.FirebaseFirestore.getInstance()
                .collection("schools")
                .document(school.id)
                .collection("students")
                .document(username.lowercase())
                .get()
                .addOnSuccessListener { doc ->
                    setLoading(false)
                    if (doc.exists()) {
                        val storedPassword = doc.getString("password") ?: ""
                        if (storedPassword == password) {
                            val rosterName = doc.getString("name")
                            val displayName = rosterName?.takeIf { it.isNotBlank() }
                                ?: etStudentName.text.toString().trim().takeIf { it.isNotEmpty() }
                                ?: SessionManager.getStudentName(this)
                            val studentId = doc.getString("student_id")
                                ?: username
                            SessionManager.login(
                                context     = this,
                                schoolId    = school.id,
                                schoolName  = school.name,
                                studentId   = studentId,
                                studentName = displayName
                            )
                            SessionManager.saveIsTeacher(this, false)
                            onJoinSuccess(school, username)
                        } else {
                            Toast.makeText(this, "Incorrect password. Check with your teacher.", Toast.LENGTH_LONG).show()
                        }
                    } else {
                        Toast.makeText(this, "Username not found in ${school.name}. Check with your teacher.", Toast.LENGTH_LONG).show()
                    }
                }
                .addOnFailureListener { e ->
                    setLoading(false)
                    Toast.makeText(this, "Could not verify: ${e.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun onJoinSuccess(school: School, username: String) {
        val freePlan = school.plans.firstOrNull { it.isFree }
        if (freePlan != null) {
            SessionManager.savePlan(this, freePlan.id, freePlan.name)
        }
        val meta = SessionManager.buildUserMetadata(this)
        FirestoreManager.saveUserMetadata(meta)
        Toast.makeText(this, "✅ Joined ${school.name}!", Toast.LENGTH_SHORT).show()
        goHome()
    }

    private fun onTeacherJoinSuccess(school: School) {
        val freePlan = school.plans.firstOrNull { it.isFree }
        if (freePlan != null) {
            SessionManager.savePlan(this, freePlan.id, freePlan.name)
        }
        val meta = SessionManager.buildUserMetadata(this)
        FirestoreManager.saveUserMetadata(meta)
        Toast.makeText(this, "✅ Welcome, Teacher! Joined ${school.name}", Toast.LENGTH_SHORT).show()
        startActivity(Intent(this, TeacherDashboardActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }

    private fun setLoading(loading: Boolean) {
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        btnJoin.isEnabled = !loading
        btnSkip.isEnabled = !loading
    }

    private fun goHome() {
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
        finish()
    }
}

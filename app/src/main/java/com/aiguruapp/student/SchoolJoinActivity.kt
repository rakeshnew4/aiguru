package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import android.widget.Toast
import com.aiguruapp.student.config.AdminConfigRepository
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.School
import com.aiguruapp.student.utils.SessionManager
import com.aiguruapp.student.widget.BoxSpinnerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.FirebaseFirestore

/**
 * Optional post-signup screen where a student can join their school by entering
 * the 4-letter school code and their roll number.
 *
 * Flow:
 * 1. Student types school code → live-search shows school name + logo emoji
 * 2. Student enters roll number
 * 3. App checks schools/{id}/students/{rollNumber} in Firestore
 * 4. On match → save school to session, apply branding, go home
 *    On no match → show error, student can skip and go home as independent
 */
class SchoolJoinActivity : BaseActivity() {

    private lateinit var etSchoolCode: TextInputEditText
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etStudentName: TextInputEditText
    private lateinit var etStudentId: TextInputEditText
    private lateinit var tvSchoolPreview: TextView
    private lateinit var tvBenefitsBanner: TextView
    private lateinit var cardBenefitsBanner: View
    private lateinit var btnJoin: MaterialButton
    private lateinit var btnSkip: MaterialButton
    private lateinit var progressBar: BoxSpinnerView

    private var matchedSchool: School? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_school_join)

        etSchoolCode     = findViewById(R.id.etSchoolCode)
        etUsername       = findViewById(R.id.etUsername)
        etPassword       = findViewById(R.id.etPassword)
        etStudentName    = findViewById(R.id.etStudentName)
        etStudentId      = findViewById(R.id.etStudentId)
        tvSchoolPreview  = findViewById(R.id.tvSchoolPreview)
        tvBenefitsBanner = findViewById(R.id.tvBenefitsBanner)
        cardBenefitsBanner = findViewById(R.id.cardBenefitsBanner)
        btnJoin          = findViewById(R.id.btnJoinSchool)
        btnSkip          = findViewById(R.id.btnSkipSchool)
        progressBar      = findViewById(R.id.schoolJoinProgress)

        // Live school name preview as the student types the code
        etSchoolCode.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val code = s.toString().trim().uppercase()
                matchedSchool = ConfigManager.findSchoolByCode(this@SchoolJoinActivity, code)
                if (matchedSchool != null) {
                    val school = matchedSchool!!
                    tvSchoolPreview.text = "${school.branding.logoEmoji}  ${school.name}"
                    tvSchoolPreview.visibility = View.VISIBLE
                    showBenefits(school)
                } else {
                    tvSchoolPreview.visibility = if (code.length >= 2) View.VISIBLE else View.GONE
                    if (code.length >= 2) tvSchoolPreview.text = "School not found for code \"$code\""
                    cardBenefitsBanner.visibility = View.GONE
                }
            }
        })

        btnJoin.setOnClickListener { attemptJoin() }
        btnSkip.setOnClickListener { goHome() }
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
            Toast.makeText(this, "Enter a valid school code first", Toast.LENGTH_SHORT).show()
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

        // Check Firestore: schools/{id}/students/{username}
        // Document must have a "password" field that matches (stored by school admin)
        FirebaseFirestore.getInstance()
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
                        // Prefer name from Firestore roster; fall back to what student typed
                        val rosterName = doc.getString("name")
                        val displayName = rosterName?.takeIf { it.isNotBlank() }
                            ?: etStudentName.text.toString().trim().takeIf { it.isNotEmpty() }
                            ?: SessionManager.getStudentName(this)
                        val studentIdOverride = doc.getString("student_id")
                            ?: etStudentId.text.toString().trim().takeIf { it.isNotEmpty() }
                            ?: username
                        SessionManager.login(
                            context     = this,
                            schoolId    = school.id,
                            schoolName  = school.name,
                            studentId   = studentIdOverride,
                            studentName = displayName
                        )
                        onJoinSuccess(school, username)
                    } else {
                        Toast.makeText(
                            this,
                            "Incorrect password. Check with your teacher.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                } else {
                    Toast.makeText(
                        this,
                        "Username not found in ${school.name}. Check with your teacher.",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            .addOnFailureListener { e ->
                setLoading(false)
                Toast.makeText(this, "Could not verify: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun onJoinSuccess(school: School, username: String) {
        // Session already updated in attemptJoin with best-available name + ID
        // Save school plan to session if school has a free plan
        val freePlan = school.plans.firstOrNull { it.isFree }
        if (freePlan != null) {
            SessionManager.savePlan(this, freePlan.id, freePlan.name)
        }
        // Persist updated metadata to Firestore
        val meta = SessionManager.buildUserMetadata(this)
        FirestoreManager.saveUserMetadata(meta)
        Toast.makeText(this, "✅ Joined ${school.name}!", Toast.LENGTH_SHORT).show()
        goHome()
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

package com.example.aiguru

import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.aiguru.firestore.FirestoreManager
import com.example.aiguru.models.UserMetadata
import com.example.aiguru.utils.ConfigManager
import com.example.aiguru.utils.SessionManager
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth

class UserProfileActivity : AppCompatActivity() {

    private lateinit var userId: String
    private var loadedMetadata: UserMetadata? = null

    private lateinit var profileHeader: LinearLayout
    private lateinit var avatarText: TextView
    private lateinit var headerNameText: TextView
    private lateinit var headerSubText: TextView

    private lateinit var nameInput: EditText
    private lateinit var emailInput: EditText
    private lateinit var gradeSpinner: Spinner
    private lateinit var studentIdText: TextView
    private lateinit var schoolText: TextView
    private lateinit var currentPlanText: TextView

    private lateinit var saveProfileButton: MaterialButton
    private lateinit var managePlanButton: MaterialButton
    private lateinit var logoutButton: MaterialButton

    private val grades = arrayOf("Select Grade", "6th", "7th", "8th", "9th", "10th", "11th", "12th")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_user_profile)

        userId = SessionManager.getFirestoreUserId(this)

        bindViews()
        applyBranding()
        setupGradeSpinner()
        populateFromSession()
        loadMetadataFromFirestore()
        bindActions()
    }

    override fun onResume() {
        super.onResume()
        updatePlanViews()
    }

    private fun bindViews() {
        profileHeader = findViewById(R.id.profileHeader)
        avatarText = findViewById(R.id.profileAvatar)
        headerNameText = findViewById(R.id.profileHeaderName)
        headerSubText = findViewById(R.id.profileHeaderSub)

        nameInput = findViewById(R.id.profileNameInput)
        emailInput = findViewById(R.id.profileEmailInput)
        gradeSpinner = findViewById(R.id.profileGradeSpinner)
        studentIdText = findViewById(R.id.profileStudentId)
        schoolText = findViewById(R.id.profileSchool)
        currentPlanText = findViewById(R.id.profilePlanText)

        saveProfileButton = findViewById(R.id.saveProfileButton)
        managePlanButton = findViewById(R.id.managePlanButton)
        logoutButton = findViewById(R.id.logoutButton)
    }

    private fun applyBranding() {
        val schoolId = SessionManager.getSchoolId(this)
        val school = ConfigManager.getSchool(this, schoolId)
        val branding = school?.branding

        runCatching {
            val primaryColor = Color.parseColor(branding?.primaryColor ?: "#1565C0")
            val accentColor = Color.parseColor(branding?.accentColor ?: "#FF8F00")
            profileHeader.setBackgroundColor(primaryColor)
            saveProfileButton.backgroundTintList = ColorStateList.valueOf(primaryColor)
            managePlanButton.backgroundTintList = ColorStateList.valueOf(accentColor)
        }
    }

    private fun setupGradeSpinner() {
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, grades)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        gradeSpinner.adapter = adapter
    }

    private fun populateFromSession() {
        val studentName = SessionManager.getStudentName(this)
        val studentId = SessionManager.getStudentId(this)
        val schoolName = SessionManager.getSchoolName(this)
        val grade = SessionManager.getGrade(this)

        headerNameText.text = studentName
        headerSubText.text = if (schoolName.isBlank()) "Student Profile" else schoolName
        avatarText.text = studentName.firstOrNull()?.uppercase() ?: "S"

        nameInput.setText(studentName)
        studentIdText.text = if (studentId.isBlank()) "-" else studentId
        schoolText.text = if (schoolName.isBlank()) "-" else schoolName

        val idx = grades.indexOf(grade).coerceAtLeast(0)
        gradeSpinner.setSelection(idx)

        updatePlanViews()

        val authEmail = FirebaseAuth.getInstance().currentUser?.email.orEmpty()
        if (authEmail.isNotBlank()) emailInput.setText(authEmail)
    }

    private fun loadMetadataFromFirestore() {
        FirestoreManager.getUserMetadata(
            userId = userId,
            onSuccess = { meta ->
                loadedMetadata = meta
                runOnUiThread {
                    if (meta == null) return@runOnUiThread
                    if (meta.name.isNotBlank()) {
                        nameInput.setText(meta.name)
                        headerNameText.text = meta.name
                        avatarText.text = meta.name.firstOrNull()?.uppercase() ?: "S"
                    }
                    if (!meta.email.isNullOrBlank()) emailInput.setText(meta.email)
                    if (meta.grade.isNotBlank()) {
                        val idx = grades.indexOf(meta.grade).coerceAtLeast(0)
                        gradeSpinner.setSelection(idx)
                    }
                }
            },
            onFailure = {
                runOnUiThread {
                    Toast.makeText(this, "Could not load latest profile data", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun bindActions() {
        findViewById<View>(R.id.profileBackButton).setOnClickListener { finish() }

        saveProfileButton.setOnClickListener { saveProfile() }

        managePlanButton.setOnClickListener {
            val schoolId = resolveSchoolIdForPlans()
            if (schoolId.isBlank()) {
                Toast.makeText(this, "No school found for plan selection", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startActivity(
                Intent(this, SubscriptionActivity::class.java)
                    .putExtra("schoolId", schoolId)
            )
        }

        logoutButton.setOnClickListener { confirmLogout() }
    }

    private fun resolveSchoolIdForPlans(): String {
        val sessionSchoolId = SessionManager.getSchoolId(this)
        if (sessionSchoolId.isNotBlank()) return sessionSchoolId

        // Fallback for accounts where session schoolId is missing.
        return ConfigManager.getSchools(this).firstOrNull()?.id.orEmpty()
    }

    private fun saveProfile() {
        val name = nameInput.text.toString().trim()
        val grade = gradeSpinner.selectedItem?.toString().orEmpty()
        val email = emailInput.text.toString().trim()

        if (name.isBlank()) {
            Toast.makeText(this, "Please enter your name", Toast.LENGTH_SHORT).show()
            return
        }
        if (grade == "Select Grade") {
            Toast.makeText(this, "Please select your grade", Toast.LENGTH_SHORT).show()
            return
        }

        saveProfileButton.isEnabled = false
        saveProfileButton.text = "Saving..."

        val now = System.currentTimeMillis()
        val planId = SessionManager.getPlanId(this).ifBlank { loadedMetadata?.planId ?: "free" }
        val planName = SessionManager.getPlanName(this).ifBlank { loadedMetadata?.planName ?: "Free" }

        val updated = (loadedMetadata ?: UserMetadata(userId = userId)).copy(
            userId = userId,
            name = name,
            email = email.ifBlank { null },
            schoolId = SessionManager.getSchoolId(this),
            schoolName = SessionManager.getSchoolName(this),
            grade = grade,
            planId = planId,
            planName = planName,
            createdAt = loadedMetadata?.createdAt?.takeIf { it > 0 } ?: now,
            updatedAt = now
        )

        FirestoreManager.saveUserMetadata(
            metadata = updated,
            onSuccess = {
                SessionManager.saveStudentName(this, name)
                SessionManager.saveGrade(this, grade)
                loadedMetadata = updated
                runOnUiThread {
                    saveProfileButton.isEnabled = true
                    saveProfileButton.text = "Save Changes"
                    headerNameText.text = name
                    avatarText.text = name.firstOrNull()?.uppercase() ?: "S"
                    Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
                }
            },
            onFailure = {
                runOnUiThread {
                    saveProfileButton.isEnabled = true
                    saveProfileButton.text = "Save Changes"
                    Toast.makeText(this, "Failed to save profile", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun updatePlanViews() {
        val planName = SessionManager.getPlanName(this).ifBlank { "No plan selected" }
        currentPlanText.text = planName
    }

    private fun confirmLogout() {
        AlertDialog.Builder(this)
            .setTitle("Logout")
            .setMessage("Are you sure you want to logout?")
            .setPositiveButton("Logout") { _, _ ->
                SessionManager.logout(this)
                FirebaseAuth.getInstance().signOut()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}

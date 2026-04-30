package com.aiguruapp.student

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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.config.ReferralManager
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.models.UserMetadata
import com.aiguruapp.student.utils.ConfigManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.material.button.MaterialButton
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class UserProfileActivity : BaseActivity() {

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
    private lateinit var deleteAccountButton: MaterialButton

    private lateinit var googleClientForDelete: GoogleSignInClient

    private val reAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            try {
                val acct = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                    .getResult(ApiException::class.java)
                val credential = GoogleAuthProvider.getCredential(acct.idToken, null)
                FirebaseAuth.getInstance().currentUser
                    ?.reauthenticate(credential)
                    ?.addOnSuccessListener { proceedWithAccountDeletion() }
                    ?.addOnFailureListener {
                        runOnUiThread {
                            deleteAccountButton.isEnabled = true
                            deleteAccountButton.text = "Delete Account"
                            Toast.makeText(this, "Re-authentication failed. Please try again.", Toast.LENGTH_LONG).show()
                        }
                    }
            } catch (e: ApiException) {
                runOnUiThread {
                    deleteAccountButton.isEnabled = true
                    deleteAccountButton.text = "Delete Account"
                    Toast.makeText(this, "Google sign-in cancelled.", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            deleteAccountButton.isEnabled = true
            deleteAccountButton.text = "Delete Account"
        }
    }

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
        deleteAccountButton = findViewById(R.id.deleteAccountButton)
    }

    private fun applyBranding() {
        val schoolId = SessionManager.getSchoolId(this)
        val school = ConfigManager.getSchool(this, schoolId)
        val branding = school?.branding

        runCatching {
            val primaryColor = Color.parseColor(branding?.primaryColor ?: "#1565C0")
            val accentColor = Color.parseColor(branding?.accentColor ?: "#1A1A2E")
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
                    // Sync plan from Firestore → local session cache so it survives reinstall
                    if (meta.planId.isNotBlank()) {
                        SessionManager.savePlan(this@UserProfileActivity, meta.planId, meta.planName)
                        updatePlanViews()
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
        deleteAccountButton.setOnClickListener { confirmDeleteAccount() }

        // Rate App
        findViewById<com.google.android.material.button.MaterialButton>(R.id.rateAppButton)
            .setOnClickListener { openPlayStoreListing() }

        // ── Referral section ──────────────────────────────────────────────────
        setupReferralSection()
    }

    private fun setupReferralSection() {
        val code = ReferralManager.codeForUser(userId)

        // "Share & Earn" button — opens Android share sheet
        val shareBtn = runCatching { findViewById<MaterialButton>(R.id.referralShareButton) }.getOrNull()
        shareBtn?.setOnClickListener {
            val msg = "Join me on AiGuru 🎓 — AI-powered tutor that makes every topic click!\n" +
                    "Use my referral code $code and we both get bonus questions!\n" +
                    "Download: https://play.google.com/store/apps/details?id=com.aiguruapp.student"
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, msg)
            }, "Share AiGuru"))
        }

        // Display code
        val codeLabel = runCatching { findViewById<TextView>(R.id.referralCodeText) }.getOrNull()
        codeLabel?.text = code

        // Copy to clipboard
        val copyBtn = runCatching { findViewById<View>(R.id.referralCopyButton) }.getOrNull()
        copyBtn?.setOnClickListener {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("Referral Code", code))
            Toast.makeText(this, "Code $code copied!", Toast.LENGTH_SHORT).show()
        }

        // "Enter a friend's code" button → dialog
        val claimBtn = runCatching { findViewById<MaterialButton>(R.id.claimReferralButton) }.getOrNull()
        claimBtn?.setOnClickListener { showClaimReferralDialog() }
    }

    private fun showClaimReferralDialog() {
        val input = EditText(this).apply {
            hint = "Enter referral code (e.g. AB12CD34)"
            maxLines = 1
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                    android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
        }
        AlertDialog.Builder(this)
            .setTitle("Enter Referral Code")
            .setMessage("Get +${ReferralManager.REFERRED_BONUS} bonus questions/day!")
            .setView(input)
            .setPositiveButton("Claim") { _, _ ->
                val code = input.text.toString().trim().uppercase()
                if (code.length != 8) {
                    Toast.makeText(this, "Referral code must be exactly 8 characters", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                Thread {
                    ReferralManager.claimReferralCodeViaServer(
                        code = code,
                        onSuccess = { referrerName ->
                            runOnUiThread {
                                Toast.makeText(
                                    this,
                                    "Claimed! You and $referrerName both get +${ReferralManager.REFERRED_BONUS} BB lessons/day for 30 days!",
                                    Toast.LENGTH_LONG
                                ).show()
                                loadMetadataFromFirestore()
                            }
                        },
                        onAlreadyClaimed = {
                            runOnUiThread {
                                Toast.makeText(this, "You've already used a referral code.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onInvalid = {
                            runOnUiThread {
                                Toast.makeText(this, "Invalid referral code. Double-check and try again.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onError = {
                            runOnUiThread {
                                Toast.makeText(this, "Something went wrong. Try again later.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                }.start()
            }
            .setNegativeButton("Cancel", null)
            .show()
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
                com.aiguruapp.student.auth.TokenManager.clearCache()
                startActivity(Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                })
                finish()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmDeleteAccount() {
        AlertDialog.Builder(this)
            .setTitle("Delete Account")
            .setMessage(
                "This will permanently delete your account and all your data " +
                "(subjects, chat history, progress, subscription). " +
                "This action cannot be undone.\n\nAre you absolutely sure?"
            )
            .setPositiveButton("Yes, Delete Permanently") { _, _ ->
                deleteAccountButton.isEnabled = false
                deleteAccountButton.text = "Deleting..."
                // Google re-auth is required before Firebase allows account deletion
                val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(getString(R.string.default_web_client_id))
                    .requestEmail()
                    .build()
                googleClientForDelete = GoogleSignIn.getClient(this, gso)
                // Force fresh sign-in so we always get a valid ID token
                googleClientForDelete.signOut().addOnCompleteListener {
                    reAuthLauncher.launch(googleClientForDelete.signInIntent)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun proceedWithAccountDeletion() {
        FirestoreManager.deleteUserData(
            userId = userId,
            onSuccess = {
                FirebaseAuth.getInstance().currentUser
                    ?.delete()
                    ?.addOnSuccessListener {
                        if (::googleClientForDelete.isInitialized) googleClientForDelete.signOut()
                        SessionManager.logout(this)
                        runOnUiThread {
                            Toast.makeText(
                                this,
                                "Your account has been permanently deleted.",
                                Toast.LENGTH_LONG
                            ).show()
                            startActivity(Intent(this, LoginActivity::class.java).apply {
                                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                            })
                            finish()
                        }
                    }
                    ?.addOnFailureListener { e ->
                        runOnUiThread {
                            deleteAccountButton.isEnabled = true
                            deleteAccountButton.text = "Delete Account"
                            Toast.makeText(
                                this,
                                "Could not delete auth account: ${e.message}",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }
            },
            onFailure = { e ->
                runOnUiThread {
                    deleteAccountButton.isEnabled = true
                    deleteAccountButton.text = "Delete Account"
                    Toast.makeText(
                        this,
                        "Failed to delete account data: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun openPlayStoreListing() {
        val packageName = "com.aiguruapp.student"
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$packageName"))
                .apply { setPackage("com.android.vending") })
        } catch (e: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW,
                android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")))
        }
    }
}

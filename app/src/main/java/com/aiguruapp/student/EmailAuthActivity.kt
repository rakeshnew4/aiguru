package com.aiguruapp.student

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.aiguruapp.student.firestore.FirestoreManager
import com.aiguruapp.student.utils.SessionManager
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.FirebaseAuth

class EmailAuthActivity : BaseActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var etEmail: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var etConfirmPassword: TextInputEditText
    private lateinit var tilConfirmPassword: TextInputLayout
    private lateinit var btnSubmit: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var tvTitle: TextView
    private lateinit var tvSubtitle: TextView
    private lateinit var tvToggleText: TextView
    private lateinit var tvToggleAction: TextView
    private lateinit var tvForgotPassword: TextView

    private var isSignUp = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_email_auth)

        auth = FirebaseAuth.getInstance()

        etEmail            = findViewById(R.id.etEmail)
        etPassword         = findViewById(R.id.etPassword)
        etConfirmPassword  = findViewById(R.id.etConfirmPassword)
        tilConfirmPassword = findViewById(R.id.tilConfirmPassword)
        btnSubmit          = findViewById(R.id.btnSubmit)
        progressBar        = findViewById(R.id.progressBar)
        tvTitle            = findViewById(R.id.tvTitle)
        tvSubtitle         = findViewById(R.id.tvSubtitle)
        tvToggleText       = findViewById(R.id.tvToggleText)
        tvToggleAction     = findViewById(R.id.tvToggleAction)
        tvForgotPassword   = findViewById(R.id.tvForgotPassword)

        btnSubmit.setOnClickListener { handleSubmit() }
        tvToggleAction.setOnClickListener { toggleMode() }
        tvForgotPassword.setOnClickListener { sendPasswordReset() }
    }

    private fun toggleMode() {
        isSignUp = !isSignUp
        if (isSignUp) {
            tvTitle.text    = "Create Account"
            tvSubtitle.text = "Join AI Guru — it's free!"
            btnSubmit.text  = "Create Account"
            tvToggleText.text   = "Already have an account? "
            tvToggleAction.text = "Sign In"
            tilConfirmPassword.visibility = View.VISIBLE
            tvForgotPassword.visibility   = View.GONE
        } else {
            tvTitle.text    = "Sign In"
            tvSubtitle.text = "Welcome back to AI Guru"
            btnSubmit.text  = "Sign In"
            tvToggleText.text   = "Don't have an account? "
            tvToggleAction.text = "Create Account"
            tilConfirmPassword.visibility = View.GONE
            tvForgotPassword.visibility   = View.VISIBLE
        }
    }

    private fun handleSubmit() {
        val email    = etEmail.text.toString().trim()
        val password = etPassword.text.toString()

        if (email.isEmpty()) {
            etEmail.error = "Email is required"; return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            etEmail.error = "Enter a valid email"; return
        }
        if (password.length < 6) {
            etPassword.error = "Password must be at least 6 characters"; return
        }
        if (isSignUp) {
            val confirm = etConfirmPassword.text.toString()
            if (confirm != password) {
                etConfirmPassword.error = "Passwords do not match"; return
            }
            createAccount(email, password)
        } else {
            signIn(email, password)
        }
    }

    private fun signIn(email: String, password: String) {
        setLoading(true)
        auth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                if (!task.isSuccessful) {
                    setLoading(false)
                    Toast.makeText(this, "Sign in failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }
                val user = task.result.user!!
                // Try to restore metadata from Firestore
                FirestoreManager.getUserMetadata(user.uid,
                    onSuccess = { metadata ->
                        if (metadata != null) {
                            // Returning user — restore everything from Firestore
                            SessionManager.login(
                                context     = this,
                                schoolId    = metadata.schoolId.ifBlank { "email" },
                                schoolName  = metadata.schoolName.ifBlank { "Email Account" },
                                studentId   = metadata.email ?: user.email ?: user.uid,
                                studentName = metadata.name.ifBlank { "Student" }
                            )
                            SessionManager.saveGrade(this, metadata.grade)
                            SessionManager.saveFirebaseUid(this, user.uid)
                            SessionManager.completeSignup(this)
                        } else {
                            // Email user with no Firestore doc (possibly created outside app)
                            SessionManager.login(
                                context     = this,
                                schoolId    = "email",
                                schoolName  = "Email Account",
                                studentId   = user.email ?: user.uid,
                                studentName = user.displayName ?: "Student"
                            )
                            SessionManager.saveFirebaseUid(this, user.uid)
                        }
                        setLoading(false)
                        goHome()
                    },
                    onFailure = { e ->
                        // Firestore failed — proceed with minimal session
                        SessionManager.login(
                            context     = this,
                            schoolId    = "email",
                            schoolName  = "Email Account",
                            studentId   = user.email ?: user.uid,
                            studentName = user.displayName ?: "Student"
                        )
                        SessionManager.saveFirebaseUid(this, user.uid)
                        setLoading(false)
                        goHome()
                    }
                )
            }
    }

    private fun createAccount(email: String, password: String) {
        setLoading(true)
        auth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener(this) { task ->
                setLoading(false)
                if (!task.isSuccessful) {
                    Toast.makeText(this, "Sign up failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                    return@addOnCompleteListener
                }
                val user = task.result.user!!
                SessionManager.login(
                    context     = this,
                    schoolId    = "email",
                    schoolName  = "Email Account",
                    studentId   = user.email ?: user.uid,
                    studentName = "Student"
                )
                SessionManager.saveFirebaseUid(this, user.uid)
                // New user — send to profile completion
                goHome()
            }
    }

    private fun sendPasswordReset() {
        val email = etEmail.text.toString().trim()
        if (email.isEmpty()) {
            etEmail.error = "Enter your email first"; return
        }
        auth.sendPasswordResetEmail(email)
            .addOnSuccessListener {
                Toast.makeText(this, "Password reset email sent to $email", Toast.LENGTH_LONG).show()
            }
            .addOnFailureListener { e ->
                Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
            }
    }

    private fun goHome() {
        val nextActivity = if (SessionManager.isSignupComplete(this)) {
            HomeActivity::class.java
        } else {
            SignupActivity::class.java
        }
        startActivity(Intent(this, nextActivity).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    private fun setLoading(loading: Boolean) {
        btnSubmit.isEnabled    = !loading
        progressBar.visibility = if (loading) View.VISIBLE else View.GONE
    }
}

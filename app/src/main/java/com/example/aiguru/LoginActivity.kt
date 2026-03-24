package com.example.aiguru

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.aiguru.utils.SessionManager
import com.example.aiguru.SchoolLoginActivity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
class LoginActivity : AppCompatActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInButton: Button
    private lateinit var loadingBar: ProgressBar

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                val displayName = account.displayName ?: "Student"
                val email = account.email ?: account.id ?: "google_user"
                bridgeFirebaseUser(displayName, email)
                setLoading(false)
                goHome()
            } catch (e: ApiException) {
                setLoading(false)
                Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        signInButton = findViewById(R.id.googleSignInButton)
        loadingBar   = findViewById<ProgressBar?>(R.id.loginProgressBar) ?: ProgressBar(this)

        // Already logged in — go straight home
        if (SessionManager.isLoggedIn(this)) {
            goHome(); return
        }

        // Check if Google account is already signed in
        val lastAccount = GoogleSignIn.getLastSignedInAccount(this)
        if (lastAccount != null) {
            bridgeFirebaseUser(lastAccount.displayName ?: "Student", lastAccount.email ?: lastAccount.id ?: "google_user")
            goHome(); return
        }

        // Use basic Google Sign-In (no idToken needed — we don't use Firebase Auth backend)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        signInButton.setOnClickListener {
            setLoading(true)
            signInLauncher.launch(googleSignInClient.signInIntent)
        }

        // School login is always available
        findViewById<Button>(R.id.schoolLoginButton).setOnClickListener {
            startActivity(Intent(this, SchoolLoginActivity::class.java))
        }

        // Email sign-in
        findViewById<Button>(R.id.emailSignInButton).setOnClickListener {
            startActivity(Intent(this, EmailAuthActivity::class.java))
        }
    }



    /**
     * Creates a SessionManager session from a Firebase Auth user so that
     * HomeActivity's SessionManager.isLoggedIn() check passes.
     */
    private fun bridgeFirebaseUser(displayName: String, email: String) {
        SessionManager.login(
            context     = this,
            schoolId    = "google",
            schoolName  = "Google Account",
            studentId   = email,
            studentName = displayName
        )
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
        signInButton.isEnabled = !loading
        loadingBar.visibility  = if (loading) View.VISIBLE else View.GONE
    }
}
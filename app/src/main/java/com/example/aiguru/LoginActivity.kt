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
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class LoginActivity : AppCompatActivity() {

    private lateinit var auth: FirebaseAuth
    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInButton: Button
    private lateinit var loadingBar: ProgressBar

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
            try {
                val account = task.getResult(ApiException::class.java)
                firebaseAuthWithGoogle(account.idToken!!)
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

        auth = FirebaseAuth.getInstance()

        // If already signed in to Firebase, bridge into SessionManager and proceed
        val fbUser = auth.currentUser
        if (fbUser != null && SessionManager.isLoggedIn(this)) {
            goHome(); return
        }
        if (fbUser != null) {
            // Firebase session exists but no SessionManager session — bridge it
            bridgeFirebaseUser(fbUser.displayName ?: "Student", fbUser.email ?: fbUser.uid)
            goHome(); return
        }

        val webClientId = try { getString(R.string.default_web_client_id) } catch (_: Exception) { "" }
        if (webClientId.isEmpty()) {
            // Google Sign-In not yet configured — disable that button but keep school login available
            signInButton.isEnabled = false
            signInButton.alpha = 0.4f
        } else {
            val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestIdToken(webClientId)
                .requestEmail()
                .build()
            googleSignInClient = GoogleSignIn.getClient(this, gso)

            signInButton.setOnClickListener {
                setLoading(true)
                signInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        // School login is always available
        findViewById<Button>(R.id.schoolLoginButton).setOnClickListener {
            startActivity(Intent(this, SchoolLoginActivity::class.java))
        }
    }

    private fun firebaseAuthWithGoogle(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnCompleteListener(this) { task ->
                setLoading(false)
                if (task.isSuccessful) {
                    val user = auth.currentUser!!
                    bridgeFirebaseUser(user.displayName ?: "Student", user.email ?: user.uid)
                    goHome()
                } else {
                    Toast.makeText(this, "Authentication failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                }
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
        startActivity(Intent(this, HomeActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        })
    }

    private fun setLoading(loading: Boolean) {
        signInButton.isEnabled = !loading
        loadingBar.visibility  = if (loading) View.VISIBLE else View.GONE
    }
}
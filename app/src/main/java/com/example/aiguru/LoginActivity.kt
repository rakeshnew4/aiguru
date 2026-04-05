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
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
class LoginActivity : BaseActivity() {

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInButton: Button
    private lateinit var loadingBar: ProgressBar

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val displayName = account.displayName ?: "Student"
                val email = account.email ?: account.id ?: "google_user"
                bridgeFirebaseUser(displayName, email) {
                    setLoading(false)
                    goHome()
                }
            } catch (e: ApiException) {
                setLoading(false)
                Toast.makeText(this, "Google sign in failed (code ${e.statusCode})", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                // SecurityException thrown when Google Play Services broker is unavailable
                setLoading(false)
                Toast.makeText(this, "Google services unavailable. Use School or Email login.", Toast.LENGTH_LONG).show()
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

        // Check if Google account is already signed in (guard against missing GMS)
        val lastAccount = try { GoogleSignIn.getLastSignedInAccount(this) } catch (_: Exception) { null }
        if (lastAccount != null) {
            bridgeFirebaseUser(lastAccount.displayName ?: "Student", lastAccount.email ?: lastAccount.id ?: "google_user") {
                goHome()
            }
            return
        }

        // Use basic Google Sign-In (no idToken needed — we don't use Firebase Auth backend)
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestProfile()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        signInButton.setOnClickListener {
            val availability = GoogleApiAvailability.getInstance()
                .isGooglePlayServicesAvailable(this)
            if (availability != ConnectionResult.SUCCESS) {
                Toast.makeText(this, "Google Play Services not available. Use School or Email login.", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
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
    private fun bridgeFirebaseUser(displayName: String, email: String, onDone: () -> Unit = {}) {
        SessionManager.login(
            context     = this,
            schoolId    = "google",
            schoolName  = "Google Account",
            studentId   = email,
            studentName = displayName
        )
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            SessionManager.saveFirebaseUid(this, auth.currentUser!!.uid)
            onDone()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { result ->
                    result.user?.uid?.let { uid -> SessionManager.saveFirebaseUid(this, uid) }
                    onDone()
                }
                .addOnFailureListener { onDone() }
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
        signInButton.isEnabled = !loading
        loadingBar.visibility  = if (loading) View.VISIBLE else View.GONE
    }
}
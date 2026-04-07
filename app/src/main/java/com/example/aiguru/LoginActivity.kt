package com.example.aiguru

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import com.example.aiguru.utils.SessionManager
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider

class LoginActivity : BaseActivity() {

    companion object {
        private const val TAG = "LoginActivity"
        // Web client (type 3) from Google Cloud Console / Firebase Auth → Google Sign-In
        private const val WEB_CLIENT_ID =
            "162570108156-u53umd4n0se88sp5dumhg8ugu1uco89j.apps.googleusercontent.com"
    }

    private lateinit var googleSignInClient: GoogleSignInClient
    private lateinit var signInButton: Button
    private lateinit var loadingBar: ProgressBar
    private val auth = FirebaseAuth.getInstance()

    private val signInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            try {
                val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
                val account = task.getResult(ApiException::class.java)
                val idToken = account.idToken ?: run {
                    setLoading(false)
                    Toast.makeText(this, "Google sign in failed: no ID token", Toast.LENGTH_LONG).show()
                    return@registerForActivityResult
                }
                firebaseSignIn(idToken)
            } catch (e: ApiException) {
                Log.e(TAG, "Google sign-in failed, code=${e.statusCode}", e)
                setLoading(false)
                Toast.makeText(this, "Google sign in failed (code ${e.statusCode})", Toast.LENGTH_SHORT).show()
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

        // If Firebase already has a real (non-anonymous) Google user, restore session
        val currentUser = auth.currentUser
        if (currentUser != null && !currentUser.isAnonymous) {
            Log.d(TAG, "Restoring existing Firebase session: uid=${currentUser.uid}")
            SessionManager.login(
                context     = this,
                schoolId    = "google",
                schoolName  = "Google Account",
                studentId   = currentUser.email ?: currentUser.uid,
                studentName = currentUser.displayName ?: "Student"
            )
            SessionManager.saveFirebaseUid(this, currentUser.uid)
            goHome()
            return
        }

        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        googleSignInClient = GoogleSignIn.getClient(this, gso)

        signInButton.setOnClickListener {
            setLoading(true)
            googleSignInClient.signOut().addOnCompleteListener {
                signInLauncher.launch(googleSignInClient.signInIntent)
            }
        }

        // School login
        findViewById<Button>(R.id.schoolLoginButton).setOnClickListener {
            startActivity(Intent(this, SchoolLoginActivity::class.java))
        }

        // Email sign-in
        findViewById<Button>(R.id.emailSignInButton).setOnClickListener {
            startActivity(Intent(this, EmailAuthActivity::class.java))
        }
    }

    private fun firebaseSignIn(idToken: String) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { authResult ->
                val user = authResult.user ?: run {
                    setLoading(false)
                    Toast.makeText(this, "Sign in failed. Please try again.", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }
                Log.d(TAG, "Firebase sign-in success: uid=${user.uid}")
                SessionManager.login(
                    context     = this,
                    schoolId    = "google",
                    schoolName  = "Google Account",
                    studentId   = user.email ?: user.uid,
                    studentName = user.displayName ?: "Student"
                )
                SessionManager.saveFirebaseUid(this, user.uid)
                setLoading(false)
                goHome()
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Firebase credential sign-in failed", e)
                setLoading(false)
                Toast.makeText(this, "Sign in failed: ${e.message}", Toast.LENGTH_LONG).show()
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
